//! Work-tool integrations for tasks: GitHub (issues as tasks, a pull request when a task
//! finishes), Jira (tickets as tasks, a comment when done), and Slack / Microsoft Teams
//! (results posted through incoming webhooks). Credentials live only in this computer's
//! host store (kv `integrations`); clients only ever see whether each one is set up and
//! the last few characters. Spec: docs/features/integrations.md.

pub mod github;
pub mod jira;
pub mod webhooks;

use crate::store::Store;
use anyhow::{Result, anyhow};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::time::Duration;

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct GitHubConfig {
    /// A personal access token. Empty: use the GitHub CLI's own sign-in (`gh api`).
    pub token: String,
    /// `https://api.github.com`, or a GitHub Enterprise `https://host/api/v3`.
    pub api_url: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct JiraConfig {
    /// `https://yourcompany.atlassian.net`
    pub base_url: String,
    pub email: String,
    /// An Atlassian API token.
    pub token: String,
    /// Which tickets to offer as tasks.
    pub jql: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct WebhookConfig {
    pub url: String,
}

/// What happens when a task finishes, plus the connections (kv `integrations`).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct Integrations {
    pub github: GitHubConfig,
    pub jira: JiraConfig,
    pub slack: WebhookConfig,
    pub teams: WebhookConfig,
    /// Learning mode: the agent explains what it changed and why when a task finishes.
    pub learning: bool,
    /// Open a pull request when a task finishes (GitHub projects only).
    pub auto_pr: bool,
    /// Post the result to Slack / Teams when a task finishes.
    pub notify: bool,
}

impl Default for Integrations {
    fn default() -> Self {
        Self {
            github: GitHubConfig::default(),
            jira: JiraConfig::default(),
            slack: WebhookConfig::default(),
            teams: WebhookConfig::default(),
            learning: true,
            auto_pr: true,
            notify: true,
        }
    }
}

pub const DEFAULT_JQL: &str = "assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC";

/// `…abcd` for a secret, empty when unset.
fn hint(secret: &str) -> String {
    let n = secret.chars().count();
    if n == 0 {
        return String::new();
    }
    let tail: String = secret.chars().skip(n.saturating_sub(4)).collect();
    format!("…{tail}")
}

impl Integrations {
    pub fn load(store: &Store) -> Self {
        store.kv_get("integrations").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
    }

    pub fn save(&self, store: &Store) -> Result<()> {
        store.kv_set("integrations", &serde_json::to_string(self)?)
    }

    pub fn jira_ready(&self) -> bool {
        !self.jira.base_url.is_empty() && !self.jira.email.is_empty() && !self.jira.token.is_empty()
    }

    /// What clients see: never a token or a full webhook URL.
    pub fn public(&self) -> Value {
        let gh_cli = self.github.token.is_empty() && github::cli_available();
        json!({
            "github": {
                "configured": !self.github.token.is_empty() || gh_cli,
                "tokenHint": hint(&self.github.token),
                "usesCli": gh_cli,
                "apiUrl": self.github.api_url,
            },
            "jira": {
                "configured": self.jira_ready(),
                "baseUrl": self.jira.base_url,
                "email": self.jira.email,
                "tokenHint": hint(&self.jira.token),
                "jql": if self.jira.jql.is_empty() { DEFAULT_JQL } else { &self.jira.jql },
            },
            "slack": {"configured": !self.slack.url.is_empty(), "urlHint": hint(&self.slack.url)},
            "teams": {"configured": !self.teams.url.is_empty(), "urlHint": hint(&self.teams.url)},
            "learning": self.learning,
            "autoPr": self.auto_pr,
            "notify": self.notify,
        })
    }
}

fn set_str(target: &mut String, v: &Value) {
    if let Some(s) = v.as_str() {
        s.trim().clone_into(target);
    }
}

/// Partial update: a field that's missing stays, an empty string clears it.
pub fn update(store: &Store, b: &Value) -> Result<Integrations> {
    let mut c = Integrations::load(store);
    set_str(&mut c.github.token, &b["github"]["token"]);
    set_str(&mut c.github.api_url, &b["github"]["apiUrl"]);
    set_str(&mut c.jira.base_url, &b["jira"]["baseUrl"]);
    set_str(&mut c.jira.email, &b["jira"]["email"]);
    set_str(&mut c.jira.token, &b["jira"]["token"]);
    set_str(&mut c.jira.jql, &b["jira"]["jql"]);
    set_str(&mut c.slack.url, &b["slack"]["url"]);
    set_str(&mut c.teams.url, &b["teams"]["url"]);
    c.jira.base_url = c.jira.base_url.trim_end_matches('/').to_owned();
    c.github.api_url = c.github.api_url.trim_end_matches('/').to_owned();
    for url in [&c.slack.url, &c.teams.url, &c.jira.base_url, &c.github.api_url] {
        if !url.is_empty() && !url.starts_with("https://") && !is_local_test_url(url) {
            return Err(anyhow!("{url} must start with https://"));
        }
    }
    if let Some(v) = b["learning"].as_bool() {
        c.learning = v;
    }
    if let Some(v) = b["autoPr"].as_bool() {
        c.auto_pr = v;
    }
    if let Some(v) = b["notify"].as_bool() {
        c.notify = v;
    }
    c.save(store)?;
    Ok(c)
}

/// Plain http is only accepted for this computer (tests, local mocks).
fn is_local_test_url(url: &str) -> bool {
    url.starts_with("http://127.0.0.1:") || url.starts_with("http://localhost:")
}

pub fn http() -> reqwest::Client {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .user_agent(concat!("hypurr-host/", env!("CARGO_PKG_VERSION")))
        .build()
        .unwrap_or_default()
}

/// A short error for the user: the service's own message when it sent one.
pub async fn check(resp: reqwest::Response, what: &str) -> Result<Value> {
    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    let body: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
    if status.is_success() {
        return Ok(body);
    }
    let msg = body["message"]
        .as_str()
        .or_else(|| body["errorMessages"][0].as_str())
        .map_or_else(|| crate::agent::acp::truncate(text.trim(), 200), str::to_owned);
    Err(anyhow!("{what} failed ({}): {msg}", status.as_u16()))
}

/// An issue or ticket a task can start from.
#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct Issue {
    /// github | jira
    pub source: String,
    /// `#142` or `SHOP-142`
    pub key: String,
    pub title: String,
    pub url: String,
    pub body: String,
    /// The project folder it belongs to (GitHub issues of a project's repository).
    pub project: Option<String>,
}

