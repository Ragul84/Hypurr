//! A standard folder layout for a messy (often AI-built) frontend, as a plan first.
//!
//! The plan only lists moves. Applying it is a separate, approved step: Hypurr makes a new
//! branch in its own copy of the repo, moves the files with `git mv`, fixes every relative
//! import, commits, and runs the project's tests. Only when they pass is the branch offered
//! as a pull request (which is still a draft until the user taps to open it).

use super::scan::PageMap;
use crate::trace::locate::{self, JS_EXT, Repo};
use anyhow::{Context as _, Result, anyhow, ensure};
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap};
use std::fmt::Write as _;
use std::path::Path;
use std::process::Command;
use std::sync::LazyLock;
use std::time::Duration;

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Move {
    pub from: String,
    pub to: String,
    pub why: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Plan {
    /// The frontend source folder (`client/src`).
    pub root: String,
    pub moves: Vec<Move>,
    /// Things worth doing by hand (not moved automatically).
    pub notes: Vec<String>,
    pub test_command: String,
}

const ENTRY: &[&str] =
    &["App", "main", "index", "router", "routes", "setupTests", "vite-env.d", "reportWebVitals", "serviceWorker"];

static HOOK: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"export\s+(?:default\s+)?function\s+use[A-Z]").expect("hook re"));

fn has_jsx(src: &str) -> bool {
    src.contains("</") || src.contains("/>")
}

fn stem(f: &str) -> &str {
    let name = f.rsplit('/').next().unwrap_or(f);
    name.split_once('.').map_or(name, |(s, _)| s)
}

fn name(f: &str) -> &str {
    f.rsplit('/').next().unwrap_or(f)
}

/// The folder the router lives in (where pages should sit under `pages/`).
fn frontend_root(map: &PageMap, repo: &Repo) -> Option<String> {
    let router_file = repo.files.iter().find(|f| {
        JS_EXT.contains(&locate::ext(f)) && {
            let s = repo.read(f);
            s.contains("<Route") || s.contains("createBrowserRouter")
        }
    })?;
    let _ = map;
    Some(router_file.rsplit_once('/').map_or_else(String::new, |(d, _)| d.to_owned()))
}

/// The plan: pages → `pages/`, shared components → `components/`, API helpers → `api/`,
/// hooks → `hooks/`. File-routed apps (Next.js) keep their pages where they are.
pub fn plan(repo: &Repo, map: &PageMap) -> Plan {
    let test_command = crate::ticket::test_command(&repo.root);
    let mut p = Plan { test_command, ..Plan::default() };
    if map.router == "Next.js" {
        p.notes.push("Next.js picks pages from their folders, so Hypurr won't move them.".into());
        return p;
    }
    let Some(root) = frontend_root(map, repo) else {
        p.notes.push("Hypurr couldn't find the app's router, so it has no layout to suggest.".into());
        return p;
    };
    let sites = locate::call_sites(repo);
    let pages: Vec<&str> = map.pages.iter().map(|x| x.file.as_str()).collect();
    let prefix = if root.is_empty() { String::new() } else { format!("{root}/") };
    let mut targets: HashMap<String, String> = HashMap::new();
    for f in repo.files.iter().filter(|f| f.starts_with(&prefix) && JS_EXT.contains(&locate::ext(f))) {
        let rel = &f[prefix.len()..];
        if f.contains(".test.")
            || f.contains(".spec.")
            || rel.contains("__tests__")
            || ENTRY.contains(&stem(f)) && !rel.contains('/')
        {
            continue;
        }
        let src = repo.read(f);
        let calls = sites.iter().filter(|s| s.file == *f).count();
        let (dir, why) = if pages.contains(&f.as_str()) {
            ("pages", "it's a page the router opens")
        } else if HOOK.is_match(&src) {
            ("hooks", "it's a React hook")
        } else if has_jsx(&src) {
            ("components", "it's a component used by pages")
        } else if calls > 0 {
            ("api", "it holds the API calls")
        } else {
            continue;
        };
        let to = format!("{prefix}{dir}/{}", name(f));
        if to == *f || rel.starts_with(&format!("{dir}/")) {
            continue;
        }
        if repo.files.contains(&to) || targets.values().any(|t| *t == to) {
            p.notes.push(format!("{f} would clash with {to}, so it stays."));
            continue;
        }
        targets.insert(f.clone(), to.clone());
        p.moves.push(Move { from: f.clone(), to, why: why.into() });
        if dir != "api" && calls > 0 {
            p.notes.push(format!(
                "{} makes API calls itself; moving them into {prefix}api/ would make it easier to find.",
                name(f)
            ));
        }
    }
    p.moves.sort_by(|a, b| a.from.cmp(&b.from));
    p.root = root;
    p
}

