#!/usr/bin/env python3
"""Generates the apps' theme from the web's: one palette, one brand mark, for every platform (ADR 0123 decision 7).

The web's colour roles live in one file, `src/web/src/styles/base.css` (`:root` light, `:root.veyq-dark` dark; the
styleguide owns what each role is for), and its brand mark in `src/web/src/assets/brand/logo-{light,dark}.svg`. The
apps never copy either: this script reads them and writes what each platform reads, so a role the web changes is the
apps' change on their next build, and the WCAG floors `scripts/validate_web_contrast.py` holds the web's roles to hold
for the apps' too. Its CSS reader is that guard's own (`modes`, `resolve`), imported, never repeated.

Outputs (each optional, each a directory this script owns and rewrites whole):
  --kotlin DIR          `me/raddatz/veyq/mobile/theme/WebPalette.kt`: every colour role, light and dark, as ARGB.
  --android-res DIR     an Android res tree: the roles the window and splash need (`values[-night]/veyq_colors.xml`),
                        the mark as vector drawables (`drawable[-night]/veyq_mark.xml`), the launcher icon's layers,
                        the TV launcher's banner (`drawable/tv_banner.xml`, 320 x 180 dp).
  --apple-assets DIR    colour sets for every role and the mark's image set, inside an asset catalog
                        (`<DIR>/veyq-<role>.colorset`, `<DIR>/VeyqMark.imageset`), light and dark appearances, and
                        the app icon (`<DIR>/AppIcon.appiconset`: light, dark, tinted), rendered by macOS' Quick Look.
  --gtk DIR             the Linux app's (ADR 0123's Consequences, the Linux app): `style.css`, libadwaita's own colour
                        variables set from the web's roles (`GTK_ROLES`), dark under `prefers-color-scheme: dark`, and
                        the app icon `icons/scalable/apps/<id>.svg` with its symbolic variant (GNOME's: a
                        full-colour icon and a `-symbolic` one, by the application id, desktop-identity.json's `appId`)
                        and the desktop entry `<id>.desktop` (`Icon=<id>`); the app bundles DIR as its GResource, from
                        whose base path libadwaita loads `style.css` itself and GtkApplication adds `icons/` to the
                        icon theme, where the window's icon name finds it. A Flatpak installs the same files to hicolor.
  --winui DIR           the Windows app's (ADR 0123's Consequences, the Windows app): `Theme.xaml`, one
                        ResourceDictionary that makes the web's brand role (`accent`) the app's accent — `SystemAccentColor`
                        and each of its light and dark steps (`WINUI_ACCENT_KEYS`) — under `ThemeDictionaries`, light and
                        dark; everything else is Fluent's own; and the app icon `veyq.ico` (`WINDOWS_ICON_PX`, a PNG frame
                        each, rendered by Quick Look as Apple's are), the exe's and the window's; and the MSIX's PNG
                        assets under `msix/` (`MSIX_ASSETS`, the same plate each at its own size: ADR 0139 decision 6),
                        which the package build links in as `Assets\\`.

A role the apps paint with that base.css no longer sets is red (exit 1), never a fallback.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
import repo_layout  # noqa: E402
from validate_web_contrast import Unresolved, modes, resolve  # noqa: E402

BASE_CSS = repo_layout.WEB / "src" / "styles" / "base.css"
BRAND = repo_layout.WEB / "src" / "assets" / "brand"

# The roles the apps paint with: the web's names, the styleguide's meanings. Missing one is red.
REQUIRED = [
    "bg", "surface", "surface-muted", "text", "muted", "control-text", "line", "control-line", "control-bg", "field",
    "accent", "accent-soft", "primary", "primary-hover", "on-fill", "success", "success-soft", "success-text",
    "warning", "warning-soft", "warning-text", "danger", "danger-soft", "info", "info-soft", "info-text", "hover",
    "focus", "favorite", "scrim", "backdrop-strong",
]


def palette() -> dict[str, dict[str, tuple]]:
    """{mode: {role: (r, g, b, a)}} for every `--app-*` token that resolves to a colour."""
    out: dict[str, dict[str, tuple]] = {}
    for mode, tokens in modes(BASE_CSS.read_text()).items():
        roles = {}
        for name in tokens:
            if not name.startswith("--app-"):
                continue
            try:
                roles[name[len("--app-"):]] = resolve(f"var({name})", tokens)
            except Unresolved:
                continue  # a gradient, a shadow, a length: not a colour role
        out[mode] = roles
    missing = sorted({r for r in REQUIRED for m in out if r not in out[m]})
    if missing:
        sys.exit(f"base.css no longer sets the colour roles the apps paint with: {', '.join(missing)}")
    return out


def lengths() -> dict[str, int]:
    tokens = modes(BASE_CSS.read_text())["light"]
    return {k[len("--app-"):]: int(v[:-2]) for k, v in tokens.items() if re.fullmatch(r"\d+px", v) and "radius" in k}


def argb(c: tuple) -> int:
    r, g, b, a = c
    return (round(a * 255) << 24) | (round(r) << 16) | (round(g) << 8) | round(b)


def camel(role: str) -> str:
    head, *rest = role.split("-")
    return head + "".join(p.capitalize() for p in rest)


def write_kotlin(out: Path, pal: dict[str, dict[str, tuple]], radii: dict[str, int]) -> None:
    roles = sorted(set(pal["light"]) & set(pal["dark"]))
    lines = [
        "package me.raddatz.veyq.mobile.theme",
        "",
        "// Generated by src/mobile/theme/generate_theme.py from src/web/src/styles/base.css. Never edit.",
        "",
        "/** The web's colour roles (docs/crosscutting/ui-web-styleguide.md), one mode, each as ARGB. */",
        "class WebPalette(",
        *[f"    val {camel(r)}: Long," for r in roles],
        ")",
        "",
        "object WebTheme {",
        "    val light = WebPalette(",
        *[f"        {camel(r)} = 0x{argb(pal['light'][r]):08X}," for r in roles],
        "    )",
        "    val dark = WebPalette(",
        *[f"        {camel(r)} = 0x{argb(pal['dark'][r]):08X}," for r in roles],
        "    )",
        *[f"    const val {camel(k)}Px: Int = {v}" for k, v in sorted(radii.items())],
        "}",
        "",
    ]
    target = out / "me/raddatz/veyq/mobile/theme/WebPalette.kt"
    shutil.rmtree(out, ignore_errors=True)
    target.parent.mkdir(parents=True)
    target.write_text("\n".join(lines))


# ---- the mark (two filled paths with objectBoundingBox gradients under one flip) as an Android vector ----

SVG = "{http://www.w3.org/2000/svg}"


def _translate(transform: str) -> tuple[float, float]:
    m = re.search(r"translate\(\s*([-\d.]+)\s*,\s*([-\d.]+)\s*\)", transform or "")
    return (float(m.group(1)), float(m.group(2))) if m else (0.0, 0.0)


def _bbox(d: str) -> tuple[float, float, float, float]:
    nums = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", d)]
    xs, ys = nums[0::2], nums[1::2]
    return min(xs), min(ys), max(xs), max(ys)


def _pct(v: str) -> float:
    return float(v.rstrip("%")) / 100


def mark(svg_file: Path) -> dict:
    """The mark's viewBox, its group flip, and each path with its gradient in the path's own user space."""
    root = ET.parse(svg_file).getroot()
    vb = [float(n) for n in root.get("viewBox").split()]
    grads = {}
    for g in root.iter(SVG + "linearGradient"):
        stops = []
        for s in g.iter(SVG + "stop"):
            colour = re.search(r"stop-color:\s*(#[0-9a-fA-F]{6})", s.get("style", "")).group(1)
            stops.append((_pct(s.get("offset")), colour))
        grads[g.get("id")] = (tuple(_pct(g.get(k)) for k in ("x1", "y1", "x2", "y2")), stops)
    group = root.find(SVG + "g")
    paths = []
    for p in group.iter(SVG + "path"):
        d = " ".join(p.get("d").split())
        x0, y0, x1, y1 = _bbox(d)
        (gx1, gy1, gx2, gy2), stops = grads[re.search(r"url\(#([^)]+)\)", p.get("fill")).group(1)]
        w, h = x1 - x0, y1 - y0
        paths.append({
            "d": d,
            "translate": _translate(p.get("transform")),
            "gradient": (x0 + gx1 * w, y0 + gy1 * h, x0 + gx2 * w, y0 + gy2 * h),
            "stops": stops,
        })
    if group.get("transform", "").replace(" ", "") not in ("scale(1.000000,-1.000000)translate(0,-395)", "scale(1,-1)translate(0,-395)"):
        sys.exit(f"{svg_file.name}: the mark's group transform changed — teach generate_theme.py the new one")
    return {"viewBox": vb, "paths": paths}


A = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"


def vector(m: dict, size_dp: float, inset: float = 0.0, monochrome: str | None = None) -> str:
    """An Android vector drawable of the mark, scaled into a `size_dp` square with `inset` (0..1) padding each side."""
    _, _, vw, vh = m["viewBox"]
    side = max(vw, vh) / (1 - 2 * inset)
    ox, oy = (side - vw) / 2, (side - vh) / 2
    out = [f'<vector xmlns:android="{A}" xmlns:aapt="{AAPT}"',
           f'    android:width="{size_dp}dp" android:height="{size_dp}dp"',
           f'    android:viewportWidth="{side:.3f}" android:viewportHeight="{side:.3f}">',
           f'  <group android:translateX="{ox:.3f}" android:translateY="{oy + vh:.3f}" android:scaleY="-1">']
    for p in m["paths"]:
        tx, ty = p["translate"]
        out.append(f'    <group android:translateX="{tx:.6f}" android:translateY="{ty:.6f}">')
        if monochrome:
            out.append(f'      <path android:fillColor="{monochrome}" android:pathData="{p["d"]}"/>')
        else:
            sx, sy, ex, ey = p["gradient"]
            out += [f'      <path android:pathData="{p["d"]}">',
                    '        <aapt:attr name="android:fillColor">',
                    f'          <gradient android:type="linear" android:startX="{sx:.3f}" android:startY="{sy:.3f}"'
                    f' android:endX="{ex:.3f}" android:endY="{ey:.3f}">']
            out += [f'            <item android:offset="{o:.3f}" android:color="{c}"/>' for o, c in p["stops"]]
            out += ['          </gradient>', '        </aapt:attr>', '      </path>']
        out.append('    </group>')
    out += ['  </group>', '</vector>', '']
    return "\n".join(out)


def banner(m: dict, background: str, width_dp: int = 320, height_dp: int = 180, mark_share: float = 0.5) -> str:
    """The TV launcher's banner (Android TV: 320 x 180 dp): the mark centred on [background], `mark_share` of its height."""
    _, _, vw, vh = m["viewBox"]
    # `vector`'s groups centre the mark in a square of its longer side, which is placed here
    side = max(vw, vh)
    scale = height_dp * mark_share / side
    ox, oy = (width_dp - side * scale) / 2, (height_dp - side * scale) / 2
    out = [f'<vector xmlns:android="{A}" xmlns:aapt="{AAPT}"',
           f'    android:width="{width_dp}dp" android:height="{height_dp}dp"',
           f'    android:viewportWidth="{width_dp}" android:viewportHeight="{height_dp}">',
           f'  <path android:fillColor="{background}" android:pathData="M0,0h{width_dp}v{height_dp}h-{width_dp}z"/>',
           f'  <group android:translateX="{ox:.3f}" android:translateY="{oy:.3f}" android:scaleX="{scale:.6f}" android:scaleY="{scale:.6f}">']
    # the mark's own drawable, its viewport the mark's: the inner groups as `vector` writes them
    inner = vector(m, vh).splitlines()
    start = next(i for i, line in enumerate(inner) if line.startswith("  <group"))
    end = max(i for i, line in enumerate(inner) if line.strip() == "</group>")
    out += ["  " + line for line in inner[start:end + 1]]
    out += ['  </group>', '</vector>', '']
    return "\n".join(out)


