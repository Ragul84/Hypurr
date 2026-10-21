//! Hypurr's built-in agent: pinned Hypurr Agent binaries in `~/.hypurr/agents/hypurr-agent`
//! (Windows: `%LOCALAPPDATA%\\Hypurr\\agent`, no admin, PATH or registry changes).
//!
//! Hypurr Agent is a rebranded fork of OpenCode (MIT). Attribution lives in the
//! agent's LICENSE/NOTICE. User-facing UI never says "OpenCode".

use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// Pinned Hypurr Agent release. Bump with matching digests from the GitHub release.
pub const VERSION: &str = "0.1.1";
pub const BACKEND_ID: &str = "hypurr-agent";
pub const DISPLAY_NAME: &str = "Hypurr Agent";
pub const DEFAULT_MODEL: &str = "hypurr/hypurr-free";
pub const RELEASE_REPO: &str = "Ragul84/hypurr-agent";

/// Free models served by the Hypurr gateway (OpenAI-compatible).
pub const FREE_MODELS: &[FreeModel] = &[
    FreeModel { id: "hypurr/hypurr-free", name: "Hypurr Free", note: "Daily free allowance via Hypurr gateway" },
    FreeModel { id: "hypurr/hypurr-fast", name: "Hypurr Fast", note: "Fast free-tier model via Hypurr gateway" },
];

/// Plain message when someone picks a Hypurr free model but no gateway serves it.
pub const NO_GATEWAY: &str =
    "Free models aren't available yet. Add your own key in Hypurr Agent, or pick Claude or Codex.";

/// Where Hypurr's own gateway lives once deployed (cloud/DEPLOY.md). Only used after its
/// `/v1/health` answers, so nothing changes for users until the deploy is live.
pub const DEFAULT_GATEWAY_URL: &str = "https://hypurr.mindgains.ai";

static DEFAULT_HEALTHY: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// The explicit choice on this computer: `HYPURR_GATEWAY_URL` (or `off`), else the cloud from
/// `HYPURR_DEFAULT_CLOUD_URL`. `Some("")` means turned off.
fn configured_gateway() -> Option<String> {
    ["HYPURR_GATEWAY_URL", "HYPURR_DEFAULT_CLOUD_URL"]
        .iter()
        .filter_map(|k| std::env::var(k).ok())
        .map(|v| v.trim().trim_end_matches('/').to_owned())
        .find(|v| !v.is_empty())
        .map(|v| if v.eq_ignore_ascii_case("off") { String::new() } else { v })
}

/// The Hypurr gateway that serves the free models, when this computer has one: an explicit
/// `HYPURR_GATEWAY_URL`, or [`DEFAULT_GATEWAY_URL`] once its health check has passed.
pub fn gateway_url() -> Option<String> {
    match configured_gateway() {
        Some(v) if v.is_empty() => None,
        Some(v) => Some(v),
        None => DEFAULT_HEALTHY.load(std::sync::atomic::Ordering::Relaxed).then(|| DEFAULT_GATEWAY_URL.to_owned()),
    }
}

pub fn gateway_ready() -> bool {
    gateway_url().is_some()
}

/// Whether `{base}/v1/health` answers `{"ok": true}`.
pub async fn gateway_healthy(base: &str) -> bool {
    let Ok(client) = reqwest::Client::builder().timeout(Duration::from_secs(8)).build() else { return false };
    match client.get(format!("{}/v1/health", base.trim_end_matches('/'))).send().await {
        Ok(r) if r.status().is_success() => r.json::<Value>().await.is_ok_and(|v| v["ok"] == true),
        _ => false,
    }
}

/// Keeps [`DEFAULT_GATEWAY_URL`]'s health fresh (start, then every 10 minutes). No-op when the
/// computer names its own gateway or turned it off.
pub async fn health_loop() {
    loop {
        if configured_gateway().is_none() {
            let ok = gateway_healthy(DEFAULT_GATEWAY_URL).await;
            if ok != DEFAULT_HEALTHY.swap(ok, std::sync::atomic::Ordering::Relaxed) {
                tracing::info!(gateway = DEFAULT_GATEWAY_URL, ok, "Hypurr gateway health changed");
            }
        }
        tokio::time::sleep(Duration::from_secs(600)).await;
    }
}