static SPEC: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?:\bfrom\s*|\bimport\s*\(\s*|\brequire\s*\(\s*|\bimport\s+)(['"])(\.{1,2}/[^'"]*)['"]"#)
        .expect("spec re")
});

/// `from` dir → `to` file, as an import specifier (`../api`).
pub fn relative(from_dir: &str, to: &str) -> String {
    let a: Vec<&str> = from_dir.split('/').filter(|s| !s.is_empty()).collect();
    let b: Vec<&str> = to.split('/').filter(|s| !s.is_empty()).collect();
    let common = a.iter().zip(&b).take_while(|(x, y)| x == y).count();
    let mut parts: Vec<&str> = vec![".."; a.len() - common];
    parts.extend(&b[common..]);
    let s = parts.join("/");
    if s.starts_with("..") { s } else { format!("./{s}") }
}

fn dir_of(f: &str) -> &str {
    f.rsplit_once('/').map_or("", |(d, _)| d)
}

/// New contents for every JS file whose relative imports change because of the moves.
pub fn rewrite_imports(repo: &Repo, moves: &[Move]) -> BTreeMap<String, String> {
    let moved_to: HashMap<&str, &str> = moves.iter().map(|m| (m.from.as_str(), m.to.as_str())).collect();
    let new_of = |f: &str| moved_to.get(f).map_or_else(|| f.to_owned(), |t| (*t).to_owned());
    let mut out = BTreeMap::new();
    for f in repo.files.iter().filter(|f| JS_EXT.contains(&locate::ext(f))) {
        let src = repo.read(f);
        let mut changed = false;
        let new_src = SPEC.replace_all(&src, |c: &regex::Captures| {
            let whole = c[0].to_owned();
            let spec = &c[2];
            let Some(target) = repo.resolve_import(f, spec) else { return whole };
            if !moved_to.contains_key(f.as_str()) && !moved_to.contains_key(target.as_str()) {
                return whole;
            }
            let new_target = new_of(&target);
            let via_index =
                target.rsplit('/').next().is_some_and(|n| n.starts_with("index.")) && !spec.ends_with(name(&target));
            let mut t = if via_index { dir_of(&new_target).to_owned() } else { new_target.clone() };
            if !via_index && !spec.ends_with(&format!(".{}", locate::ext(&target))) {
                t = t.rsplit_once('.').map_or(t.clone(), |(s, _)| s.to_owned());
            }
            let new_spec = relative(dir_of(&new_of(f)), &t);
            if new_spec == spec {
                return whole;
            }
            changed = true;
            whole.replacen(spec, &new_spec, 1)
        });
        if changed {
            out.insert(f.clone(), new_src.into_owned());
        }
    }
    out
}

