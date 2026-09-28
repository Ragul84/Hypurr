//! Native connection cards use the same secure host workflow as iOS and macOS.
use crate::{
    client,
    rows::label,
    ui::{App, toast},
};
use adw::prelude::*;
use serde_json::{Value, json};
use std::{cell::RefCell, rc::Rc};

pub fn card(ui: &App, entry: &Value) -> gtk::Widget {
    let request = &entry["data"]["connectionRequest"];
    let body = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(10)
        .css_classes(["perm-card"])
        .build();
    body.append(&label(
        request["title"].as_str().unwrap_or("Connect service"),
        &["headline"],
    ));
    let reason = label(entry["data"]["text"].as_str().unwrap_or(""), &["secondary"]);
    reason.set_wrap(true);
    body.append(&reason);
    if request["status"] != "pending" {
        body.append(&label(
            if request["status"] == "ready" {
                "Connected"
            } else {
                "Cancelled"
            },
            &["secondary"],
        ));
        return body.upcast();
    }
    let input = gtk::PasswordEntry::builder()
        .show_peek_icon(true)
        .placeholder_text("Credential or op:// reference")
        .build();
    let username = gtk::Entry::builder()
        .placeholder_text("Username or email")
        .build();
    let login = request["kind"] == "login";
    let secret = login || request["kind"] == "secret";
    if login {
        input.set_placeholder_text(Some("Password or op:// reference"));
        body.append(&username);
        body.append(&input);
        body.append(&label(
            "Saved securely on this computer. The bot types it into the sign-in page but never sees it.",
            &["small", "secondary"],
        ));
    } else if secret {
        body.append(&label(
            request["field"].as_str().unwrap_or("Credential"),
            &["secondary"],
        ));
        body.append(&input);
        body.append(&label(
            "Saved securely; never included in the conversation.",
            &["small", "secondary"],
        ));
    }
    let actions = gtk::Box::builder().spacing(8).build();
    let connect = gtk::Button::with_label(if login {
        "Save login"
    } else if secret {
        "Save and connect"
    } else {
        "Connect"
    });
    let cancel = gtk::Button::with_label("Cancel");
    actions.append(&connect);
    actions.append(&cancel);
    body.append(&actions);
    let (ui2, e) = (ui.clone(), entry.clone());
    connect.connect_clicked(move |button| {
        let r = &e["data"]["connectionRequest"];
        if secret {
            let value = input.text().to_string();
            if value.is_empty() {
                return;
            }
            button.set_sensitive(false);
            let (ui3, button, input) = (ui2.clone(), button.clone(), input.clone());
            client::call(
                "connectorRequestFinish",
                json!({"entryId":e["id"],"value":value,"username":username.text().as_str()}),
                move |result| {
                    button.set_sensitive(true);
                    match result {
                        Ok(_) => input.set_text(""),
                        Err(error) => toast(&ui3, &error),
                    }
                },
            );
        } else if r["kind"] == "app" {
            hosted_app(
                &ui2,
                e["id"].clone(),
                r["toolkit"].as_str().unwrap_or("").to_owned(),
            );
        } else if let Some(id) = r["connectorId"].as_str() {
            authorize(&ui2, e["id"].clone(), id.to_owned());
        } else {
            let (ui3, e) = (ui2.clone(), e.clone());
            client::call(
                "connectorInfo",
                json!({"registryName":r["registryName"]}),
                move |result| match result {
                    Ok(item) => install(&ui3, &item, e["id"].clone()),
                    Err(error) => toast(&ui3, &error),
                },
            );
        }
    });
    let (ui2, id) = (ui.clone(), entry["id"].clone());
    cancel.connect_clicked(move |button| {
        button.set_sensitive(false);
        let (ui3, button) = (ui2.clone(), button.clone());
        client::call(
            "connectorRequestFinish",
            json!({"entryId":id,"cancel":true}),
            move |r| {
                button.set_sensitive(true);
                if let Err(e) = r {
                    toast(&ui3, &e);
                }
            },
        );
    });
    body.upcast()
}

