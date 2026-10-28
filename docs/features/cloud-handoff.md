# Continue in the cloud (PC can be off)

From a task's sheet, **Continue in the cloud** hands the task to the agent maker's own cloud on the user's own
account. Hypurr hosts nothing and pays nothing; the vendor's normal plan limits apply.

| Cloud | How Hypurr starts it | Needs | Status |
|---|---|---|---|
| Codex cloud | `codex cloud exec --env <environment> [--branch <b>]`, prompt on stdin ([source](https://github.com/openai/codex/blob/main/codex-rs/cloud-tasks/src/cli.rs)) | Codex signed in with ChatGPT; a Codex cloud environment for the repo (created at chatgpt.com/codex); the name is asked once per folder and kept in the vault | Supported (Codex marks `codex cloud` experimental) |
| Claude Code on the web | `claude --cloud "<task>"` ([docs](https://code.claude.com/docs/en/claude-code-on-the-web)) | Claude Code signed in to a claude.ai plan with Claude Code on the web; repo on GitHub | Supported |
| Cursor cloud agent | `POST https://api.cursor.com/v0/agents` with `autoCreatePr` ([docs](https://cursor.com/docs/cloud-agent/api/v0)) | A Cursor API key (dashboard → API Keys), kept in the computer's vault | Supported (v0 API; v1 is in public beta) |
| Gemini CLI, Hypurr Agent, others | — | — | Not supported (no official cloud handoff) |

All three clone the **pushed** GitHub branch, not local changes: Hypurr warns when the folder has unpushed work or no
GitHub remote. The phone gets the task link (Codex/Claude) or the agent's link (Cursor); results come back as a pull
request or in the vendor's app.

API: `cloudOptions {project}`, `cloudHandoff {project, vendor, prompt, codexEnv?, branch?}`, `setCursorKey {key}`.

## Clients

- **Android**: "Continue in the cloud" in the task chat.
- **iPhone and Mac** (kit `CloudHandoffView`): the same button above the composer for task chats.
- **Linux app**: "Continue in the cloud" in the conversation details pane.
- **Windows app**: status and pairing only (no chat), so not applicable.