def colour_hex(c: tuple) -> str:
    return "#{:02X}{:02X}{:02X}{:02X}".format(round(c[3] * 255), round(c[0]), round(c[1]), round(c[2]))


def write_android(out: Path, pal: dict[str, dict[str, tuple]]) -> None:
    shutil.rmtree(out, ignore_errors=True)
    light, dark = mark(BRAND / "logo-light.svg"), mark(BRAND / "logo-dark.svg")
    files = {}
    for folder, mode in (("values", "light"), ("values-night", "dark")):
        rows = [f'    <color name="veyq_{r.replace("-", "_")}">{colour_hex(pal[mode][r])}</color>' for r in REQUIRED]
        files[f"{folder}/veyq_colors.xml"] = "\n".join(['<?xml version="1.0" encoding="utf-8"?>',
                                                         "<!-- Generated from src/web/src/styles/base.css. Never edit. -->",
                                                         "<resources>", *rows, "</resources>", ""])
    files["drawable/veyq_mark.xml"] = vector(light, 24)
    files["drawable-night/veyq_mark.xml"] = vector(dark, 24)
    # Adaptive launcher icon: 108dp layers, the mark inside the 66dp safe zone; background is the web's bg role.
    files["drawable/ic_launcher_foreground.xml"] = vector(light, 108, inset=0.30)
    files["drawable-night/ic_launcher_foreground.xml"] = vector(dark, 108, inset=0.30)
    files["drawable/ic_launcher_monochrome.xml"] = vector(light, 108, inset=0.30, monochrome="#FFFFFFFF")
    files["mipmap-anydpi-v26/ic_launcher.xml"] = "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        f'<adaptive-icon xmlns:android="{A}">',
        '    <background android:drawable="@color/veyq_surface"/>',
        '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>',
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>',
        '</adaptive-icon>', ''])
    # The TV launcher's banner (ADR 0159 decision 1): the mark on the dark surface, as the TV's launcher is dark.
    files["drawable/tv_banner.xml"] = banner(dark, colour_hex(pal["dark"]["surface"]))
    for rel, text in files.items():
        f = out / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text)


