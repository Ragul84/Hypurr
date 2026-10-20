//! Explain-as-you-go approvals: turns an agent's permission request into one plain
//! sentence and a risk level (low / medium / high) from fixed rules, and decides when
//! the safety net refuses a request outright (pushes to protected branches, force pushes).
//! Rules are deliberately simple and conservative: anything unrecognised is medium.

use super::shell::{self, Simple, Word};
use serde::{Deserialize, Serialize};
use std::path::Path;

/// How risky a request is (wire values `low` / `medium` / `high`).
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Default)]
#[serde(rename_all = "lowercase")]
pub enum Risk {
    Low,
    #[default]
    Medium,
    High,
}

/// What the approval card shows.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Assessment {
    pub risk: Risk,
    /// One plain sentence: "The agent wants to delete the folder build."
    pub explain: String,
    /// Why it got that level, plain words, most serious first.
    pub reasons: Vec<String>,
    /// Set when the safety net refuses the request without asking (plain reason).
    pub blocked: Option<String>,
    /// Always surface a Needs-you card (outside project folder, deletes, pushes) — even if the bot is set to auto-approve.
    #[serde(default)]
    pub must_ask: bool,
}

/// A permission request, as the bot actor sees it.
pub struct Request<'a> {
    pub tool_kind: &'a str,
    pub title: &'a str,
    pub command: Option<&'a str>,
    /// Files the request would change (diff paths).
    pub paths: &'a [String],
    pub cwd: &'a str,
    pub protected: &'a [String],
    /// Refuse pushes to protected branches and force pushes.
    pub block_protected: bool,
}

struct Finding {
    risk: Risk,
    /// Must reach the person even on auto-approve bots (outside the folder, deletes, pushes).
    ask: bool,
    /// Completes "The agent wants to …".
    action: String,
    reason: Option<String>,
    blocked: Option<String>,
}

impl Finding {
    fn new(risk: Risk, action: impl Into<String>) -> Self {
        Self { risk, ask: risk >= Risk::High, action: action.into(), reason: None, blocked: None }
    }
    fn ask(mut self) -> Self {
        self.ask = true;
        self
    }
    fn because(mut self, reason: impl Into<String>) -> Self {
        self.reason = Some(reason.into());
        self
    }
}

pub fn assess(req: &Request) -> Assessment {
    let mut findings: Vec<Finding> = Vec::new();
    if let Some(cmd) = req.command.map(str::trim).filter(|c| !c.is_empty()) {
        let scoped = !req.cwd.trim().is_empty();
        let root = if scoped { super::scope::real_path(Path::new("/"), req.cwd.trim()) } else { None };
        let ctx = Ctx { scope: super::scope::Scope::new(req.cwd), scoped, root: root.clone(), req };
        ctx.command(cmd, root, 0, &mut findings);
    }
    if !req.paths.is_empty() {
        findings.push(paths_finding(req.paths, req.cwd));
    }
    if findings.is_empty() {
        findings.push(kind_finding(req.tool_kind, req.title));
    }
    // The most serious finding speaks; ties keep the first (command order).
    let top = findings
        .iter()
        .enumerate()
        .max_by(|(ia, a), (ib, b)| a.risk.cmp(&b.risk).then(ib.cmp(ia)))
        .map_or(0, |(i, _)| i);
    let risk = findings[top].risk;
    let mut actions: Vec<&str> = vec![findings[top].action.as_str()];
    for (i, f) in findings.iter().enumerate() {
        if i != top && f.risk >= Risk::Medium && !actions.contains(&f.action.as_str()) && actions.len() < 3 {
            actions.push(f.action.as_str());
        }
    }
    let explain = format!("The agent wants to {}.", join_and(&actions));
    let mut ranked: Vec<&Finding> = findings.iter().collect();
    ranked.sort_by_key(|f| std::cmp::Reverse(f.risk));
    let mut reasons: Vec<String> = Vec::new();
    for r in ranked.iter().filter_map(|f| f.reason.clone()) {
        if !reasons.contains(&r) {
            reasons.push(r);
        }
    }
    let blocked = ranked.iter().find_map(|f| f.blocked.clone());
    let must_ask = blocked.is_none() && findings.iter().any(|f| f.ask);
    Assessment { risk, explain, reasons, blocked, must_ask }
}

fn join_and(items: &[&str]) -> String {
    match items {
        [] => String::new(),
        [one] => (*one).to_owned(),
        [init @ .., last] => format!("{}, then {last}", init.join(", ")),
    }
}

const SECRET_HINTS: &[&str] = &[
    ".env",
    "id_rsa",
    "id_ed25519",
    ".pem",
    ".key",
    ".p12",
    ".pfx",
    "credentials",
    "secret",
    "token",
    ".npmrc",
    ".pypirc",
    ".netrc",
    ".aws",
    ".ssh",
    ".kube",
    "kubeconfig",
    ".git-credentials",
];

fn looks_secret(p: &str) -> bool {
    let lower = p.to_lowercase();
    let name = Path::new(&lower).file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    // `.env.example` style files are documentation, not secrets.
    if name.ends_with(".example") || name.ends_with(".sample") || name.ends_with(".template") {
        return false;
    }
    SECRET_HINTS.iter().any(|h| {
        if h.starts_with('.') && !h[1..].contains('.') {
            name == *h
                || name.starts_with(&format!("{h}."))
                || lower.contains(&format!("/{h}/"))
                || lower.starts_with(&format!("{h}/"))
        } else {
            name.contains(h)
        }
    })
}

/// Whether `p` falls outside the task's project folder `cwd` (symlinks, `..` and
/// absolute paths resolved on the real filesystem; see [`super::scope`]).
fn outside(cwd: &str, p: &str) -> bool {
    !super::scope::Scope::new(cwd).contains(p)
}

fn short_list(items: &[String]) -> String {
    let names: Vec<String> = items
        .iter()
        .map(|p| Path::new(p).file_name().map_or_else(|| p.clone(), |n| n.to_string_lossy().into_owned()))
        .collect();
    match names.len() {
        0 => String::new(),
        1..=3 => names.join(", "),
        n => format!("{} and {} more", names[..2].join(", "), n - 2),
    }
}

