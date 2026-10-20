# Data flow, on one page

## What runs where

| Piece | Runs on | Does |
| --- | --- | --- |
| `hypurr-host` | The employee's laptop, as that user | Runs AI coding agents in folders the user picks, keeps tasks, chat and the audit log in `~/.hypurr/` (SQLite) |
| Agents (Claude Code, Codex, built-in agent, …) | The same laptop, child processes of the host | Read and edit code in the chosen folder; call their model endpoint |
| Phone app (Android) | The employee's phone | A remote control: starts tasks, approves risky steps, reads results |
| Hypurr cloud (optional) | `api.hypurr.dev` | Pairing and a relay so the phone can reach the laptop away from the office Wi-Fi. Turned off in local-only mode |

Code, files, prompts and agent output stay on the laptop. The phone sees them only
through the encrypted channel below.

## What leaves the laptop

1. **Model calls.** Each agent sends prompts and code context to its model endpoint
   (the provider the user signed in to, or your company gateway / local model in
   local-only mode). This is the agent's own traffic; Hypurr does not proxy it.
2. **Phone traffic.** Encrypted frames to the paired phone: directly over the LAN
   (TCP 19222), or through the Hypurr relay when the cloud is on. The relay only sees
   ciphertext, sizes and timing.
3. **Push wake-ups** (cloud on only): a sealed, content-free nudge through the push relay.
4. **Optional, user-started:** update checks (GitHub releases), marketplace lists
   (agent / MCP / skill registries), connectors the user signs in to, and integrations
   the user sets up (GitHub, Jira, Slack/Teams webhooks). Shares (“Share”, “Copy”) never
   send anything themselves; the user picks the app.

Nothing is sent to Hypurr for analytics.

## End-to-end encryption

Phone ↔ laptop uses a signed ephemeral handshake (X25519 + Ed25519 identity keys,
HKDF-SHA256) and ChaCha20-Poly1305 frames, rekeyed before counters wrap. Pairing shows
a short code on both screens to rule out a man in the middle. The relay cannot read or
change frames. Spec: `docs/reference/remote-relay.md`, test vectors in
`docs/reference/fixtures/`.

## No admin rights

Installs into the user's home (`~/.local/bin` or the macOS app), runs as a per-user
launchd agent / systemd user unit. No root, no kernel extension, no system service.
Screen sharing on macOS asks the user for the Screen Recording permission.

## Folder scope

Agents start in the folder the user (or the team's folder rules) chose, with Git
worktrees for parallel tasks. Admins can restrict which folders and which agents team
members may use. Risky steps (deleting, pushing, installing, network calls the agent
asks about) can require approval from the phone, and from an admin above a set risk.

## Audit

Every admin and risky action is written to a hash-chained audit log on the laptop
(each record carries the hash of the one before). Admins can export it as CSV or JSON
lines and check it is intact; see [audit-export.md](audit-export.md).
