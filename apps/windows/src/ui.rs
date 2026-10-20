//! C · Signal look: black and white on light grey, big rounded cards, Inter text,
//! Doto only for big numbers, the dotted cat, red only for "needs you". No icon fonts.

use crate::model::{self, Network, Snapshot};
use eframe::egui::{
    self, Align2, Color32, CornerRadius, FontData, FontDefinitions, FontFamily, FontId, Pos2, Rect,
    Sense, Stroke, Vec2,
};

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

#[derive(Clone, Copy)]
pub struct Palette {
    pub bg: Color32,
    pub card: Color32,
    pub text: Color32,
    pub secondary: Color32,
    pub tertiary: Color32,
    pub border: Color32,
    pub dot_off: Color32,
    pub accent: Color32,
    pub on_accent: Color32,
    pub needs: Color32,
    pub needs_text: Color32,
}

pub const LIGHT: Palette = Palette {
    bg: Color32::from_rgb(0xED, 0xED, 0xEA),
    card: Color32::WHITE,
    text: Color32::from_rgb(0x0E, 0x0E, 0x0E),
    secondary: Color32::from_rgb(0x34, 0x34, 0x3A),
    tertiary: Color32::from_rgb(0x5C, 0x5C, 0x63),
    border: Color32::from_rgb(0xE0, 0xE0, 0xDC),
    dot_off: Color32::from_rgb(0xD6, 0xD6, 0xD1),
    accent: Color32::from_rgb(0x0E, 0x0E, 0x0E),
    on_accent: Color32::WHITE,
    needs: Color32::from_rgb(0xD2, 0x1F, 0x26),
    needs_text: Color32::from_rgb(0xC4, 0x1A, 0x20),
};

pub const DARK: Palette = Palette {
    bg: Color32::BLACK,
    card: Color32::from_rgb(0x11, 0x11, 0x11),
    text: Color32::from_rgb(0xF5, 0xF5, 0xF2),
    secondary: Color32::from_rgb(0xC9, 0xC9, 0xCE),
    tertiary: Color32::from_rgb(0x9A, 0x9A, 0xA1),
    border: Color32::from_rgb(0x24, 0x24, 0x26),
    dot_off: Color32::from_rgb(0x2E, 0x2E, 0x31),
    accent: Color32::from_rgb(0xF5, 0xF5, 0xF2),
    on_accent: Color32::from_rgb(0x0E, 0x0E, 0x0E),
    needs: Color32::from_rgb(0xD2, 0x1F, 0x26),
    needs_text: Color32::from_rgb(0xFF, 0x4D, 0x4F),
};

pub fn install_fonts(ctx: &egui::Context) {
    let mut f = FontDefinitions::default();
    f.font_data.insert(
        "inter".into(),
        FontData::from_static(include_bytes!("../assets/inter_regular.ttf")).into(),
    );
    f.font_data.insert(
        "inter-semibold".into(),
        FontData::from_static(include_bytes!("../assets/inter_semibold.ttf")).into(),
    );
    f.font_data.insert(
        "doto".into(),
        FontData::from_static(include_bytes!("../assets/doto.ttf")).into(),
    );
    f.families
        .get_mut(&FontFamily::Proportional)
        .unwrap()
        .insert(0, "inter".into());
    f.families.insert(
        FontFamily::Name("semibold".into()),
        vec!["inter-semibold".into(), "inter".into()],
    );
    f.families.insert(
        FontFamily::Name("doto".into()),
        vec!["doto".into(), "inter".into()],
    );
    ctx.set_fonts(f);
}

fn inter(size: f32) -> FontId {
    FontId::new(size, FontFamily::Proportional)
}
fn semibold(size: f32) -> FontId {
    FontId::new(size, FontFamily::Name("semibold".into()))
}
fn doto(size: f32) -> FontId {
    FontId::new(size, FontFamily::Name("doto".into()))
}

