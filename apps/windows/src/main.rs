//! Hypurr for Windows: a small tray app that runs the host in the background and
//! pairs a phone with a QR code. No console window, no admin rights.
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod autostart;
mod host;
mod model;
#[cfg(windows)]
mod tray;
mod ui;

use eframe::egui;
use model::{Agent, Network, Snapshot};
use std::sync::{Arc, Mutex};

#[derive(Default, Clone)]
struct Args {
    background: bool,
    demo: Option<String>,
    theme: Option<String>,
    screenshot: Option<String>,
}

fn parse_args() -> Args {
    let mut a = Args::default();
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--background" => a.background = true,
            "--demo" => a.demo = it.next(),
            "--theme" => a.theme = it.next(),
            "--screenshot" => a.screenshot = it.next(),
            _ => {}
        }
    }
    a
}

/// Fixed data for screenshots and UI checks (no host is started).
fn demo(kind: &str) -> Snapshot {
    let now = host::now_ms();
    let mut s = Snapshot {
        running: true,
        computer: "RAGUL-PC".into(),
        pairing_url: Some(
            "hypurr://pair?h=demo&c=0123456789abcdef&k=ZGVtby1rZXktZm9yLXNjcmVlbnNob3Rz".into(),
        ),
        expires_at_ms: now + 9 * 60_000 + 30_000,
        agent: Agent::Installed(vec!["Hypurr Agent".into(), "Claude Code".into()]),
        network: Network::Private,
        ..Default::default()
    };
    match kind {
        "status" => (s.paired_phones, s.connected_phones) = (1, 1),
        "public" => s.network = Network::Public("Cafe Wi-Fi".into()),
        _ => {}
    }
    s
}

struct App {
    args: Args,
    state: Arc<Mutex<Snapshot>>,
    host: Option<host::Host>,
    show_qr_override: Option<bool>,
    autostart: bool,
    frames: u32,
    shot_requested: bool,
    #[cfg(windows)]
    _tray: Option<tray::Tray>,
}

impl App {
    fn new(cc: &eframe::CreationContext, args: Args) -> Self {
        ui::install_fonts(&cc.egui_ctx);
        let (state, host) = match &args.demo {
            Some(kind) => (Arc::new(Mutex::new(demo(kind))), None),
            None => {
                let ctx = cc.egui_ctx.clone();
                let h = host::Host::start(move || ctx.request_repaint());
                (h.state.clone(), Some(h))
            }
        };
        #[cfg(windows)]
        let tray = if args.demo.is_none() {
            let (show_ctx, quit_ctx) = (cc.egui_ctx.clone(), cc.egui_ctx.clone());
            let quit_state = QUIT.clone();
            tray::install(
                move || {
                    show_ctx.send_viewport_cmd(egui::ViewportCommand::Visible(true));
                    show_ctx.send_viewport_cmd(egui::ViewportCommand::Focus);
                    show_ctx.request_repaint();
                },
                move || {
                    *quit_state.lock().unwrap() = true;
                    quit_ctx.send_viewport_cmd(egui::ViewportCommand::Visible(true));
                    quit_ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                    quit_ctx.request_repaint();
                },
            )
        } else {
            None
        };
        App {
            autostart: args.demo.is_none() && autostart::is_enabled(),
            args,
            state,
            host,
            show_qr_override: None,
            frames: 0,
            shot_requested: false,
            #[cfg(windows)]
            _tray: tray,
        }
    }
}

static QUIT: std::sync::LazyLock<Arc<Mutex<bool>>> =
    std::sync::LazyLock::new(|| Arc::new(Mutex::new(false)));

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        let dark = match self.args.theme.as_deref() {
            Some("dark") => true,
            Some("light") => false,
            _ => ctx.system_theme() == Some(egui::Theme::Dark),
        };
        ctx.set_visuals(if dark {
            egui::Visuals::dark()
        } else {
            egui::Visuals::light()
        });
        let palette = if dark { ui::DARK } else { ui::LIGHT };
        let snap = self.state.lock().unwrap().clone();
        let show_qr = self.show_qr_override.unwrap_or(snap.wants_qr());
        let view = ui::View {
            snap: &snap,
            show_qr,
            autostart: self.autostart,
            now_ms: host::now_ms(),
        };
        let act = ui::draw(ctx, &palette, &view);
        if act.pair_another {
            self.show_qr_override = Some(true);
            if let Some(h) = &self.host {
                *h.force_new_code.lock().unwrap() = true;
            }
        }
        if act.back_to_status {
            self.show_qr_override = Some(false);
        }
        if act.hide {
            ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
        }
        if let Some(on) = act.toggle_autostart
            && autostart::set(on).is_ok()
        {
            self.autostart = on;
        }
        // Closing the window hides it to the tray; Quit in the tray really exits.
        if ctx.input(|i| i.viewport().close_requested()) {
            if *QUIT.lock().unwrap() || self.args.demo.is_some() || !cfg!(windows) {
                if let Some(h) = &self.host {
                    h.stop();
                }
            } else {
                ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
                ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
            }
        }
        if let Some(path) = &self.args.screenshot {
            self.frames += 1;
            // Live runs wait for the first pairing code (or ~30 s) so the shot shows the real QR.
            let ready =
                self.args.demo.is_some() || snap.pairing_url.is_some() || self.frames > 1800;
            if self.frames >= 4 && ready && !self.shot_requested {
                self.shot_requested = true;
                ctx.send_viewport_cmd(egui::ViewportCommand::Screenshot(egui::UserData::default()));
            }
            let shot = ctx.input(|i| {
                i.events.iter().find_map(|e| match e {
                    egui::Event::Screenshot { image, .. } => Some(image.clone()),
                    _ => None,
                })
            });
            if let Some(img) = shot {
                save_png(path, &img);
                if let Some(h) = &self.host {
                    h.stop();
                }
                std::process::exit(0);
            }
            ctx.request_repaint();
        } else {
            ctx.request_repaint_after(std::time::Duration::from_secs(1));
        }
    }
}

fn save_png(path: &str, img: &egui::ColorImage) {
    let [w, h] = img.size;
    let bytes: Vec<u8> = img.pixels.iter().flat_map(|c| c.to_array()).collect();
    if let Some(buf) = image::RgbaImage::from_raw(w as u32, h as u32, bytes) {
        let _ = buf.save(path);
    }
}

fn main() -> eframe::Result<()> {
    let args = parse_args();
    let (rgba, size) = ui::cat_icon_rgba(64);
    let viewport = egui::ViewportBuilder::default()
        .with_title("Hypurr")
        .with_inner_size([460.0, 760.0])
        .with_min_inner_size([400.0, 600.0])
        .with_visible(!args.background)
        .with_icon(egui::IconData {
            rgba,
            width: size,
            height: size,
        });
    let opts = eframe::NativeOptions {
        viewport,
        ..Default::default()
    };
    let a = args.clone();
    eframe::run_native(
        "Hypurr",
        opts,
        Box::new(move |cc| Ok(Box::new(App::new(cc, a)))),
    )
}