fn dialog(ui: &App, title: &str) -> (adw::Dialog, gtk::Box) {
    let body = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(12)
        .margin_top(20)
        .margin_bottom(20)
        .margin_start(20)
        .margin_end(20)
        .build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    let scroll = gtk::ScrolledWindow::builder().child(&body).build();
    view.set_content(Some(&scroll));
    let dialog = adw::Dialog::builder()
        .title(title)
        .content_width(480)
        .content_height(440)
        .child(&view)
        .build();
    dialog.present(Some(&ui.window));
    (dialog, body)
}

fn install(ui: &App, item: &Value, entry: Value) {
    if let Some(options) = item["options"].as_array()
        && options.len() == 1
        && options[0]["inputs"].as_array().is_some_and(Vec::is_empty)
    {
        let ui = ui.clone();
        client::call(
            "installConnector",
            json!({"registryName":item["name"],"option":options[0]["id"],"inputs":{}}),
            move |r| match r {
                Ok(v) => added(&ui, entry, &v["connector"]),
                Err(e) => toast(&ui, &e),
            },
        );
        return;
    }

    let (window, body) = dialog(ui, item["title"].as_str().unwrap_or("Connect"));
    let options = item["options"].as_array().cloned().unwrap_or_default();
    for option in options {
        let section = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(8)
            .build();
        section.append(&label(
            option["label"].as_str().unwrap_or("Setup"),
            &["headline"],
        ));
        let mut inputs: Vec<(String, gtk::Entry)> = Vec::new();
        for field in option["inputs"].as_array().into_iter().flatten() {
            let input = gtk::Entry::builder()
                .placeholder_text(field["name"].as_str().unwrap_or("Value"))
                .visibility(field["secret"] != true)
                .text(field["default"].as_str().unwrap_or(""))
                .build();
            section.append(&input);
            inputs.push((field["name"].as_str().unwrap_or("").to_owned(), input));
        }
        let add = gtk::Button::with_label("Add");
        section.append(&add);
        body.append(&section);
        let (ui, item, entry, window) = (ui.clone(), item.clone(), entry.clone(), window.clone());
        add.connect_clicked(move |button| {
            button.set_sensitive(false);
            let values: serde_json::Map<String, Value> = inputs
                .iter()
                .map(|(k, e)| (k.clone(), e.text().to_string().into()))
                .collect();
            let (ui, entry, window, button, inputs) = (
                ui.clone(),
                entry.clone(),
                window.clone(),
                button.clone(),
                inputs.clone(),
            );
            client::call(
                "installConnector",
                json!({"registryName":item["name"],"option":option["id"],"inputs":values}),
                move |r| {
                    button.set_sensitive(true);
                    match r {
                        Ok(v) => {
                            for (_, input) in inputs {
                                input.set_text("");
                            }
                            window.close();
                            added(&ui, entry, &v["connector"]);
                        }
                        Err(e) => toast(&ui, &e),
                    }
                },
            );
        });
    }
}

fn added(ui: &App, entry: Value, connector: &Value) {
    let id = connector["id"].as_str().unwrap_or("").to_owned();
    if connector["auth"] == "signedOut" {
        authorize(ui, entry, id);
        return;
    }
    let ui = ui.clone();
    let method = if entry.is_null() {
        "connectorVerify"
    } else {
        "connectorRequestFinish"
    };
    client::call(
        method,
        json!({"id":id,"connectorId":id,"entryId":entry}),
        move |r| match r {
            Ok(_) => toast(&ui, "Connected"),
            Err(e) => toast(&ui, &e),
        },
    );
}

fn authorize(ui: &App, entry: Value, id: String) {
    let (window, body) = dialog(ui, "Connect service");
    body.append(&label(
        "Sign in if needed, then check the connection.",
        &["secondary"],
    ));
    let login = gtk::Button::with_label("Sign in");
    body.append(&login);
    let check = gtk::Button::with_label("Check connection");
    body.append(&check);
    let (ui2, id2) = (ui.clone(), id.clone());
    login.connect_clicked(move |button| {
        button.set_sensitive(false);
        let (ui, button) = (ui2.clone(), button.clone());
        client::call("connectorSignIn", json!({"id":id2}), move |r| {
            button.set_sensitive(true);
            match r {
                Ok(v) => {
                    if let Some(url) = v["url"].as_str() {
                        let _ = gtk::gio::AppInfo::launch_default_for_uri(
                            url,
                            None::<&gtk::gio::AppLaunchContext>,
                        );
                    }
                }
                Err(e) => toast(&ui, &e),
            }
        });
    });
    let ui = ui.clone();
    check.connect_clicked(move |button| {
        button.set_sensitive(false);
        let (ui, window, button) = (ui.clone(), window.clone(), button.clone());
        let method = if entry.is_null() {
            "connectorVerify"
        } else {
            "connectorRequestFinish"
        };
        client::call(
            method,
            json!({"entryId":entry,"connectorId":id,"id":id}),
            move |r| {
                button.set_sensitive(true);
                match r {
                    Ok(_) => {
                        window.close();
                    }
                    Err(e) => toast(&ui, &e),
                }
            },
        );
    });
}

