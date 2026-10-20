//! Project guide, handover / KT doc and "Ask about this project".
//!
//! Read-only by construction: Hypurr reads the folder and its git history itself
//! ([`scan`]) and hands the facts to a tool-less agent ([`crate::chat::memory::one_shot`]),
//! so nothing in the folder can change. Every file path in the answer is checked against
//! the folder ([`ground`]); made-up paths are dropped.

use crate::hub::Hub;
use anyhow::{Result, anyhow, ensure};
use serde::Serialize;
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::time::Duration;

const SKIP: &[&str] = &[
    ".git",
    "node_modules",
    "target",
    "build",
    "dist",
    ".next",
    "vendor",
    ".gradle",
    ".idea",
    "__pycache__",
    ".venv",
    "venv",
    "Pods",
    ".turbo",
    "coverage",
    "out",
];
const MAX_FILES: usize = 20_000;

/// Everything Hypurr learned about the folder, all of it real.
#[derive(Debug, Default, Clone, Serialize)]
pub struct Facts {
    pub name: String,
    pub readme: String,
    /// Relative paths of every file (capped), for grounding.
    #[serde(skip)]
    pub files: BTreeSet<String>,
    pub top: Vec<String>,
    pub manifests: BTreeMap<String, String>,
    pub tests: Vec<String>,
    pub deploy: Vec<String>,
    pub owners: Vec<String>,
    pub codeowners: String,
    pub recent: Vec<String>,
    pub branches: Vec<String>,
    pub hot: Vec<String>,
}

fn rel(root: &Path, p: &Path) -> String {
    p.strip_prefix(root).unwrap_or(p).to_string_lossy().replace('\\', "/")
}

pub fn walk(root: &Path) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(rd) = std::fs::read_dir(&dir) else { continue };
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            let Ok(ft) = e.file_type() else { continue };
            if ft.is_dir() {
                if !SKIP.contains(&name.as_str()) {
                    stack.push(e.path());
                }
            } else if ft.is_file() {
                out.insert(rel(root, &e.path()));
                if out.len() >= MAX_FILES {
                    return out;
                }
            }
        }
    }
    out
}

fn git(dir: &Path, args: &[&str]) -> Vec<String> {
    Command::new("git")
        .arg("-C")
        .arg(dir)
        .args(args)
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| {
            String::from_utf8_lossy(&o.stdout).lines().map(str::to_owned).filter(|l| !l.trim().is_empty()).collect()
        })
        .unwrap_or_default()
}

fn head(text: &str, lines: usize, chars: usize) -> String {
    let s: String = text.lines().take(lines).collect::<Vec<_>>().join("\n");
    s.chars().take(chars).collect()
}

fn read(root: &Path, f: &str) -> String {
    std::fs::read_to_string(root.join(f)).unwrap_or_default()
}

const MANIFESTS: &[&str] = &[
    "package.json",
    "Cargo.toml",
    "pyproject.toml",
    "requirements.txt",
    "go.mod",
    "pom.xml",
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle.kts",
    "Makefile",
    "Gemfile",
    "composer.json",
    "docker-compose.yml",
    "Dockerfile",
    "justfile",
];