/// kv: this computer's id at the gateway, and (vault) its trial key.
const GATEWAY_HOST_ID: &str = "gateway_host_id";
const GATEWAY_KEY_SLOT: &str = "gateway_key";

/// This computer's gateway key, minted once through `POST /v1/gateway/trial-key` and kept in
/// the vault (shown once by the gateway).
pub async fn gateway_key(store: &crate::store::Store, base: &str) -> Result<String> {
    if let Some(k) = crate::market::vault::read(store, GATEWAY_KEY_SLOT)?.filter(|k| !k.is_empty()) {
        return Ok(k);
    }
    let host_id = if let Some(id) = store.kv_get(GATEWAY_HOST_ID) {
        id
    } else {
        let id = uuid::Uuid::new_v4().simple().to_string();
        store.kv_set(GATEWAY_HOST_ID, &id)?;
        id
    };
    let client = reqwest::Client::builder().timeout(Duration::from_secs(15)).build()?;
    let res = client
        .post(format!("{}/v1/gateway/trial-key", base.trim_end_matches('/')))
        .json(&json!({"hostId": host_id}))
        .send()
        .await
        .context("reaching the Hypurr gateway")?;
    if !res.status().is_success() {
        bail!("{NO_GATEWAY} (gateway said {})", res.status());
    }
    let v: Value = res.json().await?;
    let Some(key) = v["apiKey"].as_str().filter(|k| !k.is_empty()) else {
        bail!("{NO_GATEWAY} (this computer's free key was lost; reinstalling Hypurr resets it)");
    };
    crate::market::vault::write(store, GATEWAY_KEY_SLOT, key)?;
    Ok(key.to_owned())
}

/// This computer's gateway subject (`trial:<host id>`), once it has a gateway key. Credits
/// bought on the phone are granted to it (the app user id at the payment provider).
pub fn gateway_subject(store: &crate::store::Store) -> Option<String> {
    gateway_ready().then(|| store.kv_get(GATEWAY_HOST_ID)).flatten().map(|id| format!("trial:{id}"))
}

/// Hypurr Agent config that adds the `hypurr` provider (OpenAI-compatible, the gateway).
pub fn agent_config(base: &str, key: &str) -> Value {
    let models: serde_json::Map<String, Value> =
        FREE_MODELS.iter().map(|m| (m.id.trim_start_matches("hypurr/").to_owned(), json!({"name": m.name}))).collect();
    json!({
        "provider": {
            "hypurr": {
                "npm": "@ai-sdk/openai-compatible",
                "name": "Hypurr",
                "options": {"baseURL": format!("{}/v1", base.trim_end_matches('/')), "apiKey": key},
                "models": models,
            }
        }
    })
}

/// Extra environment for a Hypurr Agent launch: the gateway provider, when there is one.
/// Never in local-only mode. Failures just leave the free models off (own keys still work).
pub async fn launch_env(store: &crate::store::Store) -> Vec<(String, String)> {
    if crate::admin::local_only::on(store) {
        return Vec::new();
    }
    let Some(base) = gateway_url() else { return Vec::new() };
    match gateway_key(store, &base).await {
        Ok(key) => vec![("OPENCODE_CONFIG_CONTENT".to_owned(), agent_config(&base, &key).to_string())],
        Err(e) => {
            tracing::warn!(error = format!("{e:#}"), "no Hypurr gateway key");
            Vec::new()
        }
    }
}

/// A model id served only by the Hypurr gateway.
pub fn is_gateway_model(model: &str) -> bool {
    model.starts_with("hypurr/")
}

/// Free models to offer: none without a gateway.
pub fn offered_free_models() -> Value {
    if gateway_ready() { free_models_json() } else { json!([]) }
}

/// Default model for a new Hypurr Agent bot: only a free one when the gateway is there.
pub fn default_model() -> Option<&'static str> {
    gateway_ready().then_some(DEFAULT_MODEL)
}

