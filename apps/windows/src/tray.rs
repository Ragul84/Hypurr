//! System tray: closing the window keeps Hypurr running; Quit lives here.

use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
use tray_icon::{Icon, TrayIcon, TrayIconBuilder, TrayIconEvent};

pub struct Tray {
    _icon: TrayIcon,
}

/// `on_show` and `on_quit` run on the UI thread's message loop.
pub fn install(
    on_show: impl Fn() + Send + Sync + 'static,
    on_quit: impl Fn() + Send + Sync + 'static,
) -> Option<Tray> {
    let open = MenuItem::new("Open Hypurr", true, None);
    let quit = MenuItem::new("Quit Hypurr", true, None);
    let menu = Menu::new();
    menu.append_items(&[&open, &PredefinedMenuItem::separator(), &quit])
        .ok()?;
    let (open_id, quit_id) = (open.id().clone(), quit.id().clone());
    let on_show = std::sync::Arc::new(on_show);
    let show2 = on_show.clone();
    MenuEvent::set_event_handler(Some(move |e: MenuEvent| {
        if e.id == open_id {
            on_show();
        } else if e.id == quit_id {
            on_quit();
        }
    }));
    TrayIconEvent::set_event_handler(Some(move |e: TrayIconEvent| {
        if let TrayIconEvent::DoubleClick { .. } = e {
            show2();
        }
    }));
    let (rgba, size) = crate::ui::cat_icon_rgba(32);
    let icon = Icon::from_rgba(rgba, size, size).ok()?;
    let tray = TrayIconBuilder::new()
        .with_tooltip("Hypurr is running")
        .with_menu(Box::new(menu))
        .with_icon(icon)
        .build()
        .ok()?;
    Some(Tray { _icon: tray })
}