pub fn scan(root: &Path) -> Facts {
    let files = walk(root);
    let mut f = Facts {
        name: root.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
        ..Facts::default()
    };
    if let Some(r) = files.iter().find(|p| !p.contains('/') && p.to_lowercase().starts_with("readme")) {
        f.readme = head(&read(root, r), 60, 3000);
    }
    let mut top: BTreeSet<String> = BTreeSet::new();
    for p in &files {
        top.insert(match p.split_once('/') {
            Some((d, _)) => format!("{d}/"),
            None => p.clone(),
        });
    }
    f.top = top.into_iter().take(40).collect();
    for p in &files {
        let base = p.rsplit('/').next().unwrap_or(p);
        if MANIFESTS.contains(&base) && p.matches('/').count() <= 2 && f.manifests.len() < 12 {
            f.manifests.insert(p.clone(), head(&read(root, p), 40, 1500));
        }
        let lower = p.to_lowercase();
        let base_l = base.to_lowercase();
        if (lower.contains("/test")
            || lower.starts_with("test")
            || base_l.contains(".test.")
            || base_l.contains(".spec.")
            || base_l.contains("_test.")
            || base_l.starts_with("test_"))
            && f.tests.len() < 12
        {
            f.tests.push(p.clone());
        }
        if (base == "Dockerfile"
            || base == "Procfile"
            || base == "fly.toml"
            || base == "vercel.json"
            || base == "netlify.toml"
            || lower.contains("deploy")
            || lower.contains("release")
            || (lower.starts_with(".github/workflows/")))
            && f.deploy.len() < 12
        {
            f.deploy.push(p.clone());
        }
    }
    for c in ["CODEOWNERS", ".github/CODEOWNERS", "docs/CODEOWNERS"] {
        if files.contains(c) {
            f.codeowners = head(&read(root, c), 30, 1500);
        }
    }
    f.owners = git(root, &["shortlog", "-sn", "--no-merges", "HEAD"])
        .into_iter()
        .take(8)
        .map(|l| l.trim().to_owned())
        .collect();
    f.recent = git(root, &["log", "-n", "20", "--date=short", "--format=%h %ad %an: %s"]);
    f.branches = git(
        root,
        &[
            "for-each-ref",
            "--sort=-committerdate",
            "--count=12",
            "--format=%(refname:short) (%(committerdate:short), %(authorname))",
            "refs/heads",
            "refs/remotes",
        ],
    );
    let mut counts: BTreeMap<String, usize> = BTreeMap::new();
    for l in git(root, &["log", "--since=90.days", "--name-only", "--format="]) {
        if files.contains(&l) {
            *counts.entry(l).or_default() += 1;
        }
    }
    let mut hot: Vec<_> = counts.into_iter().collect();
    hot.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    f.hot = hot.into_iter().take(10).map(|(p, n)| format!("{p} ({n} changes in 90 days)")).collect();
    f.files = files;
    f
}

/// The facts as prompt text.
pub fn render(f: &Facts) -> String {
    let mut s = format!("Project folder: {}\n", f.name);
    let list = |s: &mut String, title: &str, items: &[String]| {
        if !items.is_empty() {
            let _ = writeln!(s, "\n## {title}");
            for i in items {
                let _ = writeln!(s, "- {i}");
            }
        }
    };
    if !f.readme.is_empty() {
        let _ = write!(s, "\n## README (start)\n{}\n", f.readme);
    }
    list(&mut s, "Top level", &f.top);
    for (p, body) in &f.manifests {
        let _ = write!(s, "\n## {p}\n{body}\n");
    }
    list(&mut s, "Test files", &f.tests);
    list(&mut s, "Build / deploy files", &f.deploy);
    list(&mut s, "Most commits (git shortlog)", &f.owners);
    if !f.codeowners.is_empty() {
        let _ = write!(s, "\n## CODEOWNERS\n{}\n", f.codeowners);
    }
    list(&mut s, "Recent commits", &f.recent);
    list(&mut s, "Branches, newest first", &f.branches);
    list(&mut s, "Most changed files (risky areas)", &f.hot);
    s
}

fn looks_like_path(t: &str) -> bool {
    let t = t.trim();
    if t.is_empty() || t.contains(char::is_whitespace) || t.contains("://") || t.starts_with('-') || t.starts_with('$')
    {
        return false;
    }
    if t.contains(['(', ')', '=', '<', '>', '{', '}', '"', '\'', ',', ';', '|', '*']) {
        return false;
    }
    if t.contains('/') {
        return true;
    }
    // `main.rs`, `package.json`: a name with a short extension.
    t.rsplit_once('.').is_some_and(|(stem, ext)| {
        !stem.is_empty()
            && (1..=5).contains(&ext.len())
            && ext.chars().all(|c| c.is_ascii_alphanumeric())
            && ext.chars().any(|c| c.is_ascii_alphabetic())
    })
}

