//! Jira Cloud over REST with an API token (basic auth: email + token): the user's open
//! tickets as tasks, and a comment on the ticket when its task finishes.

use super::{DEFAULT_JQL, Issue, JiraConfig, check, http};
use anyhow::Result;
use serde_json::{Value, json};

fn get(cfg: &JiraConfig, path: &str) -> reqwest::RequestBuilder {
    http()
        .get(format!("{}{path}", cfg.base_url))
        .basic_auth(&cfg.email, Some(&cfg.token))
        .header("Accept", "application/json")
}

pub async fn whoami(cfg: &JiraConfig) -> Result<String> {
    let v = check(get(cfg, "/rest/api/3/myself").send().await?, "Jira").await?;
    Ok(v["displayName"].as_str().or(v["emailAddress"].as_str()).unwrap_or("unknown").to_owned())
}

/// Plain text from Atlassian Document Format.
pub fn adf_text(v: &Value) -> String {
    fn walk(v: &Value, out: &mut String) {
        if let Some(t) = v["text"].as_str() {
            out.push_str(t);
        }
        for c in v["content"].as_array().into_iter().flatten() {
            walk(c, out);
        }
        if matches!(v["type"].as_str(), Some("paragraph" | "heading" | "listItem" | "codeBlock")) {
            out.push('\n');
        }
    }
    if let Some(s) = v.as_str() {
        return s.to_owned();
    }
    let mut out = String::new();
    walk(v, &mut out);
    out.trim().to_owned()
}

pub async fn issues(cfg: &JiraConfig) -> Result<Vec<Issue>> {
    let jql = if cfg.jql.is_empty() { DEFAULT_JQL } else { &cfg.jql };
    let url = reqwest::Url::parse_with_params(
        &format!("{}/rest/api/3/search/jql", cfg.base_url),
        &[("jql", jql), ("fields", "summary,description"), ("maxResults", "30")],
    )?;
    let req = http().get(url).basic_auth(&cfg.email, Some(&cfg.token)).header("Accept", "application/json");
    let v = check(req.send().await?, "Jira").await?;
    Ok(v["issues"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|i| {
            let key = i["key"].as_str().unwrap_or_default().to_owned();
            Issue {
                source: "jira".into(),
                url: format!("{}/browse/{key}", cfg.base_url),
                key,
                title: i["fields"]["summary"].as_str().unwrap_or_default().to_owned(),
                body: crate::agent::acp::truncate(&adf_text(&i["fields"]["description"]), 4000),
                project: None,
            }
        })
        .collect())
}

pub async fn comment(cfg: &JiraConfig, key: &str, text: &str, link: Option<&str>) -> Result<()> {
    let mut content = vec![json!({"type": "paragraph", "content": [{"type": "text", "text": text}]})];
    if let Some(url) = link {
        content.push(json!({"type": "paragraph", "content": [
            {"type": "text", "text": url, "marks": [{"type": "link", "attrs": {"href": url}}]}
        ]}));
    }
    let body = json!({"body": {"type": "doc", "version": 1, "content": content}});
    let req = http()
        .post(format!("{}/rest/api/3/issue/{key}/comment", cfg.base_url))
        .basic_auth(&cfg.email, Some(&cfg.token))
        .json(&body);
    check(req.send().await?, "Jira comment").await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adf_to_text() {
        let doc = json!({"type": "doc", "content": [
            {"type": "paragraph", "content": [{"type": "text", "text": "Checkout "}, {"type": "text", "text": "fails"}]},
            {"type": "paragraph", "content": [{"type": "text", "text": "on Safari"}]}
        ]});
        assert_eq!(adf_text(&doc), "Checkout fails\non Safari");
        assert_eq!(adf_text(&json!("plain")), "plain");
    }
}
