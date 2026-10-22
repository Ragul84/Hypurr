//! Input events (see `host/src/screen.rs`) through `SendInput`. Coordinates are
//! display pixels, mapped onto the virtual desktop.

use crate::capture::Display;
use anyhow::{Result, anyhow};
use serde_json::Value;
use std::time::Duration;
use windows::Win32::UI::Input::KeyboardAndMouse::{
    INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBD_EVENT_FLAGS, KEYBDINPUT, KEYEVENTF_EXTENDEDKEY,
    KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, MOUSE_EVENT_FLAGS, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_HWHEEL,
    MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP,
    MOUSEEVENTF_MOVE, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_VIRTUALDESK,
    MOUSEEVENTF_WHEEL, MOUSEINPUT, SendInput, VIRTUAL_KEY, VK_BACK, VK_CONTROL, VK_DELETE, VK_DOWN,
    VK_END, VK_ESCAPE, VK_F1, VK_HOME, VK_LEFT, VK_LWIN, VK_MENU, VK_NEXT, VK_PRIOR, VK_RETURN,
    VK_RIGHT, VK_SHIFT, VK_SPACE, VK_TAB, VK_UP, VkKeyScanW,
};
use windows::Win32::UI::WindowsAndMessaging::{
    GetSystemMetrics, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN, SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN,
    WHEEL_DELTA,
};

pub fn perform(display: &Display, e: &Value) -> Result<()> {
    let num = |k: &str| e[k].as_f64();
    let point = || -> Result<(f64, f64)> {
        Ok((
            num("x").ok_or_else(|| anyhow!("x is required"))?,
            num("y").ok_or_else(|| anyhow!("y is required"))?,
        ))
    };
    let (down, up) = match e["button"].as_str() {
        Some("right") => (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
        Some("middle") => (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
        _ => (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    };
    let modifiers: Vec<VIRTUAL_KEY> = e["modifiers"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|m| modifier(m.as_str()?))
        .collect();
    let move_to = |x: f64, y: f64| send(&[mouse(display, x, y, MOUSEEVENTF_MOVE, 0)]);
    let hold = |press: bool| -> Result<()> {
        let keys: Vec<INPUT> = modifiers.iter().map(|&vk| key(vk, !press)).collect();
        send(&keys)
    };
    match e["type"].as_str().unwrap_or_default() {
        "move" => {
            let (x, y) = point()?;
            move_to(x, y)?;
        }
        "down" | "up" => {
            let (x, y) = point()?;
            let flag = if e["type"] == "down" { down } else { up };
            send(&[mouse(display, x, y, MOUSEEVENTF_MOVE | flag, 0)])?;
        }
        "click" => {
            let (x, y) = point()?;
            move_to(x, y)?;
            hold(true)?;
            for _ in 0..e["count"].as_u64().unwrap_or(1).clamp(1, 3) {
                send(&[mouse(display, x, y, down, 0), mouse(display, x, y, up, 0)])?;
            }
            hold(false)?;
        }
        "drag" => {
            let (x, y) = point()?;
            let (tx, ty) = (
                num("toX").ok_or_else(|| anyhow!("toX is required"))?,
                num("toY").ok_or_else(|| anyhow!("toY is required"))?,
            );
            send(&[mouse(display, x, y, MOUSEEVENTF_MOVE | MOUSEEVENTF_LEFTDOWN, 0)])?;
            for i in 1..=20 {
                std::thread::sleep(Duration::from_millis(15));
                let t = f64::from(i) / 20.0;
                move_to(x + (tx - x) * t, y + (ty - y) * t)?;
            }
            send(&[mouse(display, tx, ty, MOUSEEVENTF_LEFTUP, 0)])?;
        }
        "scroll" => {
            let (x, y) = point()?;
            move_to(x, y)?;
            let (dx, dy) = (num("dx").unwrap_or(0.0), num("dy").unwrap_or(0.0));
            // Lines → wheel notches; pixel scrolls (trackpad-style) use a finer step.
            let per = if e["units"] == "pixel" { f64::from(WHEEL_DELTA) / 40.0 } else { f64::from(WHEEL_DELTA) };
            let (wx, wy) = ((dx * per).round() as i32, (-dy * per).round() as i32);
            if wy != 0 {
                send(&[mouse(display, x, y, MOUSEEVENTF_WHEEL, wy)])?;
            }
            if wx != 0 {
                send(&[mouse(display, x, y, MOUSEEVENTF_HWHEEL, wx)])?;
            }
        }
        "text" => {
            let text = e["text"].as_str().unwrap_or_default();
            let mut keys = Vec::new();
            for c in text.chars() {
                if c == '\n' || c == '\r' {
                    keys.extend([key(VK_RETURN, false), key(VK_RETURN, true)]);
                    continue;
                }
                let mut units = [0u16; 2];
                for &u in c.encode_utf16(&mut units).iter() {
                    keys.extend([unicode(u, false), unicode(u, true)]);
                }
            }
            send(&keys)?;
        }
        "key" => {
            let name = e["key"].as_str().unwrap_or_default();
            let (vk, shift) = vk_for(name).ok_or_else(|| anyhow!("unknown key {name}"))?;
            hold(true)?;
            if shift && !modifiers.contains(&VK_SHIFT) {
                send(&[key(VK_SHIFT, false), key(vk, false), key(vk, true), key(VK_SHIFT, true)])?;
            } else {
                send(&[key(vk, false), key(vk, true)])?;
            }
            hold(false)?;
        }
        other => return Err(anyhow!("unknown input {other}")),
    }
    Ok(())
}

fn modifier(name: &str) -> Option<VIRTUAL_KEY> {
    Some(match name {
        "cmd" => VK_LWIN,
        "option" => VK_MENU,
        "ctrl" => VK_CONTROL,
        "shift" => VK_SHIFT,
        _ => return None,
    })
}

/// Virtual key for a key name from the shared protocol, or a single character
/// (with whether it needs Shift on this keyboard layout).
fn vk_for(name: &str) -> Option<(VIRTUAL_KEY, bool)> {
    let vk = match name {
        "return" => VK_RETURN,
        "tab" => VK_TAB,
        "space" => VK_SPACE,
        "escape" => VK_ESCAPE,
        "delete" => VK_BACK, // the protocol names keys like a Mac keyboard
        "forwardDelete" => VK_DELETE,
        "left" => VK_LEFT,
        "right" => VK_RIGHT,
        "up" => VK_UP,
        "down" => VK_DOWN,
        "home" => VK_HOME,
        "end" => VK_END,
        "pageUp" => VK_PRIOR,
        "pageDown" => VK_NEXT,
        _ => {
            if let Some(n) = name.strip_prefix('f').and_then(|n| n.parse::<u16>().ok()).filter(|n| (1..=12).contains(n)) {
                return Some((VIRTUAL_KEY(VK_F1.0 + n - 1), false));
            }
            let mut chars = name.chars();
            let (Some(c), None) = (chars.next(), chars.next()) else { return None };
            let mut units = [0u16; 2];
            let [u] = c.encode_utf16(&mut units) else { return None };
            // SAFETY: pure lookup in the current keyboard layout.
            let scan = unsafe { VkKeyScanW(*u) };
            if scan == -1 {
                return None;
            }
            let [low, high] = scan.to_le_bytes();
            return Some((VIRTUAL_KEY(u16::from(low)), high & 1 != 0));
        }
    };
    Some((vk, false))
}

fn mouse(d: &Display, x: f64, y: f64, flags: MOUSE_EVENT_FLAGS, data: i32) -> INPUT {
    // SAFETY: GetSystemMetrics has no preconditions.
    let (vx, vy, vw, vh) = unsafe {
        (
            GetSystemMetrics(SM_XVIRTUALSCREEN),
            GetSystemMetrics(SM_YVIRTUALSCREEN),
            GetSystemMetrics(SM_CXVIRTUALSCREEN).max(2),
            GetSystemMetrics(SM_CYVIRTUALSCREEN).max(2),
        )
    };
    let px = f64::from(d.x) + x.clamp(0.0, f64::from(d.width) - 1.0);
    let py = f64::from(d.y) + y.clamp(0.0, f64::from(d.height) - 1.0);
    let norm = |p: f64, origin: i32, size: i32| ((p - f64::from(origin)) * 65535.0 / f64::from(size - 1)).round() as i32;
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT {
                dx: norm(px, vx, vw),
                dy: norm(py, vy, vh),
                mouseData: data as u32,
                dwFlags: flags | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
                ..Default::default()
            },
        },
    }
}

fn key(vk: VIRTUAL_KEY, up: bool) -> INPUT {
    let extended = [VK_LEFT, VK_RIGHT, VK_UP, VK_DOWN, VK_HOME, VK_END, VK_PRIOR, VK_NEXT, VK_DELETE, VK_LWIN].contains(&vk);
    let mut flags = KEYBD_EVENT_FLAGS(0);
    if extended {
        flags |= KEYEVENTF_EXTENDEDKEY;
    }
    if up {
        flags |= KEYEVENTF_KEYUP;
    }
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: vk, dwFlags: flags, ..Default::default() } },
    }
}