/// `src/a.rs:12` → `src/a.rs`; `./x` → `x`; trailing `/` kept for folders.
fn clean(t: &str) -> String {
    let t = t.trim().trim_start_matches("./");
    let t = match t.rsplit_once(':') {
        Some((p, n)) if n.chars().all(|c| c.is_ascii_digit() || c == '-') && !n.is_empty() => p,
        _ => t,
    };
    t.to_owned()
}

fn exists(files: &BTreeSet<String>, t: &str) -> bool {
    let p = clean(t);
    let dir = p.trim_end_matches('/');
    files.contains(&p) || files.range(format!("{dir}/")..).next().is_some_and(|f| f.starts_with(&format!("{dir}/")))
}

/// Grounds the Markdown in the folder: keeps real paths, drops made-up ones.
/// A bullet that only points at a missing file is removed; elsewhere the fake path loses
/// its code / link formatting so it never reads as a file. Returns `(markdown, cited, dropped)`.
pub fn ground(md: &str, files: &BTreeSet<String>) -> (String, Vec<String>, Vec<String>) {
    let mut cited = BTreeSet::new();
    let mut dropped = BTreeSet::new();
    let mut out = Vec::new();
    for line in md.lines() {
        let mut fixed = String::new();
        let mut bad_here = 0;
        let mut good_here = 0;
        let mut rest = line;
        // Links: [text](target)
        while let Some(i) = rest.find("](") {
            let Some(open) = rest[..i].rfind('[') else { break };
            let Some(close) = rest[i + 2..].find(')') else { break };
            let target = &rest[i + 2..i + 2 + close];
            let text = &rest[open + 1..i];
            fixed.push_str(&rest[..open]);
            if looks_like_path(target) && !target.starts_with('#') {
                if exists(files, target) {
                    good_here += 1;
                    cited.insert(clean(target));
                    let _ = write!(fixed, "[{text}]({})", clean(target));
                } else {
                    bad_here += 1;
                    dropped.insert(clean(target));
                    fixed.push_str(text);
                }
            } else {
                fixed.push_str(&rest[open..i + 3 + close]);
            }
            rest = &rest[i + 3 + close..];
        }
        fixed.push_str(rest);
        // Code spans: `x`
        let mut final_line = String::new();
        let mut parts = fixed.split('`');
        if let Some(first) = parts.next() {
            final_line.push_str(first);
        }
        let parts: Vec<&str> = parts.collect();
        let mut i = 0;
        while i < parts.len() {
            let code = parts[i];
            let after = parts.get(i + 1).copied();
            if after.is_none() {
                // Unbalanced backtick: keep as written.
                let _ = write!(final_line, "`{code}");
                break;
            }
            if looks_like_path(code) {
                if exists(files, code) {
                    good_here += 1;
                    cited.insert(clean(code));
                    let _ = write!(final_line, "`{code}`");
                } else {
                    bad_here += 1;
                    dropped.insert(clean(code));
                    final_line.push_str(code);
                }
            } else {
                let _ = write!(final_line, "`{code}`");
            }
            final_line.push_str(after.unwrap_or_default());
            i += 2;
        }
        let bullet = line.trim_start().starts_with(['-', '*'])
            || line.trim_start().chars().next().is_some_and(|c| c.is_ascii_digit());
        if bullet && bad_here > 0 && good_here == 0 {
            continue;
        }
        out.push(final_line);
    }
    (out.join("\n"), cited.into_iter().collect(), dropped.into_iter().collect())
}

fn bullets(s: &mut String, items: &[String], none: &str) {
    if items.is_empty() {
        let _ = writeln!(s, "- {none}");
    }
    for i in items {
        let _ = writeln!(s, "- {i}");
    }
}

fn code_list(items: &[String]) -> Vec<String> {
    items.iter().map(|p| format!("`{p}`")).collect()
}

