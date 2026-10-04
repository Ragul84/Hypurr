//! Beginner tasks: the user describes a goal (optionally from a template), the host
//! routes it to a project and agent, isolates it on its own git branch and worktree,
//! saves checkpoints as the agent works, and can roll back to any checkpoint.
//! A task is a bot (its chat is the task's conversation) plus a row in `tasks`;
//! clients see it as the bot's `task` field. Spec: docs/features/tasks-and-safety.md.

pub mod cost;
pub mod finish;
pub mod risk;
pub mod router;
pub mod safety;
pub mod templates;

use crate::hub::Hub;
use crate::store::{BotConfig, EntryKind, Lane, ReadScope, Store, now_ms};
use anyhow::{Context as _, Result, anyhow, bail};
use router::{Agent, Project};
use safety::Checkpoint;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// Safety-net settings (kv `safety`).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct SafetySettings {
    /// Branches agents may never push to or work on (`*` = prefix).
    pub protected_branches: Vec<String>,
    /// Refuse pushes to protected branches and force pushes without asking.
    pub block_protected: bool,
    /// High-risk requests always ask, even for bots set to approve automatically.
    pub always_ask_high: bool,
    /// Tasks in folders that aren't git projects are refused (no checkpoints possible).
    pub require_git: bool,
}

impl Default for SafetySettings {
    fn default() -> Self {
        Self {
            protected_branches: safety::DEFAULT_PROTECTED.iter().map(|s| (*s).to_owned()).collect(),
            block_protected: true,
            always_ask_high: true,
            require_git: false,
        }
    }
}

impl SafetySettings {
    pub fn load(store: &Store) -> Self {
        store.kv_get("safety").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
    }

    pub fn save(&self, store: &Store) -> Result<()> {
        store.kv_set("safety", &serde_json::to_string(self)?)
    }
}

/// How a task is protected (wire values `worktree` / `none`).
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub enum SafetyMode {
    /// Own branch + worktree + checkpoints.
    #[default]
    Worktree,
    /// Not a git project: runs in the folder itself, no checkpoints.
    None,
}

/// Wire values `active` / `finishing` / `finished`.
#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub enum TaskStatus {
    #[default]
    Active,
    /// Writing the learning summary, opening the PR, posting the result.
    Finishing,
    Finished,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Task {
    pub id: String,
    pub bot_id: String,
    pub goal: String,
    pub title: String,
    #[serde(default)]
    pub template: Option<String>,
    pub project: String,
    pub project_name: String,
    pub backend: String,
    #[serde(default)]
    pub safety: SafetyMode,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default)]
    pub base: Option<String>,
    #[serde(default)]
    pub worktree: Option<String>,
    #[serde(default)]
    pub status: TaskStatus,
    #[serde(default)]
    pub checkpoints: Vec<Checkpoint>,
    pub created_at: i64,
    pub updated_at: i64,
    /// Later stages add fields (summary, cost, PR); kept as-is.
    #[serde(flatten)]
    pub extra: serde_json::Map<String, Value>,
}

/// The newest checkpoints a client sees on the bot.
const CHECKPOINTS_ON_BOT: usize = 30;

impl Task {
    pub fn load(store: &Store, id: &str) -> Option<Self> {
        store.task_data(id).and_then(|v| serde_json::from_value(v).ok())
    }

    pub fn for_bot(store: &Store, bot_id: &str) -> Option<Self> {
        store.task_data_for_bot(bot_id).and_then(|v| serde_json::from_value(v).ok())
    }

    pub fn save(&mut self, store: &Store) -> Result<()> {
        self.updated_at = now_ms();
        store.save_task(&self.id, &self.bot_id, &serde_json::to_value(&*self)?, self.created_at)
    }

    /// What clients get as the bot's `task`.
    pub fn public(&self) -> Value {
        let mut v = serde_json::to_value(self).unwrap_or(Value::Null);
        let n = self.checkpoints.len();
        v["checkpoints"] = json!(self.checkpoints[n.saturating_sub(CHECKPOINTS_ON_BOT)..]);
        if let Some(o) = v.as_object_mut() {
            o.remove("finishing");
            if self.extra.contains_key("usage") {
                o.insert("usage".into(), self.usage().public());
            }
        }
        v
    }

