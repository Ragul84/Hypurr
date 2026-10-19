//! Ticket to PR: a Jira or GitHub issue (URL or key), or plain pasted text from any other
//! tracker (Azure `DevOps`, `ServiceNow`, …) becomes a plain-words plan the user approves or
//! edits, then a normal task on the safe branch (approvals, checkpoints, undo) that writes
//! or updates tests and runs them. At the end: a plain diff summary, a pull request
//! description, and a ticket-comment draft. Uploading the branch and opening the pull
//! request only happen when the person approves it (`approved: true`, a Needs-you card on
//! the phone). The comment is never posted by Hypurr: it's for copying or sharing.

#![allow(clippy::assigning_clones, clippy::items_after_statements, clippy::map_unwrap_or)]

use crate::hub::Hub;
use crate::integrations::{Integrations, Issue, github, jira};
use crate::tasks::Task;
use anyhow::{Result, anyhow, bail, ensure};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::time::Duration;

/// Where the pasted reference points.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Reference {
    /// `owner/repo`, number. `repo` is empty for a bare `#12` (the project's repository).
    GitHub {
        repo: String,
        number: u64,
    },
    Jira {
        key: String,
        base: Option<String>,
    },
    /// Anything else: the text is the ticket.
    Text,
}

fn is_jira_key(s: &str) -> bool {
    let Some((proj, num)) = s.split_once('-') else { return false };
    let mut chars = proj.chars();
    chars.next().is_some_and(|c| c.is_ascii_uppercase())
        && chars.all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
        && !num.is_empty()
        && num.chars().all(|c| c.is_ascii_digit())
}

/// Reads what the person pasted: a URL, `SHOP-142`, `#12`, `owner/repo#12`, or text.
pub fn reference(input: &str) -> Reference {
    let s = input.trim();
    if s.contains('\n') || s.is_empty() {
        return Reference::Text;
    }
    if let Some(rest) = s.split_once("://").map(|(_, r)| r) {
        let (host, path) = rest.split_once('/').unwrap_or((rest, ""));
        let parts: Vec<&str> = path.split(['/', '?', '#']).filter(|p| !p.is_empty()).collect();
        if host.contains("github")
            && parts.len() >= 4
            && matches!(parts[2], "issues" | "pull")
            && let Ok(number) = parts[3].parse()
        {
            return Reference::GitHub { repo: format!("{}/{}", parts[0], parts[1]), number };
        }
        let base = format!("https://{host}");
        if let Some(i) = parts.iter().position(|p| *p == "browse")
            && let Some(key) = parts.get(i + 1).filter(|k| is_jira_key(k))
        {
            return Reference::Jira { key: (*key).to_owned(), base: Some(base) };
        }
        if let Some(key) =
            path.split(['?', '&']).find_map(|kv| kv.strip_prefix("selectedIssue=")).filter(|k| is_jira_key(k))
        {
            return Reference::Jira { key: key.to_owned(), base: Some(base) };
        }
        return Reference::Text;
    }
    if is_jira_key(s) {
        return Reference::Jira { key: s.to_owned(), base: None };
    }
    let (repo, num) = s.rsplit_once('#').unwrap_or(("", s.strip_prefix('#').unwrap_or("")));
    if let Ok(number) = num.parse::<u64>()
        && (repo.is_empty() || repo.split('/').count() == 2)
        && !repo.contains(' ')
    {
        return Reference::GitHub { repo: repo.to_owned(), number };
    }
    Reference::Text
}

/// A ticket from a tracker Hypurr can't reach: the key if it has a known shape (Azure
/// `DevOps` `AB#123` or a work-item URL, `ServiceNow` `INC0012345`), the first line as title.
pub fn from_text(text: &str) -> Issue {
    let text = text.trim();
    let mut key = String::new();
    let mut url = String::new();
    for word in text.split_whitespace() {
        let w = word.trim_matches(|c: char| !c.is_ascii_alphanumeric() && c != '#' && c != ':' && c != '/' && c != '.');
        let kw = w.trim_end_matches([':', '.', ',']);
        if key.is_empty() {
            if let Some(n) = kw.strip_prefix("AB#").filter(|n| !n.is_empty() && n.chars().all(|c| c.is_ascii_digit())) {
                key = format!("AB#{n}");
            } else if ["INC", "CHG", "RITM", "PRB", "TASK", "REQ"]
                .iter()
                .any(|p| kw.strip_prefix(p).is_some_and(|n| n.len() >= 5 && n.chars().all(|c| c.is_ascii_digit())))
                || is_jira_key(kw)
            {
                key = kw.to_owned();
            }
        }
        if url.is_empty() && (w.starts_with("https://") || w.starts_with("http://")) {
            url = w.to_owned();
            if key.is_empty()
                && (w.contains("dev.azure.com") || w.contains("visualstudio.com"))
                && let Some(n) = w.rsplit('/').find(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit()))
            {
                key = format!("AB#{n}");
            }
        }
    }
    let first = text.lines().map(str::trim).find(|l| !l.is_empty() && !l.starts_with("http")).unwrap_or("Ticket");
    let title = first.trim_start_matches(['#', '*', ' ']).trim();
    let title = title.strip_prefix(key.as_str()).map(|t| t.trim_start_matches([':', '-', ' ', '—'])).unwrap_or(title);
    Issue {
        source: "text".into(),
        key,
        title: crate::agent::acp::truncate(title, 120),
        url,
        body: crate::agent::acp::truncate(text, 6000),
        project: None,
    }
}

