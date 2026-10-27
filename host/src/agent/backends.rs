//! Which coding harnesses this machine has, and how to drive each over ACP.
//!
//! Detection works like Orca's preflight: hydrate PATH from the user's login
//! shell (GUI apps and launchd get a bare PATH), add the usual install dirs
//! (Homebrew, ~/.local/bin, nvm, bun, volta, asdf, mise, pnpm…), then look for
//! each known CLI. Launching prefers the installed CLI's own ACP mode and falls
//! back to the official ACP registry build (npx / uvx / downloaded binary).

use crate::LockExt;
use crate::agent::registry;
use serde_json::{Value, json};
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

pub struct Harness {
    pub id: &'static str,
    pub name: &'static str,
    /// CLI names that mean "installed on this machine".
    pub bins: &'static [&'static str],
    /// The installed CLI's native ACP mode (`{bin}` = resolved binary path).
    pub local: Option<&'static str>,
    /// Entry in the ACP registry (adapter or pinned build).
    pub registry: Option<&'static str>,
    /// What to tell someone setting it up by hand.
    pub setup: &'static str,
    /// How Hypurr installs the CLI for you (in a setup terminal).
    pub install: Option<Install>,
    /// Shell command that signs in, run in a setup terminal (`{bin}` = the CLI).
    pub login: &'static str,
    /// How to tell whether it's signed in without starting it.
    pub signed_in: Option<SignInCheck>,
}

#[derive(Clone, Copy)]
pub enum Install {
    /// A vendor install script (`curl … | bash`).
    Script(&'static str),
    /// A global npm package.
    Npm(&'static str),
    /// Hypurr downloads a pinned build into `~/.hypurr/agents` (Hypurr Agent).
    Managed,
}

/// How a vendor installs its CLI on native Windows (from each vendor's install docs).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WinInstall {
    /// A PowerShell script (`irm … | iex`), run with `powershell -NoProfile -ExecutionPolicy Bypass`.
    PowerShell(&'static str),
    /// `uv tool install <pkg>` (the vendor's documented Windows path).
    Uv(&'static str),
    /// The vendor has no native Windows install (WSL only).
    Unavailable,
}

/// Shown instead of an install button when the vendor has no native Windows install.
pub const NOT_ON_WINDOWS: &str = "Not available on Windows yet";
/// Plain words when npm is missing.
pub const NEEDS_NODE: &str =
    "This needs Node.js first. Install the LTS version from https://nodejs.org, then try again.";

/// Official native Windows install per agent whose Unix install is a `curl … | sh` script.
/// Sources are listed in docs/features/agent-installs.md.
pub fn windows_install(id: &str) -> WinInstall {
    use WinInstall::{PowerShell, Unavailable, Uv};
    match id {
        "claude" => PowerShell("irm https://claude.ai/install.ps1 | iex"),
        "codex" => PowerShell("irm https://chatgpt.com/codex/install.ps1 | iex"),
        "cursor" => PowerShell("irm 'https://cursor.com/install?win32=true' | iex"),
        "goose" => PowerShell(
            "$f = Join-Path $env:TEMP 'goose_download_cli.ps1'; Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/aaif-goose/goose/main/download_cli.ps1' -OutFile $f; $env:CONFIGURE = 'false'; & $f",
        ),
        "kimi" => PowerShell("irm https://code.kimi.com/kimi-code/install.ps1 | iex"),
        "vibe" => Uv("mistral-vibe"),
        "kiro" => PowerShell("irm 'https://cli.kiro.dev/install.ps1' | iex"),
        "devin" => PowerShell("irm https://static.devin.ai/cli/setup.ps1 | iex"),
        "junie" => PowerShell("iex (irm 'https://junie.jetbrains.com/install.ps1')"),
        "antigravity" => PowerShell("irm https://antigravity.google/cli/install.ps1 | iex"),
        "cortex" => PowerShell("irm https://ai.snowflake.com/static/cc-scripts/install.ps1 | iex"),
        "poolside" => PowerShell("irm https://downloads.poolside.ai/pool/install.ps1 | iex"),
        // Amp documents WSL only for Windows.
        _ => Unavailable,
    }
}

/// A setup command and the shell that runs it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SetupCommand {
    pub text: String,
    /// Run with PowerShell (Windows installs) instead of the platform shell.
    pub powershell: bool,
}

impl Install {
    /// The command for this machine.
    pub fn command(self, id: &str) -> Result<SetupCommand, &'static str> {
        self.command_for(id, cfg!(windows))
    }

    /// The command for Windows or Unix (split out so both are tested everywhere).
    pub fn command_for(self, id: &str, windows: bool) -> Result<SetupCommand, &'static str> {
        let unix = |text: String| Ok(SetupCommand { text, powershell: false });
        let ps = |text: String| Ok(SetupCommand { text, powershell: true });
        match (self, windows) {
            (Self::Script(s), false) => unix(s.to_owned()),
            (Self::Script(_), true) => match windows_install(id) {
                WinInstall::PowerShell(s) => ps(s.to_owned()),
                WinInstall::Uv(pkg) => ps(format!(
                    "if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {{ Write-Host 'This needs uv first. Install it from https://docs.astral.sh/uv/getting-started/installation/, then try again.'; exit 1 }}; uv tool install {pkg}"
                )),
                WinInstall::Unavailable => Err(NOT_ON_WINDOWS),
            },
            (Self::Npm(pkg), false) => {
                unix(format!("command -v npm >/dev/null || {{ echo '{NEEDS_NODE}'; exit 1; }}; npm install -g {pkg}"))
            }
            (Self::Npm(pkg), true) => ps(format!(
                "if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {{ Write-Host '{NEEDS_NODE}'; exit 1 }}; npm.cmd install -g {pkg}"
            )),
            // Managed installs run inside the host (see `term` / `installBuiltinAgent`), not a shell.
            (Self::Managed, _) => unix(String::new()),
        }
    }