    /// The GitHub issue or Jira ticket the task started from.
    pub fn issue(&self) -> Option<crate::integrations::Issue> {
        self.extra.get("issue").and_then(|v| serde_json::from_value(v.clone()).ok())
    }

    pub fn usage(&self) -> cost::Usage {
        self.extra.get("usage").and_then(|v| serde_json::from_value(v.clone()).ok()).unwrap_or_default()
    }

    fn set_usage(&mut self, u: &cost::Usage) {
        self.extra.insert("usage".into(), serde_json::to_value(u).unwrap_or(Value::Null));
    }

    fn worktree_path(&self) -> Option<PathBuf> {
        self.worktree.as_deref().map(PathBuf::from).filter(|p| p.is_dir())
    }

    fn push_checkpoints(&mut self, cps: impl IntoIterator<Item = Checkpoint>) {
        for cp in cps {
            if !self.checkpoints.iter().any(|c| c.id == cp.id) {
                self.checkpoints.push(cp);
            }
        }
    }
}

pub fn worktrees_dir() -> PathBuf {
    crate::service::data_dir().join("worktrees")
}

/// Projects: saved ones (kv `projects`) plus every git project a bot works in.
pub fn projects(store: &Store) -> Vec<Project> {
    let mut out: Vec<Project> =
        store.kv_get("projects").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
    for p in &mut out {
        p.saved = true;
    }
    let wt = worktrees_dir();
    for row in store.bots().unwrap_or_default() {
        let cfg = row.config;
        if row.deleted || cfg.is_group() || cfg.cwd.is_empty() || crate::agent::workspace::is_managed(&cfg) {
            continue;
        }
        let cwd = Path::new(&cfg.cwd);
        if cwd.starts_with(&wt) {
            continue;
        }
        let Some(root) = safety::repo_root(cwd) else { continue };
        let path = root.to_string_lossy().into_owned();
        if !out.iter().any(|p| p.path == path) {
            let name = root.file_name().map_or_else(|| path.clone(), |n| n.to_string_lossy().into_owned());
            out.push(Project { name, path, keywords: vec![], saved: false });
        }
    }
    out
}

pub fn save_project(store: &Store, p: &Value) -> Result<Project> {
    let path =
        p["path"].as_str().map(str::trim).filter(|s| !s.is_empty()).ok_or_else(|| anyhow!("`path` is required"))?;
    let dir = std::fs::canonicalize(path).with_context(|| format!("folder not found: {path}"))?;
    if !dir.is_dir() {
        bail!("not a folder: {path}");
    }
    let path = dir.to_string_lossy().into_owned();
    let name = p["name"].as_str().map(str::trim).filter(|s| !s.is_empty()).map_or_else(
        || dir.file_name().map_or_else(|| path.clone(), |n| n.to_string_lossy().into_owned()),
        str::to_owned,
    );
    let keywords =
        p["keywords"].as_array().into_iter().flatten().filter_map(Value::as_str).map(str::to_owned).collect();
    let project = Project { name, path, keywords, saved: true };
    let mut list: Vec<Project> =
        store.kv_get("projects").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
    list.retain(|x| x.path != project.path);
    list.push(project.clone());
    store.kv_set("projects", &serde_json::to_string(&list)?)?;
    Ok(project)
}

pub fn remove_project(store: &Store, path: &str) -> Result<()> {
    let mut list: Vec<Project> =
        store.kv_get("projects").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
    list.retain(|x| x.path != path);
    store.kv_set("projects", &serde_json::to_string(&list)?)
}

/// Agents the router may use: the ones installed on this computer (the user has set them
/// up), or, when none are, the curated harnesses Hypurr can install. Never an unknown
/// registry agent: a beginner shouldn't be routed to something they've never heard of.
pub fn agents() -> Vec<Agent> {
    pick_agents(&crate::agent::backends::list())
}

/// The agents the team's rules allow (`admin::Policies::allowed_agents`).
pub fn allowed_agents(store: &Store) -> Vec<Agent> {
    let p = crate::admin::Policies::load(store);
    agents().into_iter().filter(|a| p.agent_allowed(&a.id)).collect()
}

