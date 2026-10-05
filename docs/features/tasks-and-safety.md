# Beginner tasks and the safety net

Hypurr's beginner path for people who don't use coding agents every day: describe a goal
in plain words (or pick a template), let Hypurr choose the project and agent, and work on a
separate branch with checkpoints you can go back to. Approval cards explain each request in
plain words with a risk level. Everything is decided on the host (`host/src/tasks/`); clients
only render the data and call the methods below.

## Pieces

| File | What it does |
|---|---|
| `host/src/tasks/router.rs` | Picks a project and agent for a goal. Simple and predictable: word matches against project names, folders and keywords; an agent named in the goal wins, else installed agents in a fixed preference order. Always returns a plain reason ("Picked shop because your goal mentions checkout"). The user can change both before starting. |
| `host/src/tasks/templates.rs` | Built-ins: Write tests, Fix this error (asks for the error text), Review my PR (read-only), Update dependencies, Explain this code (read-only). User templates are stored in kv `templates`. `{input}` in a prompt is replaced by the user's text. |
| `host/src/tasks/safety.rs` | The safety net. Each task gets a git worktree at `~/.hypurr/worktrees/<taskId>` on branch `hypurr/<slug>-<id8>` from the project's HEAD. Checkpoints are commits on that branch (`--no-verify`, author "Hypurr"). Rolling back saves "Before going back" first, then restores the checkpoint's tree as a new commit ("Went back to: …"), so history only grows and nothing is lost. It never touches the user's own checkout or a protected branch. |
| `host/src/tasks/risk.rs` | Rule-based risk for each permission request: `low` / `medium` / `high`, one plain sentence (`explain`), reasons, and `blocked` for what the safety net refuses outright. Rules: deleting files, `git push` (force, or to a protected branch → blocked), `reset --hard`, `clean`, network access, `curl … \| sh` (blocked), `sudo`, secrets files (`.env`, keys, credentials), writes outside the project, package installs, cloud CLIs, databases. |
| `host/src/tasks/mod.rs` | Task records (SQLite `tasks` table), settings, and the API methods. |

## Lifecycle

1. `routeTask` previews the plan while the user types.
2. `startTask` routes what wasn't picked, isolates the task (worktree + branch), creates a
   bot with permission **Ask** and that folder as its cwd, and sends the first message. The chat
   shows the user's own words; the agent also gets the template prompt and Hypurr's working
   rules (stay in this folder, don't push, don't touch protected branches, explain in plain words).
3. Checkpoints are saved automatically: at the start, before every approval card
   (`data.checkpoint`, so the card's **Undo** goes back to just before it), and after every
   turn that changed files ("After step N"). `taskCheckpoint` saves one on demand.
4. `rollbackTask` goes back to any checkpoint (refused while the agent is working; clients
   stop it first). A notice in the chat says what happened.
5. `finishTask` saves a last checkpoint and marks the task finished. The branch stays for
   review or a pull request; deleting the bot removes the worktree but keeps the branch.

Without git (or with `requireGit` off and no repo) a task still runs, but `safety: "none"` and
clients hide checkpoint controls.

## Settings (kv `safety`, `setSafetySettings`)

- `protectedBranches` (default `main`, `master`, `production`, `prod`, `release/*`, `develop`)
- `blockProtected` (default on): pushes/commits to those are blocked without asking.
- `alwaysAskHigh` (default on): even bots set to auto-approve ask for high-risk requests.
- `requireGit` (default off): refuse to start a task outside a git repository.

## Data

- Bot: `task` (present only on task bots): `{id, goal, title, template, project, projectName,
  backend, safety, branch, base, worktree, status: active|finished, checkpoints: [{id, label,
  at, files}]}` (last 30 checkpoints).
- Permission entry `data`: `risk`, `explain`, `riskReasons`, `blocked` (answered automatically
  with the reject option), `checkpoint`. Push text for high risk starts with "High risk: ".
- Example of real host output: [`docs/reference/fixtures/task-wire.json`](../reference/fixtures/task-wire.json).

## Methods (`POST /api/<method>`, same on the E2E channel)

| Method | Body | Returns |
|---|---|---|
| `taskSetup` | | `{projects, agents, templates, safety}` |
| `routeTask` | `goal, template?` | `{project, agent, reason, title, …}` |
| `startTask` | `goal, template?, input?, project?, backend?, attachments?` | `{bot, task}` |
| `tasks` | | `{tasks}` |
| `taskCheckpoint` | `taskId, label?` | `{task}` |
| `rollbackTask` | `taskId, checkpointId` | `{task}` |
| `finishTask` | `taskId` | `{task}` |
| `taskTemplates` / `saveTemplate` / `deleteTemplate` | — / `{id?, title, prompt, …}` / `id` | templates |
| `saveProject` / `removeProject` | `{path, name?, keywords?}` / `path` | `{project}` |
| `safetySettings` / `setSafetySettings` | — / partial settings | `{safety}` |

## Clients

| | New task + plan | Explained card + risk | Blocked state | Undo / checkpoints | Templates & safety settings |
|---|---|---|---|---|---|
| Android | yes | yes | yes | yes (task strip, checkpoint sheet) | yes |
| iPhone / Mac (HypurrUI) | not yet | yes | yes | Undo on the card | not yet |
| Linux (GTK) | not yet | yes | yes | not yet | not yet |
| Terminal UI | not yet | yes | yes | not yet | not yet |

Android is the launch platform, so it gets the full flow first; the other clients already show
the explained cards and keep working with task bots like any other bot.

## Tests

- Unit: `cargo test --bin hypurr-host tasks` (risk rules, router, templates, worktree,
  checkpoints, rollback).
- End to end: `host/tests/tasks_e2e.rs` with `host/tests/fake_task_agent.py` (a blocked push to
  main, an explained high-risk delete, checkpoints, rollback).
- Android: `TaskWireTest` (fixture), `TaskStoreTest`, and `LocalHostE2ETest.beginnerTask`
  against a real host (`apps/android/scripts/e2e-local-host.sh`).
- Swift: `kit/Tests/HypurrKitTests/TaskWireTests.swift` (fixture).