/// `ticketResolve {input, project?}` → `{ticket}` or `{needsSetup: "jira"|"github"}`.
pub async fn resolve(store: &crate::store::Store, b: &Value) -> Result<Value> {
    let input = b["input"].as_str().unwrap_or_default();
    ensure!(!input.trim().is_empty(), "Paste a ticket link, a key like SHOP-142, or the ticket's text.");
    let project = b["project"].as_str().filter(|s| !s.is_empty()).map(str::to_owned);
    let cfg = Integrations::load(store);
    let mut issue = match reference(input) {
        Reference::Text => from_text(input),
        Reference::Jira { key, base } => {
            if !cfg.jira_ready() {
                return Ok(json!({"needsSetup": "jira", "key": key}));
            }
            if let Some(base) = base
                && !base.eq_ignore_ascii_case(&cfg.jira.base_url)
            {
                bail!("This ticket is on {base}, but Hypurr is set up for {}.", cfg.jira.base_url);
            }
            jira::issue(&cfg.jira, &key).await?
        }
        Reference::GitHub { repo, number } => {
            let repo = if repo.is_empty() {
                project
                    .as_deref()
                    .and_then(|p| github::repo_of(Path::new(p)))
                    .ok_or_else(|| anyhow!("Pick the project folder first, or paste the full issue link."))?
            } else {
                repo
            };
            let Some(gh) = github::Client::new(&cfg.github) else {
                return Ok(json!({"needsSetup": "github", "key": format!("#{number}")}));
            };
            gh.issue(&repo, number).await?
        }
    };
    issue.project = project;
    Ok(json!({"ticket": issue}))
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct PlanFile {
    pub path: String,
    pub why: String,
    /// The file is already in the project (else the agent plans to create it).
    pub exists: bool,
}

/// What the person approves before anything changes.
#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct Plan {
    pub understood: String,
    pub files: Vec<PlanFile>,
    /// Acceptance checks: how we'll know it's done.
    pub checks: Vec<String>,
    /// Tests to write or update.
    pub tests: Vec<String>,
    /// How the tests run in this project, e.g. `cargo test`.
    pub test_command: String,
}

/// How tests run in this folder, from its build files.
pub fn test_command(dir: &Path) -> String {
    let has = |f: &str| dir.join(f).exists();
    let package_test = std::fs::read_to_string(dir.join("package.json"))
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .is_some_and(|v| v["scripts"]["test"].is_string());
    let make_test =
        std::fs::read_to_string(dir.join("Makefile")).is_ok_and(|s| s.lines().any(|l| l.starts_with("test:")));
    let cmd = if has("Cargo.toml") {
        "cargo test"
    } else if package_test {
        "npm test"
    } else if has("gradlew") {
        "./gradlew test"
    } else if has("pom.xml") {
        "mvn test"
    } else if has("go.mod") {
        "go test ./..."
    } else if has("pyproject.toml") || has("pytest.ini") || has("setup.py") || has("tests") {
        "pytest"
    } else if make_test {
        "make test"
    } else {
        ""
    };
    cmd.to_owned()
}

/// The project's files (git), for grounding the plan.
fn file_list(dir: &Path, max: usize) -> Vec<String> {
    let out = Command::new("git").arg("-C").arg(dir).args(["ls-files"]).output();
    let Ok(out) = out else { return vec![] };
    String::from_utf8_lossy(&out.stdout).lines().take(max).map(str::to_owned).collect()
}

pub const PLAN_PROMPT: &str = "You plan a code change from a ticket, for someone who will approve it before any file changes. Don't change files. Answer in exactly this shape, plain words, no jargon unless the person's profile says they're an experienced developer:\n\
UNDERSTOOD: one or two sentences: what the ticket asks for, in your own words.\n\
FILES:\n- path/from/project/root — why it changes (use real paths from the list; mark a new file with (new))\n\
CHECKS:\n- an acceptance check a person could verify\n\
TESTS:\n- a test to write or update\n\
Keep it short: at most 6 files, 5 checks, 4 tests.";