fn pick_agents(all: &[Value]) -> Vec<Agent> {
    let to_agent = |b: &Value| Some(Agent { id: b["id"].as_str()?.to_owned(), name: b["name"].as_str()?.to_owned() });
    let installed: Vec<Agent> =
        all.iter().filter(|b| b["installed"] == true && b["available"] == true).filter_map(to_agent).collect();
    if !installed.is_empty() {
        return installed;
    }
    all.iter().filter(|b| b["curated"] == true && b["available"] == true).filter_map(to_agent).collect()
}

fn last_project(store: &Store) -> Option<String> {
    store.tasks_data(1).ok()?.into_iter().next()?["project"].as_str().map(str::to_owned)
}

/// Everything the New task screen needs in one call.
pub fn setup(store: &Store) -> Value {
    json!({
        "templates": templates::all(store),
        "projects": projects(store),
        "agents": allowed_agents(store),
        "safety": SafetySettings::load(store),
        "integrations": crate::integrations::Integrations::load(store).public(),
    })
}

pub fn route(store: &Store, goal: &str, template: Option<&str>) -> Value {
    let read_only = template.and_then(|t| templates::get(store, t)).is_some_and(|t| t.read_only);
    let r = router::route(goal, read_only, &projects(store), &allowed_agents(store), last_project(store).as_deref());
    serde_json::to_value(r).unwrap_or(Value::Null)
}

fn title_for(goal: &str, template: Option<&templates::Template>) -> String {
    let goal = goal.split_whitespace().collect::<Vec<_>>().join(" ");
    let t = match (template, goal.is_empty()) {
        (Some(t), true) => t.title.clone(),
        (Some(t), false) if t.id != "fix-error" && t.id != "custom" => format!("{}: {goal}", t.title),
        _ => goal,
    };
    let t = crate::agent::acp::truncate(&t, 48);
    if t.is_empty() { "New task".into() } else { t }
}

/// The rules every task's agent gets with the first message.
/// The ticket's own words, for the agent.
fn issue_context(task: &Task) -> String {
    let Some(i) = task.issue() else { return String::new() };
    let source = if i.source == "jira" { "Jira ticket" } else { "GitHub issue" };
    let mut s = format!("\n\n[{source} {}: {}]({})", i.key, i.title, i.url);
    if !i.body.trim().is_empty() {
        let _ = write!(s, "\n{}", i.body.trim());
    }
    s
}

fn rules(task: &Task, read_only: bool) -> String {
    let mut r = String::from("\n\n---\nWorking rules from Hypurr (the user's safety net):\n");
    match (&task.branch, &task.worktree) {
        (Some(b), Some(w)) => {
            let _ = writeln!(
                r,
                "- You're working on the branch `{b}` in its own folder ({w}). Stay on this branch. Don't switch to, merge into or push to main, master or any other protected branch."
            );
        }
        _ => r.push_str("- This folder has no git safety net, so be extra careful: don't delete files unless asked.\n"),
    }
    if read_only {
        r.push_str("- Don't change any files for this task.\n");
    }
    r.push_str("- Don't push, publish, deploy or touch files outside this folder unless the user asks.\n");
    r.push_str("- Hypurr saves checkpoints for you; you don't need to commit.\n");
    r.push_str("- When you finish, end with a short plain-language summary of what you changed and why, for someone new to programming.");
    r
}