fn scripts(f: &Facts) -> Vec<String> {
    let mut out = vec![];
    for (p, body) in &f.manifests {
        let base = p.rsplit('/').next().unwrap_or(p);
        let cmd = match base {
            "package.json" => Some(if body.contains("\"test\"") {
                "npm install, then npm test"
            } else {
                "npm install, then npm run dev"
            }),
            "Cargo.toml" => Some("cargo build, then cargo test"),
            "pyproject.toml" | "requirements.txt" => Some("pip install, then pytest"),
            "go.mod" => Some("go build ./..., then go test ./..."),
            "build.gradle" | "build.gradle.kts" => Some("./gradlew build, then ./gradlew test"),
            "Makefile" => Some("make, then make test"),
            "pom.xml" => Some("mvn test"),
            _ => None,
        };
        if let Some(c) = cmd {
            out.push(format!("`{p}`: {c}"));
        }
    }
    out
}

/// A guide built only from the facts, for when no agent is available.
pub fn fallback(kind: &str, f: &Facts, beginner: bool) -> String {
    let mut s = String::new();
    if kind == "handover" {
        let _ = writeln!(s, "# Handover: {}\n", f.name);
        s.push_str("## Recent work\n");
        bullets(&mut s, &f.recent, "No git history found.");
        s.push_str("\n## Open branches\n");
        bullets(&mut s, &f.branches, "No branches found.");
        s.push_str("\n## Pending tasks\n- Check the open branches above and the issue tracker; nothing is recorded in the folder.\n");
        s.push_str("\n## Risky areas (changed most in 90 days)\n");
        bullets(&mut s, &f.hot, "No recent changes.");
        s.push_str("\n## How to deploy\n");
        bullets(&mut s, &code_list(&f.deploy), "No deploy files found; ask the owners below.");
        s.push_str("\n## People\n");
        bullets(&mut s, &f.owners, "No git history found.");
        return s;
    }
    let _ = writeln!(s, "# Project guide: {}\n", f.name);
    s.push_str("## What it does\n");
    let intro = f
        .readme
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty() && !l.starts_with('#') && !l.starts_with('[') && !l.starts_with('<'));
    let _ = writeln!(s, "{}\n", intro.unwrap_or("The README doesn't say; start with the top-level folders below."));
    s.push_str("## How to run and test it\n");
    bullets(&mut s, &scripts(f), "No build file found.");
    s.push_str("\n## Map of the code\n");
    bullets(&mut s, &code_list(&f.top), "Empty folder.");
    s.push_str("\n## Key files\n");
    bullets(&mut s, &code_list(&f.manifests.keys().cloned().collect::<Vec<_>>()), "None found.");
    s.push_str("\n## Tests\n");
    bullets(&mut s, &code_list(&f.tests), "No test files found.");
    s.push_str("\n## Where to start for common changes\n");
    bullets(&mut s, &f.hot, "No recent changes to learn from.");
    s.push_str(
        "\n## Gotchas\n- Files that change often (above) break most often: run the tests after touching them.\n",
    );
    s.push_str("\n## People and owners\n");
    bullets(&mut s, &f.owners, "No git history found.");
    if !f.codeowners.is_empty() {
        let _ = writeln!(s, "\nFrom `CODEOWNERS`:\n\n{}", f.codeowners);
    }
    if beginner {
        s.push_str("\n## New here?\nRead the README first, then open one key file and its test side by side. Ask Hypurr about anything you don't recognise.\n");
    }
    s
}

pub const GUIDE_SYSTEM: &str = "You write onboarding docs for a software project from facts Hypurr collected from the folder. Use only the facts. Name files with their exact relative path in backticks; never invent a path, command or person. Plain words. Markdown only, no preamble.";

pub fn prompt(kind: &str, facts: &str, profile: Option<&str>) -> String {
    let sections = if kind == "handover" {
        "# Handover: <project>\n## Recent work\n## Open branches and PRs\n## Pending tasks\n## Risky areas\n## How to deploy\n## Who to ask"
    } else {
        "# Project guide: <project>\n## What it does\n## How to run and test it\n## Map of the code\n## Key files\n## Where to start for common changes\n## Gotchas\n## People and owners"
    };
    let mut p = format!(
        "Write this document with these sections:\n{sections}\n\nIf the facts don't cover a section, say so in one line.\n"
    );
    if let Some(pr) = profile {
        let _ = write!(p, "\n{pr}\n");
    }
    let _ = write!(p, "\nFacts:\n{facts}");
    p
}