fn ticket_block(t: &Issue) -> String {
    let mut s = String::new();
    let _ = writeln!(s, "Ticket {} {}", t.key, t.title);
    if !t.url.is_empty() {
        let _ = writeln!(s, "Link: {}", t.url);
    }
    if !t.body.trim().is_empty() && t.body.trim() != t.title.trim() {
        let _ = writeln!(s, "\n{}", crate::agent::acp::truncate(t.body.trim(), 5000));
    }
    s
}

pub fn plan_prompt(t: &Issue, files: &[String], profile: Option<&str>, test_cmd: &str) -> String {
    let mut s = String::new();
    if let Some(p) = profile {
        let _ = writeln!(s, "About the person:\n{p}\n");
    }
    s.push_str(&ticket_block(t));
    if !test_cmd.is_empty() {
        let _ = writeln!(s, "\nTests in this project run with: {test_cmd}");
    }
    let _ = writeln!(s, "\nProject files ({} shown):\n{}", files.len(), files.join("\n"));
    s
}

/// Reads the agent's plan. Files outside the project that don't exist and aren't marked
/// new are dropped (the agent invented them).
pub fn parse_plan(raw: &str, dir: &Path) -> Option<Plan> {
    let mut p = Plan::default();
    let mut section = "";
    for line in raw.lines() {
        let l = line.trim().trim_start_matches(['*', '#']).trim();
        let upper = l.to_uppercase();
        let head = |name: &str| upper.starts_with(name).then(|| l[name.len()..].trim_start_matches(['*', ':']).trim());
        if let Some(v) = head("UNDERSTOOD") {
            p.understood = v.to_owned();
            section = "u";
            continue;
        }
        if head("FILES").is_some() {
            section = "f";
            continue;
        }
        if head("CHECKS").is_some() || head("ACCEPTANCE").is_some() {
            section = "c";
            continue;
        }
        if head("TESTS").is_some() {
            section = "t";
            continue;
        }
        let item = l.strip_prefix("- ").or_else(|| l.strip_prefix("• ")).map(str::trim);
        match (section, item) {
            ("u", None) if !l.is_empty() => {
                p.understood.push(' ');
                p.understood.push_str(l);
            }
            ("f", Some(it)) => {
                let (path, why) = it
                    .split_once(" — ")
                    .or_else(|| it.split_once(" – "))
                    .or_else(|| it.split_once(" - "))
                    .or_else(|| it.split_once(": "))
                    .unwrap_or((it, ""));
                let new = path.contains("(new)") || why.contains("(new)");
                let path = path.replace("(new)", "").trim().trim_matches('`').trim_start_matches("./").to_owned();
                let safe = !path.is_empty() && !path.starts_with('/') && !path.split('/').any(|c| c == "..");
                let exists = safe && dir.join(&path).is_file();
                if safe && (exists || new) && p.files.len() < 6 {
                    p.files.push(PlanFile { path, why: why.replace("(new)", "").trim().to_owned(), exists });
                }
            }
            ("c", Some(it)) if p.checks.len() < 5 => p.checks.push(it.to_owned()),
            ("t", Some(it)) if p.tests.len() < 4 => p.tests.push(it.to_owned()),
            _ => {}
        }
    }
    p.understood = p.understood.trim().to_owned();
    (!p.understood.is_empty()).then_some(p)
}

pub fn fallback_plan(t: &Issue, test_cmd: &str) -> Plan {
    let mut checks = vec![format!("What the ticket asks for works: {}", t.title)];
    if !test_cmd.is_empty() {
        checks.push(format!("All tests pass ({test_cmd})"));
    }
    Plan {
        understood: if t.title.is_empty() { "Do what the ticket describes.".into() } else { t.title.clone() },
        files: vec![],
        checks,
        tests: vec!["Add or update a test that covers this change".into()],
        test_command: test_cmd.to_owned(),
    }
}

fn pick_bot(store: &crate::store::Store, wanted: Option<&str>) -> Option<crate::store::BotConfig> {
    let bots = store.bots().ok()?;
    let usable = |b: &&crate::store::BotRow| !b.deleted && !b.config.is_group();
    wanted
        .and_then(|id| bots.iter().filter(usable).find(|b| b.config.id == id))
        .or_else(|| bots.iter().find(usable))
        .map(|b| b.config.clone())
}