/// Starts a task: route → worktree → bot → first message. Returns `{task, bot}`.
pub async fn start(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let store = &hub.store;
    let goal = b["goal"].as_str().unwrap_or_default().trim().to_owned();
    let template = match b["template"].as_str().filter(|s| !s.is_empty()) {
        Some(id) => Some(templates::get(store, id).ok_or_else(|| anyhow!("unknown template"))?),
        None => None,
    };
    let input = b["input"].as_str().unwrap_or_default().to_owned();
    let issue: Option<crate::integrations::Issue> =
        b.get("issue").filter(|v| v.is_object()).and_then(|v| serde_json::from_value(v.clone()).ok());
    let goal = match &issue {
        Some(i) if goal.is_empty() => format!("{} {}", i.key, i.title).trim().to_owned(),
        _ => goal,
    };
    if goal.is_empty() && template.is_none() {
        bail!("describe what you need, or pick a template");
    }
    if let Some(t) = &template
        && t.id == "fix-error"
        && input.trim().is_empty()
        && goal.is_empty()
        && b["attachments"].as_array().is_none_or(Vec::is_empty)
    {
        bail!("paste the error message");
    }
    let read_only = template.as_ref().is_some_and(|t| t.read_only);
    // Route what the user didn't pick.
    let picked_project = b["project"]
        .as_str()
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .or_else(|| issue.as_ref().and_then(|i| i.project.clone()));
    let picked_backend = b["backend"].as_str().filter(|s| !s.is_empty()).map(str::to_owned);
    let command = b["command"].as_str().filter(|s| !s.trim().is_empty()).map(str::to_owned);
    let (project, backend) = {
        let store_goal = format!("{goal} {input}");
        let projects_now = {
            let hub = hub.clone();
            tokio::task::spawn_blocking(move || projects(&hub.store)).await?
        };
        let r = router::route(
            &store_goal,
            read_only,
            &projects_now,
            &allowed_agents(store),
            last_project(store).as_deref(),
        );
        let project = match picked_project {
            Some(path) => projects_now.into_iter().find(|p| p.path == path).unwrap_or_else(|| Project {
                name: Path::new(&path).file_name().map_or_else(|| path.clone(), |n| n.to_string_lossy().into_owned()),
                path,
                keywords: vec![],
                saved: false,
            }),
            None => r.project.ok_or_else(|| anyhow!("pick the project this task is about"))?,
        };
        let backend = picked_backend.or_else(|| r.agent.map(|a| a.id)).ok_or_else(|| {
            if crate::admin::Policies::load(store).allowed_agents.is_empty() {
                anyhow!("no coding agent is installed on this computer")
            } else {
                anyhow!("none of the agents your team allows is installed on this computer")
            }
        })?;
        (project, backend)
    };
    if !Path::new(&project.path).is_dir() {
        bail!("project folder not found: {}", project.path);
    }
    let settings = SafetySettings::load(store);
    let id = uuid::Uuid::new_v4().to_string();
    let title = title_for(&goal, template.as_ref());
    let now = now_ms();
    let mut task = Task {
        id: id.clone(),
        bot_id: String::new(),
        goal: goal.clone(),
        title: title.clone(),
        template: template.as_ref().map(|t| t.id.clone()),
        project: project.path.clone(),
        project_name: project.name.clone(),
        backend: backend.clone(),
        safety: SafetyMode::Worktree,
        branch: None,
        base: None,
        worktree: None,
        status: TaskStatus::Active,
        checkpoints: vec![],
        created_at: now,
        updated_at: now,
        extra: serde_json::Map::new(),
    };
    if let Some(i) = &issue {
        task.extra.insert("issue".into(), serde_json::to_value(i)?);
    }
    // Set by `admin::authorize`, never taken from the client.
    if b["startedBy"].is_object() {
        task.extra.insert("startedBy".into(), b["startedBy"].clone());
    }
    // Isolate on a branch + worktree when the project is a git checkout.
    let isolated = {
        let (repo, id, title, protected) =
            (PathBuf::from(&project.path), id.clone(), title.clone(), settings.protected_branches.clone());
        tokio::task::spawn_blocking(move || {
            if safety::repo_root(&repo).is_none() {
                return Ok(None);
            }
            safety::isolate(&repo, &worktrees_dir(), &id, &title, &protected).map(Some)
        })
        .await??
    };
    let cwd = match isolated {
        Some(iso) => {
            task.branch = Some(iso.branch);
            task.base = Some(iso.base);
            task.worktree = Some(iso.worktree.to_string_lossy().into_owned());
            task.checkpoints.push(iso.start);
            iso.worktree.to_string_lossy().into_owned()
        }
        None if settings.require_git => bail!("{} isn't a git project, and the safety net requires one", project.name),
        None => {
            task.safety = SafetyMode::None;
            project.path.clone()
        }
    };
    let cfg = BotConfig {
        id: String::new(),
        kind: crate::store::BotKind::default(),
        members: vec![],
        name: title.clone(),
        description: goal.clone(),
        avatar_color: ["blue", "purple", "teal", "green", "orange", "pink"]
            [usize::try_from(now.unsigned_abs() % 6).unwrap_or(0)]
        .into(),
        avatar_shape: "squircle".into(),
        backend,
        command,
        cwd,
        permission: crate::store::Permission::Ask,
        model: None,
        pinned: false,
        hidden: false,
        notify: None,
        connectors: vec![],
        skills: vec![],
        computer: false,
        auto_name: false,
        created_at: 0,
    };
    let created = {
        let hub = hub.clone();
        tokio::task::spawn_blocking(move || hub.create_bot(cfg)).await?
    };
    let bot = match created {
        Ok(bot) => bot,
        Err(e) => {
            // Don't leave an orphan worktree behind.
            if let Some(w) = task.worktree.clone() {
                let repo = PathBuf::from(&task.project);
                let _ = tokio::task::spawn_blocking(move || safety::remove(&repo, Path::new(&w))).await;
            }
            return Err(e);
        }
    };
    let bot_id = bot["id"].as_str().ok_or_else(|| anyhow!("bot wasn't created"))?.to_owned();
    task.bot_id.clone_from(&bot_id);
    task.save(store)?;
    // What the chat shows vs. what the agent gets.
    let shown = match &template {
        Some(t) if goal.is_empty() => t.title.clone(),
        Some(t) => format!("{}: {goal}", t.title),
        None => goal.clone(),
    };
    let shown = if input.trim().is_empty() { shown } else { format!("{shown}\n\n{}", input.trim()) };
    let body = match &template {
        Some(t) => templates::fill(t, &goal, &input),
        None => goal.clone(),
    };
    let uploads: Vec<String> =
        b["attachments"].as_array().into_iter().flatten().filter_map(Value::as_str).map(str::to_owned).collect();
    if let Some(draft) = b["draftId"].as_str().filter(|_| !uploads.is_empty()) {
        let draft = crate::chat::uploads::draft_root(draft)?;
        let root = crate::chat::uploads::root(&hub.store.bot(&bot_id)?.ok_or_else(|| anyhow!("unknown bot"))?.config);
        let ids = uploads.clone();
        tokio::task::spawn_blocking(move || crate::chat::uploads::adopt_drafts(&draft, &root, &ids)).await??;
    }
    let prompt = format!("{body}{}{}", issue_context(&task), rules(&task, read_only));
    send_first(hub, &bot_id, &shown, &prompt, &uploads)?;
    hub.emit_bot(&bot_id);
    Ok(json!({"task": task.public(), "bot": hub.store.bot(&bot_id)?.map(|r| hub.bot_json(&r))}))
}

