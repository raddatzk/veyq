#!/usr/bin/env python3
"""Writes the QR decoder's own fixtures (core/src/commonTest/resources/qr/*.pgm), once; their output is committed.

Each code's modules come from the web's own `qrcode` package (src/web, `QRCode.create` at its default level M), so the
fixtures are what the web draws: a pairing code and a sign-in code as `PairingCode.vue` draws them (margin 1, black on
the white plate), the Emergency Kit's code as `emergencyKit.ts` puts it on the PDF (margin 0, #181426 on the page's
white), each again inverted (light on dark), and a photo-like degraded set — rotated, in perspective, blurred, low
contrast with noise and a shadow — rendered by a seeded, supersampled warp, so a rerun writes the same bytes.

Binary PGM (P5), so every test target reads them with no image codec. Run from anywhere:
    /usr/bin/python3 src/mobile/core/qr/make_fixtures.py
(needs `pnpm install` once, for src/web's node_modules).
"""
from __future__ import annotations

import json
import math
import random
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
WEB = ROOT / "src" / "web"
OUT = Path(__file__).resolve().parents[1] / "src" / "commonTest" / "resources" / "qr"

# The texts: a pairing and a sign-in payload's shape (marker + base64url of 32 bytes, pairing.ts), and the kit token of
# contracts/crypto/vectors/v1.json's emergency-kit case with a server (what kitPayload writes).
TEXTS = {
    "pair": "veyqpair:3q2-7wABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4",
    "signin": "veyqsignin:Zm9vYmFyLWJhei1xdXV4LWNvcmdlLWdyYXVsdC1nYXJwbHk",
    "kit": "veyqkit:eyJ2IjozLCJlbWFpbCI6Inpvw6subcO8bGxlckBleGFtcGxlLmNvbSIsInNrIjoiMEM1MTI2LTBaNFJQSy04RVQyOTUtODVFUUs1LURIU1FOMC1DOEhZQjktVjk1QlBBLVdXMUhZRS1UUUUwTkQiLCJzcnYiOiJodHRwczovL3ZleXEuZXhhbXBsZS5jb20ifQ",
}

KIT_DARK = round(0.299 * 0x18 + 0.587 * 0x14 + 0.114 * 0x26)  # #181426 as grey


def modules(texts: dict[str, str]) -> dict[str, list[list[bool]]]:
    """Each text's module matrix, from the web's `qrcode` (its default error-correction level, M)."""
    script = (
        "import QRCode from 'qrcode';"
        "const texts = JSON.parse(process.argv[1]); const out = {};"
        "for (const [k, t] of Object.entries(texts)) { const m = QRCode.create(t).modules;"
        " out[k] = Array.from({ length: m.size }, (_, y) => Array.from({ length: m.size }, (_, x) => !!m.get(y, x))); }"
        "console.log(JSON.stringify(out));"
    )
    run = subprocess.run(["node", "--input-type=module", "-e", script, json.dumps(texts)], cwd=WEB, check=True, capture_output=True, text=True)
    return json.loads(run.stdout)


def write_pgm(name: str, width: int, height: int, pixels: list[int]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    data = bytes(max(0, min(255, round(p))) for p in pixels)
    (OUT / f"{name}.pgm").write_bytes(b"P5\n%d %d\n255\n" % (width, height) + data)


def crisp(m: list[list[bool]], margin: int, scale: int, dark: int, light: int, pad: int = 0) -> tuple[int, list[int]]:
    """The code drawn module by module at an integer scale, with [margin] light modules and [pad] more of page around."""
    n = len(m)
    side = (n + 2 * (margin + pad)) * scale
    px = []
    for y in range(side):
        for x in range(side):
            my, mx = y // scale - margin - pad, x // scale - margin - pad
            px.append(dark if 0 <= my < n and 0 <= mx < n and m[my][mx] else light)
    return side, px


def homography(src: list[tuple[float, float]], dst: list[tuple[float, float]]) -> list[float]:
    """The 3x3 projective map taking the four [src] corners onto the four [dst] corners (solved by Gauss–Jordan)."""
    a, b = [], []
    for (x, y), (u, v) in zip(src, dst):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); b.append(u)
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y]); b.append(v)
    rows = [row + [rhs] for row, rhs in zip(a, b)]
    for c in range(8):
        p = max(range(c, 8), key=lambda r: abs(rows[r][c]))
        rows[c], rows[p] = rows[p], rows[c]
        for r in range(8):
            if r != c:
                f = rows[r][c] / rows[c][c]
                rows[r] = [rv - f * cv for rv, cv in zip(rows[r], rows[c])]
    return [rows[i][8] / rows[i][i] for i in range(8)] + [1.0]