fn git(dir: &Path, args: &[&str]) -> Result<String> {
    let o = Command::new("git").args(args).current_dir(dir).output().context("git isn't available")?;
    ensure!(o.status.success(), "git {}: {}", args.first().unwrap_or(&""), String::from_utf8_lossy(&o.stderr).trim());
    Ok(String::from_utf8_lossy(&o.stdout).into_owned())
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Applied {
    /// `ready` (tests passed), `testsFailed`, `noTests` or `failed`.
    pub status: String,
    pub branch: String,
    pub base: String,
    pub moved: usize,
    pub rewritten: usize,
    pub test_command: String,
    pub test_output: String,
    pub pr_title: String,
    pub pr_body: String,
    pub error: Option<String>,
}

fn run_tests(dir: &Path, cmd: &str, limit: Duration) -> (bool, String) {
    let mut c = if cfg!(windows) {
        let mut c = Command::new("cmd");
        c.args(["/C", cmd]);
        c
    } else {
        let mut c = Command::new("sh");
        c.args(["-c", cmd]);
        c
    };
    c.current_dir(dir).stdin(std::process::Stdio::null()).env("CI", "1");
    let Ok(mut child) = c.stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::piped()).spawn() else {
        return (false, format!("Couldn't run {cmd}"));
    };
    let start = std::time::Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) if start.elapsed() > limit => {
                let _ = child.kill();
                let _ = child.wait();
                return (false, format!("The tests took longer than {} minutes.", limit.as_secs() / 60));
            }
            Ok(None) => std::thread::sleep(Duration::from_millis(200)),
            Err(e) => return (false, e.to_string()),
        }
    }
    match child.wait_with_output() {
        Ok(o) => {
            let text = format!("{}{}", String::from_utf8_lossy(&o.stdout), String::from_utf8_lossy(&o.stderr));
            (o.status.success(), crate::proof::clean_log(&text))
        }
        Err(e) => (false, e.to_string()),
    }
}

/// Link the project's installed packages into the copy so its tests can run.
fn link_deps(project: &Path, copy: &Path) -> Vec<std::path::PathBuf> {
    let mut made = Vec::new();
    let mut dirs = vec![std::path::PathBuf::new()];
    if let Ok(rd) = std::fs::read_dir(project) {
        dirs.extend(rd.flatten().filter(|e| e.path().join("node_modules").is_dir()).map(|e| e.file_name().into()));
    }
    for d in dirs {
        let src = project.join(&d).join("node_modules");
        let dst = copy.join(&d).join("node_modules");
        if !src.is_dir() || dst.exists() || !copy.join(&d).is_dir() {
            continue;
        }
        #[cfg(unix)]
        let ok = std::os::unix::fs::symlink(&src, &dst).is_ok();
        #[cfg(windows)]
        let ok = Command::new("cmd")
            .args(["/C", "mklink", "/J"])
            .arg(&dst)
            .arg(&src)
            .output()
            .is_ok_and(|o| o.status.success());
        if ok {
            made.push(dst);
        }
    }
    made
}

fn remove_link(p: &Path) {
    #[cfg(windows)]
    let _ = std::fs::remove_dir(p);
    #[cfg(not(windows))]
    let _ = std::fs::remove_file(p);
}