/// The task's first message: the chat shows `shown`, the agent gets `prompt`.
fn send_first(hub: &Arc<Hub>, bot_id: &str, shown: &str, prompt: &str, uploads: &[String]) -> Result<()> {
    let row = hub.store.bot(bot_id)?.ok_or_else(|| anyhow!("unknown bot"))?;
    let (mut paths, mut attachments) = (Vec::new(), Vec::new());
    if !uploads.is_empty() {
        let root = crate::chat::uploads::root(&row.config);
        for id in uploads {
            let (path, meta) = crate::chat::uploads::resolve(&root, id)?;
            paths.push(path);
            attachments.push(meta);
        }
    }
    let prompt = if paths.is_empty() {
        prompt.to_owned()
    } else {
        format!("{prompt}{}", crate::chat::uploads::prompt_suffix(&paths))
    };
    let lane = Lane::main(bot_id);
    let turn = hub.store.max_turn(bot_id) + 1;
    let e = hub
        .add_entry(
            &lane,
            EntryKind::User,
            turn,
            &json!({"text": shown, "status": "queued", "attachments": attachments}),
        )
        .ok_or_else(|| anyhow!("couldn't save the message"))?;
    let _ = hub.mark_read(bot_id, ReadScope::Chat);
    hub.send_cmd(bot_id, crate::agent::bot::Cmd::Send { lane, entry_id: e.id, text: prompt })
}

pub fn list(store: &Store) -> Result<Value> {
    let tasks: Vec<Value> = store
        .tasks_data(200)?
        .into_iter()
        .filter_map(|v| serde_json::from_value::<Task>(v).ok())
        .map(|t| t.public())
        .collect();
    Ok(json!({"tasks": tasks}))
}

