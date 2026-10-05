# Built-in agent (Hypurr Agent)

Hypurr’s default coding agent is **Hypurr Agent** — a rebranded MIT fork of OpenCode.
Users never see the OpenCode name in product UI. Attribution is only in the agent’s
`LICENSE` / `NOTICE`.

## What ships

- Binary id: `hypurr-agent` (ACP: `hypurr-agent acp`)
- Install path: `~/.hypurr/agents/hypurr-agent/<version>/`
- Default model: `hypurr/hypurr-free` via the Hypurr gateway
- Free models listed in host status / Android Free badge

Optional: if the user already has a real OpenCode CLI on PATH, it can appear as agent
`opencode` but is never the default or branding.

See also [Feature 12: Hypurr Agent + gateway](./hypurr-agent-gateway.md).
