//! Memory and routines use the same host-owned state and schedule validation as SwiftUI.
use crate::{
    client,
    rows::{icon_button, label},
    ui::{self, App, toast},
};
use adw::prelude::*;
use serde_json::{Value, json};

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
    let add = icon_button("list-add-symbolic", "Ask the bot for a routine");
    let refresh = icon_button("view-refresh-symbolic", "Refresh routines");
    actions.append(&add);
    actions.append(&refresh);
    body.append(&actions);
    let list = gtk::Box::new(gtk::Orientation::Vertical, 14);
    body.append(&list);
    // New routines are set up by talking to the bot: start the ask in its chat.
    let ui2 = ui.clone();
    add.connect_clicked(move |_| {
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
                "No routines yet. Tell the bot what to run and when, and it sets one up.",
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
            let edit = icon_button("document-edit-symbolic", "Edit routine");
            let (ui2, bot2, item2, list2) = (ui.clone(), bot.clone(), item.clone(), list.clone());
            edit.connect_clicked(move |_| routine_editor(&ui2, &bot2, item2.clone(), &list2));
            row.add_suffix(&edit);
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
                    let (ui2, bot, list, id) = (ui.clone(), bot.clone(), list.clone(), item["id"].clone());
                    let run = move || routine_call(&ui2, method, json!({"botId":bot,"id":id}), &bot, &list);
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
                .tooltip_text(if item["enabled"] == true { "Pause" } else { "Resume" })
                .build();
            row.add_suffix(&on);
            let (ui, bot, id, list) = (ui.clone(), bot.clone(), item["id"].clone(), list.clone());
            on.connect_state_set(move |_, enabled| {
                routine_call(&ui, "setRoutineEnabled", json!({"botId":bot,"id":id,"enabled":enabled}), &bot, &list);
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
    let (window, body) = dialog(ui, "Edit routine");
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
    let kinds = vec![
        "keep", "daily", "weekly", "monthly", "custom", "once", "webhook",
    ];
    body.append(&label("Schedule", &["secondary"]));
    let labels: Vec<&str> = kinds
        .iter()
        .map(|k| match *k {
            "keep" => "Keep existing triggers",
            "daily" => "Daily",
            "weekly" => "Weekly",
            "monthly" => "Monthly",
            "custom" => "Custom cron",
            "once" => "Run once",
            _ => "Webhook",
        })
        .collect();
    let kind = gtk::DropDown::from_strings(&labels);
    body.append(&kind);
    let hour = entry(&body, "Hour (0–23)", "9");
    let minute = entry(&body, "Minute (0–59)", "0");
    let day = entry(
        &body,
        "Weekly: weekday (0 = Sunday); monthly: day (1–31)",
        "1",
    );
    let zone_name = gtk::glib::TimeZone::local().identifier().to_string();
    let zone = entry(&body, "Time zone", &zone_name);
    let expression = entry(
        &body,
        "Custom cron (minute hour day month weekday)",
        "0 9 * * *",
    );
    let once = entry(
        &body,
        "Run once: date and time (ISO 8601, include time zone)",
        "",
    );
    let existing = label(
        &routine["triggerDescriptions"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_str)
            .collect::<Vec<_>>()
            .join(" · "),
        &["secondary"],
    );
    existing.set_wrap(true);
    body.append(&existing);
    let update_fields = {
        let (kinds, hour, minute, day, zone, expression, once, existing) = (
            kinds.clone(),
            hour.clone(),
            minute.clone(),
            day.clone(),
            zone.clone(),
            expression.clone(),
            once.clone(),
            existing.clone(),
        );
        move |kind: &gtk::DropDown| {
            let style = kinds[kind.selected() as usize];
            for (field, visible) in [
                (&hour, matches!(style, "daily" | "weekly" | "monthly")),
                (&minute, matches!(style, "daily" | "weekly" | "monthly")),
                (&day, matches!(style, "weekly" | "monthly")),
                (
                    &zone,
                    matches!(style, "daily" | "weekly" | "monthly" | "custom"),
                ),
                (&expression, style == "custom"),
                (&once, style == "once"),
            ] {
                if let Some(group) = field.parent() {
                    group.set_visible(visible);
                }
            }
            existing.set_visible(style == "keep");
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
            hour,
            minute,
            day,
            zone,
            expression,
            once,
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
            hour.clone(),
            minute.clone(),
            day.clone(),
            zone.clone(),
            expression.clone(),
            once.clone(),
            timeout.clone(),
            status.clone(),
        );
        button.connect_clicked(move |button| {
            let style = kinds[kind.selected() as usize];
            let draft = schedule_draft(style, &hour.text(), &minute.text(), &day.text(), &zone.text(), &expression.text(), &once.text(), &routine["triggers"]);
            let draft = match draft { Ok(d) => d, Err(e) => { status.set_label(&e); return; } };
            let buffer = instruction.buffer();
            let text = buffer.text(&buffer.start_iter(), &buffer.end_iter(), false).to_string();
            let timeout = match timeout.text().parse::<u64>() { Ok(v) if (1..=86400).contains(&v) => v, _ => { status.set_label("Timeout must be between 1 and 86400 seconds."); return; } };
            if save && (name.text().trim().is_empty() || text.trim().is_empty()) { status.set_label("Enter a name and instruction."); return; }
            button.set_sensitive(false);
            let mut args = json!({"draft":draft});
            if save { args = json!({"botId":bot,"name":name.text().as_str(),"instruction":text,"schedule":draft,"timeoutSeconds":timeout}); args["id"] = routine["id"].clone(); }
            let (ui, bot, list, window, status, button) = (ui.clone(), bot.clone(), list.clone(), window.clone(), status.clone(), button.clone());
            client::call(if save { "saveRoutine" } else { "routineSchedule" }, args, move |r| {
                button.set_sensitive(true);
                match r {
                    Ok(v) if save => { let _ = v; window.close(); load_routines(&ui, &bot, &list); }
                    Ok(v) => status.set_label(&format!("{}{}{}", v["summary"].as_str().unwrap_or(""),
                        v["nextRunAt"].as_i64().map(|at| format!("\nNext: {}", schedule_time(at))).unwrap_or_default(),
                        v["warning"].as_str().map(|w| format!("\n{w}")).unwrap_or_default())),
                    Err(e) => status.set_label(&e),
                }
            });
        });
    }
}

#[allow(clippy::too_many_arguments)]
fn schedule_draft(
    style: &str,
    hour: &str,
    minute: &str,
    day: &str,
    zone: &str,
    expression: &str,
    once: &str,
    original: &Value,
) -> Result<Value, String> {
    let mut draft = json!({"kind": if matches!(style, "keep" | "once" | "webhook") { style } else { "cron" }, "zone":zone,"original":original.as_array().cloned().unwrap_or_default()});
    if draft["kind"] == "cron" {
        draft["calendarStyle"] = style.into();
        draft["expression"] = expression.into();
        if style != "custom" {
            draft["hour"] = hour.parse::<u8>().map_err(|_| "Enter a valid hour")?.into();
            draft["minute"] = minute
                .parse::<u8>()
                .map_err(|_| "Enter a valid minute")?
                .into();
            if style == "weekly" {
                draft["weekday"] = day
                    .parse::<u8>()
                    .map_err(|_| "Enter a weekday from 0 to 6")?
                    .into();
            }
            if style == "monthly" {
                draft["monthDay"] = day
                    .parse::<u8>()
                    .map_err(|_| "Enter a day from 1 to 31")?
                    .into();
            }
        }
    } else if style == "once" {
        let date = gtk::glib::DateTime::from_iso8601(once, None)
            .map_err(|_| "Enter an ISO 8601 date with a time zone")?;
        draft["at"] = (date.to_unix() * 1000).into();
    }
    Ok(draft)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn edits_preserve_existing_event_triggers() {
        let original = json!([{"type":"event","source":"github","event":"pull_request.opened","filters":{"repo":"a/b"}}]);
        let draft = schedule_draft("keep", "", "", "", "Asia/Taipei", "", "", &original).unwrap();
        assert_eq!(draft["original"], original);
        assert_eq!(draft["kind"], "keep");
    }
    #[test]
    fn once_requires_a_date_and_weekly_carries_the_selected_zone() {
        assert!(schedule_draft("once", "", "", "", "UTC", "", "bad", &Value::Null).is_err());
        let draft = schedule_draft(
            "weekly",
            "14",
            "20",
            "0",
            "Asia/Taipei",
            "",
            "",
            &Value::Null,
        )
        .unwrap();
        assert_eq!(draft["weekday"], 0);
        assert_eq!(draft["zone"], "Asia/Taipei");
    }
}
