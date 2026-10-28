//! Work account and the company team pool (host `work*` methods, gateway `cloud/src/teams`).
//! Sign-in opens the company's Google or Microsoft page in the browser; the host catches the
//! redirect on 127.0.0.1 and keeps the work key. Admins set limits, pause people, fill the pool.

use crate::host::call_with;
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[derive(Default, Clone)]
pub struct TeamState {
    pub email: String,
    pub setup: bool,
    pub team_name: String,
    pub provider_ms: bool,
    pub client_id: String,
    pub tenant: String,
    pub pool_input: String,
    pub limit_input: String,
    pub editing: Option<String>,
    pub busy: Option<String>,
    pub error: Option<String>,
    /// `gatewayBalance`: `workEmail`, `team` (limit, spend).
    pub balance: Value,
    /// `workTeam`: team, role, members, poolCents.
    pub team: Value,
}

impl TeamState {
    pub fn signed_in(&self) -> bool {
        self.balance["workEmail"]
            .as_str()
            .is_some_and(|e| !e.is_empty())
    }
    pub fn is_admin(&self) -> bool {
        self.team["role"] == "admin"
    }
}

/// Gateway credits are US cents.
pub fn dollars(cents: Option<i64>) -> String {
    cents.map_or_else(|| "–".to_owned(), |c| format!("${:.2}", c as f64 / 100.0))
}

/// "$12.50" / "12.5" → cents; empty → None.
pub fn parse_dollars(s: &str) -> Option<i64> {
    let v: f64 = s.trim().trim_start_matches('$').parse().ok()?;
    (v >= 0.0).then(|| (v * 100.0).round() as i64)
}

#[derive(Clone)]
pub struct Team {
    pub state: Arc<Mutex<TeamState>>,
    repaint: Arc<dyn Fn() + Send + Sync>,
}

pub enum Action {
    Refresh,
    SignIn,
    SignOut,
    Member(String, &'static str, Value),
    Pool(i64),
}

fn open_in_browser(url: &str) {
    #[cfg(windows)]
    let _ = std::process::Command::new("rundll32")
        .args(["url.dll,FileProtocolHandler", url])
        .spawn();
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg(url).spawn();
    #[cfg(all(unix, not(target_os = "macos")))]
    let _ = std::process::Command::new("xdg-open").arg(url).spawn();
}

impl Team {
    pub fn new(repaint: impl Fn() + Send + Sync + 'static) -> Self {
        Team {
            state: Arc::new(Mutex::new(TeamState::default())),
            repaint: Arc::new(repaint),
        }
    }

    pub fn demo(state: TeamState) -> Self {
        Team {
            state: Arc::new(Mutex::new(state)),
            repaint: Arc::new(|| {}),
        }
    }

    fn set(&self, f: impl FnOnce(&mut TeamState)) {
        f(&mut self.state.lock().unwrap());
        (self.repaint)();
    }

    fn load(&self) {
        let balance = call_with("gatewayBalance", &json!({})).unwrap_or(Value::Null);
        let signed = balance["workEmail"].as_str().is_some_and(|e| !e.is_empty());
        let team = if signed {
            call_with("workTeam", &json!({}))
        } else {
            Ok(Value::Null)
        };
        self.set(|s| {
            s.balance = balance;
            match team {
                Ok(t) => s.team = t,
                Err(e) => s.error = Some(e),
            }
        });
    }

    /// Runs one action off the UI thread.
    pub fn run(&self, action: Action) {
        let me = self.clone();
        let snapshot = self.state.lock().unwrap().clone();
        if snapshot.busy.is_some() {
            return;
        }
        self.set(|s| {
            s.busy = Some("Working…".into());
            s.error = None;
        });
        std::thread::spawn(move || {
            let r = match action {
                Action::Refresh => Ok(()),
                Action::SignIn => me.sign_in(&snapshot),
                Action::SignOut => call_with("workSignOut", &json!({})).map(|_| ()),
                Action::Member(id, k, v) => {
                    let mut body = json!({"userId": id});
                    body[k] = v;
                    call_with("workTeamMember", &body).map(|_| ())
                }
                Action::Pool(cents) => {
                    call_with("workTeamPool", &json!({"amountCents": cents})).map(|_| ())
                }
            };
            me.load();
            me.set(|s| {
                s.busy = None;
                if let Err(e) = r {
                    s.error = Some(e);
                }
            });
        });
    }

    fn sign_in(&self, s: &TeamState) -> Result<(), String> {
        let mut body = json!({"email": s.email.trim()});
        if s.setup {
            body["setup"] = json!({
                "name": s.team_name.trim(),
                "provider": if s.provider_ms { "microsoft" } else { "google" },
                "clientId": s.client_id.trim(),
                "tenant": if s.provider_ms { Some(s.tenant.trim()) } else { None },
            });
        }
        let start = call_with("workDesktopStart", &body)?;
        let attempt = start["attempt"].as_str().unwrap_or_default().to_owned();
        open_in_browser(start["url"].as_str().unwrap_or_default());
        self.set(|s| s.busy = Some("Finish signing in in your browser…".into()));
        for _ in 0..600 {
            std::thread::sleep(Duration::from_secs(1));
            let r = call_with("workDesktopResult", &json!({"attempt": attempt}))?;
            if r["done"] == true {
                return Ok(());
            }
        }
        Err("Sign-in didn't finish within 10 minutes.".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn money() {
        assert_eq!(dollars(Some(1234)), "$12.34");
        assert_eq!(dollars(None), "–");
        assert_eq!(parse_dollars("$12.5"), Some(1250));
        assert_eq!(parse_dollars(""), None);
        assert_eq!(parse_dollars("-1"), None);
    }

    #[test]
    fn roles() {
        let s = TeamState {
            balance: json!({"workEmail": "a@b.c"}),
            team: json!({"role": "admin"}),
            ..Default::default()
        };
        assert!(s.signed_in() && s.is_admin());
        assert!(!TeamState::default().signed_in());
    }
}
