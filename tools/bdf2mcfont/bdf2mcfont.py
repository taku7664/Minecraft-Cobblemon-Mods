#!/usr/bin/env python3
"""
BDF -> Minecraft Java Edition bitmap-font resource pack converter.

Renders a BDF pixel font into PNG glyph atlases plus `bitmap` font providers so
Minecraft reproduces the original pixels 1:1, with none of the antialiasing the
`ttf` provider's stb_truetype rasterizer would introduce.

Advance handling
----------------
Minecraft derives a glyph's advance as

    round(actual_width * scale) + 1

where `actual_width` is (rightmost opaque column index + 1) measured from the
cell's left edge -- which is also the pen origin -- and `scale` is height/cell_h.
We keep scale at 1, so the advance is simply `ink_right_edge + 1`.

Galmuri's DWIDTH already equals that for the vast majority of glyphs. Where the
ink stops short, an alpha=1 (visually invisible) marker pixel is written at
column DWIDTH-2 to pin the advance back to DWIDTH.
"""
import argparse
import json
import re
import shutil
from pathlib import Path

from PIL import Image

PUA = range(0xE000, 0xF900)  # left to vanilla / mod icon fonts
CONTROL = set(range(0x00, 0x20)) | set(range(0x7F, 0xA0))

# Minecraft rejects a resource location containing anything else, and one bad
# location makes it discard the entire font definition. The pack then loads
# without any visible error and silently falls back to vanilla, which looks
# like "the pack did nothing" rather than a failure. Catch it here instead.
RESOURCE_PATH = re.compile(r"^[a-z0-9/._-]+$")
RESOURCE_NAMESPACE = re.compile(r"^[a-z0-9._-]+$")
PAD = "\u0000"  # vanilla pads unused atlas cells with NUL too


def parse_bdf(path):
    """Return {codepoint: {dw, bw, bh, bx, by, ink}} where ink is a bit matrix."""
    src = path.read_text(encoding="utf-8")
    glyphs = {}
    for m in re.finditer(r"STARTCHAR .*?\nENCODING (-?\d+)\n(.*?)ENDCHAR", src, re.S):
        cp = int(m.group(1))
        if cp < 0:
            continue
        body = m.group(2)
        dw = int(re.search(r"DWIDTH (-?\d+)", body).group(1))
        bw, bh, bx, by = map(
            int, re.search(r"BBX (-?\d+) (-?\d+) (-?\d+) (-?\d+)", body).groups()
        )
        raw = []
        if "BITMAP" in body:
            raw = [l.strip() for l in body.split("BITMAP\n")[1].strip().split("\n") if l.strip()]
        nbits = len(raw[0]) * 4 if raw else 0
        ink = [[(int(l, 16) >> (nbits - 1 - i)) & 1 for i in range(bw)] for l in raw]
        glyphs[cp] = dict(dw=dw, bw=bw, bh=bh, bx=bx, by=by, ink=ink)
    return glyphs


