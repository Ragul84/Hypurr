//! Embeds the Hypurr icon into hypurr.exe so Explorer, the taskbar, Start and the
//! installer show the cat instead of the generic exe icon.

#[allow(dead_code)]
mod icon {
    include!("src/icon.rs");
}

fn main() {
    println!("cargo:rerun-if-changed=src/icon.rs");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let out = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("hypurr.ico");
    std::fs::write(&out, ico(&[16, 20, 24, 32, 40, 48, 64, 256])).unwrap();
    let mut res = winresource::WindowsResource::new();
    res.set_icon(out.to_str().unwrap());
    res.set("FileDescription", "Hypurr");
    res.set("ProductName", "Hypurr");
    res.compile().expect("embed Windows icon");
}

/// A .ico with one PNG entry per size (supported since Vista).
fn ico(sizes: &[u32]) -> Vec<u8> {
    let pngs: Vec<Vec<u8>> = sizes
        .iter()
        .map(|&s| {
            let (rgba, _) = icon::cat_icon_rgba(s);
            let mut png = Vec::new();
            image::codecs::png::PngEncoder::new(&mut png)
                .write_image(&rgba, s, s, image::ExtendedColorType::Rgba8)
                .unwrap();
            png
        })
        .collect();
    let mut f = vec![0, 0, 1, 0];
    f.extend((sizes.len() as u16).to_le_bytes());
    let mut offset = 6 + 16 * sizes.len() as u32;
    for (&s, png) in sizes.iter().zip(&pngs) {
        let d = if s >= 256 { 0 } else { s as u8 };
        f.extend([d, d, 0, 0]);
        f.extend(1u16.to_le_bytes());
        f.extend(32u16.to_le_bytes());
        f.extend((png.len() as u32).to_le_bytes());
        f.extend(offset.to_le_bytes());
        offset += png.len() as u32;
    }
    pngs.iter().for_each(|p| f.extend(p));
    f
}

use image::ImageEncoder;