fn paths_finding(paths: &[String], cwd: &str) -> Finding {
    let n = paths.len();
    let files = if n == 1 { "1 file".to_owned() } else { format!("{n} files") };
    let out: Vec<String> = paths.iter().filter(|p| outside(cwd, p)).cloned().collect();
    if !out.is_empty() {
        return Finding::new(Risk::High, format!("change {} outside this project", short_list(&out)))
            .because("It changes files outside the project folder, where the safety net can't undo it.")
            .ask();
    }
    let secret: Vec<String> = paths.iter().filter(|p| looks_secret(p)).cloned().collect();
    if !secret.is_empty() {
        return Finding::new(Risk::High, format!("change {}", short_list(&secret)))
            .because("That file may hold passwords or keys.");
    }
    let sensitive = paths.iter().any(|p| {
        let l = p.to_lowercase();
        l.contains(".github/workflows")
            || l.ends_with("dockerfile")
            || l.contains("deploy")
            || l.ends_with(".gitlab-ci.yml")
    });
    if sensitive {
        return Finding::new(Risk::Medium, format!("edit {files} ({})", short_list(paths)))
            .because("It changes build or deployment settings.");
    }
    Finding::new(Risk::Low, format!("edit {files} ({})", short_list(paths)))
        .because("Edits inside the project can be undone from a checkpoint.")
}

fn kind_finding(kind: &str, title: &str) -> Finding {
    let what = if title.is_empty() { "use a tool".to_owned() } else { format!("use a tool: {title}") };
    match kind {
        "read" | "search" | "think" => {
            Finding::new(Risk::Low, format!("look at files ({title})")).because("It only reads; nothing changes.")
        }
        "fetch" => Finding::new(Risk::Medium, format!("open a web page ({title})")).because("It reaches the internet."),
        "delete" => Finding::new(Risk::High, format!("delete something ({title})")).because("It deletes files.").ask(),
        "edit" | "move" => Finding::new(Risk::Low, format!("change files ({title})"))
            .because("Edits inside the project can be undone from a checkpoint."),
        _ => Finding::new(Risk::Medium, what).because("Hypurr doesn't recognise this tool, so it asks to be safe."),
    }
}

/// Words after `git`, skipping `-C dir` / `-c k=v` style options.
fn git_args(w: &[String]) -> Vec<&str> {
    let mut out = Vec::new();
    let mut i = 1;
    while i < w.len() {
        let a = w[i].as_str();
        if out.is_empty() && (a == "-C" || a == "-c") {
            i += 2;
            continue;
        }
        if out.is_empty() && a.starts_with('-') {
            i += 1;
            continue;
        }
        out.push(a);
        i += 1;
    }
    out
}

fn is_protected(branch: &str, protected: &[String]) -> bool {
    let b = branch.trim_start_matches('+').rsplit(':').next().unwrap_or(branch).trim_start_matches("refs/heads/");
    protected.iter().any(|p| match p.strip_suffix('*') {
        Some(prefix) => b.starts_with(prefix),
        None => b == p,
    })
}

fn git_finding(args: &[&str], req: &Request) -> Finding {
    let sub = args.first().copied().unwrap_or("");
    let rest: Vec<&str> = args.iter().skip(1).copied().collect();
    let has = |f: &str| rest.contains(&f);
    let positional: Vec<&str> = rest.iter().copied().filter(|a| !a.starts_with('-')).collect();
    match sub {
        "status" | "diff" | "log" | "show" | "blame" | "branch" if sub != "branch" || positional.is_empty() => {
            Finding::new(Risk::Low, "look at the project history").because("It only reads; nothing changes.")
        }
        "push" => {
            let force = rest.iter().any(|a| {
                matches!(*a, "-f" | "--force" | "--force-with-lease" | "--mirror")
                    || a.starts_with("--force")
                    || a.starts_with('+')
            }) || positional.iter().skip(1).any(|r| r.starts_with('+'));
            let delete = has("--delete") || has("-d") || positional.iter().skip(1).any(|r| r.starts_with(':'));
            let targets: Vec<&str> = positional.iter().skip(1).copied().collect();
            let protected_target = targets.iter().any(|t| is_protected(t, req.protected));
            let remote = positional.first().copied().unwrap_or("origin");
            if force || delete {
                let mut f = Finding::new(
                    Risk::High,
                    if delete { format!("delete a branch on {remote}") } else { format!("force-push to {remote}") },
                )
                .because("It can erase other people's work on the server, and the safety net can't undo it.");
                if req.block_protected {
                    f.blocked = Some(
                        "Force pushes and branch deletions on the server are turned off by the safety net.".into(),
                    );
                }
                return f;
            }
            if protected_target {
                let branch = targets.iter().find(|t| is_protected(t, req.protected)).copied().unwrap_or("main");
                let mut f = Finding::new(Risk::High, format!("upload commits straight to {branch} on {remote}"))
                    .because(format!("{branch} is a protected branch; changes should go through a pull request."));
                if req.block_protected {
                    f.blocked =
                        Some(format!("Pushing to {branch} is blocked by the safety net. Open a pull request instead."));
                }
                return f;
            }
            Finding::new(Risk::Medium, format!("upload this branch to {remote}"))
                .because("It sends your code to the server where others can see it.")
                .ask()
        }
        "reset" if has("--hard") => Finding::new(Risk::High, "throw away uncommitted changes (git reset --hard)")
            .because("Uncommitted work is lost."),
        "clean" => Finding::new(Risk::High, "delete untracked files (git clean)")
            .because("It deletes files git doesn't track."),
        "checkout" | "switch" | "merge" | "rebase" | "cherry-pick"
            if positional.iter().any(|b| is_protected(b, req.protected)) =>
        {
            let b = positional.iter().find(|b| is_protected(b, req.protected)).copied().unwrap_or("main");
            Finding::new(Risk::High, format!("work on the protected branch {b} (git {sub})"))
                .because("Tasks should stay on their own branch; protected branches are off limits.")
        }
        "branch" if has("-D") || has("-d") || has("--delete") => {
            let names: Vec<String> = positional.iter().map(|s| (*s).to_owned()).collect();
            Finding::new(Risk::High, format!("delete the branch {}", short_list(&names)))
                .because("Deleting a branch can lose work.")
        }
        "stash" if has("drop") || has("clear") => Finding::new(Risk::High, "throw away saved changes (git stash)")
            .because("Dropped stashes are hard to get back."),
        "clone" | "fetch" | "pull" | "remote" | "submodule" => {
            Finding::new(Risk::Medium, format!("download code (git {sub})")).because("It reaches the internet.")
        }
        "commit" | "add" | "stash" | "tag" | "restore" | "mv" | "rm" => {
            let f = Finding::new(Risk::Medium, format!("change the project history (git {sub})"))
                .because("It changes what git records; checkpoints still let you go back.");
            if sub == "rm" { f.ask() } else { f }
        }
        _ => Finding::new(Risk::Medium, format!("run git {sub}"))
            .because("Hypurr doesn't recognise this git command, so it asks to be safe."),
    }
}

const READ_ONLY: &[&str] = &[
    "ls", "cat", "head", "tail", "less", "more", "grep", "rg", "find", "fd", "wc", "pwd", "echo", "which", "tree",
    "stat", "file", "diff", "du", "df", "date", "whoami", "env", "printenv", "sort", "uniq", "cut", "awk", "jq",
    "true", "test", "[",
];
const NETWORK: &[&str] = &["curl", "wget", "ssh", "scp", "rsync", "nc", "ftp", "telnet", "http", "httpie"];
const DANGEROUS: &[&str] =
    &["dd", "mkfs", "shutdown", "reboot", "halt", "kill", "killall", "pkill", "crontab", "chown"];