fn project_dir(store: &crate::store::Store, project: &str) -> Result<PathBuf> {
    ensure!(!project.is_empty(), "Pick the project folder first.");
    let known = crate::tasks::projects(store).into_iter().any(|p| p.path == project);
    ensure!(known, "That folder isn't one of this computer's projects.");
    Ok(PathBuf::from(project))
}

/// `ticketPlan {ticket, project, botId?}` → `{plan, by: agent|ticket}`.
pub async fn plan(hub: &Arc<Hub>, b: &Value, actor_key: &str) -> Result<Value> {
    let t: Issue = serde_json::from_value(b["ticket"].clone()).map_err(|_| anyhow!("missing ticket"))?;
    let dir = project_dir(&hub.store, b["project"].as_str().unwrap_or_default())?;
    let d2 = dir.clone();
    let (files, test_cmd) = tokio::task::spawn_blocking(move || (file_list(&d2, 400), test_command(&d2))).await?;
    let profile = crate::chat::profile::load(&hub.store, Some(actor_key)).render();
    let mut plan = fallback_plan(&t, &test_cmd);
    let mut by = "ticket";
    if let Some(cfg) = pick_bot(&hub.store, b["botId"].as_str()) {
        let prompt = plan_prompt(&t, &files, profile.as_deref(), &test_cmd);
        let ask = crate::chat::memory::one_shot(hub, &cfg, PLAN_PROMPT, &prompt);
        match tokio::time::timeout(Duration::from_secs(150), ask).await {
            Ok(Ok(answer)) => {
                if let Some(mut p) = parse_plan(&answer, &dir) {
                    if p.checks.is_empty() {
                        p.checks = plan.checks.clone();
                    }
                    p.test_command = test_cmd.clone();
                    plan = p;
                    by = "agent";
                }
            }
            Ok(Err(e)) => tracing::info!(error = %e, "ticket plan agent failed; using the ticket title"),
            Err(_) => tracing::info!("ticket plan agent timed out"),
        }
    }
    Ok(json!({"plan": plan, "by": by}))
}

/// The task's goal: the approved plan, plus the tests rule.
pub fn goal(t: &Issue, p: &Plan) -> String {
    let mut g =
        format!("Work on the ticket {} “{}”.\n\nThe plan the person approved:\n{}\n", t.key, t.title, p.understood);
    if !p.files.is_empty() {
        g.push_str("\nFiles:\n");
        for f in &p.files {
            let _ = writeln!(g, "- {}{} — {}", f.path, if f.exists { "" } else { " (new)" }, f.why);
        }
    }
    if !p.checks.is_empty() {
        g.push_str("\nDone when:\n");
        for c in &p.checks {
            let _ = writeln!(g, "- {c}");
        }
    }
    g.push_str("\nTests: write or update tests for this change");
    if !p.tests.is_empty() {
        let _ = write!(g, " ({})", p.tests.join("; "));
    }
    if p.test_command.is_empty() {
        g.push_str(", then run the project's tests");
    } else {
        let _ = write!(g, ", then run them with `{}`", p.test_command);
    }
    g.push_str(" and say plainly whether they passed. Stick to the plan; if it needs to change, say why.");
    g
}

/// `ticketStart {ticket, plan, project, backend?}`: a normal task with the ticket attached.
pub async fn start(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let mut t: Issue = serde_json::from_value(b["ticket"].clone()).map_err(|_| anyhow!("missing ticket"))?;
    let p: Plan = serde_json::from_value(b["plan"].clone()).map_err(|_| anyhow!("missing plan"))?;
    ensure!(!p.understood.trim().is_empty(), "The plan is empty.");
    let project = b["project"].as_str().unwrap_or_default().to_owned();
    project_dir(&hub.store, &project)?;
    t.project = Some(project.clone());
    let mut req = json!({"goal": goal(&t, &p), "project": project, "issue": t});
    if let Some(be) = b["backend"].as_str() {
        req["backend"] = be.into();
    }
    let out = crate::tasks::start(hub, &req).await?;
    if let Some(id) = out["task"]["id"].as_str()
        && let Some(mut task) = Task::load(&hub.store, id)
    {
        task.extra.insert("ticket".into(), json!({"plan": p}));
        task.save(&hub.store)?;
    }
    Ok(out)
}

/// Where a pull request can be opened from this folder.
#[derive(Serialize, Clone, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Publish {
    /// github | gitlab | azure | other | none
    pub provider: String,
    /// How it'd be opened: `GitHub token`, `gh`, `glab`, `az`; empty when it can't be.
    pub via: String,
    pub branch: String,
    pub base: String,
    pub protected: bool,
}