def measure(glyphs):
    """Global ink extents relative to the baseline / pen origin."""
    top = bot = left = right = None
    for g in glyphs.values():
        inked = [j for j, r in enumerate(g["ink"]) if any(r)]
        if not inked:
            continue
        cols = [i for i in range(g["bw"]) if any(r[i] for r in g["ink"])]
        t = g["by"] + g["bh"] - min(inked)
        b = g["by"] + g["bh"] - max(inked) - 1
        l = g["bx"] + min(cols)
        r = g["bx"] + max(cols) + 1
        top = t if top is None else max(top, t)
        bot = b if bot is None else min(bot, b)
        left = l if left is None else min(left, l)
        right = r if right is None else max(right, r)
    return top, bot, left, right


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bdf")
    ap.add_argument("out")
    ap.add_argument("--namespace", default="galmuri")
    ap.add_argument("--font-name", default="galmuri9")
    ap.add_argument("--prefix", default="g9")
    ap.add_argument("--pack-format", type=int, default=34)
    ap.add_argument("--description", default="Galmuri9 pixel font")
    ap.add_argument("--cols", type=int, default=16)
    ap.add_argument("--rows", type=int, default=16)
    a = ap.parse_args()

    if not RESOURCE_NAMESPACE.match(a.namespace):
        raise SystemExit(f"--namespace {a.namespace!r} must match [a-z0-9._-]")
    for label, value in (("--font-name", a.font_name), ("--prefix", a.prefix)):
        if not RESOURCE_PATH.match(value):
            raise SystemExit(f"{label} {value!r} must match [a-z0-9/._-]")

    glyphs = parse_bdf(Path(a.bdf))
    glyphs = {cp: g for cp, g in glyphs.items() if cp not in PUA and cp not in CONTROL}

    spaces, drawn = {}, {}
    for cp, g in glyphs.items():
        (spaces if not any(any(r) for r in g["ink"]) else drawn)[cp] = g

    top, bot, ink_left, ink_right = measure(drawn)
    ascent = top
    cell_h = top - bot
    cell_w = max(ink_right, max(g["dw"] for g in drawn.values()))

    out = Path(a.out)
    if out.exists():
        shutil.rmtree(out)
    tex_dir = out / "assets" / a.namespace / "textures" / "font"
    tex_dir.mkdir(parents=True)

    order = sorted(drawn)
    per_sheet = a.cols * a.rows
    providers = []
    markers = clipped = 0
    sheets = 0

    if spaces:
        providers.append(
            {
                "type": "space",
                "advances": {chr(cp): g["dw"] for cp, g in sorted(spaces.items())},
            }
        )

    for start in range(0, len(order), per_sheet):
        chunk = order[start : start + per_sheet]
        n_rows = -(-len(chunk) // a.cols)  # last sheet is short; do not pad whole rows
        img = Image.new("RGBA", (a.cols * cell_w, n_rows * cell_h), (0, 0, 0, 0))
        px = img.load()
        grid = []
        for r in range(n_rows):
            row_cps = chunk[r * a.cols : (r + 1) * a.cols]
            grid.append("".join(chr(c) for c in row_cps).ljust(a.cols, PAD))
            for c, cp in enumerate(row_cps):
                g = drawn[cp]
                ox, oy = c * cell_w, r * cell_h
                max_col = -1
                for j, bits in enumerate(g["ink"]):
                    cy = ascent - (g["by"] + g["bh"] - j)
                    if not 0 <= cy < cell_h:
                        clipped += 1
                        continue
                    for i, on in enumerate(bits):
                        if not on:
                            continue
                        cx = max(0, g["bx"] + i)  # clamp rare negative bearings
                        if cx >= cell_w:
                            clipped += 1
                            continue
                        px[ox + cx, oy + cy] = (255, 255, 255, 255)
                        max_col = max(max_col, cx)
                target = g["dw"] - 2  # pin MC's derived advance to DWIDTH
                if 0 <= target < cell_w and max_col < target and px[ox + target, oy][3] == 0:
                    px[ox + target, oy] = (255, 255, 255, 1)
                    markers += 1
        name = f"{a.prefix}_{sheets:02d}.png"
        img.save(tex_dir / name, optimize=True)
        providers.append(
            {
                "type": "bitmap",
                "file": f"{a.namespace}:font/{name}",
                "ascent": ascent,
                "height": cell_h,
                "chars": grid,
            }
        )
        sheets += 1

    font_dir = out / "assets" / a.namespace / "font"
    font_dir.mkdir(parents=True)
    (font_dir / f"{a.font_name}.json").write_text(
        json.dumps({"providers": providers}, ensure_ascii=True, indent=2), encoding="utf-8"
    )

    mc_font = out / "assets" / "minecraft" / "font"
    mc_font.mkdir(parents=True)
    (mc_font / "default.json").write_text(
        json.dumps(
            {
                "providers": [
                    {"type": "reference", "id": "minecraft:include/space"},
                    {"type": "reference", "id": f"{a.namespace}:{a.font_name}"},
                    {
                        "type": "reference",
                        "id": "minecraft:include/default",
                        "filter": {"uniform": False},
                    },
                    {"type": "reference", "id": "minecraft:include/unifont"},
                ]
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    (out / "pack.mcmeta").write_text(
        json.dumps(
            {"pack": {"pack_format": a.pack_format, "description": a.description}},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    print(f"cell {cell_w}x{cell_h}  ascent {ascent}  descent {-bot}  (ink x {ink_left}..{ink_right})")
    print(f"glyphs {len(drawn)} drawn + {len(spaces)} space-only   sheets {sheets}")
    print(f"advance markers {markers}   clipped pixels {clipped}")
    print(f"-> {out}")


if __name__ == "__main__":
    main()
