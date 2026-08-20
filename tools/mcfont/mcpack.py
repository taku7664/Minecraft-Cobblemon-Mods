"""Shared pieces for writing a Minecraft Java Edition font resource pack."""
import json
import re
from pathlib import Path

# Minecraft rejects a resource location containing anything else, and one bad
# location makes it discard the entire font definition. The pack then loads
# without any visible error and silently falls back to vanilla, which looks
# like "the pack did nothing" rather than a failure. Catch it before writing.
RESOURCE_PATH = re.compile(r"^[a-z0-9/._-]+$")
RESOURCE_NAMESPACE = re.compile(r"^[a-z0-9._-]+$")

PUA = range(0xE000, 0xF900)  # left to vanilla / mod icon fonts
CONTROL = set(range(0x00, 0x20)) | set(range(0x7F, 0xA0))


def check_namespace(value, label="--namespace"):
    if not RESOURCE_NAMESPACE.match(value):
        raise SystemExit(f"{label} {value!r} must match [a-z0-9._-]")
    return value


def check_path(value, label):
    if not RESOURCE_PATH.match(value):
        raise SystemExit(f"{label} {value!r} must match [a-z0-9/._-]")
    return value


def write_default_font(out, providers):
    """Write assets/minecraft/font/default.json wrapping `providers`.

    Keeps vanilla's own structure and slots the custom providers in second, so
    anything the custom font lacks still falls back to vanilla and then to
    unifont. `filter: {"uniform": false}` is vanilla's Force Unicode Font
    handling and is preserved as-is.
    """
    font_dir = Path(out) / "assets" / "minecraft" / "font"
    font_dir.mkdir(parents=True, exist_ok=True)
    (font_dir / "default.json").write_text(
        json.dumps(
            {
                "providers": [
                    {"type": "reference", "id": "minecraft:include/space"},
                    *providers,
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


def write_mcmeta(out, pack_format, description):
    (Path(out) / "pack.mcmeta").write_text(
        json.dumps(
            {"pack": {"pack_format": pack_format, "description": description}},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


def bdf_codepoints(bdf_path):
    """Every codepoint a BDF defines. Used to build a TTF `skip` list."""
    src = Path(bdf_path).read_text(encoding="utf-8")
    return sorted(
        cp for cp in (int(m.group(1)) for m in re.finditer(r"\nENCODING (-?\d+)", src)) if cp >= 0
    )
