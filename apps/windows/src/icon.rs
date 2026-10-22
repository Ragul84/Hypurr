// The cat mark and app icon. Shared with build.rs (via include!), which turns it
// into the .ico embedded in hypurr.exe, so tray, window and Explorer all match.

pub const CAT: [&str; 9] = [
    "#ooooooo#",
    "##ooooo##",
    "#########",
    "#########",
    "##o###o##",
    "#########",
    "####o####",
    "o#######o",
    "oo#####oo",
];

/// The app icon as RGBA: the brand tile (dark rounded square, white cat dots on dim
/// dots), so it stays visible on light and dark taskbars. Mirrors docs/brand/hypurr-icon.svg.
pub fn cat_icon_rgba(size: u32) -> (Vec<u8>, u32) {
    const SS: u32 = 4; // supersamples per axis, for smooth edges at 16-32 px
    let s = size as f32;
    let radius = s * 0.225;
    let pad = s * 0.14;
    let cell = (s - 2.0 * pad) / 9.0;
    let inside = |x: f32, y: f32| -> Option<[u8; 3]> {
        // Rounded-square tile.
        let (qx, qy) = ((x - s / 2.0).abs() - (s / 2.0 - radius), (y - s / 2.0).abs() - (s / 2.0 - radius));
        if qx > 0.0 && qy > 0.0 && qx * qx + qy * qy > radius * radius {
            return None;
        }
        let (gx, gy) = (((x - pad) / cell).floor(), ((y - pad) / cell).floor());
        if (0.0..9.0).contains(&gx) && (0.0..9.0).contains(&gy) {
            let (cx, cy) = (pad + (gx + 0.5) * cell, pad + (gy + 0.5) * cell);
            let (dx, dy) = (x - cx, y - cy);
            let lit = CAT[gy as usize].as_bytes()[gx as usize] == b'#';
            let r = cell * if lit { 0.40 } else { 0.30 };
            if dx * dx + dy * dy <= r * r {
                return Some(if lit { [0xFF, 0xFF, 0xFF] } else { [0x2A, 0x2A, 0x2D] });
            }
        }
        Some([0x0E, 0x0E, 0x0E])
    };
    let mut px = vec![0u8; (size * size * 4) as usize];
    for py in 0..size {
        for pxl in 0..size {
            let (mut acc, mut hits) = ([0u32; 3], 0u32);
            for sy in 0..SS {
                for sx in 0..SS {
                    let x = pxl as f32 + (sx as f32 + 0.5) / SS as f32;
                    let y = py as f32 + (sy as f32 + 0.5) / SS as f32;
                    if let Some(c) = inside(x, y) {
                        for k in 0..3 {
                            acc[k] += c[k] as u32;
                        }
                        hits += 1;
                    }
                }
            }
            if hits > 0 {
                let i = ((py * size + pxl) * 4) as usize;
                for k in 0..3 {
                    px[i + k] = (acc[k] / hits) as u8;
                }
                px[i + 3] = (hits * 255 / (SS * SS)) as u8;
            }
        }
    }
    (px, size)
}
