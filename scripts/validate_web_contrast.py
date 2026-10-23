#!/usr/bin/env python3
"""Guard that the browser surfaces' key colour pairs meet WCAG AA, in light and in dark, computed from the tokens.

**What it asserts.** Each sheet in `SHEETS` — `src/web/src/styles/base.css` and `src/admin-web/src/styles/base.css`, the
deliberate copy ADR 0065 keeps (inbox #390) — is read the way the browser cascades it: every `:root { … }` block
gives the light values, `:root.veyq-dark { … }` overrides them for dark, and the `--p-*` declarations in `:root`
(the block that routes PrimeVue's colours onto our role tokens) are resolved per mode through those tokens —
`var()`, `color-mix(in srgb, …)`, hex and `rgb()/rgba()`, with alpha composited over the surface. For each pair in
`PAIRS` and each mode, the WCAG 2.x contrast ratio (relative luminance, `(L1+0.05)/(L2+0.05)`) must reach its floor:
**4.5:1** for text, **3:1** for a UI component's boundary or fill (WCAG 1.4.3 / 1.4.11). A pair that reads a
`--p-*` variable base.css does not set is **red, not skipped**: it means that colour falls back to the PrimeVue
preset (Aura), which this buildless job cannot see — the routing was deleted, so nothing pins the pair any more. Both sheets answer to the same `PAIRS`: they carry the
same role vocabulary, so a role pair one surface does not paint today is still the pair its next component would.
A missing sheet is red, never a silent skip.

**The defect that bought it.** inbox #388 (from #385 (g)): the green primary button — Aura's emerald-500 `#10b981`
under white text — measured **2.54:1**, app-wide, on every Save/Create/Confirm, for as long as the app had used
PrimeVue's default primary. The same audit found 36 failing pairs (light and dark): Aura's danger/warn buttons and
their text variants, its warn/success/error messages and toasts (2.8–4.4:1), its input/checkbox/switch boundaries
(1.5:1 light, 2.2:1 dark), and white text on the dark theme's light fills (accent badges, the danger dialog
button, the date picker's selected day: 1.9–2.8:1). Nothing failed a build: colour is invisible to every test.

**Its limit.** Five.
  (a) **It proves the TOKENS, not the rendered page.** A component that paints a raw colour, or pairs a token with
      a background this list never names, is not seen. The pairs are the role pairs the styleguide prescribes
      (text on surface, a role's text on its soft fill, on-fill on a role's fill, the PrimeVue routing); a new
      pairing in a component needs a row here, and nothing forces one. The #388 audit that found the 36 also
      measured the rendered harness with `getComputedStyle` — that is the proof of the page, this is the pin.
  (b) **It trusts that the routing reaches PrimeVue.** It checks that `--p-primary-color` & co. are SET to our
      tokens; whether PrimeVue still reads a variable of that name (a preset rename) needs `node_modules`, which
      the trust-chain job does not have. A rename shows as the old Aura colour in the browser, not as red here.
  (c) **Gradients, images and media scrims are out of scope**: a label over a photo or over
      `linear-gradient(…)` has no single background (the styleguide's "rendered content" exception).
  (d) **Disabled controls are exempt** (WCAG 1.4.3 "inactive user interface component"): PrimeVue paints them at
      `opacity: .6`, and no pair here measures that.
  (e) **Two sheets, one list — not one palette.** The copies may drift apart (that is the point of ADR 0065), and
      each is held to the floor on its own values; nothing here says they must agree. A third browser surface is
      a row in `SHEETS`, and nothing forces one. (Before inbox #390 `src/admin-web/` was not covered and failed the
      same pairs on Aura's defaults: 23 light, 11 dark, measured on the rendered harness.)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import repo_layout  # where the code surfaces live (ADR 0106)

ROOT = Path(__file__).resolve().parent.parent
SHEETS = [repo_layout.WEB / "src" / "styles" / "base.css", repo_layout.ADMIN_WEB / "src" / "styles" / "base.css"]

TEXT, UI = 4.5, 3.0
# (label, foreground, background, floor). Expressions are CSS as the stylesheets write them.
PAIRS = [
    # The UI layer's components (ADR 0107) paint from the roles directly: buttons, text fields, checkboxes, switches,
    # toggle groups, messages, avatars, progress (`src/web/src/shared/ui/layer.css`).
    ("primary button label", "var(--app-on-fill)", "var(--app-primary)", TEXT),
    ("primary button label (hover)", "var(--app-on-fill)", "var(--app-primary-hover)", TEXT),
    ("primary button label (active)", "var(--app-on-fill)", "var(--app-primary-active)", TEXT),
    ("primary as text (text/outlined button, markdown link)", "var(--app-primary)", "var(--app-surface)", TEXT),
    ("primary as text on success-soft (outlined/text button hover)", "var(--app-primary)", "var(--app-success-soft)", TEXT),
    ("primary as a checked checkbox/switch fill", "var(--app-primary)", "var(--app-surface)", UI),
    ("focus ring on surface", "var(--app-focus)", "var(--app-surface)", UI),
    ("focus ring on page background", "var(--app-focus)", "var(--app-bg)", UI),
    ("secondary button label", "var(--app-control-text)", "var(--app-surface-muted)", TEXT),
    ("secondary button label (hover)", "var(--app-control-text)", "var(--app-line)", TEXT),
    ("danger button label", "var(--app-on-fill)", "var(--app-danger)", TEXT),
    ("danger button label (hover)", "var(--app-on-fill)", "var(--app-danger-strong)", TEXT),
    ("danger outlined/text label", "var(--app-danger)", "var(--app-surface)", TEXT),
    ("danger outlined/text label (hover)", "var(--app-danger)", "var(--app-danger-soft)", TEXT),
    ("warn button label", "var(--app-backdrop-strong)", "var(--app-warning)", TEXT),
    ("warn button label (hover)", "var(--app-backdrop-strong)",
     "color-mix(in srgb, var(--app-warning) 86%, var(--app-backdrop-strong))", TEXT),
    ("warn button label (active)", "var(--app-backdrop-strong)",
     "color-mix(in srgb, var(--app-warning) 76%, var(--app-backdrop-strong))", TEXT),
    ("warn outlined/text label", "var(--app-warning-text)", "var(--app-surface)", TEXT),
    ("message error", "var(--app-danger)", "var(--app-danger-soft)", TEXT),
    ("message warn", "var(--app-warning-text)", "var(--app-warning-soft)", TEXT),
    ("message success", "var(--app-success-text)", "var(--app-success-soft)", TEXT),
    ("message info", "var(--app-info)", "var(--app-info-soft)", TEXT),
    ("text-field text on its field", "var(--app-control-text)", "var(--app-field)", TEXT),
    ("text-field placeholder on its field", "var(--app-muted)", "var(--app-field)", TEXT),
    ("form-field boundary on the field", "var(--app-control-line)", "var(--app-field)", UI),
    ("form-field boundary (and its icons) on surface", "var(--app-control-line)", "var(--app-surface)", UI),
    ("form-field boundary on page background", "var(--app-control-line)", "var(--app-bg)", UI),
    ("switch track (off) on surface", "var(--app-control-line)", "var(--app-surface)", UI),
    ("toggle-group option on its track", "var(--app-muted)", "var(--app-segment-track)", TEXT),
    ("toggle-group picked option", "var(--app-text)", "var(--app-segment-thumb)", TEXT),
    ("avatar initial", "var(--app-control-text)", "var(--app-line)", TEXT),
    ("toast error", "var(--app-danger)", "var(--app-danger-soft)", TEXT),
    ("toast success", "var(--app-success-text)", "var(--app-success-soft)", TEXT),
    ("toast info", "var(--app-info)", "var(--app-info-soft)", TEXT),
    ("tooltip text", "var(--app-on-strong)", "var(--app-tooltip)", TEXT),
    ("menu / select entry on its highlight", "var(--app-text)", "var(--app-surface-muted)", TEXT),
    ("picked select option", "var(--app-primary)", "var(--app-success-soft)", TEXT),
    ("tab label on the tab bar", "var(--app-muted)", "var(--app-surface)", TEXT),
    ("on-fill on accent (badges, share submit)", "var(--app-on-fill)", "var(--app-accent)", TEXT),
    ("on-fill on success (date picker selection)", "var(--app-on-fill)", "var(--app-success)", TEXT),
    ("on-fill on danger (.veyq-dialog-button-danger)", "var(--app-on-fill)", "var(--app-danger)", TEXT),
    ("on-fill on danger-strong (its hover)", "var(--app-on-fill)", "var(--app-danger-strong)", TEXT),
    ("text on surface", "var(--app-text)", "var(--app-surface)", TEXT),
    ("text on page background", "var(--app-text)", "var(--app-bg)", TEXT),
    ("muted text on surface", "var(--app-muted)", "var(--app-surface)", TEXT),
    ("muted text on page background", "var(--app-muted)", "var(--app-bg)", TEXT),
    ("muted text on surface-muted (.veyq-badge)", "var(--app-muted)", "var(--app-surface-muted)", TEXT),
    ("muted text on a hovered row", "var(--app-muted)", "var(--app-hover)", TEXT),
    # PrimeVue's own muted text (inbox #395), still painted by its overlays (the dialog's close button, a select's
    # placeholder): Aura's surface-500 `#64748b` was 4.48:1 on the page background, so the block routes it onto --app-muted.
    ("PrimeVue muted text on page background", "var(--p-text-muted-color)", "var(--app-bg)", TEXT),
    ("PrimeVue muted text on surface", "var(--p-text-muted-color)", "var(--app-surface)", TEXT),
    ("secondary outlined/text label on page background", "var(--app-muted)", "var(--app-bg)", TEXT),
    ("secondary outlined/text label on its hover", "var(--app-muted)", "var(--app-hover)", TEXT),
    ("accent text / link on surface", "var(--app-accent)", "var(--app-surface)", TEXT),
    ("accent text on accent-soft", "var(--app-accent)", "var(--app-accent-soft)", TEXT),
    ("success badge", "var(--app-success-text)", "var(--app-success-soft)", TEXT),
    ("warning badge", "var(--app-warning-text)", "var(--app-warning-soft)", TEXT),
    ("danger text on danger-soft (.veyq-banner)", "var(--app-danger)", "var(--app-danger-soft)", TEXT),
    ("control text on control background", "var(--app-control-text)", "var(--app-control-bg)", TEXT),
]

Colour = tuple  # (r, g, b, alpha) with r/g/b in 0..255


def blocks(css: str, selector: str) -> dict[str, str]:
    """Custom properties declared in every top-level block whose selector is exactly `selector`."""
    out: dict[str, str] = {}
    for m in re.finditer(r"(?m)^" + re.escape(selector) + r"\s*\{", css):
        depth, i = 1, m.end()
        while depth:
            depth += {"{": 1, "}": -1}.get(css[i], 0)
            i += 1
        for d in re.finditer(r"(--[\w-]+)\s*:\s*([^;]+);", css[m.end():i - 1]):
            out[d.group(1)] = d.group(2).replace("!important", "").strip()
    return out


def split_args(s: str) -> list[str]:
    parts, depth, cur = [], 0, ""
    for ch in s:
        depth += {"(": 1, ")": -1}.get(ch, 0)
        if ch == "," and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    return parts + [cur.strip()]


def literal(s: str) -> Colour | None:
    s = s.strip().lower()
    if s == "transparent":
        return (0.0, 0.0, 0.0, 0.0)
    m = re.fullmatch(r"#([0-9a-f]{3}|[0-9a-f]{6})", s)
    if m:
        h = m.group(1) if len(m.group(1)) == 6 else "".join(c * 2 for c in m.group(1))
        return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 1.0)
    m = re.fullmatch(r"rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)\s*(?:[,/]\s*([\d.]+)(%?))?\s*\)", s)
    if m:
        a = 1.0 if m.group(4) is None else float(m.group(4)) / (100 if m.group(5) else 1)
        return (float(m.group(1)), float(m.group(2)), float(m.group(3)), a)
    return None


class Unresolved(Exception):
    pass


def resolve(expr: str, tokens: dict[str, str], seen: tuple = ()) -> Colour:
    expr = expr.strip()
    m = re.fullmatch(r"var\(\s*(--[\w-]+)\s*(?:,\s*(.+))?\)", expr)
    if m:
        name = m.group(1)
        if name in seen:
            raise Unresolved(f"{name} refers to itself")
        if name in tokens:
            return resolve(tokens[name], tokens, seen + (name,))
        raise Unresolved(f"{name} is not set in base.css")  # a fallback is the preset's job, never ours
    m = re.fullmatch(r"color-mix\(\s*in srgb\s*,(.+)\)", expr)
    if m:
        (a, pa), (b, pb) = [(re.sub(r"\s+[\d.]+%$", "", p), (re.findall(r"\s([\d.]+)%$", p) or [None])[0])
                            for p in split_args(m.group(1))]
        pa = float(pa) if pa else (100 - float(pb) if pb else 50.0)
        pb = float(pb) if pb else 100 - pa
        ca, cb = resolve(a, tokens, seen), resolve(b, tokens, seen)
        wa, wb = pa / (pa + pb), pb / (pa + pb)
        alpha = ca[3] * wa + cb[3] * wb
        if alpha == 0:
            return (0.0, 0.0, 0.0, 0.0)
        return tuple((ca[i] * ca[3] * wa + cb[i] * cb[3] * wb) / alpha for i in range(3)) + (alpha,)
    c = literal(expr)
    if c is None:
        raise Unresolved(f"cannot read colour {expr!r}")
    return c


def over(top: Colour, under: Colour) -> Colour:
    return tuple(top[i] * top[3] + under[i] * (1 - top[3]) for i in range(3)) + (1.0,)


def luminance(c: Colour) -> float:
    def ch(v: float) -> float:
        v /= 255
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])


def ratio(a: Colour, b: Colour) -> float:
    hi, lo = sorted((luminance(a), luminance(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def modes(css: str) -> dict[str, dict[str, str]]:
    light = blocks(css, ":root")
    return {"light": light, "dark": {**light, **blocks(css, ":root.veyq-dark")}}


def check(css: str, name: str = "base.css") -> tuple[list[str], int]:
    failures, checked = [], 0
    for mode, tokens in modes(css).items():
        if "--app-surface" not in tokens:
            return [f"{mode}: --app-surface is not defined in {name} — nothing to measure on"], 0
        surface = resolve("var(--app-surface)", tokens)
        for label, fg, bg, floor in PAIRS:
            try:
                b = resolve(bg, tokens)
                b = over(b, surface) if b[3] < 1 else b
                f = resolve(fg, tokens)
                f = over(f, b) if f[3] < 1 else f
            except Unresolved as e:
                failures.append(f"{mode}: {label}: {e}")
                continue
            checked += 1
            r = ratio(f, b)
            if r + 1e-9 < floor:
                failures.append(f"{mode}: {label}: {r:.2f}:1 < {floor}:1 ({fg} on {bg})")
    return failures, checked


def main() -> int:
    failures, checked = [], 0
    for sheet in SHEETS:
        name = str(sheet.relative_to(ROOT))
        if not sheet.is_file():
            failures.append(f"{name} is missing — nothing was checked there")
            continue
        found, n = check(sheet.read_text(encoding="utf-8"), name)
        failures += [f"{name}: {msg}" for msg in found]
        checked += n
    if failures:
        print("FAIL web-contrast (WCAG AA, see docs/crosscutting/ui-web-styleguide.md § Contrast):")
        for msg in failures:
            print(f"  {msg}")
        return 1
    print(f"web-contrast OK: {checked} colour pair(s) at WCAG AA across light and dark "
          f"({len(PAIRS)} per mode, {len(SHEETS)} sheets)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