def write_apple(out: Path, pal: dict[str, dict[str, tuple]]) -> None:
    """Colour sets for every role (named `veyq-<role>`) and the mark, into an asset catalog folder."""
    for old in out.glob("veyq-*.colorset"):
        shutil.rmtree(old)
    info = {"author": "generate_theme.py", "version": 1}

    def comp(c: tuple) -> dict:
        return {"color-space": "srgb", "components": {
            "red": f"{c[0] / 255:.4f}", "green": f"{c[1] / 255:.4f}", "blue": f"{c[2] / 255:.4f}", "alpha": f"{c[3]:.4f}"}}

    for role in sorted(set(pal["light"]) & set(pal["dark"])):
        d = out / f"veyq-{role}.colorset"
        d.mkdir(parents=True)
        (d / "Contents.json").write_text(json.dumps({"colors": [
            {"idiom": "universal", "color": comp(pal["light"][role])},
            {"idiom": "universal", "appearances": [{"appearance": "luminosity", "value": "dark"}], "color": comp(pal["dark"][role])},
        ], "info": info}, indent=2))
    mark_set = out / "VeyqMark.imageset"
    shutil.rmtree(mark_set, ignore_errors=True)
    mark_set.mkdir(parents=True)
    shutil.copy(BRAND / "logo-light.svg", mark_set / "veyq-mark-light.svg")
    shutil.copy(BRAND / "logo-dark.svg", mark_set / "veyq-mark-dark.svg")
    (mark_set / "Contents.json").write_text(json.dumps({"images": [
        {"idiom": "universal", "filename": "veyq-mark-light.svg"},
        {"idiom": "universal", "filename": "veyq-mark-dark.svg", "appearances": [{"appearance": "luminosity", "value": "dark"}]},
    ], "info": info, "properties": {"preserves-vector-representation": True}}, indent=2))