pub fn provider_of(remote: &str) -> &'static str {
    let r = remote.to_lowercase();
    if r.is_empty() {
        "none"
    } else if r.contains("github") {
        "github"
    } else if r.contains("gitlab") {
        "gitlab"
    } else if r.contains("dev.azure.com") || r.contains("visualstudio.com") {
        "azure"
    } else {
        "other"
    }
}

fn cli_ok(program: &str, args: &[&str]) -> bool {
    Command::new(program)
        .args(args)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .is_ok_and(|s| s.success())
}

fn publish_info(store: &crate::store::Store, task: &Task) -> Publish {
    let dir = PathBuf::from(&task.project);
    let provider = provider_of(&github::remote_url(&dir).unwrap_or_default()).to_owned();
    let cfg = Integrations::load(store);
    let via = match provider.as_str() {
        "github" if !cfg.github.token.is_empty() => "GitHub token",
        "github" if github::cli_available() => "gh",
        "gitlab" if cli_ok("glab", &["auth", "status"]) => "glab",
        "azure" if cli_ok("az", &["account", "show", "-o", "none"]) => "az",
        _ => "",
    };
    let branch = task.branch.clone().unwrap_or_default();
    let protected = crate::tasks::SafetySettings::load(store).protected_branches;
    Publish {
        protected: branch.is_empty() || crate::tasks::safety::is_protected(&branch, &protected),
        provider,
        via: via.into(),
        branch,
        base: task.base.clone().unwrap_or_default(),
    }
}

fn ticket_of(task: &Task) -> Issue {
    task.issue().unwrap_or_else(|| Issue { title: task.title.clone(), ..Issue::default() })
}

pub fn pr_title(t: &Issue) -> String {
    let title = crate::agent::acp::truncate(t.title.trim(), 90);
    if t.key.is_empty() { title } else { format!("{}: {title}", t.key) }
}

pub fn pr_body(t: &Issue, plan: Option<&Plan>, summary: &str, files: &[crate::tasks::finish::FileChange]) -> String {
    let mut b = String::new();
    match (t.source.as_str(), t.key.is_empty()) {
        ("github", false) => {
            let _ = writeln!(b, "Closes {}\n", t.key);
        }
        (_, false) if !t.url.is_empty() => {
            let _ = writeln!(b, "Ticket: [{}]({})\n", t.key, t.url);
        }
        (_, false) => {
            let _ = writeln!(b, "Ticket: {}\n", t.key);
        }
        _ => {}
    }
    let _ = writeln!(b, "## What changed\n{summary}\n");
    if !files.is_empty() {
        b.push_str("## Files\n");
        for f in files.iter().take(30) {
            let _ = writeln!(b, "- `{}` (+{} −{})", f.path, f.added, f.removed);
        }
        b.push('\n');
    }
    if let Some(p) = plan
        && !p.checks.is_empty()
    {
        b.push_str("## How to check\n");
        for c in &p.checks {
            let _ = writeln!(b, "- [ ] {c}");
        }
        b.push('\n');
    }
    b.push_str("---\nMade with Hypurr from the ticket, on a safe branch with checkpoints.");
    b
}

pub fn comment_draft(t: &Issue, summary: &str, pr_url: Option<&str>) -> String {
    let mut c = String::from("Update from Hypurr:\n\n");
    let _ = writeln!(c, "{summary}");
    match pr_url {
        Some(u) => {
            let _ = write!(c, "\nPull request for review: {u}");
        }
        None => c.push_str("\nA pull request is on its way for review."),
    }
    if !t.key.is_empty() {
        let _ = write!(c, "\n({})", t.key);
    }
    c
}

pub const SUMMARY_PROMPT: &str = "Explain a code change to someone who isn't a programmer, in 2 to 4 short plain sentences: what it does for the user of the product, and what was tested. Say nothing you can't see in the diff. No markdown headings.";

fn diff_text(dir: &Path, from: &str) -> String {
    let run = |args: &[&str]| {
        Command::new("git")
            .arg("-C")
            .arg(dir)
            .args(args)
            .output()
            .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
            .unwrap_or_default()
    };
    let stat = run(&["diff", "--stat", from, "HEAD"]);
    let full = run(&["diff", "--no-color", from, "HEAD"]);
    format!("{stat}\n{}", crate::agent::acp::truncate(&full, 12000))
}