/// Apply the plan on a new branch (never the user's checkout) and run the tests.
pub fn apply(project: &Path, plan: &Plan, branch: &str, test_limit: Duration) -> Result<Applied> {
    ensure!(!plan.moves.is_empty(), "There's nothing to move.");
    let base = git(project, &["rev-parse", "--abbrev-ref", "HEAD"])?.trim().to_owned();
    let work_root = project.join(".hypurr").join("restructure");
    std::fs::create_dir_all(&work_root)?;
    std::fs::write(work_root.join(".gitignore"), "*\n")?;
    let copy = work_root.join("copy");
    if copy.exists() {
        let _ = git(project, &["worktree", "remove", "--force", &copy.to_string_lossy()]);
        let _ = std::fs::remove_dir_all(&copy);
        let _ = git(project, &["worktree", "prune"]);
    }
    git(project, &["worktree", "add", "-b", branch, &copy.to_string_lossy(), "HEAD"])?;
    let result = (|| -> Result<Applied> {
        let repo = Repo::scan(&copy);
        for m in &plan.moves {
            ensure!(repo.files.contains(&m.from), "{} isn't in the last commit", m.from);
        }
        let rewrites = rewrite_imports(&repo, &plan.moves);
        for (f, body) in &rewrites {
            std::fs::write(copy.join(f), body)?;
        }
        for m in &plan.moves {
            if let Some(parent) = copy.join(&m.to).parent() {
                std::fs::create_dir_all(parent)?;
            }
            git(&copy, &["mv", &m.from, &m.to])?;
        }
        git(&copy, &["add", "-A"])?;
        let mut commit = vec!["-c", "user.name=Hypurr", "-c", "user.email=hypurr@localhost"];
        if git(&copy, &["config", "user.email"]).is_ok_and(|s| !s.trim().is_empty()) {
            commit.clear();
        }
        commit.extend(["commit", "-q", "-m", "Restructure folders into a standard layout"]);
        git(&copy, &commit)?;
        let mut a = Applied {
            branch: branch.to_owned(),
            base: base.clone(),
            moved: plan.moves.len(),
            rewritten: rewrites.len(),
            test_command: plan.test_command.clone(),
            pr_title: "Restructure folders into a standard layout".into(),
            ..Applied::default()
        };
        a.pr_body = pr_body(plan, rewrites.len());
        if plan.test_command.is_empty() {
            a.status = "noTests".into();
            return Ok(a);
        }
        let links = link_deps(project, &copy);
        let (ok, out) = run_tests(&copy, &plan.test_command, test_limit);
        for l in &links {
            remove_link(l);
        }
        a.test_output = out;
        a.status = if ok { "ready" } else { "testsFailed" }.into();
        Ok(a)
    })();
    let _ = git(project, &["worktree", "remove", "--force", &copy.to_string_lossy()]);
    let _ = git(project, &["worktree", "prune"]);
    result.map_err(|e| {
        let _ = git(project, &["branch", "-D", branch]);
        anyhow!("{e:#}")
    })
}

fn pr_body(plan: &Plan, rewritten: usize) -> String {
    let mut s = String::from("Moves files into a standard layout. No code changes beyond fixing import paths.\n\n");
    for m in &plan.moves {
        let _ = writeln!(s, "- `{}` → `{}` ({})", m.from, m.to, m.why);
    }
    let _ = writeln!(s, "\n{rewritten} files had their imports updated. Tests: `{}`.", plan.test_command);
    s
}

