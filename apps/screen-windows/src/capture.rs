//! Displays and screen capture (Windows Graphics Capture). One capture per display,
//! shared by every viewer and screenshot, stopped when the last one lets go.

use anyhow::{Context, Result, anyhow};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex, Weak};
use std::time::Duration;
use windows::Win32::Graphics::Gdi::{GetMonitorInfoW, HMONITOR, MONITORINFO, MONITORINFOEXW};
use windows::Win32::UI::HiDpi::{
    DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2, SetProcessDpiAwarenessContext,
};
use windows::Win32::UI::WindowsAndMessaging::MONITORINFOF_PRIMARY;
use windows_capture::capture::{CaptureControl, Context as CaptureContext, GraphicsCaptureApiHandler};
use windows_capture::frame::Frame as CapturedFrame;
use windows_capture::graphics_capture_api::InternalCaptureControl;
use windows_capture::monitor::Monitor;
use windows_capture::settings::{
    ColorFormat, CursorCaptureSettings, DirtyRegionSettings, DrawBorderSettings,
    MinimumUpdateIntervalSettings, SecondaryWindowSettings, Settings,
};

pub fn make_dpi_aware() {
    // SAFETY: plain process-wide setting, called once at startup before any window exists.
    let _ = unsafe { SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2) };
}

/// A monitor, in physical pixels on the virtual desktop.
#[derive(Clone, Debug)]
pub struct Display {
    pub id: u32,
    pub name: String,
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
    pub main: bool,
    hmonitor: usize,
}

impl Display {
    pub fn to_json(&self) -> Value {
        json!({"id": self.id, "name": self.name, "width": self.width, "height": self.height, "main": self.main})
    }
}

pub fn displays() -> Result<Vec<Display>> {
    let monitors = Monitor::enumerate().context("listing displays")?;
    let mut out = Vec::new();
    for (i, m) in monitors.iter().enumerate() {
        let mut info = MONITORINFOEXW {
            monitorInfo: MONITORINFO {
                cbSize: u32::try_from(std::mem::size_of::<MONITORINFOEXW>())?,
                ..Default::default()
            },
            ..Default::default()
        };
        let h = HMONITOR(m.as_raw_hmonitor());
        // SAFETY: `info` is a correctly sized MONITORINFOEXW and `h` came from the system.
        if !unsafe { GetMonitorInfoW(h, &raw mut info.monitorInfo) }.as_bool() {
            continue;
        }
        let r = info.monitorInfo.rcMonitor;
        out.push(Display {
            id: u32::try_from(i + 1)?,
            name: m.name().unwrap_or_else(|_| format!("Display {}", i + 1)),
            x: r.left,
            y: r.top,
            width: u32::try_from(r.right - r.left)?,
            height: u32::try_from(r.bottom - r.top)?,
            main: info.monitorInfo.dwFlags & MONITORINFOF_PRIMARY != 0,
            hmonitor: m.as_raw_hmonitor() as usize,
        });
    }
    Ok(out)
}

/// The requested display, else the main one.
pub fn display(id: Option<u64>) -> Result<Display> {
    let all = displays()?;
    let found = match id {
        Some(id) => all.iter().find(|d| u64::from(d.id) == id).cloned(),
        None => None,
    };
    found
        .or_else(|| all.iter().find(|d| d.main).cloned())
        .or_else(|| all.first().cloned())
        .ok_or_else(|| anyhow!("This computer has no display to share."))
}

/// One BGRA frame without row padding.
pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub bgra: Vec<u8>,
    pub seq: u64,
}

#[derive(Default)]
struct Latest {
    frame: Option<Arc<Frame>>,
    seq: u64,
    failed: Option<String>,
}

/// A running capture of one display.
pub struct Source {
    pub display: Display,
    latest: Arc<(Mutex<Latest>, Condvar)>,
    control: Mutex<Option<CaptureControl<Handler, anyhow::Error>>>,
}

impl Source {
    /// The newest frame, waiting up to `timeout` for the first one.
    pub fn latest(&self, timeout: Duration) -> Result<Arc<Frame>> {
        let (lock, cv) = &*self.latest;
        let guard = lock.lock().map_err(|_| anyhow!("capture lock poisoned"))?;
        let (guard, _) = cv
            .wait_timeout_while(guard, timeout, |l| l.frame.is_none() && l.failed.is_none())
            .map_err(|_| anyhow!("capture lock poisoned"))?;
        if let Some(e) = &guard.failed {
            return Err(anyhow!("screen capture stopped: {e}"));
        }
        guard.frame.clone().ok_or_else(|| anyhow!("no picture from the display yet"))
    }

    /// The newest frame if it's newer than `seq`.
    pub fn newer(&self, seq: u64) -> Option<Arc<Frame>> {
        let l = self.latest.0.lock().ok()?;
        l.frame.as_ref().filter(|f| f.seq > seq).cloned()
    }
}

impl Drop for Source {
    fn drop(&mut self) {
        if let Some(c) = self.control.get_mut().ok().and_then(Option::take) {
            let _ = c.stop();
        }
    }
}

/// Live captures by display id.
#[derive(Clone, Default)]
pub struct Captures(Arc<Mutex<HashMap<u32, Weak<Source>>>>);

impl Captures {
    pub fn get(&self, d: &Display) -> Result<Arc<Source>> {
        let mut map = self.0.lock().map_err(|_| anyhow!("capture lock poisoned"))?;
        if let Some(s) = map.get(&d.id).and_then(Weak::upgrade) {
            return Ok(s);
        }
        let latest: Arc<(Mutex<Latest>, Condvar)> = Arc::default();
        let settings = Settings::new(
            Monitor::from_raw_hmonitor(d.hmonitor as *mut std::ffi::c_void),
            CursorCaptureSettings::WithCursor,
            DrawBorderSettings::WithoutBorder,
            SecondaryWindowSettings::Default,
            MinimumUpdateIntervalSettings::Custom(Duration::from_millis(16)),
            DirtyRegionSettings::Default,
            ColorFormat::Bgra8,
            latest.clone(),
        );
        let control = Handler::start_free_threaded(settings)
            .map_err(|e| anyhow!("can't capture {}: {e}", d.name))?;
        let source = Arc::new(Source {
            display: d.clone(),
            latest,
            control: Mutex::new(Some(control)),
        });
        map.insert(d.id, Arc::downgrade(&source));
        Ok(source)
    }
}

struct Handler {
    latest: Arc<(Mutex<Latest>, Condvar)>,
}

impl GraphicsCaptureApiHandler for Handler {
    type Flags = Arc<(Mutex<Latest>, Condvar)>;
    type Error = anyhow::Error;

    fn new(ctx: CaptureContext<Self::Flags>) -> Result<Self> {
        Ok(Self { latest: ctx.flags })
    }

    fn on_frame_arrived(&mut self, frame: &mut CapturedFrame, _: InternalCaptureControl) -> Result<()> {
        let (width, height) = (frame.width(), frame.height());
        let mut buf = frame.buffer()?;
        let bgra = buf.as_nopadding_buffer()?.to_vec();
        let (lock, cv) = &*self.latest;
        if let Ok(mut l) = lock.lock() {
            l.seq += 1;
            l.frame = Some(Arc::new(Frame { width, height, bgra, seq: l.seq }));
        }
        cv.notify_all();
        Ok(())
    }

    fn on_closed(&mut self) -> Result<()> {
        let (lock, cv) = &*self.latest;
        if let Ok(mut l) = lock.lock() {
            l.failed = Some("the display was disconnected".into());
        }
        cv.notify_all();
        Ok(())
    }
}
