//! "Trace a click": open a local or staging page in a browser Hypurr controls, record what a
//! click does (handler, every API call with redacted bodies) and map it back to the frontend
//! line and the backend route in the repo. Saved as plain words under `.hypurr/trace/`, with an
//! optional Playwright test of the flow. Everything is a draft on the laptop; nothing is posted.

pub mod cdp;
pub mod chain;
pub mod locate;
pub mod model;
pub mod policy;
pub mod redact;
pub mod sourcemap;

use crate::hub::Hub;
use crate::store::Store;
use anyhow::{Result, anyhow, bail, ensure};
use model::Trace;
use policy::HostKind;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;
use tokio::sync::watch;

/// A trace window stays open at most this long.
const MAX_RECORD: Duration = Duration::from_secs(10 * 60);

static RUNNING: LazyLock<Mutex<HashMap<String, watch::Sender<bool>>>> = LazyLock::new(Mutex::default);

fn allow_key(project: &str) -> String {
    format!("trace:allowed:{project}")
}

fn hosts_key(project: &str) -> String {
    format!("trace:hosts:{project}")
}

pub fn approved_hosts(store: &Store, project: &str) -> Vec<String> {
    store.kv_get(&hosts_key(project)).and_then(|v| serde_json::from_str(&v).ok()).unwrap_or_default()
}

/// What still needs the user's OK before this URL may be traced (None = ready).
pub fn needs_approval(store: &Store, project: &str, url: &str) -> Result<Option<Value>> {
    let host = policy::host_of(url).ok_or_else(|| anyhow!("That isn't a web address."))?;
    ensure!(
        url.starts_with("http://") || url.starts_with("https://"),
        "Use an http:// or https:// address of your local or staging site."
    );
    let kind = policy::classify(&host);
    ensure!(
        kind != HostKind::Production,
        "{host} looks like production. Hypurr only traces localhost and staging sites."
    );
    let project_ok = store.kv_get(&allow_key(project)).is_some_and(|v| v == "1");
    let host_ok = kind != HostKind::Other || approved_hosts(store, project).contains(&host);
    if project_ok && host_ok {
        return Ok(None);
    }
    let why = if kind == HostKind::Other {
        format!(
            "{host} isn't localhost and doesn't look like staging. Only allow it if it's a test site you're meant to use."
        )
    } else {
        "Hypurr opens the page in its own browser on this laptop and records the calls. Nothing is uploaded.".to_owned()
    };
    Ok(Some(json!({"status": "needsApproval", "host": host, "hostKind": kind.as_str(), "why": why})))
}

fn trace_root(project: &Path) -> Result<PathBuf> {
    let d = project.join(".hypurr").join("trace");
    std::fs::create_dir_all(&d)?;
    std::fs::write(d.join(".gitignore"), "*\n")?;
    Ok(d)
}

fn safe_id(id: &str) -> Result<&str> {
    ensure!(
        !id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-'),
        "bad trace id"
    );
    Ok(id)
}

fn save(project: &Path, t: &Trace) -> Result<()> {
    let d = trace_root(project)?.join(safe_id(&t.id)?);
    std::fs::create_dir_all(&d)?;
    std::fs::write(d.join("trace.json"), serde_json::to_string_pretty(t)?)?;
    if t.status == "done" {
        std::fs::write(d.join("trace.md"), chain::markdown(t))?;
    }
    Ok(())
}

pub fn load(project: &Path, id: &str) -> Result<Trace> {
    let p = project.join(".hypurr/trace").join(safe_id(id)?).join("trace.json");
    let s = std::fs::read_to_string(p).map_err(|_| anyhow!("That trace isn't there any more."))?;
    Ok(serde_json::from_str(&s)?)
}

fn now() -> i64 {
    chrono::Utc::now().timestamp()
}

fn project_dir(store: &Store, b: &Value) -> Result<PathBuf> {
    crate::learn::guide::project_dir(store, b)
}