/// Delete a restructure branch that wasn't wanted.
pub fn discard(project: &Path, branch: &str) -> Result<()> {
    ensure!(branch.starts_with("hypurr/restructure-"), "That isn't a Hypurr restructure branch.");
    git(project, &["branch", "-D", branch]).map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::trace::locate::tests::fixture;

    #[test]
    fn plans_the_fixture() {
        let repo = Repo::scan(&fixture());
        let map = super::super::scan::build(&repo);
        let p = plan(&repo, &map);
        assert_eq!(p.root, "client/src");
        assert_eq!(p.test_command, "npm test");
        let moves: Vec<(&str, &str)> = p.moves.iter().map(|m| (m.from.as_str(), m.to.as_str())).collect();
        assert_eq!(
            moves,
            vec![
                ("client/src/Home.jsx", "client/src/pages/Home.jsx"),
                ("client/src/OrderDetail.jsx", "client/src/pages/OrderDetail.jsx"),
                ("client/src/api.js", "client/src/api/api.js"),
            ]
        );
        assert!(p.notes.iter().any(|n| n.starts_with("OrderDetail.jsx makes API calls")));
    }

    #[test]
    fn relative_paths() {
        assert_eq!(relative("client/src/pages", "client/src/api/api"), "../api/api");
        assert_eq!(relative("client/src", "client/src/pages/Home"), "./pages/Home");
        assert_eq!(relative("client/src/pages", "client/src/pages/X"), "./X");
    }

    #[test]
    fn rewrites_imports() {
        let repo = Repo::scan(&fixture());
        let map = super::super::scan::build(&repo);
        let p = plan(&repo, &map);
        let r = rewrite_imports(&repo, &p.moves);
        assert!(r["client/src/App.jsx"].contains("import Home from './pages/Home';"));
        assert!(r["client/src/App.jsx"].contains("import OrderDetail from './pages/OrderDetail';"));
        assert!(r["client/src/pages/Orders.jsx"].contains("from '../api/api';"));
        assert!(r["client/src/Home.jsx"].contains("from '../api/api';"));
        assert!(!r.contains_key("client/src/components/OrderRow.jsx"));
    }

    fn copy_fixture() -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("hypurr-restructure-{}", uuid::Uuid::new_v4()));
        let src = fixture();
        for f in Repo::scan(&src).files.iter().chain(std::iter::once(&"package.json".to_owned())) {
            let to = dir.join(f);
            std::fs::create_dir_all(to.parent().expect("parent")).expect("mkdir");
            std::fs::copy(src.join(f), to).expect("copy");
        }
        for a in [
            &["init", "-q", "-b", "main"][..],
            &["add", "-A"],
            &["-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init"],
        ] {
            git(&dir, a).expect("git");
        }
        dir
    }

    #[test]
    fn applies_on_a_branch_and_runs_tests() {
        let dir = copy_fixture();
        let repo = Repo::scan(&dir);
        let mut p = plan(&repo, &super::super::scan::build(&repo));
        // Stand-in for the project's tests: every moved file must exist and App must import it.
        p.test_command = "test -f client/src/pages/Home.jsx && grep -q \"./pages/Home'\" client/src/App.jsx".into();
        let a = apply(&dir, &p, "hypurr/restructure-test", Duration::from_secs(60)).expect("apply");
        assert_eq!(a.status, "ready", "{}", a.test_output);
        assert_eq!((a.moved, a.rewritten, a.base.as_str()), (3, 3, "main"));
        // The user's checkout is untouched; the branch has the moves.
        assert!(dir.join("client/src/Home.jsx").is_file());
        assert_eq!(git(&dir, &["rev-parse", "--abbrev-ref", "HEAD"]).expect("head").trim(), "main");
        let files = git(&dir, &["ls-tree", "-r", "--name-only", "hypurr/restructure-test"]).expect("tree");
        assert!(files.contains("client/src/pages/Home.jsx") && !files.contains("client/src/Home.jsx"));
        assert!(!dir.join(".hypurr/restructure/copy").exists());

        p.test_command = "exit 3".into();
        let b = apply(&dir, &p, "hypurr/restructure-test2", Duration::from_secs(60)).expect("apply");
        assert_eq!(b.status, "testsFailed");
        discard(&dir, "hypurr/restructure-test2").expect("discard");
        assert!(discard(&dir, "main").is_err());
        let _ = std::fs::remove_dir_all(dir);
    }

    /// Restructure a real checkout: `HYPURR_LIVE_REPO`, `HYPURR_LIVE_TESTCMD` (e.g. a production build).
    #[test]
    #[ignore = "needs a real project with its packages installed"]
    fn live_restructure() {
        let (Ok(repo_dir), Ok(cmd)) = (std::env::var("HYPURR_LIVE_REPO"), std::env::var("HYPURR_LIVE_TESTCMD")) else {
            return;
        };
        let dir = std::path::PathBuf::from(repo_dir);
        let repo = Repo::scan(&dir);
        let mut p = plan(&repo, &super::super::scan::build(&repo));
        p.test_command = cmd;
        let a = apply(&dir, &p, "hypurr/restructure-live", Duration::from_secs(300)).expect("apply");
        println!("{a:#?}");
        assert_eq!(a.status, "ready", "{}", a.test_output);
    }
}
