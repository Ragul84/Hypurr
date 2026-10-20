//! Finding code without running it: the frontend lines that call an API, and the backend
//! route that answers it (Express, `Next.js` API routes, `FastAPI`, Flask, Spring and Django).

use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

const SKIP_DIRS: &[&str] = &[
    "node_modules",
    ".git",
    ".hypurr",
    "dist",
    "build",
    "out",
    ".next",
    ".nuxt",
    "target",
    "coverage",
    "vendor",
    "__pycache__",
    ".venv",
    "venv",
    ".gradle",
    ".idea",
    ".vscode",
    "bin",
    "obj",
];
const MAX_FILES: usize = 20_000;
const MAX_SIZE: u64 = 512 * 1024;
const CODE_EXT: &[&str] = &["js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte", "py", "java", "kt"];
pub const JS_EXT: &[&str] = &["js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte"];

fn re(s: &str) -> Regex {
    Regex::new(s).expect("valid regex")
}

/// The project's source files (relative, `/`-separated), skipping dependencies and builds.
#[derive(Debug, Clone)]
pub struct Repo {
    pub root: PathBuf,
    pub files: Vec<String>,
}

pub fn ext(path: &str) -> &str {
    path.rsplit_once('.').map_or("", |(_, e)| e)
}

impl Repo {
    pub fn scan(root: &Path) -> Self {
        let mut files = Vec::new();
        let mut stack = vec![root.to_path_buf()];
        while let Some(dir) = stack.pop() {
            let Ok(rd) = std::fs::read_dir(&dir) else { continue };
            let mut entries: Vec<_> = rd.flatten().collect();
            entries.sort_by_key(std::fs::DirEntry::file_name);
            for e in entries {
                let name = e.file_name().to_string_lossy().into_owned();
                let Ok(ft) = e.file_type() else { continue };
                if ft.is_dir() {
                    if !SKIP_DIRS.contains(&name.as_str()) && !name.starts_with('.') {
                        stack.push(e.path());
                    }
                } else if ft.is_file() && CODE_EXT.contains(&ext(&name)) && files.len() < MAX_FILES {
                    if e.metadata().is_ok_and(|m| m.len() > MAX_SIZE) || name.contains(".min.") {
                        continue;
                    }
                    if let Ok(rel) = e.path().strip_prefix(root) {
                        files.push(rel.to_string_lossy().replace('\\', "/"));
                    }
                }
            }
        }
        files.sort();
        Self { root: root.to_path_buf(), files }
    }

    pub fn read(&self, rel: &str) -> String {
        std::fs::read_to_string(self.root.join(rel)).unwrap_or_default()
    }

    /// The repo file a source-map path or script URL points at (longest matching tail).
    pub fn by_suffix(&self, path: &str) -> Option<String> {
        let p = path.trim_start_matches("./").trim_start_matches("../").trim_start_matches('/');
        if p.is_empty() {
            return None;
        }
        if self.files.iter().any(|f| f == p) {
            return Some(p.to_owned());
        }
        let parts: Vec<&str> = p.split('/').collect();
        (0..parts.len()).find_map(|i| {
            let tail = parts[i..].join("/");
            let hits: Vec<&String> =
                self.files.iter().filter(|f| f.ends_with(&format!("/{tail}")) || **f == tail).collect();
            (hits.len() == 1 && (parts.len() - i >= 2 || i == 0)).then(|| hits[0].clone())
        })
    }

    /// Resolve a relative import (`../api`) from `from` to a repo file.
    pub fn resolve_import(&self, from: &str, spec: &str) -> Option<String> {
        if !spec.starts_with('.') {
            return None;
        }
        let base = from.rsplit_once('/').map_or("", |(d, _)| d);
        let joined = normalize(&format!("{base}/{spec}"));
        let mut tries = vec![joined.clone()];
        for e in JS_EXT {
            tries.push(format!("{joined}.{e}"));
        }
        for e in JS_EXT {
            tries.push(format!("{joined}/index.{e}"));
        }
        tries.into_iter().find(|t| self.files.contains(t))
    }
}