pub fn credentials(ui: &App) {
    let (_window, body) = dialog(ui, "Credentials");
    let status = label("Linux Secret Service · encrypted storage", &["headline"]);
    body.append(&status);
    let help = label(
        "1Password: install the op CLI and use a service account restricted to a dedicated shared vault. Use op://vault/item/field in connector credential fields.",
        &["secondary"],
    );
    help.set_wrap(true);
    body.append(&help);
    let composio = gtk::PasswordEntry::builder()
        .placeholder_text("Composio API key (for hosted apps)")
        .show_peek_icon(true)
        .build();
    body.append(&composio);
    let setup = gtk::Button::with_label("Set up hosted apps");
    body.append(&setup);
    let ui2 = ui.clone();
    setup.connect_clicked(move |button| {
        button.set_sensitive(false);
        let (ui, composio, button) = (ui2.clone(), composio.clone(), button.clone());
        client::call(
            "setComposioKey",
            json!({"key":composio.text().as_str()}),
            move |r| {
                button.set_sensitive(true);
                match r {
                    Ok(_) => {
                        composio.set_text("");
                        toast(&ui, "Hosted apps configured");
                    }
                    Err(e) => toast(&ui, &e),
                }
            },
        );
    });
    let saved = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(8)
        .build();
    body.append(&saved);
    let ui2 = ui.clone();
    client::call("connectors", json!({}), move |r| {
        if let Ok(v) = r {
            for connector in v["items"].as_array().into_iter().flatten() {
                if connector["keys"].as_array().is_none_or(Vec::is_empty) {
                    continue;
                }
                let button = gtk::Button::with_label(
                    connector["name"].as_str().unwrap_or("Saved connection"),
                );
                saved.append(&button);
                let (ui, connector) = (ui2.clone(), connector.clone());
                button.connect_clicked(move |_| edit_credentials(&ui, &connector));
            }
        }
    });
    body.append(&label("Sign-ins", &["headline"]));
    let logins = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(8)
        .build();
    body.append(&logins);
    let ui2 = ui.clone();
    client::call("credentialLogins", json!({}), move |r| {
        let Ok(v) = r else { return };
        for login in v["items"].as_array().into_iter().flatten() {
            let row = gtk::Box::builder().spacing(8).build();
            let text = format!(
                "{} · {}",
                login["site"].as_str().unwrap_or(""),
                login["username"].as_str().unwrap_or("")
            );
            let name = label(&text, &[]);
            name.set_hexpand(true);
            row.append(&name);
            let remove = gtk::Button::from_icon_name("user-trash-symbolic");
            remove.set_tooltip_text(Some("Remove"));
            row.append(&remove);
            logins.append(&row);
            let (ui, row, logins, id) = (
                ui2.clone(),
                row.clone(),
                logins.clone(),
                login["id"].clone(),
            );
            remove.connect_clicked(move |_| {
                let (ui, row, logins) = (ui.clone(), row.clone(), logins.clone());
                client::call(
                    "credentialRemoveLogin",
                    json!({"id":id}),
                    move |r| match r {
                        Ok(_) => logins.remove(&row),
                        Err(e) => toast(&ui, &e),
                    },
                );
            });
        }
    });
    let input = gtk::PasswordEntry::builder()
        .placeholder_text("1Password service account token")
        .show_peek_icon(true)
        .build();
    body.append(&input);
    let connect = gtk::Button::with_label("Connect 1Password");
    body.append(&connect);
    let disconnect = gtk::Button::with_label("Disconnect 1Password");
    body.append(&disconnect);
    let busy = Rc::new(RefCell::new(false));
    for (button, remove) in [(connect, false), (disconnect, true)] {
        let (ui, input, status, busy) = (ui.clone(), input.clone(), status.clone(), busy.clone());
        button.connect_clicked(move |_| {
            if *busy.borrow() {
                return;
            }
            *busy.borrow_mut() = true;
            let token = if remove {
                String::new()
            } else {
                input.text().to_string()
            };
            let (ui, input, status, busy) =
                (ui.clone(), input.clone(), status.clone(), busy.clone());
            client::call(
                "credentialSetOnePassword",
                json!({"token":token}),
                move |r| {
                    *busy.borrow_mut() = false;
                    match r {
                        Ok(v) => {
                            input.set_text("");
                            status.set_label(if v["onePasswordConnected"] == true {
                                "1Password connected"
                            } else {
                                "1Password disconnected"
                            });
                        }
                        Err(e) => toast(&ui, &e),
                    }
                },
            );
        });
    }
    client::call("credentialStatus", json!({}), move |r| match r {
        Ok(v) => status.set_label(if v["onePasswordConnected"] == true {
            "Secret Service · 1Password connected"
        } else {
            "Secret Service"
        }),
        Err(e) => status.set_label(&e),
    });
}

