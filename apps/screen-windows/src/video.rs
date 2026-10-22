//! Frames → what the phone shows: crop and scale (the phone's `view` message),
//! BGRA → I420, H.264 with OpenH264, and one-off JPEG stills.

use crate::capture::{Frame, Source};
use anyhow::{Result, anyhow};
use base64::Engine as _;
use openh264::OpenH264API;
use openh264::encoder::{BitRate, Encoder, EncoderConfig, FrameRate, IntraFramePeriod, UsageType};
use openh264::formats::YUVBuffer;
use serde_json::{Value, json};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Default output bounds when the phone hasn't said how big it is.
const DEFAULT_LONG: f64 = 1920.0;
const DEFAULT_SHORT: f64 = 1080.0;
/// A still screen still gets a frame this often, so late keyframe requests are answered.
const KEEPALIVE: Duration = Duration::from_secs(1);

/// The part of the display to send and the size to send it at, in pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct View {
    pub crop: Option<(f64, f64, f64, f64)>,
    pub long: f64,
    pub short: f64,
}

impl Default for View {
    fn default() -> Self {
        Self { crop: None, long: DEFAULT_LONG, short: DEFAULT_SHORT }
    }
}

impl View {
    /// Applies a `view` message from the phone and returns the reply.
    pub fn apply(&mut self, msg: &Value, display_id: u32) -> Value {
        self.crop = msg["crop"].as_array().and_then(|c| {
            let v: Vec<f64> = c.iter().filter_map(Value::as_f64).collect();
            (v.len() == 4 && v[2] >= 16.0 && v[3] >= 16.0).then(|| (v[0].max(0.0), v[1].max(0.0), v[2], v[3]))
        });
        if let (Some(long), Some(short)) = (msg["long"].as_f64(), msg["short"].as_f64()) {
            self.long = long.min(3840.0);
            self.short = short.min(2160.0);
        }
        json!({"type": "view", "display": display_id, "crop": self.crop.map(|(x, y, w, h)| json!([x, y, w, h]))})
    }

    /// Source rectangle (clamped to the frame) and even output size.
    fn plan(&self, fw: u32, fh: u32) -> ((usize, usize, usize, usize), (usize, usize)) {
        let (fwf, fhf) = (f64::from(fw), f64::from(fh));
        let (x, y, w, h) = self.crop.unwrap_or((0.0, 0.0, fwf, fhf));
        let x = x.clamp(0.0, fwf - 2.0);
        let y = y.clamp(0.0, fhf - 2.0);
        let w = w.clamp(2.0, fwf - x);
        let h = h.clamp(2.0, fhf - y);
        let fit = (self.long / w.max(h)).min(self.short / w.min(h)).min(1.0);
        let even = |v: f64| ((v * fit) as usize / 2 * 2).max(2);
        ((x as usize, y as usize, w as usize, h as usize), (even(w), even(h)))
    }
}

/// Nearest-neighbour crop + scale straight into I420 (BT.601 limited range).
fn to_i420(f: &Frame, (sx, sy, sw, sh): (usize, usize, usize, usize), (ow, oh): (usize, usize)) -> Vec<u8> {
    let stride = f.width as usize * 4;
    let mut out = vec![0u8; ow * oh * 3 / 2];
    let (y_plane, uv) = out.split_at_mut(ow * oh);
    let (u_plane, v_plane) = uv.split_at_mut(ow * oh / 4);
    let cols: Vec<usize> = (0..ow).map(|x| (sx + x * sw / ow) * 4).collect();
    for y in 0..oh {
        let row = &f.bgra[(sy + y * sh / oh) * stride..];
        let yrow = &mut y_plane[y * ow..(y + 1) * ow];
        for (x, &c) in cols.iter().enumerate() {
            let (b, g, r) = (i32::from(row[c]), i32::from(row[c + 1]), i32::from(row[c + 2]));
            yrow[x] = (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16) as u8;
            if y % 2 == 0 && x % 2 == 0 {
                let i = (y / 2) * (ow / 2) + x / 2;
                u_plane[i] = (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128) as u8;
                v_plane[i] = (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128) as u8;
            }
        }
    }
    out
}

