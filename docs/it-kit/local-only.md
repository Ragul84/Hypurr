# Local-only mode

An enforced setting (Team admin → Rules → Local-only, or `HYPURR_LOCAL_ONLY=1` for the
host process, which an MDM profile can set and the user cannot switch off in the app).

When it is on, the host:

- **turns the Hypurr cloud off**: no pairing through the cloud, no relay socket, no push
  relay. Phones pair and connect over the LAN / VPN only (TCP 19222);
- **stops background internet calls**: no update checks, no agent registry refresh;
- **refuses** marketplace lists, installing connectors / skills / the built-in agent,
  connector sign-ins and Composio;
- **launches agents only if** they are on the team's allowed list **and** every model
  endpoint they are configured with (`*_BASE_URL`, `*_ENDPOINT`, `*API_BASE`,
  `OLLAMA_HOST`) is on the approved list (`https://…` or a local model on
  `localhost` / `127.0.0.1`). An agent with no endpoint set is refused, because it would
  use its public default.

What it cannot do: stop a program that ignores its endpoint settings. Pair the mode with
your proxy/firewall allowing only the approved endpoints (see [allowlist.md](allowlist.md)).

Every change of the setting is written to the audit log.
