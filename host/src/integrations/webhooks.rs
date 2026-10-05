//! Slack and Microsoft Teams incoming webhooks: a finished task's result, posted.

use super::{check, http};
use anyhow::Result;
use serde_json::{Value, json};

pub struct Message {
    pub title: String,
    pub text: String,
    pub url: Option<String>,
    /// Label / value rows (Project, Branch, Cost…).
    pub facts: Vec<(String, String)>,
}

pub fn slack_body(m: &Message) -> Value {
    let mut lines = vec![format!("*{}*", m.title)];
    if !m.text.is_empty() {
        lines.push(m.text.clone());
    }
    for (k, v) in &m.facts {
        lines.push(format!("{k}: {v}"));
    }
    if let Some(url) = &m.url {
        lines.push(format!("<{url}|Open the pull request>"));
    }
    json!({"text": lines.join("\n")})
}

/// An Adaptive Card: accepted by Teams Workflows webhooks and classic connectors.
pub fn teams_body(m: &Message) -> Value {
    let mut body =
        vec![json!({"type": "TextBlock", "text": m.title, "weight": "Bolder", "size": "Medium", "wrap": true})];
    if !m.text.is_empty() {
        body.push(json!({"type": "TextBlock", "text": m.text, "wrap": true}));
    }
    if !m.facts.is_empty() {
        let facts: Vec<Value> = m.facts.iter().map(|(k, v)| json!({"title": k, "value": v})).collect();
        body.push(json!({"type": "FactSet", "facts": facts}));
    }
    let actions: Vec<Value> =
        m.url.iter().map(|u| json!({"type": "Action.OpenUrl", "title": "Open the pull request", "url": u})).collect();
    json!({
        "type": "message",
        "attachments": [{
            "contentType": "application/vnd.microsoft.card.adaptive",
            "content": {
                "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                "type": "AdaptiveCard",
                "version": "1.4",
                "body": body,
                "actions": actions,
            }
        }]
    })
}

pub async fn post(kind: &str, url: &str, m: &Message) -> Result<()> {
    let body = if kind == "teams" { teams_body(m) } else { slack_body(m) };
    let resp = http().post(url).json(&body).send().await?;
    check(resp, if kind == "teams" { "Teams" } else { "Slack" }).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bodies() {
        let m = Message {
            title: "Done: Fix login".into(),
            text: "Fixed the null check.".into(),
            url: Some("https://github.com/a/b/pull/3".into()),
            facts: vec![("Project".into(), "shop".into())],
        };
        let s = slack_body(&m)["text"].as_str().unwrap_or_default().to_owned();
        assert!(s.starts_with("*Done: Fix login*") && s.contains("Project: shop") && s.contains("pull/3|"));
        let t = teams_body(&m);
        assert_eq!(t["attachments"][0]["content"]["actions"][0]["url"], "https://github.com/a/b/pull/3");
    }
}
