//! "Show me it works": visual proof of a task's result, sent to the phone.
//!
//! When a task finishes (or on demand), Hypurr looks at the project and picks the best proof:
//! - **Web**: starts the dev server in the task's safe-branch copy and in a copy of the base
//!   commit, and captures the changed pages before vs after (Playwright when the project has
//!   it, with a short clip; otherwise headless Chrome screenshots).
//! - **Android**: with an adb device, installs the branch build and captures the screen and a
//!   short clip; otherwise records the project's Roborazzi / Paparazzi screenshots on both
//!   sides and pairs the ones that changed.
//! - **Anything else**: runs the project's tests (real pass/fail counts) and summarises the diff.
//!
//! Starting servers and browsers runs project code, so the first proof for a project asks for
//! approval (remembered per project; teams can require an admin). Every file stays inside the
//! task's copy (`.hypurr/proof/`, git-ignored); logs are redacted; every step is time-limited
//! and servers are killed after capture.

use crate::hub::Hub;
use crate::store::{EntryKind, Lane, Store};
use crate::tasks::Task;
use anyhow::{Result, anyhow, bail, ensure};
use serde_json::{Value, json};
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// The whole proof run, start to finish.
const TOTAL: Duration = Duration::from_secs(8 * 60);
/// Packages must be installed by then, leaving time to capture inside the 8-minute cap.
const INSTALL_BY: Duration = Duration::from_secs(5 * 60);
/// How long a dev server gets to start listening.
const SERVER_START: Duration = Duration::from_secs(90);
/// Pages captured at most.
const MAX_PAGES: usize = 3;
/// Screenshot pairs shown at most.
const MAX_SHOTS: usize = 6;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Web,
    Android,
    Other,
}

impl Kind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Web => "web",
            Self::Android => "android",
            Self::Other => "tests",
        }
    }
}

/// What a proof run will do, shown on the approval card before anything runs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Plan {
    pub kind: Kind,
    /// Plain words: "Start the web app and capture the changed pages".
    pub what: String,
    /// The command that runs project code, if any.
    pub command: Option<String>,
    /// Android: capture on a connected device (otherwise screenshot tests).
    pub device: bool,
}

impl Plan {
    fn public(&self) -> Value {
        json!({"kind": self.kind.as_str(), "what": self.what, "command": self.command})
    }
}

fn read(dir: &Path, f: &str) -> String {
    std::fs::read_to_string(dir.join(f)).unwrap_or_default()
}

const WEB_DEPS: &[&str] = &[
    "\"next\"",
    "\"vite\"",
    "\"react-scripts\"",
    "\"astro\"",
    "\"nuxt\"",
    "\"@sveltejs/kit\"",
    "\"@angular/core\"",
    "\"gatsby\"",
    "\"@remix-run/dev\"",
];

/// The dev server command for a web project (`None`: not a web app).
pub fn web_command(dir: &Path) -> Option<String> {
    let pkg = read(dir, "package.json");
    if pkg.is_empty() || !WEB_DEPS.iter().any(|d| pkg.contains(d)) {
        return None;
    }
    let v: Value = serde_json::from_str(&pkg).ok()?;
    let runner = if dir.join("pnpm-lock.yaml").is_file() {
        "pnpm"
    } else if dir.join("yarn.lock").is_file() {
        "yarn"
    } else {
        "npm"
    };
    let script = ["dev", "start", "serve"].into_iter().find(|s| v["scripts"][s].is_string())?;
    Some(if runner == "npm" && script != "start" { format!("npm run {script}") } else { format!("{runner} {script}") })
}

fn gradle_files(dir: &Path) -> String {
    let mut all = String::new();
    for f in
        ["build.gradle", "build.gradle.kts", "app/build.gradle", "app/build.gradle.kts", "gradle/libs.versions.toml"]
    {
        all.push_str(&read(dir, f));
    }
    all
}

fn is_android(dir: &Path) -> bool {
    let settings = dir.join("settings.gradle").is_file() || dir.join("settings.gradle.kts").is_file();
    settings && gradle_files(dir).contains("com.android")
}

fn gradlew(dir: &Path) -> &'static str {
    if dir.join("gradlew").is_file() { "./gradlew" } else { "gradle" }
}

/// Whether an Android phone or emulator is attached (`adb devices`).
pub fn adb_device() -> bool {
    std::process::Command::new("adb")
        .arg("devices")
        .output()
        .is_ok_and(|o| String::from_utf8_lossy(&o.stdout).lines().skip(1).any(|l| l.trim_end().ends_with("\tdevice")))
}

/// Picks what proof this project can give.
pub fn plan(dir: &Path, device: bool) -> Plan {
    if let Some(cmd) = web_command(dir) {
        return Plan {
            kind: Kind::Web,
            what: "Start the web app (yours and the original) for a few minutes and capture the changed pages".into(),
            command: Some(cmd),
            device: false,
        };
    }
    if is_android(dir) {
        let g = gradlew(dir);
        if device {
            return Plan {
                kind: Kind::Android,
                what: "Install the new build on the connected phone and capture the screen".into(),
                command: Some(format!("{g} installDebug")),
                device: true,
            };
        }
        let gf = gradle_files(dir);
        let task = if gf.contains("roborazzi") {
            Some("recordRoborazziDebug")
        } else if gf.contains("paparazzi") {
            Some("recordPaparazziDebug")
        } else {
            None
        };
        if let Some(t) = task {
            return Plan {
                kind: Kind::Android,
                what: "Record the app's screenshot tests before and after the change".into(),
                command: Some(format!("{g} {t}")),
                device: false,
            };
        }
    }
    let tests = crate::qa::frameworks(dir).into_iter().find(|f| !f.needs_device);
    Plan {
        kind: Kind::Other,
        what: if tests.is_some() {
            "Run the project's tests and summarise what changed".into()
        } else {
            "Summarise what changed (no tests found to run)".into()
        },
        command: tests.map(|f| f.command),
        device: false,
    }
}

