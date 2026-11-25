#!/usr/bin/env python3
"""Generates the SavahLive icon set from ONE source, so the SVGs and the Android vector drawables can never drift apart.

    python scripts/gen_savahlive_icons.py

Writes  branding/savahlive-icons/<name>.svg  and  android/app/src/main/res/drawable/ic_<name>.xml.

Style (every icon): 24 x 24 grid, 2 px stroke, round caps and joins, no fill, so the set reads as one family.
Three deliberate exceptions use a solid shape because a solid shape is the clearer symbol: dot (a status light), play and stop (media
controls), heart (likes). The Android drawables are white: the app tints them with the panel theme (setColorFilter) at runtime.
"""
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SVG_DIR = ROOT / "branding" / "savahlive-icons"
XML_DIR = ROOT / "android" / "app" / "src" / "main" / "res" / "drawable"

S, F, FS = "stroke", "fill", "fillstroke"   # how a path is painted


def gear() -> str:
    """An 8-tooth cog outline with rounded valleys, plus nothing else (the hub circle is a separate path)."""
    teeth, r_in, r_out = 8, 6.9, 9.4
    pts = []
    for k in range(teeth):
        a = math.radians(k * 360 / teeth - 90)
        w_in, w_out = math.radians(13), math.radians(7.5)
        pts.append((a - w_in, r_in))
        pts.append((a - w_out, r_out))
        pts.append((a + w_out, r_out))
        pts.append((a + w_in, r_in))
    xy = [(12 + r * math.cos(t), 12 + r * math.sin(t)) for t, r in pts]
    d = f"M{xy[0][0]:.2f} {xy[0][1]:.2f}"
    for i in range(1, len(xy)):
        if i % 4 == 0:       # from the last point of one tooth to the first of the next: a curve along the valley
            d += f"A{r_in} {r_in} 0 0 1 {xy[i][0]:.2f} {xy[i][1]:.2f}"
        else:
            d += f"L{xy[i][0]:.2f} {xy[i][1]:.2f}"
    d += f"A{r_in} {r_in} 0 0 1 {xy[0][0]:.2f} {xy[0][1]:.2f}z"
    return d


