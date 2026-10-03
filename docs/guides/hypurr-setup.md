# Setting up your own Hypurr services

Hypurr ships with **placeholders** where the original project pointed at
services and keys owned by someone else. Nothing below is shared; you must
create your own before releases, phone pairing over the internet, sign-in or
push notifications work. LAN, Tailscale and SSH pairing work without any of it.

| Placeholder | Where | Replace with |
|---|---|---|
| `AAAA…A=` (32 zero bytes) | `packaging/updates/host-public-key.txt` | Your host update Ed25519 public key |
| `AAAA…A=` (32 zero bytes) | `packaging/updates/macos-public-key.txt`, `apps/macos/Resources/Info.plist` (`SUPublicEDKey`) | Your Sparkle Ed25519 public key |
| `DEVELOPMENT_TEAM: ""` | `apps/project.yml` (then `xcodegen generate --spec apps/project.yml`) | Your Apple Developer Team ID |
| `com.ragul84.Hypurr*`, `group.com.ragul84.hypurr` | `apps/project.yml`, entitlements | Register these App IDs / App Group in your Apple account (or change them) |
| `pk_live_REPLACE…`, `pk_test_REPLACE…` | `apps/shared/Config/{main,dev}.plist` | Your Clerk publishable keys |
| `REPLACE_WITH_YOUR_D1_DATABASE_ID` | `cloud/wrangler.toml` | `wrangler d1 create hypurr` / `hypurr-dev` IDs |
| `hypurr.dev`, `api.hypurr.dev`, `dev-api.hypurr.dev`, `clerk.hypurr.dev` | cloud, apps, web | A domain you own on Cloudflare |
| `hypurr-relay.example.workers.dev` | `kit/Sources/HypurrKit/Client/SharedStore.swift` | Your deployed `relay/` Worker URL |
| `id0000000000` | `web/app/links.ts`, README, macOS/Linux "get mobile app" links | Your App Store app id |
| `github.com/Ragul84/Hypurr` | README, installer, update manifests, Homebrew | Rename the GitHub repo to `Hypurr` (GitHub redirects the old name) |
| `Ragul84/homebrew-hypurr` | `packaging/homebrew`, workflows | Create this tap repo + a `TAP_TOKEN` secret |

## Generating update signing keys

Host updates (standalone `hypurr-host`):

```sh
python3 - <<'PY'
import base64, os
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
seed = os.urandom(32)
pub = Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
print("HOST_UPDATE_SIGNING_KEY (secret):", base64.b64encode(seed).decode())
print("host-public-key.txt:            ", base64.b64encode(pub).decode())
PY
```

Store the seed as the `HOST_UPDATE_SIGNING_KEY` repository secret and commit
only the public key. For the Mac app run Sparkle's `generate_keys`, store the
private export as `SPARKLE_PRIVATE_KEY`, and put the public key in both
`macos-public-key.txt` and `SUPublicEDKey`.

## Re-enabling releases

Releases are switched off until the secrets above exist: `auto-tag.yml` and
`release-macos.yml` only run by hand (`workflow_dispatch`), and the `tags:`
trigger in `host.yml` is commented out. Restore those triggers once
`APPLE_*`, `SPARKLE_PRIVATE_KEY`, `HOST_UPDATE_SIGNING_KEY` and `TAP_TOKEN` are set.

## Other services

- **Cloudflare**: deploy `cloud/` (D1, Durable Objects, rate limits, cron) and
  `relay/` (APNs push) with `wrangler`.
- **Clerk**: a development and a production instance; point the webhook at
  `https://api.<your domain>/v1/webhooks/clerk`.
- **Apple**: APNs key for `relay/`, Developer ID certificate + notarization for the DMG.
- **TURN** for Remote screen (see `cloud/` TURN credentials endpoint).