/// `ticketReview {taskId, botId?}`: what changed, in plain words, plus the PR text and the
/// comment draft. Saves a checkpoint first so the summary matches the branch.
pub async fn review(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let task_id = b["taskId"].as_str().ok_or_else(|| anyhow!("missing taskId"))?;
    let task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    if task.status == crate::tasks::TaskStatus::Active {
        let (h, bot) = (hub.clone(), task.bot_id.clone());
        let _ = tokio::task::spawn_blocking(move || crate::tasks::checkpoint_bot(&h, &bot, "Before review")).await?;
    }
    let task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    let t = ticket_of(&task);
    let plan: Option<Plan> =
        serde_json::from_value(task.extra.get("ticket").map(|v| v["plan"].clone()).unwrap_or_default()).ok();
    let dir = task.worktree_path().unwrap_or_else(|| PathBuf::from(&task.project));
    let from = task.checkpoints.first().map(|c| c.id.clone());
    let (files, diff) = match from {
        Some(f) => {
            let d = dir.clone();
            tokio::task::spawn_blocking(move || (crate::tasks::finish::changes(&d, &f), diff_text(&d, &f))).await?
        }
        None => (vec![], String::new()),
    };
    let mut summary = crate::tasks::finish::fallback_summary(&files);
    let mut by = "files";
    if !files.is_empty()
        && let Some(cfg) = pick_bot(&hub.store, b["botId"].as_str())
    {
        let prompt = format!("{}\nThe diff:\n{diff}", ticket_block(&t));
        let ask = crate::chat::memory::one_shot(hub, &cfg, SUMMARY_PROMPT, &prompt);
        if let Ok(Ok(s)) = tokio::time::timeout(Duration::from_secs(120), ask).await
            && !s.trim().is_empty()
        {
            summary = crate::agent::acp::truncate(s.trim(), 1200);
            by = "agent";
        }
    }
    let pr = task.extra.get("ticket").and_then(|v| v.get("pr")).cloned().unwrap_or(Value::Null);
    let pr_url = pr["url"].as_str();
    let publish = {
        let (h, t2) = (hub.clone(), task.clone());
        tokio::task::spawn_blocking(move || publish_info(&h.store, &t2)).await?
    };
    Ok(json!({
        "ticket": t,
        "files": files,
        "summary": summary,
        "by": by,
        "prTitle": pr_title(&t),
        "prBody": pr_body(&t, plan.as_ref(), &summary, &files),
        "comment": comment_draft(&t, &summary, pr_url),
        "publish": publish,
        "pr": pr,
        "testCommand": plan.map(|p| p.test_command).unwrap_or_default(),
    }))
}

fn run_cli(dir: &Path, program: &str, args: &[&str]) -> Result<String> {
    let out = Command::new(program)
        .current_dir(dir)
        .args(args)
        .env("GH_PROMPT_DISABLED", "1")
        .env("GLAB_NO_PROMPT", "1")
        .output()
        .map_err(|e| anyhow!("couldn't run {program}: {e}"))?;
    if !out.status.success() {
        bail!("{program} failed: {}", crate::agent::acp::truncate(String::from_utf8_lossy(&out.stderr).trim(), 300));
    }
    Ok(String::from_utf8_lossy(&out.stdout).into_owned())
}

/// The pull request link in a CLI's output.
pub fn url_in(out: &str) -> Option<String> {
    out.split_whitespace().rev().find(|w| w.starts_with("https://")).map(|w| w.trim_matches(['"', ',']).to_owned())
}