ICONS = {
    # ---- navigation (bottom bar) ------------------------------------------------------------------------------------------
    "home": [("M3 10.5L12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z", S)],
    "palette": [
        ("M12 3a9 9 0 1 0 0 18c1.1 0 1.8-.9 1.8-1.8 0-.5-.2-.9-.5-1.3-.3-.3-.5-.7-.5-1.2 0-1 .8-1.7 1.7-1.7H16a5 5 0 0 0 5-5c0-4.4-4-8-9-8z", S),
        ("M7.5 11.5h.01M10 7.5h.01M14.5 7.5h.01", S),
    ],
    "chat": [("M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z", S)],
    "settings": [(gear(), S), ("M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z", S)],
    # ---- actions ----------------------------------------------------------------------------------------------------------
    "play": [("M7 4.5L19.5 12 7 19.5z", FS)],
    "stop": [("M6.5 6.5h11v11h-11z", FS)],
    "power": [("M12 3v8M17.7 6.3a8 8 0 1 1-11.4 0", S)],
    "check": [("M20 6L9 17l-5-5", S)],
    "close": [("M18 6L6 18M6 6l12 12", S)],
    "plus": [("M12 5v14M5 12h14", S)],
    "minus": [("M5 12h14", S)],
    "back": [("M19 12H5M12 19l-7-7 7-7", S)],
    "send": [("M21.5 2.5L10.5 13.5M21.5 2.5l-7 19-4-8.5-8.5-4z", S)],
    "paste": [
        ("M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", S),
        ("M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z", S),
    ],
    "link": [("M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7", S)],
    "download": [("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3", S)],
    "trash": [("M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6", S)],
    "chevron_right": [("M9 6l6 6-6 6", S)],
    "chevron_down": [("M6 9l6 6 6-6", S)],
    # ---- the panel and the tab --------------------------------------------------------------------------------------------
    "eye": [
        ("M2 12C4 8.5 7.5 5 12 5C16.5 5 20 8.5 22 12C20 15.5 16.5 19 12 19C7.5 19 4 15.5 2 12z", S),
        ("M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z", S),
    ],
    "eye_off": [
        ("M2 12C4 8.5 7.5 5 12 5C16.5 5 20 8.5 22 12C20 15.5 16.5 19 12 19C7.5 19 4 15.5 2 12z", S),
        ("M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z", S),
        ("M4 4l16 16", S),
    ],
    "user": [("M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 20c0-3.3 3.6-6 8-6s8 2.7 8 6", S)],
    "heart": [("M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z", FS)],
    "dot": [("M12 6a6 6 0 1 0 0 12 6 6 0 0 0 0-12z", F)],
    "dots": [
        ("M5 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z", F),
        ("M12 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z", F),
        ("M19 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z", F),
    ],
    "expand": [("M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7", S)],
    "collapse": [("M4 14h6v6M20 10h-6V4M14 10l7-7M3 21l7-7", S)],
    "dock": [("M4 12h11M10 7l5 5-5 5M20 4v16", S)],
    "tuck": [("M4 12h11M10 7l5 5-5 5M20 4v16", S)],
    "badge_bot": [("M12 8V4H8M6 8h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zM2 14h2M20 14h2M15 13v2M9 13v2", S)],
    "badge_mod": [("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1zM9 12l2 2 4-4", S)],
    "badge_owner": [("M11.56 3.27a.5.5 0 0 1 .88 0l2.95 5.6a1 1 0 0 0 1.52.29L21.18 5.5a.5.5 0 0 1 .8.52l-2.83 10.25a1 1 0 0 1-.96.73H5.81a1 1 0 0 1-.96-.73L2.02 6.02a.5.5 0 0 1 .8-.52l4.28 3.66a1 1 0 0 0 1.52-.29zM5 21h14", S)],
    "bell_off": [("M8.7 3A6 6 0 0 1 18 8c0 2.69.77 4.65 1.7 6.05M17 17H3s3-2 3-9a4.67 4.67 0 0 1 .3-1.7M10.3 21a1.94 1.94 0 0 0 3.4 0M2 2l20 20", S)],
    "tuck_near": [
        ("M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z", S),
        ("M12 8V3M9.5 5.5L12 3l2.5 2.5M12 16v5M9.5 18.5L12 21l2.5-2.5M8 12H3M5.5 9.5L3 12l2.5 2.5M16 12h5M18.5 9.5L21 12l-2.5 2.5", S),
    ],
    "tuck_right": [("M3 12h14M11 6l6 6-6 6M21 5v14", S)],
    "tuck_left": [("M21 12H7M13 6l-6 6 6 6M3 5v14", S)],
    "tuck_top": [("M12 21V7M6 13l6-6 6 6M5 3h14", S)],
    "tuck_bottom": [("M12 3v14M6 11l6 6 6-6M5 21h14", S)],
    "tab": [("M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM15 5v14", S)],
    "move": [("M5 9l-3 3 3 3M9 5l3-3 3 3M15 19l-3 3-3-3M19 9l3 3-3 3M2 12h20M12 2v20", S)],
    "grip": [("M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01", S)],
    "pin": [("M12 17v5M9 10.8a2 2 0 0 1-1.1 1.8l-1.8.9A2 2 0 0 0 5 15.2V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.8a2 2 0 0 0-1.1-1.7l-1.8-.9A2 2 0 0 1 15 10.8V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z", S)],
    "lock": [("M5 11h14a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1zM8 11V7a4 4 0 0 1 8 0v4", S)],
    "unlock": [("M5 11h14a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1zM8 11V7a4 4 0 0 1 7.5-2", S)],
    "phone": [("M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zM12 18h.01", S)],
    "opacity": [("M12 3s6.5 6.4 6.5 10.8a6.5 6.5 0 0 1-13 0C5.5 9.4 12 3 12 3z", S)],
    "text": [("M4 7V4h16v3M9 20h6M12 4v16", S)],
    # ---- things the app talks about ---------------------------------------------------------------------------------------
    "layers": [("M12 2.5L2.5 7.5 12 12.5l9.5-5zM2.5 12L12 17l9.5-5M2.5 16.5L12 21.5l9.5-5", S)],
    "bell": [("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0", S)],
    "battery": [
        ("M4 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM22 11v2", S),
        ("M11.5 9.5L9.5 12h3l-2 2.5", S),
    ],
    "live": [
        ("M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z", S),
        ("M16.2 7.8a6 6 0 0 1 0 8.4M7.8 16.2a6 6 0 0 1 0-8.4M19.1 4.9a10 10 0 0 1 0 14.2M4.9 19.1a10 10 0 0 1 0-14.2", S),
    ],
    "broadcast": [
        ("M12 10.4a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2z", F),
        ("M16.2 7.8a6 6 0 0 1 0 8.4M7.8 16.2a6 6 0 0 1 0-8.4M19.1 4.9a10 10 0 0 1 0 14.2M4.9 19.1a10 10 0 0 1 0-14.2", S),
    ],
    "heart_o": [("M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z", S)],
    "info": [("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 16v-4M12 8h.01", S)],
    "help": [("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM9.2 9.4a2.9 2.9 0 0 1 5.6.9c0 1.9-2.8 2.6-2.8 2.6M12 17h.01", S)],
    "check_circle": [("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM8 12.5l3 3 5-6", S)],
    "ring": [("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z", S)],
    "bookmark": [("M6 3h12a1 1 0 0 1 1 1v17l-7-4-7 4V4a1 1 0 0 1 1-1z", S)],
    "star": [("M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z", S)],
    "clock": [("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2", S)],
    "zap": [("M13 2.5L4.5 13.5H11l-1 8 8.5-11H12z", S)],
    "wifi_off": [("M12 20h.01M8.5 16.4a5 5 0 0 1 7 0M5 12.9a10 10 0 0 1 5.2-2.7M19 12.9a10 10 0 0 0-2-1.5M2 8.8a15 15 0 0 1 4.2-2.6M22 8.8a15 15 0 0 0-11.3-3.8M2 2l20 20", S)],
    "wifi": [("M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 20h.01", S)],
    "chart": [("M4 20V11M10 20V4M16 20v-6M22 20H2", S)],
    "sliders": [("M20 5h-7M9 5H4M20 12h-3M13 12H4M20 19h-9M7 19H4M11 3v4M15 10v4M9 17v4", S)],
}