pub struct FreeModel {
    pub id: &'static str,
    pub name: &'static str,
    pub note: &'static str,
}

struct Release {
    archive: &'static str,
    cmd: &'static str,
    sha256: &'static str,
}

pub fn release_ok() -> bool {
    release().is_ok()
}

fn release() -> Result<&'static Release> {
    // Digests filled after first release build; tests override via env.
    const LINUX_X64: Release = Release {
        archive: "hypurr-agent-linux-x64.tar.gz",
        cmd: "hypurr-agent",
        sha256: "80e13a65edc205032f7421a0c6ee9fd07b4aef68227ea4f2b2d6b34eac4a5d1e",
    };
    const LINUX_ARM64: Release = Release {
        archive: "hypurr-agent-linux-arm64.tar.gz",
        cmd: "hypurr-agent",
        sha256: "d854f5950d3c73301fa2441525907585a3e8717a9a10a04d11a4cef1e0c51900",
    };
    const MAC_X64: Release = Release {
        archive: "hypurr-agent-darwin-x64.zip",
        cmd: "hypurr-agent",
        sha256: "443e8912402c2603aea301f582a36da47f1364c76a10ce8b1aa7a487cbfd1c25",
    };
    const MAC_ARM64: Release = Release {
        archive: "hypurr-agent-darwin-arm64.zip",
        cmd: "hypurr-agent",
        sha256: "eb733a52907d1495fc7f8cb2789a867954a49abc959e70a739133f0c4bdf6e00",
    };
    const WIN_X64: Release = Release {
        archive: "hypurr-agent-windows-x64.zip",
        cmd: "hypurr-agent.exe",
        sha256: "8950a06f6154ceada288ed4d4bf69c16734b53dab631fff669add4a7cae15931",
    };
    const WIN_ARM64: Release = Release {
        archive: "hypurr-agent-windows-arm64.zip",
        cmd: "hypurr-agent.exe",
        sha256: "6d731c333127e6f1ac601e9f44b28b9e27ceae99cfddda09a62f6d6d3ac9151f",
    };
    let (os, arch) = (std::env::consts::OS, std::env::consts::ARCH);
    Ok(match (os, arch) {
        ("linux", "x86_64") => &LINUX_X64,
        ("linux", "aarch64") => &LINUX_ARM64,
        ("macos", "x86_64") => &MAC_X64,
        ("macos", "aarch64") => &MAC_ARM64,
        ("windows", "x86_64") => &WIN_X64,
        ("windows", "aarch64") => &WIN_ARM64,
        _ => bail!("Hypurr Agent isn't available for {os}/{arch} yet"),
    })
}

fn root() -> PathBuf {
    #[cfg(windows)]
    if let Some(local) = std::env::var_os("LOCALAPPDATA").filter(|v| !v.is_empty()) {
        // User-writable, per-user, no admin rights needed.
        return PathBuf::from(local).join("Hypurr").join("agent");
    }
    crate::service::data_dir().join("agents").join(BACKEND_ID)
}

/// File name of the agent executable on this OS.
pub const fn exe_name() -> &'static str {
    if cfg!(windows) { "hypurr-agent.exe" } else { "hypurr-agent" }
}

pub fn install_dir() -> PathBuf {
    root().join(VERSION)
}

pub fn bin_path() -> PathBuf {
    install_dir().join(exe_name())
}

pub fn is_installed() -> bool {
    let p = bin_path();
    p.is_file() && std::fs::read(install_dir().join(".installed")).is_ok()
}

pub fn bin_dir() -> Option<PathBuf> {
    is_installed().then(install_dir)
}

pub fn free_models_json() -> Value {
    json!(
        FREE_MODELS
            .iter()
            .map(|m| json!({"id": m.id, "name": m.name, "description": m.note, "free": true}))
            .collect::<Vec<_>>()
    )
}

