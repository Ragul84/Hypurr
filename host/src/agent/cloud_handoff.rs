//! Hand a task off to the agent vendor's own cloud, on the user's own account, so the PC can be
//! turned off. Only officially supported paths are used:
//! - Codex: `codex cloud exec --env <environment> [--branch <b>]` (prompt on stdin), on the
//!   `ChatGPT` sign-in Codex already has. Needs a Codex cloud environment for the repo.
//! - Claude Code: `claude --cloud "<task>"` (creates a claude.ai cloud session from the repo's
//!   GitHub remote at the current branch), on the Claude sign-in Claude Code already has.
//! - Cursor: Cloud Agents API (`POST https://api.cursor.com/v0/agents`) with the user's own Cursor
//!   API key, kept in this computer's vault.
//!
//! The cloud clones the pushed remote branch, not local changes: Hypurr says so before handing off.

use crate::agent::backends;
use crate::store::Store;
use anyhow::{Context, Result, anyhow, bail};
use serde_json::{Value, json};
use std::path::Path;
use std::time::Duration;

pub const CURSOR_KEY: &str = "CURSOR_API_KEY";
const CURSOR_API: &str = "https://api.cursor.com/v0/agents";

/// What each vendor needs, for the phone: `{vendors:[{id, name, ready, needs}], repo, branch, unpushed}`.
pub async fn options(store: &Store, project: &str) -> Result<Value> {
    let dir = Path::new(project);
    let repo = git(dir, &["remote", "get-url", "origin"]).await.ok().map(|u| https_url(&u));
    let branch = git(dir, &["rev-parse", "--abbrev-ref", "HEAD"]).await.ok();
    let unpushed = git(dir, &["status", "--porcelain", "--branch"]).await.is_ok_and(|s| has_unpushed(&s));
    let cursor_key = crate::agent::auth::env(store, "cursor")?.iter().any(|(k, _)| k == CURSOR_KEY);
    let saved_env = crate::market::vault::read(store, &env_slot(project))?;
    let vendors = vec![
        vendor(
            "codex",
            "Codex cloud",
            backends::which("codex").is_some() && backends::signed_in("codex") != Some(false),
            if saved_env.is_some() { None } else { Some("the name of your Codex cloud environment for this repo") },
        ),
        vendor(
            "claude",
            "Claude Code on the web",
            backends::which("claude").is_some() && backends::signed_in("claude") != Some(false),
            None,
        ),
        vendor(
            "cursor",
            "Cursor cloud agent",
            cursor_key,
            (!cursor_key).then_some("a Cursor API key (Cursor dashboard → API Keys)"),
        ),
    ];
    Ok(json!({"vendors": vendors, "repo": repo, "branch": branch, "unpushed": unpushed, "codexEnv": saved_env}))
}

fn vendor(id: &str, name: &str, ready: bool, needs: Option<&str>) -> Value {
    json!({"id": id, "name": name, "ready": ready, "needs": needs})
}

fn env_slot(project: &str) -> String {
    format!("codex-cloud-env:{project}")
}

