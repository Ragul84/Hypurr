//! "Page map": every page, its buttons, the API calls behind them and the files that hold
//! them, saved under `.hypurr/map/` (JSON + markdown), plus a suggested folder restructure.
//! The restructure is a plan only; applying it is a separate approved action on a new branch.

pub mod restructure;
pub mod scan;

use crate::hub::Hub;
use crate::store::Store;
use crate::trace::locate::Repo;
use anyhow::{Result, anyhow, ensure};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

const TEST_LIMIT: Duration = Duration::from_secs(8 * 60);

fn map_dir(project: &Path) -> Result<PathBuf> {
    let d = project.join(".hypurr").join("map");
    std::fs::create_dir_all(&d)?;
    std::fs::write(d.join(".gitignore"), "*\n")?;
    Ok(d)
}

/// Scan, plan and save.
pub fn scan_and_save(project: &Path) -> Result<Value> {
    let repo = Repo::scan(project);
    let map = scan::build(&repo);
    let plan = restructure::plan(&repo, &map);
    let d = map_dir(project)?;
    std::fs::write(d.join("map.json"), serde_json::to_string_pretty(&map)?)?;
    std::fs::write(d.join("map.md"), scan::markdown(&map))?;
    std::fs::write(d.join("restructure-plan.json"), serde_json::to_string_pretty(&plan)?)?;
    Ok(json!({"map": map, "plan": plan}))
}

fn read_json(p: &Path) -> Value {
    std::fs::read_to_string(p).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or(Value::Null)
}

fn dir(store: &Store, b: &Value) -> Result<PathBuf> {
    crate::learn::guide::project_dir(store, b)
}

/// `mapScan {project}`.
pub async fn scan(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let d = dir(&hub.store, b)?;
    tokio::task::spawn_blocking(move || scan_and_save(&d)).await?
}

/// `mapGet {project}`: the last scan, and the restructure status.
pub fn get(store: &Store, b: &Value) -> Result<Value> {
    let d = dir(store, b)?.join(".hypurr/map");
    Ok(json!({"map": read_json(&d.join("map.json")), "plan": read_json(&d.join("restructure-plan.json")),
        "applied": read_json(&d.join("restructure.json"))}))
}

/// `restructureApply {project, approved: true}`: new branch, moves, tests. Runs in the background.
pub fn apply(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    ensure!(b["approved"] == true, "Moving files needs your approval.");
    let d = dir(&hub.store, b)?;
    let plan: restructure::Plan = serde_json::from_value(read_json(&d.join(".hypurr/map/restructure-plan.json")))
        .map_err(|_| anyhow!("Make the page map first."))?;
    ensure!(!plan.moves.is_empty(), "There's nothing to move.");
    let status = map_dir(&d)?.join("restructure.json");
    std::fs::write(&status, json!({"status": "running"}).to_string())?;
    let branch = format!("hypurr/restructure-{}", chrono::Local::now().format("%Y%m%d-%H%M%S"));
    tokio::task::spawn_blocking(move || {
        let out = match restructure::apply(&d, &plan, &branch, TEST_LIMIT) {
            Ok(a) => serde_json::to_value(a).unwrap_or_default(),
            Err(e) => json!({"status": "failed", "error": format!("{e:#}")}),
        };
        let _ = std::fs::write(status, out.to_string());
    });
    Ok(json!({"applied": {"status": "running"}}))
}

/// `restructureDiscard {project}`: delete the branch.
pub fn discard(store: &Store, b: &Value) -> Result<Value> {
    let d = dir(store, b)?;
    let status = d.join(".hypurr/map/restructure.json");
    let a = read_json(&status);
    let branch = a["branch"].as_str().ok_or_else(|| anyhow!("There's no restructure branch."))?;
    restructure::discard(&d, branch)?;
    let _ = std::fs::remove_file(status);
    Ok(json!({}))
}

/// `restructurePublish {project, approved: true}`: upload the branch and open the PR, only
/// once its tests passed and only when the user taps it.
pub async fn publish(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    ensure!(b["approved"] == true, "Uploading the branch needs your approval.");
    let d = dir(&hub.store, b)?;
    let status = d.join(".hypurr/map/restructure.json");
    let mut a = read_json(&status);
    ensure!(a["status"] == "ready", "The branch is offered only after its tests pass.");
    let branch = a["branch"].as_str().unwrap_or_default().to_owned();
    let base = a["base"].as_str().unwrap_or("main").to_owned();
    let cfg = crate::integrations::Integrations::load(&hub.store);
    let gh = crate::integrations::github::Client::new(&cfg.github);
    {
        let (d2, br, token) = (d.clone(), branch.clone(), gh.as_ref().and_then(|g| g.token().map(str::to_owned)));
        tokio::task::spawn_blocking(move || crate::integrations::github::push_branch(&d2, &br, token.as_deref()))
            .await??;
    }
    a["pushed"] = true.into();
    if let (Some(g), Some(repo)) = (gh.as_ref(), crate::integrations::github::repo_of(&d)) {
        let title = a["prTitle"].as_str().unwrap_or_default().to_owned();
        let body = a["prBody"].as_str().unwrap_or_default().to_owned();
        let (n, url) = g.open_pr(&repo, &branch, &base, &title, &body).await?;
        a["prNumber"] = n.into();
        a["prUrl"] = url.into();
    }
    std::fs::write(&status, a.to_string())?;
    Ok(json!({"applied": a}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn saves_map_files() {
        let src = crate::trace::locate::tests::fixture();
        let dir = std::env::temp_dir().join(format!("hypurr-map-{}", uuid::Uuid::new_v4()));
        for f in Repo::scan(&src).files {
            let to = dir.join(&f);
            std::fs::create_dir_all(to.parent().expect("parent")).expect("mkdir");
            std::fs::copy(src.join(&f), to).expect("copy");
        }
        let v = scan_and_save(&dir).expect("scan");
        assert_eq!(v["map"]["pages"].as_array().map(Vec::len), Some(3));
        for f in ["map.json", "map.md", "restructure-plan.json", ".gitignore"] {
            assert!(dir.join(".hypurr/map").join(f).is_file(), "{f}");
        }
        let _ = std::fs::remove_dir_all(dir);
    }

    /// Map a real checkout: `HYPURR_LIVE_REPO`. Writes `.hypurr/map/` there and prints it.
    #[test]
    #[ignore = "needs a real project"]
    fn live_map() {
        let Ok(repo) = std::env::var("HYPURR_LIVE_REPO") else {
            return;
        };
        let v = scan_and_save(std::path::Path::new(&repo)).expect("scan");
        let md = std::fs::read_to_string(std::path::Path::new(&repo).join(".hypurr/map/map.md")).unwrap_or_default();
        println!("{md}\n\nPLAN:\n{:#}", v["plan"]);
    }
}
