# Windows signing and the Microsoft Store (Rs 0 routes)

Two free routes, prepared in CI but switched off until you finish the steps that only you can do:

| Route | Cost | What users see | Status in this repo |
|---|---|---|---|
| **A. Microsoft Store (MSIX)** | Free (individual account) | Installed from the Store, no SmartScreen warning; the Store signs the package | CI builds `Hypurr-<version>.msix` (artifact `hypurr-windows-msix`). Needs your Store identity values. |
| **B. SignPath Foundation (OSS)** | Free | `HypurrSetup-<version>.exe` signed; publisher shows as **SignPath Foundation** | Optional CI steps run only when the SignPath secret + variables exist. |
| PFX certificate (existing) | Paid | Signed with your own certificate | `packaging/windows/sign.ps1`; runs only with `WINDOWS_CERT_PFX_BASE64`. |

Not options at Rs 0: Azure Artifact Signing (paid; individuals only in US/Canada), OV certificates (~US$150–300/yr),
Certum Open Source (~€49/yr). Submitting the plain `.exe` to the Store also needs it to be signed by a trusted CA, so
the free Store route is **MSIX**.

Versions: `apps/windows/Cargo.toml` and `host/Cargo.toml` must match (CI checks); the installer is
`HypurrSetup-<version>.exe` and the MSIX `Hypurr-<version>.msix` (`x.y.z.0` inside).

---

## A. Microsoft Store (MSIX)

