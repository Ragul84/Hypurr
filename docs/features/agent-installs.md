# One-tap agent installs per platform

`host/src/agent/backends.rs` (`Install::command_for`, `windows_install`). macOS and Linux run each
vendor's documented script as before. On native Windows, Hypurr runs the vendor's documented
PowerShell install (`powershell -NoProfile -ExecutionPolicy Bypass -Command …`); npm packages use
`npm.cmd` and say plainly when Node.js is missing. Agents with no native Windows install show
"Not available on Windows yet" instead of an install button. `hypurr-host install-plan` prints
the plan for this computer without installing anything.

| Agent | Windows command | Source |
|---|---|---|
| Claude Code | `irm https://claude.ai/install.ps1 \| iex` | https://code.claude.com/docs/en/setup |
| Codex | `irm https://chatgpt.com/codex/install.ps1 \| iex` | https://github.com/openai/codex (README) |
| Cursor CLI | `irm 'https://cursor.com/install?win32=true' \| iex` | https://cursor.com/docs/cli/installation |
| goose | download `download_cli.ps1` from aaif-goose/goose, run with `CONFIGURE=false` | https://goose-docs.ai/docs/getting-started/installation/ |
| Kimi Code | `irm https://code.kimi.com/kimi-code/install.ps1 \| iex` (needs Git for Windows to run) | https://github.com/MoonshotAI/kimi-code |
| Mistral Vibe | `uv tool install mistral-vibe` (needs uv) | https://github.com/mistralai/mistral-vibe |
| Kiro CLI | `irm 'https://cli.kiro.dev/install.ps1' \| iex` (Windows 11) | https://kiro.dev/docs/cli/ |
| Devin CLI | `irm https://static.devin.ai/cli/setup.ps1 \| iex` | https://docs.devin.ai/cli |
| Junie | `iex (irm 'https://junie.jetbrains.com/install.ps1')` | https://junie.jetbrains.com/docs/junie-cli.html |
| Antigravity CLI | `irm https://antigravity.google/cli/install.ps1 \| iex` | https://antigravity.google/docs/cli/install |
| Cortex Code | `irm https://ai.snowflake.com/static/cc-scripts/install.ps1 \| iex` | https://docs.snowflake.com/en/user-guide/cortex-code/cortex-code-cli |
| Poolside | `irm https://downloads.poolside.ai/pool/install.ps1 \| iex` (beta) | https://docs.poolside.ai/cli/install |
| Amp | Not available on Windows yet (vendor documents WSL only) | https://ampcode.com/docs/cli |
| npm agents (Gemini, Grok Build, Copilot, Qwen, Pi, Droid, Kilo, Cline, Auggie, Qoder, CodeBuddy, MiniMax) | `npm.cmd install -g <package>` | each package's npm page |
