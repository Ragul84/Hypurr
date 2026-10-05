# Built-in agent (OpenCode)

Hypurr ships with a **built-in default agent** so a brand-new user can install Hypurr and
run a first task in about five minutes without another AI account.

## What it is

- **OpenCode** ([anomalyco/opencode](https://github.com/anomalyco/opencode), MIT), already a
  first-class Hypurr harness (`opencode acp`).
- Hypurr downloads a **pinned** release binary into `~/.hypurr/agents/opencode/<version>/`
  (SHA-256 verified against the GitHub release digest). Source is not vendored.
- Default model: **`opencode/big-pickle`** (Big Pickle), a free OpenCode Zen model. Other free
  Zen models (Space Bunny Free, MiniMax M2.5 Free when listed, MiMo Flash Free, …) are offered
  in the model picker. Ids and availability follow [OpenCode Zen](https://opencode.ai/docs/zen/).

## Onboarding

1. Pair the phone with the computer.
2. If no coding agent is installed, the roster shows **Install OpenCode** (Free badge) with a
   short consent notice.
3. `installBuiltinAgent { consent: true }` (or the setup-terminal Install step for `opencode`)
   downloads and installs.
4. New bot / New task prefer the built-in agent; the router prefers it when it is available.

Users can still install Claude Code, Codex, Cursor, Gemini, etc., or sign in to OpenCode with
their own API key / Copilot / ChatGPT login (`opencode auth login`).

## Team admin

The built-in agent id is `opencode`. Stage C **allowed agents** rules apply the same way as for
any other harness: leave the list empty to allow all, or include `opencode` explicitly.

## Cost

Free Zen models are $0. Hypurr's cost tracker uses a zero rate for the `opencode` backend when
estimating; paid Zen models that report `usage_update.cost` still show their real cost.

## Clients

| Client | Status |
| --- | --- |
| Android | Install card, Free badge, free-model picker on New bot / edit bot |
| iPhone / Mac / Linux / TUI | Backend name and install via setup terminal; Free badge and model picker are Android-first (gaps) |

## Later: Hypurr credits

A future option is a Hypurr-operated gateway (“Hypurr credits”) that meters paid models while
keeping the same UX. Not in this stage; free OpenCode Zen remains the default zero-cost path.

## Licensing / ToS caveats

- OpenCode is MIT; keep attribution in NOTICE.
- Free Zen models are **time-limited** and provided by OpenCode, not Hypurr. Relying on them in a
  commercial product means accepting OpenCode's Zen terms and privacy notes (some free models may
  use prompts for training; NVIDIA free endpoints are trial-only — do not submit confidential data).
- Direct calls to `https://opencode.ai/zen/...` outside the OpenCode client return
  `FreeTierError` (“free tier can only be used from within OpenCode”); Hypurr always drives models
  through the OpenCode binary.
