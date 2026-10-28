//! "Continue in the cloud": hand a task to the agent maker's cloud on the user's own account
//! (host `cloudOptions`, `cloudHandoff`, `setCursorKey`).

use crate::client;
use adw::prelude::*;
use serde_json::{Value, json};
use std::cell::RefCell;
use std::rc::Rc;

pub fn open(parent: &adw::ApplicationWindow, project: &str, goal: &str) {
    let win = gtk::Window::builder()
        .transient_for(parent)
        .modal(true)
        .title("Continue in the cloud")
        .default_width(420)
        .build();
    let body = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(10)
        .margin_top(18)
        .margin_bottom(18)
        .margin_start(18)
        .margin_end(18)
        .build();
    let intro = gtk::Label::new(Some(
        "Runs on the agent maker's cloud with your own account, so your PC can be off. \
         The result comes back as a pull request or a link.",
    ));
    intro.set_wrap(true);
    intro.set_xalign(0.0);
    body.append(&intro);
    let status = gtk::Label::new(Some("Checking your computer…"));
    status.set_wrap(true);
    status.set_xalign(0.0);
    body.append(&status);
    let form = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(8)
        .build();
    body.append(&form);
    win.set_child(Some(&body));
    win.present();

    let (project, goal) = (project.to_owned(), goal.to_owned());
    let (form2, status2) = (form.clone(), status.clone());
    client::call(
        "cloudOptions",
        json!({"project": project}),
        move |r| match r {
            Err(e) => status2.set_label(&e),
            Ok(o) => build_form(&form2, &status2, &o, &project, &goal),
        },
    );
}

fn build_form(form: &gtk::Box, status: &gtk::Label, o: &Value, project: &str, goal: &str) {
    if o["repo"].is_null() {
        status.set_label("This folder isn't on GitHub, so a cloud agent can't open it.");
        return;
    }
    status.set_label(if o["unpushed"] == true {
        "The cloud starts from your branch on GitHub. Push your latest changes first."
    } else {
        ""
    });
    let names: Vec<(String, String, bool)> = o["vendors"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|v| {
            let id = v["id"].as_str().unwrap_or_default().to_owned();
            let ready = v["ready"] == true;
            let name = v["name"].as_str().unwrap_or_default().to_owned();
            (id, name, ready)
        })
        .filter(|(id, _, ready)| *ready || id == "cursor")
        .collect();
    let labels: Vec<&str> = names.iter().map(|(_, n, _)| n.as_str()).collect();
    let pick = gtk::DropDown::from_strings(&labels);
    form.append(&pick);
    let env = gtk::Entry::builder()
        .placeholder_text("Codex cloud environment name")
        .build();
    if o["codexEnv"].is_null() {
        form.append(&env);
    }
    let key = gtk::PasswordEntry::builder()
        .placeholder_text("Cursor API key (only if Cursor isn't set up)")
        .build();
    form.append(&key);
    let prompt = gtk::Entry::builder()
        .text(format!("Continue this task: {goal}"))
        .build();
    form.append(&prompt);
    let start = gtk::Button::with_label("Start in the cloud");
    start.add_css_class("suggested-action");
    form.append(&start);
    let names = Rc::new(names);
    let codex_env = o["codexEnv"].as_str().map(str::to_owned);
    let project = project.to_owned();
    let status = status.clone();
    let busy = Rc::new(RefCell::new(false));
    start.connect_clicked(move |btn| {
        if *busy.borrow() {
            return;
        }
        let Some((vendor, _, ready)) = names.get(pick.selected() as usize).cloned() else {
            return;
        };
        let env_text = env.text().to_string();
        let body = json!({
            "project": project, "vendor": vendor, "prompt": prompt.text().to_string(),
            "codexEnv": if env_text.is_empty() { codex_env.clone() } else { Some(env_text) },
        });
        let key_text = key.text().to_string();
        *busy.borrow_mut() = true;
        btn.set_label("Starting…");
        let (status, btn, busy) = (status.clone(), btn.clone(), busy.clone());
        let go = move || {
            client::call("cloudHandoff", body, move |r| {
                *busy.borrow_mut() = false;
                btn.set_label("Start in the cloud");
                match r {
                    Ok(v) => {
                        let url = v["url"].as_str().unwrap_or("https://github.com").to_owned();
                        status.set_label(&format!("Started: {url}"));
                        let _ = gtk::gio::AppInfo::launch_default_for_uri(
                            &url,
                            None::<&gtk::gio::AppLaunchContext>,
                        );
                    }
                    Err(e) => status.set_label(&e),
                }
            });
        };
        if vendor == "cursor" && !ready && !key_text.is_empty() {
            client::call("setCursorKey", json!({"key": key_text.trim()}), move |_| {
                go()
            });
        } else {
            go();
        }
    });
}