/// What the user can do from the window; the app applies these.
#[derive(Default)]
pub struct Actions {
    pub pair_another: bool,
    pub back_to_status: bool,
    pub hide: bool,
    pub toggle_autostart: Option<bool>,
}

pub struct View<'a> {
    pub snap: &'a Snapshot,
    pub show_qr: bool,
    pub autostart: bool,
    pub now_ms: i64,
}

pub fn draw(ctx: &egui::Context, p: &Palette, v: &View) -> Actions {
    let mut a = Actions::default();
    let frame = egui::Frame::new()
        .fill(p.bg)
        .inner_margin(egui::Margin::symmetric(28, 24));
    egui::CentralPanel::default().frame(frame).show(ctx, |ui| {
        ui.spacing_mut().item_spacing = Vec2::new(0.0, 0.0);
        header(ui, p, &v.snap.computer);
        ui.add_space(22.0);
        if v.show_qr {
            qr_screen(ui, p, v, &mut a);
        } else {
            status_screen(ui, p, v, &mut a);
        }
    });
    a
}

fn header(ui: &mut egui::Ui, p: &Palette, computer: &str) {
    ui.horizontal(|ui| {
        cat(ui, p, 30.0);
        ui.add_space(12.0);
        ui.vertical(|ui| {
            ui.label(
                egui::RichText::new("Hypurr")
                    .font(semibold(20.0))
                    .color(p.text),
            );
            if !computer.is_empty() {
                ui.label(
                    egui::RichText::new(computer)
                        .font(inter(13.0))
                        .color(p.tertiary),
                );
            }
        });
    });
}

pub fn cat(ui: &mut egui::Ui, p: &Palette, size: f32) {
    let (rect, _) = ui.allocate_exact_size(Vec2::splat(size), Sense::hover());
    let cell = size / 9.0;
    for (y, row) in CAT.iter().enumerate() {
        for (x, ch) in row.chars().enumerate() {
            let c = Pos2::new(
                rect.left() + (x as f32 + 0.5) * cell,
                rect.top() + (y as f32 + 0.5) * cell,
            );
            ui.painter()
                .circle_filled(c, cell * 0.36, if ch == '#' { p.text } else { p.dot_off });
        }
    }
}

fn card(ui: &mut egui::Ui, p: &Palette, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::new()
        .fill(p.card)
        .stroke(Stroke::new(1.0_f32, p.border))
        .corner_radius(CornerRadius::same(22))
        .inner_margin(egui::Margin::same(20))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            add(ui)
        });
}

fn title(ui: &mut egui::Ui, p: &Palette, t: &str, hint: &str) {
    ui.label(egui::RichText::new(t).font(semibold(26.0)).color(p.text));
    ui.add_space(6.0);
    ui.label(
        egui::RichText::new(hint)
            .font(inter(15.0))
            .color(p.secondary),
    );
    ui.add_space(18.0);
}

fn button(ui: &mut egui::Ui, p: &Palette, text: &str, primary: bool) -> bool {
    let (rect, resp) =
        ui.allocate_exact_size(Vec2::new(ui.available_width(), 50.0), Sense::click());
    let (fill, fg) = if primary {
        (p.accent, p.on_accent)
    } else {
        (p.card, p.text)
    };
    let fill = if resp.hovered() {
        fill.gamma_multiply(0.92)
    } else {
        fill
    };
    ui.painter().rect(
        rect,
        CornerRadius::same(25),
        fill,
        Stroke::new(1.0_f32, if primary { fill } else { p.border }),
        egui::StrokeKind::Inside,
    );
    ui.painter().text(
        rect.center(),
        Align2::CENTER_CENTER,
        text,
        semibold(15.0),
        fg,
    );
    resp.on_hover_cursor(egui::CursorIcon::PointingHand)
        .clicked()
}

