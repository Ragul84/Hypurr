//! Hypurr's built-in agent: a pinned OpenCode binary in `~/.hypurr/agents/opencode`.
//!
//! OpenCode (<https://github.com/anomalyco/opencode>, MIT) already speaks ACP
//! (`opencode acp`). Brand-new users get free Zen models (Big Pickle and the
//! other free models) without a separate AI account. Hypurr downloads the
//! official release for the platform, verifies the SHA-256 digest, and never
//! vendors OpenCode's source.

use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// Pinned OpenCode release. Bump with matching digests from the GitHub release.
pub const VERSION: &str = "1.18.31";
pub const BACKEND_ID: &str = "opencode";
pub const DISPLAY_NAME: &str = "Hypurr built-in";
pub const DEFAULT_MODEL: &str = "opencode/big-pickle";

/// Free Zen models verified against <https://opencode.ai/zen/v1/models> (Oct 2026).
/// Time-limited by OpenCode; Hypurr surfaces them as Free. Ids are `opencode/<slug>`.
pub const FREE_MODELS: &[FreeModel] = &[
    FreeModel {
        id: "opencode/big-pickle",
        name: "Big Pickle",
        note: "General coding (stealth, free for a limited time)",
    },
    FreeModel { id: "opencode/space-bunny-free", name: "Space Bunny Free", note: "Free; provider says zero retention" },
    FreeModel {
        id: "opencode/minimax-m2.5-free",
        name: "MiniMax M2.5 Free",
        note: "Strong coding, long context (free for a limited time; may not always be listed)",
    },
    FreeModel {
        id: "opencode/longcat-2.5-preview-free",
        name: "LongCat 2.5 Preview Free",
        note: "Free; provider says zero retention",
    },
    FreeModel { id: "opencode/mimo-v2.6-flash-free", name: "MiMo-V2.6-Flash Free", note: "Free for a limited time" },
    FreeModel { id: "opencode/mimo-v2.5-free", name: "MiMo-V2.5 Free", note: "Free for a limited time" },
    FreeModel { id: "opencode/fledge-alpha-free", name: "Fledge Alpha Free", note: "Free for a limited time" },
    FreeModel {
        id: "opencode/nemotron-3-ultra-free",
        name: "Nemotron 3 Ultra Free",
        note: "NVIDIA trial; do not submit confidential data",
    },
    FreeModel {
        id: "opencode/nemotron-3.5-lightning-free",
        name: "Nemotron 3.5 Lightning Free",
        note: "NVIDIA trial; do not submit confidential data",
    },
    FreeModel { id: "opencode/ling-3.1-flash-free", name: "Ling 3.1 Flash Free", note: "Free for a limited time" },
];

pub struct FreeModel {
    pub id: &'static str,
    pub name: &'static str,
    pub note: &'static str,
}

struct Release {
    /// Archive file name on the GitHub release.
    archive: &'static str,
    /// Path of the binary inside the archive (or the archive itself when raw).
    cmd: &'static str,
    sha256: &'static str,
}

pub fn release_ok() -> bool {
    release().is_ok()
}

fn release() -> Result<&'static Release> {
    const LINUX_X64: Release = Release {
        archive: "opencode-linux-x64.tar.gz",
        cmd: "opencode",
        sha256: "e9312be75ed803b7415fc2aeabda1f4fe938912a39673762dc0c38c0e11ebde4",
    };
    const LINUX_ARM64: Release = Release {
        archive: "opencode-linux-arm64.tar.gz",
        cmd: "opencode",
        sha256: "d4e332f46b227448582c0d9fc75f6f826dfe95c9f751bc2011fc4d937a042be6",
    };
    const MAC_X64: Release = Release {
        archive: "opencode-darwin-x64.zip",
        cmd: "opencode",
        sha256: "f8510eaf400f07c3a2014e3a517e3650c705bcd6ac3e6740351b723ee685042f",
    };
    const MAC_ARM64: Release = Release {
        archive: "opencode-darwin-arm64.zip",
        cmd: "opencode",
        sha256: "caf7f31fa1aec2353ea859d4ef9ab824c6273d941b016e88d51193fa3028d34e",
    };
    let (os, arch) = (std::env::consts::OS, std::env::consts::ARCH);
    Ok(match (os, arch) {
        ("linux", "x86_64") => &LINUX_X64,
        ("linux", "aarch64") => &LINUX_ARM64,
        ("macos", "x86_64") => &MAC_X64,
        ("macos", "aarch64") => &MAC_ARM64,
        _ => bail!("Hypurr's built-in agent isn't available for {os}/{arch} yet"),
    })
}

fn root() -> PathBuf {
    crate::service::data_dir().join("agents").join(BACKEND_ID)
}

pub fn install_dir() -> PathBuf {
    root().join(VERSION)
}

pub fn bin_path() -> PathBuf {
    install_dir().join("opencode")
}

pub fn is_installed() -> bool {
    let p = bin_path();
    p.is_file() && std::fs::read(install_dir().join(".installed")).is_ok()
}

/// Directory that should be first on PATH so `opencode` resolves to the managed build.
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

/// What `hello` / `backends` expose for the built-in agent.
pub fn status() -> Value {
    let installed = is_installed();
    json!({
        "id": BACKEND_ID,
        "name": DISPLAY_NAME,
        "agentName": "OpenCode",
        "subtitle": "OpenCode · free models",
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
        "source": "https://github.com/anomalyco/opencode",
        "consent": "Hypurr downloads OpenCode into ~/.hypurr/agents/opencode. Free Zen models are provided by OpenCode for a limited time; some may use prompts to improve the model. See OpenCode Zen docs.",
    })
}

