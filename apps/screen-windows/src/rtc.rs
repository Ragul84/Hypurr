//! One viewer: a webrtc-rs peer connection (non-trickle, like the other helpers)
//! sending the display as H.264, with the phone's input on its data channels.

use crate::capture::Display;
use crate::video::{self, View};
use crate::{Helper, input};
use anyhow::{Result, anyhow};
use serde_json::{Value, json};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;
use webrtc::api::APIBuilder;
use webrtc::api::interceptor_registry::register_default_interceptors;
use webrtc::api::media_engine::{MIME_TYPE_H264, MediaEngine};
use webrtc::data_channel::RTCDataChannel;
use webrtc::data_channel::data_channel_message::DataChannelMessage;
use webrtc::ice_transport::ice_server::RTCIceServer;
use webrtc::interceptor::registry::Registry;
use webrtc::media::Sample;
use webrtc::peer_connection::RTCPeerConnection;
use webrtc::peer_connection::configuration::RTCConfiguration;
use webrtc::peer_connection::peer_connection_state::RTCPeerConnectionState;
use webrtc::peer_connection::sdp::session_description::RTCSessionDescription;
use webrtc::rtcp::payload_feedbacks::picture_loss_indication::PictureLossIndication;
use webrtc::rtp_transceiver::rtp_codec::RTCRtpCodecCapability;
use webrtc::track::track_local::TrackLocal;
use webrtc::track::track_local::track_local_static_sample::TrackLocalStaticSample;

const BITRATE_BPS: u64 = 8_000_000;

pub struct Session {
    pc: Arc<RTCPeerConnection>,
    stop: Arc<AtomicBool>,
}