/// Open issues for the New task screen: the GitHub issues of each GitHub project, and the
/// user's Jira tickets. A source that fails reports its error instead of failing the rest.
pub async fn issues(store: &Store, projects: &[crate::tasks::router::Project]) -> Value {
    let cfg = Integrations::load(store);
    let mut out: Vec<Issue> = vec![];
    let mut errors: Vec<Value> = vec![];
    let gh = github::Client::new(&cfg.github);
    if let Some(gh) = gh {
        for p in projects.iter().take(8) {
            let Some(repo) = github::repo_of(std::path::Path::new(&p.path)) else { continue };
            match gh.issues(&repo).await {
                Ok(list) => out.extend(list.into_iter().map(|mut i| {
                    i.project = Some(p.path.clone());
                    i
                })),
                Err(e) => errors.push(json!({"source": "github", "message": format!("{repo}: {e:#}")})),
            }
        }
    }
    if cfg.jira_ready() {
        match jira::issues(&cfg.jira).await {
            Ok(list) => out.extend(list),
            Err(e) => errors.push(json!({"source": "jira", "message": format!("{e:#}")})),
        }
    }
    json!({"issues": out, "errors": errors})
}

/// "Test" in settings: checks the connection, or posts a test message to a webhook.
pub async fn test(store: &Store, kind: &str) -> Result<Value> {
    let cfg = Integrations::load(store);
    let detail = match kind {
        "github" => {
            let gh = github::Client::new(&cfg.github).ok_or_else(|| anyhow!("GitHub isn't set up"))?;
            format!("Signed in as {}", gh.whoami().await?)
        }
        "jira" => format!("Signed in as {}", jira::whoami(&cfg.jira).await?),
        "slack" | "teams" => {
            let url = if kind == "slack" { &cfg.slack.url } else { &cfg.teams.url };
            if url.is_empty() {
                return Err(anyhow!("add the webhook URL first"));
            }
            let msg = webhooks::Message {
                title: "Hypurr is connected".into(),
                text: "Finished tasks will be posted here.".into(),
                url: None,
                facts: vec![],
            };
            webhooks::post(kind, url, &msg).await?;
            "Sent a test message".into()
        }
        _ => return Err(anyhow!("unknown integration")),
    };
    Ok(json!({"ok": true, "detail": detail}))
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;
    use std::path::Path;

    #[test]
    fn secrets_never_leave_and_updates_are_partial() {
        let store = Store::open(Path::new(":memory:")).unwrap();
        let c = update(
            &store,
            &json!({"jira": {"baseUrl": "https://acme.atlassian.net/", "email": "p@acme.in", "token": "secret-token-1234"},
                    "slack": {"url": "https://hooks.slack.com/services/T/B/xyzw"}}),
        )
        .unwrap();
        assert_eq!(c.jira.base_url, "https://acme.atlassian.net");
        let public = c.public().to_string();
        assert!(!public.contains("secret-token") && !public.contains("services/T"), "{public}");
        assert!(public.contains("…1234") && public.contains("…xyzw"));
        let c = update(&store, &json!({"learning": false, "slack": {"url": ""}})).unwrap();
        assert!(c.slack.url.is_empty() && !c.learning && c.jira_ready());
        assert!(update(&store, &json!({"teams": {"url": "http://evil.example/hook"}})).is_err());
    }
}