/// Saves a checkpoint for a task bot now (blocking). Returns the checkpoint the
/// worktree is at afterwards (a new one, or the latest when nothing changed).
pub fn checkpoint_bot(hub: &Hub, bot_id: &str, label: &str) -> Result<Option<Checkpoint>> {
    let Some(mut task) = Task::for_bot(&hub.store, bot_id) else { return Ok(None) };
    let (Some(wt), Some(branch)) = (task.worktree_path(), task.branch.clone()) else { return Ok(None) };
    if task.status != TaskStatus::Active {
        return Ok(None);
    }
    match safety::checkpoint(&wt, &branch, label)? {
        Some(cp) => {
            task.push_checkpoints([cp.clone()]);
            task.save(&hub.store)?;
            hub.emit_bot(bot_id);
            Ok(Some(cp))
        }
        None => Ok(task.checkpoints.last().cloned()),
    }
}

/// A turn ended: record its usage, wrap up a finishing task, or save a checkpoint off
/// the actor's thread.
pub fn after_turn(hub: &Arc<Hub>, bot_id: &str, final_text: Option<&str>, usage: Option<&Value>) {
    let Some(mut task) = Task::for_bot(&hub.store, bot_id) else { return };
    if let Some(u) = usage.filter(|u| u.is_object()) {
        let mut acc = task.usage();
        acc.add_turn(u, cost::rate_for(&task.backend));
        task.set_usage(&acc);
        if let Err(e) = task.save(&hub.store) {
            tracing::warn!(task = task.id, error = format!("{e:#}"), "couldn't record usage");
        }
        crate::admin::after_usage(hub, &mut task);
        hub.emit_bot(bot_id);
    }
    if finish::after_summary_turn(hub, &task, final_text) || task.status != TaskStatus::Active {
        return;
    }
    let (hub, bot_id) = (hub.clone(), bot_id.to_owned());
    tokio::task::spawn_blocking(move || {
        let n = Task::for_bot(&hub.store, &bot_id).map_or(0, |t| t.checkpoints.len());
        if let Err(e) = checkpoint_bot(&hub, &bot_id, &format!("After step {n}")) {
            tracing::warn!(bot = bot_id, error = format!("{e:#}"), "task checkpoint failed");
            let _ = hub.add_entry(
                &Lane::main(&bot_id),
                EntryKind::Notice,
                hub.store.max_turn(&bot_id),
                &json!({"text": format!("Couldn't save a checkpoint: {e:#}"), "style": "error"}),
            );
        }
    });
}

pub async fn manual_checkpoint(hub: &Arc<Hub>, task_id: &str, label: &str) -> Result<Value> {
    let task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    let label = if label.trim().is_empty() { "Saved by you".to_owned() } else { label.trim().to_owned() };
    let h = hub.clone();
    let cp = tokio::task::spawn_blocking(move || checkpoint_bot(&h, &task.bot_id, &label)).await??;
    Ok(json!({"checkpoint": cp, "task": Task::load(&hub.store, task_id).map(|t| t.public())}))
}

/// One-tap rollback to a checkpoint of this task. Refused while the agent is working.
pub async fn rollback(hub: &Arc<Hub>, task_id: &str, checkpoint_id: &str) -> Result<Value> {
    let mut task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    let target = task
        .checkpoints
        .iter()
        .find(|c| c.id == checkpoint_id)
        .cloned()
        .ok_or_else(|| anyhow!("unknown checkpoint"))?;
    let (Some(wt), Some(branch)) = (task.worktree_path(), task.branch.clone()) else {
        bail!("this task has no safety net to go back with");
    };
    if hub.runtime(&task.bot_id).status == crate::hub::BotStatus::Working {
        bail!("stop the agent before going back");
    }
    let label = target.label.clone();
    let added = tokio::task::spawn_blocking(move || safety::rollback(&wt, &branch, &target.id, &label)).await??;
    task.push_checkpoints(added);
    task.save(&hub.store)?;
    let text = format!(
        "Went back to the checkpoint “{}”. Nothing is lost: “Before going back” keeps what was there.",
        target_label(&task, checkpoint_id)
    );
    hub.add_entry(
        &Lane::main(&task.bot_id),
        EntryKind::Notice,
        hub.store.max_turn(&task.bot_id),
        &json!({"text": text, "style": "info"}),
    );
    hub.emit_bot(&task.bot_id);
    Ok(json!({"task": task.public()}))
}

