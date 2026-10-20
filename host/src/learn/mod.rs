//! Learning mode for juniors, and project onboarding ([`guide`]).
//!
//! Learning mode only adds words: the task's agent is asked to explain each step simply,
//! the phone offers "Teach me this" cards built from the user's own code, and after a task
//! a three-question check-yourself quiz. Nothing waits on it; approvals and the safety net
//! are untouched.

pub mod guide;

use crate::chat::profile::Profile;
use crate::hub::Hub;
use anyhow::{Result, anyhow};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::path::Path;
use std::process::Command;
use std::sync::Arc;

/// On by default for people new to this, and for students.
pub fn default_on(p: &Profile) -> bool {
    p.experience.as_deref() == Some("New") || p.role.as_deref() == Some("Student")
}

/// Whether a new task explains itself: the phone's choice, else the starter's profile.
pub fn wanted(store: &crate::store::Store, b: &Value) -> bool {
    b["learning"].as_bool().unwrap_or_else(|| {
        let key = b["startedBy"]["key"].as_str();
        default_on(&crate::chat::profile::load(store, key))
    })
}

/// Extra working rules for a task in learning mode.
pub const RULES: &str = "\n- Learning mode is on: the user is learning. Before each step, say in one or two short sentences what you're about to do and why, in simple words. When you use an idea a beginner may not know (for example a test, a branch, async code), name it in one line starting with `Teach me:`. Keep working as usual; never wait for the user because of this.";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Card {
    pub id: String,
    pub concept: String,
    pub explain: String,
    /// A real line from the user's code, with where it is.
    pub example: String,
    pub file: String,
    pub line: usize,
}

struct Concept {
    id: &'static str,
    name: &'static str,
    explain: &'static str,
    /// Any of these in a line shows the idea.
    marks: &'static [&'static str],
    /// The correct quiz answer, and a wrong one other concepts can borrow.
    answer: &'static str,
}

const CONCEPTS: &[Concept] = &[
    Concept {
        id: "test",
        name: "Automated tests",
        explain: "A test is a small program that runs your code and checks the result, so a later change can't quietly break it.",
        marks: &["#[test]", "test(", "it(", "describe(", "@Test", "def test_", "func Test", "assert"],
        answer: "It runs the code and checks the result automatically",
    },
    Concept {
        id: "async",
        name: "Async code",
        explain: "Async code starts slow work (a network call, a file read) and carries on; `await` pauses just that piece until the result is ready.",
        marks: &["async ", "await", ".then(", "suspend fun", "Promise"],
        answer: "It lets slow work finish without freezing everything else",
    },
    Concept {
        id: "import",
        name: "Imports and modules",
        explain: "Code is split into files (modules). An import brings a piece of another file into this one so it can be reused.",
        marks: &["import ", "require(", "use crate::", "from ", "#include"],
        answer: "It reuses code that lives in another file",
    },
    Concept {
        id: "function",
        name: "Functions",
        explain: "A function is a named set of steps. You give it inputs, it gives back a result, and you can call it from many places.",
        marks: &["function ", "fn ", "def ", "fun ", "func "],
        answer: "A named set of steps you can call with inputs",
    },
    Concept {
        id: "error",
        name: "Error handling",
        explain: "Code that can fail says what to do when it does: show a message, retry, or stop safely, instead of crashing.",
        marks: &["try {", "catch", "except ", "Result<", "?;", "throw ", "raise "],
        answer: "It decides what happens when something fails",
    },
    Concept {
        id: "http",
        name: "HTTP requests",
        explain: "Apps talk to servers by sending requests to a URL (GET to read, POST to send) and reading the response.",
        marks: &["fetch(", "axios", "http.", "reqwest", "requests.", "GET", "POST"],
        answer: "It asks a server for data or sends data to it",
    },
    Concept {
        id: "env",
        name: "Configuration and environment variables",
        explain: "Settings that change between your laptop and production (keys, URLs) live outside the code, in environment variables or config files.",
        marks: &["process.env", "env::var", "os.environ", "getenv", ".env"],
        answer: "It keeps settings like keys and URLs out of the code",
    },
    Concept {
        id: "state",
        name: "State",
        explain: "State is data that can change while the app runs, like what's in a cart. When it changes, the screen shows the new value.",
        marks: &["useState", "mutableStateOf", "setState", "this.state", "StateFlow"],
        answer: "Data that changes while the app runs and updates the screen",
    },
    Concept {
        id: "sql",
        name: "Database queries",
        explain: "A query asks the database for rows (SELECT) or changes them (INSERT, UPDATE). Placeholders like `?` keep user input safe.",
        marks: &["SELECT ", "INSERT ", "UPDATE ", "query(", "prepare("],
        answer: "It reads or changes rows in a database",
    },
    Concept {
        id: "branch",
        name: "Git branches",
        explain: "A branch is a separate line of work. Hypurr does each task on its own branch so main stays safe until you review and merge.",
        marks: &[],
        answer: "So the work stays separate until you review it",
    },
];

