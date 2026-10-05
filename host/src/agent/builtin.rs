//! Hypurr's built-in agent: pinned Hypurr Agent binaries in `~/.hypurr/agents/hypurr-agent`.
//!
//! Hypurr Agent is a rebranded fork of OpenCode (MIT). Attribution lives in the
//! agent's LICENSE/NOTICE. User-facing UI never says "OpenCode".

use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// Pinned Hypurr Agent release. Bump with matching digests from the GitHub release.
pub const VERSION: &str = "0.1.0";
pub const BACKEND_ID: &str = "hypurr-agent";
pub const DISPLAY_NAME: &str = "Hypurr Agent";
pub const DEFAULT_MODEL: &str = "hypurr/hypurr-free";
pub const RELEASE_REPO: &str = "Ragul84/hypurr-agent";

/// Free models served by the Hypurr gateway (OpenAI-compatible).
pub const FREE_MODELS: &[FreeModel] = &[
    FreeModel {
        id: "hypurr/hypurr-free",
        name: "Hypurr Free",
        note: "Daily free allowance via Hypurr gateway",
    },
    FreeModel {
        id: "hypurr/hypurr-fast",
        name: "Hypurr Fast",
        note: "Fast free-tier model via Hypurr gateway",
    },
];

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
        sha256: "578bc0b9db6591d26f3108d106d028dce7b0b55c8bb6be98c8c7ca166c64e0a3",
    };
    const LINUX_ARM64: Release = Release {
        archive: "hypurr-agent-linux-arm64.tar.gz",
        cmd: "hypurr-agent",
        sha256: "d93e2c8cfcd9041ae44726a06e5d3ac68bb6261c16788efd9055a34f7d9fb6b2",
    };
    const MAC_X64: Release = Release {
        archive: "hypurr-agent-darwin-x64.zip",
        cmd: "hypurr-agent",
        sha256: "d48f9fb425a6c56fd02648ae9605a599ec3d5f397835b9dc9217bf0884ad92e6",
    };
    const MAC_ARM64: Release = Release {
        archive: "hypurr-agent-darwin-arm64.zip",
        cmd: "hypurr-agent",
        sha256: "28ddb235dcbd81dc870a0181d0e28647906345ec0444b6ceab0f1bdf4906e706",
    };
    let (os, arch) = (std::env::consts::OS, std::env::consts::ARCH);
    Ok(match (os, arch) {
        ("linux", "x86_64") => &LINUX_X64,
        ("linux", "aarch64") => &LINUX_ARM64,
        ("macos", "x86_64") => &MAC_X64,
        ("macos", "aarch64") => &MAC_ARM64,
        _ => bail!("Hypurr Agent isn't available for {os}/{arch} yet"),
    })
}

fn root() -> PathBuf {
    crate::service::data_dir().join("agents").join(BACKEND_ID)
}

pub fn install_dir() -> PathBuf {
    root().join(VERSION)
}

pub fn bin_path() -> PathBuf {
    install_dir().join("hypurr-agent")
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
        "subtitle": "Hypurr Agent · free models",
        "builtin": true,
        "free": true,
        "installed": installed,
        "available": installed || release().is_ok(),
        "version": VERSION,
        "path": installed.then(|| bin_path().to_string_lossy().into_owned()),
        "defaultModel": DEFAULT_MODEL,
        "freeModels": free_models_json(),
        "needsInstall": !installed,
        "license": "MIT",
        "source": format!("https://github.com/{RELEASE_REPO}"),
        "consent": "Hypurr downloads Hypurr Agent into ~/.hypurr/agents/hypurr-agent. Free models use the Hypurr gateway with a daily allowance; paid models use credits. Bring-your-own-key providers are also supported.",
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
        std::process::Command::new("unzip").arg("-q").arg("-o").arg(archive).arg("-d").arg(dir).status()
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
    if !exe.exists() {
        if let Ok(entries) = std::fs::read_dir(dir) {
            for e in entries.flatten() {
                let nested = e.path().join(cmd);
                if nested.is_file() {
                    std::fs::rename(&nested, &exe)?;
                    break;
                }
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
    fn status_marks_builtin() {
        let s = status();
        assert_eq!(s["id"], BACKEND_ID);
        assert_eq!(s["builtin"], true);
        assert_eq!(s["free"], true);
        assert_eq!(s["agentName"], "Hypurr Agent");
        assert_eq!(s["defaultModel"], DEFAULT_MODEL);
        assert!(s["freeModels"].as_array().unwrap().len() >= 1);
        let blob = s.to_string().to_lowercase();
        assert!(!blob.contains("opencode"), "user-visible status must not say opencode: {blob}");
    }

    #[test]
    fn install_from_tiny_archive() {
        let dir = std::env::temp_dir().join(format!("hypurr-ha-src-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let bin = dir.join("hypurr-agent");
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
            .arg("hypurr-agent")
            .status()
            .unwrap();
        assert!(status.success());
        let bytes = std::fs::read(&tar).unwrap();
        let sum = sha256_hex(&bytes);

        let home = std::env::temp_dir().join(format!("hypurr-ha-home-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&home).unwrap();
        unsafe { std::env::set_var("HYPURR_HOME", &home) };

        let path = install_from_bytes_for_test(&bytes, "hypurr-agent-linux-x64.tar.gz", &sum, "hypurr-agent").unwrap();
        assert!(path.is_file(), "{path:?}");
        assert!(is_installed());
        let _ = std::fs::remove_dir_all(&home);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
