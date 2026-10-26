#!/usr/bin/env python3
"""Single source of truth for the Kopi app icon.

Generates the master SVGs (design/icon/*.svg), the Android adaptive-icon
vector drawables + legacy PNG mipmaps, the macOS AppIcon.icns, and
design/icon/preview.png.

Requires: rsvg-convert (brew install librsvg) and iconutil (macOS).
Run from anywhere: python3 design/icon/build.py
"""
import base64
import os
import shutil
import subprocess
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "apps/android/app/src/main/res")
MAC_RES = os.path.join(ROOT, "apps/macos/Resources")

CYAN = "#22D3EE"
VIOLET = "#7C3AED"
BACK_ALPHA = 0.70

# ---------------------------------------------------------------------------
# Motif geometry, in "motif units" (local coordinates, origin = back card TL).
# ---------------------------------------------------------------------------
CARD_W, CARD_H, CARD_R = 24.0, 29.0, 5.0
FRONT_DX, FRONT_DY = 15.0, 11.0
GAP = 2.6                      # cut-out between front card and back card
ARC_W = 3.9                    # stroke width of the hop arc / arrow head
ARC_START = (10.5, -3.8)       # just above the back card
ARC_END = (31.5, 6.0)          # arrow tip, just above the front card
ARC_LIFT = 12.0
HEAD = 4.3

# Visual centre of the motif (tuned by eye from the preview).
MOTIF_CX, MOTIF_CY = 19.6, 14.8


