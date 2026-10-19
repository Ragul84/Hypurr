//! A small, conservative shell reader for approval checks.
//!
//! It splits a command line into simple commands across `;`, `&&`, `||`, `|`, `&`,
//! newlines and `( … )` subshells, pulls `$( … )`, backticks and `<( … )` out as their
//! own commands, notes `>`/`>>` write targets, and tracks the working folder across
//! `cd` / `pushd` so `cd ..; rm x` is judged where `rm` really runs. Anything it
//! can't know (a `$VAR`, a substitution's output, `cd -`) is marked *dynamic* or
//! makes the folder unknown, and the risk rules treat that as "ask the person".

use std::path::PathBuf;

/// One word of a simple command.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Word {
    pub text: String,
    /// Part of it comes from a variable or a substitution, so its value is unknown.
    pub dynamic: bool,
}

impl Word {
    pub fn lit(s: &str) -> Self {
        Self { text: s.to_owned(), dynamic: false }
    }
}

/// One simple command and where it runs.
#[derive(Clone, Debug, Default)]
pub struct Simple {
    pub words: Vec<Word>,
    /// Folder it runs in; `None` = unknown (after `cd -`, `cd $X`, `popd`, …).
    pub cwd: Option<PathBuf>,
    /// Files it writes through `>` / `>>` / `&>`.
    pub writes: Vec<Word>,
    /// The line had syntax Hypurr can't follow (unbalanced quotes or brackets).
    pub unsure: bool,
}

const MAX_DEPTH: usize = 6;

/// Splits `cmd` into simple commands, starting in folder `cwd`.
pub fn parse(cmd: &str, cwd: Option<PathBuf>) -> Vec<Simple> {
    let mut p = Parser { out: Vec::new(), stack: Vec::new(), cwd, depth: 0 };
    p.run(cmd);
    p.out
}

struct Parser {
    out: Vec<Simple>,
    stack: Vec<Option<PathBuf>>,
    cwd: Option<PathBuf>,
    depth: usize,
}

#[derive(Default)]
struct Cur {
    words: Vec<Word>,
    writes: Vec<Word>,
    buf: String,
    started: bool,
    dynamic: bool,
    /// The next word is a redirect target: 1 = write, 2 = read / ignored.
    redirect: u8,
    unsure: bool,
}

impl Cur {
    fn end_word(&mut self) {
        if !self.started {
            return;
        }
        let w = Word { text: std::mem::take(&mut self.buf), dynamic: self.dynamic };
        match self.redirect {
            1 => self.writes.push(w),
            2 => {}
            _ => self.words.push(w),
        }
        self.redirect = 0;
        self.started = false;
        self.dynamic = false;
    }
}

/// Text up to the `close` that balances an already-consumed opener; quotes respected.
fn take_balanced(chars: &mut std::iter::Peekable<std::str::Chars>, open: char, close: char) -> Option<String> {
    let mut depth = 1usize;
    let mut s = String::new();
    let (mut single, mut double) = (false, false);
    while let Some(c) = chars.next() {
        match c {
            '\\' if !single => {
                s.push(c);
                if let Some(n) = chars.next() {
                    s.push(n);
                }
                continue;
            }
            '\'' if !double => single = !single,
            '"' if !single => double = !double,
            c if c == open && !single && !double => depth += 1,
            c if c == close && !single && !double => {
                depth -= 1;
                if depth == 0 {
                    return Some(s);
                }
            }
            _ => {}
        }
        s.push(c);
    }
    None
}

fn home() -> String {
    dirs::home_dir().map_or_else(|| "~".into(), |h| h.to_string_lossy().into_owned())
}

impl Parser {
    fn sub(&mut self, inner: &str) {
        if self.depth >= MAX_DEPTH {
            self.out.push(Simple { unsure: true, cwd: self.cwd.clone(), ..Default::default() });
            return;
        }
        // A substitution runs in a subshell: its `cd` doesn't leak out.
        let saved = self.cwd.clone();
        self.depth += 1;
        self.run(inner);
        self.depth -= 1;
        self.cwd = saved;
    }

    /// `$…` after the `$` was consumed.
    fn dollar(&mut self, chars: &mut std::iter::Peekable<std::str::Chars>, cur: &mut Cur) {
        cur.started = true;
        match chars.peek().copied() {
            Some('(') => {
                chars.next();
                if chars.peek() == Some(&'(') {
                    chars.next();
                    let _ = take_balanced(chars, '(', ')');
                    let _ = chars.next_if_eq(&')');
                    cur.dynamic = true;
                } else {
                    match take_balanced(chars, '(', ')') {
                        Some(inner) => self.sub(&inner),
                        None => cur.unsure = true,
                    }
                    cur.dynamic = true;
                }
            }
            Some('{') => {
                chars.next();
                let name = take_balanced(chars, '{', '}').unwrap_or_default();
                if name == "HOME" {
                    cur.buf.push_str(&home());
                } else {
                    cur.dynamic = true;
                }
            }
            Some(c) if c.is_ascii_alphanumeric() || c == '_' => {
                let mut name = String::new();
                while let Some(&c) = chars.peek() {
                    if c.is_ascii_alphanumeric() || c == '_' {
                        name.push(c);
                        chars.next();
                    } else {
                        break;
                    }
                }
                if name == "HOME" {
                    cur.buf.push_str(&home());
                } else {
                    cur.dynamic = true;
                }
            }
            Some('?' | '$' | '!' | '#' | '@' | '*' | '-') => {
                chars.next();
                cur.dynamic = true;
            }
            _ => cur.buf.push('$'),
        }
    }