const UNSURE: &str = "Hypurr can't tell for sure what this does or where, so it asks to be safe.";
const OUTSIDE_RUN: &str = "It runs outside the project folder, where the safety net can't undo changes.";
/// Programs where an unknown (`$X`, `$(…)`) argument can't change anything.
const SAFE_DYNAMIC: &[&str] = &[
    "echo", "printf", "true", "false", "test", "[", "pwd", "date", "which", "basename", "dirname", "expr", "seq",
    "sleep",
];
const SHELLS: &[&str] = &["sh", "bash", "zsh", "dash", "ksh", "fish"];
const CHANGERS: &[&str] = &[
    "rm", "rmdir", "unlink", "shred", "trash", "mv", "cp", "ln", "touch", "mkdir", "tee", "sed", "perl", "chmod",
    "chown", "truncate", "install", "rsync", "dd",
];

fn unsure(action: impl Into<String>) -> Finding {
    Finding::new(Risk::Medium, action).because(UNSURE).ask()
}

fn base_name(p: &str) -> String {
    Path::new(p).file_name().map_or_else(|| p.to_owned(), |n| n.to_string_lossy().into_owned())
}

/// Strips `sudo`, `env X=1`, `nohup`, `time`, `nice`, `timeout N`, `command`, `exec` wrappers.
fn unwrap(words: &[Word]) -> (Vec<Word>, bool) {
    let mut w: Vec<Word> = words.to_vec();
    let mut sudo = false;
    while let Some(first) = w.first().cloned() {
        if first.dynamic {
            break;
        }
        let t = first.text.as_str();
        if t == "sudo" || t == "doas" {
            sudo = true;
            w.remove(0);
            while w.first().is_some_and(|a| a.text.starts_with('-')) {
                let takes = matches!(w[0].text.as_str(), "-u" | "-g" | "-U" | "-C" | "-h" | "-p");
                w.remove(0);
                if takes && !w.is_empty() {
                    w.remove(0);
                }
            }
        } else if matches!(t, "env" | "nohup" | "time" | "command" | "builtin" | "exec" | "stdbuf")
            || shell::is_assignment(t)
        {
            w.remove(0);
            while w.first().is_some_and(|a| !a.dynamic && (a.text.starts_with('-') || shell::is_assignment(&a.text))) {
                w.remove(0);
            }
        } else if t == "nice" {
            w.remove(0);
            if w.first().is_some_and(|a| a.text == "-n") {
                w.drain(..w.len().min(2));
            } else if w.first().is_some_and(|a| a.text.starts_with('-')) {
                w.remove(0);
            }
        } else if t == "timeout" && w.len() > 2 {
            w.remove(0);
            while w.first().is_some_and(|a| a.text.starts_with('-')) {
                w.remove(0);
            }
            if !w.is_empty() {
                w.remove(0);
            }
        } else {
            break;
        }
    }
    (w, sudo)
}

struct Ctx<'a> {
    scope: super::scope::Scope,
    scoped: bool,
    root: Option<std::path::PathBuf>,
    req: &'a Request<'a>,
}

/// One simple command's view: the task's folder plus where this command runs.
struct At<'a, 'b> {
    ctx: &'b Ctx<'a>,
    here: Option<std::path::PathBuf>,
}

impl At<'_, '_> {
    /// Whether `p`, as this command sees it, is outside the project folder.
    fn outside(&self, p: &str) -> bool {
        if !self.ctx.scoped {
            return false;
        }
        if p.starts_with('/') || p.starts_with('~') {
            return !self.ctx.scope.contains(p);
        }
        match &self.here {
            Some(h) => !self.ctx.scope.contains(&h.join(p).to_string_lossy()),
            None => true,
        }
    }
    /// The command runs somewhere other than the project folder itself.
    fn moved(&self) -> bool {
        self.ctx.scoped && self.here != self.ctx.root
    }
    fn here_outside(&self) -> bool {
        self.ctx.scoped && self.here.as_ref().is_none_or(|h| !self.ctx.scope.contains(&h.to_string_lossy()))
    }

    fn write_finding(&self, w: &Word) -> Finding {
        let name = base_name(&w.text);
        if !w.dynamic && matches!(w.text.as_str(), "/dev/null" | "/dev/stdout" | "/dev/stderr") {
            return Finding::new(Risk::Low, "hide some output");
        }
        if w.dynamic {
            return unsure("write to a file Hypurr can't work out");
        }
        if self.outside(&w.text) {
            return Finding::new(Risk::High, format!("write to {name} outside this project"))
                .because("It changes files outside the project folder, where the safety net can't undo it.")
                .ask();
        }
        if looks_secret(&w.text) {
            return Finding::new(Risk::High, format!("write to {name}"))
                .because("That file may hold passwords or keys.");
        }
        Finding::new(Risk::Low, format!("write to {name}"))
            .because("Edits inside the project can be undone from a checkpoint.")
    }
}

