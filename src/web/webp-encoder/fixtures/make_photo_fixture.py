#!/usr/bin/env python3
"""Write `photo-256.rgba.gz`: a generated, photo-like 256 x 256 picture as raw RGBA, gzipped (the size test's input).

Generated rather than photographed, so it carries no one's picture and no licence: a sky gradient over a ground, the
sun, a few soft-edged shapes and a fine multi-octave noise texture (the detail that makes a photo cost bytes where a
flat graphic does not). Deterministic — a fixed seed, no clock in the gzip header — so a re-run writes the same bytes.
Python standard library only: `/usr/bin/python3 src/web/webp-encoder/fixtures/make_photo_fixture.py`.
"""
from __future__ import annotations

import gzip
import math
import pathlib
import random

SIZE = 256
OUT = pathlib.Path(__file__).resolve().parent / "photo-256.rgba.gz"


def value_noise(rng: random.Random, cells: int) -> list:
    """A `cells` x `cells` lattice of random values, bilinearly interpolated per pixel by `sample`."""
    return [[rng.random() for _ in range(cells + 1)] for _ in range(cells + 1)]


def sample(lattice: list, cells: int, x: float, y: float) -> float:
    gx, gy = x / SIZE * cells, y / SIZE * cells
    x0, y0 = int(gx), int(gy)
    fx, fy = gx - x0, gy - y0
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    top = lattice[y0][x0] * (1 - fx) + lattice[y0][x0 + 1] * fx
    bottom = lattice[y0 + 1][x0] * (1 - fx) + lattice[y0 + 1][x0 + 1] * fx
    return top * (1 - fy) + bottom * fy


def main() -> None:
    rng = random.Random(20261112)
    octaves = [(cells, value_noise(rng, cells), weight) for cells, weight in ((4, 0.4), (16, 0.25), (64, 0.2), (128, 0.15))]
    shapes = [(rng.uniform(20, 236), rng.uniform(120, 236), rng.uniform(12, 40),
               (rng.randint(40, 200), rng.randint(40, 160), rng.randint(20, 120))) for _ in range(7)]
    pixels = bytearray()
    for y in range(SIZE):
        for x in range(SIZE):
            texture = sum(sample(lattice, cells, x, y) * weight for cells, lattice, weight in octaves)
            if y < 110 + 20 * math.sin(x / 40):
                t = y / 130
                base = (90 + 80 * t, 140 + 60 * t, 220 - 30 * t)  # sky
                grain = 18
            else:
                base = (70 + 50 * texture, 110 + 40 * texture, 40 + 20 * texture)  # ground
                grain = 90
            r, g, b = base
            sun = math.hypot(x - 190, y - 45)
            if sun < 26:
                glow = 1 - sun / 26
                r, g, b = r + 120 * glow, g + 110 * glow, b + 40 * glow
            for cx, cy, radius, colour in shapes:
                d = math.hypot(x - cx, y - cy)
                if d < radius:
                    edge = min(1.0, (radius - d) / 4)
                    r, g, b = (r * (1 - edge) + colour[0] * edge, g * (1 - edge) + colour[1] * edge,
                               b * (1 - edge) + colour[2] * edge)
            shade = (texture - 0.5) * grain + rng.gauss(0, 3)  # the scene's detail, then the sensor's grain
            pixels += bytes(max(0, min(255, int(round(c + shade + rng.gauss(0, 1.5))))) for c in (r, g, b))
            pixels.append(255)
    OUT.write_bytes(gzip.compress(bytes(pixels), compresslevel=9, mtime=0))
    print(f"{OUT.name}: {len(pixels)} RGBA bytes, {OUT.stat().st_size} gzipped")


if __name__ == "__main__":
    main()