pub(crate) fn pick_bot(store: &crate::store::Store, wanted: Option<&str>) -> Option<crate::store::BotConfig> {
    let bots = store.bots().ok()?;
    let usable = |b: &&crate::store::BotRow| !b.deleted && !b.config.is_group();
    wanted
        .and_then(|id| bots.iter().filter(usable).find(|b| b.config.id == id))
        .or_else(|| bots.iter().find(usable))
        .map(|b| b.config.clone())
}

pub(crate) fn project_dir(store: &crate::store::Store, b: &Value) -> Result<PathBuf> {
    let project = b["project"].as_str().unwrap_or_default();
    ensure!(
        crate::tasks::projects(store).iter().any(|p| p.path == project),
        "Pick one of this computer's project folders."
    );
    let dir = PathBuf::from(project);
    ensure!(dir.is_dir(), "That folder isn't there any more.");
    Ok(dir)
}

pub(crate) async fn ask_agent(hub: &Arc<Hub>, bot: Option<&str>, system: &str, prompt: &str) -> Option<String> {
    let cfg = pick_bot(&hub.store, bot)?;
    match tokio::time::timeout(Duration::from_secs(180), crate::chat::memory::one_shot(hub, &cfg, system, prompt)).await
    {
        Ok(Ok(a)) if !a.trim().is_empty() => Some(a),
        Ok(Ok(_)) => None,
        Ok(Err(e)) => {
            tracing::info!(error = %e, "guide agent failed; using the facts only");
            None
        }
        Err(_) => {
            tracing::info!("guide agent timed out");
            None
        }
    }
}

/// `projectGuide {project, kind: guide|handover, botId?}` → `{markdown, title, by, cited, dropped}`.
pub async fn generate(hub: &Arc<Hub>, b: &Value, actor_key: &str) -> Result<Value> {
    let dir = project_dir(&hub.store, b)?;
    let kind = if b["kind"] == "handover" { "handover" } else { "guide" };
    let facts = { tokio::task::spawn_blocking(move || scan(&dir)).await? };
    let prof = crate::chat::profile::load(&hub.store, Some(actor_key));
    let beginner = super::default_on(&prof);
    let mut md = fallback(kind, &facts, beginner);
    let mut by = "facts";
    if let Some(a) =
        ask_agent(hub, b["botId"].as_str(), GUIDE_SYSTEM, &prompt(kind, &render(&facts), prof.render().as_deref()))
            .await
    {
        a.trim().trim_start_matches("```markdown").trim_end_matches("```").trim().clone_into(&mut md);
        by = "agent";
    }
    let (markdown, cited, dropped) = ground(&md, &facts.files);
    let title =
        if kind == "handover" { format!("Handover: {}", facts.name) } else { format!("Project guide: {}", facts.name) };
    Ok(json!({"markdown": markdown, "title": title, "kind": kind, "by": by, "cited": cited, "dropped": dropped}))
}

fn words(q: &str) -> Vec<String> {
    const STOP: &[&str] = &[
        "what", "where", "which", "does", "this", "that", "with", "from", "have", "how", "the", "and", "for", "when",
        "why", "into", "about", "project", "code",
    ];
    q.split(|c: char| !c.is_alphanumeric() && c != '_')
        .map(str::to_lowercase)
        .filter(|w| w.len() >= 3 && !STOP.contains(&w.as_str()))
        .collect()
}

/// Files whose name or text mention the question's words: `(path, [(line, text)])`, best first.
pub fn search(root: &Path, files: &BTreeSet<String>, q: &str) -> Vec<(String, Vec<(usize, String)>)> {
    let ws = words(q);
    if ws.is_empty() {
        return vec![];
    }
    let mut scored = vec![];
    for f in files.iter().take(5000) {
        let lower = f.to_lowercase();
        let mut score = ws.iter().filter(|w| lower.contains(w.as_str())).count() * 5;
        let mut hits = vec![];
        if std::fs::metadata(root.join(f)).is_ok_and(|m| m.len() < 200_000)
            && let Ok(text) = std::fs::read_to_string(root.join(f))
        {
            for (i, l) in text.lines().enumerate() {
                let ll = l.to_lowercase();
                let n = ws.iter().filter(|w| ll.contains(w.as_str())).count();
                if n > 0 {
                    score += n;
                    if hits.len() < 3 {
                        hits.push((i + 1, l.trim().chars().take(160).collect()));
                    }
                }
            }
        }
        if score > 0 {
            scored.push((score, f.clone(), hits));
        }
    }
    scored.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    scored.into_iter().take(6).map(|(_, f, h)| (f, h)).collect()
}

