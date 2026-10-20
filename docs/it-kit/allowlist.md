# Allowlist template

Taken from the host code (`host/src`). Copy into your firewall / proxy / EDR tickets.
Rows marked *local-only: blocked* are switched off by the host itself in local-only mode.

## Binaries

| Binary | Where | Why |
| --- | --- | --- |
| `hypurr-host` | `~/.local/bin` (or `/usr/local/bin`), or inside `Hypurr.app` | The host |
| `hypurr-screen` | Next to `hypurr-host` | Screen sharing helper (optional) |
| `hypurr-agent` | `~/.hypurr/` | Built-in agent (optional) |
| `claude`, `codex`, `gemini`, … | Wherever your team installs them | Agents the host launches |
| `git`, `node`/`npx` | System | Worktrees; some agents and MCP servers |

## Inbound

| Port | From | Why |
| --- | --- | --- |
| TCP 19222 | Same LAN / VPN only | Paired phones connect directly (encrypted WebSocket). Change with `--port`. Never expose to the internet |

## Outbound (HTTPS 443)

| Host | Used for | Local-only |
| --- | --- | --- |
| `api.hypurr.dev` | Pairing and relay (WebSocket) | blocked |
| Your push relay (`relay_url`) | Content-free wake-ups for the phone | blocked |
| `github.com`, `api.github.com`, `objects.githubusercontent.com` | Host updates, built-in agent download, skills | blocked |
| `raw.githubusercontent.com` | Installer script, skills | blocked |
| `cdn.agentclientprotocol.com` | Agent registry list | blocked |
| `registry.modelcontextprotocol.io` | MCP connector list | blocked |
| `backend.composio.dev` | Composio connectors (only if a user adds a key) | blocked |
| Model providers (e.g. `api.anthropic.com`, `api.openai.com`) | The agents' own model calls | only approved endpoints |
| Your GitHub / GitHub Enterprise, Jira, Slack / Teams webhook hosts | Integrations a user sets up | allowed if your network allows |

Screen sharing uses WebRTC; on the LAN it needs nothing else, through the cloud it uses
the TURN servers the cloud hands out.
