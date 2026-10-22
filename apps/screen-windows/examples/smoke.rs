//! End-to-end check without a phone: plays the host (named pipe) and the phone
//! (WebRTC offerer), runs the real helper, saves a screenshot and counts video.
//! `cargo run --release --example smoke -- <out.jpg>`

use base64::Engine as _;
use serde_json::{Value, json};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::windows::named_pipe::ServerOptions;
use webrtc::api::APIBuilder;
use webrtc::api::interceptor_registry::register_default_interceptors;
use webrtc::api::media_engine::MediaEngine;
use webrtc::interceptor::registry::Registry;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::rtp_transceiver::rtp_codec::RTPCodecType;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let out = std::env::args().nth(1).unwrap_or_else(|| "shot.jpg".into());
    let user = std::env::var("USERNAME").unwrap_or_default();
    let name = format!(r"\\.\pipe\hypurr-screen-{}", user.replace(['\\', '/'], "_"));
    let server = ServerOptions::new().first_pipe_instance(true).create(&name)?;
    let exe = std::env::current_exe()?.parent().unwrap().parent().unwrap().join("hypurr-screen.exe");
    let mut child = tokio::process::Command::new(exe).kill_on_drop(true).spawn()?;
    tokio::time::timeout(Duration::from_secs(10), server.connect()).await??;
    let (rd, mut wr) = tokio::io::split(server);
    let mut lines = BufReader::new(rd).lines();
    let status: Value = serde_json::from_str(&lines.next_line().await?.unwrap())?;
    println!("status: {}", status["params"]);

    let mut call = async |id: i64, method: &str, params: Value| -> anyhow::Result<Value> {
        let line = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params}).to_string() + "\n";
        wr.write_all(line.as_bytes()).await?;
        loop {
            let msg: Value = serde_json::from_str(&lines.next_line().await?.unwrap())?;
            if msg["id"] == id {
                return Ok(msg);
            }
        }
    };

    let shot = call(1, "screenshot", json!({"width": 1280, "height": 720})).await?;
    let jpeg = base64::engine::general_purpose::STANDARD.decode(shot["result"]["data"].as_str().unwrap_or_default())?;
    std::fs::write(&out, &jpeg)?;
    println!("screenshot: {} bytes -> {out}", jpeg.len());

    let mut media = MediaEngine::default();
    media.register_default_codecs()?;
    let registry = register_default_interceptors(Registry::new(), &mut media)?;
    let api = APIBuilder::new().with_media_engine(media).with_interceptor_registry(registry).build();
    let pc = Arc::new(api.new_peer_connection(RTCConfiguration::default()).await?);
    pc.add_transceiver_from_kind(RTPCodecType::Video, None).await?;
    let input = pc.create_data_channel("input", None).await?;
    let packets = Arc::new(AtomicUsize::new(0));
    let codec = Arc::new(std::sync::Mutex::new(String::new()));
    {
        let (packets, codec) = (packets.clone(), codec.clone());
        pc.on_track(Box::new(move |track, _, _| {
            let (packets, codec) = (packets.clone(), codec.clone());
            *codec.lock().unwrap() = track.codec().capability.mime_type.clone();
            Box::pin(async move {
                while track.read_rtp().await.is_ok() {
                    packets.fetch_add(1, Ordering::Relaxed);
                }
            })
        }));
    }
    let offer = pc.create_offer(None).await?;
    let mut gathered = pc.gathering_complete_promise().await;
    pc.set_local_description(offer).await?;
    let _ = gathered.recv().await;
    let sdp = pc.local_description().await.unwrap().sdp;
    let reply = call(2, "answer", json!({"session": "smoke", "sdp": sdp, "display": status["params"]["displays"][0]["id"], "iceServers": [], "maxFramerate": 30})).await?;
    let answer = reply["result"]["sdp"].as_str().ok_or_else(|| anyhow::anyhow!("answer failed: {reply}"))?;
    pc.set_remote_description(webrtc::peer_connection::sdp::session_description::RTCSessionDescription::answer(answer.to_owned())?).await?;
    tokio::time::sleep(Duration::from_secs(2)).await;
    let _ = input.send_text(json!({"type": "view", "long": 1280, "short": 720}).to_string()).await;
    tokio::time::sleep(Duration::from_secs(4)).await;
    println!("state: {}, codec: {}, rtp packets in 6s: {}", pc.connection_state(), codec.lock().unwrap(), packets.load(Ordering::Relaxed));
    let _ = call(3, "closeAll", json!({})).await?;
    pc.close().await?;
    child.kill().await?;
    Ok(())
}