    /// Whether Hypurr can install it on this kind of machine.
    pub fn available_for(self, id: &str, windows: bool) -> bool {
        self.command_for(id, windows).is_ok()
    }
}

/// `<bin> <args>`, judged by exit code and stdout.
#[derive(Clone, Copy)]
pub struct SignInCheck {
    pub args: &'static str,
    pub ok: fn(bool, &str) -> bool,
}

fn exit_ok(success: bool, _: &str) -> bool {
    success
}

fn claude_signed_in(_: bool, out: &str) -> bool {
    serde_json::from_str::<Value>(out).is_ok_and(|v| v["loggedIn"] == true)
}

fn qoder_signed_in(_: bool, out: &str) -> bool {
    serde_json::from_str::<Value>(out).is_ok_and(|v| v["logged_in"] == true)
}

fn says_logged_in(_: bool, out: &str) -> bool {
    let out = out.to_lowercase();
    out.contains("logged in") && !out.contains("not logged in")
}

/// Dry run for every agent with an install: the exact command, its shell, and whether the
/// prerequisites it needs are on this computer. Installs nothing.
pub fn install_plan() -> Value {
    let windows = cfg!(windows);
    let tool = |b: &str| which(b).is_some();
    let rows: Vec<Value> = HARNESSES
        .iter()
        .filter_map(|h| h.install.map(|i| (h, i)))
        .filter(|(_, i)| !matches!(i, Install::Managed))
        .map(|(h, i)| {
            let (command, shell, note) = match i.command_for(h.id, windows) {
                Ok(c) => (
                    Some(c.text),
                    if c.powershell {
                        "powershell"
                    } else if windows {
                        "cmd"
                    } else {
                        "sh"
                    },
                    None,
                ),
                Err(e) => (None, "", Some(e)),
            };
            let needs: Vec<(&str, bool)> = match (i, windows) {
                (Install::Npm(_), true) => vec![("npm.cmd (Node.js)", tool("npm.cmd") || tool("npm"))],
                (Install::Npm(_), false) => vec![("npm (Node.js)", tool("npm"))],
                (Install::Script(_), true) if matches!(windows_install(h.id), WinInstall::Uv(_)) => {
                    vec![("uv", tool("uv"))]
                }
                (Install::Script(_), true) => vec![("powershell", tool("powershell"))],
                (Install::Script(_), false) => vec![("curl", tool("curl"))],
                _ => vec![],
            };
            json!({
                "id": h.id,
                "name": h.name,
                "installed": resolve(h).is_some(),
                "shell": shell,
                "command": command,
                "note": note,
                "prereqs": needs.iter().map(|(n, ok)| json!({"need": n, "found": ok})).collect::<Vec<_>>(),
            })
        })
        .collect();
    json!({"os": std::env::consts::OS, "agents": rows})
}