/// `ticketPublish {taskId, approved: true, title?, body?}`: uploads the branch, then opens
/// the pull request where the folder's remote lives. Refuses without `approved`.
pub async fn publish(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    ensure!(b["approved"] == true, "Uploading the branch needs your approval.");
    let task_id = b["taskId"].as_str().ok_or_else(|| anyhow!("missing taskId"))?;
    let mut task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    let info = {
        let (h, t2) = (hub.clone(), task.clone());
        tokio::task::spawn_blocking(move || publish_info(&h.store, &t2)).await?
    };
    ensure!(!info.protected, "This task isn't on a safe branch, so Hypurr won't upload it.");
    ensure!(info.provider != "none", "This folder has no remote to upload to.");
    let t = ticket_of(&task);
    let title = b["title"].as_str().filter(|s| !s.trim().is_empty()).map(str::to_owned).unwrap_or_else(|| pr_title(&t));
    let body = b["body"].as_str().unwrap_or_default().to_owned();
    let cfg = Integrations::load(&hub.store);
    let gh = (info.provider == "github").then(|| github::Client::new(&cfg.github)).flatten();
    let dir = task.worktree_path().unwrap_or_else(|| PathBuf::from(&task.project));
    {
        let (d, br, token) = (dir.clone(), info.branch.clone(), gh.as_ref().and_then(|g| g.token().map(str::to_owned)));
        tokio::task::spawn_blocking(move || github::push_branch(&d, &br, token.as_deref())).await??;
    }
    let mut pr = json!({"pushed": true, "branch": info.branch, "provider": info.provider});
    let opened: Result<Option<String>> = match (info.provider.as_str(), info.via.as_str()) {
        ("github", _) if gh.is_some() => {
            let repo = github::repo_of(Path::new(&task.project)).ok_or_else(|| anyhow!("no GitHub repository"))?;
            let gh = gh.as_ref().ok_or_else(|| anyhow!("GitHub isn't set up"))?;
            gh.open_pr(&repo, &info.branch, &info.base, &title, &body).await.map(|(n, url)| {
                pr["number"] = n.into();
                Some(url)
            })
        }
        ("gitlab", "glab") => {
            let (d, br, base, ti, bo) =
                (dir.clone(), info.branch.clone(), info.base.clone(), title.clone(), body.clone());
            tokio::task::spawn_blocking(move || {
                run_cli(
                    &d,
                    "glab",
                    &[
                        "mr",
                        "create",
                        "--source-branch",
                        &br,
                        "--target-branch",
                        &base,
                        "--title",
                        &ti,
                        "--description",
                        &bo,
                        "--yes",
                    ],
                )
            })
            .await?
            .map(|o| url_in(&o))
        }
        ("azure", "az") => {
            let (d, br, base, ti, bo) =
                (dir.clone(), info.branch.clone(), info.base.clone(), title.clone(), body.clone());
            tokio::task::spawn_blocking(move || {
                run_cli(
                    &d,
                    "az",
                    &[
                        "repos",
                        "pr",
                        "create",
                        "--source-branch",
                        &br,
                        "--target-branch",
                        &base,
                        "--title",
                        &ti,
                        "--description",
                        &bo,
                        "-o",
                        "json",
                    ],
                )
            })
            .await?
            .map(|o| {
                let v: Value = serde_json::from_str(&o).unwrap_or_default();
                let web = v["repository"]["webUrl"].as_str().unwrap_or_default();
                v["pullRequestId"].as_u64().filter(|_| !web.is_empty()).map(|id| format!("{web}/pullrequest/{id}"))
            })
        }
        _ => Ok(None),
    };
    match opened {
        Ok(Some(url)) => pr["url"] = url.into(),
        Ok(None) => pr["copy"] = true.into(),
        Err(e) => {
            pr["copy"] = true.into();
            pr["error"] = format!("{e:#}").into();
        }
    }
    let entry = task.extra.entry("ticket").or_insert_with(|| json!({}));
    entry["pr"] = pr.clone();
    task.save(&hub.store)?;
    hub.emit_bot(&task.bot_id);
    Ok(json!({"pr": pr}))
}

