# Motion proof clips (v2)

Generated from `web/public/motion-lab/index.html` with a deterministic JS clock (`?capture=1`), phone-framed at 390×844 @2x → 1080-tall H.264, 60fps.

| File | Moment |
|---|---|
| `thinking.gif` / `thinking-strip.png` | Scan band + soft flat falloff trail, phase cross-fade, mono elapsed |
| `streaming.gif` | Per-word ink resolve + cyan caret |
| `tool.gif` | Progress hairline + staggered file chips |
| `assemble.gif` | Diagonal rain → Hypurr cat glyph paths + signal band + name type-in |
| `pairing.gif` | Rain converges to neon point, then Direct light-up (≤2 flashes) |
| `needsyou.gif` | Plate drop + amber strike + badge tick |
| `strike.gif` | Full plate → cyan band → live row collapse |
| `deny.gif` | Deny shear + red hairline |
| `done.gif` | Done rule draw + checkpoint chip settle |
| `skeleton.gif` | Diagonal rain-streak shimmer (not grey pulse) |
| `lab-full.png` | Mid-frame sheet of all ten moments |

## Higher-quality MP4s (agent box)

Under `/workspace/hypurr-shots/motion/v2/mp4/` (not committed — keep repo lean):

| Clip | Description |
|---|---|
| `thinking.mp4` … `skeleton.mp4` | Phone-frame 3s @60fps each |
| `desktop-thinking.mp4` | Desktop window: bots list scan |
| `desktop-strike.mp4` | Desktop window: signal strike plate |
| `showreel.mp4` | ~25.5s titled cut through all moments |

Capture: puppeteer-core + Chrome, `window.__MOTION__.setTime(ms)`. Spec: `docs/design/hypurr-motion.md`.