pub const HARNESSES: &[Harness] = &[
    Harness {
        id: "claude",
        name: "Claude Code",
        bins: &["claude"],
        local: None,
        registry: Some("claude-acp"),
        setup: "Install Claude Code and run `claude auth login`.",
        install: Some(Install::Script("curl -fsSL https://claude.ai/install.sh | bash")),
        login: "{bin} auth login",
        signed_in: Some(SignInCheck { args: "auth status", ok: claude_signed_in }),
    },
    Harness {
        id: "codex",
        name: "Codex",
        bins: &["codex"],
        local: None,
        registry: Some("codex-acp"),
        setup: "Install Codex CLI and run `codex login --device-auth`.",
        install: Some(Install::Script("curl -fsSL https://chatgpt.com/codex/install.sh | sh")),
        login: "{bin} login --device-auth",
        signed_in: Some(SignInCheck { args: "login status", ok: exit_ok }),
    },
    Harness {
        id: "cursor",
        name: "Cursor",
        bins: &["cursor-agent", "agent"],
        local: Some("{bin} acp"),
        registry: Some("cursor"),
        setup: "Install Cursor CLI (curl https://cursor.com/install -fsS | bash) and run `cursor-agent login`.",
        install: Some(Install::Script("curl https://cursor.com/install -fsS | bash")),
        login: "NO_OPEN_BROWSER=1 {bin} login",
        signed_in: Some(SignInCheck { args: "status", ok: says_logged_in }),
    },
    Harness {
        id: "pi",
        name: "Pi",
        bins: &["pi"],
        local: None,
        registry: Some("pi-acp"),
        setup: "Install pi (npm install -g @earendil-works/pi-coding-agent), run `pi` and type /login.",
        install: Some(Install::Npm("@earendil-works/pi-coding-agent")),
        login: "{bin}",
        signed_in: None,
    },
    Harness {
        id: "hypurr-agent",
        name: "Hypurr Agent",
        bins: &["hypurr-agent"],
        local: Some("{bin} acp"),
        registry: Some("hypurr-agent"),
        setup: "Hypurr can install Hypurr Agent for you (free models via the Hypurr gateway).",
        install: Some(Install::Managed),
        login: "{bin} auth login",
        signed_in: None,
    },
    Harness {
        // Legacy id kept for compatibility (a user-installed upstream CLI); always shown as Hypurr Agent.
        id: "opencode",
        name: "Hypurr Agent",
        bins: &["opencode"],
        local: Some("{bin} acp"),
        registry: Some("opencode"),
        setup: "Hypurr Agent from a CLI already on your computer. Hypurr can also install Hypurr Agent for you.",
        install: None,
        login: "{bin} auth login",
        signed_in: None,
    },
    Harness {
        id: "grok",
        name: "Grok Build",
        bins: &["grok"],
        local: Some("{bin} agent stdio"),
        registry: Some("grok-build"),
        setup: "Install Grok Build (curl -fsSL https://x.ai/cli/install.sh | bash) and run `grok login --device-auth`.",
        install: Some(Install::Npm("@xai-official/grok")),
        login: "{bin} login --device-auth",
        signed_in: None,
    },
    Harness {
        id: "gemini",
        name: "Gemini CLI",
        bins: &["gemini"],
        local: Some("{bin} --acp"),
        registry: Some("gemini"),
        setup: "Install Gemini CLI (npm install -g @google/gemini-cli) and run `gemini` to sign in with Google.",
        install: Some(Install::Npm("@google/gemini-cli")),
        login: "NO_BROWSER=true {bin}",
        signed_in: None,
    },
    Harness {
        id: "copilot",
        name: "GitHub Copilot",
        bins: &["copilot"],
        local: Some("{bin} --acp --stdio"),
        registry: Some("github-copilot-cli"),
        setup: "Install Copilot CLI (npm install -g @github/copilot) and run `copilot login`.",
        install: Some(Install::Npm("@github/copilot")),
        login: "{bin} login --device-code",
        signed_in: None,
    },
    Harness {
        id: "qwen",
        name: "Qwen Code",
        bins: &["qwen"],
        local: Some("{bin} --acp"),
        registry: Some("qwen-code"),
        setup: "Install Qwen Code (npm install -g @qwen-code/qwen-code), run `qwen` and type /auth.",
        install: Some(Install::Npm("@qwen-code/qwen-code")),
        login: "{bin}",
        signed_in: None,
    },
    Harness {
        id: "goose",
        name: "goose",
        bins: &["goose"],
        local: Some("{bin} acp"),
        registry: Some("goose"),
        setup: "Install goose and run `goose configure`.",
        install: Some(Install::Script(
            "curl -fsSL https://github.com/aaif-goose/goose/releases/download/stable/download_cli.sh | bash",
        )),
        login: "{bin} configure",
        signed_in: None,
    },
    Harness {
        id: "kimi",
        name: "Kimi Code",
        bins: &["kimi"],
        local: Some("{bin} acp"),
        registry: Some("kimi"),
        setup: "Install Kimi Code (curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash) and run `kimi login`.",
        install: Some(Install::Script("curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash")),
        login: "{bin} login",
        signed_in: None,
    },
    Harness {
        id: "droid",
        name: "Factory Droid",
        bins: &["droid"],
        local: Some("{bin} exec --output-format acp-daemon"),
        registry: Some("factory-droid"),
        setup: "Install Droid (curl -fsSL https://app.factory.ai/cli | sh) and sign in with `droid`.",
        install: Some(Install::Npm("droid")),
        login: "{bin}",
        signed_in: None,
    },
    Harness {
        id: "amp",
        name: "Amp",
        bins: &["amp"],
        local: None,
        registry: Some("amp-acp"),
        setup: "Install Amp (curl -fsSL https://ampcode.com/install.sh | bash) and run `amp login`.",
        install: Some(Install::Script("curl -fsSL https://ampcode.com/install.sh | bash")),
        login: "{bin} login",
        signed_in: None,
    },
    Harness {
        id: "kilo",
        name: "Kilo",
        bins: &["kilo"],
        local: Some("{bin} acp"),
        registry: Some("kilo"),
        setup: "Install Kilo CLI (npm install -g @kilocode/cli) and run `kilo auth login`.",
        install: Some(Install::Npm("@kilocode/cli")),
        login: "{bin} auth login",
        signed_in: Some(SignInCheck { args: "profile --json", ok: exit_ok }),
    },
    Harness {
        id: "cline",
        name: "Cline",
        bins: &["cline"],
        local: Some("{bin} --acp"),
        registry: Some("cline"),
        setup: "Install Cline CLI (npm install -g cline) and run `cline auth`.",
        install: Some(Install::Npm("cline")),
        login: "{bin} auth",
        signed_in: None,
    },
    Harness {
        id: "auggie",
        name: "Auggie",
        bins: &["auggie"],
        local: Some("{bin} --acp"),
        registry: Some("auggie"),
        setup: "Install Auggie (npm install -g @augmentcode/auggie) and run `auggie login`.",
        install: Some(Install::Npm("@augmentcode/auggie")),
        login: "{bin} login",
        signed_in: Some(SignInCheck { args: "account status", ok: exit_ok }),
    },
    Harness {
        id: "vibe",
        name: "Mistral Vibe",
        bins: &["vibe-acp", "vibe"],
        local: None,
        registry: Some("mistral-vibe"),
        setup: "Install Mistral Vibe (curl -LsSf https://mistral.ai/vibe/install.sh | bash) and run `vibe --setup`.",
        install: Some(Install::Script("curl -LsSf https://mistral.ai/vibe/install.sh | bash")),
        login: "vibe --setup",
        signed_in: None,
    },
    Harness {
        id: "kiro",
        name: "Kiro CLI",
        bins: &["kiro-cli"],
        local: Some("{bin} acp"),
        registry: None,
        setup: "Install Kiro CLI (curl -fsSL https://cli.kiro.dev/install | bash) and run `kiro-cli login`.",
        install: Some(Install::Script("curl -fsSL https://cli.kiro.dev/install | bash")),
        login: "{bin} login",
        signed_in: None,
    },
    Harness {
        id: "devin",
        name: "Devin",
        bins: &["devin"],
        local: Some("{bin} acp"),
        registry: Some("devin"),
        setup: "Install the Devin CLI (curl -fsSL https://cli.devin.ai/install.sh | bash) and run `devin auth login`.",
        install: Some(Install::Script("curl -fsSL https://cli.devin.ai/install.sh | bash")),
        login: "{bin} auth login",
        signed_in: None,
    },
    Harness {
        id: "qoder",
        name: "Qoder CLI",
        bins: &["qodercli"],
        local: Some("{bin} --acp"),
        registry: Some("qoder"),
        setup: "Install Qoder CLI (npm install -g @qoder-ai/qodercli) and run `qodercli login`.",
        install: Some(Install::Npm("@qoder-ai/qodercli")),
        login: "{bin} login",
        signed_in: Some(SignInCheck { args: "status -o json", ok: qoder_signed_in }),
    },
    Harness {
        id: "codebuddy",
        name: "CodeBuddy Code",
        bins: &["codebuddy", "cbc"],
        local: Some("{bin} --acp"),
        registry: Some("codebuddy-code"),
        setup: "Install CodeBuddy Code (npm install -g @tencent-ai/codebuddy-code) and sign in with `codebuddy`.",
        install: Some(Install::Npm("@tencent-ai/codebuddy-code")),
        login: "{bin}",
        signed_in: None,
    },
    Harness {
        id: "minimax",
        name: "MiniMax Code",
        bins: &["mcode"],
        local: Some("{bin} acp"),
        registry: Some("minimax-code"),
        setup: "Install MiniMax Code (npm install -g @minimax-ai/code) and run `mcode login`.",
        install: Some(Install::Npm("@minimax-ai/code")),
        login: "{bin} login",
        signed_in: None,
    },
    Harness {
        id: "junie",
        name: "Junie",
        bins: &["junie"],
        local: Some("{bin} --acp=true"),
        registry: Some("junie"),
        setup: "Install Junie (curl -fsSL https://junie.jetbrains.com/install.sh | bash) and sign in with `junie`.",
        install: Some(Install::Script("curl -fsSL https://junie.jetbrains.com/install.sh | bash")),
        login: "{bin}",
        signed_in: None,
    },
    Harness {
        id: "antigravity",
        name: "Google Antigravity",
        bins: &["agy"],
        local: None,
        registry: Some("antigravity-acp"),
        setup: "Install the Antigravity CLI (curl -fsSL https://antigravity.google/cli/install.sh | bash) and sign in with `agy`.",
        install: Some(Install::Script("curl -fsSL https://antigravity.google/cli/install.sh | bash")),
        login: "{bin}",
        signed_in: None,
    },
    Harness {
        id: "cortex",
        name: "Cortex Code",
        bins: &["cortex"],
        local: Some("{bin} acp serve"),
        registry: Some("cortex-code"),
        setup: "Install Cortex Code (curl -LsS https://ai.snowflake.com/static/cc-scripts/install.sh | sh) and set up a connection with `cortex`.",
        install: Some(Install::Script("curl -LsS https://ai.snowflake.com/static/cc-scripts/install.sh | sh")),
        login: "{bin}",
        signed_in: None,
    },
    Harness {
        id: "poolside",
        name: "Poolside",
        bins: &["pool"],
        local: Some("{bin} acp"),
        registry: Some("poolside"),
        setup: "Install Poolside (curl -fsSL https://downloads.poolside.ai/pool/install.sh | sh) and run `pool login`.",
        install: Some(Install::Script("curl -fsSL https://downloads.poolside.ai/pool/install.sh | sh")),
        login: "{bin} login",
        signed_in: None,
    },
];