fn target_label(task: &Task, id: &str) -> String {
    task.checkpoints.iter().find(|c| c.id == id).map_or_else(String::new, |c| c.label.clone())
}

/// `usage_update` from a task's agent: the session's cost so far, when it reports one.
pub fn usage_update(hub: &Hub, bot_id: &str, u: &Value) {
    let Some(amount) = u["cost"]["amount"].as_f64() else { return };
    let Some(mut task) = Task::for_bot(&hub.store, bot_id) else { return };
    let mut acc = task.usage();
    acc.set_session_cost(amount, u["cost"]["currency"].as_str().unwrap_or("USD"), cost::rate_for(&task.backend));
    task.set_usage(&acc);
    if task.save(&hub.store).is_ok() {
        crate::admin::after_usage(hub, &mut task);
        hub.emit_bot(bot_id);
    }
}

/// What all tasks started today (UTC) cost so far, in USD.
pub fn spent_today(store: &Store) -> f64 {
    let now = now_ms();
    let start = now - now.rem_euclid(86_400_000);
    let total: f64 = store
        .tasks_data(1000)
        .unwrap_or_default()
        .into_iter()
        .filter_map(|v| serde_json::from_value::<Task>(v).ok())
        .filter(|t| t.created_at >= start)
        .map(|t| t.usage().cost)
        .sum();
    // An empty float sum is -0.0.
    (total * 10_000.0).round() / 10_000.0 + 0.0
}

/// The cost tracker: every task's cost, plus totals (all time, last 7 days, today).
pub fn costs(store: &Store) -> Result<Value> {
    let day = 86_400_000;
    let now = now_ms();
    let today_start = now - now.rem_euclid(day);
    let mut rows = vec![];
    let (mut all, mut week, mut today, mut tokens, mut estimated) = (0.0_f64, 0.0_f64, 0.0_f64, 0_u64, false);
    for t in store.tasks_data(1000)?.into_iter().filter_map(|v| serde_json::from_value::<Task>(v).ok()) {
        let u = t.usage();
        if u.is_empty() {
            continue;
        }
        all += u.cost;
        tokens += u.total_tokens;
        estimated |= u.estimated;
        if t.created_at >= now - 7 * day {
            week += u.cost;
        }
        if t.created_at >= today_start {
            today += u.cost;
        }
        rows.push(json!({"taskId": t.id, "botId": t.bot_id, "title": t.title, "createdAt": t.created_at, "usage": u.public()}));
    }
    let r = |x: f64| (x * 10_000.0).round() / 10_000.0;
    Ok(json!({
        "tasks": rows,
        "total": {"cost": r(all), "tokens": tokens, "tasks": rows.len(), "estimated": estimated},
        "week": r(week),
        "today": r(today),
        "currency": "USD",
    }))
}

/// The task's bot was deleted: remove its folder (the branch and checkpoints stay).
pub fn bot_deleted(hub: &Hub, bot_id: &str) {
    let Some(mut task) = Task::for_bot(&hub.store, bot_id) else { return };
    if let Some(wt) = task.worktree.clone() {
        let repo = PathBuf::from(&task.project);
        if let Err(e) = safety::remove(&repo, Path::new(&wt)) {
            tracing::warn!(task = task.id, error = format!("{e:#}"), "couldn't remove task folder");
        }
    }
    task.status = TaskStatus::Finished;
    task.worktree = None;
    let _ = task.save(&hub.store);
}

/// The approval card's explanation fields for a permission request.
pub fn assess_permission(store: &Store, data: &Value) -> risk::Assessment {
    let settings = SafetySettings::load(store);
    let paths: Vec<String> =
        data["diffs"].as_array().into_iter().flatten().filter_map(|d| d["path"].as_str().map(str::to_owned)).collect();
    risk::assess(&risk::Request {
        tool_kind: data["toolKind"].as_str().unwrap_or("other"),
        title: data["title"].as_str().unwrap_or_default(),
        command: data["command"].as_str(),
        paths: &paths,
        cwd: data["cwd"].as_str().unwrap_or_default(),
        protected: &settings.protected_branches,
        block_protected: settings.block_protected,
    })
}

