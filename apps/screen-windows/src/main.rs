//! `hypurr-screen`: Remote screen for Windows. The host (`hypurr-host`) starts it
//! while Remote screen is on; it connects to the host's per-user named pipe and
//! serves the same JSON-RPC as the macOS and Linux helpers (see `host/src/screen.rs`):
//! capture through Windows Graphics Capture, video as H.264 (OpenH264) over
//! webrtc-rs, and input through `SendInput`.

mod capture;
mod input;
mod rtc;
mod video;

use anyhow::{Result, anyhow};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::windows::named_pipe::ClientOptions;
use tokio::sync::{Mutex, mpsc};

/// Must match `pipe_name` in `host/src/screen.rs`.
fn pipe_name() -> String {
    let user = std::env::var("USERNAME").unwrap_or_default();
    format!(r"\\.\pipe\hypurr-screen-{}", user.replace(['\\', '/'], "_"))
}

/// Messages to the host, one JSON line each.
pub type Outbox = mpsc::UnboundedSender<Value>;

pub struct Helper {
    pub captures: capture::Captures,
    sessions: Mutex<HashMap<String, rtc::Session>>,
    out: Outbox,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "hypurr_screen=info".into()),
        )
        .init();
    // Points are physical pixels: capture size, input coordinates and the host all agree.
    capture::make_dpi_aware();
    let captures = capture::Captures::default();
    loop {
        match ClientOptions::new().open(pipe_name()) {
            Ok(pipe) => {
                tracing::info!("connected to hypurr-host");
                if let Err(error) = serve(pipe, captures.clone()).await {
                    tracing::warn!(error = format!("{error:#}"), "host link ended");
                }
            }
            Err(error) => tracing::debug!(%error, "hypurr-host isn't listening yet"),
        }
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
}

async fn serve(pipe: tokio::net::windows::named_pipe::NamedPipeClient, captures: capture::Captures) -> Result<()> {
    let (rd, mut wr) = tokio::io::split(pipe);
    let (out, mut outbox) = mpsc::unbounded_channel::<Value>();
    let writer = tokio::spawn(async move {
        while let Some(msg) = outbox.recv().await {
            let mut line = msg.to_string();
            line.push('\n');
            if wr.write_all(line.as_bytes()).await.is_err() {
                break;
            }
        }
    });
    let helper = Arc::new(Helper {
        captures,
        sessions: Mutex::default(),
        out: out.clone(),
    });
    let _ = out.send(json!({"jsonrpc": "2.0", "method": "status", "params": status()}));
    let mut lines = BufReader::new(rd).lines();
    while let Some(line) = lines.next_line().await? {
        let Ok(msg) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        let (Some(method), Some(id)) = (
            msg["method"].as_str().map(str::to_owned),
            msg.get("id").cloned(),
        ) else {
            continue;
        };
        let helper = helper.clone();
        tokio::spawn(async move {
            let reply = match handle(&helper, &method, &msg["params"]).await {
                Ok(result) => json!({"jsonrpc": "2.0", "id": id, "result": result}),
                Err(error) => {
                    json!({"jsonrpc": "2.0", "id": id, "error": {"code": -32000, "message": format!("{error:#}")}})
                }
            };
            let _ = helper.out.send(reply);
        });
    }
    writer.abort();
    for (_, s) in helper.sessions.lock().await.drain() {
        s.close().await;
    }
    Ok(())
}

fn status() -> Value {
    let displays = capture::displays().unwrap_or_default();
    json!({
        "platform": "windows",
        "version": env!("CARGO_PKG_VERSION"),
        "displays": displays.iter().map(capture::Display::to_json).collect::<Vec<_>>(),
        // Windows needs no permission grant for capture or SendInput in the user's session.
        "capture": !displays.is_empty(),
        "input": true,
    })
}

async fn handle(h: &Arc<Helper>, method: &str, p: &Value) -> Result<Value> {
    let display = || capture::display(p["display"].as_u64());
    Ok(match method {
        "answer" => {
            let id = p["session"]
                .as_str()
                .ok_or_else(|| anyhow!("session is required"))?
                .to_owned();
            let sdp = p["sdp"]
                .as_str()
                .ok_or_else(|| anyhow!("sdp is required"))?;
            if let Some(s) = h.sessions.lock().await.get(&id) {
                return Ok(json!({"sdp": s.answer(sdp).await?}));
            }
            let session = rtc::Session::new(id.clone(), display()?, h.clone(), p).await?;
            let answer = session.answer(sdp).await?;
            h.sessions.lock().await.insert(id, session);
            json!({"sdp": answer})
        }
        "close" => {
            let s = match p["session"].as_str() {
                Some(id) => h.sessions.lock().await.remove(id),
                None => None,
            };
            if let Some(s) = s {
                s.close().await;
            }
            json!({})
        }
        "closeAll" => {
            let all: Vec<_> = h.sessions.lock().await.drain().map(|(_, s)| s).collect();
            for s in all {
                s.close().await;
            }
            json!({})
        }
        "screenshot" => {
            let (w, h2) = (
                u32::try_from(p["width"].as_u64().unwrap_or(1280))?,
                u32::try_from(p["height"].as_u64().unwrap_or(800))?,
            );
            let d = display()?;
            let source = h.captures.get(&d)?;
            let jpeg = tokio::task::spawn_blocking(move || video::screenshot(&source, w, h2)).await??;
            json!({"data": jpeg})
        }
        "input" => {
            let d = display()?;
            let event = p["event"].clone();
            tokio::task::spawn_blocking(move || input::perform(&d, &event)).await??;
            json!({})
        }
        "uiTree" | "focusedField" => {
            return Err(anyhow!("The accessibility tree isn't available on Windows yet."));
        }
        "openApp" => {
            input::open_app(
                p["name"]
                    .as_str()
                    .ok_or_else(|| anyhow!("name is required"))?,
            )?;
            json!({})
        }
        other => return Err(anyhow!("unknown method {other}")),
    })
}

impl Helper {
    /// A session's WebRTC connection ended on its own.
    pub fn ended(self: &Arc<Self>, id: &str) {
        let this = self.clone();
        let id = id.to_owned();
        tokio::spawn(async move {
            let s = this.sessions.lock().await.remove(&id);
            if let Some(s) = s {
                s.close().await;
            }
            let _ = this.out.send(json!({"jsonrpc": "2.0", "method": "session", "params": {"session": id, "state": "closed"}}));
        });
    }
}