pub fn harness(id: &str) -> Option<&'static Harness> {
    HARNESSES.iter().find(|h| h.id == id)
}

// MARK: PATH hydration

static SEARCH_PATH: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());

/// The user's saved PATH on Windows (User + Machine), so a CLI installed after Hypurr started
/// (installers add their folder there) is found without restarting Hypurr.
fn windows_saved_path() -> Option<String> {
    let mut cmd = std::process::Command::new("powershell.exe");
    cmd.args([
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "[Environment]::GetEnvironmentVariable('Path','User') + ';' + [Environment]::GetEnvironmentVariable('Path','Machine')",
    ])
    .stdin(std::process::Stdio::null())
    .stderr(std::process::Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let out = cmd.output().ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_owned())
}

/// Login-shell PATH, via `$SHELL -ilc` with a timeout (nvm/conda can make shells slow).
/// On Windows, the saved User and Machine PATH instead.
fn login_shell_path() -> Option<String> {
    const MARK: &str = "__HYPURR_PATH__";
    if cfg!(windows) {
        return windows_saved_path();
    }
    let shell = std::env::var("SHELL").unwrap_or_else(|_| "/bin/zsh".into());
    let mut child = std::process::Command::new(shell)
        .args(["-ilc", &format!("printf '%s%s%s' '{MARK}' \"$PATH\" '{MARK}'")])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()
        .ok()?;
    let deadline = std::time::Instant::now() + Duration::from_secs(8);
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) if std::time::Instant::now() < deadline => std::thread::sleep(Duration::from_millis(50)),
            _ => {
                let _ = child.kill();
                return None;
            }
        }
    }
    let mut out = String::new();
    std::io::Read::read_to_string(&mut child.stdout.take()?, &mut out).ok()?;
    let start = out.find(MARK)? + MARK.len();
    let end = start + out[start..].find(MARK)?;
    Some(out[start..end].to_owned())
}

/// `…/v22.1.0` → `[22, 1, 0]` (unparseable parts count as 0).
fn node_version(dir: &Path) -> Vec<u32> {
    dir.file_name()
        .and_then(|n| n.to_str())
        .map(|n| n.trim_start_matches('v').split('.').map(|p| p.parse().unwrap_or(0)).collect())
        .unwrap_or_default()
}

