//! Plain-language routing: picks a project and an agent for a goal with simple,
//! explainable rules (word matches against project names and keywords, agent
//! preferences per kind of task). The user can always override either pick.

use serde::{Deserialize, Serialize};
use std::path::Path;

/// A folder tasks can run in (kv `projects`, plus every git project a bot works in).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Project {
    pub name: String,
    pub path: String,
    /// Extra words that point at this project ("payments", "android").
    #[serde(default)]
    pub keywords: Vec<String>,
    /// Saved by the user (vs found from a bot's folder).
    #[serde(default)]
    pub saved: bool,
}

/// An installed agent the router may pick.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct Agent {
    pub id: String,
    pub name: String,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Route {
    pub project: Option<Project>,
    pub agent: Option<Agent>,
    /// Plain words: why these were picked.
    pub reason: String,
}

const PREFER_CHANGES: &[&str] = &["hypurr-agent", "opencode", "claude", "codex", "cursor", "gemini", "grok", "pi"];
const PREFER_READING: &[&str] = &["hypurr-agent", "opencode", "claude", "gemini", "codex", "cursor", "grok", "pi"];

fn words(text: &str) -> Vec<String> {
    text.to_lowercase().split(|c: char| !c.is_alphanumeric()).filter(|w| w.len() > 1).map(str::to_owned).collect()
}

fn project_words(p: &Project) -> Vec<String> {
    let base = Path::new(&p.path).file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let mut w = words(&p.name);
    w.extend(words(&base));
    w.extend(p.keywords.iter().flat_map(|k| words(k)));
    w.retain(|x| !matches!(x.as_str(), "the" | "app" | "src" | "code" | "project" | "repo"));
    w.sort();
    w.dedup();
    w
}

/// Picks a project and agent. `last_project` (the newest task's) breaks ties.
pub fn route(goal: &str, read_only: bool, projects: &[Project], agents: &[Agent], last_project: Option<&str>) -> Route {
    let goal_words = words(goal);
    let mut best: Option<(usize, &Project, Vec<String>)> = None;
    for p in projects {
        let hits: Vec<String> = project_words(p).into_iter().filter(|w| goal_words.contains(w)).collect();
        let score = hits.len() * 10 + usize::from(last_project == Some(p.path.as_str()));
        if best.as_ref().is_none_or(|(s, _, _)| score > *s) {
            best = Some((score, p, hits));
        }
    }
    let (project, project_reason) = match best {
        Some((_, p, hits)) if !hits.is_empty() => {
            (Some(p.clone()), format!("{} matches “{}” in your request", p.name, hits.join("”, “")))
        }
        Some((_, p, _)) if projects.len() == 1 => (Some(p.clone()), format!("{} is your only project", p.name)),
        Some((_, p, _)) if last_project == Some(p.path.as_str()) => {
            (Some(p.clone()), format!("{} is the project you used last", p.name))
        }
        _ if projects.is_empty() => (None, "Add a project folder first".to_owned()),
        _ => (None, "Pick the project this is about".to_owned()),
    };
    // Named in the goal ("use codex …") wins; otherwise the preference order.
    let named =
        agents.iter().find(|a| goal_words.contains(&a.id) || words(&a.name).iter().all(|w| goal_words.contains(w)));
    let order = if read_only { PREFER_READING } else { PREFER_CHANGES };
    let (agent, agent_reason) = if let Some(a) = named {
        (Some(a.clone()), format!("you asked for {}", a.name))
    } else if let Some(a) = order.iter().find_map(|id| agents.iter().find(|a| a.id == *id)).or_else(|| agents.first()) {
        let why = if read_only { "good at reading and explaining code" } else { "good at making careful code changes" };
        (Some(a.clone()), format!("{} is installed and {why}", a.name))
    } else {
        (None, "no coding agent is installed yet".to_owned())
    };
    Route { project, agent, reason: format!("{project_reason}; {agent_reason}.") }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(name: &str, path: &str, kw: &[&str]) -> Project {
        Project {
            name: name.into(),
            path: path.into(),
            keywords: kw.iter().map(|s| (*s).to_owned()).collect(),
            saved: true,
        }
    }
    fn a(id: &str, name: &str) -> Agent {
        Agent { id: id.into(), name: name.into() }
    }

    #[test]
    fn matches_project_by_name_and_keywords() {
        let projects = [p("Shop web", "/w/shop-web", &[]), p("Payments", "/w/pay-svc", &["billing", "invoice"])];
        let agents = [a("codex", "Codex"), a("claude", "Claude Code")];
        let r = route("Fix the invoice total rounding bug", false, &projects, &agents, None);
        assert_eq!(r.project.unwrap().name, "Payments");
        assert_eq!(r.agent.unwrap().id, "claude");
        assert!(r.reason.contains("“invoice”"), "{}", r.reason);
        let r = route("shop checkout is slow", false, &projects, &agents, None);
        assert_eq!(r.project.unwrap().path, "/w/shop-web");
    }

    #[test]
    fn falls_back_to_only_or_last_project() {
        let one = [p("Api", "/w/api", &[])];
        assert_eq!(route("tidy things", false, &one, &[], None).project.unwrap().name, "Api");
        let two = [p("Api", "/w/api", &[]), p("Web", "/w/web", &[])];
        let r = route("tidy things", false, &two, &[], Some("/w/web"));
        assert_eq!(r.project.unwrap().name, "Web");
        assert!(route("tidy things", false, &two, &[], None).project.is_none());
        assert!(r.reason.contains("no coding agent"));
    }

    #[test]
    fn agent_named_in_goal_wins() {
        let agents = [a("claude", "Claude Code"), a("codex", "Codex")];
        let r = route("use codex to add tests", false, &[], &agents, None);
        assert_eq!(r.agent.unwrap().id, "codex");
        assert!(r.reason.contains("you asked for Codex"));
        assert!(route("x", false, &[], &[], None).reason.starts_with("Add a project folder first"));
    }

    #[test]
    fn prefers_builtin_hypurr_agent_when_available() {
        let projects = [p("Shop", "/w/shop", &[])];
        let agents = [a("hypurr-agent", "Hypurr Agent"), a("claude", "Claude Code")];
        let r = route("Fix the checkout total", false, &projects, &agents, None);
        assert_eq!(r.agent.unwrap().id, "hypurr-agent");
        let only = [a("hypurr-agent", "Hypurr Agent")];
        let r = route("Explain main.rs", true, &projects, &only, None);
        assert_eq!(r.agent.unwrap().id, "hypurr-agent");
    }
}