def f(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def back_card_path():
    """Back card as an L-shape: rounded rect minus the (gap-expanded) front card."""
    r = CARD_R
    w, h = CARD_W, CARD_H
    cx0 = FRONT_DX - GAP          # left edge of expanded front card
    cy0 = FRONT_DY - GAP          # top edge of expanded front card
    cr = CARD_R + GAP             # corner radius of expanded front card
    ir = 2.0                      # soften the two inner joints
    return (
        f"M{f(r)},0 H{f(w - r)} A{f(r)},{f(r)} 0 0 1 {f(w)},{f(r)} "
        f"V{f(cy0 - ir)} A{f(ir)},{f(ir)} 0 0 1 {f(w - ir)},{f(cy0)} "
        f"H{f(cx0 + cr)} A{f(cr)},{f(cr)} 0 0 0 {f(cx0)},{f(cy0 + cr)} "
        f"V{f(h - ir)} A{f(ir)},{f(ir)} 0 0 1 {f(cx0 - ir)},{f(h)} "
        f"H{f(r)} A{f(r)},{f(r)} 0 0 1 0,{f(h - r)} V{f(r)} A{f(r)},{f(r)} 0 0 1 {f(r)},0 Z"
    )


def front_card_path():
    x, y, w, h, r = FRONT_DX, FRONT_DY, CARD_W, CARD_H, CARD_R
    return (
        f"M{f(x + r)},{f(y)} H{f(x + w - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w)},{f(y + r)} "
        f"V{f(y + h - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w - r)},{f(y + h)} "
        f"H{f(x + r)} A{f(r)},{f(r)} 0 0 1 {f(x)},{f(y + h - r)} "
        f"V{f(y + r)} A{f(r)},{f(r)} 0 0 1 {f(x + r)},{f(y)} Z"
    )


def arc_path():
    (sx, sy), (ex, ey) = ARC_START, ARC_END
    # Leaves the back card going straight up, lands on the front card going straight down.
    return (
        f"M{f(sx)},{f(sy)} C{f(sx)},{f(sy - ARC_LIFT)} "
        f"{f(ex)},{f(sy - ARC_LIFT)} {f(ex)},{f(ey)}"
    )


def head_path():
    ex, ey = ARC_END
    return f"M{f(ex - HEAD)},{f(ey - HEAD)} L{f(ex)},{f(ey)} L{f(ex + HEAD)},{f(ey - HEAD)}"


def motif_svg(color="#FFFFFF", back_alpha=BACK_ALPHA, card_filter=None, arc_w=ARC_W):
    flt = f' filter="url(#{card_filter})"' if card_filter else ""
    stroke = (
        f'stroke="{color}" stroke-width="{f(arc_w)}" stroke-linecap="round" '
        f'stroke-linejoin="round" fill="none"'
    )
    return (
        f'<g{flt}>'
        f'<path d="{back_card_path()}" fill="{color}" fill-opacity="{back_alpha}"/>'
        f'<path d="{front_card_path()}" fill="{color}"/>'
        f'<path d="{arc_path()}" {stroke}/>'
        f'<path d="{head_path()}" {stroke}/>'
        f'</g>'
    )


def place(scale, cx, cy):
    """Transform that puts the motif's visual centre at (cx, cy) with given scale."""
    tx = cx - MOTIF_CX * scale
    ty = cy - MOTIF_CY * scale
    return f"translate({f(tx)} {f(ty)}) scale({f(scale)})"


# ---------------------------------------------------------------------------
# macOS master (1024 canvas, 824 squircle, ~185 radius)
# ---------------------------------------------------------------------------
MAC_SCALE = 10.2


def mac_svg(small=False):
    """small=True is a hinted variant for the 16/32 px slots: bigger motif, bolder arc."""
    scale = MAC_SCALE * (1.18 if small else 1.0)
    arc_w = ARC_W * (1.3 if small else 1.0)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
  <defs>
    <linearGradient id="bg" x1="100" y1="100" x2="924" y2="924" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{CYAN}"/>
      <stop offset="1" stop-color="{VIOLET}"/>
    </linearGradient>
    <linearGradient id="sheen" x1="0" y1="100" x2="0" y2="924" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.16"/>
      <stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0"/>
    </linearGradient>
    <filter id="tileShadow" x="-20%" y="-20%" width="140%" height="140%">
      <feGaussianBlur in="SourceAlpha" stdDeviation="12"/>
      <feOffset dy="10" result="b"/>
      <feFlood flood-color="#1E1B4B" flood-opacity="0.30"/>
      <feComposite in2="b" operator="in"/>
      <feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge>
    </filter>
    <filter id="cardShadow" x="-30%" y="-30%" width="160%" height="160%">
      <feGaussianBlur in="SourceAlpha" stdDeviation="0.9"/>
      <feOffset dy="0.8" result="b"/>
      <feFlood flood-color="#312E81" flood-opacity="0.22"/>
      <feComposite in2="b" operator="in"/>
      <feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge>
    </filter>
  </defs>
  <g filter="url(#tileShadow)">
    <rect x="100" y="100" width="824" height="824" rx="185" ry="185" fill="url(#bg)"/>
  </g>
  <rect x="100" y="100" width="824" height="824" rx="185" ry="185" fill="url(#sheen)"/>
  <g transform="{place(scale, 512, 512)}">
    {motif_svg(card_filter="cardShadow", arc_w=arc_w)}
  </g>
</svg>
'''


# ---------------------------------------------------------------------------
# Android adaptive icon (108 dp, 66 dp safe zone)
# ---------------------------------------------------------------------------
AND_SCALE = 0.9


def and_bg_svg():
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
  <defs>
    <linearGradient id="bg" x1="18" y1="18" x2="90" y2="90" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{CYAN}"/><stop offset="1" stop-color="{VIOLET}"/>
    </linearGradient>
  </defs>
  <rect width="108" height="108" fill="url(#bg)"/>
</svg>
'''


def and_fg_svg(color="#FFFFFF"):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
  <g transform="{place(AND_SCALE, 54, 54)}">{motif_svg(color)}</g>
</svg>
'''


def vector_group(fill_main="#FFFFFFFF"):
    tx = 54 - MOTIF_CX * AND_SCALE
    ty = 54 - MOTIF_CY * AND_SCALE
    a = f"{BACK_ALPHA}"
    return f'''    <group
        android:translateX="{f(tx)}"
        android:translateY="{f(ty)}"
        android:scaleX="{f(AND_SCALE)}"
        android:scaleY="{f(AND_SCALE)}">
        <!-- Back card (L-shape, cut around the front card) -->
        <path
            android:fillColor="{fill_main}"
            android:fillAlpha="{a}"
            android:pathData="{back_card_path()}" />
        <!-- Front card -->
        <path
            android:fillColor="{fill_main}"
            android:pathData="{front_card_path()}" />
        <!-- Hop arc from the back card to the front card -->
        <path
            android:strokeColor="{fill_main}"
            android:strokeWidth="{f(ARC_W)}"
            android:strokeLineCap="round"
            android:strokeLineJoin="round"
            android:pathData="{arc_path()}" />
        <path
            android:strokeColor="{fill_main}"
            android:strokeWidth="{f(ARC_W)}"
            android:strokeLineCap="round"
            android:strokeLineJoin="round"
            android:pathData="{head_path()}" />
    </group>
'''


def vector_fg(comment):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} Generated by design/icon/build.py; do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{vector_group()}</vector>
'''


def vector_bg():
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Adaptive icon background: cyan to violet diagonal. Generated by design/icon/build.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="18"
                android:startY="18"
                android:endX="90"
                android:endY="90"
                android:startColor="#FF{CYAN[1:]}"
                android:endColor="#FF{VIOLET[1:]}" />
        </aapt:attr>
    </path>
</vector>
'''


ADAPTIVE_XML = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''

COLORS_XML = f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Mid-point of the icon gradient; used where a flat icon background is needed. -->
    <color name="ic_launcher_background">#4F86F0</color>
</resources>
'''


def masked_android_svg(mask, size_px, mono=False, theme="light"):
    """Composite of the adaptive layers, showing the visible 72 dp of the 108 dp canvas."""
    if mask == "circle":
        clip = '<circle cx="54" cy="54" r="36"/>'
    else:  # squircle-ish rounded square, like Pixel / One UI masks
        clip = '<rect x="18" y="18" width="72" height="72" rx="22" ry="22"/>'
    if mono:
        bg = "#D6E4FF" if theme == "light" else "#1F2A44"
        fg = "#1F3A8A" if theme == "light" else "#C7D7FF"
        bg_el = f'<rect width="108" height="108" fill="{bg}"/>'
        motif = motif_svg(fg)
    else:
        bg_el = '<rect width="108" height="108" fill="url(#bg)"/>'
        motif = motif_svg()
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size_px}" height="{size_px}" viewBox="18 18 72 72">
  <defs>
    <linearGradient id="bg" x1="18" y1="18" x2="90" y2="90" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{CYAN}"/><stop offset="1" stop-color="{VIOLET}"/>
    </linearGradient>
    <clipPath id="m">{clip}</clipPath>
  </defs>
  <g clip-path="url(#m)">{bg_el}<g transform="{place(AND_SCALE, 54, 54)}">{motif}</g></g>
