//! The page map: every page of the frontend, the buttons and links on it, the API calls it
//! makes (directly or through a helper like `createOrder`) and the backend route behind each.

use crate::trace::locate::{self, CallSite, JS_EXT, Repo, Route};
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeSet, HashMap};
use std::fmt::Write as _;
use std::sync::LazyLock;

fn re(s: &str) -> Regex {
    Regex::new(s).expect("valid regex")
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Element {
    /// `button`, `link`, `form`, `input` or `select`.
    pub kind: String,
    pub label: String,
    pub handler: Option<String>,
    pub file: String,
    pub line: u32,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PageCall {
    pub method: String,
    pub path: String,
    pub file: String,
    pub line: u32,
    /// The helper the page calls (`createOrder`), when the request lives in another file.
    pub via: Option<String>,
    pub handled_by: Option<Route>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Page {
    pub path: String,
    pub file: String,
    pub component: Option<String>,
    pub files: Vec<String>,
    pub elements: Vec<Element>,
    pub calls: Vec<PageCall>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PageMap {
    pub at: i64,
    /// `React Router`, `Next.js`, …
    pub router: String,
    pub pages: Vec<Page>,
    pub routes: Vec<Route>,
    /// Backend routes no page calls (often dead code or used elsewhere).
    pub unused_routes: Vec<Route>,
}

static IMPORT_DEFAULT: LazyLock<Regex> =
    LazyLock::new(|| re(r#"import\s+([A-Za-z_$][\w$]*)\s*(?:,\s*\{[^}]*\})?\s+from\s+['"]([^'"]+)['"]"#));
static IMPORT_NAMED: LazyLock<Regex> =
    LazyLock::new(|| re(r#"import\s+(?:[A-Za-z_$][\w$]*\s*,\s*)?\{([^}]*)\}\s*from\s+['"]([^'"]+)['"]"#));
static LAZY: LazyLock<Regex> = LazyLock::new(|| {
    re(r#"(?:const|let)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:React\.)?lazy\(\s*\(\)\s*=>\s*import\(\s*['"]([^'"]+)['"]"#)
});
static ROUTE_JSX: LazyLock<Regex> = LazyLock::new(|| {
    re(
        r#"<Route\b[^>]*?(?:path=["']([^"']*)["'][^>]*?(?:element=\{\s*<([A-Z]\w*)|component=\{([A-Z]\w*)\})|(?:element=\{\s*<([A-Z]\w*)[^>]*?/>\s*\}|component=\{([A-Z]\w*)\})[^>]*?path=["']([^"']*)["'])"#,
    )
});
static ROUTE_OBJ: LazyLock<Regex> = LazyLock::new(|| {
    re(r#"(?s)path\s*:\s*['"]([^'"]*)['"]\s*,\s*(?:element\s*:\s*<([A-Z]\w*)|component\s*:\s*([A-Z]\w*))"#)
});
static NEXT_PAGE: LazyLock<Regex> = LazyLock::new(|| re(r"(?:^|/)pages/(.+)\.(?:jsx|tsx|js|ts)$"));
static NEXT_APP_PAGE: LazyLock<Regex> = LazyLock::new(|| re(r"(?:^|/)app/(.*?)/?page\.(?:jsx|tsx|js|ts)$"));

static BUTTON: LazyLock<Regex> = LazyLock::new(|| re(r"<(button|Button)\b([^>]*)>([^<{]*)"));
static LINK: LazyLock<Regex> =
    LazyLock::new(|| re(r#"<(?:a|Link|NavLink)\b[^>]*?(?:href|to)=["']([^"']+)["'][^>]*>([^<{]*)"#));
static FORM: LazyLock<Regex> = LazyLock::new(|| re(r"<form\b([^>]*)>"));
static INPUT: LazyLock<Regex> = LazyLock::new(|| re(r"<(input|select|textarea)\b([^>]*)"));
static ON: LazyLock<Regex> =
    LazyLock::new(|| re(r"on(?:Click|Submit|Change)=\{\s*(?:(?:async\s*)?\([^)]*\)\s*=>\s*)?([A-Za-z_$][\w$.]*)"));
static ATTR: LazyLock<Regex> = LazyLock::new(|| re(r#"(aria-label|name|placeholder|type)=["']([^"']+)["']"#));

fn line_no(i: usize) -> u32 {
    u32::try_from(i + 1).unwrap_or(0)
}

/// Pages found from the router (React Router / object routes) or file-based routing (Next.js).
fn find_pages(repo: &Repo) -> (String, Vec<(String, String, Option<String>)>) {
    let mut out: Vec<(String, String, Option<String>)> = Vec::new();
    let mut router = String::new();
    for f in repo.files.iter().filter(|f| JS_EXT.contains(&locate::ext(f))) {
        let src = repo.read(f);
        if !src.contains("path") {
            continue;
        }
        let mut imports: HashMap<String, String> = HashMap::new();
        for c in IMPORT_DEFAULT.captures_iter(&src).chain(LAZY.captures_iter(&src)) {
            if let Some(t) = repo.resolve_import(f, &c[2]) {
                imports.insert(c[1].to_owned(), t);
            }
        }
        let mut hits: Vec<(String, String)> = Vec::new();
        for c in ROUTE_JSX.captures_iter(&src) {
            let path = c.get(1).or_else(|| c.get(6)).map_or("", |m| m.as_str());
            let comp =
                c.get(2).or_else(|| c.get(3)).or_else(|| c.get(4)).or_else(|| c.get(5)).map_or("", |m| m.as_str());
            hits.push((path.to_owned(), comp.to_owned()));
        }
        let jsx = !hits.is_empty();
        if src.contains("createBrowserRouter") || src.contains("routes") || src.contains("Routes") {
            for c in ROUTE_OBJ.captures_iter(&src) {
                let comp = c.get(2).or_else(|| c.get(3)).map_or("", |m| m.as_str());
                hits.push((c[1].to_owned(), comp.to_owned()));
            }
        }
        let before = out.len();
        for (path, comp) in hits {
            let p = if path.starts_with('/') || path == "*" { path } else { format!("/{path}") };
            let file = imports.get(&comp).cloned().unwrap_or_else(|| f.clone());
            if !out.iter().any(|(q, _, _)| *q == p) {
                out.push((p, file, Some(comp)));
            }
        }
        if out.len() > before && router.is_empty() {
            router = if jsx { "React Router" } else { "Route list" }.into();
        }
    }
    // File-based routing (Next.js) only when no router was found: a React app can have a
    // `pages/` folder too.
    let has_routes = !out.is_empty();
    for f in repo.files.iter().filter(|_| !has_routes) {
        let raw = if let Some(c) = NEXT_APP_PAGE.captures(f) {
            c[1].to_owned()
        } else if let Some(c) = NEXT_PAGE.captures(f) {
            let r = c[1].to_owned();
            if r.starts_with("api/") || r.starts_with('_') || r.contains("/_") {
                continue;
            }
            r
        } else {
            continue;
        };
        let segs: Vec<&str> = raw
            .split('/')
            .filter(|s| !s.is_empty() && *s != "index" && !(s.starts_with('(') && s.ends_with(')')))
            .collect();
        let p = format!("/{}", segs.join("/"));
        if !out.iter().any(|(q, _, _)| *q == p) {
            out.push((p, f.clone(), None));
            "Next.js".clone_into(&mut router);
        }
    }
    if out.is_empty() {
        for f in repo.files.iter().filter(|f| JS_EXT.contains(&locate::ext(f))) {
            if ["/pages/", "/views/", "/screens/"].iter().any(|d| f.contains(d)) {
                let name = f.rsplit('/').next().unwrap_or_default().split('.').next().unwrap_or_default();
                out.push((format!("/{}", name.to_ascii_lowercase()), f.clone(), Some(name.to_owned())));
            }
        }
        if !out.is_empty() {
            router = "Folder names".into();
        }
    }
    out.sort();
    (router, out)
}

/// The page file and the local files it imports (two levels).
fn page_files(repo: &Repo, file: &str) -> Vec<String> {
    let mut seen: BTreeSet<String> = BTreeSet::from([file.to_owned()]);
    let mut frontier = vec![file.to_owned()];
    for _ in 0..2 {
        let mut next = Vec::new();
        for f in &frontier {
            let src = repo.read(f);
            for c in IMPORT_DEFAULT.captures_iter(&src).chain(IMPORT_NAMED.captures_iter(&src)) {
                if let Some(t) = repo.resolve_import(f, &c[2])
                    && seen.insert(t.clone())
                {
                    next.push(t);
                }
            }
        }
        frontier = next;
    }
    let mut v: Vec<String> = seen.into_iter().collect();
    v.retain(|f| f != file);
    v.insert(0, file.to_owned());
    v
}

fn clean(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn attr<'a>(attrs: &'a str, name: &str) -> Option<&'a str> {
    ATTR.captures_iter(attrs).find(|c| &c[1] == name).and_then(|c| c.get(2)).map(|m| m.as_str())
}

/// Interactive elements written in a file's JSX.
pub fn elements(repo: &Repo, file: &str) -> Vec<Element> {
    let src = repo.read(file);
    let mut out = Vec::new();
    for (i, l) in src.lines().enumerate() {
        let handler = ON.captures(l).map(|c| c[1].to_owned());
        let mut push = |kind: &str, label: String, handler: Option<String>| {
            out.push(Element { kind: kind.into(), label, handler, file: file.to_owned(), line: line_no(i) });
        };
        if let Some(c) = BUTTON.captures(l) {
            let label = attr(&c[2], "aria-label").map_or_else(|| clean(&c[3]), str::to_owned);
            push("button", label, handler);
        } else if let Some(c) = LINK.captures(l) {
            let label = clean(&c[2]);
            push("link", if label.is_empty() { c[1].to_owned() } else { format!("{label} → {}", &c[1]) }, None);
        } else if let Some(c) = FORM.captures(l) {
            push("form", attr(&c[1], "aria-label").unwrap_or("Form").to_owned(), handler);
        } else if let Some(c) = INPUT.captures(l) {
            let kind = if &c[1] == "input" { "input" } else { "select" };
            let label = attr(&c[2], "aria-label")
                .or_else(|| attr(&c[2], "placeholder"))
                .or_else(|| attr(&c[2], "name"))
                .unwrap_or("");
            push(kind, label.to_owned(), handler);
        }
    }
    out
}

/// Build the whole map.
pub fn build(repo: &Repo) -> PageMap {
    let sites: Vec<CallSite> = locate::call_sites(repo);
    let routes = locate::routes(repo);
    let (router_kind, found) = find_pages(repo);
    let mut used: BTreeSet<(String, String)> = BTreeSet::new();
    let mut pages = Vec::new();
    for (path, file, component) in found {
        let files = page_files(repo, &file);
        let elements: Vec<Element> = files.iter().flat_map(|f| elements(repo, f)).collect();
        let mut calls: Vec<PageCall> = Vec::new();
        let mut add = |s: &CallSite, via: Option<String>| {
            if calls.iter().any(|c| c.file == s.file && c.line == s.line) {
                return;
            }
            let handled_by = locate::route_for(&routes, &s.method, &s.pattern).cloned();
            if let Some(r) = &handled_by {
                used.insert((r.method.clone(), r.path.clone()));
            }
            calls.push(PageCall {
                method: s.method.clone(),
                path: s.pattern.clone(),
                file: s.file.clone(),
                line: s.line,
                via,
                handled_by,
            });
        };
        // Calls written in the page's own files (not shared helper files).
        let helpers: Vec<String> = files.iter().filter(|f| elements_free(repo, f)).cloned().collect();
        for s in sites.iter().filter(|s| files.contains(&s.file) && !helpers.contains(&s.file)) {
            add(s, None);
        }
        // Helpers the page imports by name: `import { createOrder } from '../api'`.
        for f in &files {
            let src = repo.read(f);
            for c in IMPORT_NAMED.captures_iter(&src) {
                let Some(target) = repo.resolve_import(f, &c[2]) else { continue };
                for name in c[1].split(',').map(|n| n.split(" as ").next().unwrap_or_default().trim()) {
                    for s in sites.iter().filter(|s| s.file == target && s.function.as_deref() == Some(name)) {
                        if src.contains(&format!("{name}(")) {
                            add(s, Some(name.to_owned()));
                        }
                    }
                }
            }
        }
        calls.sort_by(|a, b| (&a.path, &a.method).cmp(&(&b.path, &b.method)));
        pages.push(Page { path, file, component, files, elements, calls });
    }
    let unused_routes =
        routes.iter().filter(|r| !used.contains(&(r.method.clone(), r.path.clone()))).cloned().collect();
    PageMap { at: chrono::Utc::now().timestamp(), router: router_kind, pages, routes, unused_routes }
}

/// A file with no JSX of its own (an API or helper module).
fn elements_free(repo: &Repo, f: &str) -> bool {
    let s = repo.read(f);
    !(s.contains("</") || s.contains("/>"))
}

pub fn markdown(m: &PageMap) -> String {
    let mut s = String::from("# Page map\n\n");
    let calls: usize = m.pages.iter().map(|p| p.calls.len()).sum();
    let _ = writeln!(
        s,
        "{} pages ({}), {calls} API calls, {} backend routes.\n",
        m.pages.len(),
        if m.router.is_empty() { "no router found" } else { &m.router },
        m.routes.len()
    );
    for p in &m.pages {
        let _ = writeln!(
            s,
            "## {}  \n`{}`{}\n",
            p.path,
            p.file,
            p.component.as_ref().map_or_else(String::new, |c| format!(" ({c})"))
        );
        if !p.elements.is_empty() {
            s.push_str("What you can do here:\n\n");
            for e in &p.elements {
                let h = e.handler.as_ref().map_or_else(String::new, |h| format!(" → `{h}`"));
                let label = if e.label.is_empty() { "(no label)" } else { &e.label };
                let _ = writeln!(s, "- {} **{label}**{h} — {}:{}", e.kind, e.file, e.line);
            }
            s.push('\n');
        }
        if p.calls.is_empty() {
            s.push_str("No API calls.\n\n");
        } else {
            s.push_str("API calls:\n\n");
            for c in &p.calls {
                let via = c.via.as_ref().map_or_else(String::new, |v| format!(" via `{v}`"));
                let back = c.handled_by.as_ref().map_or_else(
                    || " — backend not in this repo".to_owned(),
                    |r| format!(" — handled in {}:{} ({})", r.file, r.line, r.framework),
                );
                let _ = writeln!(s, "- {} {}{via} — {}:{}{back}", c.method, c.path, c.file, c.line);
            }
            s.push('\n');
        }
    }
    if !m.unused_routes.is_empty() {
        s.push_str("## Backend routes no page calls\n\n");
        for r in &m.unused_routes {
            let _ = writeln!(s, "- {} {} — {}:{}", r.method, r.path, r.file, r.line);
        }
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::trace::locate::tests::{fixture, temp_repo};

    #[test]
    fn maps_the_fixture() {
        let repo = Repo::scan(&fixture());
        let m = build(&repo);
        assert_eq!(m.router, "React Router");
        let paths: Vec<&str> = m.pages.iter().map(|p| p.path.as_str()).collect();
        assert_eq!(paths, vec!["/", "/orders", "/orders/:id"]);
        let orders = &m.pages[1];
        assert_eq!(orders.file, "client/src/pages/Orders.jsx");
        assert!(orders.files.contains(&"client/src/components/OrderRow.jsx".to_owned()));
        let b: Vec<(&str, Option<&str>)> = orders
            .elements
            .iter()
            .filter(|e| e.kind == "button")
            .map(|e| (e.label.as_str(), e.handler.as_deref()))
            .collect();
        assert_eq!(b, vec![("Place order", Some("handlePlaceOrder")), ("Refresh", Some("handleRefresh"))]);
        let calls: Vec<(&str, &str, Option<&str>)> =
            orders.calls.iter().map(|c| (c.method.as_str(), c.path.as_str(), c.via.as_deref())).collect();
        assert_eq!(
            calls,
            vec![("GET", "/api/orders", Some("listOrders")), ("POST", "/api/orders", Some("createOrder"))]
        );
        assert_eq!(orders.calls[1].handled_by.as_ref().map(|r| r.line), Some(7));
        let detail = &m.pages[2];
        assert_eq!(detail.calls[0].method, "DELETE");
        assert_eq!(detail.calls[0].via, None);
        assert!(detail.elements.iter().any(|e| e.kind == "form" && e.handler.as_deref() == Some("cancel")));
        let home = &m.pages[0];
        assert_eq!(home.calls[0].path, "/api/products");
        assert!(home.elements.iter().any(|e| e.kind == "input" && e.label == "Search socks"));
        assert!(m.unused_routes.is_empty(), "{:?} {:?}", m.unused_routes, home.calls);
        let md = markdown(&m);
        assert!(md.contains("## /orders/:id"));
        assert!(md.contains("POST /api/orders via `createOrder`"));
    }

    #[test]
    fn next_js_file_routes() {
        let dir = temp_repo(&[
            ("app/page.tsx", "export default function Home() { return <button onClick={go}>Start</button> }\n"),
            (
                "app/(shop)/cart/page.tsx",
                "export default function Cart() { return <a href=\"/checkout\">Checkout</a> }\n",
            ),
            ("pages/_app.tsx", "export default function A() {}\n"),
            ("pages/about.tsx", "export default function About() { return <p/> }\n"),
            ("pages/api/x.ts", "export default function h() {}\n"),
        ]);
        let m = build(&Repo::scan(&dir));
        let paths: Vec<&str> = m.pages.iter().map(|p| p.path.as_str()).collect();
        assert_eq!(paths, vec!["/", "/about", "/cart"]);
        assert_eq!(m.router, "Next.js");
        let _ = std::fs::remove_dir_all(dir);
    }
}
