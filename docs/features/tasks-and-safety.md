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
| `host/src/tasks/finish.rs` | Finishing: learning-mode summary, pull request, Jira comment, Slack / Teams post, the "What changed" card. |
| `host/src/tasks/cost.rs` | Tokens and cost per task. |
| `host/src/tasks/mod.rs` | Task records (SQLite `tasks` table), settings, and the API methods. |

## Lifecycle

1. `routeTask` previews the plan while the user types. Screenshots or error files can be
   attached before starting: clients `upload` them with a `draftId` and pass the same
   `draftId` to `startTask`, which moves them into the bot's uploads and sends them with the
   first message. A task can also start from a GitHub issue or Jira ticket (`issue`, see
   [work tools](integrations.md)).
2. `startTask` routes what wasn't picked, isolates the task (worktree + branch), creates a
   bot with permission **Ask** and that folder as its cwd, and sends the first message. The chat
   shows the user's own words; the agent also gets the template prompt and Hypurr's working
   rules (stay in this folder, don't push, don't touch protected branches, explain in plain words).
3. Checkpoints are saved automatically: at the start, before every approval card
   (`data.checkpoint`, so the card's **Undo** goes back to just before it), and after every
   turn that changed files ("After step N"). `taskCheckpoint` saves one on demand.
4. `rollbackTask` goes back to any checkpoint (refused while the agent is working; clients
   stop it first). A notice in the chat says what happened.
5. `finishTask` finishes the task (below). The branch stays for review; deleting the bot
   removes the worktree but keeps the branch.

## Finishing

`finishTask {taskId, openPr?, notify?, learning?}` (each defaults to the `integrations`
setting, all on) is refused while the agent is working. It saves a "Finished" checkpoint and
sets `status: finishing`. With **learning mode** the agent gets one more turn ("Explain what you
changed and why", shown in the chat) asking for plain bullet points: what changed, why, what to
check. Then the host computes the changed files from the first checkpoint, pushes the branch
and opens a pull request (`openPr`, GitHub projects), comments on the Jira ticket, and posts to
Slack / Teams (`notify`). The task becomes `finished` with `summary`, `changes` and `pr`, and a
notice with `data.learning` is added:

```json
{"taskId", "title", "summary", "files": [{"path", "added", "removed"}], "added", "removed",
 "branch", "base", "pr": {"number", "url", "repo"}, "cost": {usage}, "posted": ["slack"], "errors": []}
```

The notice's `text` is a plain one-paragraph version for clients that don't draw the card.
Learning mode costs one extra agent turn; without it the summary is built from the changed files.

## Cost tracker

Each task keeps `usage: {inputTokens, outputTokens, totalTokens, cost, currency, estimated,
turns}`. Tokens come from each prompt response's `usage`; when the agent reports a session cost
(`usage_update.cost`) that is used, otherwise the cost is estimated from list prices per agent
(`estimated: true`, shown as "≈ $0.12"). `taskCosts` returns `{tasks: [{taskId, botId, title,
createdAt, usage}], total: {cost, tokens, tasks, estimated}, week, today, currency}`.

Without git (or with `requireGit` off and no repo) a task still runs, but `safety: "none"` and
clients hide checkpoint controls.

## Settings (kv `safety`, `setSafetySettings`)

- `protectedBranches` (default `main`, `master`, `production`, `prod`, `release/*`, `develop`)
- `blockProtected` (default on): pushes/commits to those are blocked without asking.
- `alwaysAskHigh` (default on): even bots set to auto-approve ask for high-risk requests.
- `requireGit` (default off): refuse to start a task outside a git repository.

## Data

- Bot: `task` (present only on task bots): `{id, goal, title, template, project, projectName,
  backend, safety, branch, base, worktree, status: active|finishing|finished, checkpoints: [{id, label,
  at, files}], issue?, usage?, summary?, changes?, pr?}` (last 30 checkpoints).
- Permission entry `data`: `risk`, `explain`, `riskReasons`, `blocked` (answered automatically
  with the reject option), `checkpoint`. Push text for high risk starts with "High risk: ".
- Notice entry `data.learning`: the "What changed" card (above).
- Examples of real host output: [`task-wire.json`](../reference/fixtures/task-wire.json),
  [`work-wire.json`](../reference/fixtures/work-wire.json) (finished task, learning card, issues,
  integrations, costs).

## Methods (`POST /api/<method>`, same on the E2E channel)

| Method | Body | Returns |
|---|---|---|
| `taskSetup` | | `{projects, agents, templates, safety}` |
| `routeTask` | `goal, template?` | `{project, agent, reason, title, …}` |
| `startTask` | `goal, template?, input?, project?, backend?, attachments?, draftId?, issue?` | `{bot, task}` |
| `upload` | `draftId` (instead of `botId`), `uploadId, name, offset, data, done` | `{attachment}` |
| `tasks` | | `{tasks}` |
| `taskCheckpoint` | `taskId, label?` | `{task}` |
| `rollbackTask` | `taskId, checkpointId` | `{task}` |
| `finishTask` | `taskId, openPr?, notify?, learning?` | `{task}` |
| `taskCosts` | | `{tasks, total, week, today, currency}` |
| `integrations` / `setIntegrations` / `testIntegration` / `issues` | see [work tools](integrations.md) | |
| `taskTemplates` / `saveTemplate` / `deleteTemplate` | — / `{id?, title, prompt, …}` / `id` | templates |
| `saveProject` / `removeProject` | `{path, name?, keywords?}` / `path` | `{project}` |
| `safetySettings` / `setSafetySettings` | — / partial settings | `{safety}` |

## Clients

| | New task + plan | Explained card + risk | Blocked state | Undo / checkpoints | Templates & safety settings | Screenshot / paste, issues | Finish options | "What changed" card | Cost | Work tools settings |
|---|---|---|---|---|---|---|---|---|---|---|
| Android | yes | yes | yes | yes (task strip, checkpoint sheet) | yes | yes | yes | yes | task strip + Settings › Spending | yes |
| iPhone / Mac (HypurrUI) | not yet | yes | yes | Undo on the card | not yet | not yet | not yet | yes | not yet | not yet |
| Linux (GTK) | not yet | yes | yes | not yet | not yet | not yet | not yet | plain text | not yet | not yet |
| Terminal UI | not yet | yes | yes | not yet | not yet | not yet | not yet | plain text | not yet | not yet |

Android is the launch platform, so it gets the full flow first; the other clients already show
the explained cards and keep working with task bots like any other bot.

## Tests

- Unit: `cargo test --bin hypurr-host tasks` (risk rules, router, templates, worktree,
  checkpoints, rollback).
- End to end: `host/tests/tasks_e2e.rs` with `host/tests/fake_task_agent.py` (a blocked push to
  main, an explained high-risk delete, checkpoints, rollback) and `fake_work_agent.py` (a task
  from a GitHub issue with a screenshot, learning summary, push + PR, Jira comment, Slack and
  Teams posts, reported cost).
- Android: `TaskWireTest`, `WorkWireTest` (fixtures), `TaskStoreTest`, and `LocalHostE2ETest.beginnerTask`
  against a real host (`apps/android/scripts/e2e-local-host.sh`).
- Swift: `kit/Tests/HypurrKitTests/TaskWireTests.swift`, `WorkWireTests.swift` (fixtures).