// ---------- approval (once per project, remembered) ----------

fn allow_key(project: &str) -> String {
    format!("proof:allowed:{project}")
}

pub fn allowed(store: &Store, project: &str) -> bool {
    store.kv_get(&allow_key(project)).is_some_and(|v| v == "1")
}

fn set_allowed(store: &Store, project: &str) -> Result<()> {
    store.kv_set(&allow_key(project), "1")
}

// ---------- changed files and pages ----------

fn git(dir: &Path, args: &[&str]) -> Option<String> {
    let o = std::process::Command::new("git").args(args).current_dir(dir).output().ok()?;
    o.status.success().then(|| String::from_utf8_lossy(&o.stdout).into_owned())
}

/// The commit the task started from.
fn base_rev(task: &Task, dir: &Path) -> Option<String> {
    if let Some(cp) = task.checkpoints.first() {
        return Some(cp.id.clone());
    }
    let base = task.base.clone().unwrap_or_else(|| "main".into());
    git(dir, &["merge-base", "HEAD", &base]).map(|s| s.trim().to_owned()).filter(|s| !s.is_empty())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Change {
    pub path: String,
    pub added: u64,
    pub removed: u64,
}

/// Files changed since `base`, including uncommitted work in the task's copy.
fn changes(dir: &Path, base: &str) -> Vec<Change> {
    let mut out: Vec<Change> = git(dir, &["diff", "--numstat", base])
        .unwrap_or_default()
        .lines()
        .filter_map(|l| {
            let mut it = l.split('\t');
            let (a, r, p) = (it.next()?, it.next()?, it.next()?);
            Some(Change { path: p.to_owned(), added: a.parse().unwrap_or(0), removed: r.parse().unwrap_or(0) })
        })
        .filter(|c| !c.path.starts_with(".hypurr/"))
        .collect();
    for p in git(dir, &["ls-files", "--others", "--exclude-standard"]).unwrap_or_default().lines() {
        if !p.starts_with(".hypurr/") && !out.iter().any(|c| c.path == p) {
            let added = read(dir, p).lines().count() as u64;
            out.push(Change { path: p.to_owned(), added, removed: 0 });
        }
    }
    out
}

/// The diff in plain words.
pub fn summary(ch: &[Change]) -> String {
    if ch.is_empty() {
        return "No files changed.".into();
    }
    let (a, r): (u64, u64) = ch.iter().fold((0, 0), |(a, r), c| (a + c.added, r + c.removed));
    let new = ch.iter().filter(|c| c.removed == 0 && c.added > 0).count();
    let mut s = format!(
        "Changed {} file{} ({} line{} added, {} removed).",
        ch.len(),
        if ch.len() == 1 { "" } else { "s" },
        a,
        if a == 1 { "" } else { "s" },
        r
    );
    if new > 0 && new < ch.len() {
        let _ = write!(s, " {new} of them look new.");
    }
    let mut biggest: Vec<&Change> = ch.iter().collect();
    biggest.sort_by_key(|c| std::cmp::Reverse(c.added + c.removed));
    let names: Vec<String> =
        biggest.iter().take(3).map(|c| format!("{} (+{} −{})", c.path, c.added, c.removed)).collect();
    let _ = write!(s, " Most work in {}.", names.join(", "));
    s
}

/// The URL path a changed file most likely renders (`Next.js`, Remix, `SvelteKit`, Astro, Nuxt).
pub fn route_for(path: &str) -> Option<String> {
    let p = path.trim_start_matches("src/");
    let stem = |s: &str| s.rsplit_once('.').map_or(s, |(a, _)| a).to_owned();
    let clean = |segs: Vec<&str>| {
        let segs: Vec<&str> = segs
            .into_iter()
            .filter(|s| (!s.starts_with('(') || !s.ends_with(')')) && !s.is_empty() && *s != "index")
            .collect();
        if segs.iter().any(|s| s.starts_with('[') || s.starts_with('$') || s.starts_with('_')) {
            return None;
        }
        Some(format!("/{}", segs.join("/")))
    };
    if let Some(rest) = p.strip_prefix("app/") {
        let file = rest.rsplit('/').next()?;
        if stem(file) != "page" && !file.starts_with("+page") {
            return None;
        }
        let dir: Vec<&str> = rest.split('/').collect();
        return clean(dir[..dir.len() - 1].to_vec());
    }
    for root in ["pages/", "routes/"] {
        if let Some(rest) = p.strip_prefix(root) {
            if rest.starts_with("api/") {
                return None;
            }
            let s = stem(rest);
            let s = s.trim_end_matches("+page").trim_end_matches('/').to_owned();
            return clean(s.split(['/', '.']).collect());
        }
    }
    None
}

/// Pages to capture: the routes the change touched, or the home page.
pub fn pages(ch: &[Change]) -> Vec<String> {
    let mut out: Vec<String> = vec![];
    for c in ch {
        if let Some(r) = route_for(&c.path)
            && !out.contains(&r)
        {
            out.push(r);
        }
    }
    if out.is_empty() {
        out.push("/".into());
    }
    out.truncate(MAX_PAGES);
    out
}

// ---------- running things, time-limited ----------

/// A started process group, killed when dropped.
struct Running {
    child: tokio::process::Child,
}

impl Drop for Running {
    fn drop(&mut self) {
        if let Some(pid) = self.child.id() {
            #[cfg(unix)]
            let _ = std::process::Command::new("kill").args(["-9", "--", &format!("-{pid}")]).status();
        }
        let _ = self.child.start_kill();
    }
}

fn spawn(dir: &Path, cmd: &str, envs: &[(&str, String)], log: &Path) -> Result<Running> {
    let file = std::fs::File::create(log)?;
    let mut c = tokio::process::Command::new("sh");
    c.arg("-c")
        .arg(cmd)
        .current_dir(dir)
        .env("CI", "1")
        .env("BROWSER", "none")
        .stdin(Stdio::null())
        .stdout(file.try_clone()?)
        .stderr(file)
        .kill_on_drop(true);
    for (k, v) in envs {
        c.env(k, v);
    }
    #[cfg(unix)]
    c.process_group(0);
    Ok(Running { child: c.spawn()? })
}

/// Runs a command to the end (or the limit). Returns (ok, output).
async fn run(dir: &Path, cmd: &str, limit: Duration) -> (bool, String) {
    let mut c = tokio::process::Command::new("sh");
    c.arg("-c").arg(cmd).current_dir(dir).env("CI", "1").stdin(Stdio::null()).kill_on_drop(true);
    #[cfg(unix)]
    c.process_group(0);
    match tokio::time::timeout(limit, c.output()).await {
        Ok(Ok(o)) => (
            o.status.success(),
            format!("{}{}", String::from_utf8_lossy(&o.stdout), String::from_utf8_lossy(&o.stderr)),
        ),
        Ok(Err(e)) => (false, format!("couldn't run {cmd}: {e}")),
        Err(_) => (false, format!("{cmd} took longer than {} minutes and was stopped.", limit.as_secs() / 60)),
    }
}

fn free_port() -> Result<u16> {
    Ok(std::net::TcpListener::bind("127.0.0.1:0")?.local_addr()?.port())
}

async fn wait_port(port: u16, limit: Duration) -> bool {
    let until = Instant::now() + limit;
    while Instant::now() < until {
        if tokio::net::TcpStream::connect(("127.0.0.1", port)).await.is_ok() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    false
}

/// The last lines of a log, secrets removed.
pub fn clean_log(text: &str) -> String {
    let lines: Vec<&str> = text.lines().collect();
    let tail = lines[lines.len().saturating_sub(30)..].join("\n");
    crate::triage::redact(&tail).0
}

/// A plain file name inside the proof folder (what the phone may fetch).
fn safe_name(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('.')
        && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))
}

