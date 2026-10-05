//! The safety net's git side: every task gets its own branch in its own worktree,
//! checkpoints are commits on that branch, and rollback resets the worktree to one
//! (after saving the current state as a checkpoint, so a rollback can be undone too).
//! Nothing here ever writes to the user's own checkout or to a protected branch.
//! Blocking: call from `spawn_blocking`.

use anyhow::{Context as _, Result, anyhow, bail};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::process::Command;

/// Default protected branches (`*` is a prefix match).
pub const DEFAULT_PROTECTED: &[&str] = &["main", "master", "production", "prod", "release/*", "develop"];

/// One saved point on the task branch.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Checkpoint {
    /// Commit hash.
    pub id: String,
    pub label: String,
    pub at: i64,
    /// Files changed since the previous checkpoint.
    #[serde(default)]
    pub files: Vec<String>,
}

fn git(dir: &Path, args: &[&str]) -> Result<String> {
    let out = Command::new("git")
        .arg("-C")
        .arg(dir)
        .args(["-c", "user.name=Hypurr", "-c", "user.email=hypurr@localhost", "-c", "commit.gpgsign=false"])
        .args(args)
        .env("GIT_TERMINAL_PROMPT", "0")
        .output()
        .with_context(|| format!("running git {}", args.first().unwrap_or(&"")))?;
    if !out.status.success() {
        bail!("git {} failed: {}", args.join(" "), String::from_utf8_lossy(&out.stderr).trim());
    }
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_owned())
}

/// The repository root containing `dir`, if it's a git checkout with at least one commit.
pub fn repo_root(dir: &Path) -> Option<PathBuf> {
    let root = git(dir, &["rev-parse", "--show-toplevel"]).ok()?;
    git(dir, &["rev-parse", "--verify", "HEAD"]).ok()?;
    Some(PathBuf::from(root))
}

pub fn current_branch(dir: &Path) -> Option<String> {
    git(dir, &["symbolic-ref", "--short", "-q", "HEAD"]).ok().filter(|b| !b.is_empty())
}

pub fn is_protected(branch: &str, protected: &[String]) -> bool {
    protected.iter().any(|p| match p.strip_suffix('*') {
        Some(prefix) => branch.starts_with(prefix),
        None => branch == p,
    })
}

/// `fix the login bug!` → `fix-the-login-bug`.
pub fn slug(text: &str) -> String {
    let mut s = String::new();
    for c in text.chars().flat_map(char::to_lowercase) {
        if c.is_ascii_alphanumeric() {
            s.push(c);
        } else if !s.ends_with('-') && !s.is_empty() {
            s.push('-');
        }
        if s.len() >= 32 {
            break;
        }
    }
    let s = s.trim_matches('-').to_owned();
    if s.is_empty() { "task".into() } else { s }
}

/// A new worktree for a task: branch `hypurr/<slug>-<id8>` from the repo's HEAD.
pub struct Isolated {
    pub branch: String,
    pub base: String,
    pub worktree: PathBuf,
    pub start: Checkpoint,
}

pub fn isolate(repo: &Path, worktrees: &Path, task_id: &str, title: &str, protected: &[String]) -> Result<Isolated> {
    let root = repo_root(repo).ok_or_else(|| anyhow!("not a git project with commits"))?;
    let short: String = task_id.chars().filter(char::is_ascii_alphanumeric).take(8).collect();
    let branch = format!("hypurr/{}-{short}", slug(title));
    if is_protected(&branch, protected) {
        bail!("the task branch name {branch} is protected");
    }
    let base = current_branch(&root).unwrap_or_else(|| "HEAD".into());
    let base_sha = git(&root, &["rev-parse", "HEAD"])?;
    std::fs::create_dir_all(worktrees).context("creating the worktrees folder")?;
    let worktree = worktrees.join(task_id);
    let wt = worktree.to_string_lossy().into_owned();
    git(&root, &["worktree", "add", "-b", &branch, &wt, &base_sha])?;
    let start =
        Checkpoint { id: base_sha, label: format!("Start (from {base})"), at: crate::store::now_ms(), files: vec![] };
    Ok(Isolated { branch, base, worktree, start })
}

fn changed_files(worktree: &Path) -> Result<Vec<String>> {
    let out = git(worktree, &["status", "--porcelain", "--untracked-files=all"])?;
    Ok(out.lines().filter_map(|l| l.get(3..)).map(|p| p.rsplit(" -> ").next().unwrap_or(p).to_owned()).collect())
}

/// Commits everything in the worktree as a checkpoint. `None` when nothing changed.
/// Refuses when the worktree isn't on the task branch (the agent switched branches).
pub fn checkpoint(worktree: &Path, branch: &str, label: &str) -> Result<Option<Checkpoint>> {
    let on = current_branch(worktree);
    if on.as_deref() != Some(branch) {
        bail!(
            "the task folder is on {} instead of {branch}; Hypurr only saves checkpoints on the task branch",
            on.as_deref().unwrap_or("a detached commit")
        );
    }
    let files = changed_files(worktree)?;
    if files.is_empty() {
        return Ok(None);
    }
    git(worktree, &["add", "-A"])?;
    git(worktree, &["commit", "--no-verify", "-q", "-m", &format!("hypurr checkpoint: {label}")])?;
    let id = git(worktree, &["rev-parse", "HEAD"])?;
    Ok(Some(Checkpoint { id, label: label.to_owned(), at: crate::store::now_ms(), files }))
}

