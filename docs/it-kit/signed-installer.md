# Signed installer

| Platform | How it is signed | How to check |
| --- | --- | --- |
| macOS (`hypurr-macos.dmg`) | Developer ID Application, hardened runtime, notarized by Apple (`.github/workflows/release-macos.yml`) | `codesign --verify --deep --strict -v /Applications/Hypurr.app` and `spctl -a -vv /Applications/Hypurr.app` |
| Linux / macOS CLI (`hypurr-*.tar.gz`) | SHA-256 checksum published next to each archive | `shasum -a 256 -c hypurr-…tar.gz.sha256` |
| Android | Signed with the release key (Play / APK) | `apksigner verify --print-certs` and compare with the fingerprint we publish |

**Needs a decision before roll-out:** the Developer ID and Android release certificates
must be the company's (or the publisher's) own, and their fingerprints published on the
website. Windows builds are not signed yet (no Authenticode certificate). Linux archives
have checksums but no signature; a Sigstore/GPG signature is planned.

Updates: the host installs updates only from this project's GitHub releases over HTTPS
(it does not yet verify the `.sha256` itself; gap). In local-only mode it does not check for updates at all; IT ships new versions.
