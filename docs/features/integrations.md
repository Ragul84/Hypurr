# Work tools: GitHub, Jira, Slack and Teams

Tasks can start from a GitHub issue or a Jira ticket, open a pull request when they finish,
comment on the Jira ticket, and post the result to Slack or Microsoft Teams. Everything runs on
the host (`host/src/integrations/`); clients only show whether each tool is set up and call
the methods below.

## Storage and secrets

Settings live in the host store, kv `integrations`:

```json
{
  "github": {"token": "", "apiUrl": ""},
  "jira": {"baseUrl": "https://acme.atlassian.net", "email": "priya@acme.in", "token": "…", "jql": ""},
  "slack": {"url": "https://hooks.slack.com/services/…"},
  "teams": {"url": "https://…webhook.office.com/…"},
  "learning": true, "autoPr": true, "notify": true
}
```

- Secrets never leave the computer. `integrations` returns a public view: `configured`, a
  `…abcd` hint for each token or webhook, and for GitHub `usesCli` when no token is set and the
  GitHub CLI is signed in.
- `setIntegrations` is a partial update. An omitted field keeps its value; an empty string
  clears it. URLs must be `https://` (plain `http://` only for `localhost` / `127.0.0.1`).
- `learning`, `autoPr` and `notify` are the defaults for the finish options (all on).

## Pieces

| File | What it does |
|---|---|
| `integrations/mod.rs` | Config, masking, partial update, `issues` (GitHub per project plus Jira) and `testIntegration`. |
| `integrations/github.rs` | REST with a token (`apiUrl` for GitHub Enterprise), otherwise `gh api` with the CLI's own sign-in. Parses `origin` to find the repo, lists open issues (pull requests filtered out), pushes the task branch and opens a PR (reuses an open one for the same branch). |
| `integrations/jira.rs` | Jira Cloud REST v3 with email + API token (basic auth). Searches with `/rest/api/3/search/jql` (default JQL: open tickets assigned to me), reads the description from ADF, and comments when the task finishes. |
| `integrations/webhooks.rs` | Slack incoming webhook (`{text}`), Teams incoming webhook / Workflows (Adaptive Card). |

## Flow

1. **Start from an issue.** `issues {project?}` returns `{issues: [{source, key, title, url,
   body, project}], errors}`. `startTask {issue}` uses "key title" as the goal when there is
   none, the issue's project, and adds the issue text to the agent's prompt.
2. **Finish.** `finishTask {taskId, openPr?, notify?, learning?}` (see
   [tasks and the safety net](tasks-and-safety.md#finishing)). With `openPr` the host pushes the
   task branch: a plain `git push` with the user's own credentials first; with a GitHub token
   and an `https` remote it retries with the token as an HTTP header (never written to disk or
   the remote URL). Then it opens the PR against the task's base branch, with `Closes #N` for a
   GitHub issue. Jira tasks get a comment with the summary and PR link. With `notify`, Slack and
   Teams get the title, summary, changed files, PR link and cost.
3. Failures don't fail the task: they are listed in the learning card's `errors`.

## Methods

| Method | Body | Returns |
|---|---|---|
| `integrations` | | `{integrations}` (public view) |
| `setIntegrations` | partial config | `{integrations}` |
| `testIntegration` | `kind: github\|jira\|slack\|teams` | `{ok, detail}` (Slack and Teams get a test message; errors come back as a method error) |
| `issues` | `project?` | `{issues, errors}` |

## Limits

- Jira Cloud only (Server / Data Center use different auth and are not supported yet).
- Teams and Slack are one-way webhooks; there is no bot or OAuth app.
- Pushing needs either the user's own git credentials or a GitHub token with `repo` scope.
- GitLab and Bitbucket are not supported.

## Tests

- `cargo test --bin hypurr-host integrations` (masking, partial update, URL rules, remote parsing, ADF, payloads).
- `host/tests/tasks_e2e.rs` `workplace_task_from_issue_with_screenshot_summary_pr_and_cost`:
  mock GitHub, Jira, Slack and Teams servers, a real git push to a bare remote, the PR,
  comment and both webhooks.
- Android: `WorkWireTest` (fixture `docs/reference/fixtures/work-wire.json`), `TaskStoreTest`.
