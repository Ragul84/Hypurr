//! The browser Hypurr controls for a trace: the Chrome or Edge already on the laptop, driven
//! over the Chrome `DevTools` Protocol. Nothing is installed and nothing leaves the laptop: the
//! profile is a throw-away folder inside `.hypurr/trace/`, and every request the page makes
//! is checked against the project's allowed hosts (others are blocked in the browser).

use super::model::{Call, Click, Frame};
use super::redact;
use super::sourcemap::{self, SourceMap};
use anyhow::{Context as _, Result, anyhow, bail};
use futures::{SinkExt as _, StreamExt as _};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;
use tokio::sync::{Mutex, mpsc, oneshot, watch};
use tokio::time::Instant;
use tokio_tungstenite::tungstenite::Message;

const CLICK_JS: &str = include_str!("recorder.js");

/// Finds the React (or plain DOM) click handler for the clicked element.
const HANDLER_FN: &str = "function(){for(let n=this;n;n=n.parentElement){const k=Object.keys(n).find(k=>k.startsWith('__reactProps$'));\
const p=k&&n[k];if(p&&typeof p.onClick==='function')return p.onClick;if(p&&typeof p.onSubmit==='function')return p.onSubmit;\
if(typeof n.onclick==='function')return n.onclick;}return null}";

const APPEARED_JS: &str = "(()=>{const b=new Set((window.__hypurrBefore||'').split('\\n').map(s=>s.trim()));\
return {url:location.href,text:(document.body?document.body.innerText:'').split('\\n').map(s=>s.trim())\
.filter(s=>s&&s.length<=80&&!b.has(s)).slice(0,5)}})()";

const CLICK_BY_TEXT: &str = "(label)=>{const w=label.trim().toLowerCase();\
const els=[...document.querySelectorAll('button,a,[role=button],input[type=submit],input[type=button],summary,label')];\
const t=e=>(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().toLowerCase();\
const el=els.find(e=>t(e)===w)||els.find(e=>t(e).includes(w));if(!el)return false;el.scrollIntoView({block:'center'});el.click();return true}";

/// How long after a click its API calls still count as part of it.
const CLICK_WINDOW: Duration = Duration::from_secs(8);
const SETTLE: Duration = Duration::from_millis(1800);

/// Chrome, Edge or Chromium, wherever the laptop already has one.
pub fn find_browser() -> Option<PathBuf> {
    if let Ok(p) = std::env::var("HYPURR_BROWSER") {
        let p = PathBuf::from(p);
        if p.is_file() {
            return Some(p);
        }
    }
    let fixed = [
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
    ];
    if let Some(p) = fixed.iter().map(PathBuf::from).find(|p| p.is_file()) {
        return Some(p);
    }
    if let Some(local) = dirs::data_local_dir() {
        let p = local.join(r"Google\Chrome\Application\chrome.exe");
        if p.is_file() {
            return Some(p);
        }
    }
    let path = std::env::var_os("PATH")?;
    for name in ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge", "msedge"] {
        for dir in std::env::split_paths(&path) {
            let p = dir.join(name);
            if p.is_file() {
                return Some(p);
            }
        }
    }
    None
}

type Pending = Arc<Mutex<HashMap<u64, oneshot::Sender<Value>>>>;

struct Cdp {
    tx: mpsc::UnboundedSender<String>,
    next: AtomicU64,
    pending: Pending,
}

impl Cdp {
    async fn connect(ws: &str) -> Result<(Self, mpsc::UnboundedReceiver<Value>)> {
        let (stream, _) = tokio::time::timeout(Duration::from_secs(15), tokio_tungstenite::connect_async(ws))
            .await
            .context("the browser didn't answer")??;
        let (mut sink, mut source) = stream.split();
        let (tx, mut rx) = mpsc::unbounded_channel::<String>();
        let (ev_tx, ev_rx) = mpsc::unbounded_channel::<Value>();
        let pending: Pending = Arc::default();
        tokio::spawn(async move {
            while let Some(m) = rx.recv().await {
                if sink.send(Message::Text(m.into())).await.is_err() {
                    break;
                }
            }
        });
        let p2 = pending.clone();
        tokio::spawn(async move {
            while let Some(Ok(m)) = source.next().await {
                let Message::Text(t) = m else { continue };
                let Ok(v) = serde_json::from_str::<Value>(t.as_str()) else { continue };
                if let Some(id) = v["id"].as_u64() {
                    if let Some(s) = p2.lock().await.remove(&id) {
                        let _ = s.send(v);
                    }
                } else if ev_tx.send(v).is_err() {
                    break;
                }
            }
            // Dropping ev_tx tells the recorder the browser went away.
        });
        Ok((Self { tx, next: AtomicU64::new(1), pending }, ev_rx))
    }

