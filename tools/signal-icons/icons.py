"""Hypurr Signal icon set: 24 grid, 1.6 stroke, round caps/joins, currentColor.

Source of truth for docs/brand/icons/*.svg and the Android ic_sg_* VectorDrawables.
Run: python3 tools/signal-icons/icons.py  (from the repo root)
"""
import os, re

SW = 1.6
# name -> list of (svg-element, extra attrs). Elements: path d / circle cx cy r / rect x y w h rx
I = {
 "bots": ['<path d="M4.8 10.6V4.9c0-.6.7-.9 1.1-.5l3.3 2.6h5.6l3.3-2.6c.4-.4 1.1-.1 1.1.5v5.7c0 4.6-3.2 8.4-7.2 8.4s-7.2-3.8-7.2-8.4z"/>',
          '<path d="M9.3 12.2v.6M14.7 12.2v.6" stroke-width="2.1"/>', '<path d="M11 15.3h2l-1 .9z"/>'],
 "tasks": ['<path d="M10 6.5h10M10 12h10M10 17.5h10"/>', '<path d="M3.8 6.6l1.3 1.3 2.4-2.6M3.8 12.1l1.3 1.3 2.4-2.6"/>', '<circle cx="5.4" cy="17.5" r="1.4"/>'],
 "spend": ['<path d="M4 7.5C4 6.1 5.1 5 6.5 5H17a1.5 1.5 0 0 1 1.5 1.5V8"/>', '<path d="M4 7.5v10A1.5 1.5 0 0 0 5.5 19H19a1 1 0 0 0 1-1v-8.5a1 1 0 0 0-1-1H5.5A1.5 1.5 0 0 1 4 7.5z"/>', '<path d="M15.5 13.75h1"/>'],
 "you": ['<circle cx="12" cy="8.5" r="3.6"/>', '<path d="M5 19.6c1.3-3.4 3.9-5 7-5s5.7 1.6 7 5"/>'],
 "settings": ['<path d="M4 7.5h9M17 7.5h3M4 16.5h3M11 16.5h9"/>', '<circle cx="15" cy="7.5" r="2"/>', '<circle cx="9" cy="16.5" r="2"/>'],
 "plus": ['<path d="M12 5.5v13M5.5 12h13"/>'],
 "back": ['<path d="M14.5 5.5L8 12l6.5 6.5"/>'],
 "chev": ['<path d="M9.5 5.5L16 12l-6.5 6.5"/>'],
 "send": ['<path d="M12 18.5v-13M6.5 11L12 5.5l5.5 5.5"/>'],
 "allow": ['<path d="M5 12.5l4.3 4.3L19 7.2"/>'],
 "check": ['<path d="M5 12.5l4.3 4.3L19 7.2"/>'],
 "deny": ['<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>'],
 "close": ['<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>'],
 "host": ['<path d="M5 6.5a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v9.5H5z"/>', '<path d="M2.8 19h18.4"/>'],
 "new": ['<rect x="4" y="4" width="16" height="16" rx="4"/>', '<path d="M12 8.5v7M8.5 12h7"/>'],
 "qr": ['<rect x="4" y="4" width="6" height="6" rx="1.5"/>', '<rect x="14" y="4" width="6" height="6" rx="1.5"/>', '<rect x="4" y="14" width="6" height="6" rx="1.5"/>',
        '<path d="M14 14h2.5v2.5M20 14v.01M14 20h.01M17 20h3v-3" />'],
 "search": ['<circle cx="11" cy="11" r="6.2"/>', '<path d="M19.5 19.5l-4-4"/>'],
 "more": ['<path d="M6 12h.01M12 12h.01M18 12h.01" stroke-width="2.6"/>'],
 "team": ['<circle cx="9" cy="9" r="3.2"/>', '<path d="M3.5 19c.9-3 3-4.6 5.5-4.6s4.6 1.6 5.5 4.6"/>', '<path d="M15 6a3 3 0 0 1 0 6M17 14.6c1.8.5 3 1.9 3.6 4.4"/>'],
 "activity": ['<path d="M3.5 12h3.5l2.5-6 5 12 2.5-6h3.5"/>'],
 "credits": ['<circle cx="12" cy="12" r="8.2"/>', '<path d="M9 9h6M9 12h6M12 12l-3 4"/>'],
 "needs_you": ['<path d="M12 3.5l6.8 2.7v5.1c0 4.3-2.8 7.8-6.8 9.4-4-1.6-6.8-5.1-6.8-9.4V6.2z"/>', '<path d="M12 8.6v4"/>', '<path d="M12 15.8h.01" stroke-width="2.4"/>'],
 "shield": ['<path d="M12 3.5l6.8 2.7v5.1c0 4.3-2.8 7.8-6.8 9.4-4-1.6-6.8-5.1-6.8-9.4V6.2z"/>', '<path d="M9 12l2.2 2.2L15.2 10"/>'],
 "tests": ['<path d="M9.5 3.5h5M10.5 3.5v5.2L5.6 17.4A2 2 0 0 0 7.4 20.5h9.2a2 2 0 0 0 1.8-3.1l-4.9-8.7V3.5"/>', '<path d="M7.6 14.5h8.8"/>'],
 "fix_error": ['<circle cx="12" cy="12" r="8.2"/>', '<path d="M12 7.8v4.6"/>', '<path d="M12 15.9h.01" stroke-width="2.4"/>'],
 "review": ['<path d="M2.8 12c2-3.9 5.1-6 9.2-6s7.2 2.1 9.2 6c-2 3.9-5.1 6-9.2 6s-7.2-2.1-9.2-6z"/>', '<circle cx="12" cy="12" r="2.6"/>'],
 "update_deps": ['<path d="M12 3.8l7.5 4.2v8l-7.5 4.2-7.5-4.2V8z"/>', '<path d="M4.5 8l7.5 4.2L19.5 8M12 12.2v8"/>'],
 "explain": ['<path d="M5 5.5h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-7l-4.5 3.2v-3.2H5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z"/>', '<path d="M8 9.5h8M8 12.5h5"/>'],
 "docs": ['<path d="M7 3.5h6.5L18 8v11.5a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1z"/>', '<path d="M13 3.8V8.5h4.7"/>', '<path d="M9 13h6M9 16.5h4"/>'],
 "file": ['<path d="M7 3.5h6.5L18 8v11.5a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1z"/>', '<path d="M13 3.8V8.5h4.7"/>'],
 "stop": ['<rect x="7" y="7" width="10" height="10" rx="2"/>'],
 "pause": ['<path d="M9 6v12M15 6v12"/>'],
 "play": ['<path d="M8 5.8v12.4a.8.8 0 0 0 1.2.7l9.8-6.2a.8.8 0 0 0 0-1.4L9.2 5.1A.8.8 0 0 0 8 5.8z"/>'],
 "paste": ['<rect x="5.5" y="5" width="13" height="15.5" rx="2"/>', '<path d="M9 5V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v1M9 11h6M9 14.5h4"/>'],
 "link": ['<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/>', '<path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>'],
 "folder": ['<path d="M3.5 7.5A2 2 0 0 1 5.5 5.5h3.6l2 2h7.4a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/>'],
 "branch": ['<circle cx="7" cy="5.5" r="1.9"/>', '<circle cx="7" cy="18.5" r="1.9"/>', '<circle cx="17" cy="7.5" r="1.9"/>', '<path d="M7 7.4v9.2M17 9.4c0 3.6-3 5.1-8.4 5.6"/>'],
 "image": ['<rect x="3.5" y="5" width="17" height="14" rx="2.5"/>', '<circle cx="9" cy="10" r="1.6"/>', '<path d="M20.5 15.5l-4.5-4.5-8.5 8"/>'],
 "refresh": ['<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/>', '<path d="M19.5 4.5v4h-4"/>'],
 "mic": ['<rect x="9" y="3.5" width="6" height="10.5" rx="3"/>', '<path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5v3"/>'],
 "trash": ['<path d="M4.5 7h15M9.5 7V4.8h5V7M6.5 7l.9 12.2h9.2L17.5 7"/>'],
 "undo": ['<path d="M8.5 14L4 9.5 8.5 5"/>', '<path d="M4 9.5h10a5.5 5.5 0 0 1 0 11h-3"/>'],
 "clock": ['<circle cx="12" cy="12" r="8.2"/>', '<path d="M12 7.5V12l3 2"/>'],
 "terminal": ['<rect x="3.5" y="4.5" width="17" height="15" rx="2.5"/>', '<path d="M7.5 9.5l2.8 2.5-2.8 2.5M12.5 14.5h4"/>'],
 "trend": ['<path d="M4 16.5l5.2-5.2 3.6 3.6L20 7.6"/>', '<path d="M15 7.5h5v5"/>'],
 "limit": ['<path d="M4 7.5h9M17 7.5h3M4 16.5h3M11 16.5h9"/>', '<circle cx="15" cy="7.5" r="2"/>', '<circle cx="9" cy="16.5" r="2"/>'],
 "bell": ['<path d="M6.5 16.5V11a5.5 5.5 0 0 1 11 0v5.5l1.5 1.5H5z"/>', '<path d="M10 20.5h4"/>'],
 "lock": ['<rect x="5" y="10.5" width="14" height="10" rx="2.5"/>', '<path d="M8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3"/>'],
 "sun": ['<circle cx="12" cy="12" r="3.8"/>', '<path d="M12 3v1.8M12 19.2V21M3 12h1.8M19.2 12H21M5.6 5.6l1.3 1.3M17.1 17.1l1.3 1.3M5.6 18.4l1.3-1.3M17.1 6.9l1.3-1.3"/>'],
 "moon": ['<path d="M19.5 14.5A7.8 7.8 0 0 1 9.5 4.5a7.8 7.8 0 1 0 10 10z"/>'],
 "trace": ['<path d="M9 9l10 3.5-4.2 1.3L13.5 18z"/>', '<path d="M4 4.5h.01M6.5 6.5h.01M4 8.5h.01" stroke-width="2.2"/>'],
 "map": ['<path d="M4 4.5h6v5H4zM14 14.5h6v5h-6zM14 4.5h6v5h-6z"/>', '<path d="M10 7h4M7 9.5v7.5h7"/>'],
}

