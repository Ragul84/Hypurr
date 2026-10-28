//! Live preview: dev servers an agent starts on this computer (`http://localhost:5173` in its
//! output), shown in the phone app. The phone never reaches the PC's network directly: each
//! request travels over the existing end-to-end encrypted channel (direct or relay) as a
//! `previewFetch` call, and the host fetches it from loopback only.
//!
//! Limits: only loopback, only ports this bot's output mentioned and that are listening now,
//! plain HTTP request/response (no web sockets, so hot reload falls back to a manual refresh).

use crate::LockExt;
use crate::store::{Entry, Store};
use anyhow::{Context, Result, bail};
use base64::Engine as _;
use regex::Regex;
use serde_json::{Value, json};
use std::collections::{BTreeSet, HashMap};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

/// Largest body slice per reply (the channel's messages are ≤ 1 MiB after base64 and framing).
pub const SLICE: usize = 384 * 1024;
/// Largest response the host will hold for paging.
const MAX_RESPONSE: usize = 32 * 1024 * 1024;
/// Largest request body from the phone.
const MAX_REQUEST: usize = 512 * 1024;
const CACHE_FOR: Duration = Duration::from_secs(30);

static URL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)\bhttps?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\])(?::(\d{2,5}))?").expect("valid regex")
});

/// Ports of local dev servers mentioned in `entries` (newest first, deduplicated).
pub fn mentioned_ports(entries: &[Entry]) -> Vec<u16> {
    let mut seen = BTreeSet::new();
    let mut out = Vec::new();
    for e in entries.iter().rev() {
        let text = e.data.to_string();
        for c in URL.captures_iter(&text) {
            let port = c.get(1).and_then(|m| m.as_str().parse::<u16>().ok()).unwrap_or(80);
            if allowed_port(port) && seen.insert(port) {
                out.push(port);
            }
        }
    }
    out
}

/// Never the host's own API, and nothing below 1024 except plain port 80.
fn allowed_port(port: u16) -> bool {
    (port == 80 || port >= 1024) && !own_ports().contains(&port)
}

static OWN_PORT: std::sync::atomic::AtomicU16 = std::sync::atomic::AtomicU16::new(0);

/// The port this host serves its API on (never previewed).
pub fn set_own_port(port: u16) {
    OWN_PORT.store(port, std::sync::atomic::Ordering::Relaxed);
}

fn own_ports() -> [u16; 2] {
    [crate::service::DEFAULT_PORT, OWN_PORT.load(std::sync::atomic::Ordering::Relaxed)]
}

/// The loopback address the dev server answers on. Node on Windows binds `localhost` to `::1`
/// only (Vite's default), so IPv4 alone misses it.
async fn loopback(port: u16) -> Option<&'static str> {
    for (ip, host) in [("127.0.0.1", "127.0.0.1"), ("::1", "[::1]")] {
        let connect = tokio::net::TcpStream::connect((ip, port));
        if matches!(tokio::time::timeout(Duration::from_millis(400), connect).await, Ok(Ok(_))) {
            return Some(host);
        }
    }
    None
}

async fn listening(port: u16) -> bool {
    loopback(port).await.is_some()
}

/// Dev servers this bot started that are up right now: `[{port, url}]`.
pub async fn ports(store: &Store, bot: &str) -> Result<Value> {
    let entries = store.history(bot, i64::MAX, 300)?;
    let mut out = Vec::new();
    for port in mentioned_ports(&entries) {
        if listening(port).await {
            out.push(json!({"port": port, "url": format!("http://localhost:{port}/")}));
        }
    }
    Ok(json!({"previews": out}))
}