pub fn status() -> Value {
    let installed = is_installed();
    json!({
        "id": BACKEND_ID,
        "name": DISPLAY_NAME,
        "agentName": "Hypurr Agent",
        "subtitle": if gateway_ready() { "Hypurr Agent · free models" } else { "Hypurr Agent · your own key" },
        "builtin": true,
        "free": gateway_ready(),
        "installed": installed,
        "available": installed || release().is_ok(),
        "version": VERSION,
        "path": installed.then(|| bin_path().to_string_lossy().into_owned()),
        "defaultModel": default_model(),
        "freeModels": offered_free_models(),
        "gatewayReady": gateway_ready(),
        "needsInstall": !installed,
        "license": "MIT",
        "source": format!("https://github.com/{RELEASE_REPO}"),
        "consent": format!("Hypurr downloads Hypurr Agent into {} (no admin rights needed). Free models use the Hypurr gateway with a daily allowance; paid models use credits. Bring-your-own-key providers are also supported.", root().display()),
    })
}

fn sha256_hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    Sha256::digest(bytes).iter().fold(String::with_capacity(64), |mut s, b| {
        let _ = write!(s, "{b:02x}");
        s
    })
}

pub async fn install(progress: impl Fn(&str)) -> Result<Value> {
    if is_installed() {
        progress(&format!("Hypurr Agent {VERSION} is already installed."));
        return Ok(status());
    }
    let rel = release()?;
    let override_path = std::env::var_os("HYPURR_AGENT_ARCHIVE").map(PathBuf::from);
    progress(&format!("Downloading Hypurr Agent {VERSION}…"));
    let (bytes, expect_sha) = if let Some(ref path) = override_path {
        let bytes = tokio::fs::read(&path).await.with_context(|| format!("reading {}", path.display()))?;
        let expect = std::env::var("HYPURR_AGENT_SHA256").unwrap_or_else(|_| rel.sha256.to_owned());
        (bytes, expect)
    } else {
        let url = format!("https://github.com/{RELEASE_REPO}/releases/download/v{VERSION}/{}", rel.archive);
        let bytes = crate::http()
            .get(&url)
            .timeout(Duration::from_secs(600))
            .send()
            .await
            .with_context(|| format!("downloading Hypurr Agent from {url}"))?
            .error_for_status()
            .with_context(|| format!("Hypurr Agent download failed ({url})"))?
            .bytes()
            .await
            .context("reading Hypurr Agent download")?
            .to_vec();
        (bytes, rel.sha256.to_owned())
    };
    let got = sha256_hex(&bytes);
    if expect_sha.starts_with("PLACEHOLDER_") {
        // Dev: allow install when digests not yet published (override archive only).
        if override_path.is_none() {
            bail!("Hypurr Agent release digests are not published yet; set HYPURR_AGENT_ARCHIVE for local install");
        }
    } else if !got.eq_ignore_ascii_case(&expect_sha) {
        bail!("Hypurr Agent download checksum mismatch (got {got}, want {expect_sha})");
    }
    progress("Installing…");
    let dir = install_dir();
    let archive_name = if bytes.starts_with(&[0x1f, 0x8b]) {
        "hypurr-agent.tar.gz".to_owned()
    } else if bytes.starts_with(b"PK") {
        "hypurr-agent.zip".to_owned()
    } else {
        rel.archive.to_owned()
    };
    let (dir2, cmd) = (dir.clone(), PathBuf::from(rel.cmd));
    tokio::task::spawn_blocking(move || -> Result<()> {
        let _ = std::fs::remove_dir_all(&dir2);
        std::fs::create_dir_all(&dir2)?;
        let archive = dir2.join(&archive_name);
        std::fs::write(&archive, &bytes)?;
        extract(&archive, &dir2, &cmd)?;
        std::fs::write(dir2.join(".installed"), VERSION)?;
        let current = root().join("current");
        let _ = std::fs::remove_file(&current);
        let _ = std::fs::remove_dir_all(&current);
        #[cfg(unix)]
        {
            use std::os::unix::fs::symlink;
            let _ = symlink(&dir2, &current);
        }
        remove_other_versions(&dir2);
        Ok(())
    })
    .await?
    .context("installing Hypurr Agent")?;
    crate::agent::backends::hydrate_path();
    progress(&format!("Hypurr Agent {VERSION} is ready. Free model: Hypurr Free."));
    Ok(status())
}

