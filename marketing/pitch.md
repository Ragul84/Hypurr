# Hypurr pitch

Free remote control of a coding agent from a phone is table stakes: Happy, Omnara, Paseo, Vicoa
and the official Claude and ChatGPT apps all do it. Hypurr never leads with "control your agent
from your phone". It leads with three things the others don't do.

## One line

**Coding agents, safe for anyone.** Ask in plain words. Hypurr keeps it safe, proves it works,
and comes with free AI.

## The three pillars

1. **Safe for non-experts.** Every approval in plain words with a risk level. The agent stays in
   one folder, never touches production (pushes to main are refused), and every step has a
   checkpoint you can roll back to with one tap. Voice approvals in eight Indian languages never
   approve a risky action without a tap.
2. **Proof it works.** *Show me it works* runs the app and returns screenshots. *Trace a click*
   follows a button to the function, API and file:line. *Page map* lists every screen and its APIs.
   *What changed* shows each file, +/− lines and the diff.
3. **Free AI built in.** *Start free with your own account*: sign in once and *Hypurr Free* runs on it at no cost. Label it
   every time: "For trying out, not for company code." Claude Code or Codex you already have work
   at no extra cost.

## Words to use / avoid

- Use: plain words, safe, one folder, never touches production, roll back, proof, Show me it works,
  Trace a click, Page map, Hypurr Free.
- Avoid: "remote control", "terminal on your phone", model or provider names behind Hypurr Free or
  Hypurr Agent, "unlimited", "free forever".

## Where this copy lives

- App: onboarding first screen, pairing screen, Bots empty state, Tasks empty state, Spend
  (`apps/android/.../ui/Pitch.kt` holds the shared strings).
- Store: `metadata/android/en-IN/` (Google Play), `metadata/app-info/`, `metadata/version/` (App Store).
- `README.md` top section.
