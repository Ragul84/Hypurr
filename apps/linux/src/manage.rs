//! Memory and routines use the same host-owned state and schedule validation as SwiftUI.
use crate::{
    client,
    rows::{icon_button, label},
    ui::{self, App, toast},
};
use adw::prelude::*;
use serde_json::{Value, json};
use std::{cell::RefCell, rc::Rc};

pub fn dialog(ui: &App, title: &str) -> (adw::Dialog, gtk::Box) {
    let body = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(14)
        .margin_top(20)
        .margin_bottom(20)
        .margin_start(20)
        .margin_end(20)
        .build();
    let scroll = gtk::ScrolledWindow::builder()
        .child(&body)
        .vexpand(true)
        .build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    view.set_content(Some(&scroll));
    let dialog = adw::Dialog::builder()
        .title(title)
        .content_width(620)
        .content_height(650)
        .child(&view)
        .build();
    dialog.present(Some(&ui.window));
    (dialog, body)
}

pub fn memory(ui: &App, bot: &str) {
    let (_, body) = dialog(ui, "Memory");
    let refresh = icon_button("view-refresh-symbolic", "Refresh memory");
    body.append(&refresh);
    let list = gtk::Box::new(gtk::Orientation::Vertical, 12);
    body.append(&list);
    let (ui2, bot2, list2) = (ui.clone(), bot.to_owned(), list.clone());
    refresh.connect_clicked(move |_| load_memory(&ui2, &bot2, &list2));
    load_memory(ui, bot, &list);
}

fn load_memory(ui: &App, bot: &str, list: &gtk::Box) {
    ui::clear(list);
    list.append(&label("Loading…", &["secondary"]));
    let (ui, bot, list) = (ui.clone(), bot.to_owned(), list.clone());
    client::call("memory", json!({"botId":bot}), move |r| {
        ui::clear(&list);
        let v = match r {
            Ok(v) => v,
            Err(e) => {
                list.append(&label(&e, &["danger-text"]));
                return;
            }
        };
        let facts = v["facts"].as_array().cloned().unwrap_or_default();
        if facts.is_empty() {
            list.append(&label(
                "Nothing yet. The bot remembers as you chat.",
                &["secondary"],
            ));
        }
        for fact in &facts {
            let row = gtk::Box::new(gtk::Orientation::Horizontal, 10);
            let text = label(fact["content"].as_str().unwrap_or(""), &[]);
            text.set_wrap(true);
            text.set_hexpand(true);
            text.set_selectable(true);
            row.append(&text);
            let remove = icon_button("edit-delete-symbolic", "Forget");
            row.append(&remove);
            list.append(&row);
            let (ui, bot, list, id) = (ui.clone(), bot.clone(), list.clone(), fact["id"].clone());
            remove.connect_clicked(move |button| {
                button.set_sensitive(false);
                let (ui, bot, list) = (ui.clone(), bot.clone(), list.clone());
                client::call("forgetMemory", json!({"botId":bot,"id":id}), move |r| {
                    if let Err(e) = r {
                        toast(&ui, &e);
                    }
                    load_memory(&ui, &bot, &list);
                });
            });
        }
        if !facts.is_empty() {
            let clear = gtk::Button::with_label("Forget everything");
            list.append(&clear);
            let (ui, bot, list) = (ui.clone(), bot.clone(), list.clone());
            clear.connect_clicked(move |_| {
                let (ui2, bot, list) = (ui.clone(), bot.clone(), list.clone());
                crate::dialogs::confirm(
                    &ui,
                    "Forget everything?",
                    "All remembered facts will be removed. Your chat stays.",
                    "Forget everything",
                    true,
                    move || {
                        client::call("clearMemory", json!({"botId":bot}), move |r| {
                            if let Err(e) = r {
                                toast(&ui2, &e);
                            }
                            load_memory(&ui2, &bot, &list);
                        });
                    },
                );
            });
        }
    });
}

