# Hypurr motion language

Wet-asphalt neon motion: **sharp, dark, electric**. Outside-app refs stay Linear
(calm density), Leica (precision body), neon in rain (one lit signal). Flat cyan
light only — **no glow blobs, no purple, no bouncing three-dots, no confetti**.

Every moment honours **Reduce Motion / `prefers-reduced-motion`**: swap loops for
a static frame or a 150ms crossfade. Photosensitivity: neon “flicker” is at most
**3 flashes/sec**, low contrast, never full-screen strobe.

Shared with Android Compose (`ui/motion/`), SwiftUI (`HypurrKit/Design/Motion+Signal.swift`),
web (`web/app/globals.css` + `components/motion.ts`), and the TUI thinking scan.

## Tokens

### Durations (ms)

| Token | ms | Use |
|---|---|---|
| `strike` | 120 | Signal band flash, neon flicker steps |
| `snap` | 180 | Crossfade labels, caret blink half-cycle |
| `settle` | 280 | Plate collapse, chip land, shared-axis slide |
| `draw` | 420 | Rule draw L→R, scan-line sweep one pass |
| `assemble` | 720 | Cat-from-rain assembly, launch flicker-on |
| `loop` | 1600 | Thinking scan loop (one full sweep) |
| `reduced` | 150 | Universal reduced-motion crossfade |

### Easings / springs

| Token | Spec | Use |
|---|---|---|
| `linear` | linear | Scan band, rule draw, strike flash |
| `emphasized` | cubic-bezier(0.2, 0, 0, 1) | Shared-axis enter/exit |
| `spatialFast` | spring 1400 / 0.6 | Press 96%, chip settle |
| `spatialDefault` | spring 380 / 0.8 | Layout, plate collapse |
| `effects` | spring 1600 / 1.0 | Colour/opacity (no overshoot) |
| `signalStrike` | spring ~900 / 0.75 | Allow collapse |

Press scale remains **0.96**. Content plates stay **0–4px** radius; motion never
introduces soft squircles.

### Colour in motion

| Role | Token | Notes |
|---|---|---|
| Live / scan / caret | `accent` `#00D4C8` / `#007A73` | Flat; opacity 0.55–1.0 only |
| Needs-you label | `attention` amber | Label strike only |
| Deny shear | `negative` | 1px hairline |
| Ink resolve | `ink-3` → `ink` | Streaming text |
| Rain streak | accent @ 12–22% | Diagonal 1px, never glow |

## Catalogue

### 1. Thinking — Scan line
A 2–4px cyan band sweeps left→right across a short rule under the bot’s working
row (or across the orb rail), like a neon tube flickering on in rain. Beside it,
mono phase text cross-fades (“Reading files” → “Planning” → “Editing App.tsx”)
on a ticker of `snap`ms. Loop `1600`ms. Reduced: static band at 40% + static phase.

### 2. Streaming text
Incoming agent text resolves word/chunk from `ink-3` to `ink` over `snap`ms.
A **1px cyan caret** leads the frontier; blink `snap` half-cycle when idle at end.
Reduced: instant ink, no caret blink (steady caret ok).

### 3. Tool running
Mono command line; a **progress hairline** (1px accent) fills L→R under the
command. File/path chips tick in with `spatialFast` (opacity + 4px rise). Reduced:
hairline at final %, chips appear static.

### 4. Creating a bot
Diagonal rain streaks (1px accent @ ~18%) converge into the cat glyph silhouette
over `assemble`ms; then a 4px signal band flashes once (`strike`) to confirm.
Reduced: glyph fades in 150ms, band static.

### 5. Connecting / pairing
Rain streaks converge to a point; Direct/Relay status **lights up** like a neon
sign: 2 quick flickers (`strike` on/off) then steady. Cap ≤3 flashes/sec, ΔL low.
Reduced: steady on, no flicker.

### 6. Needs you arriving
Plate drops in from top (~8px + fade, `emphasized`/`settle`); amber **Needs you**
label does a 4px left-to-right strike. Light haptic where available. Reduced: fade only.

### 7. Signal strike (Allow)
Exists: press 96% → 4px cyan band full-width (`strike` 120ms) → plate collapses to
live row (`signalStrike` ~280ms) → 3px left rail remains. Reduced: 150ms crossfade.

### 8. Deny
Plate shears sideways 4px (`snap`) and dims to 55%; a 1px `negative` hairline
draws on the leading edge. Reduced: dim crossfade, no shear.

### 9. Task done
A cyan rule draws L→R under the summary (`draw` 420ms); checkpoint chip settles
with `spatialFast`. Reduced: rule + chip appear static.

### 10. Screen transitions
Shared-axis: 8px slide + fade, `emphasized` / `settle`. No bouncy scale.
Reduced: 150ms fade.

### 11. Pull-to-refresh / skeletons
**Rain-streak shimmer**: diagonal 1px cyan strokes drift across raised plates at
low opacity (not grey pulse). Reduced: static plate, no drift.

### 12. App launch
Cat glyph, single neon flicker-on (2 frames then steady), then bots list shared-axis
in. Reduced: glyph → list fade.

## Implementation map

| Moment | Android | Web | Swift | TUI |
|---|---|---|---|---|
| Thinking scan | `ScanLine` + `PhaseTicker` | CSS `.scan-line` | `ScanLineView` | spinner + phase |
| Streaming | `StreamingText` | CSS caret | `StreamingText` | — |
| Tool running | `ToolProgress` | CSS hairline | optional | — |
| Create bot | `CatAssemble` | CSS | optional | — |
| Pairing neon | `NeonStatus` | CSS | optional | — |
| Needs you | `NeedsYouEnter` | CSS | optional | — |
| Signal strike | `SignalStrike` (polish) | — | polish | — |
| Deny | `DenyShear` | CSS | optional | — |
| Task done | `DoneRule` | CSS | optional | — |
| Transitions | nav anim | framer | navigation | — |
| Skeleton | `RainShimmer` | CSS | optional | — |
| Launch | `LaunchFlicker` | — | optional | — |

## Proof

Clips under `/workspace/hypurr-shots/motion/` (and linked from the PR): short
2–6s MP4/GIF or frame strips for thinking, strike, deny, streaming, pairing,
create-bot, skeleton.
