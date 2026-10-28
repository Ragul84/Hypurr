//! A tiny loopback-only file server for plain HTML projects (proof captures and "See it live").
//! Serves files under one folder; never dotfiles or dot-folders (so never `.env` or `.git`),
//! never anything outside the folder.

use anyhow::Result;
use axum::body::Body;
use axum::http::{HeaderValue, StatusCode, Uri, header};
use axum::response::Response;
use std::collections::HashMap;
use std::path::{Component, Path, PathBuf};
use std::sync::{LazyLock, Mutex};

/// The relative file a request path maps to, or `None` when it isn't allowed.
pub fn resolve(path: &str) -> Option<PathBuf> {
    let raw = path.split(['?', '#']).next().unwrap_or("");
    let decoded = percent_decode(raw)?;
    let mut rel = PathBuf::new();
    for part in decoded.split(['/', '\\']).filter(|p| !p.is_empty()) {
        if part.starts_with('.') || part.contains(':') {
            return None;
        }
        rel.push(part);
    }
    if rel.components().any(|c| !matches!(c, Component::Normal(_))) {
        return None;
    }
    Some(rel)
}

fn percent_decode(s: &str) -> Option<String> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            let h = std::str::from_utf8(b.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(h, 16).ok()?);
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

fn mime(p: &Path) -> &'static str {
    match p.extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase().as_str() {
        "html" | "htm" => "text/html; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "js" | "mjs" => "text/javascript; charset=utf-8",
        "json" => "application/json",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "ico" => "image/x-icon",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        "txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    }
}

fn status(code: StatusCode, text: &'static str) -> Response {
    let mut r = Response::new(Body::from(text));
    *r.status_mut() = code;
    r
}

async fn answer(root: &Path, uri: &Uri) -> Response {
    let Some(rel) = resolve(uri.path()) else { return status(StatusCode::NOT_FOUND, "Not found") };
    let mut file = root.join(&rel);
    if file.is_dir() {
        file = file.join("index.html");
    }
    // Symlinks must not lead out of the folder.
    let (Ok(target), Ok(top)) = (std::fs::canonicalize(&file), std::fs::canonicalize(root)) else {
        return status(StatusCode::NOT_FOUND, "Not found");
    };
    if !target.starts_with(&top) {
        return status(StatusCode::NOT_FOUND, "Not found");
    }
    match tokio::fs::read(&target).await {
        Ok(bytes) => {
            let mut r = Response::new(Body::from(bytes));
            r.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static(mime(&target)));
            r.headers_mut().insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
            r
        }
        Err(_) => status(StatusCode::NOT_FOUND, "Not found"),
    }
}

/// A running server; stops when dropped.
pub struct Server {
    pub port: u16,
    task: tokio::task::JoinHandle<()>,
}

impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}

/// Serves `root` on a free 127.0.0.1 port.
pub async fn serve(root: PathBuf) -> Result<Server> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let port = listener.local_addr()?.port();
    let app = axum::Router::new().fallback(move |uri: Uri| {
        let root = root.clone();
        async move { answer(&root, &uri).await }
    });
    let task = tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
    Ok(Server { port, task })
}

static LIVE: LazyLock<Mutex<HashMap<PathBuf, Server>>> = LazyLock::new(Mutex::default);

/// The long-lived server for "See it live" on `root` (reused while it lives; at most a few).
pub async fn live(root: &Path) -> Result<u16> {
    if let Some(s) = LIVE.lock().map_err(|_| anyhow::anyhow!("lock"))?.get(root) {
        return Ok(s.port);
    }
    let s = serve(root.to_owned()).await?;
    let port = s.port;
    let mut all = LIVE.lock().map_err(|_| anyhow::anyhow!("lock"))?;
    if all.len() >= 4
        && let Some(k) = all.keys().next().cloned()
    {
        all.remove(&k);
    }
    all.insert(root.to_owned(), s);
    Ok(port)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn never_dotfiles_or_escapes() {
        assert_eq!(resolve("/waitlist.html"), Some(PathBuf::from("waitlist.html")));
        assert_eq!(resolve("/a/b.css?x=1"), Some(PathBuf::from("a").join("b.css")));
        assert_eq!(resolve("/"), Some(PathBuf::new()));
        for bad in ["/.env", "/.env.local", "/.git/config", "/../x", "/a/%2e%2e/x", "/%2eenv", "/C:/x", "/a\\..\\b"] {
            assert_eq!(resolve(bad), None, "{bad}");
        }
    }

    #[tokio::test]
    async fn serves_pages_but_not_secrets() {
        let d = std::env::temp_dir().join(format!("hypurr-static-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("index.html"), "<h1>hi</h1>").unwrap();
        std::fs::write(d.join(".env"), "SECRET=1").unwrap();
        let s = serve(d.clone()).await.unwrap();
        let get = |p: &str| reqwest::get(format!("http://127.0.0.1:{}{p}", s.port));
        let ok = get("/").await.unwrap();
        assert_eq!(ok.status(), 200);
        assert!(ok.text().await.unwrap().contains("hi"));
        assert_eq!(get("/.env").await.unwrap().status(), 404);
        let _ = std::fs::remove_dir_all(d);
    }
}
