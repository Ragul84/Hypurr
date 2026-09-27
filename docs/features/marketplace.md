# Marketplace and agent setup

The Plugins screen configures capabilities installed on the selected computer. Account membership does not install the same capability on every computer.

| Capability | Implementation | Behavior |
| --- | --- | --- |
| Agents | `host/src/agent/backends.rs`, `host/src/agent/registry.rs` | Detect installed harnesses and resolve ACP registry adapters |
| Connectors | `host/src/market/mod.rs` | Discover MCP servers or add one manually; install per computer and enable per bot |
| Connected apps | `host/src/market/composio.rs` | Expose connected Composio apps as connectors |
| Skills | `host/src/market/mod.rs` | Instruction folders containing `SKILL.md`, stored under `~/.codync/skills/<id>` |

An enabled connector is passed to the agent as an MCP server when its session starts or resumes. Local connectors are spawned by the agent; remote ones always go through the host's stdio proxy (`codync-host mcp remote`, `host/src/mcp.rs`), so they work with agents that only speak stdio and always carry a fresh token.

## Sign-in for remote connectors (`host/src/market/oauth.rs`)

Installing a remote connector probes it; a 401 marks it `signedOut` and bots don't get it until someone signs in. Sign-in follows the MCP authorization spec: protected-resource metadata → authorization-server metadata → dynamic client registration (both redirect URIs at once) → PKCE. Tokens stay on the computer and are refreshed on use (or after a 401).

| Where the user signs in | Redirect URI | Who finishes |
| --- | --- | --- |
| On the computer (Mac app, TUI; loopback caller) | `http://127.0.0.1:<port>/oauth/callback` | the host's callback page |
| On the phone (E2E channel caller) | `<cloud>/v1/oauth/callback` → 302 `codync://oauth?…` | the app's system sign-in sheet sends `connectorSignInFinish` |

The cloud page is stateless and the code is useless without the PKCE verifier held by the host. Servers without dynamic client registration (e.g. GitHub) need a token header instead (custom connector, URL + headers). `connectorTarget` (loopback only) hands the proxy the URL and headers.

Registry packages install only at the exact version the MCP Registry lists (no `latest`), since they run as the user. An enabled skill tells the bot where its instruction folder lives. These are distinct from the built-in `team` and `computer` MCP services.

Registry and repository metadata are untrusted: the host validates names and paths before installing. Secret values stay on the computer; listings report whether required values are configured. Adapter downloads and external authorization depend on the selected provider.

To check a change, use a disposable bot on the intended computer, verify discovery/install/configuration, start a new session as needed, and confirm the agent actually receives the capability. Listing an item alone does not verify its authentication or runtime behavior.

See [file structure](../architecture/file-structure.md), [bot collaboration](bot-collaboration.md), and [remote screen](remote-screen.md).

## Model selection

The shared bot editor requests `agentModels {backend}` from the selected computer. The host starts an isolated ACP session under its probe directory with saved agent credentials, no MCP servers and no prompt, then closes the process. The response is `{models: [{id, name, description}], currentModelId}`.

Discovery prefers ACP `configOptions` with category `model` (including grouped options), with conventional `model`/`models` IDs as a compatibility fallback. Older agents may expose `models.availableModels`. Model names and IDs come from the agent; Codync does not bundle a provider catalog. See the [ACP config option contract](https://agentclientprotocol.com/protocol/v1/session-config-options).

The editor keeps Default, shows available models in a dropdown, and resets the selected model when changing agents. Errors are visible and retryable. Custom commands and agents without model discovery retain manual ID entry; an existing unlisted ID is preserved. The probe uses an isolated directory, so project-specific agent configuration can differ from the real bot session. Changing the model restarts the bot's agent sessions with fresh model context and preserves the stored chat history. The agent remains responsible for validating availability when a session starts.