/// `a/b/../c/./d` → `a/c/d`.
pub fn normalize(p: &str) -> String {
    let mut out: Vec<&str> = Vec::new();
    for s in p.split('/') {
        match s {
            "" | "." => {}
            ".." => {
                out.pop();
            }
            _ => out.push(s),
        }
    }
    out.join("/")
}

// ---------- URL patterns ----------

/// The path of a URL or pattern, without origin, query or trailing slash.
pub fn path_of(url: &str) -> String {
    let rest = url.split_once("://").map_or(url, |(_, r)| r.find('/').map_or("", |i| &r[i..]));
    let p = rest.split(['?', '#']).next().unwrap_or_default();
    let p = p.trim_end_matches('/');
    if p.starts_with('/') { p.to_owned() } else { format!("/{p}") }
}

fn is_param(seg: &str) -> bool {
    seg.starts_with(':')
        || (seg.starts_with('{') && seg.ends_with('}'))
        || (seg.starts_with('[') && seg.ends_with(']'))
        || (seg.starts_with('<') && seg.ends_with('>'))
        || seg == "*"
}

/// How well a route pattern matches a real path: `None` if it doesn't, else literal segments.
pub fn match_path(pattern: &str, actual: &str) -> Option<usize> {
    let (pp, ap) = (path_of(pattern), path_of(actual));
    let p: Vec<&str> = pp.split('/').filter(|s| !s.is_empty()).collect();
    let a: Vec<&str> = ap.split('/').filter(|s| !s.is_empty()).collect();
    if p.len() != a.len() {
        return None;
    }
    let mut lit = 0;
    for (ps, as_) in p.iter().zip(&a) {
        if is_param(ps) || is_param(as_) {
            continue;
        }
        if !ps.eq_ignore_ascii_case(as_) {
            return None;
        }
        lit += 1;
    }
    Some(lit)
}

// ---------- frontend call sites ----------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CallSite {
    pub file: String,
    pub line: u32,
    pub method: String,
    /// `/api/orders/:p`.
    pub pattern: String,
    /// The function the call sits in (`createOrder`), when there is one.
    pub function: Option<String>,
}