fn well_known_dirs() -> Vec<PathBuf> {
    let Some(home) = dirs::home_dir() else { return vec![] };
    let mut dirs: Vec<PathBuf> = Vec::new();
    // Managed Hypurr Agent first so the built-in agent wins over a random PATH copy.
    if let Some(d) = crate::agent::builtin::bin_dir() {
        dirs.push(d);
    }
    dirs.extend(
        ["/opt/homebrew/bin", "/usr/local/bin", "/home/linuxbrew/.linuxbrew/bin", "/usr/bin", "/bin"]
            .iter()
            .map(PathBuf::from),
    );
    for rel in [
        ".local/bin",
        ".bun/bin",
        ".cargo/bin",
        ".volta/bin",
        ".asdf/shims",
        ".local/share/mise/shims",
        ".fnm/aliases/default/bin",
        ".local/share/pnpm",
        "Library/pnpm",
        ".npm-global/bin",
        ".hypurr/agent/bin",
        ".deno/bin",
        "go/bin",
    ] {
        dirs.push(home.join(rel));
    }
    // Windows per-user installs (Cursor's PowerShell installer, global npm).
    for var in ["LOCALAPPDATA", "APPDATA"] {
        if let Some(base) = std::env::var_os(var).map(PathBuf::from) {
            dirs.push(base.join(if var == "APPDATA" { "npm" } else { "cursor-agent" }));
        }
    }
    // Every nvm-installed node, newest first (global npm CLIs live next to node).
    if let Ok(entries) = std::fs::read_dir(home.join(".nvm/versions/node")) {
        let mut versions: Vec<PathBuf> = entries.filter_map(Result::ok).map(|e| e.path()).collect();
        // Numeric, not lexicographic: v22.1.0 is newer than v9.11.2.
        versions.sort_by_key(|p| std::cmp::Reverse(node_version(p)));
        dirs.extend(versions.into_iter().map(|p| p.join("bin")));
    }
    dirs
}

/// Re-detects the search path and exports it as this process's PATH, so every
/// agent we spawn (and the `npx`/`node` they need) resolves the same way.
pub fn hydrate_path() {
    let mut seen = HashSet::new();
    let mut path: Vec<PathBuf> = vec![];
    let current = std::env::var("PATH").unwrap_or_default();
    let login = login_shell_path().unwrap_or_default();
    for p in std::env::split_paths(&current).chain(std::env::split_paths(&login)).chain(well_known_dirs()) {
        if p.as_os_str().is_empty() || !p.is_dir() {
            continue;
        }
        if seen.insert(p.clone()) {
            path.push(p);
        }
    }
    if let Ok(joined) = std::env::join_paths(&path) {
        // SAFETY: called at startup and from the single refresh command, before/between spawns.
        unsafe { std::env::set_var("PATH", joined) };
    }
    *SEARCH_PATH.locked() = path;
}

/// The harness's installed CLI. Generic names (Cursor's `agent`) only count when the file is
/// really that harness's: Grok also ships an `agent.exe`.
fn resolve(h: &Harness) -> Option<PathBuf> {
    h.bins.iter().find_map(|b| which(b).filter(|p| bin_is_theirs(h.id, b, p)))
}

fn bin_is_theirs(id: &str, bin: &str, path: &Path) -> bool {
    match (id, bin) {
        ("cursor", "agent") => {
            let real = std::fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
            [path, real.as_path()].iter().any(|p| p.to_string_lossy().to_ascii_lowercase().contains("cursor"))
        }
        _ => true,
    }
}

pub fn which(bin: &str) -> Option<PathBuf> {
    let dirs = {
        let cached = SEARCH_PATH.locked();
        if cached.is_empty() {
            std::env::var_os("PATH").map(|p| std::env::split_paths(&p).collect()).unwrap_or_default()
        } else {
            cached.clone()
        }
    };
    dirs.into_iter().find_map(|d| exe_in(&d, bin))
}

/// `dir/bin` on Unix. On Windows, `bin` + PATHEXT (.exe, .cmd, .bat, then .ps1) and never the
/// extensionless file: npm puts a `sh` script there, and spawning it fails with os error 193.
fn exe_in(dir: &Path, bin: &str) -> Option<PathBuf> {
    if !cfg!(windows) {
        let p = dir.join(bin);
        return is_executable(&p).then_some(p);
    }
    if Path::new(bin).extension().is_some() {
        let p = dir.join(bin);
        return p.is_file().then_some(p);
    }
    windows_exts().iter().map(|ext| dir.join(format!("{bin}{ext}"))).find(|p| p.is_file())
}

/// PATHEXT order limited to what Hypurr can launch, plus `.ps1` last.
fn windows_exts() -> Vec<String> {
    const RUNNABLE: [&str; 3] = [".exe", ".cmd", ".bat"];
    let from_env = std::env::var("PATHEXT").unwrap_or_default();
    let mut out: Vec<String> =
        from_env.split(';').map(|e| e.trim().to_ascii_lowercase()).filter(|e| RUNNABLE.contains(&e.as_str())).collect();
    for e in RUNNABLE {
        if !out.iter().any(|o| o == e) {
            out.push(e.to_owned());
        }
    }
    out.push(".ps1".into());
    out
}

fn is_executable(p: &Path) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::metadata(p).is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
    }
    #[cfg(not(unix))]
    {
        p.is_file()
    }
}

pub fn on_path(bin: &str) -> bool {
    which(bin).is_some()
}

// MARK: listing + launching

