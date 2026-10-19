# Hypurr motion: Signal

Subtle, precise, dot-matrix. Palette and type follow
[`hypurr-design-system.md`](hypurr-design-system.md): monochrome, red only for "needs you".
No glow, no bounce, no confetti, no sparkle.

Every moment honours **Reduce Motion** (Android animator scale 0, iOS/macOS
`accessibilityReduceMotion`, web `prefers-reduced-motion`): loops stop on a static frame and
transitions become a 150ms fade. Previews and screenshots render each moment's end state.

Code: Android `ui/motion/MotionTokens.kt` (`HypurrMotion`) and `ui/motion/SignalMotion.kt`;
web `web/app/globals.css` (`.hypurr-scan`, `.hypurr-needs`, `.hypurr-drop`) and
`web/app/components/motion.ts`; TUI `host/src/tui/view.rs` (`SCAN_LINE` dot pulse);
Swift `kit/Sources/HypurrKit/Design/SignalMotion.swift`.

## Tokens

| Token | ms | Use |
|---|---|---|
| `PRESS` | 120 | pill press in / out |
| `SNAP` | 180 | label crossfades, chip land |
| `SETTLE` | 240 | approval drop-in, shared-axis screen change |
| `DRAW` | 420 | dotted done rule |
| `ASSEMBLE` | 760 | dots assembling the cat |
| `SCAN` | 1100 | one pass of the thinking pulse |
| `PULSE` | 1400 | one needs-you ring |
| `REDUCED` | 150 | universal reduced-motion fade |

Easing: emphasized `cubic-bezier(0.2, 0, 0, 1)` for tweens; critically damped springs
(no overshoot) for layout. Press scale **0.97** (cards) / **0.96** (Allow once).

## Moments

| # | Moment | Treatment | Android |
|---|---|---|---|
| 1 | Thinking | Five dots, a lit head scans across (unlit dots stay so nothing jumps); a small dot cat blinks; the phase text crossfades | `ThinkingRow`, `DotPulse` |
| 2 | Streaming | A red block caret at the end of the live agent text, 530ms blink | `StreamCaret` |
| 3 | Needs you | A red dot with one ring breathing out every 1.4s. The only looping red. | `NeedsYouPulse` |
| 4 | Approval arrives | The card rises 16dp with a fade, 240ms emphasized. No squash. | `ApprovalDropIn` |
| 5 | Allow once | The black pill presses to 0.96 and releases (120ms each way), then an ink "Allowed" chip with a check scales in | `AllowPressEffect`, `AllowedChip` |
| 6 | Deny | Two short 3dp nudges, then the control dims | `DenyNudge` |
| 7 | Creating a bot | The cat's dots fly in from a scatter and lock to the grid (760ms), then the name types in at 28ms a letter | `CatAssemble` |
| 8 | Pairing | The hero settles from 0.96 and the cat's eyes light | `PairingSuccess` |
| 9 | Task done | The cat squints (eyes filled) and a dotted rule fills in under "Done" | `DoneStamp`, `DottedRule` |
| 10 | Pull to refresh | The dot cat assembles as you pull | `CatPeek` |
| 11 | Loading | A faint dot grid with a slow diagonal brightness wave | `DotShimmer` |
| 12 | Brand mark | Dot cat tile that blinks now and then | `DotCatMark` |

## Proof

`SignalMotionReelTest` renders the real screens frame by frame (Robolectric
`mainClock.advanceTimeBy(16)`), gated by the `HYPURR_REEL` env var so CI skips it:

```sh
cd apps/android
HYPURR_REEL=/tmp/reel ./gradlew :app:testDebugUnitTest --tests '*SignalMotionReelTest' -Proborazzi.test.record=true
ffmpeg -framerate 60 -pattern_type glob -i '/tmp/reel/*.png' -pix_fmt yuv420p reel.mp4
```

The current reel is [`signal/reel.mp4`](signal/reel.mp4).
