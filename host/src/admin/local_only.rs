//! Local-only mode (a team rule, `Policies.local_only`): Hypurr itself contacts nothing on the
//! internet. Off while it's on:
//! - the Hypurr cloud (accounts, the off-LAN relay): phones pair and connect on the LAN / VPN only;
//! - the push relay (notifications);
//! - update checks and downloads, the agent registry refresh, the connector / skill
//!   marketplaces and Composio.
//!
//! Agents may only start when every model endpoint they're configured with (their saved
//! `*_BASE_URL` / `*_ENDPOINT` / `*_API_BASE` / `OLLAMA_HOST` keys) is on the approved list
//! (`Policies.model_endpoints`: the company's gateway, or a local model server), and when
//! they're on the allowed-agents list if there is one. Hypurr can't see inside an agent: an
//! agent that ignores these settings must be blocked by the company's own network rules
//! (docs/it-kit/allowlist.md). `HYPURR_LOCAL_ONLY=1` forces the mode on for managed installs.

use super::{NotAllowed, Policies};
use crate::store::Store;
use anyhow::{Result, bail};
use serde_json::{Value, json};
use std::sync::atomic::{AtomicBool, Ordering};

static ON: AtomicBool = AtomicBool::new(false);

fn forced() -> bool {
    std::env::var("HYPURR_LOCAL_ONLY").is_ok_and(|v| v == "1" || v.eq_ignore_ascii_case("true"))
}

/// Reads the rule into the process-wide switch (startup and after `setPolicies`).
pub fn sync(store: &Store) {
    ON.store(forced() || Policies::load(store).local_only, Ordering::Relaxed);
}

/// Whether local-only mode is on (for code without a store: background loops).
pub fn active() -> bool {
    forced() || ON.load(Ordering::Relaxed)
}

pub fn on(store: &Store) -> bool {
    forced() || Policies::load(store).local_only
}

/// Methods that reach the internet for Hypurr itself.
const BLOCKED: &[(&str, &str)] = &[
    ("checkHostUpdate", "the update server"),
    ("installHostUpdate", "the update server"),
    ("installBuiltinAgent", "GitHub to download the agent"),
    ("marketConnectors", "the connector marketplace"),
    ("marketSkills", "the skills marketplace"),
    ("installConnector", "the connector marketplace"),
    ("installSkill", "the skills marketplace"),
    ("connectorSignIn", "the connector's sign-in page"),
    ("setComposioKey", "Composio"),
    ("composioConnect", "Composio"),
    ("composioConnectFields", "Composio"),
    ("composioConnection", "Composio"),
];

pub fn check_method(store: &Store, method: &str) -> Result<(), NotAllowed> {
    if let Some((_, what)) = BLOCKED.iter().find(|(m, _)| *m == method)
        && on(store)
    {
        return Err(NotAllowed(format!(
            "Local-only mode is on, so Hypurr doesn't contact {what}. An admin can change this in the Manager console."
        )));
    }
    Ok(())
}

/// The model endpoints an agent's saved keys point at.
pub fn endpoints(env: &[(String, String)]) -> Vec<String> {
    env.iter()
        .filter(|(k, _)| {
            let k = k.to_uppercase();
            k.ends_with("BASE_URL") || k.ends_with("_ENDPOINT") || k.ends_with("API_BASE") || k == "OLLAMA_HOST"
        })
        .map(|(_, v)| v.trim().trim_end_matches('/').to_owned())
        .filter(|v| !v.is_empty())
        .collect()
}

fn approved(endpoint: &str, list: &[String]) -> bool {
    list.iter().any(|a| {
        let a = a.trim().trim_end_matches('/');
        !a.is_empty() && (endpoint == a || endpoint.starts_with(&format!("{a}/")))
    })
}