    async fn call(&self, method: &str, params: Value) -> Result<Value> {
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        let (s, r) = oneshot::channel();
        self.pending.lock().await.insert(id, s);
        self.tx
            .send(json!({"id": id, "method": method, "params": params}).to_string())
            .map_err(|_| anyhow!("the browser closed"))?;
        let v = tokio::time::timeout(Duration::from_secs(20), r).await.context("the browser stopped answering")??;
        if let Some(e) = v.get("error") {
            bail!("{method}: {}", e["message"].as_str().unwrap_or("failed"));
        }
        Ok(v["result"].clone())
    }
}

/// A running browser; closed and its profile removed when dropped.
struct Browser {
    child: tokio::process::Child,
    profile: PathBuf,
}

impl Drop for Browser {
    fn drop(&mut self) {
        let _ = self.child.start_kill();
        let _ = std::fs::remove_dir_all(&self.profile);
    }
}

async fn launch(exe: &Path, profile: &Path, headless: bool) -> Result<(Browser, u16)> {
    std::fs::create_dir_all(profile)?;
    let mut cmd = tokio::process::Command::new(exe);
    cmd.arg("--remote-debugging-port=0")
        .arg("--remote-debugging-address=127.0.0.1")
        .arg(format!("--user-data-dir={}", profile.display()))
        .args(["--no-first-run", "--no-default-browser-check", "--disable-sync", "--disable-extensions"])
        .args(["--disable-background-networking", "--disable-component-update", "--metrics-recording-only"])
        .arg("--window-size=1280,860")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true);
    if headless {
        cmd.args(["--headless=new", "--disable-gpu"]);
    }
    cmd.arg("about:blank");
    let child = cmd.spawn().with_context(|| format!("couldn't start {}", exe.display()))?;
    let b = Browser { child, profile: profile.to_owned() };
    let port_file = profile.join("DevToolsActivePort");
    let start = Instant::now();
    loop {
        if let Ok(s) = std::fs::read_to_string(&port_file)
            && let Some(port) = s.lines().next().and_then(|l| l.trim().parse::<u16>().ok())
        {
            return Ok((b, port));
        }
        if start.elapsed() > Duration::from_secs(20) {
            bail!("the browser didn't start");
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

async fn page_ws(port: u16) -> Result<String> {
    let http = reqwest::Client::builder().timeout(Duration::from_secs(5)).no_proxy().build()?;
    for _ in 0..50 {
        if let Ok(r) = http.get(format!("http://127.0.0.1:{port}/json/list")).send().await
            && let Ok(list) = r.json::<Vec<Value>>().await
            && let Some(ws) = list.iter().find(|t| t["type"] == "page").and_then(|t| t["webSocketDebuggerUrl"].as_str())
        {
            return Ok(ws.to_owned());
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    bail!("couldn't find the browser tab")
}

pub struct Options {
    pub url: String,
    /// Other hosts approved for this project (localhost and staging are always fine).
    pub approved: Vec<String>,
    pub headless: bool,
    /// Buttons to click by their words; empty = the user clicks.
    pub steps: Vec<String>,
    pub max: Duration,
}

#[derive(Default)]
pub struct Recording {
    pub clicks: Vec<Click>,
    pub blocked_hosts: Vec<String>,
    pub maps: HashMap<String, SourceMap>,
    pub browser: String,
    pub missing_steps: Vec<String>,
}

struct Req {
    click: usize,
    call: Call,
    start: f64,
    mime: String,
}

fn frames(initiator: &Value) -> Vec<Frame> {
    let mut out = Vec::new();
    let mut stack = initiator.get("stack");
    while let Some(s) = stack {
        for f in s["callFrames"].as_array().into_iter().flatten() {
            let url = f["url"].as_str().unwrap_or_default();
            if url.is_empty() {
                continue;
            }
            out.push(Frame {
                url: url.to_owned(),
                line: u32::try_from(f["lineNumber"].as_u64().unwrap_or(0)).unwrap_or(0),
                col: u32::try_from(f["columnNumber"].as_u64().unwrap_or(0)).unwrap_or(0),
                function: f["functionName"].as_str().unwrap_or_default().to_owned(),
            });
        }
        stack = s.get("parent");
    }
    out
}

struct Session {
    cdp: Cdp,
    approved: Vec<String>,
    rec: Recording,
    reqs: HashMap<String, Req>,
    scripts: HashMap<String, (String, String)>,
    last_click: Option<Instant>,
    settle_at: Option<Instant>,
}

impl Session {
    async fn handler(&mut self, idx: usize) {
        let Ok(el) = self
            .cdp
            .call("Runtime.evaluate", json!({"expression": "window.__hypurrLast", "objectGroup": "hypurr"}))
            .await
        else {
            return;
        };
        let Some(el_id) = el["result"]["objectId"].as_str().map(str::to_owned) else { return };
        let mut loc: Option<(String, u32, u32)> = None;
        let mut name = None;
        if let Ok(f) = self
            .cdp
            .call(
                "Runtime.callFunctionOn",
                json!({"objectId": el_id, "functionDeclaration": HANDLER_FN, "objectGroup": "hypurr"}),
            )
            .await
            && let Some(fid) = f["result"]["objectId"].as_str()
        {
            name = f["result"]["description"].as_str().and_then(fn_name);
            if let Ok(props) =
                self.cdp.call("Runtime.getProperties", json!({"objectId": fid, "ownProperties": true})).await
            {
                loc = props["internalProperties"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .find(|p| p["name"] == "[[FunctionLocation]]")
                    .map(|p| {
                        let v = &p["value"]["value"];
                        (
                            v["scriptId"].as_str().unwrap_or_default().to_owned(),
                            u32::try_from(v["lineNumber"].as_u64().unwrap_or(0)).unwrap_or(0),
                            u32::try_from(v["columnNumber"].as_u64().unwrap_or(0)).unwrap_or(0),
                        )
                    });
            }
        }
        if loc.is_none()
            && let Ok(l) = self.cdp.call("DOMDebugger.getEventListeners", json!({"objectId": el_id})).await
            && let Some(x) = l["listeners"].as_array().into_iter().flatten().find(|x| x["type"] == "click")
        {
            loc = Some((
                x["scriptId"].as_str().unwrap_or_default().to_owned(),
                u32::try_from(x["lineNumber"].as_u64().unwrap_or(0)).unwrap_or(0),
                u32::try_from(x["columnNumber"].as_u64().unwrap_or(0)).unwrap_or(0),
            ));
        }
        let _ = self.cdp.call("Runtime.releaseObjectGroup", json!({"objectGroup": "hypurr"})).await;
        let c = &mut self.rec.clicks[idx];
        c.handler_name.clone_from(&name);
        if let Some((sid, line, col)) = loc
            && let Some((url, _)) = self.scripts.get(&sid)
        {
            c.handler_frame = Some(Frame { url: url.clone(), line, col, function: name.unwrap_or_default() });
        }
    }

    async fn event(&mut self, v: &Value) {
        let p = &v["params"];
        match v["method"].as_str().unwrap_or_default() {
            "Fetch.requestPaused" => {
                let url = p["request"]["url"].as_str().unwrap_or_default();
                let id = p["requestId"].clone();
                if super::policy::allowed(url, &self.approved) {
                    let _ = self.cdp.call("Fetch.continueRequest", json!({"requestId": id})).await;
                } else {
                    if let Some(h) = super::policy::host_of(url)
                        && !self.rec.blocked_hosts.contains(&h)
                    {
                        self.rec.blocked_hosts.push(h);
                    }
                    let _ = self
                        .cdp
                        .call("Fetch.failRequest", json!({"requestId": id, "errorReason": "BlockedByClient"}))
                        .await;
                }
            }
            "Debugger.scriptParsed" => {
                let id = p["scriptId"].as_str().unwrap_or_default().to_owned();
                let url = p["url"].as_str().unwrap_or_default().to_owned();
                let map = p["sourceMapURL"].as_str().unwrap_or_default().to_owned();
                self.scripts.insert(id, (url, map));
            }
            "Runtime.bindingCalled" if p["name"] == "__hypurrClick" => {
                let d: Value = serde_json::from_str(p["payload"].as_str().unwrap_or("{}")).unwrap_or_default();
                self.rec.clicks.push(Click {
                    label: redact::text(d["label"].as_str().unwrap_or_default()),
                    tag: d["tag"].as_str().unwrap_or_default().to_owned(),
                    role: d["role"].as_str().map(str::to_owned),
                    selector: d["selector"].as_str().unwrap_or_default().to_owned(),
                    page: d["page"].as_str().unwrap_or_default().to_owned(),
                    ..Click::default()
                });
                let now = Instant::now();
                self.last_click = Some(now);
                self.settle_at = Some(now + SETTLE);
                let idx = self.rec.clicks.len() - 1;
                self.handler(idx).await;
            }
            "Network.requestWillBeSent" => {
                let kind = p["type"].as_str().unwrap_or_default();
                let Some(at) = self.last_click else { return };
                if !matches!(kind, "XHR" | "Fetch") || at.elapsed() > CLICK_WINDOW || self.rec.clicks.is_empty() {
                    return;
                }
                let r = &p["request"];
                let id = p["requestId"].as_str().unwrap_or_default().to_owned();
                let mut body = r["postData"].as_str().unwrap_or_default().to_owned();
                if body.is_empty()
                    && r["hasPostData"] == true
                    && let Ok(d) = self.cdp.call("Network.getRequestPostData", json!({"requestId": id})).await
                {
                    d["postData"].as_str().unwrap_or_default().clone_into(&mut body);
                }
                let call = Call {
                    method: r["method"].as_str().unwrap_or("GET").to_owned(),
                    url: redact::url(r["url"].as_str().unwrap_or_default()),
                    request_body: redact::body(&body),
                    request_headers: r["headers"].as_object().map(redact::headers).unwrap_or_default(),
                    initiator: frames(&p["initiator"]),
                    ..Call::default()
                };
                let start = p["timestamp"].as_f64().unwrap_or(0.0);
                self.reqs.insert(id, Req { click: self.rec.clicks.len() - 1, call, start, mime: String::new() });
                self.settle_at = Some(Instant::now() + SETTLE);
            }
            "Network.responseReceived" => {
                if let Some(r) = self.reqs.get_mut(p["requestId"].as_str().unwrap_or_default()) {
                    let res = &p["response"];
                    r.call.status = res["status"].as_u64().and_then(|s| u16::try_from(s).ok());
                    res["statusText"].as_str().unwrap_or_default().clone_into(&mut r.call.status_text);
                    res["mimeType"].as_str().unwrap_or_default().clone_into(&mut r.mime);
                }
            }
            "Network.loadingFinished" | "Network.loadingFailed" => {
                let id = p["requestId"].as_str().unwrap_or_default().to_owned();
                let Some(mut r) = self.reqs.remove(&id) else { return };
                let end = p["timestamp"].as_f64().unwrap_or(r.start);
                #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
                let ms = ((end - r.start).max(0.0) * 1000.0).round() as u64;
                r.call.ms = Some(ms);
                if v["method"] == "Network.loadingFailed" {
                    let e = p["errorText"].as_str().unwrap_or("failed");
                    r.call.blocked = e.contains("BLOCKED_BY_CLIENT");
                    r.call.failed = (!r.call.blocked).then(|| e.to_owned());
                    r.call.ms = None;
                } else if r.mime.contains("json") || r.mime.starts_with("text/") {
                    if let Ok(b) = self.cdp.call("Network.getResponseBody", json!({"requestId": id})).await
                        && b["base64Encoded"] != true
                    {
                        r.call.response_body = redact::body(b["body"].as_str().unwrap_or_default());
                    }
                } else if !r.mime.is_empty() {
                    r.call.response_body = format!("({})", r.mime);
                }
                if let Some(c) = self.rec.clicks.get_mut(r.click) {
                    c.calls.push(r.call);
                }
                self.settle_at = Some(Instant::now() + SETTLE);
            }
            _ => {}
        }
    }

    async fn settle(&mut self) {
        self.settle_at = None;
        if let Ok(v) =
            self.cdp.call("Runtime.evaluate", json!({"expression": APPEARED_JS, "returnByValue": true})).await
            && let Some(c) = self.rec.clicks.last_mut()
        {
            let v = &v["result"]["value"];
            c.appeared =
                v["text"].as_array().into_iter().flatten().filter_map(Value::as_str).map(redact::text).collect();
            c.url_after = v["url"].as_str().map(redact::url);
        }
    }

    async fn maps(&mut self) {
        let wanted: Vec<String> = self
            .rec
            .clicks
            .iter()
            .flat_map(|c| {
                c.handler_frame
                    .iter()
                    .map(|f| f.url.clone())
                    .chain(c.calls.iter().flat_map(|x| x.initiator.iter().map(|f| f.url.clone())))
            })
            .collect();
        let http = reqwest::Client::builder().timeout(Duration::from_secs(10)).no_proxy().build().ok();
        for (url, map) in self.scripts.values() {
            if map.is_empty() || !wanted.contains(url) || self.rec.maps.contains_key(url) {
                continue;
            }
            let json = if map.starts_with("data:") {
                sourcemap::data_url(map)
            } else {
                let full = join_url(url, map);
                match (&http, super::policy::allowed(&full, &self.approved)) {
                    (Some(h), true) => match h.get(&full).send().await {
                        Ok(r) if r.content_length().unwrap_or(0) < 30_000_000 => r.text().await.ok(),
                        _ => None,
                    },
                    _ => None,
                }
            };
            if let Some(m) = json.as_deref().and_then(SourceMap::parse) {
                self.rec.maps.insert(url.clone(), m);
            }
        }
    }
}

fn fn_name(desc: &str) -> Option<String> {
    let d = desc.trim_start().trim_start_matches("async ");
    let rest = d.strip_prefix("function")?.trim_start().trim_start_matches('*').trim_start();
    let name: String = rest.chars().take_while(|c| c.is_alphanumeric() || *c == '_' || *c == '$').collect();
    (!name.is_empty()).then_some(name)
}

/// A source map URL relative to its script.
pub fn join_url(script: &str, rel: &str) -> String {
    if rel.contains("://") {
        return rel.to_owned();
    }
    let (scheme, rest) = script.split_once("://").unwrap_or(("http", script));
    let host = rest.split('/').next().unwrap_or_default();
    if rel.starts_with('/') {
        return format!("{scheme}://{host}{rel}");
    }
    let dir = script.split(['?', '#']).next().unwrap_or_default();
    let dir = dir.rsplit_once('/').map_or(dir, |(d, _)| d);
    format!("{dir}/{rel}")
}

/// Open the page, record clicks until the window is closed, `stop` fires, the steps are done
/// or the time runs out.
pub async fn record(exe: &Path, profile: &Path, opts: Options, mut stop: watch::Receiver<bool>) -> Result<Recording> {
    let (mut browser, port) = launch(exe, profile, opts.headless).await?;
    let ws = page_ws(port).await?;
    let (cdp, mut events) = Cdp::connect(&ws).await?;
    let mut s = Session {
        cdp,
        approved: opts.approved.clone(),
        rec: Recording {
            browser: exe.file_stem().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
            ..Recording::default()
        },
        reqs: HashMap::new(),
        scripts: HashMap::new(),
        last_click: None,
        settle_at: None,
    };
    for (m, p) in [
        ("Fetch.enable", json!({"patterns": [{"urlPattern": "*", "requestStage": "Request"}]})),
        ("Page.enable", json!({})),
        ("Runtime.enable", json!({})),
        ("Debugger.enable", json!({})),
        ("Debugger.setSkipAllPauses", json!({"skip": true})),
        ("Network.enable", json!({"maxPostDataSize": 65536})),
        ("Runtime.addBinding", json!({"name": "__hypurrClick"})),
        ("Page.addScriptToEvaluateOnNewDocument", json!({"source": CLICK_JS})),
    ] {
        s.cdp.call(m, p).await.with_context(|| format!("browser setup ({m})"))?;
    }
    // Navigate without waiting: requests pause in Fetch until the loop lets them through.
    let nav = json!({"id": 0, "method": "Page.navigate", "params": {"url": opts.url}}).to_string();
    s.cdp.tx.send(nav).map_err(|_| anyhow!("the browser closed"))?;
    let deadline = Instant::now() + opts.max;
    let mut steps = opts.steps.clone().into_iter();
    let mut loaded = false;
    let mut next_step: Option<Instant> = None;
    let far = Instant::now() + Duration::from_secs(86_400);
    loop {
        tokio::select! {
            ev = events.recv() => {
                let Some(ev) = ev else { break };
                if ev["method"] == "Page.loadEventFired" && !loaded {
                    loaded = true;
                    if !opts.steps.is_empty() {
                        next_step = Some(Instant::now() + Duration::from_millis(800));
                    }
                }
                if ev["method"] == "Inspector.detached" || ev["method"] == "Target.targetDestroyed" {
                    break;
                }
                s.event(&ev).await;
            }
            () = tokio::time::sleep_until(s.settle_at.unwrap_or(far)), if s.settle_at.is_some() => {
                s.settle().await;
                if !opts.steps.is_empty() {
                    next_step = Some(Instant::now() + Duration::from_millis(300));
                }
            }
            () = tokio::time::sleep_until(next_step.unwrap_or(far)), if next_step.is_some() && s.settle_at.is_none() => {
                next_step = None;
                let Some(label) = steps.next() else { break };
                let expr = format!("({CLICK_BY_TEXT})({})", serde_json::to_string(&label)?);
                let r = s.cdp.call("Runtime.evaluate", json!({"expression": expr, "returnByValue": true})).await?;
                if r["result"]["value"] != true {
                    s.rec.missing_steps.push(label);
                    next_step = Some(Instant::now() + Duration::from_millis(300));
                }
            }
            _ = stop.changed() => break,
            () = tokio::time::sleep_until(deadline) => break,
        }
    }
    if s.settle_at.is_some() {
        s.settle().await;
    }
    // Calls still in flight keep what they have.
    let left: Vec<Req> = s.reqs.drain().map(|(_, r)| r).collect();
    for r in left {
        if let Some(c) = s.rec.clicks.get_mut(r.click) {
            c.calls.push(r.call);
        }
    }
    s.maps().await;
    let _ = tokio::time::timeout(Duration::from_secs(3), s.cdp.call("Browser.close", json!({}))).await;
    let _ = browser.child.kill().await;
    drop(browser);
    Ok(s.rec)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn helpers() {
        assert_eq!(fn_name("async function handlePlaceOrder() {"), Some("handlePlaceOrder".into()));
        assert_eq!(fn_name("() => setX(1)"), None);
        assert_eq!(
            join_url("http://localhost:5173/assets/index-abc.js?v=1", "index-abc.js.map"),
            "http://localhost:5173/assets/index-abc.js.map"
        );
        assert_eq!(join_url("http://h/a/b.js", "/maps/b.map"), "http://h/maps/b.map");
        let f = frames(
            &json!({"stack": {"callFrames": [{"url": "http://h/src/api.js", "lineNumber": 8, "columnNumber": 20, "functionName": "createOrder"}],
            "parent": {"callFrames": [{"url": "", "lineNumber": 0, "columnNumber": 0}, {"url": "http://h/src/pages/Orders.jsx", "lineNumber": 9, "columnNumber": 4, "functionName": "handlePlaceOrder"}]}}}),
        );
        assert_eq!(f.len(), 2);
        assert_eq!(f[1].function, "handlePlaceOrder");
    }
}
