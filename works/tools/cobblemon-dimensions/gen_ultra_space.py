"""Generates Ultra Space's biome layout and its six biomes for cobblemon-dimensions from the installed Terralith jar.

Ultra Space floats Terralith's overworld terrain as islands. To keep Terralith's special landforms, the dimension takes
Terralith's whole overworld biome layout (every climate entry) and only renames each entry's biome to the Ultra biome
that fits it: a desert canyon stays a canyon and becomes Ultra Desert. Each Ultra biome borrows the features of one
Terralith biome, by reference, so the mod needs Terralith installed. Rerun this when Terralith or the mapping changes.

    python tools/cobblemon-dimensions/gen_ultra_space.py [--terralith JAR]
"""
import argparse, json, pathlib, zipfile

REPO = pathlib.Path(__file__).resolve().parents[2]
DATA = REPO / "mods/cobblemon-dimensions/src/main/resources/data/cobblemon_dimensions"
DEFAULT_JAR = REPO.parent / "develop-product/server/mods/Terralith_1.21.x_v2.6.2.jar"
NS = "cobblemon_dimensions"

# Ultra biome -> (Terralith biome whose features it borrows, look). Colors are this mod's own.
BIOMES = {
    "ultra_deep_sea": ("terralith:basalt_cliffs", dict(sky=0x0B0B26, fog=0x141436, water=0x1A1A4A, grass=0x2B2050, foliage=0x2B2050)),
    "ultra_desert": ("terralith:desert_spires", dict(sky=0xA8DCFF, fog=0xD2EEFF, water=0x7FC8FF, grass=0x9ED6E8, foliage=0xBFE8F0)),
    "ultra_jungle": ("terralith:amethyst_rainforest", dict(sky=0xE58AB8, fog=0xCC6FA0, water=0xB45C9A, grass=0xD46AA8, foliage=0xC2559A)),
    "ultra_forest": ("terralith:moonlight_grove", dict(sky=0x6E52B0, fog=0x40306E, water=0x5A46A0, grass=0x5B3F9E, foliage=0x4A3290)),
    "ultra_crater": ("terralith:yellowstone", dict(sky=0xFFB347, fog=0xF2953A, water=0xE0802A, grass=0xC88A3A, foliage=0xB8742E)),
    "ultra_plant": ("terralith:amethyst_canyon", dict(sky=0x14305E, fog=0x2E86D6, water=0x2E86D6, grass=0x1E6FB8, foliage=0x1E6FB8)),
}

# Name fragments decide where a Terralith or vanilla overworld biome goes; the first match wins.
RULES = [
    ("ultra_deep_sea", ["ocean", "beach", "shore", "river", "mirage_isles", "alpha_islands", "ice_marsh"]),
    ("ultra_crater", ["volcanic", "caldera", "basalt", "yellowstone", "ashen", "scarlet", "haze", "peaks", "stony_spires",
                      "windswept_spires", "rocky_mountains", "granite_cliffs", "frozen_cliffs", "glacial_chasm"]),
    ("ultra_desert", ["desert", "badlands", "mesa", "sands", "canyon", "bryce", "painted", "oasis", "arid", "sandstone",
                      "hot_shrubland", "fractured_savanna", "white_cliffs"]),
    ("ultra_jungle", ["jungle", "rainforest", "swamp", "cloud_forest", "mangrove"]),
    ("ultra_plant", ["plains", "meadow", "shrubland", "brushland", "steppe", "savanna", "shield", "highlands", "valley",
                     "clearing", "blooming", "emerald", "yosemite", "lowlands"]),
]
DEFAULT = "ultra_forest"  # every forest, taiga, grove and snowy biome left over
UNDERGROUND = ["cave", "deep_dark", "lush_caves", "dripstone"]


def ultra_for(biome):
    name = biome.split(":", 1)[1]
    for ultra, fragments in RULES:
        if any(f in name for f in fragments):
            return ultra
    return DEFAULT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--terralith", default=str(DEFAULT_JAR))
    args = parser.parse_args()
    with zipfile.ZipFile(args.terralith) as jar:
        overworld = json.loads(jar.read("data/minecraft/dimension/overworld.json"))
        sources = {source: json.loads(jar.read(f"data/terralith/worldgen/biome/{source.split(':')[1]}.json"))
                   for source, _ in BIOMES.values()}

    entries, counts = [], {}
    for entry in overworld["generator"]["biome_source"]["biomes"]:
        biome = entry["biome"]
        # Island interiors take the surface biome above them; underground biomes would scatter through them.
        if any(f in biome for f in UNDERGROUND):
            continue
        ultra = ultra_for(biome)
        counts[ultra] = counts.get(ultra, 0) + 1
        entries.append({"biome": f"{NS}:{ultra}", "parameters": entry["parameters"]})

    dimension = {
        "type": f"{NS}:ultra_space",
        "generator": {
            "type": "minecraft:noise",
            "settings": f"{NS}:ultra_space",
            "biome_source": {"type": "minecraft:multi_noise", "biomes": entries},
        },
    }
    write(DATA / "dimension/ultra_space.json", dimension)

    for ultra, (source, look) in BIOMES.items():
        terralith = sources[source]
        biome = {
            "has_precipitation": False,
            "temperature": 0.8,
            "downfall": 0.4,
            "effects": {
                "sky_color": look["sky"], "fog_color": look["fog"],
                "water_color": look["water"], "water_fog_color": look["fog"],
                "grass_color": look["grass"], "foliage_color": look["foliage"],
            },
            # Vanilla mobs stay out; Pokemon come from Cobblemon's spawn pools.
            "spawners": {},
            "spawn_costs": {},
            "carvers": terralith.get("carvers", {}),
            "features": terralith["features"],
        }
        write(DATA / f"worldgen/biome/{ultra}.json", biome)

    print(f"{len(entries)} climate entries:", ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
