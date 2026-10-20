//! Platform shells and process trees: `sh -c` on Unix, `cmd /D /S /C` on Windows.

use std::path::Path;

/// The platform shell running `cmd` (blocking).
pub fn std_shell(cmd: &str) -> std::process::Command {
    if cfg!(windows) {
        let mut c = std::process::Command::new("cmd.exe");
        c.args(["/D", "/S", "/C", cmd]);
        c
    } else {
        let mut c = std::process::Command::new("sh");
        c.args(["-c", cmd]);
        c
    }
}

/// The platform shell running `cmd` (async).
pub fn tokio_shell(cmd: &str) -> tokio::process::Command {
    tokio::process::Command::from(std_shell(cmd))
}

/// Kills a process and everything it started (its process group on Unix, its tree on Windows).
pub fn kill_tree(pid: u32) {
    #[cfg(unix)]
    let _ = std::process::Command::new("kill").args(["-9", "--", &format!("-{pid}")]).status();
    #[cfg(windows)]
    let _ = std::process::Command::new("taskkill")
        .args(["/T", "/F", "/PID", &pid.to_string()])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status();
}

/// Points `dst` at the directory `src`: a symlink on Unix, a junction (no admin rights) on Windows.
pub fn link_dir(src: &Path, dst: &Path) -> bool {
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(src, dst).is_ok()
    }
    #[cfg(windows)]
    {
        std::process::Command::new("cmd.exe")
            .args(["/D", "/C", "mklink", "/J"])
            .arg(dst)
            .arg(src)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .is_ok_and(|s| s.success())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shell_runs_and_links() {
        let out = std_shell("echo hi").output().unwrap();
        assert!(out.status.success());
        assert_eq!(String::from_utf8_lossy(&out.stdout).trim(), "hi");
        let dir = std::env::temp_dir().join(format!("oscmd-{}", uuid::Uuid::new_v4().simple()));
        std::fs::create_dir_all(dir.join("src/inner")).unwrap();
        assert!(link_dir(&dir.join("src"), &dir.join("dst")));
        assert!(dir.join("dst/inner").is_dir());
        let _ = std::fs::remove_dir_all(dir);
    }
}