impl Ctx<'_> {
    fn command(&self, cmd: &str, start: Option<std::path::PathBuf>, depth: usize, out: &mut Vec<Finding>) {
        if depth > 4 {
            out.push(unsure("run commands nested too deeply to check"));
            return;
        }
        let segs = shell::parse(cmd, start);
        let prog = |s: &Simple| unwrap(&s.words).0.first().map(|w| base_name(&w.text)).unwrap_or_default();
        let fetch_at = segs.iter().position(|s| NETWORK.contains(&prog(s).as_str()));
        let shell_after = fetch_at.is_some_and(|i| {
            segs.iter().skip(i + 1).any(|s| {
                let p = prog(s);
                SHELLS.contains(&p.as_str()) || matches!(p.as_str(), "python" | "python3" | "node" | "perl" | "eval")
            })
        });
        if shell_after {
            out.push(
                Finding::new(Risk::High, "download a script from the internet and run it")
                    .because("It runs code nobody has checked, with access to your whole computer."),
            );
        }
        for s in &segs {
            self.simple(s, depth, out);
        }
    }

    fn simple(&self, s: &Simple, depth: usize, out: &mut Vec<Finding>) {
        let at = At { ctx: self, here: s.cwd.clone() };
        if s.unsure {
            out.push(unsure("run a command Hypurr can't fully read"));
        }
        for w in &s.writes {
            out.push(at.write_finding(w));
        }
        let (w, sudo) = unwrap(&s.words);
        if w.is_empty() {
            return;
        }
        let prog = base_name(&w[0].text);
        let mut f = self.simple_finding(&at, &w, depth, out);
        let display = crate::agent::acp::truncate(&texts(&w).join(" "), 80);
        let harmless = matches!(prog.as_str(), "cd" | "pushd" | "popd" | "echo" | "printf" | "true" | ":" | "pwd");
        if at.here_outside() && !harmless {
            let place = at.here.as_ref().map_or_else(
                || "a folder Hypurr can't work out".to_owned(),
                |h| format!("{} (outside this project)", h.display()),
            );
            out.push(Finding::new(Risk::Medium, format!("run {display} in {place}")).because(OUTSIDE_RUN).ask());
        }
        if sudo {
            f = Finding::new(Risk::High, format!("run a command as administrator ({display})"))
                .because("Administrator (sudo) commands can change the whole computer.");
        }
        let secret: Vec<String> = w[1..]
            .iter()
            .filter(|a| !a.dynamic && !a.text.starts_with('-') && looks_secret(&a.text))
            .map(|a| a.text.clone())
            .collect();
        if !secret.is_empty() && f.risk < Risk::High {
            f = Finding::new(Risk::High, format!("open {} ({display})", short_list(&secret)))
                .because("That file may hold passwords or keys.");
        }
        out.push(f);
    }

    fn simple_finding(&self, at: &At, w: &[Word], depth: usize, out: &mut Vec<Finding>) -> Finding {
        let display = crate::agent::acp::truncate(&texts(w).join(" "), 80);
        if w[0].dynamic {
            return unsure(format!("run a command Hypurr can't work out ({display})"));
        }
        let first = w[0].text.as_str();
        let prog = base_name(first);
        let args = &w[1..];
        match prog.as_str() {
            "cd" | "pushd" | "popd" => return Finding::new(Risk::Low, "move to another folder"),
            "set" | "export" | "unset" | "shopt" | "local" | "readonly" | "exit" | "return" | ":" | "[[" | "wait" => {
                return Finding::new(Risk::Low, format!("set up the shell ({display})"));
            }
            p if SHELLS.contains(&p) => {
                let c = args.iter().position(|a| {
                    !a.dynamic && a.text.starts_with('-') && !a.text.starts_with("--") && a.text.contains('c')
                });
                if let Some(i) = c {
                    return match args.get(i + 1) {
                        Some(script) if !script.dynamic => {
                            self.command(&script.text, at.here.clone(), depth + 1, out);
                            Finding::new(Risk::Low, format!("run a shell ({p} -c)"))
                        }
                        _ => unsure(format!("run a shell command Hypurr can't read ({display})")),
                    };
                }
                if let Some(script) = args.iter().find(|a| a.dynamic || !a.text.starts_with('-')) {
                    return self.script_finding(at, script, depth, out);
                }
                return Finding::new(Risk::Medium, format!("run a shell ({display})"))
                    .because("It runs whatever is sent to it, which Hypurr can't check.")
                    .ask();
            }
            "eval" => {
                if args.iter().any(|a| a.dynamic) {
                    return unsure(format!("run commands Hypurr can't read ({display})"));
                }
                self.command(&texts(args).join(" "), at.here.clone(), depth + 1, out);
                return Finding::new(Risk::Low, "run commands (eval)");
            }
            "source" | "." => {
                return match args.first() {
                    Some(s) => self.script_finding(at, s, depth, out),
                    None => unsure("run a script"),
                };
            }
            "xargs" => {
                let mut i = 0;
                while i < args.len() && args[i].text.starts_with('-') && !args[i].dynamic {
                    let takes = matches!(args[i].text.as_str(), "-I" | "-n" | "-P" | "-d" | "-L" | "-s" | "-E" | "-a");
                    i += if takes { 2 } else { 1 };
                }
                let mut inner: Vec<Word> = args.get(i..).unwrap_or_default().to_vec();
                if inner.is_empty() {
                    inner.push(Word::lit("echo"));
                }
                inner.push(Word { text: "<input>".into(), dynamic: true });
                return self.simple_finding(at, &inner, depth + 1, out);
            }
            "find" => return self.find_finding(at, w, depth, out, &display),
            "git" => {
                if let Some(i) = args.iter().position(|a| a.text == "-C") {
                    match args.get(i + 1) {
                        Some(d) if !d.dynamic && !at.outside(&d.text) => {}
                        _ => out.push(
                            Finding::new(Risk::Medium, format!("run git in another folder ({display})"))
                                .because(OUTSIDE_RUN)
                                .ask(),
                        ),
                    }
                }
            }
            _ => {}
        }
        // `./run.sh`, `scripts/x.sh`, or a path into the project: running a file from here.
        if first.contains('/') && (!first.starts_with('/') || !at.outside(first)) {
            return self.script_finding(at, &w[0], depth, out);
        }
        let strings = texts(w);
        let arg_strs: Vec<&str> = strings.iter().skip(1).copied().collect();
        let owned: Vec<String> = strings.iter().map(|s| (*s).to_owned()).collect();
        let f = classify(&prog, &arg_strs, &owned, at, at.moved(), &display);
        // `… | xargs grep x`: the piped-in names only feed a reader.
        let piped_reader = READ_ONLY.contains(&prog.as_str())
            && prog != "find"
            && args.iter().filter(|a| a.dynamic).all(|a| a.text == "<input>");
        if args.iter().any(|a| a.dynamic) && !SAFE_DYNAMIC.contains(&prog.as_str()) && !piped_reader {
            let changes = CHANGERS.contains(&prog.as_str());
            out.push(
                Finding::new(
                    if changes { Risk::High } else { Risk::Medium },
                    format!(
                        "{} files Hypurr can't work out ({display})",
                        if changes { "change or delete" } else { "use" }
                    ),
                )
                .because(
                    "Part of the command is only known when it runs, so Hypurr can't check it stays in the project.",
                )
                .ask(),
            );
        }
        f
    }

    fn find_finding(&self, at: &At, words: &[Word], depth: usize, out: &mut Vec<Finding>, display: &str) -> Finding {
        let args = &words[1..];
        let starts: Vec<&Word> = args
            .iter()
            .take_while(|a| a.dynamic || !(a.text.starts_with('-') || a.text == "(" || a.text == "!"))
            .collect();
        let outside: Vec<String> =
            starts.iter().filter(|a| a.dynamic || at.outside(&a.text)).map(|a| a.text.clone()).collect();
        let mut i = 0;
        while i < args.len() {
            let a = args[i].text.as_str();
            match a {
                "-delete" => out.push(
                    Finding::new(
                        Risk::High,
                        if outside.is_empty() {
                            format!("delete every file find matches ({display})")
                        } else {
                            format!("delete files outside this project ({display})")
                        },
                    )
                    .because("It deletes every file it finds; the safety net can't bring back new or ignored ones.")
                    .ask(),
                ),
                "-exec" | "-execdir" | "-ok" | "-okdir" => {
                    let mut inner = Vec::new();
                    i += 1;
                    while i < args.len() && (args[i].dynamic || !matches!(args[i].text.as_str(), ";" | "+")) {
                        let x = &args[i];
                        inner.push(match x.text.as_str() {
                            "{}" => Word { text: "<input>".into(), dynamic: true },
                            t if t.contains("{}") => Word { text: x.text.clone(), dynamic: true },
                            _ => x.clone(),
                        });
                        i += 1;
                    }
                    if inner.is_empty() {
                        out.push(unsure(format!("run a command on every file found ({display})")));
                    } else {
                        let f = self.simple_finding(at, &inner, depth + 1, out);
                        out.push(f);
                    }
                }
                "-fprint" | "-fprint0" | "-fprintf" | "-fls" => {
                    if let Some(t) = args.get(i + 1) {
                        out.push(at.write_finding(t));
                    }
                }
                _ => {}
            }
            i += 1;
        }
        if outside.is_empty() {
            Finding::new(Risk::Low, format!("look at files ({display})")).because("It only reads; nothing changes.")
        } else {
            Finding::new(Risk::Medium, format!("look at files outside this project ({})", short_list(&outside)))
                .because("It reads files outside the project folder.")
                .ask()
        }
    }

    /// Running a script file: allowed quietly only when it's inside the folder and
    /// reading it shows nothing but low-risk commands.
    fn script_finding(&self, at: &At, script: &Word, depth: usize, out: &mut Vec<Finding>) -> Finding {
        let name = base_name(&script.text);
        if script.dynamic {
            return unsure("run a script Hypurr can't work out");
        }
        if at.outside(&script.text) {
            return Finding::new(Risk::High, format!("run the script {name} from outside this project"))
                .because("It runs code from outside the project folder that Hypurr hasn't checked.")
                .ask();
        }
        let path = if script.text.starts_with('/') || script.text.starts_with('~') {
            super::scope::real_path(Path::new("/"), &script.text)
        } else {
            at.here.as_ref().and_then(|h| super::scope::real_path(h, &script.text))
        };
        let text = path
            .and_then(|p| std::fs::File::open(p).ok())
            .and_then(|f| {
                use std::io::Read;
                let mut s = String::new();
                f.take(64 * 1024 + 1).read_to_string(&mut s).ok().map(|_| s)
            })
            .filter(|s| s.len() <= 64 * 1024);
        let Some(text) = text else {
            return Finding::new(Risk::Medium, format!("run the script {name}"))
                .because("Hypurr couldn't read the script to check it, so it asks.")
                .ask();
        };
        let shebang = text.lines().next().unwrap_or("");
        if shebang.starts_with("#!")
            && !SHELLS.iter().any(|s| shebang.ends_with(&format!("/{s}")) || shebang.contains(&format!(" {s}")))
        {
            return Finding::new(Risk::Medium, format!("run the script {name}"))
                .because("Hypurr can only read shell scripts; this one runs other code it can't check.")
                .ask();
        }
        let mut inner = Vec::new();
        self.command(&text, at.here.clone(), depth + 1, &mut inner);
        if inner.iter().all(|f| f.risk == Risk::Low && !f.ask && f.blocked.is_none()) {
            return Finding::new(Risk::Low, format!("run the script {name}"))
                .because("Hypurr read the script: it only reads, builds or tests.");
        }
        for mut f in inner.into_iter().filter(|f| f.risk >= Risk::Medium) {
            f.ask = true;
            out.push(f);
        }
        Finding::new(Risk::Medium, format!("run the script {name}"))
            .because("The script does more than read, build or test.")
            .ask()
    }
}