    fn backtick(&mut self, chars: &mut std::iter::Peekable<std::str::Chars>, cur: &mut Cur) {
        let mut inner = String::new();
        let mut closed = false;
        while let Some(c) = chars.next() {
            match c {
                '\\' => {
                    if let Some(n) = chars.next() {
                        inner.push(n);
                    }
                }
                '`' => {
                    closed = true;
                    break;
                }
                _ => inner.push(c),
            }
        }
        if !closed {
            cur.unsure = true;
        }
        self.sub(&inner);
        cur.started = true;
        cur.dynamic = true;
    }

    fn finish(&mut self, cur: &mut Cur) {
        cur.end_word();
        let mut words = std::mem::take(&mut cur.words);
        let writes = std::mem::take(&mut cur.writes);
        let unsure = std::mem::take(&mut cur.unsure);
        *cur = Cur::default();
        // `{ a; }` group braces are just grouping.
        words.retain(|w| w.dynamic || (w.text != "{" && w.text != "}"));
        if words.is_empty() && writes.is_empty() && !unsure {
            return;
        }
        let here = self.cwd.clone();
        // Track the folder for what comes next. Leading `X=1` assignments don't matter here.
        let first = words.iter().position(|w| w.dynamic || !is_assignment(&w.text));
        if let Some(i) = first {
            let w = &words[i];
            if !w.dynamic && matches!(w.text.as_str(), "cd" | "pushd" | "popd") {
                let arg = words[i + 1..].iter().find(|a| a.dynamic || !a.text.starts_with('-') || a.text == "-");
                self.cwd = match (w.text.as_str(), arg) {
                    ("popd", _) => None,
                    (_, None) => dirs::home_dir(),
                    (_, Some(a)) if a.dynamic || a.text == "-" => None,
                    (_, Some(a)) => self.cwd.as_ref().and_then(|c| super::scope::real_path(c, &a.text)),
                };
                // `cd /abs` works even from an unknown folder.
                if self.cwd.is_none()
                    && let Some(a) = arg.filter(|a| !a.dynamic && (a.text.starts_with('/') || a.text.starts_with('~')))
                {
                    self.cwd = super::scope::real_path(std::path::Path::new("/"), &a.text);
                }
            }
        }
        self.out.push(Simple { words, cwd: here, writes, unsure });
    }

    fn run(&mut self, cmd: &str) {
        let mut chars = cmd.chars().peekable();
        let mut cur = Cur::default();
        while let Some(c) = chars.next() {
            match c {
                '\\' => {
                    cur.started = true;
                    match chars.next() {
                        Some('\n') | None => {}
                        Some(n) => cur.buf.push(n),
                    }
                }
                '\'' => {
                    cur.started = true;
                    let mut closed = false;
                    for n in chars.by_ref() {
                        if n == '\'' {
                            closed = true;
                            break;
                        }
                        cur.buf.push(n);
                    }
                    if !closed {
                        cur.unsure = true;
                    }
                }
                '"' => {
                    cur.started = true;
                    let mut closed = false;
                    while let Some(n) = chars.next() {
                        match n {
                            '"' => {
                                closed = true;
                                break;
                            }
                            '\\' => {
                                if let Some(e) = chars.next() {
                                    if !matches!(e, '"' | '\\' | '$' | '`' | '\n') {
                                        cur.buf.push('\\');
                                    }
                                    if e != '\n' {
                                        cur.buf.push(e);
                                    }
                                }
                            }
                            '$' => self.dollar(&mut chars, &mut cur),
                            '`' => self.backtick(&mut chars, &mut cur),
                            _ => cur.buf.push(n),
                        }
                    }
                    if !closed {
                        cur.unsure = true;
                    }
                }
                '$' => self.dollar(&mut chars, &mut cur),
                '`' => self.backtick(&mut chars, &mut cur),
                '#' if !cur.started => {
                    for n in chars.by_ref() {
                        if n == '\n' {
                            break;
                        }
                    }
                    self.finish(&mut cur);
                }
                '<' | '>' if chars.peek() == Some(&'(') => {
                    chars.next();
                    match take_balanced(&mut chars, '(', ')') {
                        Some(inner) => self.sub(&inner),
                        None => cur.unsure = true,
                    }
                    cur.started = true;
                    cur.dynamic = true;
                }
                '>' | '<' => {
                    // `2>` / `1>`: the digits name a stream, not a word.
                    if cur.started && !cur.dynamic && !cur.buf.is_empty() && cur.buf.chars().all(|d| d.is_ascii_digit())
                    {
                        cur.buf.clear();
                        cur.started = false;
                    }
                    cur.end_word();
                    if c == '<' {
                        // `<<EOF`, `<<<`, `< file`: an input, not a change.
                        while chars.next_if(|n| *n == '<' || *n == '-').is_some() {}
                        cur.redirect = 2;
                    } else {
                        let _ = chars.next_if(|n| *n == '>' || *n == '|');
                        if chars.next_if_eq(&'&').is_some() {
                            // `>&2`: duplicating a stream.
                            cur.redirect = 2;
                        } else {
                            cur.redirect = 1;
                        }
                    }
                }
                '&' if chars.peek() == Some(&'>') => {
                    chars.next();
                    let _ = chars.next_if_eq(&'>');
                    cur.end_word();
                    cur.redirect = 1;
                }
                ';' | '&' | '|' | '\n' => {
                    let _ = chars.next_if(|n| matches!(n, '&' | '|' | ';'));
                    self.finish(&mut cur);
                }
                '(' if !cur.started && cur.words.is_empty() => {
                    self.finish(&mut cur);
                    self.stack.push(self.cwd.clone());
                }
                ')' => {
                    self.finish(&mut cur);
                    match self.stack.pop() {
                        Some(c) => self.cwd = c,
                        None => self.out.push(Simple { unsure: true, cwd: self.cwd.clone(), ..Default::default() }),
                    }
                }
                c if c.is_whitespace() => cur.end_word(),
                _ => {
                    cur.started = true;
                    cur.buf.push(c);
                }
            }
        }
        if !self.stack.is_empty() && self.depth == 0 {
            cur.unsure = true;
            self.stack.clear();
        }
        self.finish(&mut cur);
    }
}

