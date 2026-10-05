//! Finishing a task: a last checkpoint, then (learning mode) the agent explains what it
//! changed and why, then the result goes where the user's team works: a pull request on
//! GitHub, a comment on the Jira ticket, a message to Slack / Teams. The chat gets one
//! "What changed" card (a notice with `data.learning`). Every outside step is best effort:
//! a failure is listed on the card, never lost work.

use super::{Task, TaskStatus, checkpoint_bot, cost, safety, send_first};
use crate::hub::Hub;
use crate::integrations::{Integrations, github, jira, webhooks};
use crate::store::{EntryKind, Lane};
use anyhow::{Result, anyhow, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// What the agent is asked when learning mode is on.
pub const LEARNING_PROMPT: &str = "The task is finished. Learning mode: explain your work to someone who is new to programming, in plain words and short bullet points:\n\
1. What you changed (which files, in one line each).\n\
2. Why (the problem and how the change fixes it).\n\
3. What I should check or test before sharing it.\n\
Don't change any files now. Keep it under 150 words.";

/// What the chat shows for that turn.
pub const LEARNING_SHOWN: &str = "Explain what you changed and why (learning mode)";

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct FileChange {
    pub path: String,
    pub added: u64,
    pub removed: u64,
}

/// Files changed on the task branch since it started (`git diff --numstat`).
pub fn changes(dir: &Path, from: &str) -> Vec<FileChange> {
    let out = std::process::Command::new("git")
        .arg("-C")
        .arg(dir)
        .args(["diff", "--numstat", "--no-renames", from, "HEAD"])
        .output();
    let Ok(out) = out else { return vec![] };
    String::from_utf8_lossy(&out.stdout)
        .lines()
        .filter_map(|l| {
            let mut p = l.splitn(3, '\t');
            let (a, r, path) = (p.next()?, p.next()?, p.next()?);
            Some(FileChange { path: path.to_owned(), added: a.parse().unwrap_or(0), removed: r.parse().unwrap_or(0) })
        })
        .collect()
}

/// A plain summary when the agent didn't give one.
pub fn fallback_summary(files: &[FileChange]) -> String {
    match files.len() {
        0 => "No files were changed.".to_owned(),
        n => {
            let names: Vec<&str> = files.iter().take(4).map(|f| f.path.as_str()).collect();
            let more = if n > 4 { format!(" and {} more", n - 4) } else { String::new() };
            let (a, r): (u64, u64) = files.iter().fold((0, 0), |(a, r), f| (a + f.added, r + f.removed));
            format!(
                "Changed {n} file{}: {}{more} ({a} line{} added, {r} removed).",
                if n == 1 { "" } else { "s" },
                names.join(", "),
                if a == 1 { "" } else { "s" }
            )
        }
    }
}

fn bot_busy(hub: &Hub, bot_id: &str) -> bool {
    matches!(hub.runtime(bot_id).status, crate::hub::BotStatus::Working | crate::hub::BotStatus::NeedsInput)
}

/// `finishTask {taskId, openPr?, notify?, learning?}`. Unset options follow the settings.
pub async fn finish(hub: &Arc<Hub>, task_id: &str, b: &Value) -> Result<Value> {
    let task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    if task.status != TaskStatus::Active {
        return Ok(json!({"task": task.public()}));
    }
    if bot_busy(hub, &task.bot_id) {
        bail!("the agent is still working; wait for it or stop it first");
    }
    let cfg = Integrations::load(&hub.store);
    let open_pr = b["openPr"].as_bool().unwrap_or(cfg.auto_pr);
    let notify = b["notify"].as_bool().unwrap_or(cfg.notify);
    let learning = b["learning"].as_bool().unwrap_or(cfg.learning);
    {
        let (h, bot) = (hub.clone(), task.bot_id.clone());
        tokio::task::spawn_blocking(move || checkpoint_bot(&h, &bot, "Finished")).await??;
    }
    let mut task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    let bot_alive = hub.store.bot(&task.bot_id)?.is_some_and(|r| !r.deleted);
    let ask = learning && bot_alive;
    task.status = TaskStatus::Finishing;
    task.extra.insert("finishing".into(), json!({"openPr": open_pr, "notify": notify, "awaitingSummary": ask}));
    task.save(&hub.store)?;
    hub.emit_bot(&task.bot_id);
    if ask {
        send_first(hub, &task.bot_id, LEARNING_SHOWN, LEARNING_PROMPT, &[])?;
    } else {
        let (h, id) = (hub.clone(), task.id.clone());
        tokio::spawn(async move { complete(&h, &id, None).await });
    }
    Ok(json!({"task": task.public()}))
}

/// The learning-mode turn ended (or there wasn't one): wrap the task up.
pub fn after_summary_turn(hub: &Arc<Hub>, task: &Task, final_text: Option<&str>) -> bool {
    if task.status != TaskStatus::Finishing || task.extra["finishing"]["awaitingSummary"] != true {
        return false;
    }
    let (h, id, text) = (hub.clone(), task.id.clone(), final_text.map(str::to_owned));
    tokio::spawn(async move { complete(&h, &id, text).await });
    true
}

fn dir_for(task: &Task) -> PathBuf {
    task.worktree_path().unwrap_or_else(|| PathBuf::from(&task.project))
}

async fn complete(hub: &Arc<Hub>, task_id: &str, summary: Option<String>) {
    if let Err(e) = complete_inner(hub, task_id, summary).await {
        tracing::warn!(task = task_id, error = format!("{e:#}"), "finishing the task failed");
        if let Some(mut t) = Task::load(&hub.store, task_id) {
            t.status = TaskStatus::Finished;
            let _ = t.save(&hub.store);
            hub.emit_bot(&t.bot_id);
        }
    }
}

fn pr_body(task: &Task, summary: &str, files: &[FileChange]) -> String {
    let mut body = format!("{summary}\n\n");
    if let Some(issue) = task.issue()
        && issue.source == "github"
    {
        let _ = write!(body, "Closes {}\n\n", issue.key);
    }
    if let Some(issue) = task.issue().filter(|i| i.source == "jira") {
        let _ = write!(body, "Jira: [{}]({})\n\n", issue.key, issue.url);
    }
    let _ = write!(
        body,
        "---\nMade with Hypurr from the task “{}”. {} file(s) changed, {} checkpoint(s) on `{}`.",
        task.title,
        files.len(),
        task.checkpoints.len(),
        task.branch.as_deref().unwrap_or_default()
    );
    body
}

async fn complete_inner(hub: &Arc<Hub>, task_id: &str, summary: Option<String>) -> Result<()> {
    let task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    let cfg = Integrations::load(&hub.store);
    let open_pr = task.extra["finishing"]["openPr"] == true;
    let notify = task.extra["finishing"]["notify"] == true;
    let files = match task.checkpoints.first().map(|c| c.id.clone()) {
        Some(start) if task.branch.is_some() => {
            let dir = dir_for(&task);
            tokio::task::spawn_blocking(move || changes(&dir, &start)).await?
        }
        _ => vec![],
    };
    let summary =
        summary.map(|s| s.trim().to_owned()).filter(|s| !s.is_empty()).unwrap_or_else(|| fallback_summary(&files));
    let mut errors: Vec<String> = vec![];
    let mut posted: Vec<&str> = vec![];
    let mut pr: Option<Value> = None;
    let protected = super::SafetySettings::load(&hub.store).protected_branches;

    // Pull request.
    if open_pr
        && !files.is_empty()
        && let (Some(branch), Some(base)) = (task.branch.clone(), task.base.clone())
    {
        match (github::Client::new(&cfg.github), github::repo_of(Path::new(&task.project))) {
            (Some(gh), Some(repo)) if !safety::is_protected(&branch, &protected) => {
                let (dir, b2, token) = (dir_for(&task), branch.clone(), gh.token().map(str::to_owned));
                let pushed =
                    tokio::task::spawn_blocking(move || github::push_branch(&dir, &b2, token.as_deref())).await?;
                match pushed {
                    Err(e) => errors.push(format!("{e:#}")),
                    Ok(()) => {
                        match gh.open_pr(&repo, &branch, &base, &task.title, &pr_body(&task, &summary, &files)).await {
                            Ok((number, url)) => pr = Some(json!({"number": number, "url": url, "repo": repo})),
                            Err(e) => errors.push(format!("Pull request: {e:#}")),
                        }
                    }
                }
            }
            (None, Some(_)) => errors.push("No pull request: GitHub isn't set up in Work tools.".into()),
            _ => {}
        }
    }
    let pr_url = pr.as_ref().and_then(|p| p["url"].as_str()).map(str::to_owned);
    let usage = task.usage();
    let cost_label = (!usage.is_empty()).then(|| cost::label(usage.cost, usage.estimated));

    // Jira comment.
    if let Some(issue) = task.issue().filter(|i| i.source == "jira")
        && cfg.jira_ready()
    {
        let text = format!("Hypurr finished “{}”.\n{summary}", task.title);
        match jira::comment(&cfg.jira, &issue.key, &text, pr_url.as_deref()).await {
            Ok(()) => posted.push("jira"),
            Err(e) => errors.push(format!("{e:#}")),
        }
    }

    // Slack / Teams.
    if notify {
        let mut facts = vec![("Project".to_owned(), task.project_name.clone())];
        if let Some(b) = &task.branch {
            facts.push(("Branch".into(), b.clone()));
        }
        if !files.is_empty() {
            facts.push(("Files changed".into(), files.len().to_string()));
        }
        if let Some(c) = &cost_label {
            facts.push(("Cost".into(), c.clone()));
        }
        let msg = webhooks::Message {
            title: format!("Done: {}", task.title),
            text: crate::agent::acp::truncate(&summary, 1500),
            url: pr_url.clone(),
            facts,
        };
        for (kind, url) in [("slack", &cfg.slack.url), ("teams", &cfg.teams.url)] {
            if url.is_empty() {
                continue;
            }
            match webhooks::post(kind, url, &msg).await {
                Ok(()) => posted.push(kind),
                Err(e) => errors.push(format!("{e:#}")),
            }
        }
    }

    let (added, removed) = files.iter().fold((0, 0), |(a, r), f| (a + f.added, r + f.removed));
    let learning = json!({
        "taskId": task.id,
        "title": task.title,
        "summary": summary,
        "files": files,
        "added": added,
        "removed": removed,
        "branch": task.branch,
        "base": task.base,
        "pr": pr,
        "cost": (!usage.is_empty()).then(|| usage.public()),
        "posted": posted,
        "errors": errors,
    });
    let mut text = format!("What changed: {}", fallback_summary(&files));
    if let Some(u) = &pr_url {
        let _ = write!(text, " Pull request: {u}");
    }
    if let Some(c) = &cost_label {
        let _ = write!(text, " Cost: {c}.");
    }
    for e in &errors {
        let _ = write!(text, "\n{e}");
    }
    let mut task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    task.status = TaskStatus::Finished;
    task.extra.remove("finishing");
    task.extra.insert("summary".into(), json!(summary));
    task.extra.insert("changes".into(), json!({"files": files.len(), "added": added, "removed": removed}));
    if let Some(p) = pr {
        task.extra.insert("pr".into(), p);
    }
    task.save(&hub.store)?;
    hub.add_entry(
        &Lane::main(&task.bot_id),
        EntryKind::Notice,
        hub.store.max_turn(&task.bot_id),
        &json!({"text": text, "style": if errors.is_empty() { "info" } else { "error" }, "learning": learning}),
    );
    hub.emit_bot(&task.bot_id);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn summaries_read_plainly() {
        let f = |p: &str, a, r| FileChange { path: p.into(), added: a, removed: r };
        assert_eq!(fallback_summary(&[]), "No files were changed.");
        assert_eq!(fallback_summary(&[f("a.rs", 1, 0)]), "Changed 1 file: a.rs (1 line added, 0 removed).");
        let many: Vec<FileChange> = (0..6).map(|i| f(&format!("f{i}"), 2, 1)).collect();
        assert_eq!(fallback_summary(&many), "Changed 6 files: f0, f1, f2, f3 and 2 more (12 lines added, 6 removed).");
    }
}