pub fn set_safety(store: &Store, b: &Value) -> Result<SafetySettings> {
    let mut s = SafetySettings::load(store);
    if let Some(list) = b["protectedBranches"].as_array() {
        s.protected_branches =
            list.iter().filter_map(Value::as_str).map(str::trim).filter(|x| !x.is_empty()).map(str::to_owned).collect();
    }
    if let Some(v) = b["blockProtected"].as_bool() {
        s.block_protected = v;
    }
    if let Some(v) = b["alwaysAskHigh"].as_bool() {
        s.always_ask_high = v;
    }
    if let Some(v) = b["requireGit"].as_bool() {
        s.require_git = v;
    }
    s.save(store)?;
    Ok(s)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    #[test]
    fn router_agents_prefer_installed_and_never_unknown() {
        let all = vec![
            json!({"id": "claude", "name": "Claude Code", "installed": false, "available": true, "curated": true}),
            json!({"id": "odd", "name": "Odd Agent", "installed": false, "available": true, "curated": false}),
            json!({"id": "codex", "name": "Codex", "installed": true, "available": true, "curated": true}),
        ];
        assert_eq!(pick_agents(&all).iter().map(|a| a.id.as_str()).collect::<Vec<_>>(), ["codex"]);
        let none_installed: Vec<Value> = all.iter().filter(|b| b["id"] != "codex").cloned().collect();
        assert_eq!(pick_agents(&none_installed).iter().map(|a| a.id.as_str()).collect::<Vec<_>>(), ["claude"]);
    }

    #[test]
    fn titles_are_short_and_plain() {
        let t = templates::builtins();
        assert_eq!(title_for("the login form", Some(&t[0])), "Write tests: the login form");
        assert_eq!(title_for("", Some(&t[3])), "Update dependencies");
        assert_eq!(title_for("when I log in", Some(&t[1])), "when I log in");
        assert!(title_for(&"word ".repeat(40), None).chars().count() <= 49);
    }

    #[test]
    fn safety_defaults_protect_main() {
        let store = Store::open(Path::new(":memory:")).unwrap();
        let s = SafetySettings::load(&store);
        assert!(s.block_protected && s.always_ask_high && s.protected_branches.contains(&"main".to_owned()));
        let s = set_safety(&store, &json!({"protectedBranches": ["trunk", " "], "alwaysAskHigh": false})).unwrap();
        assert_eq!(s.protected_branches, ["trunk"]);
        assert!(!SafetySettings::load(&store).always_ask_high);
    }

    #[test]
    fn task_rows_round_trip_with_extra_fields() {
        let store = Store::open(Path::new(":memory:")).unwrap();
        let mut t = Task {
            id: "t1".into(),
            bot_id: "b1".into(),
            goal: "g".into(),
            title: "T".into(),
            template: None,
            project: "/p".into(),
            project_name: "p".into(),
            backend: "claude".into(),
            safety: SafetyMode::Worktree,
            branch: Some("hypurr/x".into()),
            base: Some("main".into()),
            worktree: None,
            status: TaskStatus::Active,
            checkpoints: (0..40)
                .map(|i| Checkpoint { id: format!("{i:07}"), label: format!("c{i}"), at: i, files: vec![] })
                .collect(),
            created_at: 1,
            updated_at: 1,
            extra: serde_json::Map::new(),
        };
        t.extra.insert("summary".into(), json!("later stage"));
        t.save(&store).unwrap();
        let back = Task::for_bot(&store, "b1").unwrap();
        assert_eq!(back.extra["summary"], "later stage");
        let public = back.public();
        assert_eq!(public["checkpoints"].as_array().unwrap().len(), CHECKPOINTS_ON_BOT);
        assert_eq!(public["summary"], "later stage");
        assert_eq!(list(&store).unwrap()["tasks"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn permission_assessment_uses_card_fields() {
        let store = Store::open(Path::new(":memory:")).unwrap();
        let a = assess_permission(
            &store,
            &json!({"toolKind": "execute", "title": "Run", "command": "git push origin main", "cwd": "/w", "diffs": []}),
        );
        assert_eq!(a.risk, risk::Risk::High);
        assert!(a.blocked.is_some());
        let a = assess_permission(
            &store,
            &json!({"toolKind": "edit", "title": "Edit", "cwd": "/w", "diffs": [{"path": "/w/a.txt"}]}),
        );
        assert_eq!(a.risk, risk::Risk::Low);
    }
}