def _icon_svg(logo: Path, background: str | None, mono: str | None = None, mac: bool = False,
              plate_px: int | None = None) -> str:
    """A 1024 square: the mark centred over `background` (None: transparent). iOS masks the square itself; a macOS
    icon carries its own rounded square (824 on the 1024 grid, Apple's macOS icon template) and its own margin.
    `plate_px` is Windows': a rounded plate of `background` (896 on the grid, a 1/16 margin) with a one-pixel edge at
    that frame's size, so the plate holds its edge from 16 px to 256 on a light taskbar as on a dark one."""
    ET.register_namespace("", SVG[1:-1])
    root = ET.parse(logo).getroot()
    _, _, vw, vh = [float(n) for n in root.get("viewBox").split()]
    side = 1024 * (0.46 if mac else 0.80 if plate_px else 0.58)  # Windows' mark fills its plate: it must read at 16 px
    w, h = (side, side * vh / vw) if vw >= vh else (side * vw / vh, side)
    root.set("x", f"{(1024 - w) / 2:.2f}")
    root.set("y", f"{(1024 - h) / 2:.2f}")
    root.set("width", f"{w:.2f}")
    root.set("height", f"{h:.2f}")
    if mono:
        for p in root.iter(SVG + "path"):
            p.set("fill", mono)
    inner = ET.tostring(root, encoding="unicode")
    if mac:
        bg = (f'<rect x="100" y="100" width="824" height="824" rx="185" fill="{background}" '
              f'stroke="#000000" stroke-opacity="0.06" stroke-width="2"/>')
    elif plate_px:
        edge = 1024 / plate_px
        bg = (f'<rect x="{64 + edge / 2:.2f}" y="{64 + edge / 2:.2f}" width="{896 - edge:.2f}" height="{896 - edge:.2f}" '
              f'rx="{144 - edge / 2:.2f}" fill="{background}" stroke="#000000" stroke-opacity="0.16" '
              f'stroke-width="{edge:.2f}"/>')
    else:
        bg = f'<rect width="1024" height="1024" fill="{background}"/>' if background else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">{bg}{inner}</svg>'


