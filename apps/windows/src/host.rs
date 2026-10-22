//! Starts `hypurr-host serve` with no console window and polls its local API.

use crate::model::{self, Snapshot};
use serde_json::{Value, json};
use std::path::PathBuf;
use std::process::{Child, Command};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const PORT: u16 = 19222;

pub fn data_dir() -> PathBuf {
    std::env::var_os("HYPURR_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| dirs::home_dir().unwrap_or_default().join(".hypurr"))
}

/// `hypurr-host(.exe)` next to this app, or `HYPURR_HOST_BIN`.
pub fn host_bin() -> PathBuf {
    if let Some(p) = std::env::var_os("HYPURR_HOST_BIN") {
        return p.into();
    }
    let name = if cfg!(windows) {
        "hypurr-host.exe"
    } else {
        "hypurr-host"
    };
    std::env::current_exe()
        .ok()
        .and_then(|e| e.parent().map(|d| d.join(name)))
        .unwrap_or_else(|| name.into())
}

pub fn no_window(cmd: &mut Command) -> &mut Command {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

struct Api {
    http: reqwest::blocking::Client,
}

impl Api {
    fn call(&self, method: &str) -> Result<Value, String> {
        self.call_with(method, &json!({}))
    }

    fn call_with(&self, method: &str, body: &Value) -> Result<Value, String> {
        let token = std::fs::read_to_string(data_dir().join("token"))
            .map_err(|_| "not started".to_owned())?;
        let res = self
            .http
            .post(format!("http://127.0.0.1:{PORT}/api/{method}"))
            .bearer_auth(token.trim())
            .json(body)
            .send()
            .map_err(|e| e.to_string())?;
        let ok = res.status().is_success();
        let v: Value = res.json().map_err(|e| e.to_string())?;
        if ok {
            Ok(v)
        } else {
            Err(v["error"].as_str().unwrap_or("the host refused").to_owned())
        }
    }
}

/// Owns the host process (if we started it) and the shared snapshot.
pub struct Host {
    pub state: Arc<Mutex<Snapshot>>,
    child: Arc<Mutex<Option<Child>>>,
    pub force_new_code: Arc<Mutex<bool>>,
    /// Remote screen on/off, applied on the next poll.
    pub set_screen: Arc<Mutex<Option<bool>>>,
}

impl Host {
    pub fn start(repaint: impl Fn() + Send + 'static) -> Self {
        let state = Arc::new(Mutex::new(Snapshot {
            starting: true,
            ..Default::default()
        }));
        let child = Arc::new(Mutex::new(None));
        let force = Arc::new(Mutex::new(false));
        let screen = Arc::new(Mutex::new(None));
        let (st, ch, fo, sc) = (state.clone(), child.clone(), force.clone(), screen.clone());
        std::thread::spawn(move || run(st, ch, fo, sc, repaint));
        Host {
            state,
            child,
            force_new_code: force,
            set_screen: screen,
        }
    }

    /// Stops the host only if this app started it.
    pub fn stop(&self) {
        if let Some(mut c) = self.child.lock().unwrap().take() {
            let _ = c.kill();
            let _ = c.wait();
        }
    }
}

fn spawn_host() -> Result<Child, String> {
    let bin = host_bin();
    no_window(Command::new(&bin).args(["serve", "--port", &PORT.to_string()]))
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|e| format!("Couldn't start {}: {e}", bin.display()))
}

fn run(
    state: Arc<Mutex<Snapshot>>,
    child: Arc<Mutex<Option<Child>>>,
    force: Arc<Mutex<bool>>,
    set_screen: Arc<Mutex<Option<bool>>>,
    repaint: impl Fn(),
) {
    let api = Api {
        http: reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(5))
            .build()
            .unwrap(),
    };
    let started = Instant::now();
    let mut last_net = None::<Instant>;
    loop {
        let hello = api.call("hello");
        if hello.is_err() && child.lock().unwrap().is_none() {
            match spawn_host() {
                Ok(c) => *child.lock().unwrap() = Some(c),
                Err(e) => state.lock().unwrap().error = Some(e),
            }
        }
        // Our host died: start it again.
        {
            let mut guard = child.lock().unwrap();
            if guard
                .as_mut()
                .is_some_and(|c| matches!(c.try_wait(), Ok(Some(_))))
            {
                *guard = None;
            }
        }
        {
            let mut s = state.lock().unwrap();
            match &hello {
                Ok(h) => {
                    s.running = true;
                    s.starting = false;
                    s.error = None;
                    s.computer = h["name"].as_str().unwrap_or_default().to_owned();
                    s.agent = model::agent_from_hello(h);
                    s.screen_enabled = h["screen"]["enabled"].as_bool().unwrap_or(false);
                    s.screen_ready = h["screen"]["connected"].as_bool().unwrap_or(false);
                }
                Err(_) => {
                    s.running = false;
                    s.starting = started.elapsed() < Duration::from_secs(30);
                    if !s.starting && s.error.is_none() {
                        s.error = Some("Not running. Quit and open Hypurr again.".into());
                    }
                }
            }
        }
        if hello.is_ok() {
            let wanted = set_screen.lock().unwrap().take();
            if let Some(on) = wanted {
                match api.call_with("setScreenEnabled", &json!({"enabled": on})) {
                    Ok(st) => state.lock().unwrap().screen_enabled = st["enabled"].as_bool().unwrap_or(on),
                    Err(e) => state.lock().unwrap().error = Some(format!("Couldn't change Remote screen: {e}")),
                }
            }
            if let Ok(d) = api.call("devices") {
                let (p, c) = model::phones(&d);
                let mut s = state.lock().unwrap();
                s.paired_phones = p;
                s.connected_phones = c;
            }
            let forced = std::mem::take(&mut *force.lock().unwrap());
            let due = forced || state.lock().unwrap().needs_new_code(now_ms());
            if due && let Ok(p) = api.call("pairing") {
                let mut s = state.lock().unwrap();
                s.pairing_url = p["pairingUrl"].as_str().map(str::to_owned);
                s.expires_at_ms = p["expiresAt"].as_i64().unwrap_or(0);
            }
        }
        if last_net.is_none_or(|t| t.elapsed() > Duration::from_secs(30)) {
            last_net = Some(Instant::now());
            state.lock().unwrap().network = network();
        }
        repaint();
        std::thread::sleep(Duration::from_secs(2));
    }
}

#[cfg(windows)]
fn network() -> model::Network {
    let out = no_window(Command::new("powershell.exe").args([
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "Get-NetConnectionProfile | ForEach-Object { \"$($_.Name)|$($_.NetworkCategory)\" }",
    ]))
    .output();
    match out {
        Ok(o) if o.status.success() => {
            model::network_from_profiles(&String::from_utf8_lossy(&o.stdout))
        }
        _ => model::Network::Unknown,
    }
}

#[cfg(not(windows))]
fn network() -> model::Network {
    model::Network::Unknown
}