/// Before an agent starts: in local-only mode, its endpoints must all be approved.
pub fn check_agent(store: &Store, backend: &str, env: &[(String, String)]) -> Result<()> {
    if !on(store) {
        return Ok(());
    }
    let p = Policies::load(store);
    if !p.agent_allowed(backend) {
        bail!("Local-only mode: the agent “{backend}” isn't on your team's allowed list.");
    }
    if p.model_endpoints.is_empty() {
        bail!(
            "Local-only mode is on, but no approved model endpoint is set. An admin adds the company's model gateway or local model server in the Manager console."
        );
    }
    let found = endpoints(env);
    if found.is_empty() {
        bail!(
            "Local-only mode: set this agent to use an approved model endpoint ({}) in its sign-in keys, e.g. ANTHROPIC_BASE_URL or OPENAI_BASE_URL.",
            p.model_endpoints.join(", ")
        );
    }
    if let Some(bad) = found.iter().find(|e| !approved(e, &p.model_endpoints)) {
        bail!("Local-only mode: this agent is set to use {bad}, which isn't an approved model endpoint.");
    }
    Ok(())
}

/// For the IT kit and the Manager console.
pub fn status(store: &Store) -> Value {
    let p = Policies::load(store);
    json!({
        "on": on(store),
        "forced": forced(),
        "modelEndpoints": p.model_endpoints,
        "cloud": crate::remote::cloud::url(store),
        "blocked": BLOCKED.iter().map(|(m, _)| *m).collect::<Vec<_>>(),
    })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    fn store_with(local: bool, endpoints: &[&str], agents: &[&str]) -> Store {
        let store = Store::open(std::path::Path::new(":memory:")).unwrap();
        let p = Policies {
            local_only: local,
            model_endpoints: endpoints.iter().map(|s| (*s).to_owned()).collect(),
            allowed_agents: agents.iter().map(|s| (*s).to_owned()).collect(),
            ..Policies::default()
        };
        p.save(&store).unwrap();
        store
    }

    fn env(pairs: &[(&str, &str)]) -> Vec<(String, String)> {
        pairs.iter().map(|(k, v)| ((*k).to_owned(), (*v).to_owned())).collect()
    }

    #[test]
    fn off_allows_everything() {
        let s = store_with(false, &[], &[]);
        assert!(check_agent(&s, "claude", &[]).is_ok());
        assert!(check_method(&s, "marketSkills").is_ok());
    }

    #[test]
    fn on_needs_approved_endpoints() {
        let s = store_with(true, &["https://llm.acme.in/v1", "http://localhost:11434"], &[]);
        assert!(check_agent(&s, "claude", &[]).unwrap_err().to_string().contains("approved model endpoint"));
        assert!(check_agent(&s, "claude", &env(&[("ANTHROPIC_BASE_URL", "https://llm.acme.in/v1/")])).is_ok());
        assert!(check_agent(&s, "x", &env(&[("OLLAMA_HOST", "http://localhost:11434")])).is_ok());
        let e = check_agent(&s, "claude", &env(&[("OPENAI_BASE_URL", "https://llm.acme.in/v1.evil.com")])).unwrap_err();
        assert!(e.to_string().contains("isn't an approved"));
        let e = check_agent(
            &s,
            "c",
            &env(&[("ANTHROPIC_BASE_URL", "https://llm.acme.in/v1"), ("OPENAI_BASE_URL", "https://api.openai.com/v1")]),
        );
        assert!(e.is_err(), "every endpoint must be approved");
        assert!(check_method(&s, "marketSkills").is_err() && check_method(&s, "tasks").is_ok());
    }

    #[test]
    fn on_respects_allowed_agents_and_needs_a_list() {
        let s = store_with(true, &[], &["claude"]);
        assert!(check_agent(&s, "codex", &[]).unwrap_err().to_string().contains("allowed list"));
        assert!(check_agent(&s, "claude", &[]).unwrap_err().to_string().contains("no approved model endpoint"));
    }

    #[test]
    fn cloud_is_off_in_local_only() {
        let s = store_with(true, &["http://localhost:11434"], &[]);
        s.kv_set("cloud_url", "https://cloud.example").unwrap();
        assert_eq!(crate::remote::cloud::url(&s), None);
        let s = store_with(false, &[], &[]);
        s.kv_set("cloud_url", "https://cloud.example").unwrap();
        assert_eq!(crate::remote::cloud::url(&s).as_deref(), Some("https://cloud.example"));
    }
}