def write_apple_icon(out: Path, pal: dict[str, dict[str, tuple]]) -> None:
    """The app icon set (light, dark, tinted) as 1024 PNGs, rendered by macOS' own Quick Look — no tool to install."""
    import subprocess
    import tempfile

    hexrgb = lambda c: "#{:02x}{:02x}{:02x}".format(*(round(v) for v in c[:3]))  # noqa: E731
    variants = {
        "veyq-icon-light": _icon_svg(BRAND / "logo-light.svg", hexrgb(pal["light"]["surface"])),
        "veyq-icon-dark": _icon_svg(BRAND / "logo-dark.svg", None),
        "veyq-icon-tinted": _icon_svg(BRAND / "logo-light.svg", "#000000", mono="#ffffff"),
        "veyq-icon-mac": _icon_svg(BRAND / "logo-light.svg", hexrgb(pal["light"]["surface"]), mac=True),
    }
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, svg in variants.items():
            src = Path(tmp) / f"{name}.svg"
            src.write_text(svg)
            subprocess.run(["qlmanage", "-t", "-s", "1024", "-o", tmp, str(src)], check=True, capture_output=True)
            shutil.move(str(Path(tmp) / f"{name}.svg.png"), out / f"{name}.png")
    images = [
        {"idiom": "universal", "platform": "ios", "size": "1024x1024", "filename": "veyq-icon-light.png"},
        {"idiom": "universal", "platform": "ios", "size": "1024x1024", "filename": "veyq-icon-dark.png",
         "appearances": [{"appearance": "luminosity", "value": "dark"}]},
        {"idiom": "universal", "platform": "ios", "size": "1024x1024", "filename": "veyq-icon-tinted.png",
         "appearances": [{"appearance": "luminosity", "value": "tinted"}]},
    ]
    # macOS takes its icon in ten fixed sizes, scaled down from the one rendering by macOS' own `sips`.
    for pt in (16, 32, 128, 256, 512):
        for scale in (1, 2):
            px = pt * scale
            name = f"veyq-icon-mac-{px}.png"
            if not (out / name).exists():
                subprocess.run(["sips", "-z", str(px), str(px), str(out / "veyq-icon-mac.png"), "--out", str(out / name)],
                               check=True, capture_output=True)
            images.append({"idiom": "mac", "size": f"{pt}x{pt}", "scale": f"{scale}x", "filename": name})
    (out / "veyq-icon-mac.png").unlink()
    (out / "Contents.json").write_text(json.dumps({"images": images, "info": {"author": "generate_theme.py", "version": 1}}, indent=2))


