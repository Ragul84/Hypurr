//! Built-in Hypurr Agent: status, managed install (local archive), free models, and a
//! turn through a fake Hypurr Agent ACP agent.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

mod common;

use serde_json::{Value, json};
use std::path::PathBuf;
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

async fn start_host(extra: &[(&str, &str)]) -> Host {
    let home = std::env::temp_dir().join(format!("hypurr-builtin-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&home).unwrap();
    let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
    let mut cmd = Command::new(env!("CARGO_BIN_EXE_hypurr-host"));
    cmd.args(["serve", "--bind", "127.0.0.1", "--port", &port.to_string()])
        .env("HYPURR_HOME", &home)
        .env("HYPURR_CLOUD", "off")
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    for (k, v) in extra {
        cmd.env(k, v);
    }
    let child = cmd.spawn().unwrap();
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
    async fn call(&self, method: &str, body: Value) -> Value {
        let res = reqwest::Client::new()
            .post(format!("{}/api/{method}", self.base))
            .bearer_auth(&self.token)
            .json(&body)
            .send()
            .await
            .unwrap();
        let ok = res.status().is_success();
        let v: Value = res.json().await.unwrap();
        assert!(ok, "{method} failed: {v}");
        v
    }
}

fn tiny_hypurr_agent_archive() -> (PathBuf, String) {
    let dir = std::env::temp_dir().join(format!("hypurr-oc-arc-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let bin = dir.join("hypurr-agent");
    // A tiny script that pretends to be Hypurr Agent for `which` / `--version`.
    std::fs::write(&bin, "#!/bin/sh\nif [ \"$1\" = --version ]; then echo test-hypurr-agent; exit 0; fi\nexit 0\n")
        .unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    let tar = dir.join("hypurr-agent-linux-x64.tar.gz");
    let status = Command::new("tar").args(["-czf"]).arg(&tar).arg("-C").arg(&dir).arg("hypurr-agent").status().unwrap();
    assert!(status.success());
    let out = Command::new("sha256sum").arg(&tar).output().unwrap();
    assert!(out.status.success());
    let sum = String::from_utf8_lossy(&out.stdout).split_whitespace().next().unwrap().to_owned();
    (tar, sum)
}

#[tokio::test]
async fn builtin_status_install_models_and_fake_turn() {
    let (archive, sha) = tiny_hypurr_agent_archive();
    let host =
        start_host(&[("HYPURR_AGENT_ARCHIVE", &archive.to_string_lossy()), ("HYPURR_AGENT_SHA256", &sha)]).await;

    let hello = host.call("hello", json!({})).await;
    let builtin = &hello["builtinAgent"];
    assert_eq!(builtin["id"], "hypurr-agent");
    assert_eq!(builtin["builtin"], true);
    assert_eq!(builtin["free"], true);
    assert_eq!(builtin["defaultModel"], "hypurr/hypurr-free");
    assert!(builtin["needsInstall"] == true || builtin["installed"] == true);
    assert!(builtin["freeModels"].as_array().unwrap().len() >= 1);

    let backends = hello["backends"].as_array().unwrap();
    let oc = backends.iter().find(|b| b["id"] == "hypurr-agent").expect("hypurr-agent backend");
    assert_eq!(oc["builtin"], true);
    assert_eq!(oc["free"], true);

    // Without consent: refused.
    let res = reqwest::Client::new()
        .post(format!("{}/api/installBuiltinAgent", host.base))
        .bearer_auth(&host.token)
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert!(!res.status().is_success());

    let installed = host.call("installBuiltinAgent", json!({"consent": true})).await;
    assert_eq!(installed["installed"], true);
    assert!(host.home.join("agents/hypurr-agent").join(hypurr_host_version_dir()).join("hypurr-agent").is_file());

    // Free models without needing a live Hypurr Agent process when probe fails — but with our
    // tiny binary, ACP will fail; the API still returns the curated free list.
    let models = host.call("agentModels", json!({"backend": "hypurr-agent"})).await;
    assert!(models["models"].as_array().unwrap().iter().any(|m| m["id"] == "hypurr/hypurr-free"));
    assert_eq!(models["free"], true);

    // Task routing prefers the built-in when it's the installed agent.
    let setup = host.call("taskSetup", json!({})).await;
    // After install, hypurr-agent should be among agents (may be the only one).
    let agents = setup["agents"].as_array().unwrap();
    assert!(agents.iter().any(|a| a["id"] == "hypurr-agent"), "{agents:?}");

    // A chat turn through a fake Hypurr Agent ACP agent (custom command).
    let agent = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/hypurr_agent_acp.py");
    let bot = host
        .call(
            "createBot",
            json!({
                "name": "Builtin",
                "backend": "custom",
                "command": format!("python3 '{}'", agent.display()),
                "cwd": "",
            }),
        )
        .await;
    let bot_id = bot["bot"]["id"].as_str().unwrap();
    host.call("send", json!({"botId": bot_id, "text": "hi"})).await;
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        let h = host.call("history", json!({"botId": bot_id})).await;
        if h["entries"].as_array().unwrap().iter().any(|e| {
            e["kind"] == "agent"
                && e["data"]["final"] == true
                && e["data"]["text"].as_str().unwrap_or("").contains("HELLO_HYPURR")
        }) {
            break;
        }
        assert!(Instant::now() < deadline, "no reply: {h}");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }

    // Allowed-agents policy covers hypurr-agent by id.
    host.call("setPolicies", json!({"allowedAgents": ["hypurr-agent"]})).await;
    let setup = host.call("taskSetup", json!({})).await;
    assert_eq!(setup["agents"].as_array().unwrap().len(), 1);
    assert_eq!(setup["agents"][0]["id"], "hypurr-agent");
}

fn hypurr_host_version_dir() -> &'static str {
    "0.1.0"
}