fn qr_screen(ui: &mut egui::Ui, p: &Palette, v: &View, a: &mut Actions) {
    title(
        ui,
        p,
        "Scan to pair your phone",
        "In the Hypurr app, tap Pair with QR and point it here.",
    );
    card(ui, p, |ui| {
        let side = ui.available_width().min(300.0);
        ui.vertical_centered(|ui| match &v.snap.pairing_url {
            Some(url) => qr(ui, url, side),
            None => {
                let (r, _) = ui.allocate_exact_size(Vec2::splat(side), Sense::hover());
                let msg = if v.snap.running {
                    "Making a code…"
                } else {
                    "Starting Hypurr…"
                };
                ui.painter().text(
                    r.center(),
                    Align2::CENTER_CENTER,
                    msg,
                    inter(15.0),
                    p.tertiary,
                );
            }
        });
        ui.add_space(14.0);
        ui.horizontal(|ui| {
            let mins = model::minutes_left(v.snap.expires_at_ms, v.now_ms);
            ui.label(
                egui::RichText::new(format!("{mins:02}"))
                    .font(doto(34.0))
                    .color(p.text),
            );
            ui.add_space(8.0);
            ui.label(
                egui::RichText::new("min left · a new code appears by itself")
                    .font(inter(13.0))
                    .color(p.tertiary),
            );
        });
    });
    ui.add_space(14.0);
    if let Network::Public(name) = &v.snap.network {
        needs_line(ui, p, &model::public_network_hint(name));
        ui.add_space(10.0);
    }
    ui.label(
        egui::RichText::new(model::FIREWALL_HINT)
            .font(inter(13.0))
            .color(p.tertiary),
    );
    ui.add_space(16.0);
    if v.snap.paired_phones > 0 && button(ui, p, "Done", true) {
        a.back_to_status = true;
    }
}

fn needs_line(ui: &mut egui::Ui, p: &Palette, text: &str) {
    ui.horizontal_top(|ui| {
        let (r, _) = ui.allocate_exact_size(Vec2::new(16.0, 18.0), Sense::hover());
        ui.painter()
            .circle_filled(Pos2::new(r.left() + 5.0, r.center().y), 4.0, p.needs);
        ui.add(
            egui::Label::new(
                egui::RichText::new(text)
                    .font(inter(13.0))
                    .color(p.needs_text),
            )
            .wrap(),
        );
    });
}

fn qr(ui: &mut egui::Ui, url: &str, side: f32) {
    let Ok(code) = qrcode::QrCode::new(url.as_bytes()) else {
        return;
    };
    let w = code.width();
    let quiet = 2usize;
    let (rect, _) = ui.allocate_exact_size(Vec2::splat(side), Sense::hover());
    // Always dark-on-white so every phone camera reads it, in dark mode too.
    ui.painter()
        .rect_filled(rect, CornerRadius::same(12), Color32::WHITE);
    let m = side / (w + quiet * 2) as f32;
    for (i, c) in code.to_colors().iter().enumerate() {
        if *c == qrcode::Color::Dark {
            let (x, y) = ((i % w + quiet) as f32, (i / w + quiet) as f32);
            let r = Rect::from_min_size(
                Pos2::new(rect.left() + x * m, rect.top() + y * m),
                Vec2::splat(m + 0.4),
            );
            ui.painter()
                .rect_filled(r, 0.0, Color32::from_rgb(0x0E, 0x0E, 0x0E));
        }
    }
}