# ---- Linux (libadwaita) ----

# The Linux app's id, which names its icon and its desktop entry: defined once, in desktop-identity.json (ADR 0139
# decision 12), never spelled here.
APP_ID = json.loads((repo_layout.MOBILE / "desktop-identity.json").read_text())["appId"]

# libadwaita's colour variables (1.9: what its own stylesheet declares) from the web's roles, the way Android's scheme
# takes them (Theme.kt): the accent (purple) is the brand and the selection, libadwaita's accent; danger is destructive
# and error. Only colours: libadwaita 1.9's one length variable, `--window-radius`, is the window's own shape, which
# the platform keeps (ADR 0123 decision 9). The web's primary (green, creating) has no libadwaita slot.
GTK_ROLES = {
    "accent-bg-color": "accent", "accent-fg-color": "on-fill", "accent-color": "accent",
    "destructive-bg-color": "danger", "destructive-fg-color": "on-fill", "destructive-color": "danger",
    "error-bg-color": "danger", "error-fg-color": "on-fill", "error-color": "danger",
    "success-bg-color": "success", "success-fg-color": "on-fill", "success-color": "success-text",
    "warning-bg-color": "warning", "warning-fg-color": "backdrop-strong", "warning-color": "warning-text",
    "window-bg-color": "bg", "window-fg-color": "text",
    "view-bg-color": "surface", "view-fg-color": "text",
    "headerbar-bg-color": "bg", "headerbar-fg-color": "text",
    "sidebar-bg-color": "surface-muted", "sidebar-fg-color": "text",
    "card-bg-color": "surface", "card-fg-color": "text",
    "dialog-bg-color": "surface", "dialog-fg-color": "text",
    "popover-bg-color": "surface", "popover-fg-color": "text",
}


def css_colour(c: tuple) -> str:
    r, g, b, a = c
    return f"rgb({round(r)} {round(g)} {round(b)} / {round(a * 100)}%)" if a < 1 else "#{:02x}{:02x}{:02x}".format(round(r), round(g), round(b))


def desktop_entry() -> str:
    """The app's desktop entry: its name the catalog's (`app_name`, one word in every language), its icon the theme's
    by the application id, which is also the file's name — how GNOME's shell matches the running window to it. It claims
    `veyq:` (ADR 0150 § O3): `Exec=veyq %u` hands the link to the app, whose `HANDLES_OPEN` passes a second launch's on to
    the running one over D-Bus; the link carries nothing, since it reaches this command line."""
    name = json.loads((repo_layout.MOBILE / "words" / "words.json").read_text())["app_name"]["en"]
    return "\n".join(["[Desktop Entry]", "Type=Application", f"Name={name}", "Exec=veyq %u", f"Icon={APP_ID}",
                      "Terminal=false", "Categories=Utility;", "MimeType=x-scheme-handler/veyq;", "StartupNotify=true", ""])


def write_gtk(out: Path, pal: dict[str, dict[str, tuple]]) -> None:
    """libadwaita's style.css (one file: 1.9 deprecates style-dark.css for a media query) and the app icon."""
    missing = sorted({role for role in GTK_ROLES.values() if role not in REQUIRED})
    if missing:
        sys.exit(f"GTK_ROLES names roles the apps do not require from base.css: {', '.join(missing)}")

    def block(mode: str, indent: str) -> list[str]:
        return [f"{indent}  --{var}: {css_colour(pal[mode][role])};" for var, role in GTK_ROLES.items()]

    css = ["/* Generated by src/mobile/theme/generate_theme.py from src/web/src/styles/base.css. Never edit. */",
           ":root {", *block("light", ""), "}", "", "@media (prefers-color-scheme: dark) {", "  :root {",
           *block("dark", "  "), "  }", "}", ""]
    files = {
        "style.css": "\n".join(css),
        f"icons/scalable/apps/{APP_ID}.svg": _icon_svg(BRAND / "logo-light.svg", None) + "\n",
        f"icons/symbolic/apps/{APP_ID}-symbolic.svg": _icon_svg(BRAND / "logo-light.svg", None, mono="#241f31") + "\n",
        f"{APP_ID}.desktop": desktop_entry(),
    }
    replace_in_place(out, files)


