//! What a trace holds, and how raw browser positions become repo files and lines.

use super::locate::{self, CallSite, Repo, Route};
use super::sourcemap::{SourceMap, clean_source};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::LazyLock;

/// A position in a script the browser ran (0-based line and column).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Frame {
    pub url: String,
    pub line: u32,
    pub col: u32,
    #[serde(default)]
    pub function: String,
}

/// A place in the repo: `client/src/api.js:9`, and how we know.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CodeRef {
    pub name: Option<String>,
    pub file: Option<String>,
    pub line: Option<u32>,
    /// `source map`, `browser` (script served as-is) or `code search`.
    pub how: String,
}

impl CodeRef {
    pub fn place(&self) -> Option<String> {
        let f = self.file.as_ref()?;
        Some(self.line.map_or_else(|| f.clone(), |l| format!("{f}:{l}")))
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Call {
    pub method: String,
    pub url: String,
    pub status: Option<u16>,
    #[serde(default)]
    pub status_text: String,
    pub ms: Option<u64>,
    #[serde(default)]
    pub request_body: String,
    #[serde(default)]
    pub response_body: String,
    #[serde(default)]
    pub request_headers: Vec<(String, String)>,
    pub failed: Option<String>,
    #[serde(default)]
    pub blocked: bool,
    pub sent_from: Option<CodeRef>,
    pub handled_by: Option<Route>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub initiator: Vec<Frame>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Click {
    /// The button's words: "Place order".
    pub label: String,
    pub tag: String,
    pub role: Option<String>,
    pub selector: String,
    /// The page path it was clicked on.
    pub page: String,
    pub handler_name: Option<String>,
    pub handler_frame: Option<Frame>,
    pub handler: Option<CodeRef>,
    #[serde(default)]
    pub calls: Vec<Call>,
    /// New text on the page after the click (for the UI assertion).
    #[serde(default)]
    pub appeared: Vec<String>,
    pub url_after: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Trace {
    pub id: String,
    pub project: String,
    pub url: String,
    pub at: i64,
    /// `recording`, `done` or `failed`.
    pub status: String,
    pub error: Option<String>,
    #[serde(default)]
    pub browser: String,
    #[serde(default)]
    pub clicks: Vec<Click>,
    /// Hosts the page tried to reach that weren't allowed (blocked in the browser).
    #[serde(default)]
    pub blocked_hosts: Vec<String>,
    /// Plain-words chain, one block per click.
    #[serde(default)]
    pub chain: Vec<Vec<String>>,
}

fn in_deps(f: &str) -> bool {
    f.contains("node_modules/") || f.contains("/.vite/") || f.contains("react-dom") || f.contains("webpack/")
}

/// Map a browser frame back to a repo file through its source map, or its served path.
pub fn resolve_frame(repo: &Repo, maps: &HashMap<String, SourceMap>, f: &Frame) -> Option<CodeRef> {
    if let Some(m) = maps.get(&f.url)
        && let Some((src, line)) = m.lookup(f.line, f.col)
    {
        let clean = clean_source(&src);
        if in_deps(&clean) {
            return None;
        }
        if let Some(file) = repo.by_suffix(&clean) {
            return Some(CodeRef {
                name: none_if_empty(&f.function),
                file: Some(file),
                line: Some(line),
                how: "source map".into(),
            });
        }
    }
    let path = locate::path_of(&f.url);
    if in_deps(&path) {
        return None;
    }
    let file = repo.by_suffix(&clean_source(&path))?;
    Some(CodeRef { name: none_if_empty(&f.function), file: Some(file), line: Some(f.line + 1), how: "browser".into() })
}

fn none_if_empty(s: &str) -> Option<String> {
    (!s.is_empty() && s != "anonymous").then(|| s.to_owned())
}

static DECL: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r"(?:function\s+([A-Za-z_$][\w$]*)|(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=)").expect("decl re")
});

/// Where a named function is declared (code search, used when the browser can't tell us).
pub fn find_decl(repo: &Repo, name: &str) -> Option<CodeRef> {
    for f in repo.files.iter().filter(|f| locate::JS_EXT.contains(&locate::ext(f))) {
        let src = repo.read(f);
        if !src.contains(name) {
            continue;
        }
        for (i, l) in src.lines().enumerate() {
            if DECL.captures_iter(l).any(|c| c.get(1).or_else(|| c.get(2)).is_some_and(|m| m.as_str() == name)) {
                return Some(CodeRef {
                    name: Some(name.to_owned()),
                    file: Some(f.clone()),
                    line: u32::try_from(i + 1).ok(),
                    how: "code search".into(),
                });
            }
        }
    }
    None
}

/// Fill in handler and call locations, the backend route, and the plain-words chain.
pub fn resolve(t: &mut Trace, repo: &Repo, maps: &HashMap<String, SourceMap>) {
    let sites: Vec<CallSite> = locate::call_sites(repo);
    let routes = locate::routes(repo);
    for c in &mut t.clicks {
        c.handler = c
            .handler_frame
            .as_ref()
            .and_then(|f| resolve_frame(repo, maps, f))
            .map(|mut r| {
                r.name = r.name.or_else(|| c.handler_name.clone());
                r
            })
            .or_else(|| {
                c.handler_name
                    .as_deref()
                    .filter(|n| !n.is_empty() && *n != "anonymous")
                    .and_then(|n| find_decl(repo, n))
            });
        for call in &mut c.calls {
            call.sent_from = call.initiator.iter().find_map(|f| resolve_frame(repo, maps, f)).or_else(|| {
                locate::sites_for(&sites, &call.method, &call.url).first().map(|s| CodeRef {
                    name: s.function.clone(),
                    file: Some(s.file.clone()),
                    line: Some(s.line),
                    how: "code search".into(),
                })
            });
            call.handled_by = locate::route_for(&routes, &call.method, &call.url).cloned();
            call.initiator.clear();
        }
    }
    t.chain = t.clicks.iter().map(super::chain::words).collect();
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::trace::locate::tests::fixture;

    pub fn sample() -> Trace {
        Trace {
            id: "t1".into(),
            project: "/p".into(),
            url: "http://localhost:5173/orders".into(),
            status: "done".into(),
            clicks: vec![Click {
                label: "Place order".into(),
                tag: "button".into(),
                selector: "button:nth-of-type(1)".into(),
                page: "/orders".into(),
                handler_name: Some("handlePlaceOrder".into()),
                calls: vec![Call {
                    method: "POST".into(),
                    url: "http://localhost:5173/api/orders".into(),
                    status: Some(201),
                    status_text: "Created".into(),
                    ms: Some(84),
                    request_body: "{\n  \"sku\": \"SOCK-RED\",\n  \"qty\": 2\n}".into(),
                    response_body: "{\n  \"id\": 1\n}".into(),
                    ..Call::default()
                }],
                appeared: vec!["Order 1 placed".into()],
                ..Click::default()
            }],
            ..Trace::default()
        }
    }

    #[test]
    fn resolves_by_code_search() {
        let repo = Repo::scan(&fixture());
        let mut t = sample();
        resolve(&mut t, &repo, &HashMap::new());
        let c = &t.clicks[0];
        let h = c.handler.as_ref().expect("handler");
        assert_eq!(h.place().as_deref(), Some("client/src/pages/Orders.jsx:9"));
        assert_eq!(h.how, "code search");
        let call = &c.calls[0];
        assert_eq!(call.sent_from.as_ref().and_then(CodeRef::place).as_deref(), Some("client/src/api.js:9"));
        let r = call.handled_by.as_ref().expect("route");
        assert_eq!(r.file, "server/routes/orders.js");
        assert!(t.chain[0].iter().any(|l| l.contains("server/routes/orders.js:7")));
    }

    #[test]
    fn resolves_through_a_source_map() {
        let repo = Repo::scan(&fixture());
        let map = SourceMap::parse(
            r#"{"version":3,"sources":["webpack://shop/./src/pages/Orders.jsx"],"mappings":"AAAA;IAQA"}"#,
        )
        .expect("map");
        let maps = HashMap::from([("http://localhost:5173/static/js/main.js".to_owned(), map)]);
        let f = Frame {
            url: "http://localhost:5173/static/js/main.js".into(),
            line: 1,
            col: 12,
            function: "handlePlaceOrder".into(),
        };
        let r = resolve_frame(&repo, &maps, &f).expect("mapped");
        assert_eq!((r.place().as_deref(), r.how.as_str()), (Some("client/src/pages/Orders.jsx:9"), "source map"));
        // Vite serves files as they are: the URL path is the file.
        let f = Frame { url: "http://localhost:5173/src/api.js?t=1".into(), line: 8, col: 2, function: String::new() };
        assert_eq!(
            resolve_frame(&repo, &HashMap::new(), &f).and_then(|r| r.place()).as_deref(),
            Some("client/src/api.js:9")
        );
        let f = Frame {
            url: "http://localhost:5173/node_modules/.vite/deps/react-dom.js".into(),
            line: 1,
            col: 1,
            function: String::new(),
        };
        assert!(resolve_frame(&repo, &HashMap::new(), &f).is_none());
    }
}