pub fn routines(ui: &App, bot: &str) {
    let (window, body) = dialog(ui, "Routines");
    let actions = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    let add = icon_button("list-add-symbolic", "Set up a routine");
    let ask = icon_button("chat-message-new-symbolic", "Ask the bot for a routine");
    let refresh = icon_button("view-refresh-symbolic", "Refresh routines");
    actions.append(&add);
    actions.append(&ask);
    actions.append(&refresh);
    body.append(&actions);
    let list = gtk::Box::new(gtk::Orientation::Vertical, 14);
    body.append(&list);
    let (ui2, bot2, list2) = (ui.clone(), bot.to_owned(), list.clone());
    add.connect_clicked(move |_| routine_editor(&ui2, &bot2, Value::Null, &list2));
    // Or ask the bot to set one up: start the ask in its chat.
    let ui2 = ui.clone();
    ask.connect_clicked(move |_| {
        window.close();
        ui::insert_draft(&ui2, "I want a routine that ");
    });
    let (ui2, bot2, list2) = (ui.clone(), bot.to_owned(), list.clone());
    refresh.connect_clicked(move |_| load_routines(&ui2, &bot2, &list2));
    load_routines(ui, bot, &list);
}

fn load_routines(ui: &App, bot: &str, list: &gtk::Box) {
    ui::clear(list);
    list.append(&label("Loading…", &["secondary"]));
    let (ui, bot, list) = (ui.clone(), bot.to_owned(), list.clone());
    client::call("routines", json!({"botId":bot}), move |r| {
        ui::clear(&list);
        let v = match r {
            Ok(v) => v,
            Err(e) => {
                list.append(&label(&e, &["danger-text"]));
                return;
            }
        };
        let items = v["routines"].as_array().cloned().unwrap_or_default();
        if items.is_empty() {
            let empty = label(
                "No routines yet. Ask the bot for one, or set it up yourself with +.",
                &["secondary"],
            );
            empty.set_wrap(true);
            list.append(&empty);
            return;
        }
        let rows = gtk::ListBox::builder()
            .selection_mode(gtk::SelectionMode::None)
            .css_classes(["boxed-list"])
            .build();
        list.append(&rows);
        for item in items {
            let active = v["runs"].as_array().into_iter().flatten().find(|run| {
                run["routineId"] == item["id"]
                    && matches!(
                        run["status"].as_str(),
                        Some("pending" | "starting" | "running" | "recovering")
                    )
            });
            // What's happening now, else when it runs.
            let summary = if let Some(run) = active {
                run_label(run["status"].as_str().unwrap_or(""))
            } else if item["lastError"].is_string() {
                "Schedule needs attention".to_owned()
            } else {
                item["triggerDescriptions"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(Value::as_str)
                    .collect::<Vec<_>>()
                    .join(" · ")
            };
            let row = adw::ActionRow::builder()
                .title(item["name"].as_str().unwrap_or("Routine"))
                .subtitle(summary)
                .tooltip_text(item["instruction"].as_str().unwrap_or(""))
                .build();
            rows.append(&row);
            // A row opens the same form as a new routine, filled in.
            row.set_activatable(true);
            let (ui2, bot2, item2, list2) = (ui.clone(), bot.clone(), item.clone(), list.clone());
            row.connect_activated(move |_| routine_editor(&ui2, &bot2, item2.clone(), &list2));
            for (icon, title, method) in [
                ("media-playback-start-symbolic", "Test run", "runRoutine"),
                ("user-trash-symbolic", "Delete routine", "deleteRoutine"),
            ] {
                let button = icon_button(icon, title);
                button.set_valign(gtk::Align::Center);
                button.set_sensitive(method != "runRoutine" || active.is_none());
                row.add_suffix(&button);
                let (ui, bot, item, list) = (ui.clone(), bot.clone(), item.clone(), list.clone());
                button.connect_clicked(move |_| {
                    let (ui2, bot, list, id) =
                        (ui.clone(), bot.clone(), list.clone(), item["id"].clone());
                    let run = move || {
                        routine_call(&ui2, method, json!({"botId":bot,"id":id}), &bot, &list)
                    };
                    if method == "deleteRoutine" {
                        crate::dialogs::confirm(
                            &ui,
                            "Delete routine?",
                            "This stops future runs. Past messages stay.",
                            "Delete",
                            true,
                            run,
                        );
                    } else {
                        run();
                    }
                });
            }
            let on = gtk::Switch::builder()
                .active(item["enabled"] == true)
                .valign(gtk::Align::Center)
                .tooltip_text(if item["enabled"] == true {
                    "Pause"
                } else {
                    "Resume"
                })
                .build();
            row.add_suffix(&on);
            let (ui, bot, id, list) = (ui.clone(), bot.clone(), item["id"].clone(), list.clone());
            on.connect_state_set(move |_, enabled| {
                routine_call(
                    &ui,
                    "setRoutineEnabled",
                    json!({"botId":bot,"id":id,"enabled":enabled}),
                    &bot,
                    &list,
                );
                gtk::glib::Propagation::Proceed
            });
        }
    });
}

fn routine_call(ui: &App, method: &str, args: Value, bot: &str, list: &gtk::Box) {
    let (ui, bot, list) = (ui.clone(), bot.to_owned(), list.clone());
    client::call(method, args, move |r| {
        if let Err(e) = r {
            toast(&ui, &e);
        }
        load_routines(&ui, &bot, &list);
    });
}

fn run_label(status: &str) -> String {
    match status {
        "pending" => "Queued",
        "starting" => "Starting",
        "running" => "Running",
        _ => "Resuming after restart",
    }
    .to_owned()
}

fn schedule_time(at: i64) -> String {
    gtk::glib::DateTime::from_unix_local(at / 1000)
        .ok()
        .and_then(|date| date.format("%b %e, %Y · %H:%M %Z").ok())
        .map(|s| s.to_string())
        .unwrap_or_default()
}

fn entry(body: &gtk::Box, title: &str, value: &str) -> gtk::Entry {
    let group = gtk::Box::new(gtk::Orientation::Vertical, 6);
    group.append(&label(title, &["secondary"]));
    let field = gtk::Entry::builder().text(value).build();
    group.append(&field);
    body.append(&group);
    field
}

fn routine_editor(ui: &App, bot: &str, routine: Value, list: &gtk::Box) {
    let (window, body) = dialog(
        ui,
        if routine.is_null() {
            "Set up a routine"
        } else {
            "Edit routine"
        },
    );
    let name = entry(&body, "Name", routine["name"].as_str().unwrap_or(""));
    body.append(&label("Instruction", &["secondary"]));
    let instruction = gtk::TextView::builder()
        .wrap_mode(gtk::WrapMode::WordChar)
        .height_request(100)
        .build();
    instruction
        .buffer()
        .set_text(routine["instruction"].as_str().unwrap_or(""));
    let scroll = gtk::ScrolledWindow::builder()
        .child(&instruction)
        .hscrollbar_policy(gtk::PolicyType::Never)
        .min_content_height(80)
        .max_content_height(120)
        .propagate_natural_height(true)
        .vexpand(false)
        .build();
    body.append(&scroll);
    // Schedule or webhook, as in Claude Code routines. Anything else the bot set up
    // (a one-off time, an interval, events, several triggers) stays until replaced.
    let triggers = routine["triggers"].as_array().cloned().unwrap_or_default();
    let mut kinds = vec!["cron", "webhook"];
    let keeps = match triggers.as_slice() {
        [] => false,
        [t] => !matches!(t["type"].as_str(), Some("cron" | "webhook")),
        _ => true,
    };
    if keeps {
        kinds.insert(0, "keep");
    } else if triggers.first().is_some_and(|t| t["type"] == "webhook") {
        kinds.reverse();
    }
    let current = routine["triggerDescriptions"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .collect::<Vec<_>>()
        .join(" · ");
    body.append(&label("When to run", &["secondary"]));
    let labels: Vec<&str> = kinds
        .iter()
        .map(|k| match *k {
            "keep" => current.as_str(),
            "cron" => "Schedule",
            _ => "Webhook",
        })
        .collect();
    let kind = gtk::DropDown::from_strings(&labels);
    body.append(&kind);
    // An existing cron trigger fills the fields, so switching to Cron edits it.
    let cron = routine["triggers"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|t| t["type"] == "cron");
    let expression = entry(
        &body,
        "Cron (minute hour day month weekday)",
        cron.and_then(|t| t["expression"].as_str())
            .unwrap_or("0 9 * * *"),
    );
    let local_zone = gtk::glib::TimeZone::local().identifier().to_string();
    let zone = entry(
        &body,
        "Time zone",
        cron.and_then(|t| t["timeZone"].as_str())
            .unwrap_or(&local_zone),
    );
    let existing = label(
        "Set up by the bot. Pick Schedule or Webhook to replace it.",
        &["secondary"],
    );
    existing.set_wrap(true);
    body.append(&existing);
    let saved_hook = triggers
        .iter()
        .any(|t| matches!(t["type"].as_str(), Some("webhook" | "event")));
    let hook = if saved_hook {
        webhook_panel(ui, bot, routine["id"].as_str().unwrap_or_default())
    } else {
        let note = label(
            "Runs each time something is posted to its URL. Save to get the URL and key.",
            &["secondary"],
        );
        note.set_wrap(true);
        note.upcast()
    };
    body.append(&hook);
    let update_fields = {
        let (kinds, zone, expression, existing, hook) = (
            kinds.clone(),
            zone.clone(),
            expression.clone(),
            existing.clone(),
            hook.clone(),
        );
        move |kind: &gtk::DropDown| {
            let style = kinds[kind.selected() as usize];
            for (field, visible) in [(&expression, style == "cron"), (&zone, style == "cron")] {
                if let Some(group) = field.parent() {
                    group.set_visible(visible);
                }
            }
            existing.set_visible(style == "keep");
            hook.set_visible(style == "webhook");
        }
    };
    update_fields(&kind);
    kind.connect_selected_notify(update_fields);
    let timeout = entry(
        &body,
        "Run timeout (seconds)",
        &routine["timeoutSeconds"]
            .as_i64()
            .unwrap_or(3600)
            .to_string(),
    );
    let status = label("", &["secondary"]);
    status.set_wrap(true);
    body.append(&status);
    let actions = gtk::Box::builder()
        .orientation(gtk::Orientation::Horizontal)
        .spacing(8)
        .margin_top(12)
        .margin_bottom(12)
        .margin_start(20)
        .margin_end(20)
        .build();
    if let Some(view) = window
        .child()
        .and_then(|w| w.downcast::<adw::ToolbarView>().ok())
    {
        view.add_bottom_bar(&actions);
    }
    for (title, save) in [("Preview schedule", false), ("Save", true)] {
        let button = gtk::Button::with_label(title);
        actions.append(&button);
        let (
            ui,
            bot,
            routine,
            list,
            window,
            name,
            instruction,
            kind,
            kinds,
            zone,
            expression,
            timeout,
            status,
        ) = (
            ui.clone(),
            bot.to_owned(),
            routine.clone(),
            list.clone(),
            window.clone(),
            name.clone(),
            instruction.clone(),
            kind.clone(),
            kinds.clone(),
            zone.clone(),
            expression.clone(),
            timeout.clone(),
            status.clone(),
        );
        button.connect_clicked(move |button| {
            let style = kinds[kind.selected() as usize];
            let draft = schedule_draft(style, &zone.text(), &expression.text(), &routine["triggers"]);
            let buffer = instruction.buffer();
            let text = buffer.text(&buffer.start_iter(), &buffer.end_iter(), false).to_string();
            let timeout = match timeout.text().parse::<u64>() { Ok(v) if (1..=86400).contains(&v) => v, _ => { status.set_label("Timeout must be between 1 and 86400 seconds."); return; } };
            if save && (name.text().trim().is_empty() || text.trim().is_empty()) { status.set_label("Enter a name and instruction."); return; }
            button.set_sensitive(false);
            let mut args = json!({"draft":draft});
            if save { args = json!({"botId":bot,"name":name.text().as_str(),"instruction":text,"schedule":draft,"timeoutSeconds":timeout}); if !routine.is_null() { args["id"] = routine["id"].clone(); } }
            let (ui, bot, list, window, status, button, routine) = (ui.clone(), bot.clone(), list.clone(), window.clone(), status.clone(), button.clone(), routine.clone());
            client::call(if save { "saveRoutine" } else { "routineSchedule" }, args, move |r| {
                button.set_sensitive(true);
                match r {
                    Ok(v) if save => {
                        window.close();
                        load_routines(&ui, &bot, &list);
                        // A new webhook routine reopens on its URL and key, which exist only now.
                        if routine.is_null() && style == "webhook" { routine_editor(&ui, &bot, v["routine"].clone(), &list); }
                    }
                    Ok(v) => status.set_label(&format!("{}{}{}", v["summary"].as_str().unwrap_or(""),
                        v["nextRunAt"].as_i64().map(|at| format!("\nNext: {}", schedule_time(at))).unwrap_or_default(),
                        v["warning"].as_str().map(|w| format!("\n{w}")).unwrap_or_default())),
                    Err(e) => status.set_label(&e),
                }
            });
        });
    }
}

/// A saved webhook routine's URL and key, each with a copy button; the key can be shown and
/// replaced. Same as the Apple editor's webhook panel.
fn webhook_panel(ui: &App, bot: &str, id: &str) -> gtk::Widget {
    let panel = gtk::Box::new(gtk::Orientation::Vertical, 8);
    let url_title = label("URL", &["secondary"]);
    let url = label("Loading webhook…", &["monospace"]);
    url.set_wrap(true);
    url.set_wrap_mode(gtk::pango::WrapMode::Char);
    url.set_selectable(true);
    url.set_hexpand(true);
    let copy_url = icon_button("edit-copy-symbolic", "Copy URL");
    let url_row = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    url_row.append(&url);
    url_row.append(&copy_url);
    let key = label("", &["monospace"]);
    key.set_hexpand(true);
    key.set_selectable(true);
    let show = icon_button("view-reveal-symbolic", "Show key");
    let rotate = icon_button("view-refresh-symbolic", "Replace key");
    let copy_key = icon_button("edit-copy-symbolic", "Copy key");
    let key_row = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    for w in [
        key.upcast_ref::<gtk::Widget>(),
        show.upcast_ref(),
        rotate.upcast_ref(),
        copy_key.upcast_ref(),
    ] {
        key_row.append(w);
    }
    let note = label("", &["secondary"]);
    note.set_wrap(true);
    panel.append(&url_title);
    panel.append(&url_row);
    panel.append(&label("Key", &["secondary"]));
    panel.append(&key_row);
    panel.append(&note);

    let state: Rc<RefCell<Value>> = Rc::new(RefCell::new(Value::Null));
    let shown = Rc::new(std::cell::Cell::new(false));
    let render = {
        let (state, shown, url_title, url, key, show, note) = (
            state.clone(),
            shown.clone(),
            url_title.clone(),
            url.clone(),
            key.clone(),
            show.clone(),
            note.clone(),
        );
        move || {
            let v = state.borrow();
            let public = v["url"].as_str();
            url_title.set_label(if public.is_some() { "URL" } else { "Local URL" });
            url.set_label(
                public
                    .or_else(|| v["localUrl"].as_str())
                    .unwrap_or_default(),
            );
            let secret = v["key"].as_str().unwrap_or_default();
            key.set_label(&if shown.get() {
                secret.to_owned()
            } else {
                "•".repeat(24)
            });
            show.set_icon_name(if shown.get() {
                "view-conceal-symbolic"
            } else {
                "view-reveal-symbolic"
            });
            show.set_tooltip_text(Some(if shown.get() { "Hide key" } else { "Show key" }));
            note.set_label(if public.is_some() {
                "POST with Authorization: Bearer <key>. For GitHub, use content type application/json and the key as the secret. Deliveries wait up to 72 hours while this computer is off. They pass through the Hypurr cloud, which can read them."
            } else {
                "The Hypurr cloud is off, so only this computer can send to it."
            });
        }
    };
    let load = {
        let (ui, bot, id, state, shown, render, note) = (
            ui.clone(),
            bot.to_owned(),
            id.to_owned(),
            state.clone(),
            shown.clone(),
            render.clone(),
            note.clone(),
        );
        Rc::new(move |rotate: bool| {
            let (ui, state, shown, render, note) = (
                ui.clone(),
                state.clone(),
                shown.clone(),
                render.clone(),
                note.clone(),
            );
            client::call(
                "routineWebhook",
                json!({"botId": bot, "id": id, "rotate": rotate}),
                move |r| match r {
                    Ok(v) => {
                        *state.borrow_mut() = v;
                        if rotate {
                            shown.set(true);
                            toast(&ui, "Key replaced");
                        }
                        render();
                    }
                    Err(e) => note.set_label(&e),
                },
            );
        })
    };
    load(false);
    {
        let (shown, render) = (shown.clone(), render.clone());
        show.connect_clicked(move |_| {
            shown.set(!shown.get());
            render();
        });
    }
    {
        let (ui, load) = (ui.clone(), load.clone());
        rotate.connect_clicked(move |_| {
            let load = load.clone();
            crate::dialogs::confirm(
                &ui,
                "Replace the key?",
                "Senders using the current key stop working until they get the new one.",
                "Replace key",
                true,
                move || load(true),
            );
        });
    }
    for (button, field) in [(&copy_url, "url"), (&copy_key, "key")] {
        let (ui, state) = (ui.clone(), state.clone());
        button.connect_clicked(move |b| {
            let v = state.borrow();
            let text = match field {
                "key" => v["key"].as_str(),
                _ => v["url"].as_str().or_else(|| v["localUrl"].as_str()),
            };
            if let Some(text) = text {
                b.clipboard().set_text(text);
                toast(
                    &ui,
                    if field == "key" {
                        "Copied the key"
                    } else {
                        "Copied the URL"
                    },
                );
            }
        });
    }
    panel.upcast()
}

/// The form as the host's schedule draft: cron is sent as written, the host checks it.
fn schedule_draft(style: &str, zone: &str, expression: &str, original: &Value) -> Value {
    let mut draft = json!({"kind":style,"zone":zone,"original":original.as_array().cloned().unwrap_or_default()});
    if style == "cron" {
        draft["calendarStyle"] = "custom".into();
        draft["expression"] = expression.into();
    }
    draft
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn edits_preserve_existing_event_triggers() {
        let original = json!([{"type":"event","source":"github","event":"pull_request.opened","filters":{"repo":"a/b"}}]);
        let draft = schedule_draft("keep", "Asia/Taipei", "", &original);
        assert_eq!(draft["original"], original);
        assert_eq!(draft["kind"], "keep");
    }
    #[test]
    fn cron_is_sent_as_written() {
        let draft = schedule_draft("cron", "Asia/Taipei", "20 14 * * 0", &Value::Null);
        assert_eq!(draft["calendarStyle"], "custom");
        assert_eq!(draft["expression"], "20 14 * * 0");
        assert_eq!(draft["zone"], "Asia/Taipei");
    }
}