/// `ticketSetup`: which trackers and PR tools are ready, without secrets.
pub fn setup(store: &crate::store::Store) -> Value {
    let cfg = Integrations::load(store);
    json!({
        "integrations": cfg.public(),
        "cli": {
            "gh": github::cli_available(),
            "glab": cli_ok("glab", &["auth", "status"]),
            "az": cli_ok("az", &["account", "show", "-o", "none"]),
        },
    })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::assertions_on_constants)]
    use super::*;

    fn tmp() -> PathBuf {
        let d = std::env::temp_dir().join(format!("hypurr-ticket-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn references_are_read() {
        assert_eq!(
            reference("https://github.com/acme/shop/issues/142"),
            Reference::GitHub { repo: "acme/shop".into(), number: 142 }
        );
        assert_eq!(reference("acme/shop#7"), Reference::GitHub { repo: "acme/shop".into(), number: 7 });
        assert_eq!(reference("#12"), Reference::GitHub { repo: String::new(), number: 12 });
        assert_eq!(reference("SHOP-142"), Reference::Jira { key: "SHOP-142".into(), base: None });
        assert_eq!(
            reference("https://acme.atlassian.net/browse/SHOP-9?focus=1"),
            Reference::Jira { key: "SHOP-9".into(), base: Some("https://acme.atlassian.net".into()) }
        );
        assert_eq!(
            reference("https://acme.atlassian.net/jira/software/projects/SHOP/boards/1?selectedIssue=SHOP-3"),
            Reference::Jira { key: "SHOP-3".into(), base: Some("https://acme.atlassian.net".into()) }
        );
        assert_eq!(reference("shop-142"), Reference::Text);
        assert_eq!(reference("Checkout total is wrong\nSteps: …"), Reference::Text);
        assert_eq!(reference("https://dev.azure.com/acme/shop/_workitems/edit/4521"), Reference::Text);
    }

    #[test]
    fn other_trackers_keep_their_key() {
        let t = from_text(
            "https://dev.azure.com/acme/shop/_workitems/edit/4521\nCart total ignores coupons\nRepro: add coupon",
        );
        assert_eq!(
            (t.key.as_str(), t.title.as_str(), t.source.as_str()),
            ("AB#4521", "Cart total ignores coupons", "text")
        );
        let t = from_text("INC0012345: Login page times out for VPN users");
        assert_eq!((t.key.as_str(), t.title.as_str()), ("INC0012345", "Login page times out for VPN users"));
        let t = from_text("Make the export button bigger");
        assert_eq!(t.key, "");
        assert_eq!(pr_title(&t), "Make the export button bigger");
    }

    #[test]
    fn plan_parse_keeps_real_and_new_files_only() {
        let dir = tmp();
        std::fs::create_dir_all(dir.as_path().join("src")).unwrap();
        std::fs::write(dir.as_path().join("src/cart.rs"), "").unwrap();
        let raw = "**UNDERSTOOD:** Coupons should lower the cart total.\nFILES:\n- src/cart.rs — apply the coupon\n- src/ghost.rs — invented\n- tests/coupon.rs (new) — new test\n- ../etc/passwd — nope\nCHECKS:\n- A 10% coupon on ₹500 shows ₹450\nTESTS:\n- coupon_lowers_total\n";
        let p = parse_plan(raw, dir.as_path()).unwrap();
        assert_eq!(p.understood, "Coupons should lower the cart total.");
        let paths: Vec<_> = p.files.iter().map(|f| (f.path.as_str(), f.exists)).collect();
        assert_eq!(paths, [("src/cart.rs", true), ("tests/coupon.rs", false)]);
        assert_eq!(p.checks, ["A 10% coupon on ₹500 shows ₹450"]);
        assert_eq!(p.tests, ["coupon_lowers_total"]);
        assert!(parse_plan("no idea", dir.as_path()).is_none());
    }

    #[test]
    fn goal_carries_plan_and_tests_rule() {
        let t = Issue { source: "jira".into(), key: "SHOP-1".into(), title: "Coupons".into(), ..Issue::default() };
        let p = Plan {
            understood: "Apply coupons.".into(),
            files: vec![PlanFile { path: "src/cart.rs".into(), why: "math".into(), exists: true }],
            checks: vec!["₹450".into()],
            tests: vec!["coupon test".into()],
            test_command: "cargo test".into(),
        };
        let g = goal(&t, &p);
        assert!(
            g.contains("SHOP-1")
                && g.contains("src/cart.rs — math")
                && g.contains("`cargo test`")
                && g.contains("passed")
        );
    }

    #[test]
    fn pr_and_comment_texts() {
        let gh = Issue { source: "github".into(), key: "#12".into(), title: "Fix cart".into(), ..Issue::default() };
        let files = vec![crate::tasks::finish::FileChange { path: "a.rs".into(), added: 3, removed: 1 }];
        let p = Plan { checks: vec!["works".into()], ..Plan::default() };
        let body = pr_body(&gh, Some(&p), "It works now.", &files);
        assert!(body.starts_with("Closes #12") && body.contains("`a.rs` (+3 −1)") && body.contains("- [ ] works"));
        assert_eq!(pr_title(&gh), "#12: Fix cart");
        let jira = Issue {
            source: "jira".into(),
            key: "SHOP-1".into(),
            url: "https://a/browse/SHOP-1".into(),
            ..Issue::default()
        };
        assert!(pr_body(&jira, None, "x", &[]).starts_with("Ticket: [SHOP-1](https://a/browse/SHOP-1)"));
        let c = comment_draft(&jira, "Done.", Some("https://github.com/a/b/pull/3"));
        assert!(c.contains("pull/3") && c.ends_with("(SHOP-1)"));
    }

    #[test]
    fn providers_and_test_commands() {
        assert_eq!(provider_of("git@github.com:a/b.git"), "github");
        assert_eq!(provider_of("https://gitlab.acme.in/a/b.git"), "gitlab");
        assert_eq!(provider_of("https://acme@dev.azure.com/acme/shop/_git/shop"), "azure");
        assert_eq!(provider_of("ssh://git.acme.in/b"), "other");
        assert_eq!(provider_of(""), "none");
        assert_eq!(
            url_in("Creating…\nhttps://gitlab.com/a/b/-/merge_requests/4\n").as_deref(),
            Some("https://gitlab.com/a/b/-/merge_requests/4")
        );
        let dir = tmp();
        assert_eq!(test_command(dir.as_path()), "");
        std::fs::write(dir.as_path().join("package.json"), r#"{"scripts":{"test":"jest"}}"#).unwrap();
        assert_eq!(test_command(dir.as_path()), "npm test");
        std::fs::write(dir.as_path().join("Cargo.toml"), "").unwrap();
        assert_eq!(test_command(dir.as_path()), "cargo test");
    }
}
