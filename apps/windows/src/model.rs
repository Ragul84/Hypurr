//! What the window shows, as plain data. Pure: no I/O, so it's unit-tested on every OS.

use serde_json::Value;

/// Refresh the pairing code this long before it expires.
pub const REFRESH_MARGIN_MS: i64 = 60_000;

#[derive(Clone, Debug, Default, PartialEq)]
pub enum Agent {
    #[default]
    Unknown,
    None,
    /// Names of installed agents, Hypurr Agent first.
    Installed(Vec<String>),
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub enum Network {
    #[default]
    Unknown,
    Private,
    /// Windows treats this network as Public: the firewall blocks the phone.
    Public(String),
}

#[derive(Clone, Debug, Default)]
pub struct Snapshot {
    pub running: bool,
    pub starting: bool,
    pub error: Option<String>,
    pub computer: String,
    pub pairing_url: Option<String>,
    pub expires_at_ms: i64,
    pub paired_phones: usize,
    pub connected_phones: usize,
    pub agent: Agent,
    pub network: Network,
    /// Remote screen is switched on (phones may view and control this PC).
    pub screen_enabled: bool,
    /// The screen helper is connected, so Remote screen actually works.
    pub screen_ready: bool,
}

impl Snapshot {
    /// First run (no phone yet) or the user asked to pair another one.
    pub fn wants_qr(&self) -> bool {
        self.paired_phones == 0
    }

    pub fn needs_new_code(&self, now_ms: i64) -> bool {
        self.running
            && (self.pairing_url.is_none() || self.expires_at_ms - now_ms <= REFRESH_MARGIN_MS)
    }
}

/// One status row: label, plain-words value, and whether it needs the user (the only red).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Row {
    pub label: &'static str,
    pub value: String,
    pub needs_you: bool,
}

pub fn rows(s: &Snapshot) -> Vec<Row> {
    let hypurr = if s.running {
        Row {
            label: "Hypurr",
            value: "Running".into(),
            needs_you: false,
        }
    } else if s.starting {
        Row {
            label: "Hypurr",
            value: "Starting…".into(),
            needs_you: false,
        }
    } else {
        Row {
            label: "Hypurr",
            value: s.error.clone().unwrap_or_else(|| "Not running".into()),
            needs_you: true,
        }
    };
    let phone = match (s.connected_phones, s.paired_phones) {
        (0, 0) => Row {
            label: "Phone",
            value: "Not paired yet".into(),
            needs_you: true,
        },
        (0, n) => Row {
            label: "Phone",
            value: format!("{n} paired, not connected right now"),
            needs_you: false,
        },
        (1, _) => Row {
            label: "Phone",
            value: "Phone connected".into(),
            needs_you: false,
        },
        (c, _) => Row {
            label: "Phone",
            value: format!("{c} phones connected"),
            needs_you: false,
        },
    };
    let agent = match &s.agent {
        Agent::Unknown => Row {
            label: "Agent",
            value: "Checking…".into(),
            needs_you: false,
        },
        Agent::None => Row {
            label: "Agent",
            value: "No agent yet. Install Hypurr Agent from your phone".into(),
            needs_you: true,
        },
        Agent::Installed(names) => Row {
            label: "Agent",
            value: format!("{} installed", names.join(", ")),
            needs_you: false,
        },
    };
    let mut out = vec![hypurr, phone, agent];
    if let Network::Public(name) = &s.network {
        out.push(Row {
            label: "Wi-Fi",
            value: public_network_hint(name),
            needs_you: true,
        });
    }
    out
}

pub const FIREWALL_HINT: &str =
    "If Windows Firewall asks, tick \"Private networks\" and click Allow.";

pub fn public_network_hint(name: &str) -> String {
    let n = if name.is_empty() { "This Wi-Fi" } else { name };
    format!(
        "{n} is set to Public, so Windows blocks your phone. Open Settings › Network › Wi-Fi › {n} and choose Private."
    )
}

/// Agents from the host's `hello.backends`; Hypurr Agent first.
pub fn agent_from_hello(hello: &Value) -> Agent {
    let Some(list) = hello["backends"].as_array() else {
        return Agent::Unknown;
    };
    let mut names: Vec<(bool, String)> = list
        .iter()
        .filter(|b| b["installed"] == true)
        .filter_map(|b| {
            let id = b["id"].as_str().unwrap_or_default();
            let name = b["name"]
                .as_str()
                .filter(|n| !n.is_empty())
                .unwrap_or(id)
                .to_owned();
            (!name.is_empty()).then_some((id == "hypurr-agent", name))
        })
        .collect();
    names.sort_by_key(|(builtin, _)| !*builtin);
    names.dedup_by(|a, b| a.1 == b.1);
    if names.is_empty() {
        Agent::None
    } else {
        Agent::Installed(names.into_iter().map(|(_, n)| n).collect())
    }
}