/// Registry browsing is explicit and paged; Add uses the same secure setup form.
pub fn marketplace(ui: &App) {
    let (_window, body) = dialog(ui, "Connectors");
    let search = gtk::SearchEntry::new();
    body.append(&search);
    let results = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(12)
        .build();
    body.append(&results);
    let load = gtk::Button::with_label("Search / load connectors");
    body.append(&load);
    let more = gtk::Button::with_label("Load more");
    more.set_visible(false);
    body.append(&more);
    let cursor = Rc::new(RefCell::new(Value::Null));
    let query = Rc::new(RefCell::new(String::new()));
    let pending = Rc::new(RefCell::new(Vec::<Value>::new()));
    let loading = Rc::new(RefCell::new(false));
    for (button, next) in [(load, false), (more.clone(), true)] {
        let (ui, search, results, more, cursor, query, pending, loading) = (
            ui.clone(),
            search.clone(),
            results.clone(),
            more.clone(),
            cursor.clone(),
            query.clone(),
            pending.clone(),
            loading.clone(),
        );
        button.connect_clicked(move |_| {
            if *loading.borrow() {
                return;
            }
            if !next {
                *query.borrow_mut() = search.text().to_string();
                *cursor.borrow_mut() = Value::Null;
                pending.borrow_mut().clear();
                while let Some(child) = results.first_child() {
                    results.remove(&child);
                }
            } else if !pending.borrow().is_empty() {
                reveal(&ui, &results, &pending);
                more.set_visible(!pending.borrow().is_empty() || cursor.borrow().is_string());
                return;
            }
            *loading.borrow_mut() = true;
            let (ui, results, more, cursor, pending, loading) = (
                ui.clone(),
                results.clone(),
                more.clone(),
                cursor.clone(),
                pending.clone(),
                loading.clone(),
            );
            client::call(
                "marketConnectors",
                json!({"search":*query.borrow(),"cursor":*cursor.borrow()}),
                move |r| {
                    *loading.borrow_mut() = false;
                    match r {
                        Ok(v) => {
                            *cursor.borrow_mut() = v["nextCursor"].clone();
                            *pending.borrow_mut() =
                                v["items"].as_array().cloned().unwrap_or_default();
                            reveal(&ui, &results, &pending);
                            more.set_visible(
                                !pending.borrow().is_empty() || cursor.borrow().is_string(),
                            );
                        }
                        Err(e) => toast(&ui, &e),
                    }
                },
            );
        });
    }
}

fn reveal(ui: &App, results: &gtk::Box, pending: &Rc<RefCell<Vec<Value>>>) {
    let count = pending.borrow().len().min(12);
    let page: Vec<_> = pending.borrow_mut().drain(..count).collect();
    for item in page {
        let row = gtk::Box::builder().spacing(8).build();
        let name = label(item["title"].as_str().unwrap_or("Connector"), &["headline"]);
        name.set_hexpand(true);
        row.append(&name);
        let add = gtk::Button::with_label(if item["installed"] == true {
            "Added"
        } else {
            "Add"
        });
        add.set_sensitive(item["installed"] != true);
        row.append(&add);
        results.append(&row);
        let ui = ui.clone();
        add.connect_clicked(move |_| install(&ui, &item, Value::Null));
    }
}

