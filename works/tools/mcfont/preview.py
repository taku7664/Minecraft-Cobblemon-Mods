#!/usr/bin/env python3
"""Render sample text with a pack built by bdf2mcfont.py, the way Minecraft would.

Reads the generated font JSON, re-derives every glyph's advance with Minecraft's
own rule, and lays the text out on Minecraft's fixed 9px line grid. That makes
line-height problems (a font whose ink is taller than 9px will collide with the
next line in chat, books and signs) visible before you launch the game.

Pass --jar <minecraft client jar> to draw a vanilla reference line on the same
baseline, and --icon to render a pack.png instead of a preview strip.
"""
import argparse
import json
import zipfile
from pathlib import Path

from PIL import Image

LINE_HEIGHT = 9  # Minecraft's line height is fixed regardless of the font
BASELINE = 7  # where the baseline sits inside that line box

SAMPLE = [
    "ABCDEFG abcdefg 0123456789",
    "!@#$%^&*() []{}<>?/ +-=_ .,;:'\"",
    "반가워 난 망토리야! 오늘도 좋은 하루",
    "피카유 레벨 100  공격 ↑  방어 ↓",
    "손상은 한 번만 발동합니다 (동일 대상)",
    "日本語も ひらがな カタカナ 漢字",
    "─━│┌┐└┘├┤┬┴┼ ←↑→↓ ♥♠♣♦",
]

# Layout of vanilla's assets/minecraft/textures/font/ascii.png (16x16 grid).
ASCII_SHEET = (
    " " * 32
    + " !\"#$%&'()*+,-./0123456789:;<=>?"
    + "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_"
    + "`abcdefghijklmnopqrstuvwxyz{|}~ "
)


def load_pack(pack, namespace, font_name):
    """Return ({char: (cell, ascent, advance)}, {char: advance}) for a built pack."""
    font = json.loads(
        (pack / "assets" / namespace / "font" / f"{font_name}.json").read_text(encoding="utf-8")
    )
    glyphs, advances = {}, {}
    for p in font["providers"]:
        if p["type"] == "space":
            advances.update(p["advances"])
            continue
        rel = p["file"].split(":", 1)[1]
        img = Image.open(pack / "assets" / namespace / "textures" / rel).convert("RGBA")
        rows = p["chars"]
        n_rows, n_cols = len(rows), len(rows[0])
        cw, ch = img.width // n_cols, img.height // n_rows
        scale = p["height"] / ch
        for r, line in enumerate(rows):
            for c, char in enumerate(line):
                if ord(char) == 0 or char in glyphs:
                    continue
                cell = img.crop((c * cw, r * ch, (c + 1) * cw, (r + 1) * ch))
                bbox = cell.getbbox()  # same measurement Minecraft makes
                advance = int(0.5 + (bbox[2] if bbox else 0) * scale) + 1
                glyphs[char] = (cell, p["ascent"], advance)
    return glyphs, advances


def load_vanilla(jar):
    """Vanilla ascii.png glyphs: 16x16 grid, ascent 7, scale 1."""
    with zipfile.ZipFile(jar) as z, z.open("assets/minecraft/textures/font/ascii.png") as f:
        img = Image.open(f).convert("RGBA")
    size = img.width // 16
    out = {}
    for i, char in enumerate(ASCII_SHEET):
        if char == " " or char in out:
            continue
        r, c = divmod(i, 16)
        cell = img.crop((c * size, r * size, (c + 1) * size, (r + 1) * size))
        bbox = cell.getbbox()
        out[char] = (cell, 7, (bbox[2] if bbox else 0) + 1)
    return out


def draw(canvas, x, baseline_y, text, glyphs, advances, fallback=None, shadow=True):
    for char in text:
        if char in advances:
            x += advances[char]
            continue
        entry = glyphs.get(char) or (fallback or {}).get(char)
        if entry is None:
            x += 4
            continue
        cell, ascent, advance = entry
        top = baseline_y - ascent
        if shadow:
            layer = Image.new("RGBA", cell.size, (0, 0, 0, 0))
            layer.paste((63, 63, 63, 255), (0, 0), cell)
            canvas.alpha_composite(layer, (x + 1, top + 1))
        canvas.alpha_composite(cell, (x, top))
        x += advance
    return x


def text_width(text, glyphs, advances, fallback=None):
    return draw(Image.new("RGBA", (1, 1)), 0, 0, text, glyphs, advances, fallback, shadow=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pack", help="directory produced by bdf2mcfont.py")
    ap.add_argument("out", help="PNG to write")
    ap.add_argument("--namespace", default="galmuri")
    ap.add_argument("--font-name", default="galmuri9")
    ap.add_argument("--jar", help="Minecraft client jar, for a vanilla reference line")
    ap.add_argument("--zoom", type=int, default=4)
    ap.add_argument("--text", action="append", help="override sample lines (repeatable)")
    ap.add_argument("--icon", nargs="+", metavar="LINE", help="render a 128x128 pack.png instead")
    a = ap.parse_args()

    glyphs, advances = load_pack(Path(a.pack), a.namespace, a.font_name)

    if a.icon:
        icon = Image.new("RGBA", (32, 32), (24, 26, 34, 255))
        y = 13
        for line in a.icon[:2]:
            draw(icon, 2, y, line, glyphs, advances)
            y += 14
        icon.resize((128, 128), Image.NEAREST).save(a.out)
        print(f"-> {a.out} (128x128 icon)")
        return

    vanilla = load_vanilla(a.jar) if a.jar else None
    lines = [(t, False) for t in (a.text or SAMPLE)]
    if vanilla:
        lines.append(("[vanilla] ABCDEFG abcdefg 0123456789", True))

    pad = 6
    width = pad * 2 + max(
        text_width(t, vanilla if v else glyphs, {} if v else advances, vanilla) for t, v in lines
    )
    height = pad * 2 + LINE_HEIGHT * len(lines)
    canvas = Image.new("RGBA", (width, height), (26, 26, 30, 255))
    y = pad
    for text, use_vanilla in lines:
        draw(
            canvas,
            pad,
            y + BASELINE,
            text,
            vanilla if use_vanilla else glyphs,
            {} if use_vanilla else advances,
            fallback=vanilla,
        )
        y += LINE_HEIGHT
    canvas.resize((width * a.zoom, height * a.zoom), Image.NEAREST).save(a.out)
    print(f"-> {a.out}  ({width}x{height} at {a.zoom}x)")


if __name__ == "__main__":
    main()