/// (paired, connected) phones from the host's `devices` answer.
pub fn phones(devices: &Value) -> (usize, usize) {
    let list = devices["devices"].as_array().cloned().unwrap_or_default();
    (
        list.len(),
        list.iter().filter(|d| d["connected"] == true).count(),
    )
}

/// `Get-NetConnectionProfile` lines as "Name|Category"; the first Public one wins.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn network_from_profiles(out: &str) -> Network {
    let mut any = false;
    for line in out.lines().map(str::trim).filter(|l| !l.is_empty()) {
        let (name, cat) = line.rsplit_once('|').unwrap_or(("", line));
        any = true;
        if cat.trim().eq_ignore_ascii_case("public") {
            return Network::Public(name.trim().to_owned());
        }
    }
    if any {
        Network::Private
    } else {
        Network::Unknown
    }
}

/// Value for the per-user Run key: the quoted exe plus `--background`.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn run_key_value(exe: &str) -> String {
    format!("\"{}\" --background", exe.replace('"', ""))
}

pub fn minutes_left(expires_at_ms: i64, now_ms: i64) -> i64 {
    ((expires_at_ms - now_ms).max(0) + 59_999) / 60_000
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn refreshes_a_minute_before_expiry() {
        let s = Snapshot {
            running: true,
            pairing_url: Some("u".into()),
            expires_at_ms: 600_000,
            ..Default::default()
        };
        assert!(!s.needs_new_code(0));
        assert!(!s.needs_new_code(539_999));
        assert!(s.needs_new_code(540_000));
        assert!(
            Snapshot {
                running: true,
                ..Default::default()
            }
            .needs_new_code(0)
        );
        assert!(
            !Snapshot::default().needs_new_code(0),
            "no code while the host is down"
        );
    }

    #[test]
    fn plain_words_and_red_only_when_needed() {
        let mut s = Snapshot {
            running: true,
            paired_phones: 1,
            connected_phones: 1,
            ..Default::default()
        };
        s.agent = Agent::Installed(vec!["Hypurr Agent".into()]);
        let r = rows(&s);
        assert_eq!(r[0].value, "Running");
        assert_eq!(r[1].value, "Phone connected");
        assert_eq!(r[2].value, "Hypurr Agent installed");
        assert!(r.iter().all(|r| !r.needs_you));
        s.agent = Agent::None;
        s.connected_phones = 0;
        s.paired_phones = 0;
        let r = rows(&s);
        assert!(r[1].needs_you && r[2].needs_you);
    }

    #[test]
    fn agents_from_hello_put_hypurr_agent_first() {
        let hello = json!({"backends": [
            {"id": "claude", "name": "Claude Code", "installed": true},
            {"id": "codex", "name": "Codex", "installed": false},
            {"id": "hypurr-agent", "name": "Hypurr Agent", "installed": true},
        ]});
        assert_eq!(
            agent_from_hello(&hello),
            Agent::Installed(vec!["Hypurr Agent".into(), "Claude Code".into()])
        );
        assert_eq!(agent_from_hello(&json!({"backends": []})), Agent::None);
        assert_eq!(agent_from_hello(&json!({})), Agent::Unknown);
    }

    #[test]
    fn counts_phones() {
        let d = json!({"devices": [
            {"source": "local", "connected": true}, {"source": "account", "connected": false}
        ]});
        assert_eq!(phones(&d), (2, 1));
    }

    #[test]
    fn detects_public_wifi() {
        assert_eq!(network_from_profiles("Home|Private\n"), Network::Private);
        assert_eq!(
            network_from_profiles("Home|Private\nCafe Wi-Fi|Public\n"),
            Network::Public("Cafe Wi-Fi".into())
        );
        assert_eq!(network_from_profiles(""), Network::Unknown);
        let s = Snapshot {
            network: Network::Public("Cafe".into()),
            ..Default::default()
        };
        assert!(
            rows(&s)
                .iter()
                .any(|r| r.label == "Wi-Fi" && r.needs_you && r.value.contains("Private"))
        );
    }

    #[test]
    fn run_key_is_quoted() {
        assert_eq!(
            run_key_value(r"C:\Users\a b\AppData\Local\Programs\Hypurr\hypurr.exe"),
            r#""C:\Users\a b\AppData\Local\Programs\Hypurr\hypurr.exe" --background"#
        );
    }

    #[test]
    fn minutes_round_up() {
        assert_eq!(minutes_left(600_000, 0), 10);
        assert_eq!(minutes_left(61_000, 0), 2);
        assert_eq!(minutes_left(0, 5), 0);
    }
}
