//! End to end: a beginner task on a real git project. The host routes it, isolates it
//! on its own branch and worktree, explains and risk-labels approvals, refuses a push
//! to main by itself, checkpoints after the step, and rolls back (and forward) on request.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // test code: failing loudly is the point

mod common;

use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

struct Host {
    child: Child,
    base: String,
    token: String,
    home: PathBuf,
}

impl Drop for Host {
    fn drop(&mut self) {
        common::stop(&mut self.child);
        let _ = std::fs::remove_dir_all(&self.home);
    }
}

async fn start_host() -> Host {
    let home = std::env::temp_dir().join(format!("hypurr-tasks-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&home).unwrap();
    let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
    let child = Command::new(env!("CARGO_BIN_EXE_hypurr-host"))
        .args(["serve", "--bind", "127.0.0.1", "--port", &port.to_string()])
        .env("HYPURR_HOME", &home)
        .env("HYPURR_CLOUD", "off")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let base = format!("http://127.0.0.1:{port}");
    let deadline = Instant::now() + Duration::from_secs(20);
    while reqwest::get(format!("{base}/health")).await.is_err() {
        assert!(Instant::now() < deadline, "host didn't start");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    let token = std::fs::read_to_string(home.join("token")).unwrap().trim().to_owned();
    Host { child, base, token, home }
}

impl Host {
    async fn try_call(&self, method: &str, body: Value) -> (bool, Value) {
        let res = reqwest::Client::new()
            .post(format!("{}/api/{method}", self.base))
            .bearer_auth(&self.token)
            .json(&body)
            .send()
            .await
            .unwrap();
        let ok = res.status().is_success();
        (ok, res.json().await.unwrap())
    }

    async fn call(&self, method: &str, body: Value) -> Value {
        let (ok, v) = self.try_call(method, body).await;
        assert!(ok, "{method} failed: {v}");
        v
    }

    async fn wait_for(&self, bot: &str, pred: impl Fn(&Value) -> bool) -> Value {
        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            let h = self.call("history", json!({"botId": bot})).await;
            if let Some(e) = h["entries"].as_array().unwrap().iter().rev().find(|e| pred(e)) {
                return e.clone();
            }
            assert!(Instant::now() < deadline, "timed out; history: {h}");
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }

    async fn task(&self, id: &str) -> Value {
        let list = self.call("tasks", json!({})).await;
        list["tasks"].as_array().unwrap().iter().find(|t| t["id"] == id).unwrap().clone()
    }
}

fn git(dir: &Path, args: &[&str]) -> String {
    let out = Command::new("git")
        .arg("-C")
        .arg(dir)
        .args(["-c", "user.name=T", "-c", "user.email=t@t"])
        .args(args)
        .output()
        .unwrap();
    assert!(out.status.success(), "git {args:?}: {}", String::from_utf8_lossy(&out.stderr));
    String::from_utf8_lossy(&out.stdout).trim().to_owned()
}

#[tokio::test]
async fn task_runs_isolated_with_explained_approvals_and_rollback() {
    if Command::new("python3").arg("--version").output().is_err()
        || Command::new("git").arg("--version").output().is_err()
    {
        eprintln!("skipping: python3 or git not available");
        return;
    }
    let host = start_host().await;
    let repo = host.home.join("shop");
    std::fs::create_dir_all(&repo).unwrap();
    git(&repo, &["init", "-q", "-b", "main"]);
    std::fs::write(repo.join("app.js"), "console.log(1)\n").unwrap();
    git(&repo, &["add", "."]);
    git(&repo, &["commit", "-q", "-m", "init"]);

    // Projects and routing.
    let p = host.call("saveProject", json!({"path": repo, "name": "Shop", "keywords": ["checkout"]})).await;
    let repo_path = p["project"]["path"].as_str().unwrap().to_owned();
    let setup = host.call("taskSetup", json!({})).await;
    assert_eq!(setup["templates"].as_array().unwrap().len(), 5);
    assert_eq!(setup["safety"]["blockProtected"], true);
    let route = host.call("routeTask", json!({"goal": "fix the checkout button"})).await;
    assert_eq!(route["route"]["project"]["name"], "Shop");
    assert!(route["route"]["reason"].as_str().unwrap().contains("checkout"));

    // Start from a template, with the scripted agent.
    let agent = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fake_task_agent.py");
    let started = host
        .call(
            "startTask",
            json!({"goal": "the checkout button", "template": "write-tests", "project": repo_path,
                   "backend": "custom", "command": format!("python3 '{}'", agent.display())}),
        )
        .await;
    let task = &started["task"];
    let task_id = task["id"].as_str().unwrap().to_owned();
    let bot = started["bot"]["id"].as_str().unwrap().to_owned();
    assert_eq!(started["bot"]["task"]["id"], task_id.as_str());
    let branch = task["branch"].as_str().unwrap().to_owned();
    assert!(branch.starts_with("hypurr/write-tests-the-checkout-button-"), "{branch}");
    assert_eq!(task["base"], "main");
    let worktree = PathBuf::from(task["worktree"].as_str().unwrap());
    assert_eq!(git(&worktree, &["symbolic-ref", "--short", "HEAD"]), branch);
    assert_eq!(git(&repo, &["symbolic-ref", "--short", "HEAD"]), "main");
    let start_cp = task["checkpoints"][0]["id"].as_str().unwrap().to_owned();

    // The chat shows the plain request, not the rules the agent gets.
    let first = host.wait_for(&bot, |e| e["kind"] == "user").await;
    assert_eq!(first["data"]["text"], "Write tests: the checkout button");

    // The push to main is refused without asking, and says why.
    let blocked = host.wait_for(&bot, |e| e["kind"] == "permission" && e["data"]["blocked"].is_string()).await;
    assert_eq!(blocked["data"]["risk"], "high");
    assert_eq!(blocked["data"]["status"], "answered");
    assert_eq!(blocked["data"]["selected"], "no");
    assert!(blocked["data"]["blocked"].as_str().unwrap().contains("main"));

    // The delete asks, explained, high risk, with a checkpoint to undo to.
    let card = host.wait_for(&bot, |e| e["kind"] == "permission" && e["data"]["status"] == "pending").await;
    assert_eq!(card["data"]["risk"], "high");
    assert_eq!(card["data"]["explain"], "The agent wants to delete build and everything inside.");
    assert_ne!(card["data"]["riskReasons"].as_array().unwrap().len(), 0);
    assert_eq!(card["data"]["checkpoint"], start_cp.as_str());
    host.call("respondPermission", json!({"entryId": card["id"], "optionId": "allow"})).await;

    let reply = host.wait_for(&bot, |e| e["kind"] == "agent" && e["data"]["final"] == true).await;
    assert_eq!(reply["data"]["text"], "push=no rm=allow rules=True");

    // A checkpoint after the step holds the agent's change, on the task branch only.
    let deadline = Instant::now() + Duration::from_secs(10);
    let after = loop {
        let t = host.task(&task_id).await;
        if let Some(cp) =
            t["checkpoints"].as_array().unwrap().iter().find(|c| c["label"].as_str().unwrap().starts_with("After step"))
        {
            break cp.clone();
        }
        assert!(Instant::now() < deadline, "no checkpoint after the turn: {t}");
        tokio::time::sleep(Duration::from_millis(100)).await;
    };
    assert_eq!(after["files"], json!(["result.txt"]));
    assert!(worktree.join("result.txt").exists());
    assert!(!repo.join("result.txt").exists());

    // One-tap rollback to the start, then forward again.
    let back = host.call("rollbackTask", json!({"taskId": task_id, "checkpointId": start_cp})).await;
    assert!(!worktree.join("result.txt").exists());
    let saved =
        back["task"]["checkpoints"].as_array().unwrap().iter().find(|c| c["label"] == "Before going back").cloned();
    // Nothing changed since the last checkpoint, so going forward uses the "After step" one.
    let forward = saved.map_or_else(|| after["id"].clone(), |c| c["id"].clone());
    host.call("rollbackTask", json!({"taskId": task_id, "checkpointId": forward})).await;
    assert!(worktree.join("result.txt").exists());
    let (ok, err) = host.try_call("rollbackTask", json!({"taskId": task_id, "checkpointId": "deadbeef00"})).await;
    assert!(!ok && err.to_string().contains("unknown checkpoint"), "{err}");
    host.wait_for(&bot, |e| {
        e["kind"] == "notice" && e["data"]["text"].as_str().is_some_and(|t| t.starts_with("Went back"))
    })
    .await;

    // Finish, then delete: the folder goes, the branch (every checkpoint) stays.
    let done = host.call("finishTask", json!({"taskId": task_id})).await;
    assert_eq!(done["task"]["status"], "finished");
    host.call("deleteBot", json!({"botId": bot})).await;
    assert!(!worktree.exists());
    git(&repo, &["rev-parse", "--verify", &branch]);
    assert_eq!(git(&repo, &["log", "-1", "--format=%s", "main"]), "init");
}

#[tokio::test]
async fn templates_and_safety_settings_round_trip() {
    let host = start_host().await;
    let t =
        host.call("saveTemplate", json!({"title": "Add docs", "prompt": "Document {goal}", "icon": "custom"})).await;
    let id = t["template"]["id"].as_str().unwrap().to_owned();
    assert_eq!(host.call("taskTemplates", json!({})).await["templates"].as_array().unwrap().len(), 6);
    let (ok, _) = host.try_call("saveTemplate", json!({"id": "fix-error", "title": "x", "prompt": "y"})).await;
    assert!(!ok);
    host.call("deleteTemplate", json!({"id": id})).await;
    let s = host.call("setSafetySettings", json!({"protectedBranches": ["main", "staging"], "requireGit": true})).await;
    assert_eq!(s["safety"]["protectedBranches"], json!(["main", "staging"]));
    // A folder that isn't a git project is refused when the safety net requires git.
    let plain = host.home.join("plain");
    std::fs::create_dir_all(&plain).unwrap();
    let (ok, err) = host
        .try_call("startTask", json!({"goal": "x", "project": plain, "backend": "custom", "command": "true"}))
        .await;
    assert!(!ok && err.to_string().contains("isn't a git project"), "{err}");
}