/// Puts the worktree back to `target` (a checkpoint of this task). History only grows:
/// the current state is saved first, then the target's files are restored as a new
/// commit, so every checkpoint (including "Before going back") stays reachable.
/// Returns the checkpoints it added.
pub fn rollback(worktree: &Path, branch: &str, target: &str, label: &str) -> Result<Vec<Checkpoint>> {
    if target.len() < 7 || !target.chars().all(|c| c.is_ascii_hexdigit()) {
        bail!("invalid checkpoint");
    }
    let mut added: Vec<Checkpoint> = checkpoint(worktree, branch, "Before going back")?.into_iter().collect();
    git(worktree, &["merge-base", "--is-ancestor", target, "HEAD"])
        .context("that checkpoint isn't on this task's branch")?;
    git(worktree, &["read-tree", "-u", "--reset", target])?;
    if let Some(cp) = checkpoint(worktree, branch, &format!("Went back to: {label}"))? {
        added.push(cp);
    }
    Ok(added)
}

/// Removes the worktree folder; the branch (and so every checkpoint) stays.
pub fn remove(repo: &Path, worktree: &Path) -> Result<()> {
    let wt = worktree.to_string_lossy().into_owned();
    if git(repo, &["worktree", "remove", "--force", &wt]).is_err() && worktree.exists() {
        std::fs::remove_dir_all(worktree).context("removing the task folder")?;
        let _ = git(repo, &["worktree", "prune"]);
    }
    Ok(())
}

#[cfg(test)]
pub mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    pub fn repo() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("hypurr-safety-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        git(&dir, &["init", "-q", "-b", "main"]).unwrap();
        std::fs::write(dir.join("a.txt"), "one\n").unwrap();
        git(&dir, &["add", "."]).unwrap();
        git(&dir, &["commit", "-q", "-m", "init"]).unwrap();
        dir
    }

    fn protected() -> Vec<String> {
        DEFAULT_PROTECTED.iter().map(|s| (*s).to_owned()).collect()
    }

    #[test]
    fn slugs_are_branch_safe() {
        assert_eq!(slug("Fix the login bug in ticket #142!"), "fix-the-login-bug-in-ticket-142");
        assert_eq!(slug("   "), "task");
        assert_eq!(slug("ÜBER café"), "ber-caf");
    }

    #[test]
    fn protected_patterns() {
        let p = protected();
        assert!(is_protected("main", &p));
        assert!(is_protected("release/2.4", &p));
        assert!(!is_protected("hypurr/main-fix", &p));
    }

    #[test]
    fn task_runs_on_its_own_branch_and_rolls_back() {
        let repo = repo();
        let wts = repo.join(".wt-test");
        let iso = isolate(&repo, &wts, "abcdef12-3456", "Fix login", &protected()).unwrap();
        assert_eq!(iso.branch, "hypurr/fix-login-abcdef12");
        assert_eq!(iso.base, "main");
        assert_eq!(current_branch(&iso.worktree).as_deref(), Some(iso.branch.as_str()));
        // The user's checkout stays on main and untouched.
        assert_eq!(current_branch(&repo).as_deref(), Some("main"));

        assert!(checkpoint(&iso.worktree, &iso.branch, "nothing").unwrap().is_none());
        std::fs::write(iso.worktree.join("a.txt"), "two\n").unwrap();
        std::fs::write(iso.worktree.join("new.txt"), "x").unwrap();
        let cp1 = checkpoint(&iso.worktree, &iso.branch, "Step 1").unwrap().unwrap();
        assert_eq!(cp1.files.len(), 2);
        std::fs::write(iso.worktree.join("a.txt"), "three\n").unwrap();

        let added = rollback(&iso.worktree, &iso.branch, &iso.start.id, "Start").unwrap();
        assert_eq!(added.len(), 2);
        assert_eq!(added[0].label, "Before going back");
        assert_eq!(std::fs::read_to_string(iso.worktree.join("a.txt")).unwrap(), "one\n");
        assert!(!iso.worktree.join("new.txt").exists());
        // Undo the rollback: "Before going back" is still a checkpoint on the branch.
        rollback(&iso.worktree, &iso.branch, &added[0].id, "Before going back").unwrap();
        assert_eq!(std::fs::read_to_string(iso.worktree.join("a.txt")).unwrap(), "three\n");
        assert!(iso.worktree.join("new.txt").exists());
        // The user's own checkout never changed.
        assert_eq!(std::fs::read_to_string(repo.join("a.txt")).unwrap(), "one\n");
        assert!(rollback(&iso.worktree, &iso.branch, "nothex!", "x").is_err());

        remove(&repo, &iso.worktree).unwrap();
        assert!(!iso.worktree.exists());
        assert!(git(&repo, &["rev-parse", "--verify", &iso.branch]).is_ok(), "branch kept");
        std::fs::remove_dir_all(&repo).unwrap();
    }

    #[test]
    fn checkpoints_refuse_another_branch() {
        let repo = repo();
        let iso = isolate(&repo, &repo.join(".wt"), "t2", "x", &protected()).unwrap();
        git(&iso.worktree, &["checkout", "-q", "-b", "other"]).unwrap();
        std::fs::write(iso.worktree.join("a.txt"), "z").unwrap();
        assert!(checkpoint(&iso.worktree, &iso.branch, "x").is_err());
        std::fs::remove_dir_all(&repo).unwrap();
    }

    #[test]
    fn not_a_repo_is_refused() {
        let dir = std::env::temp_dir().join(format!("hypurr-norepo-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        assert!(repo_root(&dir).is_none());
        assert!(isolate(&dir, &dir.join("wt"), "t", "x", &protected()).is_err());
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
