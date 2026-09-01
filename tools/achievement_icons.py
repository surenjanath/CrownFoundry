#!/usr/bin/env python3
"""Draw the achievement icons Play Games asks for.

Generated rather than drawn by hand so the eight of them are actually a set: one background, one
stroke weight, one margin, one font. Hand-made icons drift, and eight slightly different oranges
look worse in a grid than eight identical ones.

The language is the app's own, taken from `docs/play-store/graphics/icon-source.svg`: the same
orange gradient corner to corner, a white glyph, and Poppins where a glyph needs a numeral.

    python tools/achievement_icons.py

Everything is drawn at 4x and downsampled, because PIL's polygon and ellipse fills are not
antialiased - at 512 directly, every diagonal comes out with visible stair-stepping.

Glyphs are drawn on a transparent layer and composited, so a notch can be punched with alpha
rather than painted over in a flat orange. Painting over does not work here: the background is a
gradient, so any single orange matches it in exactly one place and shows as a patch everywhere
else.
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SIZE = 512
SCALE = 4
S = SIZE * SCALE

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "play-store" / "achievements"
FONT = ROOT / "Mobile/app/src/main/res/font/poppins_w700.ttf"

# The gradient endpoints from icon-source.svg.
TOP_LEFT = (0xFF, 0x8A, 0x2B)
BOTTOM_RIGHT = (0xF2, 0x54, 0x00)
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)

STROKE = int(S * 0.055)


def background() -> Image.Image:
    """The diagonal gradient, drawn per row of the anti-diagonal so it matches the app icon."""
    image = Image.new("RGB", (S, S))
    pixels = image.load()
    for y in range(S):
        for x in range(0, S, 8):
            t = (x + y) / (2 * S - 2)
            colour = tuple(
                int(TOP_LEFT[i] + (BOTTOM_RIGHT[i] - TOP_LEFT[i]) * t) for i in range(3)
            )
            for dx in range(min(8, S - x)):
                pixels[x + dx, y] = colour
    return image


def centred(points, box=0.60, cx=0.5, cy=0.5):
    """Scale a 0..1 glyph into a box of the canvas, centred."""
    span = S * box
    ox = S * cx - span / 2
    oy = S * cy - span / 2
    return [(ox + px * span, oy + py * span) for px, py in points]


def crown(draw):
    """The app's own crown, straight from the launcher icon."""
    path = [(40, 66), (40, 47), (47, 55), (54, 44), (61, 55), (68, 47), (68, 66)]
    lo, hi = 39, 69
    pts = [((x - lo) / (hi - lo), (y - 44) / (69 - 44)) for x, y in path]
    draw.polygon(centred(pts, box=0.52, cy=0.46), fill=WHITE)
    bar = centred([(0.0, 1.06), (1.0, 1.06)], box=0.52, cy=0.46)
    draw.line(bar, fill=WHITE, width=STROKE, joint="curve")


def shield(draw):
    pts = [(0.5, 0.0), (1.0, 0.18), (1.0, 0.55), (0.5, 1.0), (0.0, 0.55), (0.0, 0.18)]
    draw.polygon(centred(pts, box=0.56), outline=WHITE, width=STROKE)
    tick = centred([(0.28, 0.48), (0.44, 0.64), (0.74, 0.30)], box=0.56)
    draw.line(tick, fill=WHITE, width=STROKE, joint="curve")


def star(draw):
    pts = []
    for i in range(10):
        angle = math.pi / 2 + i * math.pi / 5
        r = 0.5 if i % 2 == 0 else 0.21
        pts.append((0.5 + r * math.cos(angle), 0.5 - r * math.sin(angle)))
    draw.polygon(centred(pts, box=0.66), fill=WHITE)


def upward(draw):
    """A rising line with an arrowhead: the rating going up."""
    line = centred([(0.02, 0.80), (0.34, 0.46), (0.55, 0.62), (0.94, 0.14)], box=0.62)
    draw.line(line, fill=WHITE, width=STROKE, joint="curve")
    head = centred([(0.66, 0.12), (0.98, 0.10), (0.96, 0.42)], box=0.62)
    draw.polygon(head, fill=WHITE)


def bolt(draw):
    """A run of wins, as a lightning bolt.

    Two flames were tried first and neither read as one: cutting an inner shape out looked like an
    eye, and a solid one with a waist looked like an acorn. A bolt has an unmistakable silhouette
    at 48px, and it is the only diagonal shape in the set - which is what stops it being confused
    with the star or the rising arrow beside it.
    """
    pts = [
        (0.56, 0.00), (0.18, 0.58), (0.44, 0.58),
        (0.36, 1.00), (0.80, 0.40), (0.52, 0.40),
    ]
    draw.polygon(centred(pts, box=0.58), fill=WHITE)


def puzzle(draw):
    """A square with a tab on top and a notch bitten out of the right edge.

    Both half-circles sit centred *on* an edge rather than beside it, which is what makes them
    read as part of the piece; the first attempt floated the tab above the body and put the notch
    in the middle, and the result read as a box with a dot.
    """
    body = centred([(0.0, 0.0), (1.0, 1.0)], box=0.50)
    draw.rectangle([body[0], body[1]], fill=WHITE)

    tab = centred([(0.34, -0.16), (0.66, 0.16)], box=0.50)
    draw.ellipse([tab[0], tab[1]], fill=WHITE)

    notch = centred([(0.84, 0.34), (1.16, 0.66)], box=0.50)
    draw.ellipse([notch[0], notch[1]], fill=(0, 0, 0, 0))


def sparring(draw):
    """Two draughts pieces facing each other: the policy against its own last version."""
    left = centred([(0.00, 0.16), (0.56, 0.72)], box=0.62)
    right = centred([(0.44, 0.28), (1.00, 0.84)], box=0.62)
    draw.ellipse([right[0], right[1]], outline=WHITE, width=STROKE)
    draw.ellipse([left[0], left[1]], fill=WHITE)


def centurion(draw):
    """A hundred games, said in the app's own typeface."""
    size = int(S * 0.30)
    font = ImageFont.truetype(str(FONT), size)
    text = "100"
    box = draw.textbbox((0, 0), text, font=font)
    draw.text(
        ((S - (box[2] - box[0])) / 2 - box[0], (S - (box[3] - box[1])) / 2 - box[1]),
        text,
        font=font,
        fill=WHITE,
    )
    ring = [S * 0.17, S * 0.17, S * 0.83, S * 0.83]
    draw.ellipse(ring, outline=WHITE, width=int(STROKE * 0.7))


GLYPHS = {
    "first_win": crown,
    "flawless": shield,
    "beat_hard": star,
    "rated_1400": upward,
    "streak_5": bolt,
    "puzzles_10": puzzle,
    "sparring": sparring,
    "centurion": centurion,
}


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    base = background()
    for key, glyph in GLYPHS.items():
        glyph_layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        glyph(ImageDraw.Draw(glyph_layer))
        image = Image.alpha_composite(base.copy().convert("RGBA"), glyph_layer)
        image.convert("RGB").resize((SIZE, SIZE), Image.LANCZOS).save(OUT / f"{key}.png")
        print(f"  {key}.png")
    print(f"wrote {len(GLYPHS)} icons to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