fn proof_dir(work: &Path) -> Result<PathBuf> {
    let d = work.join(".hypurr").join("proof");
    if d.exists() {
        std::fs::remove_dir_all(&d)?;
    }
    std::fs::create_dir_all(&d)?;
    // Never committed: the folder ignores itself.
    std::fs::write(d.join(".gitignore"), "*\n")?;
    Ok(d)
}

/// A detached copy of the base commit inside the proof folder; removed afterwards.
struct BaseCopy {
    repo: PathBuf,
    path: PathBuf,
}

impl BaseCopy {
    fn new(repo: &Path, out: &Path, rev: &str) -> Result<Self> {
        let path = out.join("base");
        let p = path.to_string_lossy().into_owned();
        git(repo, &["worktree", "add", "--detach", "--force", &p, rev])
            .ok_or_else(|| anyhow!("couldn't copy the original code"))?;
        Ok(Self { repo: repo.to_owned(), path })
    }
}

impl Drop for BaseCopy {
    fn drop(&mut self) {
        let p = self.path.to_string_lossy().into_owned();
        let _ = git(&self.repo, &["worktree", "remove", "--force", &p]);
        let _ = std::fs::remove_dir_all(&self.path);
        let _ = git(&self.repo, &["worktree", "prune"]);
    }
}

/// The install command for a web project, from its lockfile.
pub fn install_command(dir: &Path) -> Option<&'static str> {
    if !dir.join("package.json").is_file() {
        return None;
    }
    Some(if dir.join("pnpm-lock.yaml").is_file() {
        "pnpm install --frozen-lockfile --ignore-scripts"
    } else if dir.join("yarn.lock").is_file() {
        "yarn install --frozen-lockfile --ignore-scripts"
    } else if dir.join("package-lock.json").is_file() {
        "npm ci --ignore-scripts --no-audit --no-fund"
    } else {
        "npm install --ignore-scripts --no-audit --no-fund"
    })
}

/// Packages missing: install them inside this folder only, within what's left of the cap.
async fn ensure_deps(dir: &Path, deadline: Instant, log: &mut String) {
    if dir.join("node_modules").exists() {
        return;
    }
    let Some(cmd) = install_command(dir) else { return };
    let left = deadline.saturating_duration_since(Instant::now()).min(Duration::from_secs(4 * 60));
    if left < Duration::from_secs(20) {
        let _ = writeln!(log, "Skipped installing packages: not enough time left.");
        return;
    }
    let (ok, text) = run(dir, cmd, left).await;
    if !ok {
        let _ = writeln!(log, "Installing packages ({cmd}): {}", clean_log(&text));
    }
}

/// Dependencies are shared from the task copy or the project when they exist.
fn link_deps(from: &[&Path], to: &Path) {
    #[cfg(unix)]
    for name in ["node_modules"] {
        if to.join(name).exists() {
            continue;
        }
        if let Some(src) = from.iter().map(|d| d.join(name)).find(|p| p.is_dir()) {
            let _ = std::os::unix::fs::symlink(src, to.join(name));
        }
    }
}

// ---------- capture ----------

