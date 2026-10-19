//! QA test writer: from a ticket (Stage 4's parsing), pasted requirements, or a screen /
//! feature name, the agent writes manual test cases in plain words (title, steps, expected
//! result, priority, positive / negative / edge, and a "why" for people new to QA). The
//! phone edits and exports them (CSV, Jira, `Xray`, `TestRail`, `Zephyr` text; never posted).
//! Picked cases can be automated in the project's own test framework on a safe-branch task;
//! Hypurr then runs that framework itself and reads which cases the tests cover (case ids
//! in test names), so the pass / fail and the coverage hint come from the code, not from
//! what the agent says.

#![allow(clippy::assigning_clones, clippy::items_after_statements, clippy::map_unwrap_or)]

use crate::hub::Hub;
use crate::tasks::Task;
use anyhow::{Result, anyhow, ensure};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct Case {
    /// `TC-1`, `TC-2`, … (used in automated test names for coverage).
    pub id: String,
    pub title: String,
    pub steps: Vec<String>,
    pub expected: String,
    /// high | medium | low
    pub priority: String,
    /// positive | negative | edge
    pub kind: String,
    /// Why this test matters, in plain words (people new to QA).
    pub why: String,
    /// The agent thinks it can be automated.
    pub automatable: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Framework {
    /// junit | pytest | jest | playwright | cypress | testng | espresso
    pub id: String,
    pub name: String,
    pub command: String,
    /// Needs a phone / emulator or a browser attached to the computer.
    pub needs_device: bool,
}

fn fw(id: &str, name: &str, command: &str, needs_device: bool) -> Framework {
    Framework { id: id.into(), name: name.into(), command: command.into(), needs_device }
}

fn read(dir: &Path, f: &str) -> String {
    std::fs::read_to_string(dir.join(f)).unwrap_or_default().to_lowercase()
}

/// The test frameworks this project already uses, best first.
pub fn frameworks(dir: &Path) -> Vec<Framework> {
    let mut out = vec![];
    let pkg = read(dir, "package.json");
    if pkg.contains("@playwright/test") {
        out.push(fw("playwright", "Playwright", "npx playwright test", true));
    }
    if pkg.contains("\"cypress\"") {
        out.push(fw("cypress", "Cypress", "npx cypress run", true));
    }
    if pkg.contains("\"jest\"") {
        out.push(fw("jest", "Jest", "npx jest", false));
    }
    let py = [read(dir, "requirements.txt"), read(dir, "pyproject.toml"), read(dir, "requirements-dev.txt")].concat();
    if py.contains("pytest") || dir.join("pytest.ini").exists() || dir.join("conftest.py").exists() {
        out.push(fw("pytest", "pytest", "pytest", false));
    }
    let pom = read(dir, "pom.xml");
    if pom.contains("testng") || pom.contains("selenium") {
        out.push(fw("testng", "Selenium / TestNG", "mvn test", pom.contains("selenium")));
    } else if pom.contains("junit") {
        out.push(fw("junit", "JUnit", "mvn test", false));
    }
    let gradle_files = ["build.gradle", "build.gradle.kts", "app/build.gradle", "app/build.gradle.kts"];
    let gradle: String = gradle_files.iter().map(|f| read(dir, f)).collect();
    if !gradle.is_empty() {
        let wrapper = if dir.join("gradlew").exists() { "./gradlew" } else { "gradle" };
        if gradle.contains("espresso") {
            out.push(fw("espresso", "Espresso", &format!("{wrapper} connectedAndroidTest"), true));
        }
        if gradle.contains("junit") || gradle.contains("android") {
            out.push(fw("junit", "JUnit", &format!("{wrapper} test"), false));
        }
    }
    out
}

pub const CASES_PROMPT: &str = "You are a careful QA engineer. Write manual test cases for what's described. Don't change any files. Cover positive, negative and edge cases; 6 to 12 cases. Use exactly this shape for each case, separated by a line with ---:\n\
CASE: short title\n\
TYPE: positive | negative | edge\n\
PRIORITY: high | medium | low\n\
STEPS:\n1. a step a tester can follow\n\
EXPECTED: what should happen\n\
WHY: one plain sentence on why this test matters\n\
AUTOMATE: yes | no (yes when it can be an automated test in the project's framework)\n\
Plain words. If the person is new to QA, make WHY teach the idea behind the test.";

pub fn cases_prompt(subject: &str, profile: Option<&str>, frameworks: &[Framework]) -> String {
    let mut s = String::new();
    if let Some(p) = profile {
        let _ = writeln!(s, "About the person:\n{p}\n");
    }
    let _ = writeln!(s, "What to test:\n{}", crate::agent::acp::truncate(subject.trim(), 6000));
    if !frameworks.is_empty() {
        let names: Vec<&str> = frameworks.iter().map(|f| f.name.as_str()).collect();
        let _ = writeln!(s, "\nThe project's test frameworks: {}", names.join(", "));
    }
    s
}

fn norm(v: &str, allowed: &[&str], default: &str) -> String {
    let v = v.trim().to_lowercase();
    allowed.iter().find(|a| v.starts_with(*a)).map_or(default, |a| a).to_owned()
}

/// Reads the agent's cases. Cases without a title or expected result are dropped.
pub fn parse_cases(raw: &str) -> Vec<Case> {
    let mut out = vec![];
    for block in raw.split("\n---") {
        let mut c = Case::default();
        let mut section = "";
        for line in block.lines() {
            let l = line.trim().trim_start_matches(['*', '#']).trim();
            let upper = l.to_uppercase();
            let field = |name: &str| {
                upper.starts_with(name).then(|| l[name.len()..].trim_start_matches(['*', ':']).trim().to_owned())
            };
            if let Some(v) = field("CASE") {
                c.title = v;
                section = "";
            } else if let Some(v) = field("TYPE") {
                c.kind = norm(&v, &["positive", "negative", "edge"], "positive");
            } else if let Some(v) = field("PRIORITY") {
                c.priority = norm(&v, &["high", "medium", "low"], "medium");
            } else if field("STEPS").is_some() {
                section = "steps";
            } else if let Some(v) = field("EXPECTED") {
                c.expected = v;
                section = "expected";
            } else if let Some(v) = field("WHY") {
                c.why = v;
                section = "why";
            } else if let Some(v) = field("AUTOMATE") {
                c.automatable = v.to_lowercase().starts_with('y');
                section = "";
            } else if !l.is_empty() {
                match section {
                    "steps" => {
                        let step = l
                            .trim_start_matches(|ch: char| ch.is_ascii_digit())
                            .trim_start_matches(['.', ')', '-'])
                            .trim();
                        if !step.is_empty() {
                            c.steps.push(step.to_owned());
                        }
                    }
                    "expected" => {
                        c.expected.push(' ');
                        c.expected.push_str(l);
                    }
                    "why" => {
                        c.why.push(' ');
                        c.why.push_str(l);
                    }
                    _ => {}
                }
            }
        }
        if !c.title.is_empty() && !c.expected.trim().is_empty() {
            if c.kind.is_empty() {
                c.kind = "positive".into();
            }
            if c.priority.is_empty() {
                c.priority = "medium".into();
            }
            c.id = format!("TC-{}", out.len() + 1);
            out.push(c);
        }
    }
    out
}

/// When the agent can't be reached: the three cases every feature needs.
pub fn fallback_cases(subject: &str) -> Vec<Case> {
    let name = subject.lines().map(str::trim).find(|l| !l.is_empty()).unwrap_or("the feature");
    let name = crate::agent::acp::truncate(name, 60);
    let mk = |i: usize, title: String, kind: &str, priority: &str, expected: &str, why: &str| Case {
        id: format!("TC-{i}"),
        title,
        steps: vec![format!("Open {name}"), "Do the main action with valid input".into()],
        expected: expected.into(),
        priority: priority.into(),
        kind: kind.into(),
        why: why.into(),
        automatable: false,
    };
    vec![
        mk(
            1,
            format!("{name} works with valid input"),
            "positive",
            "high",
            "It does what the requirement says.",
            "Checks the main path people use every day.",
        ),
        mk(
            2,
            format!("{name} rejects invalid input"),
            "negative",
            "high",
            "A clear error appears and nothing breaks.",
            "Checks the product fails safely when people make mistakes.",
        ),
        mk(
            3,
            format!("{name} at the limits"),
            "edge",
            "medium",
            "Empty, very long and boundary values are handled.",
            "Bugs hide at the edges: zero, maximum, empty.",
        ),
    ]
}

/// `qaCases {source: ticket|text|feature, input, project?, botId?}`.
pub async fn cases(hub: &Arc<Hub>, b: &Value, actor_key: &str) -> Result<Value> {
    let input = b["input"].as_str().unwrap_or_default().trim().to_owned();
    ensure!(!input.is_empty(), "Paste a ticket, the requirements, or name the screen to test.");
    let project = b["project"].as_str().unwrap_or_default().to_owned();
    let mut ticket = Value::Null;
    let subject = match b["source"].as_str().unwrap_or("text") {
        "ticket" => {
            let r = crate::ticket::resolve(&hub.store, &json!({"input": input, "project": project})).await?;
            if r.get("needsSetup").is_some() {
                return Ok(r);
            }
            ticket = r["ticket"].clone();
            format!(
                "Ticket {} {}\n{}",
                ticket["key"].as_str().unwrap_or(""),
                ticket["title"].as_str().unwrap_or(""),
                ticket["body"].as_str().unwrap_or("")
            )
        }
        "feature" => format!("The screen or feature called “{input}”. Test what a user can do there."),
        _ => input.clone(),
    };
    let fws = if project.is_empty() {
        vec![]
    } else {
        let p = PathBuf::from(&project);
        tokio::task::spawn_blocking(move || frameworks(&p)).await?
    };
    let prof = crate::chat::profile::load(&hub.store, Some(actor_key));
    // New to QA (or a student): the phone shows the "why" under each case by default.
    let explain = prof.experience.as_deref() == Some("New") || prof.role.as_deref() == Some("Student");
    let profile = prof.render();
    let mut list = fallback_cases(if b["source"] == "feature" { &input } else { &subject });
    let mut by = "template";
    if let Some(cfg) = pick_bot(&hub.store, b["botId"].as_str()) {
        let prompt = cases_prompt(&subject, profile.as_deref(), &fws);
        let ask = crate::chat::memory::one_shot(hub, &cfg, CASES_PROMPT, &prompt);
        match tokio::time::timeout(Duration::from_secs(150), ask).await {
            Ok(Ok(answer)) => {
                let parsed = parse_cases(&answer);
                if !parsed.is_empty() {
                    list = parsed;
                    by = "agent";
                }
            }
            Ok(Err(e)) => tracing::info!(error = %e, "qa agent failed; using the basic cases"),
            Err(_) => tracing::info!("qa agent timed out"),
        }
    }
    Ok(json!({"cases": list, "by": by, "frameworks": fws, "ticket": ticket, "explain": explain}))
}

fn pick_bot(store: &crate::store::Store, wanted: Option<&str>) -> Option<crate::store::BotConfig> {
    let bots = store.bots().ok()?;
    let usable = |b: &&crate::store::BotRow| !b.deleted && !b.config.is_group();
    wanted
        .and_then(|id| bots.iter().filter(usable).find(|b| b.config.id == id))
        .or_else(|| bots.iter().find(usable))
        .map(|b| b.config.clone())
}

/// The task's goal for automating the picked cases.
pub fn automate_goal(cases: &[Case], f: &Framework) -> String {
    let mut g = format!(
        "Write automated tests with {} (the framework this project already uses) for these manual test cases. Put the case id in each test's name or display name (for example `TC-3 rejects an expired code`) so Hypurr can see which cases are covered. Follow the project's existing test layout and style. Then run them with `{}` and say plainly how many passed and failed. Don't change product code unless a test can't be written otherwise; if so, explain why.\n",
        f.name, f.command
    );
    for c in cases {
        let _ = writeln!(g, "\n{} {} ({}, {})", c.id, c.title, c.kind, c.priority);
        for (i, s) in c.steps.iter().enumerate() {
            let _ = writeln!(g, "  {}. {s}", i + 1);
        }
        let _ = writeln!(g, "  Expected: {}", c.expected);
    }
    g
}

/// `qaAutomate {cases, framework, project}`: a safe-branch task that writes the tests.
pub async fn automate(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let cases: Vec<Case> = serde_json::from_value(b["cases"].clone()).map_err(|_| anyhow!("missing cases"))?;
    ensure!(!cases.is_empty(), "Pick at least one case to automate.");
    let project = b["project"].as_str().unwrap_or_default().to_owned();
    ensure!(
        crate::tasks::projects(&hub.store).iter().any(|p| p.path == project),
        "Pick one of this computer's project folders."
    );
    let p = PathBuf::from(&project);
    let fws = tokio::task::spawn_blocking(move || frameworks(&p)).await?;
    let f = fws
        .iter()
        .find(|f| Some(f.id.as_str()) == b["framework"].as_str())
        .or_else(|| fws.first())
        .cloned()
        .ok_or_else(|| anyhow!("This project has no test framework Hypurr recognises yet."))?;
    let title = format!("Automate {} test case{}", cases.len(), if cases.len() == 1 { "" } else { "s" });
    let out = crate::tasks::start(
        hub,
        &json!({"goal": format!("{title}\n\n{}", automate_goal(&cases, &f)), "project": project}),
    )
    .await?;
    if let Some(id) = out["task"]["id"].as_str()
        && let Some(mut task) = Task::load(&hub.store, id)
    {
        task.extra.insert("qa".into(), json!({"cases": cases, "framework": f}));
        task.save(&hub.store)?;
    }
    Ok(out)
}

/// Pass / fail counts from common runners' summaries.
pub fn counts(output: &str) -> (u64, u64) {
    let (mut passed, mut failed) = (0, 0);
    let mut prev: Option<u64> = None;
    let mut completed: Option<u64> = None;
    for word in output.split(|c: char| c.is_whitespace() || c == ',' || c == '(' || c == ')') {
        let w = word.trim_matches(|c: char| !c.is_ascii_alphanumeric()).to_lowercase();
        if let (Some(n), Some(kind)) = (prev, Some(w.as_str())) {
            match kind {
                "passed" | "passing" | "pass" => passed = n,
                "failed" | "failing" | "failures" | "fail" => failed = n,
                "tests" if completed.is_none() => completed = Some(n),
                _ => {}
            }
        }
        prev = w.parse().ok();
    }
    // Gradle: "12 tests completed, 2 failed" and no "passed".
    if passed == 0
        && let Some(total) = completed
    {
        passed = total.saturating_sub(failed);
    }
    (passed, failed)
}

/// Case ids that appear in the task's changed test files.
pub fn covered(dir: &Path, from: &str, cases: &[Case]) -> Vec<String> {
    let files = crate::tasks::finish::changes(dir, from);
    let text: String =
        files.iter().filter_map(|f| std::fs::read_to_string(dir.join(&f.path)).ok()).collect::<Vec<_>>().join("\n");
    cases
        .iter()
        .filter(|c| {
            let id = c.id.as_str();
            let alt = id.replace('-', "_");
            text.match_indices(id)
                .chain(text.match_indices(alt.as_str()))
                .any(|(i, m)| !text[i + m.len()..].chars().next().is_some_and(|ch| ch.is_ascii_digit()))
        })
        .map(|c| c.id.clone())
        .collect()
}

fn tail(s: &str, lines: usize) -> String {
    let all: Vec<&str> = s.lines().collect();
    all[all.len().saturating_sub(lines)..].join("\n")
}

/// `qaRun {taskId}`: runs the framework in the task's folder and reads the result.
pub async fn run(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let task_id = b["taskId"].as_str().ok_or_else(|| anyhow!("missing taskId"))?;
    let task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    let qa = task.extra.get("qa").cloned().ok_or_else(|| anyhow!("this isn't a QA task"))?;
    let f: Framework = serde_json::from_value(qa["framework"].clone())?;
    let cases: Vec<Case> = serde_json::from_value(qa["cases"].clone()).unwrap_or_default();
    ensure!(
        crate::hub::BotStatus::Working != hub.runtime(&task.bot_id).status,
        "The agent is still writing the tests. Wait for it to finish."
    );
    let dir = task.worktree_path().unwrap_or_else(|| PathBuf::from(&task.project));
    let started = std::time::Instant::now();
    let mut cmd = tokio::process::Command::new("sh");
    cmd.arg("-c").arg(&f.command).current_dir(&dir).kill_on_drop(true).env("CI", "1");
    let out = tokio::time::timeout(Duration::from_secs(900), cmd.output()).await;
    let (ok, output, timed_out) = match out {
        Ok(Ok(o)) => (
            o.status.success(),
            format!("{}{}", String::from_utf8_lossy(&o.stdout), String::from_utf8_lossy(&o.stderr)),
            false,
        ),
        Ok(Err(e)) => (false, format!("couldn't run {}: {e}", f.command), false),
        Err(_) => (false, "The tests took longer than 15 minutes and were stopped.".into(), true),
    };
    let (mut passed, failed) = counts(&output);
    if ok && passed == 0 && failed == 0 {
        passed = 1;
    }
    let automated = match task.checkpoints.first() {
        Some(cp) => {
            let (d, from, cs) = (dir.clone(), cp.id.clone(), cases.clone());
            tokio::task::spawn_blocking(move || covered(&d, &from, &cs)).await?
        }
        None => vec![],
    };
    let result = json!({
        "ok": ok,
        "passed": passed,
        "failed": failed,
        "timedOut": timed_out,
        "command": f.command,
        "seconds": started.elapsed().as_secs(),
        "output": crate::agent::acp::truncate(&tail(&output, 40), 4000),
        "automated": automated,
        "manual": cases.iter().map(|c| c.id.clone()).filter(|id| !automated.contains(id)).collect::<Vec<_>>(),
        "needsDevice": f.needs_device,
    });
    let mut task = task;
    if let Some(q) = task.extra.get_mut("qa") {
        q["result"] = result.clone();
    }
    task.save(&hub.store)?;
    Ok(json!({"result": result}))
}

/// `qaFrameworks {project}`.
pub async fn detect(b: &Value) -> Result<Value> {
    let p = PathBuf::from(b["project"].as_str().unwrap_or_default());
    Ok(json!({"frameworks": tokio::task::spawn_blocking(move || frameworks(&p)).await?}))
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    fn tmp() -> PathBuf {
        let d = std::env::temp_dir().join(format!("hypurr-qa-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn parses_cases_and_drops_broken_ones() {
        let raw = "**CASE:** Valid coupon lowers total\nTYPE: Positive\nPRIORITY: High\nSTEPS:\n1. Add a ₹500 item\n2) Apply SAVE10\nEXPECTED: Total shows ₹450\nWHY: The main promise of coupons.\nAUTOMATE: yes\n---\nCASE: no expected\nSTEPS:\n1. x\n---\nCASE: Expired coupon\nTYPE: negative\nPRIORITY: medium\nSTEPS:\n- Apply OLD5\nEXPECTED: 'This code has expired'\n and the total stays\nAUTOMATE: no";
        let c = parse_cases(raw);
        assert_eq!(c.len(), 2);
        assert_eq!((c[0].id.as_str(), c[0].kind.as_str(), c[0].priority.as_str()), ("TC-1", "positive", "high"));
        assert_eq!(c[0].steps, ["Add a ₹500 item", "Apply SAVE10"]);
        assert!(c[0].automatable && !c[1].automatable);
        assert_eq!(c[1].id, "TC-2");
        assert_eq!(c[1].expected, "'This code has expired' and the total stays");
        assert_eq!(c[1].steps, ["Apply OLD5"]);
    }

    #[test]
    fn fallback_has_all_three_kinds() {
        let c = fallback_cases("Checkout\nmore");
        let kinds: Vec<_> = c.iter().map(|c| c.kind.as_str()).collect();
        assert_eq!(kinds, ["positive", "negative", "edge"]);
        assert!(c[0].title.starts_with("Checkout"));
    }

    #[test]
    fn detects_frameworks() {
        let d = tmp();
        assert_eq!(frameworks(&d), vec![]);
        std::fs::write(d.join("package.json"), r#"{"devDependencies":{"jest":"29","@playwright/test":"1.4"}}"#)
            .unwrap();
        let ids: Vec<_> = frameworks(&d).into_iter().map(|f| f.id).collect();
        assert_eq!(ids, ["playwright", "jest"]);
        let d = tmp();
        std::fs::write(d.join("gradlew"), "").unwrap();
        std::fs::create_dir_all(d.join("app")).unwrap();
        std::fs::write(
            d.join("app/build.gradle.kts"),
            "androidTestImplementation(libs.espresso.core)\ntestImplementation(libs.junit)",
        )
        .unwrap();
        let f = frameworks(&d);
        assert_eq!(f[0].command, "./gradlew connectedAndroidTest");
        assert!(f[0].needs_device);
        assert_eq!(f[1].id, "junit");
        let d = tmp();
        std::fs::write(d.join("pom.xml"), "<artifactId>testng</artifactId><artifactId>selenium-java</artifactId>")
            .unwrap();
        assert_eq!(frameworks(&d)[0].name, "Selenium / TestNG");
        let d = tmp();
        std::fs::write(d.join("requirements.txt"), "pytest==8").unwrap();
        assert_eq!(frameworks(&d)[0].id, "pytest");
    }

    #[test]
    fn reads_runner_summaries() {
        assert_eq!(counts("==== 5 passed, 1 failed in 0.3s ===="), (5, 1));
        assert_eq!(counts("Tests:       2 failed, 10 passed, 12 total"), (10, 2));
        assert_eq!(counts("12 tests completed, 2 failed"), (10, 2));
        assert_eq!(counts("  3 passing (40ms)\n  1 failing"), (3, 1));
        assert_eq!(counts("BUILD SUCCESSFUL"), (0, 0));
    }

    #[test]
    fn goal_names_ids_and_command() {
        let c = fallback_cases("Login");
        let g = automate_goal(&c[..1], &fw("pytest", "pytest", "pytest", false));
        assert!(g.contains("TC-1 Login works") && g.contains("`pytest`") && g.contains("Expected:"));
    }

    #[test]
    fn coverage_reads_case_ids_in_changed_tests() {
        let d = tmp();
        let git = |args: &[&str]| {
            std::process::Command::new("git").arg("-C").arg(&d).args(args).output().unwrap();
        };
        git(&["init", "-q"]);
        git(&["-c", "user.email=a@b", "-c", "user.name=a", "commit", "-q", "--allow-empty", "-m", "start"]);
        let from = String::from_utf8(
            std::process::Command::new("git").arg("-C").arg(&d).args(["rev-parse", "HEAD"]).output().unwrap().stdout,
        )
        .unwrap();
        std::fs::write(d.join("test_cart.py"), "def test_TC_1_valid():\n    pass\n# TC-12 is not TC-1\n").unwrap();
        git(&["add", "."]);
        git(&["-c", "user.email=a@b", "-c", "user.name=a", "commit", "-q", "-m", "tests"]);
        let mut cases = fallback_cases("Cart");
        cases[2].id = "TC-12".into();
        assert_eq!(covered(&d, from.trim(), &cases), ["TC-1", "TC-12"]);
    }
}