fn remove_other_versions(installed: &Path) {
    let Some(parent) = installed.parent() else { return };
    let Ok(entries) = std::fs::read_dir(parent) else { return };
    for e in entries.flatten() {
        let p = e.path();
        if p != installed && p.file_name().is_some_and(|n| n != "current") && p.is_dir() {
            let _ = std::fs::remove_dir_all(p);
        }
    }
}

fn extract(archive: &Path, dir: &Path, cmd: &Path) -> Result<()> {
    let name = archive.file_name().map(|n| n.to_string_lossy().to_lowercase()).unwrap_or_default();
    #[allow(clippy::case_sensitive_file_extension_comparisons)]
    let status = if name.ends_with(".zip") {
        // In-process: Windows has no `unzip`.
        let f = std::fs::File::open(archive)?;
        zip::ZipArchive::new(f).context("reading Hypurr Agent zip")?.extract(dir).context("extracting Hypurr Agent")?;
        Ok(std::process::ExitStatus::default())
    } else if name.ends_with(".tar.gz") || name.ends_with(".tgz") {
        std::process::Command::new("tar").arg("-xzf").arg(archive).arg("-C").arg(dir).status()
    } else {
        bail!("unsupported Hypurr Agent archive {}", archive.display());
    }
    .context("extracting Hypurr Agent")?;
    if !status.success() {
        bail!("couldn't extract {}", archive.display());
    }
    let _ = std::fs::remove_file(archive);
    let exe = dir.join(cmd);
    if !exe.exists()
        && let Ok(entries) = std::fs::read_dir(dir)
    {
        for e in entries.flatten() {
            let nested = e.path().join(cmd);
            if nested.is_file() {
                std::fs::rename(&nested, &exe)?;
                break;
            }
        }
    }
    if !exe.exists() {
        bail!("Hypurr Agent archive had no {} binary", cmd.display());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut p = std::fs::metadata(&exe)?.permissions();
        p.set_mode(p.mode() | 0o755);
        std::fs::set_permissions(&exe, p)?;
    }
    Ok(())
}

