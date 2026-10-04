//! GitHub: open issues of a project's repository, and a pull request for a finished task.
//! Uses the token from settings over the REST API, or, without one, the GitHub CLI's own
//! sign-in through `gh api` (Hypurr never reads the CLI's token).

use super::{GitHubConfig, Issue, check, http};
use anyhow::{Context as _, Result, anyhow, bail};
use serde_json::{Value, json};
use std::path::Path;
use std::process::Stdio;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tokio::io::AsyncWriteExt as _;

pub enum Client {
    Rest { api: String, token: String },
    Cli,
}

static CLI: Mutex<Option<(Instant, bool)>> = Mutex::new(None);

/// `gh` is installed and signed in (checked at most once a minute).
pub fn cli_available() -> bool {
    let mut cached = CLI.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some((at, ok)) = *cached
        && at.elapsed() < Duration::from_secs(60)
    {
        return ok;
    }
    let ok = std::process::Command::new("gh")
        .args(["auth", "status", "--active"])
        .env("GH_PROMPT_DISABLED", "1")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|s| s.success());
    *cached = Some((Instant::now(), ok));
    ok
}

/// `owner/repo` from a GitHub `origin` remote (https or ssh).
pub fn parse_remote(url: &str) -> Option<String> {
    let url = url.trim().trim_end_matches('/').trim_end_matches(".git");
    let rest = if let Some(r) = url.strip_prefix("git@") {
        r.split_once(':')?.1.to_owned()
    } else {
        let after = url.split_once("://")?.1;
        let after = after.rsplit_once('@').map_or(after, |(_, h)| h);
        after.split_once('/')?.1.to_owned()
    };
    let mut parts = rest.split('/').filter(|s| !s.is_empty());
    let (owner, repo) = (parts.next()?, parts.next()?);
    (parts.next().is_none()).then(|| format!("{owner}/{repo}"))
}

fn remote_url(dir: &Path) -> Option<String> {
    let out =
        std::process::Command::new("git").arg("-C").arg(dir).args(["remote", "get-url", "origin"]).output().ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_owned())
}

/// The GitHub repository a project folder pushes to, if any.
pub fn repo_of(dir: &Path) -> Option<String> {
    let url = remote_url(dir)?;
    url.contains("github").then(|| parse_remote(&url)).flatten()
}

impl Client {
    pub fn new(cfg: &GitHubConfig) -> Option<Self> {
        if !cfg.token.is_empty() {
            let api = if cfg.api_url.is_empty() { "https://api.github.com".to_owned() } else { cfg.api_url.clone() };
            return Some(Self::Rest { api, token: cfg.token.clone() });
        }
        cli_available().then_some(Self::Cli)
    }

    pub fn token(&self) -> Option<&str> {
        match self {
            Self::Rest { token, .. } => Some(token),
            Self::Cli => None,
        }
    }