fn steps(b: &Value) -> Vec<String> {
    b["steps"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .take(20)
        .map(str::to_owned)
        .collect()
}

/// Record, resolve and save one trace (runs in the background).
pub async fn run(dir: PathBuf, mut t: Trace, exe: PathBuf, opts: cdp::Options, stop: watch::Receiver<bool>) -> Trace {
    let profile = dir.join(".hypurr/trace").join(&t.id).join("browser-profile");
    let rec = cdp::record(&exe, &profile, opts, stop).await;
    // The browser may still be letting go of its files.
    for _ in 0..20 {
        if std::fs::remove_dir_all(&profile).is_ok() || !profile.exists() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
    match rec {
        Ok(r) => {
            t.browser = r.browser;
            t.clicks = r.clicks;
            t.blocked_hosts = r.blocked_hosts;
            let (d2, mut t2, maps) = (dir.clone(), t.clone(), r.maps);
            t = tokio::task::spawn_blocking(move || {
                let repo = locate::Repo::scan(&d2);
                model::resolve(&mut t2, &repo, &maps);
                t2
            })
            .await
            .unwrap_or(t);
            t.status = "done".into();
            if !r.missing_steps.is_empty() {
                t.error = Some(format!("Couldn't find: {}", r.missing_steps.join(", ")));
            }
        }
        Err(e) => {
            t.status = "failed".into();
            t.error = Some(redact::text(&format!("{e:#}")));
        }
    }
    let _ = save(&dir, &t);
    t
}

/// `traceStart {project, url, steps?, headless?}`.
pub fn start(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let dir = project_dir(&hub.store, b)?;
    let project = dir.to_string_lossy().into_owned();
    let url = b["url"].as_str().unwrap_or_default().trim().to_owned();
    if let Some(ask) = needs_approval(&hub.store, &project, &url)? {
        return Ok(ask);
    }
    let exe = cdp::find_browser().ok_or_else(|| {
        anyhow!("Hypurr needs Chrome or Edge on this laptop to trace a page, and couldn't find either.")
    })?;
    let id = uuid::Uuid::new_v4().simple().to_string()[..12].to_owned();
    let t = Trace {
        id: id.clone(),
        project: project.clone(),
        url: redact::url(&url),
        at: now(),
        status: "recording".into(),
        ..Trace::default()
    };
    save(&dir, &t)?;
    let st = steps(b);
    let opts = cdp::Options {
        url,
        approved: approved_hosts(&hub.store, &project),
        headless: b["headless"].as_bool().unwrap_or(!st.is_empty()),
        steps: st,
        max: MAX_RECORD,
    };
    let (tx, rx) = watch::channel(false);
    RUNNING.lock().map_err(|_| anyhow!("busy"))?.insert(id.clone(), tx);
    let t2 = t.clone();
    tokio::spawn(async move {
        run(dir, t2, exe, opts, rx).await;
        if let Ok(mut m) = RUNNING.lock() {
            m.remove(&id);
        }
    });
    Ok(json!({"status": "recording", "trace": t}))
}

/// `traceAllow {project, url}`: remember the project (and a non-staging host), then start.
pub fn allow(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let dir = project_dir(&hub.store, b)?;
    let project = dir.to_string_lossy().into_owned();
    let url = b["url"].as_str().unwrap_or_default();
    let host = policy::host_of(url).ok_or_else(|| anyhow!("That isn't a web address."))?;
    match policy::classify(&host) {
        HostKind::Production => bail!("{host} looks like production. Hypurr only traces localhost and staging sites."),
        HostKind::Other => {
            let mut hosts = approved_hosts(&hub.store, &project);
            if !hosts.contains(&host) {
                hosts.push(host);
            }
            hub.store.kv_set(&hosts_key(&project), &serde_json::to_string(&hosts)?)?;
        }
        HostKind::Local | HostKind::Staging => {}
    }
    hub.store.kv_set(&allow_key(&project), "1")?;
    start(hub, b)
}

/// `traceStop {id}`: close the window and finish the trace.
pub fn stop(b: &Value) -> Result<Value> {
    let id = b["id"].as_str().ok_or_else(|| anyhow!("missing id"))?;
    if let Some(tx) = RUNNING.lock().map_err(|_| anyhow!("busy"))?.get(id) {
        let _ = tx.send(true);
    }
    Ok(json!({}))
}

/// `traceGet {project, id}`.
pub fn get(store: &Store, b: &Value) -> Result<Value> {
    let dir = project_dir(store, b)?;
    Ok(json!({"trace": load(&dir, b["id"].as_str().unwrap_or_default())?}))
}

/// `traceList {project}`: newest first.
pub fn list(store: &Store, b: &Value) -> Result<Value> {
    let dir = project_dir(store, b)?;
    let mut out: Vec<Value> = std::fs::read_dir(dir.join(".hypurr/trace"))
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| load(&dir, &e.file_name().to_string_lossy()).ok())
        .map(|t| {
            json!({"id": t.id, "url": t.url, "at": t.at, "status": t.status,
                "clicks": t.clicks.len(), "calls": t.clicks.iter().map(|c| c.calls.len()).sum::<usize>(),
                "first": t.clicks.first().map(|c| c.label.clone())})
        })
        .collect();
    out.sort_by(|a, b| b["at"].as_i64().cmp(&a["at"].as_i64()));
    Ok(json!({"traces": out}))
}

/// `traceExport {project, id}`: the flow as a Playwright test, saved next to the trace.
pub fn export(store: &Store, b: &Value) -> Result<Value> {
    let dir = project_dir(store, b)?;
    let t = load(&dir, b["id"].as_str().unwrap_or_default())?;
    ensure!(t.status == "done" && !t.clicks.is_empty(), "This trace has no clicks to turn into a test.");
    let code = chain::playwright(&t);
    let path = dir.join(".hypurr/trace").join(&t.id).join("flow.spec.js");
    std::fs::write(&path, &code)?;
    Ok(json!({"code": code, "path": path.to_string_lossy()}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn approvals() {
        let tmp = std::env::temp_dir().join(format!("hypurr-trace-db-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&tmp).expect("dir");
        let store = Store::open(&tmp.join("t.db")).expect("store");
        let p = "/work/shop";
        let ask = needs_approval(&store, p, "http://localhost:5173/orders").expect("ok").expect("asks once");
        assert_eq!(ask["hostKind"], "local");
        store.kv_set(&allow_key(p), "1").expect("kv");
        assert!(needs_approval(&store, p, "http://localhost:5173/orders").expect("ok").is_none());
        assert!(needs_approval(&store, p, "https://qa.shop.in/").expect("ok").is_none());
        let ask = needs_approval(&store, p, "https://shop.example.com/").expect("ok").expect("other host asks");
        assert_eq!(ask["hostKind"], "other");
        store.kv_set(&hosts_key(p), "[\"shop.example.com\"]").expect("kv");
        assert!(needs_approval(&store, p, "https://shop.example.com/").expect("ok").is_none());
        // Another project doesn't inherit it.
        assert!(needs_approval(&store, "/work/other", "https://shop.example.com/").expect("ok").is_some());
        assert!(needs_approval(&store, p, "https://app.prod.shop.in/").is_err());
        assert!(needs_approval(&store, p, "file:///etc/hosts").is_err());
    }

    #[test]
    fn saves_and_lists_inside_the_project() {
        let dir = std::env::temp_dir().join(format!("hypurr-trace-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).expect("dir");
        let t =
            Trace { id: "abc123".into(), url: "http://localhost/".into(), status: "done".into(), ..Trace::default() };
        save(&dir, &t).expect("save");
        assert_eq!(std::fs::read_to_string(dir.join(".hypurr/trace/.gitignore")).expect("ignore"), "*\n");
        assert!(dir.join(".hypurr/trace/abc123/trace.md").is_file());
        assert_eq!(load(&dir, "abc123").expect("load").url, "http://localhost/");
        assert!(load(&dir, "../../etc").is_err());
        let _ = std::fs::remove_dir_all(dir);
    }

    const SHOP_HTML: &str = "<!doctype html><html><body><h1>Orders</h1><button id=\"place\">Place order</button><p id=\"done\"></p>\
<script src=\"/src/shop.js\"></script></body></html>";
    const SHOP_JS: &str = "const done = document.getElementById('done');\n\
\n\
function handlePlaceOrder() {\n\
  fetch('https://www.google-analytics.com/collect?v=1').catch(() => {});\n\
  fetch('/api/orders', {\n\
    method: 'POST',\n\
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer secret-token-123' },\n\
    body: JSON.stringify({ sku: 'SOCK-RED', qty: 2, email: 'ravi@corp.in' }),\n\
  }).then((r) => r.json()).then((o) => { done.textContent = 'Order ' + o.id + ' placed'; });\n\
}\n\
\n\
document.getElementById('place').addEventListener('click', handlePlaceOrder);\n";

    /// Drives the real Chrome/Edge on this machine. Run with `cargo test -- --ignored live_trace`.
    #[tokio::test]
    #[ignore = "needs Chrome or Edge"]
    async fn live_trace() {
        use axum::{Json, Router, routing::get, routing::post};
        let Some(exe) = cdp::find_browser() else { return };
        let dir = locate::tests::temp_repo(&[
            ("web/src/shop.js", SHOP_JS),
            (
                "server/routes.js",
                "const router = require('express').Router();\nrouter.post('/api/orders', function placeOrder(req, res) {\n  res.status(201).json({ id: 7 });\n});\n",
            ),
        ]);
        let app = Router::new()
            .route("/orders", get(|| async { axum::response::Html(SHOP_HTML) }))
            .route("/src/shop.js", get(|| async { ([("content-type", "text/javascript")], SHOP_JS) }))
            .route(
                "/api/orders",
                post(|| async { (axum::http::StatusCode::CREATED, Json(json!({"id": 7, "token": "abc"}))) }),
            );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let port = listener.local_addr().expect("addr").port();
        tokio::spawn(async move { axum::serve(listener, app).await.ok() });
        let url = format!("http://localhost:{port}/orders");
        let t = Trace { id: "live1".into(), url: url.clone(), status: "recording".into(), ..Trace::default() };
        let opts = cdp::Options {
            url,
            approved: vec![],
            headless: true,
            steps: vec!["Place order".into()],
            max: Duration::from_secs(60),
        };
        let (_tx, rx) = watch::channel(false);
        let t = run(dir.clone(), t, exe, opts, rx).await;
        assert_eq!(t.status, "done", "{:?}", t.error);
        assert_eq!(t.clicks.len(), 1);
        let c = &t.clicks[0];
        assert_eq!((c.label.as_str(), c.tag.as_str(), c.page.as_str()), ("Place order", "button", "/orders"));
        assert_eq!(c.handler.as_ref().and_then(model::CodeRef::place).as_deref(), Some("web/src/shop.js:3"), "{c:?}");
        let call = c.calls.iter().find(|x| x.url.ends_with("/api/orders")).expect("orders call");
        assert_eq!((call.method.as_str(), call.status), ("POST", Some(201)));
        assert!(call.request_body.contains("SOCK-RED") && !call.request_body.contains("ravi@corp.in"));
        assert!(call.response_body.contains("[redacted]") && !call.response_body.contains("abc"));
        assert!(call.request_headers.iter().any(|(k, v)| k == "Authorization" && v == "[redacted]"));
        assert_eq!(call.sent_from.as_ref().and_then(model::CodeRef::place).as_deref(), Some("web/src/shop.js:5"));
        assert_eq!(call.handled_by.as_ref().map(|r| r.file.as_str()), Some("server/routes.js"));
        assert!(t.blocked_hosts.contains(&"www.google-analytics.com".to_owned()), "{:?}", t.blocked_hosts);
        assert_eq!(c.appeared, vec!["Order 7 placed".to_owned()]);
        assert!(dir.join(".hypurr/trace/live1/trace.md").is_file());
        assert!(!dir.join(".hypurr/trace/live1/browser-profile").exists());
        println!("{}", chain::markdown(&t));
        let _ = std::fs::remove_dir_all(dir);
    }

    /// Trace any running app: `HYPURR_LIVE_URL`, `HYPURR_LIVE_REPO`, `HYPURR_LIVE_STEPS` (a|b).
    #[tokio::test]
    #[ignore = "needs a running app and Chrome or Edge"]
    async fn live_trace_url() {
        let (Ok(url), Ok(repo), Some(exe)) =
            (std::env::var("HYPURR_LIVE_URL"), std::env::var("HYPURR_LIVE_REPO"), cdp::find_browser())
        else {
            return;
        };
        let steps = std::env::var("HYPURR_LIVE_STEPS")
            .unwrap_or_default()
            .split('|')
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .collect();
        let id = format!("live{}", uuid::Uuid::new_v4().simple());
        let t = Trace { id, url: url.clone(), status: "recording".into(), ..Trace::default() };
        let opts = cdp::Options { url, approved: vec![], headless: true, steps, max: Duration::from_secs(90) };
        let (_tx, rx) = watch::channel(false);
        let t = run(PathBuf::from(repo), t, exe, opts, rx).await;
        println!("{}\n\n{}", chain::markdown(&t), chain::playwright(&t));
        assert_eq!(t.status, "done", "{:?}", t.error);
    }
}