impl Session {
    pub async fn new(id: String, display: Display, helper: Arc<Helper>, config: &Value) -> Result<Self> {
        let mut media = MediaEngine::default();
        media.register_default_codecs()?;
        let registry = register_default_interceptors(Registry::new(), &mut media)?;
        let api = APIBuilder::new()
            .with_media_engine(media)
            .with_interceptor_registry(registry)
            .build();
        let ice_servers = config["iceServers"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|s| RTCIceServer {
                urls: match &s["urls"] {
                    Value::Array(a) => a.iter().filter_map(|u| u.as_str().map(str::to_owned)).collect(),
                    Value::String(u) => vec![u.clone()],
                    _ => Vec::new(),
                },
                username: s["username"].as_str().unwrap_or_default().to_owned(),
                credential: s["credential"].as_str().unwrap_or_default().to_owned(),
            })
            .collect();
        let pc = Arc::new(api.new_peer_connection(RTCConfiguration { ice_servers, ..Default::default() }).await?);

        let track = Arc::new(TrackLocalStaticSample::new(
            RTCRtpCodecCapability {
                mime_type: MIME_TYPE_H264.to_owned(),
                clock_rate: 90_000,
                sdp_fmtp_line: "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f".to_owned(),
                ..Default::default()
            },
            "video".to_owned(),
            "hypurr-screen".to_owned(),
        ));
        let sender = pc.add_track(track.clone() as Arc<dyn TrackLocal + Send + Sync>).await?;

        // The phone asks for a keyframe when it joins or loses packets.
        let keyframe = Arc::new(AtomicBool::new(true));
        {
            let keyframe = keyframe.clone();
            tokio::spawn(async move {
                while let Ok((packets, _)) = sender.read_rtcp().await {
                    if packets.iter().any(|p| p.as_any().downcast_ref::<PictureLossIndication>().is_some()) {
                        keyframe.store(true, Ordering::Relaxed);
                    }
                }
            });
        }

        // Video: capture → encode on a thread → samples to the track.
        let source = helper.captures.get(&display)?;
        let view = Arc::new(Mutex::new(View::default()));
        let stop = Arc::new(AtomicBool::new(false));
        let fps = u32::try_from(config["maxFramerate"].as_u64().unwrap_or(60).clamp(1, 60))?;
        let bitrate = u32::try_from(config["maxBitrateBps"].as_u64().unwrap_or(BITRATE_BPS).clamp(100_000, BITRATE_BPS))?;
        let (samples, mut rx) = mpsc::channel::<Sample>(4);
        {
            let (view, stop, keyframe) = (view.clone(), stop.clone(), keyframe.clone());
            std::thread::spawn(move || {
                let result = video::encode_loop(&source, &view, fps, bitrate, &keyframe, &stop, |data, duration| {
                    // A full queue means the network is behind: drop this frame, keep going.
                    !matches!(
                        samples.try_send(Sample { data: data.into(), duration, ..Default::default() }),
                        Err(mpsc::error::TrySendError::Closed(_))
                    )
                });
                if let Err(error) = result {
                    tracing::warn!(error = format!("{error:#}"), "video stopped");
                }
            });
        }
        tokio::spawn(async move {
            while let Some(sample) = rx.recv().await {
                if track.write_sample(&sample).await.is_err() {
                    break;
                }
            }
        });

        // Input from the phone, applied in order.
        let channels: Arc<Mutex<Vec<Arc<RTCDataChannel>>>> = Arc::default();
        let (tx, mut inbox) = mpsc::unbounded_channel::<Value>();
        {
            let channels = channels.clone();
            let display = display.clone();
            tokio::spawn(async move {
                while let Some(msg) = inbox.recv().await {
                    let reply = match msg["type"].as_str() {
                        Some("view") => Some(view.lock().map_or_else(
                            |_| error_msg(&anyhow!("view lock poisoned")),
                            |mut v| v.apply(&msg, display.id),
                        )),
                        Some("clipboard") => input::set_clipboard(msg["text"].as_str().unwrap_or_default())
                            .err()
                            .as_ref()
                            .map(error_msg),
                        _ => {
                            let d = display.clone();
                            tokio::task::spawn_blocking(move || input::perform(&d, &msg))
                                .await
                                .map_err(anyhow::Error::from)
                                .and_then(|r| r)
                                .err()
                                .as_ref()
                                .map(error_msg)
                        }
                    };
                    if let Some(reply) = reply {
                        send(&channels, &reply).await;
                    }
                }
            });
        }
        pc.on_data_channel(Box::new(move |channel: Arc<RTCDataChannel>| {
            let tx = tx.clone();
            if let Ok(mut list) = channels.lock() {
                list.push(channel.clone());
            }
            Box::pin(async move {
                channel.on_message(Box::new(move |m: DataChannelMessage| {
                    if let Ok(msg) = serde_json::from_slice::<Value>(&m.data) {
                        let _ = tx.send(msg);
                    }
                    Box::pin(async {})
                }));
            })
        }));
        {
            let helper = helper.clone();
            let stop = stop.clone();
            pc.on_peer_connection_state_change(Box::new(move |state| {
                if matches!(state, RTCPeerConnectionState::Failed | RTCPeerConnectionState::Closed) {
                    stop.store(true, Ordering::Relaxed);
                    helper.ended(&id);
                }
                Box::pin(async {})
            }));
        }
        Ok(Self { pc, stop })
    }

    /// Answers an offer (the first one, or a renegotiation) once ICE gathering is done.
    pub async fn answer(&self, offer: &str) -> Result<String> {
        self.pc.set_remote_description(RTCSessionDescription::offer(offer.to_owned())?).await?;
        let answer = self.pc.create_answer(None).await?;
        let mut gathered = self.pc.gathering_complete_promise().await;
        self.pc.set_local_description(answer).await?;
        let _ = tokio::time::timeout(Duration::from_secs(10), gathered.recv()).await;
        Ok(self
            .pc
            .local_description()
            .await
            .ok_or_else(|| anyhow!("no local description"))?
            .sdp)
    }

    pub async fn close(self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = self.pc.close().await;
    }
}

fn error_msg(e: &anyhow::Error) -> Value {
    json!({"type": "error", "message": format!("{e:#}")})
}

/// Replies go on the reliable `input` channel.
async fn send(channels: &Mutex<Vec<Arc<RTCDataChannel>>>, msg: &Value) {
    let channel = channels
        .lock()
        .ok()
        .and_then(|list| list.iter().find(|c| c.label() == "input").cloned());
    if let Some(c) = channel {
        let _ = c.send_text(msg.to_string()).await;
    }
}
