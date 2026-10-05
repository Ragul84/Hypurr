//! Task templates: one-tap starting points with tuned prompts and safe defaults.
//! Built-ins ship with the host; user-defined ones live in kv `templates`.

use crate::store::Store;
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Template {
    #[serde(default)]
    pub id: String,
    pub title: String,
    /// One line under the title.
    #[serde(default)]
    pub summary: String,
    /// Symbol name clients map to an icon: `test`, `bug`, `review`, `deps`, `explain`, `custom`.
    #[serde(default = "custom_icon")]
    pub icon: String,
    /// `{goal}` and `{input}` are filled in.
    pub prompt: String,
    /// Asks for a second field (an error, a PR link) under this label.
    #[serde(default)]
    pub input_label: Option<String>,
    /// What the goal field says when empty.
    #[serde(default)]
    pub goal_hint: String,
    /// The agent is told not to change files.
    #[serde(default)]
    pub read_only: bool,
    #[serde(default)]
    pub builtin: bool,
}

fn custom_icon() -> String {
    "custom".into()
}

#[allow(clippy::too_many_arguments)] // one row of the built-in table
fn builtin(
    id: &str,
    title: &str,
    summary: &str,
    icon: &str,
    hint: &str,
    input: Option<&str>,
    read_only: bool,
    prompt: &str,
) -> Template {
    Template {
        id: id.into(),
        title: title.into(),
        summary: summary.into(),
        icon: icon.into(),
        prompt: prompt.into(),
        input_label: input.map(Into::into),
        goal_hint: hint.into(),
        read_only,
        builtin: true,
    }
}

pub fn builtins() -> Vec<Template> {
    vec![
        builtin(
            "write-tests",
            "Write tests",
            "Add tests for a feature or file",
            "test",
            "What should be tested? e.g. the login form",
            None,
            false,
            "Write automated tests for: {goal}\n\nFind the project's existing test setup and follow its style. Cover the main behaviour and the important edge cases, then run the tests. Change only test code; if you find a real bug, describe it instead of changing app code.",
        ),
        builtin(
            "fix-error",
            "Fix this error",
            "Paste an error or log and get a fix",
            "bug",
            "Where did it happen? e.g. when I log in",
            Some("Error message or log"),
            false,
            "Fix this error. {goal}\n\nError or log:\n```\n{input}\n```\n\nFind the cause, make the smallest safe fix, and run the relevant tests to show it works.",
        ),
        builtin(
            "review-pr",
            "Review my PR",
            "Get a plain-language review of changes",
            "review",
            "Which changes? e.g. my branch, or a PR link",
            Some("Pull request link or branch (optional)"),
            true,
            "Review these changes: {goal} {input}\n\nDon't change any files. List problems by how serious they are (bugs, security, missing tests, readability), in plain words, with the file and line, and suggest a fix for each.",
        ),
        builtin(
            "update-deps",
            "Update dependencies",
            "Safely update packages and check the build",
            "deps",
            "Anything specific? e.g. only the web app",
            None,
            false,
            "Update this project's dependencies. {goal}\n\nPrefer patch and minor updates; list major updates instead of applying them. Run the build and tests afterwards and fix anything the updates broke. Don't publish anything.",
        ),
        builtin(
            "explain-code",
            "Explain this code",
            "Understand a file or feature in plain words",
            "explain",
            "Which code? e.g. src/auth or the payment flow",
            None,
            true,
            "Explain {goal} in plain language for someone in their first year of programming: what it does, how the pieces fit together, and anything risky or surprising. Don't change any files.",
        ),
    ]
}

fn custom(store: &Store) -> Vec<Template> {
    store.kv_get("templates").and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
}

pub fn all(store: &Store) -> Vec<Template> {
    let mut out = builtins();
    out.extend(custom(store).into_iter().map(|mut t| {
        t.builtin = false;
        t
    }));
    out
}

pub fn get(store: &Store, id: &str) -> Option<Template> {
    all(store).into_iter().find(|t| t.id == id)
}

/// Adds or replaces a user template (built-ins can't be changed).
pub fn save(store: &Store, mut t: Template) -> Result<Template> {
    if t.title.trim().is_empty() || t.prompt.trim().is_empty() {
        bail!("a template needs a title and a prompt");
    }
    if t.id.is_empty() {
        t.id = format!("custom-{}", uuid::Uuid::new_v4());
    }
    if builtins().iter().any(|b| b.id == t.id) {
        bail!("built-in templates can't be changed");
    }
    t.builtin = false;
    if !t.prompt.contains("{goal}") {
        t.prompt = format!("{}\n\n{{goal}}", t.prompt.trim_end());
    }
    let mut list = custom(store);
    list.retain(|x| x.id != t.id);
    list.push(t.clone());
    store.kv_set("templates", &serde_json::to_string(&list)?)?;
    Ok(t)
}

pub fn delete(store: &Store, id: &str) -> Result<bool> {
    let mut list = custom(store);
    let before = list.len();
    list.retain(|x| x.id != id);
    store.kv_set("templates", &serde_json::to_string(&list)?)?;
    Ok(list.len() != before)
}

/// The template's prompt with the user's words filled in.
pub fn fill(t: &Template, goal: &str, input: &str) -> String {
    let goal = if goal.trim().is_empty() { "this project" } else { goal.trim() };
    t.prompt.replace("{goal}", goal).replace("{input}", input.trim()).trim().to_owned()
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    #[test]
    fn five_builtins_with_goal_slots() {
        let b = builtins();
        assert_eq!(
            b.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(),
            ["write-tests", "fix-error", "review-pr", "update-deps", "explain-code"]
        );
        assert!(b.iter().all(|t| t.prompt.contains("{goal}")));
        assert_eq!(b.iter().filter(|t| t.read_only).count(), 2);
    }

    #[test]
    fn fill_uses_goal_and_input() {
        let t = get(&Store::open(std::path::Path::new(":memory:")).unwrap(), "fix-error").unwrap();
        let p = fill(&t, "on login", "TypeError: x is undefined");
        assert!(p.starts_with("Fix this error. on login"));
        assert!(p.contains("TypeError: x is undefined"));
        assert!(fill(&builtins()[4], "", "").starts_with("Explain this project"));
    }

    #[test]
    fn custom_templates_round_trip() {
        let store = Store::open(std::path::Path::new(":memory:")).unwrap();
        let t = save(
            &store,
            Template {
                id: String::new(),
                title: "Add docs".into(),
                summary: String::new(),
                icon: "custom".into(),
                prompt: "Write docs".into(),
                input_label: None,
                goal_hint: String::new(),
                read_only: false,
                builtin: true,
            },
        )
        .unwrap();
        assert!(t.id.starts_with("custom-") && !t.builtin && t.prompt.ends_with("{goal}"));
        assert_eq!(all(&store).len(), 6);
        assert!(save(&store, Template { id: "fix-error".into(), ..t.clone() }).is_err());
        assert!(delete(&store, &t.id).unwrap());
        assert_eq!(all(&store).len(), 5);
    }
}