fn texts(w: &[Word]) -> Vec<&str> {
    w.iter().map(|x| x.text.as_str()).collect()
}

fn classify(prog: &str, args: &[&str], w: &[String], at: &At, moved: bool, display: &str) -> Finding {
    let req = at.ctx.req;
    let targets: Vec<String> = args.iter().filter(|a| !a.starts_with('-')).map(|a| (*a).to_owned()).collect();
    match prog {
        "git" => git_finding(&git_args(w), req),
        "rm" | "rmdir" | "unlink" | "shred" | "trash" => {
            let recursive =
                args.iter().any(|a| a.starts_with('-') && !a.starts_with("--") && (a.contains('r') || a.contains('R')))
                    || args.contains(&"--recursive");
            let out: Vec<String> = targets
                .iter()
                .filter(|t| at.outside(t) || t.as_str() == "/" || t.contains('*') && t.starts_with('/'))
                .cloned()
                .collect();
            if !out.is_empty() {
                return Finding::new(Risk::High, format!("delete {} outside this project", short_list(&out)))
                    .because("Deleting outside the project can't be undone by the safety net.");
            }
            let what = if targets.is_empty() { "files".to_owned() } else { short_list(&targets) };
            if recursive || targets.len() > 3 || targets.iter().any(|t| t.contains('*')) {
                Finding::new(
                    Risk::High,
                    format!("delete {what}{}", if recursive { " and everything inside" } else { "" }),
                )
                .because("It deletes files. A checkpoint can bring tracked files back, but not ignored or new ones.")
            } else {
                Finding::new(Risk::Medium, format!("delete {what}"))
                    .because("It deletes files; a checkpoint can bring tracked files back.")
                    .ask()
            }
        }
        "mv" | "cp" | "ln" | "touch" | "mkdir" | "tee" | "sed" | "perl" => {
            let out: Vec<String> = targets.iter().filter(|t| at.outside(t)).cloned().collect();
            if !out.is_empty() {
                Finding::new(Risk::High, format!("change {} outside this project", short_list(&out)))
                    .because("It changes files outside the project folder, where the safety net can't undo it.")
            } else if prog == "sed" && !args.iter().any(|a| a.starts_with("-i")) {
                Finding::new(Risk::Low, format!("read files ({display})")).because("It only reads; nothing changes.")
            } else {
                Finding::new(Risk::Low, format!("change files in the project ({display})"))
                    .because("Edits inside the project can be undone from a checkpoint.")
            }
        }
        "chmod" if args.iter().any(|a| a.contains("777") || *a == "-R") => {
            Finding::new(Risk::High, format!("change who can open files ({display})"))
                .because("Loose file permissions can expose your computer.")
        }
        p if READ_ONLY.contains(&p) => {
            let out: Vec<String> = targets
                .iter()
                .filter(|t| (moved || t.contains('/') || t.starts_with("..") || t.starts_with('~')) && at.outside(t))
                .cloned()
                .collect();
            if out.is_empty() {
                Finding::new(Risk::Low, format!("look at files ({display})")).because("It only reads; nothing changes.")
            } else {
                Finding::new(Risk::Medium, format!("look at files outside this project ({})", short_list(&out)))
                    .because("It reads files outside the project folder.")
                    .ask()
            }
        }
        p if NETWORK.contains(&p) => {
            let piped = w.iter().any(|a| a == "sh" || a == "bash");
            if piped || args.iter().any(|a| matches!(*a, "-d" | "--data" | "-T" | "--upload-file" | "-F" | "--form")) {
                Finding::new(Risk::High, format!("send data to or run code from the internet ({display})"))
                    .because("It can upload your files or run code nobody has checked.")
            } else {
                Finding::new(Risk::Medium, format!("download something from the internet ({display})"))
                    .because("It reaches the internet.")
            }
        }
        p if DANGEROUS.contains(&p) => Finding::new(Risk::High, format!("run a system command ({display})"))
            .because("It can affect the whole computer, not just this project."),
        "npm" | "pnpm" | "yarn" | "bun" | "pip" | "pip3" | "uv" | "poetry" | "cargo" | "go" | "gem" | "brew"
        | "apt" | "apt-get" | "composer" | "dotnet" | "mvn" | "gradle" | "./gradlew" | "gradlew" | "make"
        | "pytest" | "python" | "python3" | "node" | "npx" | "deno" | "swift" | "xcodebuild" | "rustc" | "tsc"
        | "jest" | "vitest" => tool_finding(prog, args, display),
        "kubectl" | "helm" | "terraform" | "pulumi" | "aws" | "gcloud" | "az" | "heroku" | "vercel" | "netlify"
        | "flyctl" | "wrangler" | "docker" => {
            let reading = args.iter().any(|a| {
                matches!(
                    *a,
                    "get"
                        | "describe"
                        | "logs"
                        | "plan"
                        | "ps"
                        | "images"
                        | "list"
                        | "ls"
                        | "status"
                        | "whoami"
                        | "version"
                        | "--version"
                )
            });
            if reading {
                Finding::new(Risk::Medium, format!("look at cloud or server settings ({display})"))
                    .because("It talks to real servers, but only reads.")
            } else {
                Finding::new(Risk::High, format!("change cloud or server resources ({display})"))
                    .because("It can change or delete real servers and cost money; the safety net can't undo it.")
            }
        }
        "psql" | "mysql" | "sqlite3" | "mongo" | "mongosh" | "redis-cli" => {
            let text = w.join(" ").to_lowercase();
            if ["drop ", "delete ", "truncate ", "update ", "alter "].iter().any(|k| text.contains(k)) {
                Finding::new(Risk::High, format!("change or delete database data ({display})"))
                    .because("Database changes can't be undone by the safety net.")
            } else {
                Finding::new(Risk::Medium, format!("talk to a database ({display})"))
                    .because("It connects to a database.")
            }
        }
        _ => Finding::new(Risk::Medium, format!("run a command: {display}"))
            .because("Hypurr doesn't recognise this command, so it asks to be safe."),
    }
}