Facts (checked 27 Oct 2026):
- Individual developer registration is **free** in the new flow, which must start at https://storedeveloper.microsoft.com,
  with ID verification (government ID + selfie), ~200 markets.
  Sources: [Open a developer account](https://learn.microsoft.com/en-us/windows/apps/publish/partner-center/open-a-developer-account),
  [Free registration for individuals](https://learn.microsoft.com/en-us/windows/apps/publish/whats-new-individual-developer),
  [Windows blog, 10 Sep 2025](https://blogs.windows.com/windowsdeveloper/2025/09/10/free-developer-registration-for-individual-developers-on-microsoft-store/).
- Win32 apps can ship as **MSIX** (Microsoft hosts and signs it for free, Store auto-updates) or as an **EXE/MSI**
  (you host it and it must be signed with a CA certificate you pay for).
  Source: [Distribute your Win32 app through the Store](https://learn.microsoft.com/en-us/windows/apps/distribute-through-store/how-to-distribute-your-win32-app-through-microsoft-store).
- Store policy (v7.20, effective 22 Oct 2026): individual accounts are for publishing not tied to a business; "a company
  account is required if your product requires financial information for primary functionality"; Win32 apps need a
  privacy policy. Source: [Store Policies](https://learn.microsoft.com/en-us/windows/apps/publish/store-policies).
  Hypurr's PC app is free and takes no payment details on Windows (credits are bought in the Android app), so an
  individual account should fit; say so in the certification notes.

### What you do (checklist)
- [ ] Open https://storedeveloper.microsoft.com → **Get started for free** → **Individual developer**; sign in with your
      personal Microsoft account; verify with your government ID + selfie (on your phone).
- [ ] Partner Center → Apps and games → **New product → MSIX or PWA app** → reserve the name **Hypurr**
      (if taken, e.g. "Hypurr – phone remote for coding agents").
- [ ] Product → **Product identity**: copy `Package/Identity/Name`, `Package/Identity/Publisher`, `PublisherDisplayName`.
- [ ] GitHub → Settings → Secrets and variables → Actions → **Variables** (not secrets; they are public in the package):
      `STORE_IDENTITY_NAME`, `STORE_PUBLISHER`, `STORE_PUBLISHER_DISPLAY`.
- [ ] Run `windows-app` on main; download artifact `hypurr-windows-msix` → `Hypurr-<version>.msix`.
- [ ] Submission: Pricing **Free**; Properties category **Developer tools**; Age rating questionnaire;
      Store listing (text below, screenshots from the `windows-app-screenshots` artifact, 1366×768 or larger);
      privacy policy URL; **Packages** → upload the `.msix`.
- [ ] Certification notes: explain `runFullTrust` (below). Submit. (Agent does not submit.)

Without the three variables CI builds a TEST-identity MSIX that the Store rejects on purpose.

Manifest: `packaging/windows/msix/AppxManifest.xml` (placeholders `__STORE_*__`), built by
`packaging/windows/msix/build.ps1` with `makeappx`. Capabilities: `runFullTrust` (desktop app that starts the
`hypurr-host.exe` helper), `internetClient`, `privateNetworkClientServer` (phone pairs over the local network).

**Certification notes (paste):**
> Hypurr is a desktop companion for the Hypurr Android app. It runs as a full-trust desktop app with a tray icon and a
> local helper (hypurr-host.exe) that runs coding agents the user already has on this PC, and pairs with the user's
> phone over an end-to-end encrypted connection. runFullTrust is required to start the helper and the user's own
> command-line tools. No payment is taken in the Windows app. To test: install, open Hypurr from Start; the window shows
> a pairing QR code. The phone app is optional for launching.

**Known MSIX differences to test before submitting:** "Start with Windows" uses the HKCU Run key in the EXE install;
packaged apps should use a `StartupTask` extension instead (add one if the Store build should support it). Files under
`%USERPROFILE%\.hypurr` are not virtualized and keep working.

### Store listing text (draft)
- **Name:** Hypurr
- **Short description:** Run your coding agents from your phone.
- **Description:**
  > Hypurr lets you start, watch and approve the coding agents on your PC from your phone.
  > Install Hypurr on your Windows PC, scan the QR code with the Hypurr app on Android, and you can:
  > • start tasks for Claude Code, Codex, Cursor and other agents already on your PC, or use Hypurr Agent;
  > • follow each agent's progress live and approve or deny what it wants to run;
  > • install supported agents in one tap and sign in to them from your phone.
  > Your phone and PC talk over an end-to-end encrypted connection. Your code stays on your PC.
  > Hypurr is free and open source (MIT): https://github.com/Ragul84/Hypurr
- **Features:** Pair with a QR code · Live agent progress · Approve or deny actions · One-tap agent installs · Encrypted phone–PC connection · Open source
- **Keywords:** coding agent, remote, phone, AI, developer tools
- **Privacy policy URL (required):** https://hypurr-legal.mindgains.ai/privacy
- **Website / support:** https://github.com/Ragul84/Hypurr · https://github.com/Ragul84/Hypurr/issues
- **Copyright:** © 2026 Ragul84

---

## B. SignPath Foundation (free OSS code signing)

Facts (checked 27 Oct 2026, [signpath.org/terms](https://signpath.org/terms), apply at [signpath.org/apply](https://signpath.org/apply)):
- OSI-approved licence for all components, no commercial dual-licensing, no proprietary components. *Hypurr: MIT.*
- Actively maintained; **already released** in the form to be signed; functionality described on the download page.
- The certificate is issued to **SignPath Foundation** (it is the publisher shown by Windows).
- Sign only your own binaries, built from your source in a verifiable CI build; every release is approved manually.
- All team members use **MFA** on GitHub and SignPath; roles: authors, reviewers, approvers.
- A **code signing policy** on the home page and download/release pages with: "Free code signing provided by
  SignPath.io, certificate by SignPath Foundation", the team roles, and a privacy statement or link.
- Software that sends user data to systems the user didn't choose must describe it in a privacy policy, **show that
  policy during installation**, and offer install options to turn those functions off. Must provide uninstall.
- Signed binaries must set product name = project name and the same product version in each build.
- SignPath verifies the project's **reputation** once at the start.
- CI integration: `signpath/github-action-submit-signing-request@v3`
  ([docs](https://docs.signpath.io/trusted-build-systems/github)).

### What you do (checklist)
- [ ] MFA on your GitHub account (required).
- [ ] Apply at https://signpath.org/apply with the text below.
- [ ] Before applying, consider fixing the likely review points: (1) show the privacy policy link in the installer and
      let the user turn off the optional cloud features at install time; (2) set Windows file metadata
      (ProductName "Hypurr", ProductVersion) on `hypurr.exe` / `hypurr-host.exe`; (3) link the code signing policy from
      the README and the GitHub release pages.
- [ ] After approval, in SignPath: create project `hypurr`, signing policy `release-signing`, artifact configurations
      `app` (zip with `hypurr.exe` + `hypurr-host.exe`, PE signing) and `installer` (zip with `HypurrSetup-*.exe`),
      install the SignPath GitHub App / trusted build system for this repo, and create a CI user API token.
- [ ] GitHub → Actions **secret** `SIGNPATH_API_TOKEN`; **variables** `SIGNPATH_ORGANIZATION_ID` (and only if your
      slugs differ: `SIGNPATH_PROJECT_SLUG`, `SIGNPATH_POLICY_SLUG`, `SIGNPATH_APP_CONFIG`, `SIGNPATH_SETUP_CONFIG`).
- [ ] Each release: approve the two signing requests in SignPath (CI waits up to 1 hour for each).

CI order when on: build → (PFX sign if set) → SignPath signs app + host → Inno Setup builds the installer from the
signed files → SignPath signs the installer → silent install/uninstall test.

### Application text (draft; paste into signpath.org/apply)
- **Project name:** Hypurr
- **Repository:** https://github.com/Ragul84/Hypurr (public) · **Licence:** MIT (OSI-approved; no dual licensing)
- **Homepage / download page:** https://github.com/Ragul84/Hypurr/releases
- **Description:** Hypurr is an open-source phone remote for AI coding agents. The Windows part is a tray app
  (`hypurr.exe`) and a local helper (`hypurr-host.exe`) that run the coding agents the user already has on their PC
  (e.g. Claude Code, Codex, Cursor) and pair with the Hypurr Android app over an end-to-end encrypted connection, so
  the user can start tasks, follow progress and approve actions from their phone.
- **What to sign:** `hypurr.exe`, `hypurr-host.exe` and the Inno Setup installer `HypurrSetup-<version>.exe`, all built by
  the public GitHub Actions workflow `.github/workflows/windows-app.yml` from this repository.
- **Upstream code:** Hypurr started from Codync (MIT, https://github.com/leepokai/Codync) and is maintained in this
  repository; no third-party binaries are signed. Agents the user installs are downloaded by the user's choice and are
  not part of the signed package.
- **Team & roles:** Ragul84 — author, reviewer and approver; MFA enabled on GitHub. Changes land through pull requests
  with CI.
- **Code signing policy:** https://hypurr-legal.mindgains.ai/code-signing
- **Privacy policy:** https://hypurr-legal.mindgains.ai/privacy
- **Existing release:** https://github.com/Ragul84/Hypurr/releases (prereleases with the Windows installer).

### Possible blockers
- **Reputation:** SignPath checks project reputation once; a young project with few users/stars may be asked to wait.
- **Privacy at install time:** the optional cloud features (account relay, Hypurr gateway at mindgains.ai) mean the
  installer should show the privacy policy and let users turn them off; it doesn't yet.
- **Publisher name:** Windows will show "SignPath Foundation", not Hypurr/Ragul84.
- **SmartScreen:** a new certificate still builds reputation over downloads; the Store route avoids this.
