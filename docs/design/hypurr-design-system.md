# Hypurr design system

One token set for every client: the web site (`web/app/globals.css`), SwiftUI
(`kit/Sources/HypurrKit/Design/Theme.swift`, `ColorFlow.swift`, `Motion.swift`),
GTK (`apps/linux/src/main.rs` palette + CSS) and the terminal UI
(`host/src/tui/view.rs`). Change a token in all of them together.

## Colour

Material 3 Expressive-inspired tonal roles derived from one seed hue
(violet, OKLCH hue 295). The web derives every role live from `--seed-h`
(the footer seed picker re-tones the page); the native clients use the hex
values below, which are the seed-295 roles.

| Role | Light | Dark |
|---|---|---|
| background | `#FCFAFF` | `#0E0A1C` |
| surface | `#F4EFFC` | `#161029` |
| bubble agent / card | `#EFE9FA` | `#1E1736` |
| bubble user | `#E4D9FB` | `#3A2A6B` |
| border | `#E6DFF3` | `#2A2145` |
| text | `#1E1433` | `#F3EEFF` |
| secondary | `#5B4F7A` | `#A89CC8` |
| tertiary | `#8C82A8` | `#75699A` |
| accent fill | `#6D3FD9` | `#A78BFA` |
| on accent | `#FFFFFF` | `#150A33` |
| needs you (warning) | `#C0267A` | `#F472B6` |
| danger | `#C2304D` | `#FF8FA3` |

**Colour flow** — violet `#A78BFA` → magenta `#F472B6` → cyan `#22D3EE`, looping.
Used for primary buttons, the send button, switches, spinners, working orbs,
the brand title and selected tabs/rows (at low opacity). Ink on the flow is
`#150A33`. It moves slowly (one loop per 6–9 s) and is static under Reduce
Motion / `prefers-reduced-motion`.

Bot avatar colours are unchanged; "needs you" is magenta (was amber).

## Surfaces

- **Glass**: translucent panels with blur and a 1px inner highlight, never a border
  line (web `.glass`, SwiftUI `.glass(in:)` → Liquid Glass on iOS/macOS 26, material before).
- **Shapes**: pills for buttons and chips; large radii (24–32) for cards, sheets
  and bubbles; M3 Expressive "cookie" shapes for icon containers on the web.

## Motion

M3 Expressive springs (stiffness / damping ratio, mass 1):

| Token | Values | Use |
|---|---|---|
| spatialFast | 1400 / 0.6 | knobs, chips, press feedback |
| spatialDefault | 380 / 0.8 | movement, resizing |
| spatialSlow | 200 / 0.8 | sheets, big panels |
| effects | 1600 / 1.0 | colour, opacity (no overshoot) |
| bouncy | 0.45 s, bounce 0.38 | confirmations, badges |

Press scale is 0.96. Web: `web/app/components/motion.ts`; GTK approximates
springs with `cubic-bezier(0.34, 1.56, 0.64, 1)`; the TUI animates only the
colour flow and spinner at its 100 ms tick.

## Type

Web: Bricolage Grotesque for display, Geist for text, Geist Mono for code.
Native apps keep the system fonts (SF / Cantarell) with heavier headline weights.