def replace_in_place(out: Path, files: dict[str, str]) -> None:
    """[out] made to hold exactly [files], never emptied on the way: each file written beside itself and renamed over
    the old one (a reader sees the old bytes or the new), a file no longer generated removed after. The directory is
    read while it is written — a `run.py run` mounts it into a container while another verb of the same tree
    generates (Linux's build.rs stopped on `no style.css`, inbox #1428) — so it is never removed and made anew."""
    for rel, text in files.items():
        f = out / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        if f.is_file() and f.read_text() == text:
            continue
        part = f.with_name(f".{f.name}.part")
        part.write_text(text)
        part.replace(f)
    kept = {out / rel for rel in files}
    for f in sorted(out.rglob("*"), reverse=True):
        if f.is_file() and f not in kept:
            f.unlink()
        elif f.is_dir() and not any(f.iterdir()):
            f.rmdir()


# ---- Windows (WinUI 3) ----

# The keys WinUI's own styles read the accent from (the system's accent and its six steps: a light theme fills with
# Dark1 and writes accent text with Dark2/Dark3, a dark theme fills with Light2 and writes with Light3). Each is the
# web's accent of that mode, as the web fills and writes with the one role; contrast is base.css's, held by
# validate_web_contrast.py (`on-fill` on `accent`, Fluent's text on an accent fill being white in light, black in dark).
WINUI_ACCENT_KEYS = ("SystemAccentColor", "SystemAccentColorLight1", "SystemAccentColorLight2", "SystemAccentColorLight3",
                     "SystemAccentColorDark1", "SystemAccentColorDark2", "SystemAccentColorDark3")


def xaml_colour(c: tuple) -> str:
    return "#{:02X}{:02X}{:02X}{:02X}".format(round(c[3] * 255), round(c[0]), round(c[1]), round(c[2]))


# The sizes Windows draws an app's icon at (the shell's 100 to 400 % scales of 16, 24, 32 and 48: the title bar, the
# taskbar, Start, Explorer's views); a frame each, so no size is a downscale Windows makes itself.
WINDOWS_ICON_PX = (16, 20, 24, 32, 40, 48, 64, 256)


def ico(frames: dict[int, bytes]) -> bytes:
    """An ICO container around PNG frames (Windows Vista on reads PNG in every frame): the 6-byte header, a 16-byte
    entry per frame (width and height 0 meaning 256, 32 bits per pixel), then each PNG as it is."""
    import struct

    head = struct.pack("<HHH", 0, 1, len(frames))
    offset = len(head) + 16 * len(frames)
    entries, data = b"", b""
    for px, png in sorted(frames.items()):
        entries += struct.pack("<BBBBHHII", px % 256, px % 256, 0, 0, 1, 32, len(png), offset + len(data))
        data += png
    return head + entries + data


# The MSIX's images (ADR 0139 decision 6), each a file name the package's manifest names (Package.appxmanifest) with
# its MRT qualifier, and its pixels: the Start and taskbar icon (Square44x44Logo) at the shell's scales and at the target
# sizes the taskbar and Explorer take unscaled (the `altform-unplated` ones are drawn without the shell's plate, which
# the icon carries itself), the medium tile (Square150x150Logo) and the package's logo (StoreLogo), 100 to 400 %. No
# wide tile: Windows 11 draws none, and Quick Look renders only squares.
MSIX_ASSETS = {
    **{f"Square44x44Logo.scale-{s}.png": round(44 * s / 100) for s in (100, 125, 150, 200, 400)},
    **{f"Square44x44Logo.targetsize-{t}{alt}.png": t for t in (16, 24, 32, 48, 256) for alt in ("", "_altform-unplated")},
    **{f"Square150x150Logo.scale-{s}.png": round(150 * s / 100) for s in (100, 125, 150, 200, 400)},
    **{f"StoreLogo.scale-{s}.png": round(50 * s / 100) for s in (100, 125, 150, 200, 400)},
}


