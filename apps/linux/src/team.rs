//! Work account and the company team pool (host `work*` methods). Sign-in opens the company's
//! Google or Microsoft page in the browser; the host catches the redirect on 127.0.0.1.
use crate::{
    client,
    manage::dialog,
    rows::label,
    ui::{App, toast},
};
use adw::prelude::*;
use serde_json::{Value, json};

/// Gateway credits are US cents.
pub fn dollars(cents: Option<i64>) -> String {
    cents.map_or_else(|| "–".to_owned(), |c| format!("${:.2}", c as f64 / 100.0))
}

/// "$12.50" / "12.5" → cents; empty or negative → None.
pub fn parse_dollars(s: &str) -> Option<i64> {
    let v: f64 = s.trim().trim_start_matches('$').parse().ok()?;
    (v >= 0.0).then(|| (v * 100.0).round() as i64)
}

fn clear(body: &gtk::Box) {
    while let Some(c) = body.first_child() {
        body.remove(&c);
    }
}

fn entry(body: &gtk::Box, placeholder: &str) -> gtk::Entry {
    let e = gtk::Entry::builder().placeholder_text(placeholder).build();
    body.append(&e);
    e
}

fn wrapped(text: &str, classes: &[&str]) -> gtk::Label {
    let l = label(text, classes);
    l.set_wrap(true);
    l
}

pub fn open(ui: &App) {
    let (_, body) = dialog(ui, "Work account");
    load(ui, &body);
}

fn load(ui: &App, body: &gtk::Box) {
    clear(body);
    body.append(&label("Loading…", &["secondary"]));
    let (ui, body) = (ui.clone(), body.clone());
    client::call("gatewayBalance", json!({}), move |r| {
        let balance = r.unwrap_or(Value::Null);
        if balance["workEmail"].as_str().is_some_and(|e| !e.is_empty()) {
            let (ui2, body2) = (ui.clone(), body.clone());
            client::call("workTeam", json!({}), move |t| match t {
                Ok(team) => signed_in(&ui2, &body2, &balance, &team),
                Err(e) => {
                    clear(&body2);
                    body2.append(&wrapped(&e, &["danger-text"]));
                }
            });
        } else {
            signed_out(&ui, &body);
        }
    });
}

fn signed_out(ui: &App, body: &gtk::Box) {
    clear(body);
    body.append(&label("Sign in with work account", &["headline"]));
    body.append(&wrapped(
        "Use your company's Google or Microsoft account. Hypurr is then paid from your team's credits.",
        &["secondary"],
    ));
    let email = entry(body, "you@company.com");
    let setup = gtk::CheckButton::with_label("Set up Hypurr for my company (IT)");
    body.append(&setup);
    let extra = gtk::Box::new(gtk::Orientation::Vertical, 8);
    extra.set_visible(false);
    let name = entry(&extra, "Team name");
    let microsoft = gtk::CheckButton::with_label("Microsoft (otherwise Google)");
    extra.append(&microsoft);
    let client_id = entry(&extra, "OAuth client id");
    let tenant = entry(&extra, "Tenant id (Microsoft)");
    extra.append(&wrapped(
        "The client id comes from your company's Google or Microsoft admin console. You become the team's admin.",
        &["secondary"],
    ));
    body.append(&extra);
    let extra2 = extra.clone();
    setup.connect_toggled(move |c| extra2.set_visible(c.is_active()));
    let status = wrapped("", &["secondary"]);
    let go = gtk::Button::with_label("Continue");
    go.add_css_class("suggested-action");
    body.append(&go);
    body.append(&status);
    let (ui, body) = (ui.clone(), body.clone());
    go.connect_clicked(move |button| {
        let mut req = json!({"email": email.text().trim()});
        if setup.is_active() {
            req["setup"] = json!({
                "name": name.text().trim(),
                "provider": if microsoft.is_active() { "microsoft" } else { "google" },
                "clientId": client_id.text().trim(),
                "tenant": microsoft.is_active().then(|| tenant.text().trim().to_owned()),
            });
        }
        button.set_sensitive(false);
        status.set_text("Opening your company's sign-in…");
        let (ui, body, status, button) = (ui.clone(), body.clone(), status.clone(), button.clone());
        client::call("workDesktopStart", req, move |r| match r {
            Ok(v) => {
                let url = v["url"].as_str().unwrap_or_default().to_owned();
                let _ = gtk::gio::AppInfo::launch_default_for_uri(
                    &url,
                    None::<&gtk::gio::AppLaunchContext>,
                );
                status.set_text("Finish signing in in your browser…");
                poll(
                    &ui,
                    &body,
                    &status,
                    &button,
                    v["attempt"].as_str().unwrap_or_default().to_owned(),
                    600,
                );
            }
            Err(e) => {
                status.set_text(&e);
                button.set_sensitive(true);
            }
        });
    });
}