const PLAYWRIGHT_JS: &str = r"
const [,, base, outDir, prefix, pagesJson, video] = process.argv;
let pw; try { pw = require('playwright'); } catch (e) { pw = require('@playwright/test'); }
(async () => {
  const browser = await pw.chromium.launch();
  const opts = { viewport: { width: 412, height: 860 }, deviceScaleFactor: 2 };
  if (video === '1') opts.recordVideo = { dir: outDir, size: { width: 412, height: 860 } };
  const ctx = await browser.newContext(opts);
  const page = await ctx.newPage();
  const pages = JSON.parse(pagesJson);
  for (let i = 0; i < pages.length; i++) {
    try { await page.goto(base + pages[i], { waitUntil: 'networkidle', timeout: 30000 }); } catch (e) {}
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${outDir}/${prefix}-${i}.png` });
    if (video === '1') { await page.mouse.wheel(0, 600); await page.waitForTimeout(1200); }
  }
  const v = page.video();
  await ctx.close();
  if (v) { const fs = require('fs'); fs.renameSync(await v.path(), `${outDir}/${prefix}-clip.webm`); }
  await browser.close();
})().catch(e => { console.error(String(e)); process.exit(1); });
";

fn chrome() -> Option<String> {
    let names = [
        "chromium",
        "chromium-browser",
        "google-chrome",
        "google-chrome-stable",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
    ];
    names.into_iter().map(str::to_owned).find(|n| {
        if n.starts_with('/') {
            Path::new(n).is_file()
        } else {
            std::process::Command::new("which").arg(n).output().is_ok_and(|o| o.status.success())
        }
    })
}

fn has_playwright(dir: &Path) -> bool {
    std::process::Command::new("node")
        .args(["-e", "try{require.resolve('playwright')}catch(e){require.resolve('@playwright/test')}"])
        .current_dir(dir)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|s| s.success())
}

/// Screenshots (and with Playwright, one clip) of `pages` served from `dir`.
async fn capture_web(
    dir: &Path,
    cmd: &str,
    out: &Path,
    prefix: &str,
    pages: &[String],
    video: bool,
    log: &mut String,
) -> Result<bool> {
    let port = free_port()?;
    let full =
        if cmd.starts_with("npm run") { format!("{cmd} -- --port {port}") } else { format!("{cmd} --port {port}") };
    let logf = out.join(format!("{prefix}-server.log"));
    let server = spawn(dir, &full, &[("PORT", port.to_string()), ("HOST", "127.0.0.1".into())], &logf)?;
    let up = wait_port(port, SERVER_START).await;
    let base = format!("http://127.0.0.1:{port}");
    let mut ok = false;
    if up {
        if has_playwright(dir) {
            let script = out.join("capture.js");
            std::fs::write(&script, PLAYWRIGHT_JS)?;
            let pj = serde_json::to_string(pages)?;
            let o = out.to_string_lossy();
            let c = format!("node '{}' '{base}' '{o}' {prefix} '{pj}' {}", script.to_string_lossy(), u8::from(video));
            let (good, text) = run(dir, &c, Duration::from_secs(120)).await;
            if !good {
                let _ = writeln!(log, "{prefix}: Playwright: {}", clean_log(&text));
            }
            ok = good;
        }
        if !ok && let Some(ch) = chrome() {
            for (i, p) in pages.iter().enumerate() {
                let shot = out.join(format!("{prefix}-{i}.png"));
                let c = format!(
                    "'{ch}' --headless=new --disable-gpu --no-sandbox --hide-scrollbars --window-size=412,860 --virtual-time-budget=4000 --screenshot='{}' '{base}{p}'",
                    shot.to_string_lossy()
                );
                let (_, text) = run(dir, &c, Duration::from_secs(60)).await;
                if shot.is_file() {
                    ok = true;
                } else {
                    let _ = writeln!(log, "{prefix}: Chrome: {}", clean_log(&text));
                }
            }
        }
        if !ok && chrome().is_none() && !has_playwright(dir) {
            let _ = writeln!(log, "No browser found: add Playwright to the project or install Chrome.");
        }
    } else {
        let _ = writeln!(log, "{prefix}: the dev server didn't start within {} seconds.", SERVER_START.as_secs());
    }
    drop(server);
    let _ = writeln!(log, "{prefix} server: {}", clean_log(&std::fs::read_to_string(&logf).unwrap_or_default()));
    let _ = std::fs::remove_file(logf);
    Ok(ok)
}

fn shot(label: &str, before: Option<&str>, after: Option<&str>) -> Value {
    json!({"label": label, "before": before, "after": after})
}

fn exists(out: &Path, name: &str) -> Option<String> {
    out.join(name).is_file().then(|| name.to_owned())
}

/// PNGs written under `dir` since `since` (screenshot tests), relative paths.
fn new_pngs(dir: &Path, since: std::time::SystemTime) -> Vec<String> {
    fn walk(root: &Path, d: &Path, since: std::time::SystemTime, out: &mut Vec<String>, depth: usize) {
        if depth > 10 || out.len() > 400 {
            return;
        }
        let Ok(rd) = std::fs::read_dir(d) else { return };
        for e in rd.flatten() {
            let p = e.path();
            let name = e.file_name().to_string_lossy().into_owned();
            if name.starts_with('.') || name == "node_modules" {
                continue;
            }
            if p.is_dir() {
                walk(root, &p, since, out, depth + 1);
            } else if Path::new(&name).extension().is_some_and(|x| x.eq_ignore_ascii_case("png"))
                && e.metadata().and_then(|m| m.modified()).is_ok_and(|t| t >= since)
                && let Ok(r) = p.strip_prefix(root)
            {
                out.push(r.to_string_lossy().into_owned());
            }
        }
    }
    let mut out = vec![];
    walk(dir, dir, since, &mut out, 0);
    out.sort();
    out
}

fn label_of(path: &str) -> String {
    let file = path.rsplit('/').next().unwrap_or(path);
    file.trim_end_matches(".png").replace(['_', '-', '.'], " ")
}

/// Pairs screenshot-test images from both copies: changed ones first.
pub fn pair_shots(after: &Path, before: &Path, rels: &[String], out: &Path) -> Vec<Value> {
    let mut changed = vec![];
    let mut same = vec![];
    for r in rels {
        let a = std::fs::read(after.join(r)).unwrap_or_default();
        let b = std::fs::read(before.join(r)).ok();
        if b.as_ref() == Some(&a) { same.push(r) } else { changed.push((r, b.is_some())) }
    }
    let mut shots = vec![];
    for (i, (r, had)) in changed.into_iter().take(MAX_SHOTS).enumerate() {
        let an = format!("after-{i}.png");
        let _ = std::fs::copy(after.join(r), out.join(&an));
        let bn = had.then(|| format!("before-{i}.png"));
        if let Some(bn) = &bn {
            let _ = std::fs::copy(before.join(r), out.join(bn));
        }
        shots.push(shot(&label_of(r), bn.as_deref(), Some(&an)));
    }
    if shots.is_empty() {
        for (i, r) in same.into_iter().take(2).enumerate() {
            let an = format!("after-{i}.png");
            let _ = std::fs::copy(after.join(r), out.join(&an));
            shots.push(shot(&format!("{} (unchanged)", label_of(r)), None, Some(&an)));
        }
    }
    shots
}

fn application_id(dir: &Path) -> Option<String> {
    let re = regex::Regex::new(r#"applicationId\s*=?\s*["']([\w.]+)["']"#).ok()?;
    re.captures(&gradle_files(dir)).map(|c| c[1].to_owned())
}

async fn capture_device(dir: &Path, cmd: &str, out: &Path, log: &mut String) -> Vec<Value> {
    let (ok, text) = run(dir, cmd, Duration::from_secs(10 * 60)).await;
    if !ok {
        let _ = writeln!(log, "Install: {}", clean_log(&text));
        return vec![];
    }
    if let Some(id) = application_id(dir) {
        let _ = run(
            dir,
            &format!("adb shell monkey -p {id} -c android.intent.category.LAUNCHER 1"),
            Duration::from_secs(30),
        )
        .await;
        tokio::time::sleep(Duration::from_secs(3)).await;
    }
    let png = out.join("after-0.png");
    let _ =
        run(dir, &format!("adb exec-out screencap -p > '{}'", png.to_string_lossy()), Duration::from_secs(30)).await;
    let clip = out.join("after-clip.mp4");
    let _ = run(dir, "adb shell screenrecord --time-limit 6 /sdcard/hypurr-proof.mp4", Duration::from_secs(20)).await;
    let _ = run(
        dir,
        &format!(
            "adb pull /sdcard/hypurr-proof.mp4 '{}' && adb shell rm /sdcard/hypurr-proof.mp4",
            clip.to_string_lossy()
        ),
        Duration::from_secs(30),
    )
    .await;
    if png.is_file() { vec![shot("On the phone", None, Some("after-0.png"))] } else { vec![] }
}

// ---------- the run ----------

fn save(hub: &Arc<Hub>, task_id: &str, proof: &Value) -> Result<()> {
    let mut task = Task::load(&hub.store, task_id).ok_or_else(|| anyhow!("unknown task"))?;
    task.extra.insert("proof".into(), proof.clone());
    task.save(&hub.store)?;
    hub.emit_bot(&task.bot_id);
    Ok(())
}

fn work_dir(task: &Task) -> PathBuf {
    task.worktree_path().unwrap_or_else(|| PathBuf::from(&task.project))
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_secs()).unwrap_or(0))
}

/// The proof for one task (`proofGet`).
pub fn get(store: &Store, b: &Value) -> Result<Value> {
    let id = b["taskId"].as_str().ok_or_else(|| anyhow!("missing taskId"))?;
    let task = Task::load(store, id).ok_or_else(|| anyhow!("unknown task"))?;
    Ok(json!({"proof": task.extra.get("proof").cloned().unwrap_or(Value::Null)}))
}

/// "Show me" (and a finished task): run now if this project is allowed, else ask once.
pub async fn start(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let id = b["taskId"].as_str().ok_or_else(|| anyhow!("missing taskId"))?.to_owned();
    let task = Task::load(&hub.store, &id).ok_or_else(|| anyhow!("unknown task"))?;
    if task.extra.get("proof").is_some_and(|p| p["status"] == "running") {
        return get(&hub.store, b);
    }
    let dir = work_dir(&task);
    let p = tokio::task::spawn_blocking(move || plan(&dir, adb_device())).await?;
    if p.command.is_some() && !allowed(&hub.store, &task.project) {
        save(hub, &id, &json!({"status": "needsApproval", "plan": p.public(), "at": now()}))?;
        return get(&hub.store, b);
    }
    launch(hub, &id, p)?;
    get(&hub.store, b)
}

/// The approval card's "Allow": remembered for the project, then the proof runs.
pub async fn allow(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let id = b["taskId"].as_str().ok_or_else(|| anyhow!("missing taskId"))?;
    let task = Task::load(&hub.store, id).ok_or_else(|| anyhow!("unknown task"))?;
    set_allowed(&hub.store, &task.project)?;
    start(hub, b).await
}

/// After a task finishes: proof only for projects already allowed (never asks by itself
/// beyond the card).
pub fn after_finish(hub: &Arc<Hub>, task_id: &str) {
    let (h, id) = (hub.clone(), task_id.to_owned());
    tokio::spawn(async move {
        if let Err(e) = start(&h, &json!({"taskId": id})).await {
            tracing::warn!(task = id, error = format!("{e:#}"), "proof after finish failed");
        }
    });
}

fn launch(hub: &Arc<Hub>, id: &str, p: Plan) -> Result<()> {
    save(hub, id, &json!({"status": "running", "plan": p.public(), "at": now()}))?;
    let (h, id) = (hub.clone(), id.to_owned());
    tokio::spawn(async move {
        let started = Instant::now();
        let res = match tokio::time::timeout(TOTAL, produce(&h, &id, &p)).await {
            Ok(r) => r,
            Err(_) => Err(anyhow!("Proof took longer than {} minutes and was stopped.", TOTAL.as_secs() / 60)),
        };
        let mut proof = match res {
            Ok(v) => v,
            Err(e) => json!({"status": "failed", "note": clean_log(&format!("{e:#}"))}),
        };
        proof["plan"] = p.public();
        proof["at"] = json!(now());
        proof["seconds"] = json!(started.elapsed().as_secs());
        let _ = save(&h, &id, &proof);
        if let Some(task) = Task::load(&h.store, &id) {
            let text = if proof["status"] == "ready" {
                "Proof is ready: see how it looks."
            } else {
                "Couldn't make the proof."
            };
            h.add_entry(
                &Lane::main(&task.bot_id),
                EntryKind::Notice,
                h.store.max_turn(&task.bot_id),
                &json!({"text": text, "style": "info", "proof": true}),
            );
            h.emit_bot(&task.bot_id);
        }
    });
    Ok(())
}

async fn produce(hub: &Arc<Hub>, id: &str, p: &Plan) -> Result<Value> {
    let task = Task::load(&hub.store, id).ok_or_else(|| anyhow!("unknown task"))?;
    let work = work_dir(&task);
    ensure!(work.is_dir(), "The task's folder is gone.");
    let base = base_rev(&task, &work).ok_or_else(|| anyhow!("couldn't find where the task started"))?;
    let out = proof_dir(&work)?;
    let ch = {
        let (w, b) = (work.clone(), base.clone());
        tokio::task::spawn_blocking(move || changes(&w, &b)).await?
    };
    let mut log = String::new();
    let mut shots: Vec<Value> = vec![];
    let mut clip: Option<String> = None;
    let mut tests = Value::Null;
    let mut note = String::new();
    match (p.kind, p.command.as_deref()) {
        (Kind::Web, Some(cmd)) => {
            let pg = pages(&ch);
            let project = PathBuf::from(&task.project);
            let deadline = Instant::now() + INSTALL_BY;
            link_deps(&[&project], &work);
            ensure_deps(&work, deadline, &mut log).await;
            let after = capture_web(&work, cmd, &out, "after", &pg, true, &mut log).await?;
            let copy = BaseCopy::new(&work, &out, &base)?;
            link_deps(&[&work, &project], &copy.path);
            let before = capture_web(&copy.path, cmd, &out, "before", &pg, false, &mut log).await?;
            drop(copy);
            if !after && !before {
                note = "The pages couldn't be captured. See the log.".into();
            }
            for (i, page) in pg.iter().enumerate() {
                let (b, a) = (exists(&out, &format!("before-{i}.png")), exists(&out, &format!("after-{i}.png")));
                if a.is_some() || b.is_some() {
                    shots.push(shot(page, b.as_deref(), a.as_deref()));
                }
            }
            clip = exists(&out, "after-clip.webm");
        }
        (Kind::Android, Some(cmd)) if p.device => {
            shots = capture_device(&work, cmd, &out, &mut log).await;
            clip = exists(&out, "after-clip.mp4");
        }
        (Kind::Android, Some(cmd)) => {
            let since = std::time::SystemTime::now();
            let (ok, text) = run(&work, cmd, Duration::from_secs(4 * 60)).await;
            if !ok {
                let _ = writeln!(log, "Screenshot tests (after): {}", clean_log(&text));
            }
            let rels = new_pngs(&work, since);
            let copy = BaseCopy::new(&work, &out, &base)?;
            let (okb, textb) = run(&copy.path, cmd, Duration::from_secs(3 * 60)).await;
            if !okb {
                let _ = writeln!(log, "Screenshot tests (before): {}", clean_log(&textb));
            }
            let (w, c, o) = (work.clone(), copy.path.clone(), out.clone());
            shots = tokio::task::spawn_blocking(move || pair_shots(&w, &c, &rels, &o)).await?;
            drop(copy);
            if shots.is_empty() {
                note = "The screenshot tests didn't produce any pictures.".into();
            }
        }
        (_, cmd) => {
            if let Some(cmd) = cmd {
                let started = Instant::now();
                let (ok, text) = run(&work, cmd, Duration::from_secs(5 * 60)).await;
                let (mut passed, failed) = crate::qa::counts(&text);
                if ok && passed == 0 && failed == 0 {
                    passed = 1;
                }
                tests = json!({"ok": ok, "passed": passed, "failed": failed, "command": cmd,
                    "seconds": started.elapsed().as_secs(), "output": clean_log(&text)});
            } else {
                note = "No tests found to run, so here's what changed.".into();
            }
        }
    }
    let ready = !shots.is_empty() || !tests.is_null() || p.kind == Kind::Other;
    Ok(json!({
        "status": if ready { "ready" } else { "failed" },
        "kind": p.kind.as_str(),
        "shots": shots,
        "clip": clip,
        "tests": tests,
        "summary": summary(&ch),
        "files": ch.iter().take(20).map(|c| json!({"path": c.path, "added": c.added, "removed": c.removed})).collect::<Vec<_>>(),
        "note": note,
        "log": crate::agent::acp::truncate(&crate::triage::redact(&log).0, 3000),
    }))
}

/// One proof file for the phone (base64), only plain names inside the proof folder.
pub fn file(store: &Store, b: &Value) -> Result<Value> {
    use base64::Engine as _;
    let id = b["taskId"].as_str().ok_or_else(|| anyhow!("missing taskId"))?;
    let name = b["name"].as_str().unwrap_or_default();
    ensure!(safe_name(name), "not a proof file");
    let task = Task::load(store, id).ok_or_else(|| anyhow!("unknown task"))?;
    let path = work_dir(&task).join(".hypurr").join("proof").join(name);
    let bytes = std::fs::read(&path).map_err(|_| anyhow!("that proof file is gone"))?;
    ensure!(bytes.len() < 20 * 1024 * 1024, "that file is too big to send");
    Ok(json!({"name": name, "data": base64::engine::general_purpose::STANDARD.encode(bytes)}))
}

/// "Looks right" / "Not right, fix it": saved; "not right" goes to the agent as a new turn.
pub fn verdict(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let id = b["taskId"].as_str().ok_or_else(|| anyhow!("missing taskId"))?;
    let mut task = Task::load(&hub.store, id).ok_or_else(|| anyhow!("unknown task"))?;
    let ok = b["ok"].as_bool().unwrap_or(true);
    let text = b["text"].as_str().unwrap_or_default().trim().to_owned();
    if !ok {
        if crate::hub::BotStatus::Working == hub.runtime(&task.bot_id).status {
            bail!("The agent is busy. Try again when it's done.");
        }
        let shown = if text.is_empty() { "Not right, fix it".to_owned() } else { format!("Not right: {text}") };
        let proof = task.extra.get("proof").cloned().unwrap_or_default();
        let prompt = feedback_prompt(&text, &proof);
        if task.status == crate::tasks::TaskStatus::Finished {
            ensure!(work_dir(&task).is_dir(), "The task's folder is gone, so it can't be reopened.");
        }
        crate::tasks::send_first(hub, &task.bot_id, &shown, &prompt, &[])?;
        reopen(&mut task, &text, now());
    }
    if let Some(p) = task.extra.get_mut("proof") {
        p["verdict"] = json!({"ok": ok, "text": text, "at": now()});
    }
    task.save(&hub.store)?;
    hub.emit_bot(&task.bot_id);
    Ok(json!({"ok": true}))
}

/// "Not right" on a finished task opens it again, keeping the user's note.
pub fn reopen(task: &mut Task, text: &str, at: i64) -> bool {
    if task.status != crate::tasks::TaskStatus::Finished {
        return false;
    }
    task.status = crate::tasks::TaskStatus::Active;
    let note = if text.is_empty() { "Not right, fix it" } else { text };
    let list = task.extra.entry("reopened").or_insert_with(|| json!([]));
    if let Some(a) = list.as_array_mut() {
        a.push(json!({"at": at, "note": note}));
    }
    true
}

/// What the agent gets when the user says the result isn't right.
pub fn feedback_prompt(text: &str, proof: &Value) -> String {
    let mut s = String::from("I looked at the proof of your change and it's not right yet.\n");
    if !text.is_empty() {
        let _ = writeln!(s, "What's wrong: {text}");
    }
    let pages: Vec<&str> =
        proof["shots"].as_array().into_iter().flatten().filter_map(|v| v["label"].as_str()).collect();
    if !pages.is_empty() {
        let _ = writeln!(s, "Captured: {}.", pages.join(", "));
    }
    if proof["tests"]["failed"].as_u64().is_some_and(|f| f > 0) {
        let _ = writeln!(
            s,
            "{} test(s) failed:\n{}",
            proof["tests"]["failed"],
            proof["tests"]["output"].as_str().unwrap_or_default()
        );
    }
    s.push_str("Please fix it in this same safe copy, then tell me in plain words what you changed.");
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("hypurr-proof-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn detects_web_projects() {
        let d = tmp("web");
        std::fs::write(d.join("package.json"), r#"{"scripts":{"dev":"next dev"},"dependencies":{"next":"14"}}"#)
            .unwrap();
        assert_eq!(web_command(&d).as_deref(), Some("npm run dev"));
        std::fs::write(d.join("pnpm-lock.yaml"), "").unwrap();
        assert_eq!(web_command(&d).as_deref(), Some("pnpm dev"));
        let p = plan(&d, false);
        assert_eq!(p.kind, Kind::Web);
        // A library with no web framework is not a web app.
        std::fs::write(d.join("package.json"), r#"{"scripts":{"test":"jest"},"devDependencies":{"jest":"29"}}"#)
            .unwrap();
        assert!(web_command(&d).is_none());
        assert_eq!(plan(&d, false).kind, Kind::Other);
    }

    #[test]
    fn detects_android_projects() {
        let d = tmp("android");
        std::fs::write(d.join("settings.gradle.kts"), "").unwrap();
        std::fs::create_dir_all(d.join("app")).unwrap();
        std::fs::write(d.join("app/build.gradle.kts"), "plugins { id(\"com.android.application\"); id(\"io.github.takahirom.roborazzi\") }\napplicationId = \"com.example.app\"").unwrap();
        std::fs::write(d.join("gradlew"), "").unwrap();
        let p = plan(&d, false);
        assert_eq!((p.kind, p.command.as_deref()), (Kind::Android, Some("./gradlew recordRoborazziDebug")));
        let p = plan(&d, true);
        assert_eq!(p.command.as_deref(), Some("./gradlew installDebug"));
        assert!(p.device);
        assert_eq!(application_id(&d).as_deref(), Some("com.example.app"));
    }

    #[test]
    fn plain_projects_get_tests_or_a_summary() {
        let d = tmp("plain");
        assert_eq!(plan(&d, false).command, None);
        std::fs::write(d.join("Cargo.toml"), "[package]\nname=\"x\"").unwrap();
        let p = plan(&d, false);
        assert_eq!(p.kind, Kind::Other);
    }

    #[test]
    fn routes_from_changed_files() {
        assert_eq!(route_for("app/page.tsx").as_deref(), Some("/"));
        assert_eq!(route_for("src/app/pricing/page.tsx").as_deref(), Some("/pricing"));
        assert_eq!(route_for("app/(marketing)/about/page.tsx").as_deref(), Some("/about"));
        assert_eq!(route_for("pages/index.tsx").as_deref(), Some("/"));
        assert_eq!(route_for("pages/blog/index.js").as_deref(), Some("/blog"));
        assert_eq!(route_for("src/routes/settings/+page.svelte").as_deref(), Some("/settings"));
        assert_eq!(route_for("app/blog/[slug]/page.tsx"), None);
        assert_eq!(route_for("pages/api/hello.ts"), None);
        assert_eq!(route_for("app/layout.tsx"), None);
        let ch = |p: &str| Change { path: p.into(), added: 1, removed: 0 };
        assert_eq!(pages(&[ch("README.md")]), vec!["/".to_owned()]);
        assert_eq!(pages(&[ch("app/a/page.tsx"), ch("app/a/page.tsx"), ch("app/b/page.tsx")]), vec!["/a", "/b"]);
    }

    #[test]
    fn summary_in_plain_words() {
        assert_eq!(summary(&[]), "No files changed.");
        let s = summary(&[
            Change { path: "src/a.ts".into(), added: 10, removed: 2 },
            Change { path: "src/new.ts".into(), added: 5, removed: 0 },
        ]);
        assert!(s.starts_with("Changed 2 files (15 lines added, 2 removed)."), "{s}");
        assert!(s.contains("src/a.ts (+10 −2)"), "{s}");
        assert!(s.contains("1 of them look new"), "{s}");
    }

    #[test]
    fn only_plain_file_names_are_served() {
        assert!(safe_name("after-0.png"));
        assert!(safe_name("after-clip.webm"));
        assert!(!safe_name("../secret"));
        assert!(!safe_name("base/x.png"));
        assert!(!safe_name(".env"));
        assert!(!safe_name(""));
    }

    #[test]
    fn logs_are_redacted() {
        let log = "starting\nOPENAI_API_KEY=sk-abcdefghijklmnopqrstuvwxyz0123456789\nready on 3000";
        let c = clean_log(log);
        assert!(!c.contains("sk-abcdefghijklmnopqrstuvwxyz0123456789"), "{c}");
        assert!(c.contains("ready on 3000"));
    }

    #[test]
    fn pairs_changed_screenshots_first() {
        let (a, b, o) = (tmp("pa"), tmp("pb"), tmp("po"));
        for (d, x, y) in [(&a, "same", "new"), (&b, "same", "old")] {
            std::fs::create_dir_all(d.join("shots")).unwrap();
            std::fs::write(d.join("shots/home.png"), x).unwrap();
            std::fs::write(d.join("shots/settings_dark.png"), y).unwrap();
        }
        std::fs::write(a.join("shots/brand-new.png"), "n").unwrap();
        let rels = vec!["shots/brand-new.png".into(), "shots/home.png".into(), "shots/settings_dark.png".into()];
        let shots = pair_shots(&a, &b, &rels, &o);
        assert_eq!(shots.len(), 2);
        assert_eq!(shots[0]["before"], Value::Null);
        assert_eq!(shots[1]["label"], "settings dark");
        assert_eq!(std::fs::read_to_string(o.join("before-1.png")).unwrap(), "old");
        assert_eq!(std::fs::read_to_string(o.join("after-1.png")).unwrap(), "new");
    }

    #[test]
    fn install_follows_the_lockfile() {
        let d = tmp("install");
        assert_eq!(install_command(&d), None);
        std::fs::write(d.join("package.json"), "{}").unwrap();
        assert!(install_command(&d).unwrap().starts_with("npm install"));
        std::fs::write(d.join("package-lock.json"), "").unwrap();
        assert!(install_command(&d).unwrap().starts_with("npm ci"));
        std::fs::write(d.join("yarn.lock"), "").unwrap();
        assert!(install_command(&d).unwrap().starts_with("yarn install"));
        std::fs::write(d.join("pnpm-lock.yaml"), "").unwrap();
        assert!(install_command(&d).unwrap().starts_with("pnpm install"));
        assert!(install_command(&d).unwrap().contains("--ignore-scripts"));
    }

    #[test]
    fn not_right_reopens_a_finished_task() {
        let mut t: Task = serde_json::from_value(json!({"id":"t","botId":"b","goal":"g","title":"t","project":"/p",
            "projectName":"p","backend":"x","status":"finished","createdAt":0,"updatedAt":0}))
        .unwrap();
        assert!(reopen(&mut t, "button is cut off", 5));
        assert_eq!(t.status, crate::tasks::TaskStatus::Active);
        assert_eq!(t.extra["reopened"][0]["note"], "button is cut off");
        assert!(!reopen(&mut t, "again", 6));
    }

    #[test]
    fn feedback_carries_what_failed() {
        let p = json!({"shots": [{"label": "/pricing"}], "tests": {"failed": 2, "output": "boom"}});
        let s = feedback_prompt("the button is cut off", &p);
        assert!(
            s.contains("the button is cut off")
                && s.contains("/pricing")
                && s.contains("2 test(s) failed")
                && s.contains("boom")
        );
    }

    #[test]
    fn diff_includes_new_files_and_skips_proof_folder() {
        let d = tmp("git");
        let g = |a: &[&str]| {
            assert!(std::process::Command::new("git").args(a).current_dir(&d).output().unwrap().status.success());
        };
        g(&["init", "-q"]);
        g(&["-c", "user.email=a@b", "-c", "user.name=a", "commit", "-q", "--allow-empty", "-m", "base"]);
        std::fs::write(d.join("a.txt"), "1\n2\n").unwrap();
        std::fs::create_dir_all(d.join(".hypurr/proof")).unwrap();
        std::fs::write(d.join(".hypurr/proof/after-0.png"), "x").unwrap();
        let ch = changes(&d, "HEAD");
        assert_eq!(ch, vec![Change { path: "a.txt".into(), added: 2, removed: 0 }]);
    }
}