def plates(pal: dict[str, dict[str, tuple]], sizes: set[int]) -> dict[int, bytes]:
    """The Windows icon at each of [sizes]: the light mark on a plate of the web's light surface, each a PNG rendered at
    its own size by macOS' Quick Look. One image cannot follow the taskbar's theme, and the light mark's grey stroke
    has no contrast on a dark taskbar nor the dark mark's deep purple on one: the plate keeps the mark on the
    background it was drawn for."""
    import subprocess
    import tempfile

    hexrgb = lambda c: "#{:02x}{:02x}{:02x}".format(*(round(v) for v in c[:3]))  # noqa: E731
    frames = {}
    with tempfile.TemporaryDirectory() as tmp:
        for px in sorted(sizes):
            src = Path(tmp) / f"veyq-{px}.svg"
            src.write_text(_icon_svg(BRAND / "logo-light.svg", hexrgb(pal["light"]["surface"]), plate_px=px))
            subprocess.run(["qlmanage", "-t", "-s", str(px), "-o", tmp, str(src)], check=True, capture_output=True)
            png = (Path(tmp) / f"{src.name}.png").read_bytes()
            if png[:8] != b"\x89PNG\r\n\x1a\n" or png[16:24] != px.to_bytes(4, "big") * 2:
                sys.exit(f"Quick Look rendered no {px} px PNG for the Windows icon")
            frames[px] = png
    return frames


def write_windows_icon(out: Path, pal: dict[str, dict[str, tuple]]) -> None:
    """`veyq.ico` (a frame per WINDOWS_ICON_PX) and the MSIX's images under `msix/`, all from one rendering per size."""
    frames = plates(pal, set(WINDOWS_ICON_PX) | set(MSIX_ASSETS.values()))
    (out / "veyq.ico").write_bytes(ico({px: frames[px] for px in WINDOWS_ICON_PX}))
    (out / "msix").mkdir()
    for name, px in MSIX_ASSETS.items():
        (out / "msix" / name).write_bytes(frames[px])


def write_winui(out: Path, pal: dict[str, dict[str, tuple]]) -> None:
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    write_windows_icon(out, pal)

    def theme(key: str, mode: str) -> list[str]:
        return [f'    <ResourceDictionary x:Key="{key}">',
                *[f'      <Color x:Key="{k}">{xaml_colour(pal[mode]["accent"])}</Color>' for k in WINUI_ACCENT_KEYS],
                "    </ResourceDictionary>"]

    xaml = ['<?xml version="1.0" encoding="utf-8"?>',
            "<!-- Generated by src/mobile/theme/generate_theme.py from src/web/src/styles/base.css. Never edit. -->",
            '<ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"',
            '                    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">',
            "  <ResourceDictionary.ThemeDictionaries>",
            *theme("Light", "light"), *theme("Dark", "dark"),
            "  </ResourceDictionary.ThemeDictionaries>",
            "</ResourceDictionary>", ""]
    (out / "Theme.xaml").write_text("\n".join(xaml), encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--kotlin", type=Path)
    ap.add_argument("--android-res", type=Path)
    ap.add_argument("--apple-assets", type=Path)
    ap.add_argument("--gtk", type=Path)
    ap.add_argument("--winui", type=Path)
    a = ap.parse_args()
    pal = palette()
    if a.kotlin:
        write_kotlin(a.kotlin, pal, lengths())
    if a.android_res:
        write_android(a.android_res, pal)
    if a.apple_assets:
        write_apple(a.apple_assets, pal)
        write_apple_icon(a.apple_assets / "AppIcon.appiconset", pal)
    if a.gtk:
        write_gtk(a.gtk, pal)
    if a.winui:
        write_winui(a.winui, pal)


if __name__ == "__main__":
    main()