fn poll(
    ui: &App,
    body: &gtk::Box,
    status: &gtk::Label,
    button: &gtk::Button,
    attempt: String,
    left: u32,
) {
    let (ui, body, status, button) = (ui.clone(), body.clone(), status.clone(), button.clone());
    gtk::glib::timeout_add_seconds_local_once(1, move || {
        let a2 = attempt.clone();
        client::call(
            "workDesktopResult",
            json!({"attempt": attempt}),
            move |r| match r {
                Ok(v) if v["done"] == true => load(&ui, &body),
                Ok(_) if left > 0 => poll(&ui, &body, &status, &button, a2, left - 1),
                Ok(_) => {
                    status.set_text("Sign-in didn't finish within 10 minutes.");
                    button.set_sensitive(true);
                }
                Err(e) => {
                    status.set_text(&e);
                    button.set_sensitive(true);
                }
            },
        );
    });
}

fn act(ui: &App, body: &gtk::Box, method: &str, args: Value) {
    let (ui, body) = (ui.clone(), body.clone());
    client::call(method, args, move |r| {
        if let Err(e) = r {
            toast(&ui, &e);
        }
        load(&ui, &body);
    });
}

fn signed_in(ui: &App, body: &gtk::Box, balance: &Value, team: &Value) {
    clear(body);
    body.append(&label(
        team["team"]["name"].as_str().unwrap_or("Your team"),
        &["headline"],
    ));
    let limit = team["monthlyLimitCents"].as_i64();
    body.append(&wrapped(
        &format!(
            "Signed in as {}. You used {} this month{}.",
            balance["workEmail"].as_str().unwrap_or_default(),
            dollars(team["spentThisMonthCents"].as_i64().or(Some(0))),
            limit
                .map(|l| format!(" of {}", dollars(Some(l))))
                .unwrap_or_default()
        ),
        &["secondary"],
    ));
    if team["role"] == "admin" {
        body.append(&label("Team credits", &["secondary"]));
        body.append(&label(&dollars(team["poolCents"].as_i64()), &["title-2"]));
        let amount = entry(body, "Add from your credits, in $");
        let add = gtk::Button::with_label("Add to team pool");
        body.append(&add);
        let (ui2, body2) = (ui.clone(), body.clone());
        add.connect_clicked(move |_| {
            if let Some(c) = parse_dollars(&amount.text()).filter(|c| *c > 0) {
                act(&ui2, &body2, "workTeamPool", json!({"amountCents": c}));
            }
        });
        body.append(&label("People", &["heading"]));
        for m in team["members"].as_array().into_iter().flatten() {
            let id = m["userId"].as_str().unwrap_or_default().to_owned();
            let paused = m["status"] == "suspended";
            let admin = m["role"] == "admin";
            let card = gtk::Box::new(gtk::Orientation::Vertical, 6);
            card.add_css_class("card");
            card.set_margin_bottom(4);
            let inner = gtk::Box::new(gtk::Orientation::Vertical, 6);
            inner.set_margin_top(12);
            inner.set_margin_bottom(12);
            inner.set_margin_start(12);
            inner.set_margin_end(12);
            card.append(&inner);
            inner.append(&label(m["email"].as_str().unwrap_or(&id), &["heading"]));
            let lim = m["monthlyLimitCents"].as_i64().map_or_else(
                || "no limit".to_owned(),
                |l| format!("limit {}", dollars(Some(l))),
            );
            let tag = if paused {
                "Paused"
            } else if admin {
                "Admin"
            } else {
                "Member"
            };
            inner.append(&label(
                &format!(
                    "{tag} · {} this month · {lim}",
                    dollars(m["spentThisMonthCents"].as_i64().or(Some(0)))
                ),
                &["secondary"],
            ));
            if !admin {
                let row = gtk::Box::new(gtk::Orientation::Horizontal, 6);
                let limit_entry = gtk::Entry::builder()
                    .placeholder_text("Monthly limit $ (empty = none)")
                    .hexpand(true)
                    .build();
                let save = gtk::Button::with_label("Set limit");
                let pause = gtk::Button::with_label(if paused { "Resume" } else { "Pause" });
                let make = gtk::Button::with_label("Make admin");
                row.append(&limit_entry);
                row.append(&save);
                row.append(&pause);
                row.append(&make);
                inner.append(&row);
                let (u, b, i) = (ui.clone(), body.clone(), id.clone());
                save.connect_clicked(move |_| {
                    let v = parse_dollars(&limit_entry.text()).map_or(Value::Null, |c| json!(c));
                    act(
                        &u,
                        &b,
                        "workTeamMember",
                        json!({"userId": i, "monthlyLimitCents": v}),
                    );
                });
                let (u, b, i) = (ui.clone(), body.clone(), id.clone());
                pause.connect_clicked(move |_| {
                    act(
                        &u,
                        &b,
                        "workTeamMember",
                        json!({"userId": i, "status": if paused { "active" } else { "suspended" }}),
                    );
                });
                let (u, b, i) = (ui.clone(), body.clone(), id.clone());
                make.connect_clicked(move |_| {
                    act(
                        &u,
                        &b,
                        "workTeamMember",
                        json!({"userId": i, "role": "admin"}),
                    )
                });
            }
            body.append(&card);
        }
    }
    let out = gtk::Button::with_label("Sign out of work account");
    body.append(&out);
    let (ui, body2) = (ui.clone(), body.clone());
    out.connect_clicked(move |_| act(&ui, &body2, "workSignOut", json!({})));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn money() {
        assert_eq!(dollars(Some(1234)), "$12.34");
        assert_eq!(parse_dollars("$12.5"), Some(1250));
        assert_eq!(parse_dollars("x"), None);
    }
}
