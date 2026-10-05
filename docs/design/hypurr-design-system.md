# Hypurr design system

**Wet-asphalt neon** — one token set for every client: the web site
(`web/app/globals.css`), SwiftUI (`kit/Sources/HypurrKit/Design/Theme.swift`,
`ColorFlow.swift`, `Motion.swift`), GTK (`apps/linux/src/main.rs` palette + CSS),
Android (`apps/android/.../ui/theme/Theme.kt`), and the terminal UI.
Change a token in all of them together.

## Colour

| Token | Dark (default) | Light |
|---|---|---|
| ground / background | `#05070A` | `#F4F6F5` |
| raised / surface | `#0B1211` | `#FFFFFF` |
| ink / text | `#E8FFFC` | `#0A1210` |
| ink-2 / secondary | `#7FA8A3` | `#4A5E5A` |
| ink-3 / tertiary | `#3D5552` | `#8A9995` |
| rule / border | `#14201E` | `#D5DEDB` |
| accent | `#00D4C8` | `#007A73` |
| on-accent | `#021412` | `#F4F6F5` |
| negative / danger | `#FF6B5A` | `#C23B2E` |
| positive / success | `#5EAD8A` | `#1F7A55` |
| attention / warning | `#FFB020` | `#B86A00` |

Accent role: **live / primary action** (Allow, Direct, active tab, live editing).
Attention amber only on the **"Needs you"** label. Negative red only for failed / deny.

**Signal language** (replaces the old violet → magenta → cyan colour flow): flat
teal fills and a 4px cyan **signal band** on live / Needs-you plates. No mesh,
no purple, no soft UI glow.

## Surfaces

- Sharp plates: **0–4px** radius on content cards, approvals, bubbles.
- Nav glass may stay ~22px pill on phone only.
- Optional thin diagonal rain streaks (1px cyan ~12–22% opacity, never glow).

## Signature interaction — Signal strike

1. User taps **Allow once** on a Needs-you plate.
2. Press scale 96% → 4px cyan band flashes full-width (~120ms) → plate collapses
   to a thin live row (~280ms spring) → 3px left rail remains.
3. Reduced motion: crossfade 150ms; still fire success haptic where available.

## Motion

M3 Expressive springs (stiffness / damping ratio, mass 1):

| Token | Values | Use |
|---|---|---|
| spatialFast | 1400 / 0.6 | knobs, chips, press feedback |
| spatialDefault | 380 / 0.8 | movement, resizing |
| spatialSlow | 200 / 0.8 | sheets, big panels |
| effects | 1600 / 1.0 | colour, opacity (no overshoot) |
| signalStrike | ~900 / 0.75 | approval collapse |
| bouncy | 0.45 s, bounce 0.38 | confirmations, badges |

Press scale is 0.96.

## Type

- **Display:** Archivo ExtraBold expanded where fonts can be bundled; else system
  with heavier weight / tighter tracking. (Font bundling may follow in a later PR.)
- **UI:** Geist or system-ui.
- **Mono:** JetBrains Mono / existing mono.

## Motion language

See [`hypurr-motion.md`](./hypurr-motion.md) for the wet-asphalt neon animation catalogue,
tokens, and reduced-motion rules. Proof clips live in `docs/design/motion-proof/`.