/// Encodes frames from `source` until `stop`, handing each access unit to `send`.
pub fn encode_loop(
    source: &Source,
    view: &Mutex<View>,
    fps: u32,
    bitrate_bps: u32,
    keyframe: &AtomicBool,
    stop: &AtomicBool,
    mut send: impl FnMut(Vec<u8>, Duration) -> bool,
) -> Result<()> {
    let interval = Duration::from_secs_f64(1.0 / f64::from(fps.max(1)));
    let mut encoder: Option<(Encoder, (usize, usize))> = None;
    let mut seq = 0;
    let mut last = Instant::now() - KEEPALIVE;
    let mut current: Option<Arc<Frame>> = None;
    while !stop.load(Ordering::Relaxed) {
        let tick = Instant::now();
        if let Some(f) = source.newer(seq) {
            seq = f.seq;
            current = Some(f);
        } else if last.elapsed() < KEEPALIVE && !keyframe.load(Ordering::Relaxed) {
            std::thread::sleep(interval.saturating_sub(tick.elapsed()));
            continue;
        }
        let Some(frame) = current.clone() else {
            std::thread::sleep(interval);
            continue;
        };
        let v = *view.lock().map_err(|_| anyhow!("view lock poisoned"))?;
        let (src, size) = v.plan(frame.width, frame.height);
        if encoder.as_ref().is_none_or(|(_, s)| *s != size) {
            let config = EncoderConfig::new()
                .usage_type(UsageType::ScreenContentRealTime)
                // Neither is supported for screen content; say so instead of letting OpenH264 warn.
                .adaptive_quantization(false)
                .background_detection(false)
                .bitrate(BitRate::from_bps(bitrate_bps))
                .max_frame_rate(FrameRate::from_hz(fps as f32))
                .intra_frame_period(IntraFramePeriod::from_num_frames(fps * 4))
                .skip_frames(true);
            encoder = Some((Encoder::with_api_config(OpenH264API::from_source(), config)?, size));
        }
        let Some((enc, _)) = encoder.as_mut() else { continue };
        if keyframe.swap(false, Ordering::Relaxed) {
            enc.force_intra_frame();
        }
        let yuv = YUVBuffer::from_vec(to_i420(&frame, src, size), size.0, size.1);
        let bits = enc.encode(&yuv)?.to_vec();
        let elapsed = last.elapsed().min(Duration::from_secs(1));
        last = Instant::now();
        if !bits.is_empty() && !send(bits, elapsed) {
            break;
        }
        std::thread::sleep(interval.saturating_sub(tick.elapsed()));
    }
    Ok(())
}

/// A JPEG of the whole display at exactly `width`×`height`, base64.
pub fn screenshot(source: &Source, width: u32, height: u32) -> Result<String> {
    let f = source.latest(Duration::from_secs(3))?;
    let (w, h) = (width.max(1) as usize, height.max(1) as usize);
    let (fw, fh) = (f.width as usize, f.height as usize);
    let mut rgb = vec![0u8; w * h * 3];
    for y in 0..h {
        let row = (y * fh / h) * fw * 4;
        for x in 0..w {
            let s = row + (x * fw / w) * 4;
            let d = (y * w + x) * 3;
            rgb[d] = f.bgra[s + 2];
            rgb[d + 1] = f.bgra[s + 1];
            rgb[d + 2] = f.bgra[s];
        }
    }
    let mut jpeg = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, 80).encode(
        &rgb,
        width,
        height,
        image::ExtendedColorType::Rgb8,
    )?;
    Ok(base64::engine::general_purpose::STANDARD.encode(jpeg))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plan_fits_and_keeps_even_sizes() {
        let v = View::default();
        let (src, size) = v.plan(2560, 1440);
        assert_eq!(src, (0, 0, 2560, 1440));
        assert_eq!(size, (1920, 1080));
        let zoom = View { crop: Some((100.0, 50.0, 301.0, 201.0)), ..View::default() };
        let (src, size) = zoom.plan(2560, 1440);
        assert_eq!(src, (100, 50, 301, 201));
        assert_eq!(size, (300, 200));
    }

    #[test]
    fn white_and_black_convert_to_video_range() {
        let f = Frame { width: 2, height: 2, bgra: [[255u8, 255, 255, 255], [0, 0, 0, 255]].concat().repeat(2), seq: 1 };
        let out = to_i420(&f, (0, 0, 2, 2), (2, 2));
        assert_eq!(&out[..2], &[235, 16]);
        assert_eq!(out[4], 128);
    }
}