/// Starts the task in the vendor's cloud; returns `{vendor, url}`.
pub async fn hand_off(store: &Store, b: &Value) -> Result<Value> {
    let project = b["project"].as_str().context("project is required")?;
    let vendor = b["vendor"].as_str().context("vendor is required")?;
    let prompt =
        b["prompt"].as_str().map(str::trim).filter(|p| !p.is_empty()).context("Say what the cloud agent should do.")?;
    if prompt.len() > 20_000 {
        bail!("That task description is too long.");
    }
    let dir = Path::new(project);
    let branch =
        b["branch"].as_str().map(str::to_owned).or(git(dir, &["rev-parse", "--abbrev-ref", "HEAD"]).await.ok());
    let url = match vendor {
        "codex" => {
            let env = match b["codexEnv"].as_str().map(str::trim).filter(|s| !s.is_empty()) {
                Some(e) => {
                    crate::market::vault::write(store, &env_slot(project), e)?;
                    e.to_owned()
                }
                None => crate::market::vault::read(store, &env_slot(project))?
                    .context("Which Codex cloud environment? Create one at chatgpt.com/codex, then type its name.")?,
            };
            let bin = backends::which("codex").context("Codex isn't installed on this computer.")?;
            let mut args = vec!["cloud".to_owned(), "exec".into(), "--env".into(), env];
            if let Some(b) = &branch {
                args.extend(["--branch".into(), b.clone()]);
            }
            let out = run(&bin, &args, dir, Some(prompt)).await?;
            first_url(&out).context("Codex didn't return a task link.")?
        }
        "claude" => {
            let bin = backends::which("claude").context("Claude Code isn't installed on this computer.")?;
            let out = run(&bin, &["--cloud".to_owned(), safe_arg(&bin, prompt)], dir, None).await?;
            first_url(&out).unwrap_or_else(|| "https://claude.ai/code".into())
        }
        "cursor" => {
            let key = crate::agent::auth::env(store, "cursor")?
                .into_iter()
                .find(|(k, _)| k == CURSOR_KEY)
                .map(|(_, v)| v)
                .context("Add your Cursor API key first.")?;
            let repo = git(dir, &["remote", "get-url", "origin"])
                .await
                .map(|u| https_url(&u))
                .context("This folder has no GitHub remote.")?;
            let body = cursor_body(prompt, &repo, branch.as_deref());
            let res = reqwest::Client::new()
                .post(CURSOR_API)
                .basic_auth(&key, Some(""))
                .json(&body)
                .timeout(Duration::from_secs(60))
                .send()
                .await
                .map_err(|_| anyhow!("Couldn't reach Cursor."))?;
            let status = res.status();
            let v: Value = res.json().await.unwrap_or_default();
            if !status.is_success() {
                bail!(
                    "Cursor said: {}",
                    v["error"]["message"].as_str().or(v["message"].as_str()).unwrap_or(status.as_str())
                );
            }
            v["target"]["url"].as_str().map_or_else(|| "https://cursor.com/agents".into(), str::to_owned)
        }
        _ => bail!("unknown cloud"),
    };
    Ok(json!({"vendor": vendor, "url": url}))
}

/// Saves the user's Cursor API key in the vault (empty removes it).
pub fn set_cursor_key(store: &Store, key: &str) -> Result<()> {
    let mut saved: std::collections::BTreeMap<String, String> =
        crate::agent::auth::env(store, "cursor")?.into_iter().collect();
    match key.trim() {
        "" => saved.remove(CURSOR_KEY),
        k => saved.insert(CURSOR_KEY.into(), k.to_owned()),
    };
    crate::market::vault::write(store, "agent-env:cursor", &serde_json::to_string(&saved)?)
}

pub fn cursor_body(prompt: &str, repo: &str, branch: Option<&str>) -> Value {
    let mut source = json!({"repository": repo});
    if let Some(b) = branch {
        source["ref"] = json!(b);
    }
    json!({"prompt": {"text": prompt}, "source": source, "target": {"autoCreatePr": true}})
}

/// `git@github.com:o/r.git` → `https://github.com/o/r`.
pub fn https_url(remote: &str) -> String {
    let r = remote.trim();
    let r = r.strip_suffix(".git").unwrap_or(r);
    match r.strip_prefix("git@") {
        Some(rest) => format!("https://{}", rest.replacen(':', "/", 1)),
        None => r.to_owned(),
    }
}

/// `git status --porcelain --branch` says `[ahead N]` or there are changes.
pub fn has_unpushed(status: &str) -> bool {
    let mut lines = status.lines();
    let head = lines.next().unwrap_or_default();
    head.contains("[ahead")
        || head.contains("no upstream")
        || !head.contains("...")
        || lines.any(|l| !l.trim().is_empty())
}