def warp(m: list[list[bool]], margin: int, size: int, corners: list[tuple[float, float]], dark: int, light: int, background: int) -> list[int]:
    """The code (with [margin] light modules) projected onto [corners] of a [size]² frame, 4×4 supersampled."""
    n = len(m) + 2 * margin
    # Inverse map: frame pixel -> code coordinates.
    h = homography(corners, [(0, 0), (n, 0), (n, n), (0, n)])
    px = []
    for y in range(size):
        for x in range(size):
            acc = 0.0
            for sy in range(4):
                for sx in range(4):
                    fx, fy = x + (sx + 0.5) / 4, y + (sy + 0.5) / 4
                    w = h[6] * fx + h[7] * fy + h[8]
                    cx, cy = (h[0] * fx + h[1] * fy + h[2]) / w, (h[3] * fx + h[4] * fy + h[5]) / w
                    if 0 <= cx < n and 0 <= cy < n:
                        mx, my = int(cx) - margin, int(cy) - margin
                        acc += dark if 0 <= mx < len(m) and 0 <= my < len(m) and m[my][mx] else light
                    else:
                        acc += background
            px.append(acc / 16)
    return px


def box_blur(px: list[float], size: int, radius: int, passes: int = 3) -> list[float]:
    """A Gaussian-like blur: [passes] box blurs of [radius] each way, edges clamped."""
    for _ in range(passes):
        for horizontal in (True, False):
            out = [0.0] * len(px)
            for i in range(size):
                for j in range(size):
                    total = 0.0
                    for k in range(-radius, radius + 1):
                        jj = min(size - 1, max(0, j + k))
                        total += px[i * size + jj] if horizontal else px[jj * size + i]
                    if horizontal:
                        out[i * size + j] = total / (2 * radius + 1)
                    else:
                        out[j * size + i] = total / (2 * radius + 1)
            px = out
    return px


def main() -> None:
    m = modules(TEXTS)
    # As drawn: the pairing and sign-in codes on the web's white plate (margin 1), the kit's on the PDF page (margin 0,
    # the page's white around it).
    for name, margin, dark, pad, scale in (("pair", 1, 0, 0, 3), ("signin", 1, 0, 0, 3), ("kit", 0, KIT_DARK, 2, 2)):
        side, px = crisp(m[name], margin, scale, dark, 255, pad)
        write_pgm(name, side, side, px)
        write_pgm(f"{name}-inverted", side, side, [255 - p for p in px])

    rng = random.Random(129)
    size = 160
    pair = m["pair"]

    # Rotated 25° on a mid-grey desk.
    c, s, r, mid = math.cos(math.radians(25)), math.sin(math.radians(25)), 62, size / 2
    rotated = [(mid + r * (dx * c - dy * s), mid + r * (dx * s + dy * c)) for dx, dy in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
    write_pgm("pair-rotated", size, size, warp(pair, 1, size, rotated, 20, 235, 128))

    # In perspective: the far edge narrower, as a phone held at an angle sees a screen.
    write_pgm("pair-perspective", size, size, warp(pair, 1, size, [(26, 12), (134, 18), (150, 150), (10, 146)], 25, 230, 190))

    # Blurred: out of focus by about half a module.
    square = [(14, 14), (146, 14), (146, 146), (14, 146)]
    write_pgm("pair-blurred", size, size, box_blur(warp(pair, 1, size, square, 0, 255, 255), size, 1))

    # Low contrast with sensor noise and a shadow falling across it.
    low = warp(pair, 1, size, square, 95, 165, 140)
    shaded = [p * (1 - 0.35 * (i % size) / size) + rng.uniform(-10, 10) for i, p in enumerate(low)]
    write_pgm("pair-lowcontrast", size, size, shaded)

    total = sum(f.stat().st_size for f in OUT.glob("*.pgm"))
    print(f"{len(list(OUT.glob('*.pgm')))} fixtures, {total} bytes in {OUT}")


if __name__ == "__main__":
    main()
