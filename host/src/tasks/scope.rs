//! The project folder a task's agent may work in, enforced on the host.
//!
//! A path is inside the folder only if it still is after `~`, `.`/`..` and every
//! symlink along the way are resolved against the real filesystem. Paths that don't
//! exist yet (a file the agent is about to create) are resolved through their deepest
//! existing parent. Anything that can't be resolved (a symlink loop, a broken link,
//! a relative path with no folder to anchor it) counts as outside: the check fails closed.

use std::path::{Component, Path, PathBuf};

/// Max symlinks followed for one path before giving up (counts as outside).
const MAX_LINKS: usize = 40;

/// `~` and `~/x` → the home folder.
fn expand_home(p: &str) -> PathBuf {
    if let Some(rest) = p.strip_prefix("~/") {
        dirs::home_dir().unwrap_or_default().join(rest)
    } else if p == "~" {
        dirs::home_dir().unwrap_or_default()
    } else {
        PathBuf::from(p)
    }
}

/// Resolves `p` (relative to `base`) to a real absolute path, following symlinks
/// component by component, so `link/../x` means what the filesystem means.
pub fn real_path(base: &Path, p: &str) -> Option<PathBuf> {
    let expanded = expand_home(p);
    let start = if expanded.is_absolute() {
        expanded
    } else if base.is_absolute() {
        base.join(expanded)
    } else {
        return None;
    };
    let mut pending: Vec<std::ffi::OsString> = Vec::new();
    // Reverse stack of components still to walk.
    for c in start.components().rev() {
        match c {
            Component::Normal(n) => pending.push(n.to_owned()),
            Component::ParentDir => pending.push("..".into()),
            Component::CurDir | Component::RootDir | Component::Prefix(_) => {}
        }
    }
    let mut cur = PathBuf::from("/");
    let mut links = 0usize;
    while let Some(part) = pending.pop() {
        if part == ".." {
            cur.pop();
            continue;
        }
        let next = cur.join(&part);
        match std::fs::symlink_metadata(&next) {
            Ok(meta) if meta.file_type().is_symlink() => {
                links += 1;
                if links > MAX_LINKS {
                    return None;
                }
                let target = std::fs::read_link(&next).ok()?;
                // Walk the link's target in place of this component.
                if target.is_absolute() {
                    cur = PathBuf::from("/");
                }
                for c in target.components().rev() {
                    match c {
                        Component::Normal(n) => pending.push(n.to_owned()),
                        Component::ParentDir => pending.push("..".into()),
                        Component::CurDir | Component::RootDir | Component::Prefix(_) => {}
                    }
                }
            }
            // Exists (or doesn't yet): keep walking lexically from here.
            _ => cur = next,
        }
    }
    Some(cur)
}

/// The folder a task may work in.
#[derive(Clone, Debug)]
pub struct Scope {
    root: Option<PathBuf>,
}

impl Scope {
    /// `root` empty = no folder chosen (nothing is treated as outside).
    pub fn new(root: &str) -> Self {
        let root = root.trim();
        if root.is_empty() {
            return Self { root: None };
        }
        let resolved = real_path(Path::new("/"), root).unwrap_or_else(|| PathBuf::from(root));
        Self { root: Some(resolved) }
    }

    /// Whether `p` (relative paths are relative to the folder) is inside the folder.
    pub fn contains(&self, p: &str) -> bool {
        let Some(root) = &self.root else { return true };
        if p.trim().is_empty() {
            return true;
        }
        real_path(root, p).is_some_and(|real| real.starts_with(root))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TempDir(PathBuf);
    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn tmp() -> (TempDir, PathBuf) {
        let dir = std::env::temp_dir().join(format!("hypurr-scope-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let real = real_path(Path::new("/"), dir.to_str().unwrap()).unwrap();
        let dir = TempDir(dir);
        let project = real.join("project");
        std::fs::create_dir_all(project.join("src")).unwrap();
        std::fs::write(project.join("src/main.rs"), "fn main() {}").unwrap();
        std::fs::create_dir_all(real.join("other")).unwrap();
        std::fs::write(real.join("other/secret.txt"), "x").unwrap();
        (dir, project)
    }

    #[test]
    fn inside_paths() {
        let (_d, project) = tmp();
        let s = Scope::new(project.to_str().unwrap());
        assert!(s.contains("src/main.rs"));
        assert!(s.contains("./src/../src/main.rs"));
        assert!(s.contains("src/new_file_not_yet_created.rs"));
        assert!(s.contains("brand/new/dir/file.txt"));
        assert!(s.contains(project.join("src/main.rs").to_str().unwrap()));
        assert!(s.contains("."));
    }

    #[test]
    fn dot_dot_traversal_is_outside() {
        let (_d, project) = tmp();
        let s = Scope::new(project.to_str().unwrap());
        assert!(!s.contains(".."));
        assert!(!s.contains("../other/secret.txt"));
        assert!(!s.contains("src/../../other/secret.txt"));
        assert!(!s.contains("src/../../../../../../etc/passwd"));
        assert!(!s.contains("does/not/exist/../../../../other"));
    }

    #[test]
    fn absolute_paths_outside_are_outside() {
        let (_d, project) = tmp();
        let s = Scope::new(project.to_str().unwrap());
        assert!(!s.contains("/etc/passwd"));
        assert!(!s.contains("/"));
        assert!(!s.contains("~/.ssh/id_rsa"));
        // A sibling whose name merely starts with the folder's name.
        let sibling = format!("{}-evil/x", project.display());
        assert!(!s.contains(&sibling));
    }

    #[cfg(unix)]
    #[test]
    fn symlinks_are_followed() {
        let (_d, project) = tmp();
        let other = project.parent().unwrap().join("other");
        std::os::unix::fs::symlink(&other, project.join("escape")).unwrap();
        std::os::unix::fs::symlink(other.join("secret.txt"), project.join("src/innocent.txt")).unwrap();
        std::os::unix::fs::symlink("main.rs", project.join("src/alias.rs")).unwrap();
        std::os::unix::fs::symlink("../..", project.join("src/up")).unwrap();
        let s = Scope::new(project.to_str().unwrap());
        assert!(!s.contains("escape/secret.txt"), "dir symlink out of the folder");
        assert!(!s.contains("escape/new.txt"), "new file through a dir symlink");
        assert!(!s.contains("src/innocent.txt"), "file symlink out of the folder");
        assert!(!s.contains("src/up/other/secret.txt"), "relative symlink up and out");
        assert!(s.contains("src/alias.rs"), "symlink that stays inside");
    }

    #[cfg(unix)]
    #[test]
    fn symlink_loops_fail_closed() {
        let (_d, project) = tmp();
        std::os::unix::fs::symlink(project.join("b"), project.join("a")).unwrap();
        std::os::unix::fs::symlink(project.join("a"), project.join("b")).unwrap();
        assert!(!Scope::new(project.to_str().unwrap()).contains("a/x"));
    }

    #[cfg(unix)]
    #[test]
    fn root_given_through_a_symlink() {
        let (_d, project) = tmp();
        let link = project.parent().unwrap().join("link-to-project");
        std::os::unix::fs::symlink(&project, &link).unwrap();
        let s = Scope::new(link.to_str().unwrap());
        assert!(s.contains("src/main.rs"));
        assert!(s.contains(project.join("src/main.rs").to_str().unwrap()));
        assert!(!s.contains("../other/secret.txt"));
    }

    #[test]
    fn no_folder_means_no_scope() {
        assert!(Scope::new("").contains("/etc/passwd"));
    }
}