pub const ASK_SYSTEM: &str = "You answer a question about a software project using only the code excerpts and facts given. Cite every claim with the file path in backticks, like `src/app.ts:12`. If the excerpts don't answer it, say so and name the files to look at. Short, plain words, Markdown.";

/// `projectAsk {project, question, botId?}` → `{answer, sources[{path, line, text}], by, cited, dropped}`.
#[allow(clippy::many_single_char_names)]
pub async fn ask(hub: &Arc<Hub>, b: &Value, actor_key: &str) -> Result<Value> {
    let dir = project_dir(&hub.store, b)?;
    let q = b["question"].as_str().unwrap_or_default().trim().to_owned();
    ensure!(!q.is_empty(), "Type a question about this project.");
    let (facts, hits) = {
        let q = q.clone();
        tokio::task::spawn_blocking(move || {
            let f = scan(&dir);
            let h = search(&dir, &f.files, &q);
            (f, h)
        })
        .await?
    };
    let sources: Vec<Value> = hits
        .iter()
        .flat_map(|(p, hs)| {
            if hs.is_empty() {
                vec![json!({"path": p, "line": Value::Null, "text": ""})]
            } else {
                hs.iter().map(|(n, t)| json!({"path": p, "line": n, "text": t})).collect()
            }
        })
        .collect();
    let mut answer = if hits.is_empty() {
        "I couldn't find that in this folder. Try the words the code would use (a screen, a function or an error message).".to_owned()
    } else {
        let mut s = String::from("These files mention it:\n");
        for (p, hs) in &hits {
            match hs.first() {
                Some((n, t)) => {
                    let _ = writeln!(s, "- `{p}:{n}`: {t}");
                }
                None => {
                    let _ = writeln!(s, "- `{p}`");
                }
            }
        }
        s
    };
    let mut by = "search";
    let prof = crate::chat::profile::load(&hub.store, Some(actor_key));
    let mut prompt = format!("Question: {q}\n\nCode excerpts (path:line: text):\n");
    for s in &sources {
        let _ = writeln!(
            prompt,
            "{}:{}: {}",
            s["path"].as_str().unwrap_or(""),
            s["line"],
            s["text"].as_str().unwrap_or("")
        );
    }
    let _ = write!(prompt, "\nProject facts:\n{}", head(&render(&facts), 200, 8000));
    if let Some(pr) = prof.render() {
        let _ = write!(prompt, "\n\n{pr}");
    }
    if !hits.is_empty()
        && let Some(a) = ask_agent(hub, b["botId"].as_str(), ASK_SYSTEM, &prompt).await
    {
        a.trim().clone_into(&mut answer);
        by = "agent";
    }
    let (answer, cited, dropped) = ground(&answer, &facts.files);
    Ok(json!({"answer": answer, "sources": sources, "by": by, "cited": cited, "dropped": dropped}))
}

/// The task goal that saves the (edited) guide as `docs/HYPURR_GUIDE.md`.
pub fn save_goal(markdown: &str) -> String {
    format!(
        "Save the project guide as docs/HYPURR_GUIDE.md\n\nCreate (or replace) the file `docs/HYPURR_GUIDE.md` with exactly the Markdown between the lines below. Change no other file.\n\n----- BEGIN -----\n{}\n----- END -----",
        markdown.trim()
    )
}