#[cfg(test)]
pub fn install_from_bytes_for_test(bytes: &[u8], archive_name: &str, sha256: &str, cmd: &str) -> Result<PathBuf> {
    let got = sha256_hex(bytes);
    if !got.eq_ignore_ascii_case(sha256) {
        bail!("checksum mismatch");
    }
    let dir = install_dir();
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir)?;
    let archive = dir.join(archive_name);
    std::fs::write(&archive, bytes)?;
    extract(&archive, &dir, Path::new(cmd))?;
    std::fs::write(dir.join(".installed"), VERSION)?;
    Ok(bin_path())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn free_models_use_hypurr_prefix() {
        assert!(FREE_MODELS.iter().all(|m| m.id.starts_with("hypurr/")));
        assert_eq!(DEFAULT_MODEL, "hypurr/hypurr-free");
        assert!(FREE_MODELS.iter().any(|m| m.id == DEFAULT_MODEL));
    }

    #[test]
    fn gateway_models_are_recognised() {
        assert!(is_gateway_model("hypurr/hypurr-free"));
        assert!(!is_gateway_model("anthropic/claude-sonnet"));
        assert!(NO_GATEWAY.contains("Free models aren't available yet"));
    }

    #[test]
    fn agent_config_points_hypurr_provider_at_gateway() {
        let c = agent_config("https://hypurr.mindgains.ai/", "hk_x");
        let p = &c["provider"]["hypurr"];
        assert_eq!(p["options"]["baseURL"], "https://hypurr.mindgains.ai/v1");
        assert_eq!(p["options"]["apiKey"], "hk_x");
        assert!(p["models"]["hypurr-free"].is_object());
        assert!(p["models"]["hypurr-fast"].is_object());
    }

    #[test]
    fn default_gateway_waits_for_health() {
        assert_eq!(DEFAULT_GATEWAY_URL, "https://hypurr.mindgains.ai");
        if configured_gateway().is_none() {
            // Nothing has probed it in this test process: not offered yet.
            assert_eq!(gateway_url(), None);
        }
    }

    #[test]
    fn status_marks_builtin() {
        let s = status();
        assert_eq!(s["id"], BACKEND_ID);
        assert_eq!(s["builtin"], true);
        assert_eq!(s["agentName"], "Hypurr Agent");
        let free_models = s["freeModels"].as_array().unwrap();
        if gateway_ready() {
            assert_eq!(s["defaultModel"], DEFAULT_MODEL);
            assert_ne!(free_models.len(), 0);
        } else {
            // No gateway: never offer or default to a model nothing serves.
            assert!(s["defaultModel"].is_null());
            assert_eq!(free_models.as_slice(), &[] as &[Value]);
            assert_eq!(s["free"], false);
            assert_eq!(s["gatewayReady"], false);
        }
        let blob = s.to_string().to_lowercase();
        assert!(!blob.contains("opencode"), "user-visible status must not say opencode: {blob}");
    }

    #[test]
    fn install_from_tiny_archive() {
        let dir = std::env::temp_dir().join(format!("hypurr-ha-src-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let bin = dir.join(exe_name());
        std::fs::write(&bin, "#!/bin/sh\necho fake\n").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let tar = dir.join("hypurr-agent-linux-x64.tar.gz");
        let status = std::process::Command::new("tar")
            .args(["-czf"])
            .arg(&tar)
            .arg("-C")
            .arg(&dir)
            .arg(exe_name())
            .status()
            .unwrap();
        assert!(status.success());
        let bytes = std::fs::read(&tar).unwrap();
        let sum = sha256_hex(&bytes);

        let home = std::env::temp_dir().join(format!("hypurr-ha-home-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&home).unwrap();
        // SAFETY: test-only, single-threaded here.
        unsafe {
            std::env::set_var("HYPURR_HOME", &home);
            std::env::set_var("LOCALAPPDATA", &home);
        }

        let path = install_from_bytes_for_test(&bytes, "hypurr-agent-linux-x64.tar.gz", &sum, exe_name()).unwrap();
        assert!(path.is_file(), "{path:?}");
        assert!(is_installed());
        let _ = std::fs::remove_dir_all(&home);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn extracts_windows_style_zip() {
        use std::io::Write as _;
        let dir = std::env::temp_dir().join(format!("hypurr-ha-zip-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let archive = dir.join("hypurr-agent-windows-x64.zip");
        {
            let mut w = zip::ZipWriter::new(std::fs::File::create(&archive).unwrap());
            w.start_file("hypurr-agent.exe", zip::write::SimpleFileOptions::default()).unwrap();
            w.write_all(b"MZfake").unwrap();
            w.finish().unwrap();
        }
        extract(&archive, &dir, Path::new("hypurr-agent.exe")).unwrap();
        assert_eq!(std::fs::read(dir.join("hypurr-agent.exe")).unwrap(), b"MZfake");
        assert!(!archive.exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn every_desktop_target_has_a_release() {
        assert!(release_ok(), "this host OS/arch must have a pinned Hypurr Agent build");
    }

    /// Real install path against a downloaded release archive (pinned digest is enforced):
    /// `HYPURR_AGENT_ARCHIVE=…zip cargo test real_release_install -- --ignored`.
    #[tokio::test]
    #[ignore = "needs HYPURR_AGENT_ARCHIVE pointing at a real release archive"]
    async fn real_release_install() {
        let st = install(|l| println!("{l}")).await.expect("install");
        assert_eq!(st["installed"], true);
        let bin = bin_path();
        println!("installed at {}", bin.display());
        let out = std::process::Command::new(&bin).arg("--version").output().expect("run agent");
        let v = String::from_utf8_lossy(&out.stdout);
        println!("hypurr-agent --version: {}", v.trim());
        assert!(out.status.success());
        assert_eq!(v.trim(), VERSION);
    }
}