/// Every harness the phone can pick, installed ones first.
pub fn list() -> Vec<Value> {
    let registry = registry::agents();
    let mut out: Vec<Value> = HARNESSES
        .iter()
        .map(|h| {
            let path = resolve(h);
            let reg = h.registry.and_then(|id| registry.iter().find(|a| a["id"] == id));
            let runnable =
                (path.is_some() && h.local.is_some()) || reg.is_some_and(|a| registry::launch_kind(a).is_some());
            let managed = h.id == crate::agent::builtin::BACKEND_ID;
            let installed = path.is_some() || (managed && crate::agent::builtin::is_installed());
            let mut row = json!({
                "id": h.id,
                "name": h.name,
                "installed": installed,
                "available": (path.is_some() && runnable) || (managed && (crate::agent::builtin::is_installed() || crate::agent::builtin::release_ok())),
                "path": path.as_ref().map(|p| p.to_string_lossy().into_owned()),
                "description": if managed {
                    "Hypurr Agent with free models on your own account.".into()
                } else if h.id == "opencode" {
                    // Legacy CLI: never show the upstream name.
                    "Hypurr Agent from a CLI already on your computer.".into()
                } else {
                    reg.and_then(|a| a["description"].as_str()).unwrap_or_default().to_owned()
                },
                "installHint": h.setup,
                "signedIn": signed_in(h.id),
                "canInstall": h.install.is_some_and(|i| i.available_for(h.id, cfg!(windows))),
                "installNote": h.install.filter(|i| !i.available_for(h.id, cfg!(windows))).map(|_| NOT_ON_WINDOWS),
                "command": h.local.unwrap_or_default(),
                "registry": h.registry,
                "curated": true,
            });
            if managed {
                let st = crate::agent::builtin::status();
                for k in ["builtin", "free", "defaultModel", "freeModels", "gatewayReady", "needsInstall", "version", "subtitle", "agentName", "consent", "license", "source"] {
                    row[k] = st[k].clone();
                }
                row["name"] = if crate::agent::builtin::is_installed() || path.is_some() {
                    json!(crate::agent::builtin::DISPLAY_NAME)
                } else {
                    json!("Hypurr Agent (free models)")
                };
                // Managed binary path when we installed it.
                if crate::agent::builtin::is_installed() {
                    row["path"] = json!(crate::agent::builtin::bin_path().to_string_lossy());
                    row["installed"] = json!(true);
                    row["available"] = json!(true);
                }
            }
            row
        })
        .collect();
    // Registry agents we don't know by name: runnable, but you sign in yourself.
    let known: HashSet<&str> = HARNESSES.iter().filter_map(|h| h.registry).collect();
    for a in &registry {
        let id = a["id"].as_str().unwrap_or_default();
        if id.is_empty() || known.contains(id) || id == "github-copilot" {
            continue;
        }
        out.push(json!({
            "id": id,
            "name": a["name"],
            "installed": false,
            "available": registry::launch_kind(a).is_some(),
            "path": null,
            "description": a["description"],
            "installHint": format!("Hypurr installs it automatically. Sign in to {} on this computer first if it needs an account.", a["name"].as_str().unwrap_or(id)),
            "signedIn": signed_in(id),
            "command": "",
            "registry": id,
            "curated": false,
        }));
    }
    out.sort_by_key(|v| {
        (
            !v["builtin"].as_bool().unwrap_or(false),
            !v["installed"].as_bool().unwrap_or(false),
            !v["curated"].as_bool().unwrap_or(false),
        )
    });
    out
}

// MARK: sign-in

/// Last sign-in check per harness id; missing = can't tell.
static SIGNED_IN: Mutex<Vec<(String, bool)>> = Mutex::new(Vec::new());

pub fn signed_in(id: &str) -> Option<bool> {
    SIGNED_IN.locked().iter().find(|(h, _)| h == id).map(|(_, ok)| *ok)
}

/// Records what a check found (`None`: can't tell).
pub fn set_signed_in(id: &str, state: Option<bool>) {
    let mut map = SIGNED_IN.locked();
    map.retain(|(h, _)| h != id);
    if let Some(ok) = state {
        map.push((id.to_owned(), ok));
    }
}