struct Cached {
    at: Instant,
    status: u16,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

static CACHE: LazyLock<Mutex<HashMap<String, Cached>>> = LazyLock::new(Mutex::default);

/// Request headers the phone may pass on (cookies so a dev login works; nothing hop-by-hop).
fn pass_request_header(name: &str) -> bool {
    matches!(
        name.to_ascii_lowercase().as_str(),
        "accept"
            | "accept-language"
            | "content-type"
            | "cookie"
            | "authorization"
            | "if-none-match"
            | "if-modified-since"
    ) || name.to_ascii_lowercase().starts_with("x-")
}

fn pass_response_header(name: &str) -> bool {
    !matches!(
        name.to_ascii_lowercase().as_str(),
        "connection" | "keep-alive" | "transfer-encoding" | "content-length" | "content-encoding" | "upgrade"
    )
}

/// Validates a phone's preview request; returns (port, path, method).
pub fn check_request(b: &Value) -> Result<(u16, String, String)> {
    let port = u16::try_from(b["port"].as_u64().context("port is required")?).context("bad port")?;
    if !allowed_port(port) {
        bail!("That port can't be previewed.");
    }
    let path = b["path"].as_str().unwrap_or("/");
    if !path.starts_with('/') || path.starts_with("//") || path.contains(['\r', '\n', '#']) {
        bail!("bad path");
    }
    let method = b["method"].as_str().unwrap_or("GET").to_ascii_uppercase();
    if !matches!(method.as_str(), "GET" | "HEAD" | "POST" | "PUT" | "PATCH" | "DELETE" | "OPTIONS") {
        bail!("unsupported method");
    }
    Ok((port, path.to_owned(), method))
}

/// One preview request, from loopback. `offset` pages through a large body.
pub async fn fetch(store: &Store, b: &Value) -> Result<Value> {
    let bot = b["botId"].as_str().context("botId is required")?;
    let (port, path, method) = check_request(b)?;
    let offset = usize::try_from(b["offset"].as_u64().unwrap_or(0)).unwrap_or(usize::MAX);
    let key = format!("{bot} {port} {method} {path}");
    if offset == 0 {
        // Only ports this bot's own output mentioned.
        if !mentioned_ports(&store.history(bot, i64::MAX, 300)?).contains(&port) {
            bail!("That server isn't one this agent started.");
        }
        let body = match b["body"].as_str() {
            Some(s) => base64::engine::general_purpose::STANDARD.decode(s).context("invalid body")?,
            None => Vec::new(),
        };
        if body.len() > MAX_REQUEST {
            bail!("request too large");
        }
        let client = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(30))
            .build()?;
        let ip = loopback(port).await.ok_or_else(|| anyhow::anyhow!("The dev server on port {port} didn't answer."))?;
        let mut req = client
            .request(method.parse()?, format!("http://{ip}:{port}{path}"))
            .header("host", format!("localhost:{port}"));
        for h in b["headers"].as_array().into_iter().flatten() {
            if let (Some(k), Some(v)) = (h[0].as_str(), h[1].as_str())
                && pass_request_header(k)
            {
                req = req.header(k, v);
            }
        }
        if !body.is_empty() {
            req = req.body(body);
        }
        let res = req.send().await.map_err(|_| anyhow::anyhow!("The dev server on port {port} didn't answer."))?;
        let status = res.status().as_u16();
        let headers = res
            .headers()
            .iter()
            .filter(|(k, _)| pass_response_header(k.as_str()))
            .filter_map(|(k, v)| Some((k.as_str().to_owned(), v.to_str().ok()?.to_owned())))
            .collect();
        let body = res.bytes().await?;
        if body.len() > MAX_RESPONSE {
            bail!("That page is too large to preview.");
        }
        let mut cache = CACHE.locked();
        cache.retain(|_, c| c.at.elapsed() < CACHE_FOR);
        cache.insert(key.clone(), Cached { at: Instant::now(), status, headers, body: body.to_vec() });
    }
    let cache = CACHE.locked();
    let c = cache.get(&key).context("That preview expired. Reload it.")?;
    let start = offset.min(c.body.len());
    let end = (start + SLICE).min(c.body.len());
    Ok(json!({
        "status": c.status,
        "headers": c.headers,
        "body": base64::engine::general_purpose::STANDARD.encode(&c.body[start..end]),
        "offset": start,
        "total": c.body.len(),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(text: &str) -> Entry {
        Entry {
            id: "e".into(),
            seq: 1,
            bot_id: "b".into(),
            thread_id: None,
            rev: 1,
            kind: "tool".into(),
            turn: 1,
            data: json!({"text": text}),
            created_at: 0,
            updated_at: 0,
        }
    }

    #[test]
    fn finds_dev_servers_in_agent_output() {
        let e = [
            entry("VITE ready in 300 ms  ➜  Local:   http://localhost:5173/"),
            entry("Started on http://127.0.0.1:8000 and http://0.0.0.0:3000/api, again http://localhost:5173"),
            entry("see https://example.com:8080 and localhost:9999 without a scheme"),
        ];
        assert_eq!(mentioned_ports(&e), vec![8000, 3000, 5173]);
    }

    #[test]
    fn never_the_hosts_own_port_or_privileged_ports() {
        set_own_port(47_917);
        assert!(!allowed_port(47_917));
        assert!(!allowed_port(crate::service::DEFAULT_PORT));
        assert!(!allowed_port(22));
        assert!(allowed_port(80));
        assert!(allowed_port(5173));
    }

    #[test]
    fn requests_are_checked() {
        assert!(check_request(&json!({"port": 5173, "path": "/a?b=1"})).is_ok());
        assert!(check_request(&json!({"port": 5173, "path": "//evil.com/"})).is_err());
        assert!(check_request(&json!({"port": 5173, "path": "http://x/"})).is_err());
        assert!(check_request(&json!({"port": 5173, "path": "/\r\nHost: x"})).is_err());
        assert!(check_request(&json!({"port": 22, "path": "/"})).is_err());
        assert!(check_request(&json!({"port": 5173, "method": "CONNECT"})).is_err());
        assert!(!pass_request_header("host"));
        assert!(pass_request_header("Cookie"));
        assert!(!pass_response_header("Transfer-Encoding"));
    }

    #[tokio::test]
    async fn fetches_from_loopback_and_pages_large_bodies() {
        use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let big = "x".repeat(SLICE + 10);
        let body = big.clone();
        tokio::spawn(async move {
            while let Ok((mut s, _)) = listener.accept().await {
                let mut buf = [0u8; 4096];
                let _ = s.read(&mut buf).await;
                let reply = format!(
                    "HTTP/1.1 200 OK\r\ncontent-type: text/html\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = s.write_all(reply.as_bytes()).await;
            }
        });
        let store = Store::open(std::path::Path::new(":memory:")).unwrap();
        let bot = "b1";
        let text = format!("server at http://localhost:{port}/");
        store
            .insert_entry(&crate::store::Lane::main(bot), crate::store::EntryKind::Agent, 1, &json!({"text": text}))
            .unwrap();
        let first = fetch(&store, &json!({"botId": bot, "port": port, "path": "/"})).await.unwrap();
        assert_eq!(first["status"], 200);
        assert_eq!(first["total"], big.len());
        let second = fetch(&store, &json!({"botId": bot, "port": port, "path": "/", "offset": SLICE})).await.unwrap();
        assert_eq!(
            base64::engine::general_purpose::STANDARD.decode(second["body"].as_str().unwrap()).unwrap().len(),
            10
        );
        assert!(fetch(&store, &json!({"botId": bot, "port": port + 1, "path": "/"})).await.is_err());
        let found = ports(&store, bot).await.unwrap();
        assert_eq!(found["previews"][0]["port"], port);
    }

    #[tokio::test]
    async fn finds_servers_bound_to_ipv6_loopback_only() {
        // Vite on Windows binds `localhost` to ::1 only.
        let Ok(listener) = tokio::net::TcpListener::bind("[::1]:0").await else { return };
        let port = listener.local_addr().unwrap().port();
        assert_eq!(loopback(port).await, Some("[::1]"));
    }
}