fn status_screen(ui: &mut egui::Ui, p: &Palette, v: &View, a: &mut Actions) {
    let headline = if v.snap.running {
        "Hypurr is running"
    } else {
        "Hypurr isn't running"
    };
    title(
        ui,
        p,
        headline,
        "Your phone talks to this PC. You can close this window.",
    );
    card(ui, p, |ui| {
        for (i, r) in model::rows(v.snap).iter().enumerate() {
            if i > 0 {
                ui.add_space(12.0);
                let (l, _) =
                    ui.allocate_exact_size(Vec2::new(ui.available_width(), 1.0), Sense::hover());
                ui.painter().rect_filled(l, 0.0, p.border);
                ui.add_space(12.0);
            }
            ui.horizontal_top(|ui| {
                let (d, _) = ui.allocate_exact_size(Vec2::new(22.0, 20.0), Sense::hover());
                ui.painter().circle_filled(
                    Pos2::new(d.left() + 6.0, d.center().y),
                    5.0,
                    if r.needs_you { p.needs } else { p.text },
                );
                ui.vertical(|ui| {
                    ui.label(
                        egui::RichText::new(r.label)
                            .font(inter(12.0))
                            .color(p.tertiary),
                    );
                    ui.add_space(2.0);
                    let col = if r.needs_you { p.needs_text } else { p.text };
                    ui.add(
                        egui::Label::new(
                            egui::RichText::new(&r.value)
                                .font(semibold(16.0))
                                .color(col),
                        )
                        .wrap(),
                    );
                });
            });
        }
    });
    ui.add_space(14.0);
    card(ui, p, |ui| {
        ui.horizontal(|ui| {
            ui.vertical(|ui| {
                ui.label(
                    egui::RichText::new("Start with Windows")
                        .font(semibold(15.0))
                        .color(p.text),
                );
                ui.add_space(2.0);
                ui.label(
                    egui::RichText::new("Open Hypurr in the tray when you sign in.")
                        .font(inter(13.0))
                        .color(p.tertiary),
                );
            });
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if toggle(ui, p, v.autostart) {
                    a.toggle_autostart = Some(!v.autostart);
                }
            });
        });
    });
    ui.add_space(18.0);
    if button(ui, p, "Pair another phone", true) {
        a.pair_another = true;
    }
    ui.add_space(10.0);
    if button(ui, p, "Hide to tray", false) {
        a.hide = true;
    }
    ui.add_space(12.0);
    ui.vertical_centered(|ui| {
        ui.label(
            egui::RichText::new("Closing the window keeps Hypurr running. Quit from the tray.")
                .font(inter(12.0))
                .color(p.tertiary),
        );
    });
}

fn toggle(ui: &mut egui::Ui, p: &Palette, on: bool) -> bool {
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(50.0, 30.0), Sense::click());
    let track = if on { p.accent } else { p.dot_off };
    ui.painter()
        .rect_filled(rect, CornerRadius::same(15), track);
    let x = if on {
        rect.right() - 15.0
    } else {
        rect.left() + 15.0
    };
    ui.painter().circle_filled(
        Pos2::new(x, rect.center().y),
        11.0,
        if on { p.on_accent } else { p.card },
    );
    resp.on_hover_cursor(egui::CursorIcon::PointingHand)
        .clicked()
}

/// The cat mark as RGBA (black dots on transparent) for the tray and window icon.
pub fn cat_icon_rgba(size: u32) -> (Vec<u8>, u32) {
    let mut px = vec![0u8; (size * size * 4) as usize];
    let cell = size as f32 / 9.0;
    for (y, row) in CAT.iter().enumerate() {
        for (x, ch) in row.chars().enumerate() {
            let (cx, cy) = ((x as f32 + 0.5) * cell, (y as f32 + 0.5) * cell);
            let (r, col) = if ch == '#' {
                (cell * 0.42, [0x0E, 0x0E, 0x0E])
            } else {
                (cell * 0.30, [0xB0, 0xB0, 0xAA])
            };
            for py in 0..size {
                for pxl in 0..size {
                    let (dx, dy) = (pxl as f32 + 0.5 - cx, py as f32 + 0.5 - cy);
                    if dx * dx + dy * dy <= r * r {
                        let i = ((py * size + pxl) * 4) as usize;
                        px[i..i + 3].copy_from_slice(&col);
                        px[i + 3] = 255;
                    }
                }
            }
        }
    }
    (px, size)
}

#[cfg(test)]
mod tests {
    #[test]
    fn cat_is_nine_by_nine() {
        assert!(super::CAT.iter().all(|r| r.len() == 9));
        let (px, s) = super::cat_icon_rgba(32);
        assert_eq!(px.len(), (s * s * 4) as usize);
        assert!(px.chunks(4).any(|p| p[3] == 255));
    }
}
