# Development and validation

For release updates, automatic installation and host rollback, see [updates](updates.md).

Run commands from the repository root unless a block changes directory. See [file structure](../architecture/file-structure.md) for ownership and [environment configuration](environments-and-deployment.md) before testing cloud access.

## Apple apps

Install Xcode and XcodeGen, then generate the project from its source:

```sh
xcodegen generate --spec apps/project.yml
xcodebuild build -project apps/Codync.xcodeproj -scheme macOS -configuration Debug -derivedDataPath build/dd
```

Use the `iOS` scheme with a connected device or simulator in Xcode. CLI builds accept `-destination 'platform=iOS,id=<device-id>'`. Do not edit `project.pbxproj` directly. Keep normal simulator signing: Clerk uses Keychain, and unsigned simulator builds can fail initialization with OSStatus -34018.

Before launching a newly built Mac app, quit running copies, including DerivedData copies:

```sh
osascript -e 'tell application id "com.pokai.Codync" to quit'
pkill -x Codync
open build/dd/Build/Products/Debug/Codync.app
```

`pkill` can return nonzero when no process exists.

The Mac app compares the running host's executable path and SHA-256 fingerprint
with its bundled host, so reopening a rebuild replaces the service even when the
version number has not changed. **Restart host** reinstalls the service from this
app and waits for the old host's data lock to be released. If a manually started
host still owns that data directory, installation fails instead of reporting a
successful restart; stop that host in its terminal and retry. Hosts attached using
`CODYNC_PORT` are managed manually.

On SIGTERM the host stops its bots without waiting for open SSE or WebSocket
connections. ACP adapters run in separate process groups; stopping an adapter
also kills tools and MCP servers that remain in its group. Processes that detach
into their own sessions and unrelated hosts using other data directories are
outside this cleanup.

After rebuilding the host, restart the launch agent (it runs
`build/dd/.../Codync.app/Contents/MacOS/codync-host`) and kill any `codync-host`
still running from a different path. Test hosts you start yourself must be
stopped when done:

```sh
launchctl kickstart -k gui/$(id -u)/com.pokai.codync.host
pgrep -fl codync-host
```

Install the device build and replace the old iPhone process (substitute its device ID):

```sh
xcrun devicectl device install app --device <device-id> build/dd/Build/Products/Debug-iphoneos/Codync.app
xcrun devicectl device process launch --terminate-existing --device <device-id> com.pokai.Codync.ios
```

Unlock the phone when required. Build, install, launch, and visual inspection are separate checks; report which actually completed.

## Component checks

| Component | Commands | Requirements |
| --- | --- | --- |
| Host | `cd host && cargo build` | rustup; `rust-toolchain.toml` pins the version CI uses (a Homebrew `rust` ahead of rustup on PATH ignores it) |
| Host checks | `cd host && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test` | Local agent credentials are unnecessary for unit tests |
| Shared Swift | `swift test --package-path kit` | Swift 6 / Xcode |
| Cloud | `cd cloud && npm ci && npm test && npm run typecheck` | Node and npm; CI uses Node 24 |
| Cloud integration | `cd cloud && env -u CODYNC_CLOUD npm run e2e` | Built host; see [Cloudflare testing](cloudflare-testing.md) |
| APNs relay | `cd relay && npm ci && npm test && npm run typecheck` | Separate package from `cloud/` |
| Linux desktop | `cd apps/linux && cargo test` | GTK 4 and libadwaita development packages |

### Screen helpers

Enable the repository's staged-file checks once per checkout:

```sh
git config core.hooksPath .githooks
```

When a screen helper changes, the pre-commit hook checks Linux Rust formatting
and parses the macOS Swift sources (on macOS). The platform builds remain in
GitHub Actions: Linux requires GStreamer development packages, and macOS builds
the `Screen` Xcode target against WebRTC.

Host development: `cargo run --manifest-path host/Cargo.toml -- serve`. Avoid competing with an installed host on port 19222; isolated tests should use a temporary `CODYNC_HOME` and another port. Stop test hosts when finished.

## Visual checks

Follow [UI conventions](../design/ui-conventions.md) for native toolbar behavior and [widget design](../design/mobile-widgets.md) for previews. Widget images can be rendered with `python3 tools/render-widgets.py`; output is under `build/widget-previews/`.

For releases, increment `CURRENT_PROJECT_VERSION` before another App Store upload. Versions are defined in `apps/project.yml`; keep the host package version aligned with `MARKETING_VERSION`, then regenerate the Xcode project.

Uploading from the command line: archive the `iOS` scheme in Release (`xcodebuild … -destination 'generic/platform=iOS' -archivePath build/archive/Codync-iOS.xcarchive -allowProvisioningUpdates archive`), export an IPA with an ExportOptions plist (`method` app-store-connect, `destination` export, automatic signing, the team id), then `xcrun altool --upload-app -f build/archive/ipa/Codync.ipa -t ios --apiKey <key id> --apiIssuer <issuer id>` with an App Store Connect API key in `~/private_keys`. `-exportArchive` with `destination` upload fails ("Failed to Use Accounts") because xcodebuild has no Apple ID session. App Store Connect then processes the build for a few minutes before it can be attached to a version.