/// Re-checks every installed harness that can report its sign-in state.
pub async fn refresh_sign_in() {
    let checks = HARNESSES.iter().filter_map(|h| {
        let check = h.signed_in?;
        let bin = resolve(h)?;
        Some(async move {
            let args: Vec<&str> = check.args.split_whitespace().collect();
            let run = crate::oscmd::program_command(&bin, &args)
                .stdin(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .kill_on_drop(true)
                .output();
            let ok = match tokio::time::timeout(Duration::from_secs(15), run).await {
                Ok(Ok(out)) => Some((check.ok)(out.status.success(), &String::from_utf8_lossy(&out.stdout))),
                Ok(Err(e)) => {
                    tracing::info!(backend = h.id, error = %e, "sign-in check failed");
                    None
                }
                Err(_) => None,
            };
            (h.id, ok)
        })
    });
    let results = futures::future::join_all(checks).await;
    for (id, ok) in results {
        set_signed_in(id, ok);
    }
}

/// `program` is some other harness's CLI (not `backend`'s own).
pub fn belongs_to_other(backend: &str, program: &str) -> bool {
    let name = Path::new(program).file_name().and_then(|n| n.to_str()).unwrap_or(program);
    let own = |h: &Harness| h.id == backend || h.registry == Some(backend);
    HARNESSES.iter().any(|h| !own(h) && h.bins.contains(&name))
        && !HARNESSES.iter().any(|h| own(h) && h.bins.contains(&name))
}

pub fn is_known(id: &str) -> bool {
    harness(id).is_some() || registry::agent(id).is_some()
}

/// Ways to start this backend, best first. Local CLIs can be too old to speak
/// ACP, so the registry build is kept as a fallback.
pub async fn launch_candidates(id: &str, progress: impl Fn(&str)) -> anyhow::Result<Vec<registry::Cmd>> {
    let mut out: Vec<registry::Cmd> = local_candidate(id).into_iter().collect();
    let h = harness(id);
    let reg_id = h.map_or(Some(id), |h| h.registry);
    if let Some(agent) = reg_id.and_then(registry::agent) {
        match registry::command(&agent, &progress).await {
            Ok(cmd) => out.push(cmd),
            Err(e) if out.is_empty() => return Err(e),
            Err(e) => tracing::info!(backend = id, error = format!("{e:#}"), "registry build unavailable"),
        }
    }
    if out.is_empty() {
        let hint = h.map_or("Check that it's installed on this computer.", |h| h.setup);
        anyhow::bail!("{} isn't set up on this computer. {hint}", h.map_or(id, |h| h.name));
    }
    Ok(out)
}

/// The registry build that *is* the harness's CLI (same npm package), so it can
/// sign in without the CLI installed. Adapters (claude-acp…) don't count.
fn registry_cli(h: &Harness) -> Option<Value> {
    let Some(Install::Npm(pkg)) = h.install else { return None };
    let agent = registry::agent(h.registry?)?;
    let npx = agent["distribution"]["npx"]["package"].as_str()?;
    (npx == pkg || npx.strip_prefix(pkg).is_some_and(|v| v.starts_with('@'))).then_some(agent)
}

/// Hypurr's own sign-in command for `id` can run now.
pub fn login_available(id: &str) -> bool {
    harness(id)
        .is_some_and(|h| h.bins.iter().any(|b| on_path(b)) || !h.login.contains("{bin}") || registry_cli(h).is_some())
}

/// Hypurr's own sign-in command for `id`, with `{bin}` resolved.
pub async fn login_command(id: &str) -> anyhow::Result<String> {
    let h = harness(id).ok_or_else(|| anyhow::anyhow!("no sign-in command for {id}"))?;
    let bin = match resolve(h) {
        Some(p) => crate::oscmd::shell_program(&p),
        None => match registry_cli(h) {
            Some(agent) => registry::command(&agent, |_| {}).await?.program,
            None if !h.login.contains("{bin}") => String::new(),
            None => anyhow::bail!("Install {} first.", h.name),
        },
    };
    Ok(h.login.replace("{bin}", &bin))
}

/// The installed CLI's own ACP mode, if it has one and it's installed.
pub fn local_candidate(id: &str) -> Option<registry::Cmd> {
    let h = harness(id)?;
    let template = h.local?;
    let path = if id == crate::agent::builtin::BACKEND_ID && crate::agent::builtin::is_installed() {
        Some(crate::agent::builtin::bin_path())
    } else {
        resolve(h)
    }?;
    let args = template.strip_prefix("{bin}").unwrap_or(template).trim().to_owned();
    Some(registry::Cmd { program: crate::oscmd::shell_program(&path), args })
}

#[cfg(test)]
mod tests {

    #[test]
    fn windows_never_gets_a_unix_pipe_script() {
        for h in HARNESSES {
            let Some(i) = h.install else { continue };
            match i.command_for(h.id, true) {
                Ok(c) => {
                    let t = c.text.to_lowercase();
                    assert!(
                        !t.contains("| bash") && !t.contains("| sh") && !t.contains("command -v"),
                        "{}: {}",
                        h.id,
                        c.text
                    );
                    if !matches!(i, Install::Managed) {
                        assert!(c.powershell, "{} runs in PowerShell on Windows", h.id);
                    }
                }
                Err(e) => assert_eq!(e, NOT_ON_WINDOWS, "{}", h.id),
            }
        }
    }

    #[test]
    fn windows_commands_follow_vendor_docs() {
        let w = |id: &str| harness(id).unwrap().install.unwrap().command_for(id, true);
        assert_eq!(w("claude").unwrap().text, "irm https://claude.ai/install.ps1 | iex");
        assert_eq!(w("codex").unwrap().text, "irm https://chatgpt.com/codex/install.ps1 | iex");
        assert_eq!(w("cursor").unwrap().text, "irm 'https://cursor.com/install?win32=true' | iex");
        assert!(w("vibe").unwrap().text.ends_with("uv tool install mistral-vibe"));
        assert_eq!(w("amp"), Err(NOT_ON_WINDOWS));
        let g = w("gemini").unwrap();
        assert!(
            g.powershell
                && g.text.contains("npm.cmd install -g @google/gemini-cli")
                && g.text.contains("https://nodejs.org")
        );
    }

    #[test]
    fn unix_commands_are_unchanged() {
        let u = |id: &str| harness(id).unwrap().install.unwrap().command_for(id, false).unwrap();
        assert_eq!(u("claude").text, "curl -fsSL https://claude.ai/install.sh | bash");
        assert!(!u("claude").powershell);
        assert_eq!(u("amp").text, "curl -fsSL https://ampcode.com/install.sh | bash");
        assert!(u("gemini").text.contains("npm install -g @google/gemini-cli") && u("gemini").text.contains("Node.js"));
        // Every Unix install still works where it did.
        assert!(
            HARNESSES.iter().filter_map(|h| h.install.map(|i| (h.id, i))).all(|(id, i)| i.available_for(id, false))
        );
    }

    #[test]
    fn install_plan_lists_every_installable_agent() {
        let p = install_plan();
        let ids: Vec<&str> = p["agents"].as_array().unwrap().iter().map(|a| a["id"].as_str().unwrap()).collect();
        assert!(ids.contains(&"cursor") && ids.contains(&"gemini") && !ids.contains(&"hypurr-agent"));
        assert!(!p.to_string().to_lowercase().contains("opencode"));
    }
    use super::*;

    #[test]
    fn cursor_agent_fallback_must_be_cursors() {
        let dir = std::env::temp_dir().join(format!("hypurr-cur-{}", uuid::Uuid::new_v4()));
        let grok = dir.join(".grok").join("bin");
        let cursor = dir.join("cursor-agent").join("bin");
        std::fs::create_dir_all(&grok).unwrap();
        std::fs::create_dir_all(&cursor).unwrap();
        let name = if cfg!(windows) { "agent.exe" } else { "agent" };
        for d in [&grok, &cursor] {
            std::fs::write(d.join(name), "x").unwrap();
        }
        assert!(!bin_is_theirs("cursor", "agent", &grok.join(name)), "Grok's agent isn't Cursor");
        assert!(bin_is_theirs("cursor", "agent", &cursor.join(name)));
        assert!(bin_is_theirs("cursor", "cursor-agent", &grok.join(name)), "the specific name is trusted");
        assert!(bin_is_theirs("grok", "grok", &grok.join(name)));
        #[cfg(unix)]
        {
            // ~/.local/bin/agent -> ~/.local/share/cursor-agent/... (the installer's symlink) counts.
            let link = dir.join("agent");
            std::os::unix::fs::symlink(cursor.join(name), &link).unwrap();
            assert!(bin_is_theirs("cursor", "agent", &link));
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn legacy_cli_row_never_says_opencode() {
        for row in list().into_iter().filter(|r| r["id"] == "opencode" || r["id"] == "hypurr-agent") {
            for k in ["name", "description", "installHint"] {
                let v = row[k].as_str().unwrap_or_default().to_lowercase();
                assert!(!v.contains("opencode"), "{k}: {v}");
            }
            assert!(row["name"].as_str().unwrap().starts_with("Hypurr Agent"));
        }
    }

    /// npm's layout on Windows: an extensionless `sh` shim next to the real `.cmd`.
    #[cfg(windows)]
    #[tokio::test]
    async fn windows_prefers_cmd_shim_over_extensionless_file() {
        let dir = std::env::temp_dir().join(format!("hypurr shim {}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("codex"), "#!/bin/sh\nexec node codex.js \"$@\"\n").unwrap();
        std::fs::write(dir.join("codex.cmd"), "@echo off\r\necho logged in as %1 %2\r\n").unwrap();
        let found = exe_in(&dir, "codex").expect("finds the .cmd");
        assert_eq!(found.extension().unwrap(), "cmd");
        assert!(exe_in(&dir, "claude").is_none(), "nothing installed");
        // Spawning the .cmd (path has spaces) works; the extensionless file would give os error 193.
        let out = crate::oscmd::program_command(&found, &["login", "status"]).output().await.unwrap();
        assert!(out.status.success());
        assert_eq!(String::from_utf8_lossy(&out.stdout).trim(), "logged in as login status");
        // Same line through the session shell.
        let line = format!("{} login status", crate::oscmd::shell_program(&found));
        let out = crate::oscmd::tokio_shell(&line).output().await.unwrap();
        assert_eq!(String::from_utf8_lossy(&out.stdout).trim(), "logged in as login status");
        // .exe beats .cmd; only an extensionless file means "not installed".
        std::fs::copy(std::env::var_os("ComSpec").unwrap(), dir.join("codex.exe")).unwrap();
        assert_eq!(exe_in(&dir, "codex").unwrap().extension().unwrap(), "exe");
        std::fs::remove_file(dir.join("codex.exe")).unwrap();
        std::fs::remove_file(dir.join("codex.cmd")).unwrap();
        assert!(exe_in(&dir, "codex").is_none(), "never the extensionless shim");
        std::fs::write(dir.join("codex.ps1"), "Write-Output ps-ok").unwrap();
        let ps = exe_in(&dir, "codex").unwrap();
        assert!(crate::oscmd::shell_program(&ps).starts_with("powershell.exe "));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(windows)]
    #[test]
    fn every_backend_resolves_through_pathext() {
        let dir = std::env::temp_dir().join(format!("hypurr-shims-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        for b in ["claude", "codex", "gemini", "cursor-agent", "hypurr-agent"] {
            std::fs::write(dir.join(b), "#!/bin/sh").unwrap();
            assert!(exe_in(&dir, b).is_none(), "{b}: extensionless only");
            std::fs::write(dir.join(format!("{b}.cmd")), "@echo off").unwrap();
            assert_eq!(exe_in(&dir, b).unwrap(), dir.join(format!("{b}.cmd")), "{b}");
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn legacy_opencode_id_is_accepted_but_shown_as_hypurr_agent() {
        let h = harness("opencode").expect("legacy id still accepted");
        assert_eq!(h.name, "Hypurr Agent");
        assert!(!h.setup.to_lowercase().contains("opencode"), "{}", h.setup);
    }

    #[cfg(unix)]
    #[test]
    fn detects_executables_on_search_path() {
        let dir = std::env::temp_dir().join(format!("hypurr-bin-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let bin = dir.join("fake-harness");
        std::fs::write(&bin, "#!/bin/sh\n").unwrap();
        *SEARCH_PATH.locked() = vec![dir.clone()];
        assert!(which("fake-harness").is_none(), "not executable yet");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        assert_eq!(which("fake-harness"), Some(bin));
        SEARCH_PATH.locked().clear();
    }

    #[test]
    fn every_harness_is_unique() {
        let ids: HashSet<_> = HARNESSES.iter().map(|h| h.id).collect();
        assert_eq!(ids.len(), HARNESSES.len());
    }
}