fn hosted_app(ui: &App, entry: Value, toolkit: String) {
    let (window, body) = dialog(ui, "Connect app");
    let ui = ui.clone();
    client::call("composioConnect", json!({"toolkit":toolkit}), move |r| {
        let plan = match r {
            Ok(v) => v,
            Err(e) => {
                toast(&ui, &e);
                return;
            }
        };
        if let Some(url) = plan["url"].as_str() {
            let _ =
                gtk::gio::AppInfo::launch_default_for_uri(url, None::<&gtk::gio::AppLaunchContext>);
        }
        let mut fields = Vec::new();
        for field in plan["fields"].as_array().into_iter().flatten() {
            let input = gtk::Entry::builder()
                .placeholder_text(field["label"].as_str().unwrap_or("Value"))
                .visibility(field["secret"] != true)
                .build();
            body.append(&input);
            fields.push((field["name"].as_str().unwrap_or("").to_owned(), input));
        }
        let connect = gtk::Button::with_label(if fields.is_empty() {
            "Check connection"
        } else {
            "Connect"
        });
        body.append(&connect);
        connect.connect_clicked(move |button| {
            button.set_sensitive(false);
            let (method, args) = if plan["status"] == "needsFields" {
                let values: serde_json::Map<String, Value> = fields
                    .iter()
                    .map(|(k, v)| (k.clone(), v.text().to_string().into()))
                    .collect();
                (
                    "composioConnectFields",
                    json!({"toolkit":toolkit,"mode":plan["mode"],"fields":values}),
                )
            } else {
                ("composioConnection", json!({"id":plan["connection"]}))
            };
            let (ui, entry, toolkit, window, button, fields) = (
                ui.clone(),
                entry.clone(),
                toolkit.clone(),
                window.clone(),
                button.clone(),
                fields.clone(),
            );
            client::call(method, args, move |r| {
                button.set_sensitive(true);
                match r {
                    Ok(v) if v["status"] == "active" => {
                        for (_, input) in fields {
                            input.set_text("");
                        }
                        client::call(
                            "connectorRequestFinish",
                            json!({"entryId":entry,"connectorId":format!("composio-{toolkit}")}),
                            move |r| match r {
                                Ok(_) => {
                                    window.close();
                                }
                                Err(e) => toast(&ui, &e),
                            },
                        );
                    }
                    Ok(_) => toast(&ui, "Finish signing in, then check again."),
                    Err(e) => toast(&ui, &e),
                }
            });
        });
    });
}

fn edit_credentials(ui: &App, connector: &Value) {
    let (window, body) = dialog(ui, connector["name"].as_str().unwrap_or("Credentials"));
    body.append(&label(
        "Enter only values to replace, or an op:// reference.",
        &["secondary"],
    ));
    let mut fields = Vec::new();
    for key in connector["keys"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
    {
        let input = gtk::PasswordEntry::builder()
            .placeholder_text(key)
            .show_peek_icon(true)
            .build();
        body.append(&input);
        fields.push((key.to_owned(), input));
    }
    let save = gtk::Button::with_label("Save credentials");
    body.append(&save);
    let (ui, id) = (ui.clone(), connector["id"].clone());
    save.connect_clicked(move |button| {
        let values: serde_json::Map<String, Value> = fields
            .iter()
            .filter(|(_, v)| !v.text().is_empty())
            .map(|(k, v)| (k.clone(), v.text().to_string().into()))
            .collect();
        if values.is_empty() {
            return;
        }
        button.set_sensitive(false);
        let (ui, window, button, fields) =
            (ui.clone(), window.clone(), button.clone(), fields.clone());
        client::call(
            "credentialUpdateConnector",
            json!({"id":id,"fields":values}),
            move |r| {
                button.set_sensitive(true);
                match r {
                    Ok(_) => {
                        for (_, input) in fields {
                            input.set_text("");
                        }
                        window.close();
                    }
                    Err(e) => toast(&ui, &e),
                }
            },
        );
    });
}
