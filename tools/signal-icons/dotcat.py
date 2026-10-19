"""The Hypurr dot-matrix cat: a 9x9 bitmap drawn in dots. Generates the app icon, notification
icon, in-app cat drawable and docs/brand/cat-dots.svg. Run from the repo root."""
import os
CAT = ["X.......X", "XX.....XX", "XXXXXXXXX", "XXXXXXXXX", "XX.XXX.XX", "XXXXXXXXX", "XXXX.XXXX", ".XXXXXXX.", "..XXXXX.."]

def dots(x0, y0, cell, r, on=True):
    for y, row in enumerate(CAT):
        for x, ch in enumerate(row):
            if (ch == "X") == on:
                yield x0 + (x + .5) * cell, y0 + (y + .5) * cell, r

def circ(cx, cy, r):
    return f"M{cx - r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0 1,0 {2*r:.2f},0a{r:.2f},{r:.2f} 0 1,0 {-2*r:.2f},0"

def vec(w, vp, groups):
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Hypurr dot-matrix cat (tools/signal-icons/dotcat.py). -->',
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{w}dp" android:height="{w}dp"'
           f' android:viewportWidth="{vp}" android:viewportHeight="{vp}">']
    for color, d in groups:
        out.append(f'    <path android:fillColor="{color}" android:pathData="{d}"/>')
    out.append('</vector>')
    return "\n".join(out) + "\n"

root = os.getcwd(); dr = os.path.join(root, "apps/android/app/src/main/res/drawable")
# Launcher foreground: 108 viewport, safe zone 66 → cat 44 wide centred.
cell = 44 / 9; x0 = (108 - 44) / 2
on = "".join(circ(*d) for d in dots(x0, x0, cell, cell * 0.38))
off = "".join(circ(*d) for d in dots(x0, x0, cell, cell * 0.30, on=False))
open(os.path.join(dr, "ic_launcher_foreground.xml"), "w").write(vec(108, 108, [("#FF2A2A2D", off), ("#FFFFFFFF", on)]))
open(os.path.join(dr, "ic_launcher_monochrome.xml"), "w").write(vec(108, 108, [("#FFFFFFFF", on)]))
open(os.path.join(dr, "ic_launcher_background.xml"), "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
    '    <solid android:color="#0E0E0E"/>\n</shape>\n')
# Small icons: 24 viewport.
cell = 20 / 9
small = "".join(circ(*d) for d in dots(2, 2, cell, cell * 0.42))
open(os.path.join(dr, "ic_notification.xml"), "w").write(vec(24, 24, [("#FFFFFFFF", small)]))
open(os.path.join(dr, "ic_hypurr_cat.xml"), "w").write(vec(24, 24, [("#FF0E0E0E", small)]))
open(os.path.join(dr, "ic_hypurr_bots.xml"), "w").write(vec(24, 24, [("#FF0E0E0E", small)]))
# Brand SVG.
cell = 24
svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 216 216" width="216" height="216">']
for cx, cy, r in dots(0, 0, cell, cell * 0.36, on=False): svg.append(f'<circle cx="{cx}" cy="{cy}" r="{r:.1f}" fill="#E4E4E1"/>')
for cx, cy, r in dots(0, 0, cell, cell * 0.36): svg.append(f'<circle cx="{cx}" cy="{cy}" r="{r:.1f}" fill="#0E0E0E"/>')
svg.append('</svg>')
open(os.path.join(root, "docs/brand/cat-dots.svg"), "w").write("\n".join(svg) + "\n")
print("ok")
