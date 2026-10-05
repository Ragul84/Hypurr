//! End to end for what phone apps show in a chat: a group answering by @-mention, a thread on
//! a bot's reply, reactions, a file sent with a message and fetched back, and bots created,
//! renamed and removed from a client. Uses a deterministic ACP agent (`fixtures/chat_agent.py`).
//! With `HYPURR_WIRE_OUT=path` it writes `docs/reference/fixtures/chat-wire.json`.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // test code: failing loudly is the point

mod common;

use base64::Engine as _;
use serde_json::{Value, json};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

struct Host {
    child: Child,
    home: PathBuf,
    base: String,
    token: String,
}

impl Drop for Host {
    fn drop(&mut self) {
        common::stop(&mut self.child);
        let _ = std::fs::remove_dir_all(&self.home);
    }
}

impl Host {
    async fn start() -> Self {
        let home = std::env::temp_dir().join(format!("hypurr-chat-e2e-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&home).unwrap();
        let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        let child = Command::new(env!("CARGO_BIN_EXE_hypurr-host"))
            .args(["serve", "--bind", "127.0.0.1", "--port", &port.to_string()])
            .env("HYPURR_CLOUD", "off")
            .env("HYPURR_HOME", &home)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        let mut host = Self { child, home, base: format!("http://127.0.0.1:{port}"), token: String::new() };
        let deadline = Instant::now() + Duration::from_secs(20);
        while reqwest::get(format!("{}/health", host.base)).await.is_err() {
            assert!(Instant::now() < deadline, "host didn't start");
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        std::fs::read_to_string(host.home.join("token")).unwrap().trim().clone_into(&mut host.token);
        host
    }

    async fn call(&self, method: &str, body: Value) -> Value {
        let res = reqwest::Client::new()
            .post(format!("{}/api/{method}", self.base))
            .bearer_auth(&self.token)
            .json(&body)
            .timeout(Duration::from_secs(20))
            .send()
            .await
            .unwrap();
        let status = res.status();
        let v: Value = res.json().await.unwrap();
        assert!(status.is_success(), "{method} failed: {v}");
        v
    }

    async fn bot(&self, name: &str) -> Value {
        let cwd = self.home.join(name.to_lowercase());
        std::fs::create_dir_all(&cwd).unwrap();
        let agent = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/chat_agent.py");
        self.call(
            "createBot",
            json!({
                "name": name, "backend": "custom", "cwd": cwd, "permission": "ask", "notify": false,
                "description": format!("{name} helps with the shop"), "avatarColor": "green",
                "command": format!("python3 -u '{}'", agent.display()),
            }),
        )
        .await["bot"]
            .clone()
    }

    /// Polls `method` (`history` or `thread`) until an entry matches.
    async fn wait(&self, method: &str, body: Value, pred: impl Fn(&Value) -> bool) -> Value {
        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            let h = self.call(method, body.clone()).await;
            if let Some(e) = h["entries"].as_array().unwrap().iter().rev().find(|e| pred(e)) {
                return e.clone();
            }
            assert!(Instant::now() < deadline, "timed out; {method}: {h}");
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }

    async fn bot_now(&self, id: &str) -> Value {
        let sync = self.call("sync", json!({"since": 0})).await;
        sync["bots"].as_array().unwrap().iter().find(|b| b["id"] == id).cloned().unwrap_or(Value::Null)
    }
}

fn final_reply(e: &Value) -> bool {
    e["kind"] == "agent" && e["data"]["final"] == true
}

#[tokio::test]
async fn groups_threads_reactions_files_and_bots_from_a_client() {
    if Command::new("python3").arg("--version").output().is_err() {
        eprintln!("skipping: python3 not available");
        return;
    }
    let host = Host::start().await;
    let alice = host.bot("Alice").await;
    let bob = host.bot("Bob").await;
    let (a, b) = (alice["id"].as_str().unwrap().to_owned(), bob["id"].as_str().unwrap().to_owned());
    assert_eq!(alice["avatarColor"], "green");

    // A reply with Markdown, then a thread on it.
    host.call("send", json!({"botId": a, "text": "what should I run?", "clientNonce": "c1"})).await;
    let reply = host.wait("history", json!({"botId": a}), final_reply).await;
    assert!(reply["data"]["text"].as_str().unwrap().contains("```sh"), "{reply}");
    let root = reply["id"].as_str().unwrap().to_owned();
    host.call("send", json!({"botId": a, "text": "and after that?", "clientNonce": "c2", "threadId": root})).await;
    let in_thread = host.wait("thread", json!({"botId": a, "rootId": root}), final_reply).await;
    assert_eq!(in_thread["threadId"], root.as_str());
    assert!(in_thread["data"]["text"].as_str().unwrap().contains("and after that?"));
    let thread = host.call("thread", json!({"botId": a, "rootId": root})).await;
    assert_eq!(thread["entries"].as_array().unwrap().len(), 2, "{thread}");
    let summary = host
        .wait("history", json!({"botId": a}), |e| e["id"] == root.as_str() && e["data"]["thread"]["count"] == 2)
        .await;
    assert_eq!(summary["data"]["thread"]["authors"][0], "user");
    // The main chat never shows the thread's messages.
    let main = host.call("history", json!({"botId": a})).await;
    assert!(main["entries"].as_array().unwrap().iter().all(|e| e["threadId"].is_null()));
    host.call("markRead", json!({"botId": a, "threadId": root})).await;

    // Reactions toggle.
    let reacted = host.call("react", json!({"entryId": root, "emoji": "👍"})).await;
    assert_eq!(reacted["entry"]["data"]["reactions"], json!(["👍"]));
    host.call("react", json!({"entryId": root, "emoji": "🎉"})).await;
    host.call("react", json!({"entryId": root, "emoji": "🎉"})).await;
    let root_now = host.wait("history", json!({"botId": a}), |e| e["id"] == root.as_str()).await;
    assert_eq!(root_now["data"]["reactions"], json!(["👍"]));

    // A file with a message: uploaded in chunks, listed on the entry, readable by other devices.
    let png: Vec<u8> = (0u8..=255).cycle().take(1000).collect();
    let upload = uuid::Uuid::new_v4().to_string();
    let b64 = base64::engine::general_purpose::STANDARD;
    host.call(
        "upload",
        json!({"botId": a, "uploadId": upload, "name": "shot.png", "offset": 0,
        "data": b64.encode(&png[..600]), "done": false}),
    )
    .await;
    let done = host
        .call(
            "upload",
            json!({"botId": a, "uploadId": upload, "name": "shot.png", "offset": 600,
        "data": b64.encode(&png[600..]), "done": true}),
        )
        .await;
    assert_eq!(done["attachment"]["size"], 1000);
    let sent = host.call("send", json!({"botId": a, "text": "", "clientNonce": "c3", "attachments": [upload]})).await;
    assert_eq!(sent["entry"]["data"]["attachments"][0]["name"], "shot.png");
    let seen = host
        .wait("history", json!({"botId": a}), |e| {
            final_reply(e) && e["data"]["text"].as_str().unwrap().contains("attached")
        })
        .await;
    let back = host.call("readUpload", json!({"botId": a, "uploadId": upload, "offset": 0})).await;
    assert_eq!(b64.decode(back["data"].as_str().unwrap()).unwrap(), png);
    assert_eq!(back["size"], 1000);

    // A group: @Alice answers, with the author on her reply.
    let group = host
        .call(
            "createBot",
            json!({"kind": "group", "name": "Shop team", "description": "Ship the shop",
        "members": [a, b]}),
        )
        .await["bot"]
        .clone();
    let g = group["id"].as_str().unwrap().to_owned();
    assert_eq!(group["kind"], "group");
    assert_eq!(group["members"], json!([a, b]));
    // The same bots again open the same group.
    let again = host.call("createBot", json!({"kind": "group", "name": "Other", "members": [a, b]})).await;
    assert_eq!(again["bot"]["id"], g.as_str());
    host.call("send", json!({"botId": g, "text": "@Alice what's left?", "clientNonce": "g1"})).await;
    let said = host.wait("history", json!({"botId": g}), |e| final_reply(e) && e["data"]["author"] == a.as_str()).await;
    assert!(said["data"]["text"].as_str().unwrap().contains("Alice"), "{said}");
    host.call("stop", json!({"botId": g})).await;

    // Edit from a client: rename a bot, change a group's members, then remove the group.
    let renamed = host.call("updateBot", json!({"id": b, "name": "Bobby", "permission": "auto"})).await;
    assert_eq!(renamed["bot"]["name"], "Bobby");
    assert_eq!(renamed["bot"]["permission"], "auto");
    let carol = host.bot("Carol").await;
    let c = carol["id"].as_str().unwrap().to_owned();
    let edited = host.call("updateBot", json!({"id": g, "members": [a, c], "name": "Shop crew"})).await;
    assert_eq!(edited["bot"]["members"], json!([a, c]));
    let dirs = host.call("listDirs", json!({"path": host.home})).await;
    assert!(dirs["dirs"].as_array().unwrap().iter().any(|d| d["name"] == "alice"), "{dirs}");
    let group_now = host.bot_now(&g).await;
    let hello = host.call("hello", json!({})).await;
    assert!(hello["backends"].as_array().unwrap().iter().any(|b| b["id"] == "claude"));

    if let Ok(out) = std::env::var("HYPURR_WIRE_OUT") {
        let home = host.home.to_string_lossy().into_owned();
        let backends: Vec<Value> = hello["backends"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|b| b["id"] == "claude" || b["id"] == "codex")
            .map(|b| json!({"id": b["id"], "name": b["name"], "available": true, "installed": true}))
            .collect();
        let wire = json!({
            "backends": backends,
            "bot": host.bot_now(&a).await,
            "group": group_now,
            "markdownReply": reply,
            "root": root_now,
            "thread": thread["entries"],
            "fileMessage": sent["entry"],
            "fileReply": seen,
            "readUpload": back,
            "groupReply": said,
            "dirs": dirs,
        });
        let tmp = host.home.parent().unwrap().to_string_lossy().into_owned();
        let text = serde_json::to_string_pretty(&wire)
            .unwrap()
            .replace(&home, "/Users/priya")
            .replace(&format!("\"{tmp}\""), "\"/Users\"")
            .replace(env!("CARGO_MANIFEST_DIR"), "/opt/hypurr/host");
        std::fs::write(out, text).unwrap();
    }
    host.call("deleteBot", json!({"botId": g})).await;
    assert!(host.bot_now(&g).await.is_null());
    assert!(!host.bot_now(&a).await.is_null(), "a group's bots stay");
}
