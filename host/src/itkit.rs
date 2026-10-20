//! The IT approval kit (`docs/it-kit/`), served to the app's "Share with IT" screen with
//! this computer's live values: port, whether the cloud is on, approved endpoints.

use crate::hub::Hub;
use serde_json::{Value, json};

/// `(id, title, markdown)` for each page, in reading order.
pub const PAGES: &[(&str, &str, &str)] = &[
    ("data-flow", "Data flow", include_str!("../../docs/it-kit/data-flow.md")),
    ("security-faq", "Security FAQ", include_str!("../../docs/it-kit/security-faq.md")),
    ("allowlist", "Allowlist", include_str!("../../docs/it-kit/allowlist.md")),
    ("signed-installer", "Signed installer", include_str!("../../docs/it-kit/signed-installer.md")),
    ("local-only", "Local-only mode", include_str!("../../docs/it-kit/local-only.md")),
    ("audit-export", "Audit export", include_str!("../../docs/it-kit/audit-export.md")),
];

/// Live facts about this host, as plain lines for the top of a share.
pub fn facts(port: u16, cloud: Option<&str>, local_only: &Value) -> Vec<String> {
    let mut out = vec![
        format!("Hypurr host {}", env!("CARGO_PKG_VERSION")),
        format!("Listens on TCP {port} for paired phones on the LAN"),
        match cloud {
            Some(u) => format!("Cloud relay on: {u}"),
            None => "Cloud relay off: LAN pairing only".to_owned(),
        },
    ];
    if local_only["on"].as_bool() == Some(true) {
        let eps: Vec<&str> = local_only["modelEndpoints"]
            .as_array()
            .map(|a| a.iter().filter_map(Value::as_str).collect())
            .unwrap_or_default();
        out.push("Local-only mode: on".to_owned());
        out.push(if eps.is_empty() {
            "Approved model endpoints: none yet (agents will not start)".to_owned()
        } else {
            format!("Approved model endpoints: {}", eps.join(", "))
        });
    } else {
        out.push("Local-only mode: off".to_owned());
    }
    out
}

pub fn kit(hub: &Hub) -> Value {
    let lo = crate::admin::local_only::status(&hub.store);
    let cloud = crate::remote::cloud::url(&hub.store);
    let facts = facts(hub.port, cloud.as_deref(), &lo);
    let pages: Vec<Value> =
        PAGES.iter().map(|(id, title, md)| json!({"id": id, "title": title, "markdown": md})).collect();
    let mut text = format!("Hypurr — IT approval kit\n\nThis computer:\n- {}\n", facts.join("\n- "));
    for (_, _, md) in PAGES {
        text.push_str("\n\n");
        text.push_str(md);
    }
    json!({"facts": facts, "pages": pages, "localOnly": lo, "port": hub.port, "cloud": cloud, "text": text})
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pages_are_bundled() {
        assert_eq!(PAGES.len(), 6);
        assert!(PAGES.iter().all(|(_, _, md)| md.starts_with("# ")));
        assert!(PAGES.iter().all(|(_, _, md)| !md.to_lowercase().contains("opencode")));
    }

    #[test]
    fn facts_show_live_values() {
        let f = facts(19222, None, &json!({"on": true, "modelEndpoints": ["https://llm.corp.in"]}));
        assert!(f.iter().any(|l| l.contains("19222")));
        assert!(f.iter().any(|l| l.contains("LAN pairing only")));
        assert!(f.iter().any(|l| l.contains("https://llm.corp.in")));
        let f = facts(19222, Some("https://api.hypurr.dev"), &json!({"on": false}));
        assert!(f.iter().any(|l| l == "Local-only mode: off"));
    }
}