fn tool_finding(prog: &str, args: &[&str], display: &str) -> Finding {
    let sub = args.iter().find(|a| !a.starts_with('-')).copied().unwrap_or("");
    let installs = matches!(sub, "install" | "i" | "add" | "update" | "upgrade" | "get" | "ci")
        || matches!(prog, "brew" | "apt" | "apt-get") && !matches!(sub, "list" | "search" | "info" | "show");
    let publishes = matches!(sub, "publish" | "deploy" | "release" | "push" | "login");
    let tests =
        matches!(sub, "test" | "check" | "lint" | "build" | "fmt" | "clippy" | "vet" | "run" | "tsc" | "typecheck")
            || matches!(prog, "pytest" | "jest" | "vitest" | "tsc" | "make" | "rustc");
    if publishes {
        Finding::new(Risk::High, format!("publish or deploy ({display})"))
            .because("Publishing makes it public and can't be undone by the safety net.")
    } else if installs && matches!(prog, "brew" | "apt" | "apt-get")
        || args.iter().any(|a| matches!(*a, "-g" | "--global"))
    {
        Finding::new(Risk::High, format!("install software on the whole computer ({display})"))
            .because("It changes the computer, not just this project.")
    } else if installs {
        Finding::new(Risk::Medium, format!("download and install packages ({display})"))
            .because("New packages come from the internet; check they're ones you expect.")
    } else if tests {
        Finding::new(Risk::Low, format!("build or test the project ({display})"))
            .because("Building and testing doesn't change your code.")
    } else {
        Finding::new(Risk::Medium, format!("run {display}"))
            .because("It runs project code, which can do anything the code does.")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn protected() -> Vec<String> {
        vec!["main".into(), "master".into(), "release/*".into()]
    }

    fn cmd(c: &str) -> Assessment {
        let p = protected();
        assess(&Request {
            tool_kind: "execute",
            title: "",
            command: Some(c),
            paths: &[],
            cwd: "/work/repo",
            protected: &p,
            block_protected: true,
        })
    }

    fn edit(paths: &[&str]) -> Assessment {
        let p = protected();
        let paths: Vec<String> = paths.iter().map(|s| (*s).to_owned()).collect();
        assess(&Request {
            tool_kind: "edit",
            title: "Edit",
            command: None,
            paths: &paths,
            cwd: "/work/repo",
            protected: &p,
            block_protected: true,
        })
    }

    #[test]
    fn reading_and_testing_are_low() {
        for c in ["ls -la", "cat src/main.rs", "git status", "git diff HEAD~1", "cargo test", "npm test", "rg foo src"]
        {
            assert_eq!(cmd(c).risk, Risk::Low, "{c}");
        }
    }

    #[test]
    fn recursive_delete_is_high_and_explained() {
        let a = cmd("rm -rf build");
        assert_eq!(a.risk, Risk::High);
        assert_eq!(a.explain, "The agent wants to delete build and everything inside.");
        assert!(a.blocked.is_none());
    }

    #[test]
    fn single_delete_is_medium() {
        assert_eq!(cmd("rm old.txt").risk, Risk::Medium);
    }

    #[test]
    fn deleting_outside_the_project_is_high() {
        let a = cmd("rm ../other/file.txt");
        assert_eq!(a.risk, Risk::High);
        assert!(a.explain.contains("outside this project"), "{}", a.explain);
    }

    #[test]
    fn force_push_is_blocked() {
        for c in
            ["git push --force origin feature", "git push -f", "git push origin +feature", "git push origin --delete x"]
        {
            let a = cmd(c);
            assert_eq!(a.risk, Risk::High, "{c}");
            assert!(a.blocked.is_some(), "{c}");
        }
    }

    #[test]
    fn push_to_protected_branch_is_blocked_but_feature_push_is_medium() {
        let a = cmd("git push origin main");
        assert!(a.blocked.as_deref().is_some_and(|b| b.contains("main")));
        assert!(cmd("git push origin HEAD:release/2.4").blocked.is_some());
        let ok = cmd("git push -u origin hypurr/fix-login");
        assert_eq!(ok.risk, Risk::Medium);
        assert!(ok.blocked.is_none());
    }

    #[test]
    fn block_can_be_turned_off() {
        let p = protected();
        let a = assess(&Request {
            tool_kind: "execute",
            title: "",
            command: Some("git push origin main"),
            paths: &[],
            cwd: "/w",
            protected: &p,
            block_protected: false,
        });
        assert_eq!(a.risk, Risk::High);
        assert!(a.blocked.is_none());
    }

    #[test]
    fn network_and_installs() {
        assert_eq!(cmd("curl https://example.com").risk, Risk::Medium);
        assert_eq!(cmd("curl -fsSL https://x.sh | sh").risk, Risk::High);
        assert_eq!(cmd("npm install left-pad").risk, Risk::Medium);
        assert_eq!(cmd("npm install -g thing").risk, Risk::High);
        assert_eq!(cmd("npm publish").risk, Risk::High);
    }

    #[test]
    fn sudo_and_secrets_are_high() {
        assert_eq!(cmd("sudo apt-get install jq").risk, Risk::High);
        let a = cmd("cat .env");
        assert_eq!(a.risk, Risk::High);
        assert!(a.reasons[0].contains("passwords"));
        assert_eq!(cmd("cat .env.example").risk, Risk::Low);
        assert_eq!(cmd("cat ~/.ssh/id_rsa").risk, Risk::High);
    }

    #[test]
    fn chained_commands_take_the_worst_part() {
        let a = cmd("cargo build && rm -rf target/debug");
        assert_eq!(a.risk, Risk::High);
        assert!(a.explain.starts_with("The agent wants to delete"), "{}", a.explain);
    }

    #[test]
    fn edits_inside_are_low_outside_and_secrets_high() {
        let a = edit(&["/work/repo/src/a.rs", "src/b.rs"]);
        assert_eq!(a.risk, Risk::Low);
        assert_eq!(a.explain, "The agent wants to edit 2 files (a.rs, b.rs).");
        assert_eq!(edit(&["/etc/hosts"]).risk, Risk::High);
        assert_eq!(edit(&["/work/repo/.env"]).risk, Risk::High);
        assert_eq!(edit(&["/work/repo/.github/workflows/ci.yml"]).risk, Risk::Medium);
    }

    #[test]
    fn unknown_tools_ask_at_medium() {
        let p = protected();
        let a = assess(&Request {
            tool_kind: "other",
            title: "Frobnicate",
            command: None,
            paths: &[],
            cwd: "/w",
            protected: &p,
            block_protected: true,
        });
        assert_eq!(a.risk, Risk::Medium);
        assert_eq!(a.explain, "The agent wants to use a tool: Frobnicate.");
        assert_eq!(cmd("frob --all").risk, Risk::Medium);
    }

    #[test]
    fn checking_out_protected_branch_is_high() {
        assert_eq!(cmd("git checkout main").risk, Risk::High);
        assert_eq!(cmd("git checkout -b feature").risk, Risk::Medium);
        assert_eq!(cmd("git reset --hard HEAD~3").risk, Risk::High);
    }

    #[test]
    fn cloud_changes_are_high() {
        assert_eq!(cmd("kubectl delete pod x").risk, Risk::High);
        assert_eq!(cmd("kubectl get pods").risk, Risk::Medium);
        assert_eq!(cmd("terraform apply").risk, Risk::High);
    }

    #[test]
    fn outside_project_always_must_ask() {
        let a = edit(&["/other/secret.txt"]);
        assert_eq!(a.risk, Risk::High);
        assert!(a.must_ask, "outside-folder edits must reach the person even on auto bots");
        assert!(a.reasons.iter().any(|r| r.contains("outside")));
    }

    #[test]
    fn inside_project_edit_does_not_force_ask() {
        let a = edit(&["/work/repo/src/main.rs", "src/lib.rs"]);
        assert_eq!(a.risk, Risk::Low);
        assert!(!a.must_ask);
        assert!(!cmd("cargo test").must_ask);
        assert!(!cmd("cat src/main.rs").must_ask, "reading inside the folder is free");
    }

    #[test]
    fn traversal_and_absolute_paths_must_ask() {
        for p in ["../other/x.rs", "src/../../other/x.rs", "/etc/hosts", "~/.bashrc"] {
            assert!(edit(&[p]).must_ask, "edit {p}");
        }
        for c in ["cat ../../etc/passwd", "cat /etc/passwd", "ls ~/Documents", "cp src/a.rs ../a.rs"] {
            let a = cmd(c);
            assert!(a.must_ask, "{c} should ask");
            assert!(a.reasons.iter().any(|r| r.contains("outside")), "{c}: {:?}", a.reasons);
        }
    }

    #[test]
    fn pushes_and_deletes_must_ask() {
        let a = cmd("git push origin feature/x");
        assert_eq!(a.risk, Risk::Medium);
        assert!(a.must_ask, "every push asks");
        assert!(cmd("rm notes.txt").must_ask, "every delete asks");
        assert!(cmd("git rm old.rs").must_ask);
        assert!(cmd("rm -rf build").must_ask);
        // Blocked outright: no card to ask on.
        let a = cmd("git push --force origin main");
        assert!(a.blocked.is_some() && !a.must_ask);
    }

    #[cfg(unix)]
    #[test]
    fn symlink_out_of_the_folder_must_ask() {
        let base = std::env::temp_dir().join(format!("hypurr-risk-{}", uuid::Uuid::new_v4()));
        let repo = base.join("repo");
        std::fs::create_dir_all(repo.join("src")).unwrap();
        std::fs::create_dir_all(base.join("outside")).unwrap();
        std::os::unix::fs::symlink(base.join("outside"), repo.join("src/link")).unwrap();
        let cwd = repo.to_string_lossy().into_owned();
        let paths = vec!["src/link/evil.sh".to_owned()];
        let a = assess(&Request {
            tool_kind: "edit",
            title: "Edit",
            command: None,
            paths: &paths,
            cwd: &cwd,
            protected: &[],
            block_protected: true,
        });
        assert_eq!(a.risk, Risk::High);
        assert!(a.must_ask);
        let a = assess(&Request {
            tool_kind: "execute",
            title: "Run",
            command: Some("rm src/link/data.db"),
            paths: &[],
            cwd: &cwd,
            protected: &[],
            block_protected: true,
        });
        assert!(a.reasons.iter().any(|r| r.contains("outside")), "{:?}", a.reasons);
        let _ = std::fs::remove_dir_all(&base);
    }

    fn asks(c: &str) -> Assessment {
        let a = cmd(c);
        assert!(a.must_ask || a.blocked.is_some(), "{c} should ask: {a:?}");
        a
    }

    #[test]
    fn chained_cd_escapes_must_ask() {
        for c in [
            "cd ..; rm x",
            "cd .. && rm x",
            "cd /tmp && rm -rf y",
            "cd src; cd ../..; rm -rf z",
            "cd src && cd .. && cd .. && cat secret.txt",
            "pushd /etc; cat hosts",
            "cd -; rm x",
            "cd $OLDPWD && rm x",
            "cd ..; npm test",
            "cd ~ && ls",
        ] {
            asks(c);
        }
        let a = cmd("cd /tmp && rm -rf y");
        assert_eq!(a.risk, Risk::High);
        assert!(a.reasons.iter().any(|r| r.contains("outside")), "{:?}", a.reasons);
    }

    #[test]
    fn cd_inside_the_folder_stays_free() {
        for c in ["cd src && ls", "cd src && cat main.rs", "cd src/a && cd .. && cargo test", "(cd web && npm test)"] {
            let a = cmd(c);
            assert!(!a.must_ask, "{c}: {a:?}");
            assert_eq!(a.risk, Risk::Low, "{c}");
        }
        // A subshell's cd doesn't leak out.
        assert!(!cmd("(cd /tmp && ls) ; ls src").must_ask || cmd("(cd /tmp && ls)").must_ask);
        assert!(!cmd("(cd src) ; cat main.rs").must_ask);
        assert_eq!(cmd("(cd /tmp); rm -rf x").risk, Risk::High);
        assert!(!cmd("(cd /tmp; true); cat src/main.rs").must_ask);
    }

    #[test]
    fn shell_wrappers_are_read_through() {
        for c in [
            "sh -c 'rm -rf ../z'",
            "bash -c \"cd .. && rm x\"",
            "bash -lc 'rm -rf /tmp/q'",
            "eval 'rm ../a'",
            "eval $CMD",
            "bash -c \"$CMD\"",
            "env FOO=1 sh -c 'cat ../../etc/passwd'",
            "timeout 10 bash -c 'rm -rf ../z'",
            "sudo -u bob sh -c 'ls'",
        ] {
            asks(c);
        }
        assert_eq!(cmd("sh -c 'rm -rf ../z'").risk, Risk::High);
        assert_eq!(cmd("bash -c 'cargo test && ls'").risk, Risk::Low);
        assert!(!cmd("bash -c 'cargo test && ls'").must_ask);
    }

    #[test]
    fn substitutions_and_variables_fail_safe() {
        for c in [
            "rm $(echo ..)/x",
            "rm `echo ..`/x",
            "rm -rf \"$DIR\"",
            "cp a.txt $(dirname $PWD)/b",
            "cat $(echo /etc/passwd)",
            "ls $(cd .. && rm -rf z)",
        ] {
            asks(c);
        }
        assert_eq!(cmd("rm $(echo ..)/x").risk, Risk::High);
        assert!(!cmd("echo $(git rev-parse HEAD)").must_ask);
        assert!(!cmd("echo \"build $(date)\"").must_ask);
    }

    #[test]
    fn pipes_xargs_and_find_deletes_ask() {
        for c in [
            "ls | xargs rm",
            "find . -name '*.tmp' | xargs -n1 rm -f",
            "find . -name '*.log' -delete",
            "find . -type f -exec rm {} \\;",
            "find build -execdir rm -rf {} +",
            "find .. -name x",
            "find /etc -name hosts",
            "git ls-files | xargs sed -i s/a/b/",
        ] {
            asks(c);
        }
        assert_eq!(cmd("find . -name '*.log' -delete").risk, Risk::High);
        assert_eq!(cmd("find . -exec rm {} ;").risk, Risk::High);
        assert!(!cmd("find src -name '*.rs' | xargs grep TODO").must_ask);
        assert!(!cmd("find . -name '*.rs' -exec wc -l {} +").must_ask);
        assert!(!cmd("cat src/a.rs | grep foo | sort | uniq").must_ask);
    }

    #[test]
    fn redirects_outside_ask() {
        for c in ["echo hi > ../x.txt", "cat a >> /etc/hosts", "ls &> ~/out.log", "echo x | tee ../y", "echo > $F"] {
            asks(c);
        }
        assert!(!cmd("cargo test 2>&1 > target/log.txt").must_ask);
        assert!(!cmd("ls 2>/dev/null").must_ask);
        assert_eq!(cmd("echo hi > ../x.txt").risk, Risk::High);
    }

    #[test]
    fn unreadable_syntax_and_git_c_ask() {
        asks("echo 'unterminated; rm -rf ..");
        asks("git -C ../other status");
        asks("git -C /tmp/x log");
        assert!(!cmd("git -C src status").must_ask);
    }

    #[test]
    fn no_folder_chosen_keeps_old_behaviour() {
        let p = protected();
        let a = assess(&Request {
            tool_kind: "execute",
            title: "",
            command: Some("cd /tmp && ls"),
            paths: &[],
            cwd: "",
            protected: &p,
            block_protected: true,
        });
        assert_eq!(a.risk, Risk::Low);
        assert!(!a.must_ask);
    }

    #[cfg(unix)]
    #[test]
    fn scripts_are_read_before_running() {
        let base = std::env::temp_dir().join(format!("hypurr-script-{}", uuid::Uuid::new_v4()));
        let repo = base.join("repo");
        std::fs::create_dir_all(repo.join("scripts")).unwrap();
        std::fs::write(repo.join("scripts/check.sh"), "#!/bin/sh\nset -e\ncargo test\nls src | grep rs\n").unwrap();
        std::fs::write(repo.join("scripts/clean.sh"), "#!/bin/bash\ncd ..\nrm -rf build\n").unwrap();
        std::fs::write(repo.join("scripts/tool.py"), "#!/usr/bin/env python3\nprint(1)\n").unwrap();
        std::fs::write(base.join("evil.sh"), "ls\n").unwrap();
        let cwd = repo.to_string_lossy().into_owned();
        let run = |c: &str| {
            assess(&Request {
                tool_kind: "execute",
                title: "",
                command: Some(c),
                paths: &[],
                cwd: &cwd,
                protected: &[],
                block_protected: true,
            })
        };
        for c in ["bash scripts/check.sh", "./scripts/check.sh", "sh ./scripts/check.sh", ". scripts/check.sh"] {
            let a = run(c);
            assert_eq!(a.risk, Risk::Low, "{c}: {a:?}");
            assert!(!a.must_ask, "{c}");
        }
        for c in [
            "bash scripts/clean.sh",
            "./scripts/clean.sh",
            "./scripts/tool.py",
            "bash ../evil.sh",
            "../evil.sh",
            "./scripts/missing.sh",
            "cd scripts && ./clean.sh",
            "source $X",
        ] {
            let a = run(c);
            assert!(a.must_ask, "{c}: {a:?}");
        }
        assert_eq!(run("bash ../evil.sh").risk, Risk::High);
        assert_eq!(run("bash scripts/clean.sh").risk, Risk::High, "the script's rm outside shows through");
        let _ = std::fs::remove_dir_all(&base);
    }
}