fn unicode(unit: u16, up: bool) -> INPUT {
    let flags = if up { KEYEVENTF_UNICODE | KEYEVENTF_KEYUP } else { KEYEVENTF_UNICODE };
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wScan: unit, dwFlags: flags, ..Default::default() } },
    }
}

fn send(inputs: &[INPUT]) -> Result<()> {
    if inputs.is_empty() {
        return Ok(());
    }
    // SAFETY: `inputs` is a valid slice of initialized INPUT structs.
    let sent = unsafe { SendInput(inputs, i32::try_from(std::mem::size_of::<INPUT>())?) };
    if sent as usize != inputs.len() {
        // Usually UIPI: the focused window belongs to an elevated app.
        return Err(anyhow!("Windows blocked the input (is an admin app in front?)"));
    }
    Ok(())
}

pub fn set_clipboard(text: &str) -> Result<()> {
    clipboard_win::set_clipboard_string(text).map_err(|e| anyhow!("can't set the clipboard: {e}"))
}

/// Opens an app by name the way the Run box does (`start`).
pub fn open_app(name: &str) -> Result<()> {
    let name = name.trim();
    if name.is_empty() || name.contains(['&', '|', '<', '>', '^', '"']) {
        return Err(anyhow!("Couldn't find an app named {name}."));
    }
    std::process::Command::new("cmd")
        .args(["/C", "start", "", name])
        .spawn()
        .map_err(|_| anyhow!("Couldn't find an app named {name}."))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn named_keys() {
        assert_eq!(vk_for("return"), Some((VK_RETURN, false)));
        assert_eq!(vk_for("f5"), Some((VIRTUAL_KEY(VK_F1.0 + 4), false)));
        assert_eq!(vk_for("banana"), None);
        assert_eq!(modifier("cmd"), Some(VK_LWIN));
    }
}