def svg(paths) -> str:
    out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">']
    for d, mode in paths:
        if mode == F:
            out.append(f'  <path d="{d}" fill="currentColor" stroke="none"/>')
        elif mode == FS:
            out.append(f'  <path d="{d}" fill="currentColor"/>')
        else:
            out.append(f'  <path d="{d}"/>')
    out.append("</svg>")
    return "\n".join(out) + "\n"


def xml(name: str, paths) -> str:
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- Generated by scripts/gen_savahlive_icons.py from branding/savahlive-icons/{name}.svg. White on purpose: the app tints it with the panel theme at runtime. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">',
    ]
    stroke = 'android:strokeColor="#FFFFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round"'
    for d, mode in paths:
        if mode == F:
            out.append(f'    <path android:fillColor="#FFFFFFFF" android:pathData="{d}" />')
        elif mode == FS:
            out.append(f'    <path android:fillColor="#FFFFFFFF" {stroke} android:pathData="{d}" />')
        else:
            out.append(f'    <path {stroke} android:pathData="{d}" />')
    out.append("</vector>")
    return "\n".join(out) + "\n"


def main() -> None:
    SVG_DIR.mkdir(parents=True, exist_ok=True)
    # remove the previous generation first, so an icon deleted from ICONS disappears from both places
    for p in SVG_DIR.glob("*.svg"):
        p.unlink()
    for p in XML_DIR.glob("ic_*.xml"):
        if p.stem not in ("ic_stat", "ic_launcher_foreground"):
            p.unlink()
    for name, paths in ICONS.items():
        (SVG_DIR / f"{name}.svg").write_text(svg(paths), encoding="utf-8")
        (XML_DIR / f"ic_{name}.xml").write_text(xml(name, paths), encoding="utf-8")
    print(f"wrote {len(ICONS)} icons")


if __name__ == "__main__":
    main()