pub fn first_url(text: &str) -> Option<String> {
    text.split_whitespace()
        .find(|w| w.starts_with("https://"))
        .map(|w| w.trim_end_matches(['.', ',', ')', '"', '\'']).to_owned())
}

/// Windows `.cmd` launchers go through cmd.exe, which would read `& | < > ^ % "` itself.
fn safe_arg(bin: &Path, arg: &str) -> String {
    if crate::oscmd::launch_kind(bin) == crate::oscmd::Launch::Cmd {
        arg.chars().map(|c| if "&|<>^%\"!\r\n".contains(c) { ' ' } else { c }).collect()
    } else {
        arg.to_owned()
    }
}

async fn git(dir: &Path, args: &[&str]) -> Result<String> {
    let out = tokio::process::Command::new("git").args(args).current_dir(dir).output().await?;
    if !out.status.success() {
        bail!("git {} failed", args.join(" "));
    }
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_owned())
}

async fn run(bin: &Path, args: &[String], dir: &Path, stdin: Option<&str>) -> Result<String> {
    use tokio::io::AsyncWriteExt as _;
    let mut cmd = if crate::oscmd::launch_kind(bin) == crate::oscmd::Launch::Cmd {
        let mut c = tokio::process::Command::new("cmd.exe");
        c.arg("/D").arg("/C").arg(bin).args(args.iter().map(|a| safe_arg(bin, a)));
        c
    } else {
        let mut c = tokio::process::Command::new(bin);
        c.args(args);
        c
    };
    cmd.current_dir(dir)
        .stdin(if stdin.is_some() { std::process::Stdio::piped() } else { std::process::Stdio::null() })
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true);
    let mut child = cmd.spawn().context("couldn't start the agent's CLI")?;
    if let (Some(text), Some(mut pipe)) = (stdin, child.stdin.take()) {
        pipe.write_all(text.as_bytes()).await?;
    }
    let out = tokio::time::timeout(Duration::from_secs(180), child.wait_with_output())
        .await
        .map_err(|_| anyhow!("The handoff didn't finish within 3 minutes."))??;
    let text = format!("{}\n{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
    if !out.status.success() {
        let last = text.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("it stopped with an error");
        bail!("{last}");
    }
    Ok(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn remotes_become_https() {
        assert_eq!(https_url("git@github.com:Ragul84/Hypurr.git\n"), "https://github.com/Ragul84/Hypurr");
        assert_eq!(https_url("https://github.com/Ragul84/Hypurr.git"), "https://github.com/Ragul84/Hypurr");
    }

    #[test]
    fn unpushed_work_is_noticed() {
        assert!(!has_unpushed("## main...origin/main"));
        assert!(has_unpushed("## main...origin/main [ahead 2]"));
        assert!(has_unpushed("## main...origin/main\n M src/a.rs"));
        assert!(has_unpushed("## feature"));
    }

    #[test]
    fn finds_the_task_link() {
        assert_eq!(
            first_url("Created task\nhttps://chatgpt.com/codex/tasks/task_e_1.\n").as_deref(),
            Some("https://chatgpt.com/codex/tasks/task_e_1")
        );
        assert_eq!(first_url("no link"), None);
    }

    #[test]
    fn cursor_request_matches_the_v0_api() {
        let b = cursor_body("Fix it", "https://github.com/o/r", Some("main"));
        assert_eq!(b["prompt"]["text"], "Fix it");
        assert_eq!(b["source"]["repository"], "https://github.com/o/r");
        assert_eq!(b["source"]["ref"], "main");
        assert_eq!(b["target"]["autoCreatePr"], true);
    }

    #[test]
    fn cmd_launchers_get_no_shell_characters() {
        let p = Path::new("C:/x/codex.cmd");
        let s = safe_arg(p, "fix & del * | \"x\" %PATH%");
        if cfg!(windows) {
            assert!(!s.contains(['&', '|', '"', '%']));
        } else {
            assert_eq!(s, "fix & del * | \"x\" %PATH%");
        }
    }
}