pub fn is_assignment(w: &str) -> bool {
    match w.split_once('=') {
        Some((name, _)) => {
            !name.is_empty()
                && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
                && !name.starts_with(|c: char| c.is_ascii_digit())
        }
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn real(p: &str) -> Option<PathBuf> {
        super::super::scope::real_path(std::path::Path::new("/"), p)
    }

    fn texts(s: &Simple) -> Vec<&str> {
        s.words.iter().map(|w| w.text.as_str()).collect()
    }

    #[test]
    fn splits_on_operators() {
        let p = parse("a 1 && b; c | d || e & f\ng", Some("/r".into()));
        let names: Vec<&str> = p.iter().map(|s| s.words[0].text.as_str()).collect();
        assert_eq!(names, ["a", "b", "c", "d", "e", "f", "g"]);
    }

    #[test]
    fn tracks_cd() {
        let p = parse("cd src && ls; cd ..; cd ..; rm x", Some("/r".into()));
        assert_eq!(p[1].cwd.as_deref(), Some(std::path::Path::new("/r/src")));
        assert_eq!(p[4].cwd.as_deref(), Some(std::path::Path::new("/")));
        let p = parse("cd -; rm x", Some("/r".into()));
        assert_eq!(p[1].cwd, None);
        let p = parse("cd $X; cd /tmp; rm y", Some("/r".into()));
        assert_eq!(p[2].cwd, real("/tmp"), "macOS: /tmp is a link to /private/tmp");
    }

    #[test]
    fn subshell_cd_does_not_leak() {
        let p = parse("(cd /tmp; rm a); rm b", Some("/r".into()));
        assert_eq!(p[1].cwd, real("/tmp"));
        assert_eq!(p[2].cwd.as_deref(), Some(std::path::Path::new("/r")));
    }

    #[test]
    fn substitutions_are_dynamic_and_parsed() {
        let p = parse("rm $(echo ..)/x", Some("/r".into()));
        assert_eq!(texts(&p[0]), ["echo", ".."]);
        assert_eq!(p[1].words[1].text, "/x");
        assert!(p[1].words[1].dynamic);
        let p = parse("rm `pwd`/a \"$HOME/b\" $X", Some("/r".into()));
        let rm = p.iter().find(|s| s.words[0].text == "rm").unwrap();
        assert!(rm.words[1].dynamic && !rm.words[2].dynamic && rm.words[3].dynamic);
    }

    #[test]
    fn redirects() {
        let p = parse("echo hi > ../out.txt 2>&1; cat < in.txt 2>/dev/null", Some("/r".into()));
        assert_eq!(texts(&p[0]), ["echo", "hi"]);
        assert_eq!(p[0].writes[0].text, "../out.txt");
        assert_eq!(texts(&p[1]), ["cat"]);
        assert_eq!(p[1].writes[0].text, "/dev/null");
    }

    #[test]
    fn quotes() {
        let p = parse("sh -c 'rm -rf ../z; ls' && echo \"a;b\"", Some("/r".into()));
        assert_eq!(texts(&p[0]), ["sh", "-c", "rm -rf ../z; ls"]);
        assert_eq!(texts(&p[1]), ["echo", "a;b"]);
        assert!(parse("echo 'oops", None)[0].unsure);
    }
}