fn concept(id: &str) -> Option<&'static Concept> {
    CONCEPTS.iter().find(|c| c.id == id)
}

fn git(dir: &Path, args: &[&str]) -> Vec<String> {
    Command::new("git")
        .arg("-C")
        .arg(dir)
        .args(args)
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).lines().map(str::to_owned).filter(|l| !l.is_empty()).collect())
        .unwrap_or_default()
}

/// Files a task changed (committed and not), relative to its folder.
pub fn changed(dir: &Path, base: Option<&str>) -> Vec<String> {
    let mut out: BTreeSet<String> = BTreeSet::new();
    if let Some(b) = base {
        out.extend(git(dir, &["diff", "--name-only", b]));
    }
    out.extend(git(dir, &["diff", "--name-only", "HEAD"]));
    out.extend(git(dir, &["ls-files", "--others", "--exclude-standard"]));
    out.into_iter().filter(|f| dir.join(f).is_file()).collect()
}

/// "Teach me this" cards: each idea found in these files, with the first real line that shows it.
pub fn cards_from(dir: &Path, files: &[String], max: usize) -> Vec<Card> {
    let mut out: Vec<Card> = vec![];
    for f in files {
        let Ok(text) = std::fs::read_to_string(dir.join(f)) else { continue };
        for (i, line) in text.lines().enumerate() {
            let t = line.trim();
            if t.is_empty() || t.starts_with("//") || t.starts_with('#') && !t.starts_with("#[") || t.len() > 160 {
                continue;
            }
            for c in CONCEPTS {
                if out.iter().any(|x| x.id == c.id) || !c.marks.iter().any(|m| t.contains(m)) {
                    continue;
                }
                out.push(Card {
                    id: c.id.into(),
                    concept: c.name.into(),
                    explain: c.explain.into(),
                    example: t.to_owned(),
                    file: f.clone(),
                    line: i + 1,
                });
                if out.len() >= max {
                    return out;
                }
            }
        }
    }
    out
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Question {
    pub q: String,
    pub options: Vec<String>,
    pub answer: usize,
    pub why: String,
}

/// A three-question check built from the task itself; correct answers move around.
pub fn quiz_from(title: &str, files: &[String], cards: &[Card]) -> Vec<Question> {
    let mut qs = vec![];
    // 1. Which file did the task change?
    if let Some(f) = files.first() {
        let mut opts = vec![f.clone(), "README.md".to_owned(), "No files changed".to_owned()];
        if files.iter().any(|x| x == "README.md") {
            opts[1] = "package-lock.json".into();
        }
        qs.push(Question {
            q: format!("“{title}” changed which of these files?"),
            options: opts,
            answer: 0,
            why: format!("{f} is in this task's changes."),
        });
    }
    // 2. A concept from their own code.
    let others: Vec<&Concept> = CONCEPTS.iter().collect();
    for card in cards.iter().take(2) {
        if qs.len() >= 2 {
            break;
        }
        let Some(c) = concept(&card.id) else { continue };
        let wrong: Vec<&str> = others.iter().filter(|o| o.id != c.id).map(|o| o.answer).take(2).collect();
        qs.push(Question {
            q: format!("In {}:{} the code uses {}. What is it for?", card.file, card.line, c.name.to_lowercase()),
            options: vec![wrong[0].to_owned(), c.answer.to_owned(), wrong[1].to_owned()],
            answer: 1,
            why: c.explain.to_owned(),
        });
    }
    // 3. Always: why a branch.
    let b = concept("branch").expect("branch concept");
    qs.push(Question {
        q: "Why did Hypurr do this task on its own branch?".into(),
        options: vec![
            "To make the app run faster".into(),
            "Git needs a new branch for every file".into(),
            b.answer.into(),
        ],
        answer: 2,
        why: b.explain.into(),
    });
    while qs.len() < 3 {
        let c = concept("test").expect("test concept");
        qs.insert(
            qs.len() - 1,
            Question {
                q: "What does an automated test do?".into(),
                options: vec![c.answer.into(), CONCEPTS[2].answer.into(), CONCEPTS[5].answer.into()],
                answer: 0,
                why: c.explain.into(),
            },
        );
    }
    qs.truncate(3);
    qs
}

pub fn parse_quiz(raw: &str) -> Vec<Question> {
    let start = raw.find('[');
    let end = raw.rfind(']');
    let (Some(s), Some(e)) = (start, end) else { return vec![] };
    let list: Vec<Question> = serde_json::from_str(&raw[s..=e]).unwrap_or_default();
    let ok: Vec<Question> = list
        .into_iter()
        .filter(|q| q.options.len() >= 2 && q.answer < q.options.len() && !q.q.is_empty())
        .take(3)
        .collect();
    if ok.len() == 3 { ok } else { vec![] }
}

fn task_dir(t: &crate::tasks::Task) -> std::path::PathBuf {
    std::path::PathBuf::from(t.worktree.clone().unwrap_or_else(|| t.project.clone()))
}

/// `learnCards {taskId}` or `{project}` → `{cards}` (from the task's changes, else the project).
pub async fn cards(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let (dir, files) = if let Some(id) = b["taskId"].as_str().filter(|s| !s.is_empty()) {
        let t = crate::tasks::Task::load(&hub.store, id).ok_or_else(|| anyhow!("task not found"))?;
        let dir = task_dir(&t);
        let base = t.base.clone();
        let d = dir.clone();
        let files = tokio::task::spawn_blocking(move || changed(&d, base.as_deref())).await?;
        (dir, files)
    } else {
        let dir = guide::project_dir(&hub.store, b)?;
        let d = dir.clone();
        let files =
            tokio::task::spawn_blocking(move || guide::walk(&d).into_iter().take(400).collect::<Vec<_>>()).await?;
        (dir, files)
    };
    let list = tokio::task::spawn_blocking(move || cards_from(&dir, &files, 4)).await?;
    Ok(json!({"cards": list}))
}

const QUIZ_SYSTEM: &str = "You write a 3-question multiple-choice check for a beginner about a coding task they just watched. Return only JSON: [{\"q\":..., \"options\":[3 strings], \"answer\": index, \"why\": one simple sentence}]. Ask about what changed and why, using only the facts given.";

/// `learnQuiz {taskId, botId?}` → `{questions, by}`.
pub async fn quiz(hub: &Arc<Hub>, b: &Value) -> Result<Value> {
    let id = b["taskId"].as_str().ok_or_else(|| anyhow!("missing taskId"))?;
    let t = crate::tasks::Task::load(&hub.store, id).ok_or_else(|| anyhow!("task not found"))?;
    let dir = task_dir(&t);
    let base = t.base.clone();
    let (files, cards) = tokio::task::spawn_blocking(move || {
        let f = changed(&dir, base.as_deref());
        let c = cards_from(&dir, &f, 4);
        (f, c)
    })
    .await?;
    let mut questions = quiz_from(&t.title, &files, &cards);
    let mut by = "task";
    let summary = t.extra.get("summary").and_then(Value::as_str).unwrap_or_default();
    let prompt = format!(
        "Task: {}\nSummary: {summary}\nChanged files: {}\nIdeas used: {}",
        t.title,
        files.join(", "),
        cards.iter().map(|c| format!("{} ({}:{})", c.concept, c.file, c.line)).collect::<Vec<_>>().join(", ")
    );
    if !files.is_empty()
        && let Some(a) = guide::ask_agent(hub, b["botId"].as_str(), QUIZ_SYSTEM, &prompt).await
    {
        let parsed = parse_quiz(&a);
        if !parsed.is_empty() {
            questions = parsed;
            by = "agent";
        }
    }
    Ok(json!({"questions": questions, "by": by}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_follows_the_profile() {
        assert!(default_on(&Profile { experience: Some("New".into()), ..Profile::default() }));
        assert!(default_on(&Profile { role: Some("Student".into()), ..Profile::default() }));
        assert!(!default_on(&Profile {
            role: Some("Developer".into()),
            experience: Some("Comfortable".into()),
            ..Profile::default()
        }));
    }

    fn tmp() -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("hypurr-learn-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("cart.ts"), "// cart\nimport { api } from './api'\nexport async function add(item) {\n  const r = await fetch('/cart')\n}\n").unwrap();
        d
    }

    #[test]
    fn cards_quote_the_users_own_code() {
        let d = tmp();
        let cards = cards_from(&d, &["cart.ts".into()], 4);
        let ids: Vec<&str> = cards.iter().map(|c| c.id.as_str()).collect();
        assert_eq!(ids, vec!["import", "async", "function", "http"]);
        assert_eq!(cards[0].line, 2);
        assert!(cards[0].example.starts_with("import"));
        assert!(cards.iter().all(|c| !c.explain.is_empty()));
    }

    #[test]
    fn quiz_has_three_questions_with_valid_answers() {
        let d = tmp();
        let cards = cards_from(&d, &["cart.ts".into()], 4);
        for (files, cards) in [(vec!["cart.ts".to_owned()], cards.clone()), (vec![], vec![])] {
            let q = quiz_from("Fix the cart", &files, &cards);
            assert_eq!(q.len(), 3);
            assert!(q.iter().all(|x| x.answer < x.options.len() && !x.why.is_empty()));
            let firsts: BTreeSet<usize> = q.iter().map(|x| x.answer).collect();
            assert!(firsts.len() > 1 || files.is_empty(), "answers move around");
        }
    }

    #[test]
    fn parses_agent_quiz_and_refuses_broken_ones() {
        let ok = r#"Here: [{"q":"a","options":["x","y","z"],"answer":1,"why":"w"},{"q":"b","options":["x","y"],"answer":0,"why":"w"},{"q":"c","options":["x","y","z"],"answer":2,"why":"w"}]"#;
        assert_eq!(parse_quiz(ok).len(), 3);
        assert_eq!(parse_quiz(&ok.replace("\"answer\":2", "\"answer\":9")).len(), 0);
        assert_eq!(parse_quiz("no json").len(), 0);
    }
}