static CALL: LazyLock<Regex> = LazyLock::new(|| {
    re(
        r#"(?:\bfetch|\baxios(?:\.(get|post|put|patch|delete))?|\$http\.(get|post|put|patch|delete)|\b(?:http|api|client|request|instance|httpClient)\.(get|post|put|patch|delete))\s*(?:<[^>]*>)?\(\s*(?:'([^']*)'|"([^"]*)"|`([^`]*)`|([A-Za-z_$][\w$]*)\s*\+\s*['"`]([^'"`]*)['"`])"#,
    )
});
static METHOD: LazyLock<Regex> = LazyLock::new(|| re(r#"method\s*:\s*['"`](\w+)['"`]"#));
static CONST_STR: LazyLock<Regex> =
    LazyLock::new(|| re(r#"(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*['"`]([^'"`$]*)['"`]"#));
static TEMPLATE_VAR: LazyLock<Regex> = LazyLock::new(|| re(r"\$\{\s*([^}]*)\}"));
static FN_DECL: LazyLock<Regex> = LazyLock::new(|| {
    re(
        r"(?:function\s*\*?\s*([A-Za-z_$][\w$]*)\s*\(|(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s+)?(?:function\b|\([^)]*\)\s*=>|[A-Za-z_$][\w$]*\s*=>)|^\s*(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{)",
    )
});

/// The function a line sits in, looking upwards.
pub fn enclosing_fn(lines: &[&str], idx: usize) -> Option<String> {
    for i in (idx.saturating_sub(40)..=idx).rev() {
        if let Some(c) = FN_DECL.captures(lines[i]) {
            let name = c.get(1).or_else(|| c.get(2)).or_else(|| c.get(3))?.as_str();
            if !matches!(name, "if" | "for" | "while" | "switch" | "catch" | "return" | "function") {
                return Some(name.to_owned());
            }
        }
    }
    None
}

fn consts(src: &str) -> HashMap<String, String> {
    CONST_STR.captures_iter(src).map(|c| (c[1].to_owned(), c[2].to_owned())).collect()
}

/// Turn a JS URL literal into a pattern: known constants filled in, other `${x}` → `:p`.
fn pattern(lit: &str, consts: &HashMap<String, String>) -> Option<String> {
    let filled = TEMPLATE_VAR.replace_all(lit, |c: &regex::Captures| {
        let k = c[1].trim();
        consts.get(k).cloned().unwrap_or_else(|| ":p".into())
    });
    let s = filled.split(['?', '#']).next().unwrap_or_default();
    let s = if s.contains("://") { path_of(s) } else { s.to_owned() };
    let s = s.strip_prefix(":p").unwrap_or(&s).to_owned();
    (s.starts_with('/') && s.len() > 1).then(|| path_of(&s))
}

/// `method: 'POST'` inside the call's options (stops at the end of the call).
fn call_method(lines: &[&str]) -> Option<String> {
    for l in lines {
        if let Some(m) = METHOD.captures(l) {
            return Some(m[1].to_owned());
        }
        if l.contains(");") || l.trim_start().starts_with("})") {
            break;
        }
    }
    None
}

/// Every API call written in the frontend code.
pub fn call_sites(repo: &Repo) -> Vec<CallSite> {
    let mut out = Vec::new();
    for f in repo.files.iter().filter(|f| JS_EXT.contains(&ext(f))) {
        let src = repo.read(f);
        if !src.contains("fetch") && !src.contains("axios") && !src.contains(".get(") && !src.contains(".post(") {
            continue;
        }
        let k = consts(&src);
        let lines: Vec<&str> = src.lines().collect();
        for (i, l) in lines.iter().enumerate() {
            for c in CALL.captures_iter(l) {
                let lit = if let (Some(v), Some(rest)) = (c.get(7), c.get(8)) {
                    format!("{}{}", k.get(v.as_str()).map_or(":p", String::as_str), rest.as_str())
                } else {
                    let Some(m) = c.get(4).or_else(|| c.get(5)).or_else(|| c.get(6)) else { continue };
                    m.as_str().to_owned()
                };
                let Some(pat) = pattern(&lit, &k) else { continue };
                let method = c
                    .get(1)
                    .or_else(|| c.get(2))
                    .or_else(|| c.get(3))
                    .map(|m| m.as_str().to_owned())
                    .or_else(|| call_method(&lines[i..lines.len().min(i + 8)]))
                    .unwrap_or_else(|| "GET".into())
                    .to_ascii_uppercase();
                out.push(CallSite {
                    file: f.clone(),
                    line: u32::try_from(i + 1).unwrap_or(0),
                    method,
                    pattern: pat,
                    function: enclosing_fn(&lines, i),
                });
            }
        }
    }
    out
}

/// The call sites that would send this request, best first.
pub fn sites_for<'a>(sites: &'a [CallSite], method: &str, url: &str) -> Vec<&'a CallSite> {
    let mut hits: Vec<(usize, &CallSite)> = sites
        .iter()
        .filter_map(|s| {
            let score = match_path(&s.pattern, url)?;
            let m = if s.method.eq_ignore_ascii_case(method) { 100 } else { 0 };
            Some((score + m, s))
        })
        .collect();
    hits.sort_by_key(|h| std::cmp::Reverse(h.0));
    let best = hits.first().map_or(0, |h| h.0);
    hits.into_iter().filter(|h| h.0 == best).map(|h| h.1).collect()
}

// ---------- backend routes ----------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Route {
    pub framework: String,
    /// `GET`, … or `ANY`.
    pub method: String,
    pub path: String,
    pub file: String,
    pub line: u32,
    pub handler: Option<String>,
}

static EXPRESS: LazyLock<Regex> = LazyLock::new(|| {
    re(r#"\b(app|router|server|routes|api|[a-z]\w*Router)\.(get|post|put|patch|delete|all)\(\s*['"`]([^'"`]+)['"`]"#)
});
static EXPRESS_FN: LazyLock<Regex> = LazyLock::new(|| re(r"function\s+(\w+)|,\s*([A-Za-z_$][\w$.]*)\s*\)\s*;?\s*$"));
static USE: LazyLock<Regex> = LazyLock::new(|| {
    re(r#"\.use\(\s*['"`]([^'"`]+)['"`]\s*,\s*(?:require\(\s*['"]([^'"]+)['"]\s*\)|([A-Za-z_$][\w$]*))"#)
});
static REQUIRE: LazyLock<Regex> = LazyLock::new(|| {
    re(
        r#"(?:(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*require\(\s*['"]([^'"]+)['"]|import\s+([A-Za-z_$][\w$]*)\s+from\s+['"]([^'"]+)['"])"#,
    )
});
static NEXT_FN: LazyLock<Regex> =
    LazyLock::new(|| re(r"export\s+(?:async\s+function|function|const)\s+(GET|POST|PUT|PATCH|DELETE)\b"));
static PY_ROUTE: LazyLock<Regex> =
    LazyLock::new(|| re(r#"@(\w+)\.(get|post|put|patch|delete|api_route)\(\s*['"]([^'"]*)['"]"#));
static PY_PREFIX: LazyLock<Regex> = LazyLock::new(|| re(r#"APIRouter\([^)]*prefix\s*=\s*['"]([^'"]+)['"]"#));
static FLASK: LazyLock<Regex> =
    LazyLock::new(|| re(r#"@(\w+)\.route\(\s*['"]([^'"]*)['"](?:[^)]*methods\s*=\s*\[([^\]]*)\])?"#));
static PY_DEF: LazyLock<Regex> = LazyLock::new(|| re(r"^\s*(?:async\s+)?def\s+(\w+)"));
static DJANGO: LazyLock<Regex> =
    LazyLock::new(|| re(r#"\b(?:re_)?path\(\s*r?['"]([^'"]*)['"]\s*,\s*([\w.]+)(?:\.as_view\(\))?"#));
static DJANGO_INC: LazyLock<Regex> =
    LazyLock::new(|| re(r#"\b(?:re_)?path\(\s*r?['"]([^'"]*)['"]\s*,\s*include\(\s*['"]([\w.]+)['"]"#));
static SPRING_CLASS: LazyLock<Regex> =
    LazyLock::new(|| re(r#"@RequestMapping\(\s*(?:(?:value|path)\s*=\s*)?\{?\s*"([^"]*)""#));
static SPRING: LazyLock<Regex> = LazyLock::new(|| {
    re(
        r#"@(Get|Post|Put|Patch|Delete|Request)Mapping\b(?:\(\s*(?:(?:value|path)\s*=\s*)?\{?\s*(?:"([^"]*)")?([^)]*)\))?"#,
    )
});
static SPRING_FN: LazyLock<Regex> = LazyLock::new(|| re(r"(?:fun\s+|\s)(\w+)\s*\("));

fn line_no(i: usize) -> u32 {
    u32::try_from(i + 1).unwrap_or(0)
}

fn join(prefix: &str, path: &str) -> String {
    let p = format!("{}/{}", prefix.trim_end_matches('/'), path.trim_start_matches('/'));
    path_of(&p)
}

fn express(repo: &Repo, out: &mut Vec<Route>) {
    let js: Vec<&String> = repo.files.iter().filter(|f| JS_EXT.contains(&ext(f))).collect();
    // Mount prefixes: app.use('/api', ordersRouter) → every route in that router's file.
    let mut prefix: HashMap<String, String> = HashMap::new();
    for f in &js {
        let src = repo.read(f);
        if !src.contains(".use(") {
            continue;
        }
        let vars: HashMap<String, String> = REQUIRE
            .captures_iter(&src)
            .filter_map(|c| {
                let (v, p) = match (c.get(1), c.get(2), c.get(3), c.get(4)) {
                    (Some(v), Some(p), _, _) | (_, _, Some(v), Some(p)) => (v.as_str(), p.as_str()),
                    _ => return None,
                };
                Some((v.to_owned(), repo.resolve_import(f, p)?))
            })
            .collect();
        for c in USE.captures_iter(&src) {
            let target = match (c.get(2), c.get(3)) {
                (Some(p), _) => repo.resolve_import(f, p.as_str()),
                (_, Some(v)) => vars.get(v.as_str()).cloned(),
                _ => None,
            };
            if let Some(t) = target {
                prefix.insert(t, c[1].to_owned());
            }
        }
    }
    for f in js {
        let src = repo.read(f);
        if !src.contains("express") && !src.contains("Router") && !prefix.contains_key(f.as_str()) {
            continue;
        }
        let pre = prefix.get(f.as_str()).cloned().unwrap_or_default();
        for (i, l) in src.lines().enumerate() {
            for c in EXPRESS.captures_iter(l) {
                let handler =
                    EXPRESS_FN.captures(l).and_then(|h| h.get(1).or_else(|| h.get(2))).map(|m| m.as_str().to_owned());
                out.push(Route {
                    framework: "Express".into(),
                    method: if &c[2] == "all" { "ANY".into() } else { c[2].to_ascii_uppercase() },
                    path: join(&pre, &c[3]),
                    file: f.clone(),
                    line: line_no(i),
                    handler,
                });
            }
        }
    }
}

static NEXT_PAGES_API: LazyLock<Regex> = LazyLock::new(|| re(r"(?:^|/)pages/(api/.+)\.(?:js|jsx|ts|tsx)$"));
static NEXT_APP_ROUTE: LazyLock<Regex> = LazyLock::new(|| re(r"(?:^|/)app/(.*?)/?route\.(?:js|ts)$"));

fn next_path(raw: &str) -> String {
    let segs: Vec<&str> = raw
        .split('/')
        .filter(|s| !s.is_empty() && *s != "index" && !(s.starts_with('(') && s.ends_with(')')))
        .collect();
    path_of(&format!("/{}", segs.join("/")))
}

fn nextjs(repo: &Repo, out: &mut Vec<Route>) {
    for f in &repo.files {
        let raw = if let Some(c) = NEXT_PAGES_API.captures(f) {
            c[1].to_owned()
        } else if let Some(c) = NEXT_APP_ROUTE.captures(f) {
            c[1].to_owned()
        } else {
            continue;
        };
        let src = repo.read(f);
        let lines: Vec<&str> = src.lines().collect();
        let mut any = false;
        for (i, l) in lines.iter().enumerate() {
            if let Some(c) = NEXT_FN.captures(l) {
                any = true;
                out.push(Route {
                    framework: "Next.js".into(),
                    method: c[1].to_owned(),
                    path: next_path(&raw),
                    file: f.clone(),
                    line: line_no(i),
                    handler: Some(c[1].to_owned()),
                });
            }
        }
        if !any {
            let i = lines.iter().position(|l| l.contains("export default")).unwrap_or(0);
            out.push(Route {
                framework: "Next.js".into(),
                method: "ANY".into(),
                path: next_path(&raw),
                file: f.clone(),
                line: line_no(i),
                handler: Some("handler".into()),
            });
        }
    }
}

fn next_def(lines: &[&str], i: usize) -> Option<String> {
    lines[i + 1..lines.len().min(i + 8)].iter().find_map(|l| PY_DEF.captures(l).map(|c| c[1].to_owned()))
}

fn python(repo: &Repo, out: &mut Vec<Route>) {
    // Django includes: path('api/', include('shop.urls')) → shop/urls.py gets the prefix.
    let mut dj_prefix: HashMap<String, String> = HashMap::new();
    for f in repo.files.iter().filter(|f| f.ends_with("urls.py")) {
        for c in DJANGO_INC.captures_iter(&repo.read(f)) {
            let module = format!("{}.py", c[2].replace('.', "/"));
            if let Some(t) = repo.by_suffix(&module) {
                dj_prefix.insert(t, c[1].to_owned());
            }
        }
    }
    for f in repo.files.iter().filter(|f| ext(f) == "py") {
        let src = repo.read(f);
        let lines: Vec<&str> = src.lines().collect();
        let pre = PY_PREFIX.captures(&src).map(|c| c[1].to_owned()).unwrap_or_default();
        for (i, l) in lines.iter().enumerate() {
            if let Some(c) = PY_ROUTE.captures(l) {
                let method = if &c[2] == "api_route" { "ANY".into() } else { c[2].to_ascii_uppercase() };
                out.push(Route {
                    framework: "FastAPI".into(),
                    method,
                    path: join(&pre, &c[3]),
                    file: f.clone(),
                    line: line_no(i),
                    handler: next_def(&lines, i),
                });
            } else if let Some(c) = FLASK.captures(l) {
                let methods: Vec<String> = c.get(3).map_or_else(
                    || vec!["GET".into()],
                    |m| {
                        m.as_str()
                            .split(',')
                            .map(|s| s.trim().trim_matches(['\'', '"']).to_ascii_uppercase())
                            .filter(|s| !s.is_empty())
                            .collect()
                    },
                );
                for m in methods {
                    out.push(Route {
                        framework: "Flask".into(),
                        method: m,
                        path: path_of(&c[2]),
                        file: f.clone(),
                        line: line_no(i),
                        handler: next_def(&lines, i),
                    });
                }
            }
        }
        if f.ends_with("urls.py") {
            let pre = dj_prefix.get(f.as_str()).cloned().unwrap_or_default();
            for (i, l) in lines.iter().enumerate() {
                if l.contains("include(") {
                    continue;
                }
                if let Some(c) = DJANGO.captures(l) {
                    let p = c[1].trim_start_matches('^').trim_end_matches('$');
                    out.push(Route {
                        framework: "Django".into(),
                        method: "ANY".into(),
                        path: join(&pre, p),
                        file: f.clone(),
                        line: line_no(i),
                        handler: Some(c[2].to_owned()),
                    });
                }
            }
        }
    }
}

fn spring(repo: &Repo, out: &mut Vec<Route>) {
    for f in repo.files.iter().filter(|f| matches!(ext(f), "java" | "kt")) {
        let src = repo.read(f);
        if !src.contains("Mapping") {
            continue;
        }
        let lines: Vec<&str> = src.lines().collect();
        let class_at = lines.iter().position(|l| l.contains("class ")).unwrap_or(0);
        let pre = lines[..class_at]
            .iter()
            .find_map(|l| SPRING_CLASS.captures(l).map(|c| c[1].to_owned()))
            .unwrap_or_default();
        for (i, l) in lines.iter().enumerate().skip(class_at + 1) {
            let Some(c) = SPRING.captures(l) else { continue };
            let method = if &c[1] == "Request" {
                let rest = c.get(3).map_or("", |m| m.as_str());
                ["GET", "POST", "PUT", "PATCH", "DELETE"]
                    .iter()
                    .find(|m| rest.contains(&format!("RequestMethod.{m}")))
                    .map_or("ANY", |m| m)
                    .to_owned()
            } else {
                c[1].to_ascii_uppercase()
            };
            let handler = lines[i + 1..lines.len().min(i + 6)]
                .iter()
                .filter(|l| !l.trim_start().starts_with('@'))
                .find_map(|l| SPRING_FN.captures(l).map(|c| c[1].to_owned()));
            out.push(Route {
                framework: "Spring".into(),
                method,
                path: join(&pre, c.get(2).map_or("", |m| m.as_str())),
                file: f.clone(),
                line: line_no(i),
                handler,
            });
        }
    }
}

static NEST_CLASS: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"@Controller\(\s*(?:['"`]([^'"`]*)['"`])?"#).expect("nest class re"));
static NEST: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"@(Get|Post|Put|Patch|Delete|All)\(\s*(?:['"`]([^'"`]*)['"`])?\s*\)"#).expect("nest re")
});
static NEST_FN: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^\s*(?:public\s+|private\s+)?(?:async\s+)?([A-Za-z_$][\w$]*)\s*\(").expect("nest fn re")
});

/// `NestJS`: `@Controller('claude')` + `@Post('chat')` → `POST /claude/chat`.
fn nestjs(repo: &Repo, out: &mut Vec<Route>) {
    for f in repo.files.iter().filter(|f| matches!(ext(f), "ts" | "js")) {
        let src = repo.read(f);
        if !src.contains("@Controller(") {
            continue;
        }
        let mut pre = String::new();
        let lines: Vec<&str> = src.lines().collect();
        for (i, l) in lines.iter().enumerate() {
            if let Some(c) = NEST_CLASS.captures(l) {
                c.get(1).map_or("", |m| m.as_str()).clone_into(&mut pre);
                continue;
            }
            let Some(c) = NEST.captures(l) else { continue };
            let method = if &c[1] == "All" { "ANY".to_owned() } else { c[1].to_ascii_uppercase() };
            let handler = lines[i + 1..lines.len().min(i + 6)]
                .iter()
                .filter(|l| !l.trim_start().starts_with('@'))
                .find_map(|l| NEST_FN.captures(l).map(|c| c[1].to_owned()));
            out.push(Route {
                framework: "NestJS".into(),
                method,
                path: join(&pre, c.get(2).map_or("", |m| m.as_str())),
                file: f.clone(),
                line: line_no(i),
                handler,
            });
        }
    }
}

/// Every backend route found in the repo.
pub fn routes(repo: &Repo) -> Vec<Route> {
    let mut out = Vec::new();
    express(repo, &mut out);
    nestjs(repo, &mut out);
    nextjs(repo, &mut out);
    python(repo, &mut out);
    spring(repo, &mut out);
    out
}

/// The route that answers this request, if the backend is in the repo.
pub fn route_for<'a>(routes: &'a [Route], method: &str, url: &str) -> Option<&'a Route> {
    routes
        .iter()
        .filter(|r| r.method == "ANY" || r.method.eq_ignore_ascii_case(method))
        .filter_map(|r| match_path(&r.path, url).map(|s| (s + usize::from(r.method != "ANY"), r)))
        .max_by_key(|(s, _)| *s)
        .map(|(_, r)| r)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub fn fixture() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/trace-app")
    }

    pub fn temp_repo(files: &[(&str, &str)]) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("hypurr-locate-{}", uuid::Uuid::new_v4()));
        for (p, body) in files {
            let path = dir.join(p);
            std::fs::create_dir_all(path.parent().expect("parent")).expect("mkdir");
            std::fs::write(path, body).expect("write");
        }
        dir
    }

    #[test]
    fn nestjs_routes() {
        let dir = temp_repo(&[(
            "backend/src/claude/claude.controller.ts",
            "import { Controller, Post, Get, Body } from '@nestjs/common';\n@Controller('claude')\nexport class ClaudeController {\n  @Post('chat')\n  async chat(@Body('prompt') prompt: string) {\n    return {};\n  }\n  @Get()\n  list() { return []; }\n}\n",
        )]);
        let r = routes(&Repo::scan(&dir));
        let chat = route_for(&r, "POST", "http://localhost:3000/claude/chat").expect("chat route");
        assert_eq!((chat.framework.as_str(), chat.line, chat.handler.as_deref()), ("NestJS", 4, Some("chat")));
        assert!(route_for(&r, "GET", "/claude").is_some());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn paths_match_with_params() {
        assert_eq!(path_of("http://localhost:3001/api/orders/7?x=1"), "/api/orders/7");
        assert_eq!(match_path("/api/orders/:id", "/api/orders/7"), Some(2));
        assert_eq!(match_path("/api/orders/{id}", "http://h/api/orders/7/"), Some(2));
        assert_eq!(match_path("/api/orders/<int:pk>", "/api/orders/7"), Some(2));
        assert_eq!(match_path("/api/orders", "/api/orders/7"), None);
        assert_eq!(match_path("/api/users", "/api/orders"), None);
    }

    #[test]
    fn fixture_calls_and_routes() {
        let repo = Repo::scan(&fixture());
        let sites = call_sites(&repo);
        let post = sites_for(&sites, "POST", "http://localhost:5173/api/orders");
        assert_eq!(post.len(), 1, "{sites:?}");
        assert_eq!((post[0].file.as_str(), post[0].line), ("client/src/api.js", 9));
        assert_eq!(post[0].function.as_deref(), Some("createOrder"));
        let prod = sites_for(&sites, "GET", "/api/products");
        assert_eq!(prod[0].function.as_deref(), Some("listProducts"));
        let del = sites_for(&sites, "DELETE", "/api/orders/7");
        assert_eq!(del[0].file, "client/src/OrderDetail.jsx");

        let routes = routes(&repo);
        let r = route_for(&routes, "POST", "/api/orders").expect("post route");
        assert_eq!((r.file.as_str(), r.line, r.framework.as_str()), ("server/routes/orders.js", 7, "Express"));
        assert_eq!(r.handler.as_deref(), Some("placeOrder"));
        let r = route_for(&routes, "DELETE", "/api/orders/9").expect("delete route");
        assert_eq!(r.path, "/api/orders/:id");
        let r = route_for(&routes, "GET", "/api/products").expect("products");
        assert_eq!(r.file, "server/index.js");
        assert!(route_for(&routes, "GET", "/api/nothing").is_none());
    }

    #[test]
    fn other_frameworks() {
        let dir = temp_repo(&[
            (
                "web/app/api/users/[id]/route.ts",
                "export async function GET(req) {}\nexport async function DELETE() {}\n",
            ),
            ("web/pages/api/health.js", "export default function handler(req, res) {}\n"),
            (
                "api/main.py",
                "from fastapi import APIRouter\nrouter = APIRouter(prefix=\"/v1\")\n\n@router.post(\"/items/{item_id}\")\nasync def save_item(item_id: int):\n    pass\n",
            ),
            ("legacy/app.py", "@app.route('/login', methods=['POST'])\ndef login():\n    pass\n"),
            ("site/urls.py", "urlpatterns = [path('api/', include('shop.urls'))]\n"),
            ("shop/urls.py", "urlpatterns = [\n    path('orders/<int:pk>/', views.order_detail),\n]\n"),
            (
                "svc/src/main/java/OrderController.java",
                "@RestController\n@RequestMapping(\"/api/v2\")\npublic class OrderController {\n  @PostMapping(\"/orders\")\n  public Order create(@RequestBody Order o) { return o; }\n  @RequestMapping(value = \"/ping\", method = RequestMethod.GET)\n  public String ping() { return \"ok\"; }\n}\n",
            ),
        ]);
        let repo = Repo::scan(&dir);
        let routes = routes(&repo);
        let find = |m: &str, u: &str| route_for(&routes, m, u).map(|r| (r.framework.clone(), r.handler.clone()));
        assert_eq!(find("GET", "/api/users/3"), Some(("Next.js".into(), Some("GET".into()))));
        assert_eq!(find("POST", "/api/health"), Some(("Next.js".into(), Some("handler".into()))));
        assert_eq!(find("POST", "/v1/items/5"), Some(("FastAPI".into(), Some("save_item".into()))));
        assert_eq!(find("POST", "/login"), Some(("Flask".into(), Some("login".into()))));
        assert_eq!(find("GET", "/login"), None);
        assert_eq!(find("GET", "/api/orders/4"), Some(("Django".into(), Some("views.order_detail".into()))));
        assert_eq!(find("POST", "/api/v2/orders"), Some(("Spring".into(), Some("create".into()))));
        assert_eq!(find("GET", "/api/v2/ping"), Some(("Spring".into(), Some("ping".into()))));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn suffix_and_imports() {
        let repo = Repo::scan(&fixture());
        assert_eq!(repo.by_suffix("webpack-src/src/api.js").as_deref(), Some("client/src/api.js"));
        assert_eq!(repo.by_suffix("src/pages/Orders.jsx").as_deref(), Some("client/src/pages/Orders.jsx"));
        assert_eq!(repo.resolve_import("client/src/pages/Orders.jsx", "../api").as_deref(), Some("client/src/api.js"));
        assert_eq!(normalize("a/b/../c/./d"), "a/c/d");
    }
}
