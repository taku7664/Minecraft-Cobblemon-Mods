#!/usr/bin/env python3
"""Wrap a TTF/OTF in a Minecraft Java Edition font resource pack.

The counterpart to bdf2mcfont.py. That one bakes a BDF into pixel-exact glyph
atlases; this one just embeds the font file and lets Minecraft rasterize it.

Use this when the font has no BDF, or when you want a pixel font's own design
at a display size that is not its native grid -- baking cannot help there, and
Minecraft's rasterizer at least keeps the shapes.

Sharpness rule for pixel fonts
------------------------------
A pixel font is only crisp when the raster size is an integer multiple of the
grid it was drawn on. Minecraft rasterizes at `size * oversample` and then
draws the glyph into a `size` box, so raising --oversample to land the raster
on a multiple of the native grid is what buys sharpness, not raising --size.

Galmuri11 is drawn on a 12px grid, so `--size 8 --oversample 1.5` rasterizes at
the native 12px and displays it in an 8px box. Going further (24px, 36px) adds
nothing: the design has no detail finer than its 12px grid, so a larger raster
holds the same information in a bigger texture.

The final on-screen sharpness also depends on the GUI Scale, which decides how
many real pixels the `size` box covers. Pin GUI Scale rather than leaving it on
Auto if you care about the result staying put.
"""
import argparse
import shutil
from pathlib import Path

import mcpack


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("font", help=".ttf/.otf to embed")
    ap.add_argument("out", help="pack directory to create")
    ap.add_argument("--size", type=float, default=8.0, help="display size in GUI pixels")
    ap.add_argument(
        "--oversample",
        type=float,
        default=1.0,
        help="raster at size*oversample; set so the raster lands on the font's native grid",
    )
    ap.add_argument("--shift", type=float, nargs=2, default=[0.0, 0.0], metavar=("X", "Y"))
    ap.add_argument("--namespace", default="galmuri")
    ap.add_argument("--name", help="embedded filename (default: lowercased source name)")
    ap.add_argument(
        "--skip-from-bdf",
        help="BDF of the same family; its Private Use Area codepoints get skipped "
        "so mod icon fonts keep working",
    )
    ap.add_argument("--pack-format", type=int, default=34)
    ap.add_argument("--description")
    ap.add_argument("--license", help="license file to copy into the pack")
    a = ap.parse_args()

    src = Path(a.font)
    name = a.name or src.name.lower()
    ns = mcpack.check_namespace(a.namespace)
    mcpack.check_path(name, "--name")

    skip = []
    if a.skip_from_bdf:
        pua = [cp for cp in mcpack.bdf_codepoints(a.skip_from_bdf) if cp in mcpack.PUA]
        if pua:
            skip = ["".join(chr(cp) for cp in pua)]

    out = Path(a.out)
    if out.exists():
        shutil.rmtree(out)
    font_dir = out / "assets" / ns / "font"
    font_dir.mkdir(parents=True)
    shutil.copy(src, font_dir / name)

    size = int(a.size) if a.size == int(a.size) else a.size
    provider = {
        "type": "ttf",
        "file": f"{ns}:{name}",
        "size": size,
        "oversample": a.oversample,
        "shift": [int(v) if v == int(v) else v for v in a.shift],
    }
    if skip:
        provider["skip"] = skip

    mcpack.write_default_font(out, [provider])
    mcpack.write_mcmeta(
        out,
        a.pack_format,
        a.description or f"{src.stem} @ {size}px TTF (oversample {a.oversample})",
    )
    if a.license:
        shutil.copy(a.license, out / "LICENSE.txt")

    raster = a.size * a.oversample
    print(f"{src.name} -> {ns}:{name}")
    print(f"display {size}px   oversample {a.oversample}   raster {raster:g}px")
    print(f"skipped PUA codepoints: {len(skip[0]) if skip else 0}")
    print(f"-> {out}")


if __name__ == "__main__":
    main()