fn sha256_hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    Sha256::digest(bytes).iter().fold(String::with_capacity(64), |mut s, b| {
        let _ = write!(s, "{b:02x}");
        s
    })
}

/// Downloads the pinned OpenCode binary into `~/.hypurr/agents/opencode/<version>`.
/// `progress` receives short status lines for the setup terminal / onboarding UI.
pub async fn install(progress: impl Fn(&str)) -> Result<Value> {
    if is_installed() {
        progress(&format!("OpenCode {VERSION} is already installed."));
        return Ok(status());
    }
    let rel = release()?;
    let override_path = std::env::var_os("HYPURR_OPENCODE_ARCHIVE").map(PathBuf::from);
    progress(&format!("Downloading OpenCode {VERSION}…"));
    let (bytes, expect_sha) = if let Some(path) = override_path {
        // Tests / offline: a local archive; checksum is still verified when
        // HYPURR_OPENCODE_SHA256 is set, otherwise against the pinned release digest.
        let bytes = tokio::fs::read(&path).await.with_context(|| format!("reading {}", path.display()))?;
        let expect = std::env::var("HYPURR_OPENCODE_SHA256").unwrap_or_else(|_| rel.sha256.to_owned());
        (bytes, expect)
    } else {
        let url = format!("https://github.com/anomalyco/opencode/releases/download/v{VERSION}/{}", rel.archive);
        let bytes = crate::http()
            .get(&url)
            .timeout(Duration::from_secs(600))
            .send()
            .await
            .with_context(|| format!("downloading OpenCode from {url}"))?
            .error_for_status()
            .with_context(|| format!("OpenCode download failed ({url})"))?
            .bytes()
            .await
            .context("reading OpenCode download")?
            .to_vec();
        (bytes, rel.sha256.to_owned())
    };
    let got = sha256_hex(&bytes);
    if !got.eq_ignore_ascii_case(&expect_sha) {
        bail!("OpenCode download checksum mismatch (got {got}, want {expect_sha})");
    }
    progress("Installing…");
    let dir = install_dir();
    let (dir2, archive_name, cmd) = (dir.clone(), rel.archive.to_owned(), PathBuf::from(rel.cmd));
    tokio::task::spawn_blocking(move || -> Result<()> {
        let _ = std::fs::remove_dir_all(&dir2);
        std::fs::create_dir_all(&dir2)?;
        let archive = dir2.join(&archive_name);
        std::fs::write(&archive, &bytes)?;
        extract(&archive, &dir2, &cmd)?;
        std::fs::write(dir2.join(".installed"), VERSION)?;
        // Point `current` at this version for humans browsing the folder.
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
    .context("installing OpenCode")?;
    // Prefer the managed binary on the search path.
    crate::agent::backends::hydrate_path();
    progress(&format!("OpenCode {VERSION} is ready. Free model: Big Pickle."));
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
        bail!("unsupported OpenCode archive {}", archive.display());
    }
    .context("extracting OpenCode")?;
    if !status.success() {
        bail!("couldn't extract {}", archive.display());
    }
    let _ = std::fs::remove_file(archive);
    let exe = dir.join(cmd);
    if !exe.exists() {
        // Some archives nest one folder.
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
        bail!("OpenCode archive had no {} binary", cmd.display());
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

/// Install from a local archive (tests): same layout and checksum check.
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
    fn free_models_use_opencode_prefix() {
        assert!(FREE_MODELS.iter().all(|m| m.id.starts_with("opencode/")));
        assert_eq!(DEFAULT_MODEL, "opencode/big-pickle");
        assert!(FREE_MODELS.iter().any(|m| m.id == DEFAULT_MODEL));
    }

    #[test]
    fn status_marks_builtin() {
        let s = status();
        assert_eq!(s["id"], BACKEND_ID);
        assert_eq!(s["builtin"], true);
        assert_eq!(s["free"], true);
        assert_eq!(s["defaultModel"], DEFAULT_MODEL);
        assert!(s["freeModels"].as_array().unwrap().len() >= 3);
    }

    #[test]
    fn install_from_tiny_archive() {
        // A tiny "tar.gz" with a fake opencode script — exercise extract + marker.
        let dir = std::env::temp_dir().join(format!("hypurr-oc-src-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let bin = dir.join("opencode");
        std::fs::write(&bin, "#!/bin/sh\necho fake\n").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let tar = dir.join("opencode-linux-x64.tar.gz");
        let status = std::process::Command::new("tar")
            .args(["-czf"])
            .arg(&tar)
            .arg("-C")
            .arg(&dir)
            .arg("opencode")
            .status()
            .unwrap();
        assert!(status.success());
        let bytes = std::fs::read(&tar).unwrap();
        let sum = sha256_hex(&bytes);

        // Point data dir at a temp home so we don't touch the real ~/.hypurr.
        let home = std::env::temp_dir().join(format!("hypurr-oc-home-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&home).unwrap();
        // SAFETY: test-only, single-threaded here.
        unsafe { std::env::set_var("HYPURR_HOME", &home) };

        let path = install_from_bytes_for_test(&bytes, "opencode-linux-x64.tar.gz", &sum, "opencode").unwrap();
        assert!(path.is_file(), "{path:?}");
        assert!(is_installed());
        let _ = std::fs::remove_dir_all(&home);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