    async fn request(&self, method: &str, path: &str, body: Option<Value>) -> Result<Value> {
        match self {
            Self::Rest { api, token } => {
                let m = reqwest::Method::from_bytes(method.as_bytes())?;
                let mut req = http()
                    .request(m, format!("{api}{path}"))
                    .bearer_auth(token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
                if let Some(b) = body {
                    req = req.json(&b);
                }
                check(req.send().await?, "GitHub").await
            }
            Self::Cli => {
                let mut cmd = tokio::process::Command::new("gh");
                cmd.args(["api", "-X", method, path.trim_start_matches('/')])
                    .env("GH_PROMPT_DISABLED", "1")
                    .stdin(Stdio::piped())
                    .stdout(Stdio::piped())
                    .stderr(Stdio::piped())
                    .kill_on_drop(true);
                if body.is_some() {
                    cmd.args(["--input", "-"]);
                }
                let mut child = cmd.spawn().context("running gh")?;
                if let (Some(b), Some(mut stdin)) = (body, child.stdin.take()) {
                    stdin.write_all(b.to_string().as_bytes()).await?;
                }
                let out = tokio::time::timeout(Duration::from_secs(30), child.wait_with_output())
                    .await
                    .map_err(|_| anyhow!("GitHub CLI timed out"))??;
                let text = String::from_utf8_lossy(&out.stdout);
                let v: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
                if !out.status.success() {
                    let msg = v["message"]
                        .as_str()
                        .map_or_else(|| String::from_utf8_lossy(&out.stderr).trim().to_owned(), str::to_owned);
                    bail!("GitHub failed: {msg}");
                }
                Ok(v)
            }
        }
    }

    pub async fn whoami(&self) -> Result<String> {
        let v = self.request("GET", "/user", None).await?;
        Ok(v["login"].as_str().unwrap_or("unknown").to_owned())
    }

    /// Open issues (not pull requests), newest activity first.
    pub async fn issues(&self, repo: &str) -> Result<Vec<Issue>> {
        let v = self.request("GET", &format!("/repos/{repo}/issues?state=open&per_page=30&sort=updated"), None).await?;
        Ok(v.as_array()
            .into_iter()
            .flatten()
            .filter(|i| i["pull_request"].is_null())
            .map(|i| Issue {
                source: "github".into(),
                key: format!("#{}", i["number"].as_u64().unwrap_or(0)),
                title: i["title"].as_str().unwrap_or_default().to_owned(),
                url: i["html_url"].as_str().unwrap_or_default().to_owned(),
                body: crate::agent::acp::truncate(i["body"].as_str().unwrap_or_default(), 4000),
                project: None,
            })
            .collect())
    }

    /// Opens a pull request (or returns the one already open for this branch).
    pub async fn open_pr(&self, repo: &str, head: &str, base: &str, title: &str, body: &str) -> Result<(u64, String)> {
        let owner = repo.split('/').next().unwrap_or_default();
        let existing = self
            .request("GET", &format!("/repos/{repo}/pulls?state=open&head={owner}:{}", urlencode(head)), None)
            .await?;
        if let Some(pr) = existing.as_array().and_then(|a| a.first()) {
            return Ok((pr["number"].as_u64().unwrap_or(0), pr["html_url"].as_str().unwrap_or_default().to_owned()));
        }
        let pr = self
            .request(
                "POST",
                &format!("/repos/{repo}/pulls"),
                Some(json!({"title": title, "head": head, "base": base, "body": body})),
            )
            .await?;
        Ok((pr["number"].as_u64().unwrap_or(0), pr["html_url"].as_str().unwrap_or_default().to_owned()))
    }
}

fn urlencode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

/// Pushes the task branch to `origin` (never a protected branch: the caller checks).
/// Uses the user's own git credentials; with a GitHub token set and an https remote, a
/// failed push is retried with the token passed in git's environment (not the command line).
pub fn push_branch(worktree: &Path, branch: &str, token: Option<&str>) -> Result<()> {
    let refspec = format!("{branch}:refs/heads/{branch}");
    let run = |extra: &[(String, String)]| {
        let mut cmd = std::process::Command::new("git");
        cmd.arg("-C").arg(worktree).args(["push", "--no-verify", "-q", "origin", &refspec]);
        cmd.env("GIT_TERMINAL_PROMPT", "0");
        for (k, v) in extra {
            cmd.env(k, v);
        }
        cmd.output()
    };
    let out = run(&[]).context("running git push")?;
    if out.status.success() {
        return Ok(());
    }
    let first = String::from_utf8_lossy(&out.stderr).trim().to_owned();
    let https = remote_url(worktree).is_some_and(|u| u.starts_with("https://"));
    if let (Some(token), true) = (token, https) {
        use base64::Engine as _;
        let basic = base64::engine::general_purpose::STANDARD.encode(format!("x-access-token:{token}"));
        let env = [
            ("GIT_CONFIG_COUNT".to_owned(), "1".to_owned()),
            ("GIT_CONFIG_KEY_0".to_owned(), "http.extraHeader".to_owned()),
            ("GIT_CONFIG_VALUE_0".to_owned(), format!("Authorization: Basic {basic}")),
        ];
        let out = run(&env).context("running git push")?;
        if out.status.success() {
            return Ok(());
        }
        bail!("couldn't upload the branch: {}", String::from_utf8_lossy(&out.stderr).trim());
    }
    bail!("couldn't upload the branch: {first}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn remotes_parse() {
        assert_eq!(parse_remote("git@github.com:Ragul84/Hypurr.git").as_deref(), Some("Ragul84/Hypurr"));
        assert_eq!(parse_remote("https://github.com/Ragul84/Hypurr").as_deref(), Some("Ragul84/Hypurr"));
        assert_eq!(parse_remote("https://x:tok@github.com/a/b.git/").as_deref(), Some("a/b"));
        assert_eq!(parse_remote("https://github.com/a/b/c"), None);
        assert_eq!(urlencode("hypurr/fix-1"), "hypurr%2Ffix-1");
    }
}
