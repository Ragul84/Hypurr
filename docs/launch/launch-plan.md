# Launch plan (written only, nothing posted)

Status: **plan only.** Nothing in this document has been posted, submitted or scheduled.
Every step that posts publicly needs the owner's go-ahead on the day. Budget: ₹0 (all
channels below are free).

## 0. Ready before launch (gates)

- [ ] Play Store listing live (internal → closed testing → production; see [play-store.md](../release/play-store.md)).
      Closed testing for a new personal account needs 12 testers for 14 days.
- [ ] Windows installer signed, or SmartScreen steps on the download page ([windows-signing.md](../release/windows-signing.md))
- [ ] README matches reality, landing page up at hypurr.mindgains.ai, privacy and terms pages
- [ ] 60–90 second demo video: phone → "add a dark mode toggle" → approval → screenshots proof → PR
- [ ] 5 phone screenshots (from `apps/android/screenshots`) and one GIF for posts
- [ ] Gateway credits and the free own-account path tested end to end on a clean phone
- [ ] "Rate Hypurr" prompt shipped (asks after the 3rd finished task, max once per 120 days)
- [ ] Issue templates and a pinned "Start here" discussion on GitHub

## 1. Week −2: soft launch (friends and testers)

- Personal network: 20–30 developers and non-developers in India, WhatsApp/Telegram one-to-one
  (not group spam). Ask for one task each and one honest line of feedback.
- Fix the top 3 problems they hit. Collect 3 short quotes (with permission) for the landing page.

## 2. Launch day (Tuesday or Wednesday, 8:30 PM IST = morning US East)

| Channel | Draft angle | Notes |
|---|---|---|
| Show HN | "Show HN: Hypurr – run Claude Code/Codex from your Android phone, safely" | Post link to GitHub, first comment explains why (non-experts, approvals in plain words, undo). Stay and answer for 6 hours. |
| Reddit r/ClaudeAI, r/ChatGPTCoding, r/androiddev, r/developersIndia | Short demo video + what's different | Read each sub's self-promo rules first; one sub per day, not all at once. |
| X / LinkedIn (owner's accounts) | 30 s clip + 3 bullets | Tag no one unprompted. |
| Product Hunt | Schedule for the following week | Needs a hunter or self-hunt; gallery = screenshots + video. |
| Dev.to / Hashnode | "How I made coding agents safe for non-experts" write-up | Technical: worktree branches, checkpoints, approval risk levels. |

## 3. Week +1 to +4

- Answer every issue within 24 hours; label `good first issue` for contributors.
- One short post a week: a real task done from the phone (before/after).
- Indian languages angle: a Hindi/Tamil voice demo for Indian tech communities.
- Listing directories (free): AlternativeTo (vs. Happy, Omnara, Claude Code mobile), awesome-claude-code, awesome-acp lists.

## 4. What to measure

| Metric | Where | Week 4 goal |
|---|---|---|
| GitHub stars | repo | 300 |
| Play installs | Play Console | 500 |
| Day-7 retention | Play Console | 25% |
| Paired computers | gateway D1 (count of paired hosts) | 150 |
| Play rating | Play Console | ≥ 4.3 with 20+ ratings |
| Paid credit buyers | RevenueCat | 10 |

## 5. Don'ts

- No paid ads, no bought reviews or stars, no incentivised ratings (Play policy forbids it).
- Don't ask for ratings in posts; the in-app prompt handles it.
- Don't claim features that are in open PRs as shipped.
- Don't post in communities that ban self-promotion.
