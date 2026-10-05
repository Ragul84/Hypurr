# Hypurr design system — Sunfield Panel

**Direction B.** One token set for every client: web (`web/app/globals.css`),
SwiftUI (`kit/.../Theme.swift`), GTK (`apps/linux`), Android
(`apps/android/.../Theme.kt`), and the TUI. Change a token in all of them together.

Category defaults refused: black+teal AI neon, purple mesh, Material letter-circles,
instrument/terminal density, congested meta columns.

## Concept

Hypurr is a Teenage Engineering synth panel: sunflower colour field, cream plates
with a chunky “press” shadow, forest-green primary actions, Cabinet Grotesk
ExtraBold titles, and friendly cat-face bot tiles.

## Colour

| Token | Light (default) | Dark (warm forest, not black) |
|---|---|---|
| ground / background | `#F2B90D` sunflower | `#0E4A38` deep forest |
| raised / surface | `#FFF8E8` cream | `#143D30` forest plate |
| cream (cards always) | `#FFF8E8` | `#1A4A3A` |
| ink / text | `#17140A` | `#FFF8E8` |
| ink-2 / secondary | `#5C5640` | `#C8E0D4` |
| ink-3 / tertiary | `#8A8168` | `#7A9E8E` |
| rule / border | `rgba(23,20,10,0.12)` | `rgba(255,248,232,0.12)` |
| accent (primary action) | `#0E4A38` forest | `#F2B90D` sunflower |
| on-accent | `#FFF8E8` | `#0E4A38` |
| negative / danger | `#A1281C` | `#F5A090` |
| positive / success | `#0E4A38` | `#5DDB9A` |
| attention / Needs-you | `#0E4A38` (chip on cream) | `#F2B90D` |

Accent role: **primary action** (Allow once, New, selected tab, send).
Needs-you uses a forest chip on cream (light) or sunflower chip on forest (dark).
Danger red only for failed / Deny emphasis.

**No teal. No cyan. No purple.**

## Surfaces & shape

- Content cards / bot rows: **18–22px** radius, cream fill.
- Chunky **press shadow**: `0 4px 0 rgba(23,20,10,0.10)` light; `0 4px 0 rgba(0,0,0,0.35)` dark.
- Needs-you hero: **26px** radius, stronger press `0 8–12px 0`.
- Nav bar: ink pill (light) or sunflower/cream pill (dark), ~24px radius.
- Buttons: **16px** radius; primary = forest fill.

## Type

- **Display / UI:** Cabinet Grotesk (Fontshare, ITF Free Font License — app embedding allowed; do not modify/redistribute the font files). Weights 400 / 500 / 700 / 800.
- Fallback OFL: Bricolage Grotesque (bundled as spare).
- Titles: ExtraBold 34–40, tracking −0.04em.
- Row titles: ExtraBold 17.
- Body: Medium 15.
- Meta: Bold 12–13.
- Sentence case everywhere. No ALL CAPS eyebrows.

## Spacing

- Side margin: **18–22px**.
- Between bot cards: **8–10px**.
- Hero → list rest: **14–22px**.
- Max ~2 lines per bot row. Spacious over dense.

## Icons

Custom chunky rounded stroke set (`docs/brand/icons/`). Stroke ~2.1–2.25, round caps.
Bot identity: cat-face tiles (ink / cream / amber fills), not letter circles.

## Signature interaction — Press Allow

1. Tap **Allow once**.
2. Button presses down (translateY +2–4px, shadow collapses) → forest flash → approval card compresses into a live cream row with a cat blink.
3. Reduced motion: 150ms crossfade; still fire success haptic.

## Motion language (Sunfield)

Friendlier restyle of the motion PR catalogue:

| Moment | Sunfield treatment |
|---|---|
| Thinking | Cat ear twitch / blink on the bot tile; soft ink-dot typing (not cyan scan) |
| Creating a bot | Cream tiles assemble into cat face on sunflower |
| Pairing | Forest label “lights on” with two soft flickers |
| Approvals | Press-down Allow; Deny shears cream card sideways |
| Loading | Soft cream shimmer (no rain streaks) |
| Launch | Sunflower field fades in; title stamps with press shadow |

Springs: keep M3 Expressive spatialFast / Default / Slow / effects; press scale **0.94** (chunkier).

## Dark mode

Warm **deep forest** ground `#0E4A38` with sunflower accent actions — never near-black + teal.