ATTR = re.compile(r'([\w-]+)="([^"]*)"')

def to_path(el):
    tag = el[1:].split()[0]
    a = dict(ATTR.findall(el))
    if tag == "path":
        d = a["d"]
    elif tag == "circle":
        cx, cy, r = float(a["cx"]), float(a["cy"]), float(a["r"])
        d = f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1,0 {2*r:g},0a{r:g},{r:g} 0 1,0 {-2*r:g},0"
    elif tag == "rect":
        x, y, w, h, rx = (float(a[k]) for k in ("x", "y", "width", "height", "rx"))
        d = (f"M{x+rx:g},{y:g}h{w-2*rx:g}a{rx:g},{rx:g} 0 0 1 {rx:g},{rx:g}v{h-2*rx:g}a{rx:g},{rx:g} 0 0 1 {-rx:g},{rx:g}"
             f"h{-(w-2*rx):g}a{rx:g},{rx:g} 0 0 1 {-rx:g},{-rx:g}v{-(h-2*rx):g}a{rx:g},{rx:g} 0 0 1 {rx:g},{-rx:g}z")
    else:
        raise ValueError(tag)
    return d, float(a.get("stroke-width", SW))

def vector(name):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           f'<!-- Hypurr Signal icon "{name}" (tools/signal-icons/icons.py). 24 grid, {SW} stroke, round caps. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
    for el in I[name]:
        d, sw = to_path(el)
        out.append(f'    <path android:pathData="{d}" android:fillColor="#00000000" android:strokeColor="#FF0E0E0E"'
                   f' android:strokeWidth="{sw:g}" android:strokeLineCap="round" android:strokeLineJoin="round"/>')
    out.append('</vector>')
    return "\n".join(out) + "\n"

def svg(name):
    body = "".join(I[name])
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" '
            f'stroke-width="{SW}" stroke-linecap="round" stroke-linejoin="round">{body}</svg>\n')

if __name__ == "__main__":
    root = os.getcwd()
    dr = os.path.join(root, "apps/android/app/src/main/res/drawable")
    sv = os.path.join(root, "docs/brand/icons")
    for f in os.listdir(dr):
        if f.startswith(("ic_sf_", "ic_sg_")): os.remove(os.path.join(dr, f))
    for f in os.listdir(sv):
        os.remove(os.path.join(sv, f))
    for n in I:
        open(os.path.join(dr, f"ic_sg_{n}.xml"), "w").write(vector(n))
        open(os.path.join(sv, f"{n}.svg"), "w").write(svg(n))
    print(len(I), "icons")
