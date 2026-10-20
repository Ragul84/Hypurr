# Security FAQ

**Does our source code go to Hypurr?** No. It stays on the laptop. Agents send context to
the model endpoint they are configured for; in local-only mode that can only be an endpoint
you approved (company gateway or a local model).

**Can Hypurr staff read the phone ↔ laptop traffic?** No. It is end-to-end encrypted;
the relay forwards ciphertext. In local-only mode there is no relay at all.

**Does it need admin rights or open inbound ports to the internet?** No admin rights. It
listens on TCP 19222 for phones on the same network; it never needs a port opened on the
firewall. Away from the LAN it dials out to the relay (cloud mode only).

**Where is data stored?** `~/.hypurr/` on the laptop (SQLite + files), readable only by
the user. Disk encryption (FileVault / LUKS / BitLocker) protects it at rest.

**How are secrets stored?** Agent sign-ins stay in each agent's own credential store.
Connector keys Hypurr keeps are stored in `~/.hypurr/` with user-only permissions.

**Can an agent run anything?** Agents run as the user, like a developer's terminal. Hypurr
adds: folder rules, allowed agents per team, approval for risky steps, ₹ spend limits per
person and team, and an audit log. It is not a sandbox; your EDR and network rules still apply.

**What if a phone is lost?** Remove it under You → Devices (or from an admin's console);
its channel closes at once and its keys stop working.

**Can we keep everything inside our network?** Yes: [local-only mode](local-only.md).

**Can we audit what happened?** Yes: [audit-export.md](audit-export.md).

**How do we know the installer is genuine?** See [signed-installer.md](signed-installer.md).

**Telemetry?** None is sent to Hypurr.

**Who do we contact about a vulnerability?** Open a private security advisory on the
GitHub repository.
