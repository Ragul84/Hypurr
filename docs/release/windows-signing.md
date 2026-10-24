# Windows code signing

`windows-app.yml` signs `hypurr.exe`, `hypurr-host.exe` and `HypurrSetup-*.exe` via `packaging/windows/sign.ps1`
when the repo secrets `WINDOWS_CERT_PFX_BASE64` + `WINDOWS_CERT_PASSWORD` exist; otherwise it skips signing.
(Cloud/HSM certs such as SignPath or Certum SimplySign need their own action instead of a PFX; swap the step when one is approved.)

| Option | Cost | Fits Hypurr? |
|---|---|---|
| SignPath Foundation (OSS) | Free | Likely: repo is MIT (OSI), no commercial dual-licence. Needs: an existing public release (test release exists), MFA on GitHub + SignPath, this policy page published, verifiable CI builds, manual approval per release. Publisher shows as "SignPath Foundation". Paid gateway is server-side; the signed binaries must contain no proprietary code. |
| Certum Open Source Code Signing (cloud, SimplySign) | ~€49/yr (+VAT) | Individual OSS developers, ID check; signing needs a one-time code from the phone app, so CI automation is awkward. |
| Azure Artifact Signing (Trusted Signing) | $9.99/mo | **Not available**: individuals only in US/Canada (onboarding paused), organizations only in US/CA/EU/UK etc. India not eligible. |
| Microsoft Store (MSIX) | Free account for individuals | Store re-signs MSIX; would need an MSIX package (not built yet). EXE submissions must already be signed. |
| OV certificate (Sectigo/DigiCert resellers) | ~$150–300/yr | Works anywhere, for a company. |

None of these gives instant SmartScreen trust; reputation builds as signed downloads accumulate.

## SignPath Foundation application (draft)
- Project: Hypurr — https://github.com/Ragul84/Hypurr — MIT licence
- Description: Windows tray app + host that runs the user's own coding agents (Claude Code, Codex) and pairs with the Hypurr phone app over an end-to-end encrypted local connection.
- Download page: https://github.com/Ragul84/Hypurr/releases
- Artifacts: hypurr.exe, hypurr-host.exe, HypurrSetup-x.y.z.exe (Inno Setup), built by GitHub Actions `windows-app.yml`.
- Code signing policy: https://hypurr-legal.mindgains.ai/code-signing ; Privacy: https://hypurr-legal.mindgains.ai/privacy
- Roles: author/reviewer/approver Ragul84 (MFA enabled).
