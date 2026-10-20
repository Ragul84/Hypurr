//! Platform shells and process trees: `sh -c` on Unix, `cmd /D /S /C` on Windows.

use std::path::Path;

/// The platform shell running `cmd` (blocking).
pub fn std_shell(cmd: &str) -> std::process::Command {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt as _;
        // cmd doesn't understand the `\"` quoting Rust would add, so hand it the line as is:
        // `/S` strips the outer quotes and runs exactly what's inside.
        let mut c = std::process::Command::new("cmd.exe");
        c.args(["/D", "/S", "/C"]).raw_arg(format!("\"{cmd}\""));
        c
    }
    #[cfg(not(windows))]
    {
        let mut c = std::process::Command::new("sh");
        c.args(["-c", cmd]);
        c
    }
}

/// How a resolved program file runs on Windows, by extension.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Launch {
    Direct,
    /// `.cmd` / `.bat`: only `cmd.exe` can run these (`CreateProcess` gives os error 193).
    Cmd,
    /// `.ps1`: through `powershell -File`.
    PowerShell,
}

pub fn launch_kind(path: &Path) -> Launch {
    match path.extension().and_then(|e| e.to_str()).map(str::to_ascii_lowercase).as_deref() {
        Some("cmd" | "bat") if cfg!(windows) => Launch::Cmd,
        Some("ps1") if cfg!(windows) => Launch::PowerShell,
        _ => Launch::Direct,
    }
}

/// `"path"` quoted for the platform shell (double quotes for cmd, single for sh).
pub fn quote(s: &str) -> String {
    if cfg!(windows) {
        if s.chars().all(|c| c.is_ascii_alphanumeric() || "-_./\\:".contains(c)) {
            s.to_owned()
        } else {
            format!("\"{}\"", s.replace('"', ""))
        }
    } else if s.chars().all(|c| c.is_ascii_alphanumeric() || "-_./".contains(c)) {
        s.to_owned()
    } else {
        format!("'{}'", s.replace('\'', "'\\''"))
    }
}

/// The program part of a shell line for `path` (already run through cmd on Windows).
pub fn shell_program(path: &Path) -> String {
    let q = quote(&path.to_string_lossy());
    match launch_kind(path) {
        Launch::PowerShell => format!("powershell.exe -NoProfile -ExecutionPolicy Bypass -File {q}"),
        // cmd already runs .cmd/.bat by name; `call` keeps the line's exit code.
        Launch::Cmd | Launch::Direct => q,
    }
}

/// Runs a resolved program with simple `args`: directly for .exe/unix, via cmd for .cmd/.bat,
/// via powershell for .ps1.
pub fn program_command(path: &Path, args: &[&str]) -> tokio::process::Command {
    match launch_kind(path) {
        Launch::Direct => {
            let mut c = tokio::process::Command::new(path);
            c.args(args);
            c
        }
        Launch::Cmd | Launch::PowerShell => {
            let line =
                std::iter::once(shell_program(path)).chain(args.iter().map(|a| quote(a))).collect::<Vec<_>>().join(" ");
            tokio_shell(&line)
        }
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
        // Quotes reach the shell untouched.
        let out = std_shell(r#"echo "a b" && echo 'c'"#).output().unwrap();
        let text = String::from_utf8_lossy(&out.stdout).replace('\r', "");
        assert!(text.contains("a b"), "{text}");
        let dir = std::env::temp_dir().join(format!("oscmd-{}", uuid::Uuid::new_v4().simple()));
        std::fs::create_dir_all(dir.join("src/inner")).unwrap();
        assert!(link_dir(&dir.join("src"), &dir.join("dst")));
        assert!(dir.join("dst/inner").is_dir());
        let _ = std::fs::remove_dir_all(dir);
    }
}