</svg>
'''


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(text)


def render(svg_path, png_path, w, h=None):
    h = h or w
    subprocess.run(
        ["rsvg-convert", "-w", str(w), "-h", str(h), "-o", png_path, svg_path], check=True
    )


def b64(path):
    with open(path, "rb") as fh:
        return "data:image/png;base64," + base64.b64encode(fh.read()).decode()


def main():
    out = HERE
    write(os.path.join(out, "kopi-macos.svg"), mac_svg())
    write(os.path.join(out, "kopi-macos-small.svg"), mac_svg(small=True))
    write(os.path.join(out, "kopi-android-background.svg"), and_bg_svg())
    write(os.path.join(out, "kopi-android-foreground.svg"), and_fg_svg())
    write(os.path.join(out, "kopi-android-monochrome.svg"), and_fg_svg("#000000"))

    # ---- Android resources
    write(os.path.join(RES, "drawable/ic_launcher_background.xml"), vector_bg())
    write(os.path.join(RES, "drawable/ic_launcher_foreground.xml"),
          vector_fg("Adaptive icon foreground: two cards and the hop arc."))
    write(os.path.join(RES, "drawable/ic_launcher_monochrome.xml"),
          vector_fg("Themed-icon (monochrome) layer: same shapes as the foreground."))
    write(os.path.join(RES, "mipmap-anydpi-v26/ic_launcher.xml"), ADAPTIVE_XML)
    write(os.path.join(RES, "mipmap-anydpi-v26/ic_launcher_round.xml"), ADAPTIVE_XML)
    write(os.path.join(RES, "values/ic_launcher_background.xml"), COLORS_XML)

    tmp = tempfile.mkdtemp()
    try:
        for name, mask in (("ic_launcher", "squircle"), ("ic_launcher_round", "circle")):
            svg = os.path.join(tmp, f"{name}.svg")
            write(svg, masked_android_svg(mask, 192))
            for dens, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                             ("xxhdpi", 144), ("xxxhdpi", 192)):
                d = os.path.join(RES, f"mipmap-{dens}")
                os.makedirs(d, exist_ok=True)
                render(svg, os.path.join(d, f"{name}.png"), px)

        # ---- macOS iconset -> icns
        iconset = os.path.join(tmp, "AppIcon.iconset")
        os.makedirs(iconset)
        mac = os.path.join(out, "kopi-macos.svg")
        mac_small = os.path.join(out, "kopi-macos-small.svg")
        for base in (16, 32, 128, 256, 512):
            render(mac_small if base <= 32 else mac,
                   os.path.join(iconset, f"icon_{base}x{base}.png"), base)
            render(mac, os.path.join(iconset, f"icon_{base}x{base}@2x.png"), base * 2)
        os.makedirs(MAC_RES, exist_ok=True)
        subprocess.run(["iconutil", "-c", "icns", iconset, "-o",
                        os.path.join(MAC_RES, "AppIcon.icns")], check=True)

        # ---- Preview sheet
        sizes = {s: os.path.join(tmp, f"mac{s}.png") for s in (1024, 128, 64, 32, 16)}
        for s, p in sizes.items():
            render(mac_small if s <= 32 else mac, p, s)
        andr = {}
        for key, mask, mono, theme in (
            ("circle", "circle", False, "light"), ("squircle", "squircle", False, "light"),
            ("mono_l", "circle", True, "light"), ("mono_d", "circle", True, "dark"),
        ):
            svg = os.path.join(tmp, f"a_{key}.svg")
            write(svg, masked_android_svg(mask, 192, mono, theme))
            andr[key] = (svg, os.path.join(tmp, f"a_{key}.png"),
                         os.path.join(tmp, f"a_{key}_48.png"))
            render(svg, andr[key][1], 192)
            render(svg, andr[key][2], 48)

        W, ROW = 1600, 460
        parts = []
        for i, (bgc, txt) in enumerate((("#F4F5F7", "#6B7280"), ("#16171D", "#9CA3AF"))):
            y0 = i * ROW
            mono_key = "mono_l" if i == 0 else "mono_d"
            parts.append(f'<rect x="0" y="{y0}" width="{W}" height="{ROW}" fill="{bgc}"/>')

            def img(p, x, y, w, pix=False):
                st = ' style="image-rendering:pixelated"' if pix else ""
                parts.append(f'<image x="{x}" y="{y0 + y}" width="{w}" height="{w}" href="{b64(p)}"{st}/>')

            def label(t, x, y):
                parts.append(f'<text x="{x}" y="{y0 + y}" fill="{txt}" font-family="Helvetica" '
                             f'font-size="16" text-anchor="middle">{t}</text>')
            img(sizes[1024], 30, 40, 320)
            label("macOS 1024 (shown 320)", 190, 400)
            img(sizes[128], 380, 136, 128)
            label("128", 444, 400)
            img(sizes[64], 540, 168, 64)
            label("64", 572, 400)
            img(sizes[32], 630, 184, 32)
            label("32", 646, 400)
            img(sizes[16], 690, 192, 16)
            label("16", 698, 400)
            img(sizes[16], 730, 136, 128, pix=True)
            label("16 at 8x", 794, 400)
            img(andr["circle"][1], 900, 104, 192)
            label("Android circle", 996, 400)
            img(andr["squircle"][1], 1110, 104, 192)
            label("Android squircle", 1206, 400)
            img(andr[mono_key][1], 1320, 104, 192)
            label("Themed (mono)", 1416, 400)
            img(andr["circle"][2], 1000 - 24, 330 - 24, 48)
            img(andr["squircle"][2], 1210 - 24, 330 - 24, 48)
            img(andr[mono_key][2], 1420 - 24, 330 - 24, 48)
        sheet = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{2 * ROW}">'
                 + "".join(parts) + "</svg>")
        sheet_svg = os.path.join(tmp, "preview.svg")
        write(sheet_svg, sheet)
        subprocess.run(["rsvg-convert", "-o", os.path.join(out, "preview.png"), sheet_svg],
                       check=True)
        render(mac, os.path.join(out, "kopi-macos-1024.png"), 1024)
    finally:
        shutil.rmtree(tmp)
    print("ok")


if __name__ == "__main__":
    main()