/// `projectGuideSave {project, markdown}`: a normal safe-branch task; its changes go through review as usual.
pub async fn save(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let dir = project_dir(&hub.store, b)?;
    let md = b["markdown"].as_str().unwrap_or_default();
    ensure!(!md.trim().is_empty(), "The guide is empty.");
    let mut body = json!({"goal": save_goal(md), "project": dir.to_string_lossy()});
    if b["startedBy"].is_object() {
        body["startedBy"] = b["startedBy"].clone();
    }
    crate::tasks::start(hub, &body).await.map_err(|e| anyhow!("{e:#}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp() -> PathBuf {
        let d = std::env::temp_dir().join(format!("hypurr-guide-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(d.join("src/api")).unwrap();
        std::fs::create_dir_all(d.join("node_modules/x")).unwrap();
        std::fs::write(d.join("README.md"), "# Shop\n\nA small shop that sells socks.\n").unwrap();
        std::fs::write(d.join("package.json"), "{\"scripts\": {\"test\": \"jest\"}}").unwrap();
        std::fs::write(d.join("src/api/cart.ts"), "export function addToCart(item) {\n  return checkout(item)\n}\n")
            .unwrap();
        std::fs::write(d.join("src/api/cart.test.ts"), "test('adds to cart', () => {})\n").unwrap();
        std::fs::write(d.join("node_modules/x/index.js"), "x").unwrap();
        d
    }

    #[test]
    fn scan_skips_dependencies_and_finds_basics() {
        let d = tmp();
        let f = scan(&d);
        assert!(f.files.contains("src/api/cart.ts"));
        assert!(!f.files.iter().any(|p| p.starts_with("node_modules")));
        assert!(f.manifests.contains_key("package.json"));
        assert!(f.tests.contains(&"src/api/cart.test.ts".to_owned()));
        assert!(f.readme.contains("socks"));
    }

    #[test]
    fn ground_drops_made_up_paths() {
        let files: BTreeSet<String> = ["src/api/cart.ts", "package.json"].iter().map(|s| (*s).to_owned()).collect();
        let md = "# Guide\n- `src/api/cart.ts:2` adds items\n- `src/payments/stripe.ts` charges cards\nSee [cart](src/api/cart.ts) and [docs](docs/ghost.md).\nRun `npm test` and read `package.json`, not `lib/ghost.rs`.\n- The `src/` folder holds the code";
        let (out, cited, dropped) = ground(md, &files);
        assert!(out.contains("`src/api/cart.ts:2`"));
        assert!(!out.contains("stripe"), "bullet with only a fake path is removed");
        assert!(out.contains("[cart](src/api/cart.ts)") && out.contains(" docs."));
        assert!(out.contains("`npm test`") && out.contains("not lib/ghost.rs"));
        assert!(out.contains("`src/`"), "folders count");
        assert_eq!(cited, vec!["package.json", "src/", "src/api/cart.ts"]);
        assert_eq!(dropped, vec!["docs/ghost.md", "lib/ghost.rs", "src/payments/stripe.ts"]);
    }

    #[test]
    fn fallback_guides_use_only_real_files() {
        let d = tmp();
        let f = scan(&d);
        for kind in ["guide", "handover"] {
            let md = fallback(kind, &f, true);
            let (_, _, dropped) = ground(&md, &f.files);
            assert_eq!(dropped, Vec::<String>::new(), "{kind}: {md}");
        }
        assert!(fallback("guide", &f, false).contains("A small shop that sells socks."));
        assert!(fallback("guide", &f, false).contains("npm test"));
        assert!(fallback("handover", &f, false).starts_with("# Handover"));
    }

    #[test]
    fn search_finds_files_and_lines() {
        let d = tmp();
        let f = scan(&d);
        let hits = search(&d, &f.files, "Where is checkout handled?");
        assert_eq!(hits[0].0, "src/api/cart.ts");
        assert_eq!(hits[0].1[0].0, 2);
        assert_eq!(search(&d, &f.files, "the and what").len(), 0);
    }

    #[test]
    fn save_goal_names_only_the_guide_file() {
        let g = save_goal("# Guide\n");
        assert!(g.contains("docs/HYPURR_GUIDE.md") && g.contains("Change no other file") && g.contains("# Guide"));
    }
}
