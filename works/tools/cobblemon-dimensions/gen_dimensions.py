"""Generates the biomes, biome layouts, wild spawns and the spawn table of cobblemon-dimensions.

Every dimension keeps Terralith's overworld terrain: it takes Terralith's whole overworld biome layout (every climate
entry) and only renames each entry's biome to one of its own, so a desert canyon stays a canyon and becomes, say, Ultra
Desert. Each biome borrows the features of one Terralith biome by reference, so the mod needs Terralith installed.

- Ultra Space floats that terrain as islands; its noise settings and island density functions are kept by hand in
  `worldgen/noise_settings/ultra_space.json` and `worldgen/density_function/ultra_space/`.
- Ancient and future are overworld-shaped: their noise settings are Terralith's overworld ones with this mod's surface.

Outputs: `dimension/*.json`, `worldgen/biome/*.json`, `worldgen/noise_settings/{ancient,future}.json`,
`spawn_pool_world/*_wild.json`, and `docs/SPAWNS.md` (the spawn table). Rerun after changing anything here.

    python tools/cobblemon-dimensions/gen_dimensions.py [--terralith JAR] [--cobblemon JAR]
"""
import argparse, json, pathlib, zipfile

REPO = pathlib.Path(__file__).resolve().parents[2]
MOD = REPO / "mods/cobblemon-dimensions"
DATA = MOD / "src/main/resources/data/cobblemon_dimensions"
LANG = MOD / "src/main/resources/assets/cobblemon_dimensions/lang"
SERVER_MODS = REPO.parent / "develop-product/server/mods"
NS = "cobblemon_dimensions"

UNDERGROUND = ["cave", "deep_dark", "lush_caves", "dripstone"]
OCEAN = ["ocean", "beach", "shore", "river", "mirage_isles", "alpha_islands", "ice_marsh"]


def look(sky, fog, water, grass, foliage=None):
    return dict(sky=sky, fog=fog, water=water, grass=grass, foliage=foliage or grass)


def surface(top, under, grass=False):
    return dict(top=top, under=under, grass=grass)


# Per dimension: biomes (name -> Terralith features, look, surface), name rules (first match wins) and the default.
DIMENSIONS = {
    "ultra_space": dict(
        biomes={
            "ultra_deep_sea": ("terralith:basalt_cliffs", look(0x0B0B26, 0x141436, 0x1A1A4A, 0x2B2050), None),
            "ultra_desert": ("terralith:desert_spires", look(0xA8DCFF, 0xD2EEFF, 0x7FC8FF, 0x9ED6E8, 0xBFE8F0), None),
            "ultra_jungle": ("terralith:amethyst_rainforest", look(0xE58AB8, 0xCC6FA0, 0xB45C9A, 0xD46AA8, 0xC2559A), None),
            "ultra_forest": ("terralith:moonlight_grove", look(0x6E52B0, 0x40306E, 0x5A46A0, 0x5B3F9E, 0x4A3290), None),
            "ultra_crater": ("terralith:yellowstone", look(0xFFB347, 0xF2953A, 0xE0802A, 0xC88A3A, 0xB8742E), None),
            "ultra_plant": ("terralith:amethyst_canyon", look(0x14305E, 0x2E86D6, 0x2E86D6, 0x1E6FB8), None),
        },
        rules=[
            ("ultra_deep_sea", OCEAN),
            ("ultra_crater", ["volcanic", "caldera", "basalt", "yellowstone", "ashen", "scarlet", "haze", "peaks", "stony_spires",
                              "windswept_spires", "rocky_mountains", "granite_cliffs", "frozen_cliffs", "glacial_chasm"]),
            ("ultra_desert", ["desert", "badlands", "mesa", "sands", "canyon", "bryce", "painted", "oasis", "arid", "sandstone",
                              "hot_shrubland", "fractured_savanna", "white_cliffs"]),
            ("ultra_jungle", ["jungle", "rainforest", "swamp", "cloud_forest", "mangrove"]),
            ("ultra_plant", ["plains", "meadow", "shrubland", "brushland", "steppe", "savanna", "shield", "highlands", "valley",
                             "clearing", "blooming", "emerald", "yosemite", "lowlands"]),
        ],
        default="ultra_forest",
    ),
    "ancient": dict(
        biomes={
            "ancient_sea": ("terralith:deep_warm_ocean", look(0xF2B27A, 0xE8A06A, 0x2E9C8A, 0x6E9A3A), surface("minecraft:sand", "minecraft:sandstone")),
            "ancient_jungle": ("terralith:tropical_jungle", look(0xF0B070, 0xD8945A, 0x3A8C6E, 0x4E9A2A, 0x3E8A1E), surface("minecraft:grass_block", "minecraft:dirt", grass=True)),
            "ancient_desert": ("terralith:ancient_sands", look(0xF6C68A, 0xF0B070, 0x4AA08A, 0xC2A04A), surface("minecraft:sand", "minecraft:sandstone")),
            "ancient_volcano": ("terralith:volcanic_peaks", look(0xD86A3A, 0xA8462A, 0x8A4A3A, 0x6A5A3A), surface("minecraft:smooth_basalt", "minecraft:blackstone")),
            "ancient_grassland": ("terralith:steppe", look(0xF4BC80, 0xE6A468, 0x3E9A7A, 0x9AAE3E), surface("minecraft:grass_block", "minecraft:coarse_dirt", grass=True)),
        },
        rules=[
            ("ancient_sea", OCEAN),
            ("ancient_volcano", ["volcanic", "caldera", "basalt", "yellowstone", "ashen", "scarlet", "haze", "peaks", "spires",
                                 "rocky_mountains", "cliffs", "glacial_chasm", "slopes"]),
            ("ancient_desert", ["desert", "badlands", "mesa", "sands", "canyon", "bryce", "painted", "oasis", "arid", "sandstone",
                                "hot_shrubland"]),
            ("ancient_jungle", ["jungle", "rainforest", "swamp", "cloud_forest", "mangrove", "dark_forest", "forest", "birch"]),
        ],
        default="ancient_grassland",
    ),
    "future": dict(
        biomes={
            "future_sea": ("minecraft:deep_frozen_ocean", look(0x8A7CFF, 0x5AD8E0, 0x2AB8D8, 0x5AC8C8), surface("minecraft:prismarine", "minecraft:dark_prismarine")),
            "future_steel_peaks": ("terralith:stony_spires", look(0x7C6CE8, 0x8AA8C8, 0x3A9AC8, 0x7A9AAA), surface("minecraft:polished_andesite", "minecraft:andesite")),
            "future_crystal": ("terralith:amethyst_canyon", look(0xA07CFF, 0xC8A8FF, 0x7A6AE8, 0xB89AE8), surface("minecraft:calcite", "minecraft:diorite")),
            "future_neon_forest": ("terralith:moonlight_grove", look(0x6A5CF0, 0x3ACCD8, 0x2AD0E0, 0x2ED8C8, 0x1EC0D0), surface("minecraft:grass_block", "minecraft:dirt", grass=True)),
            "future_flats": ("terralith:shield", look(0x8C7CF8, 0x6AD0E0, 0x3AB8D8, 0x6AC8B8), surface("minecraft:smooth_stone", "minecraft:stone")),
        },
        rules=[
            ("future_sea", OCEAN),
            ("future_steel_peaks", ["peaks", "cliffs", "spires", "mountains", "slopes", "glacial", "volcanic", "caldera", "basalt",
                                    "highlands", "haze", "scarlet", "yellowstone"]),
            ("future_crystal", ["desert", "badlands", "mesa", "canyon", "sands", "oasis", "painted", "bryce", "arid", "sandstone",
                                "amethyst"]),
            ("future_neon_forest", ["forest", "jungle", "taiga", "grove", "birch", "sakura", "lavender", "moonlight", "rainforest",
                                    "swamp", "mangrove", "wintry", "siberian", "maple"]),
        ],
        default="future_flats",
    ),
}

# Wild spawns: (species, bucket, level, position). Buckets follow Cobblemon's: common, uncommon, rare, ultra-rare.
# Only species Cobblemon 1.8.1 implements; Legendaries, Mythicals, Ultra Beasts and paradoxes never go here.
WEIGHT = {"common": 10.0, "uncommon": 5.0, "rare": 2.0, "ultra-rare": 1.0}
G, SUB, SURF = "grounded", "submerged", "surface"
SPAWNS = {
    "ultra_deep_sea": [("gastly", "common", "50-58", G), ("duskull", "common", "50-58", G), ("sableye", "uncommon", "52-60", G),
                       ("elgyem", "uncommon", "50-58", G), ("lunatone", "uncommon", "52-60", G), ("haunter", "rare", "55-62", G),
                       ("beheeyem", "rare", "55-62", G), ("mimikyu", "ultra-rare", "55-62", G)],
    "ultra_desert": [("trapinch", "common", "50-58", G), ("baltoy", "common", "50-58", G), ("sandile", "common", "50-58", G),
                     ("bronzor", "uncommon", "50-58", G), ("solrock", "uncommon", "52-60", G), ("sigilyph", "uncommon", "52-60", G),
                     ("claydol", "rare", "55-62", G), ("bronzong", "rare", "55-62", G), ("larvesta", "ultra-rare", "50-55", G)],
    "ultra_jungle": [("cutiefly", "common", "50-58", G), ("fomantis", "common", "50-58", G), ("bounsweet", "common", "50-58", G),
                     ("morelull", "uncommon", "50-58", G), ("pikipek", "uncommon", "50-58", G), ("tropius", "uncommon", "52-60", G),
                     ("ribombee", "rare", "55-62", G), ("lurantis", "rare", "55-62", G), ("comfey", "rare", "55-62", G)],
    "ultra_forest": [("phantump", "common", "50-58", G), ("pumpkaboo", "common", "50-58", G), ("shuppet", "common", "50-58", G),
                     ("misdreavus", "uncommon", "50-58", G), ("noibat", "uncommon", "50-58", G), ("honedge", "uncommon", "50-58", G),
                     ("trevenant", "rare", "55-62", G), ("banette", "rare", "55-62", G), ("spiritomb", "ultra-rare", "55-62", G)],
    "ultra_crater": [("salandit", "common", "50-58", G), ("slugma", "common", "50-58", G), ("numel", "common", "50-58", G),
                     ("aron", "uncommon", "50-58", G), ("torkoal", "uncommon", "52-60", G), ("magby", "uncommon", "50-58", G),
                     ("salazzle", "rare", "55-62", G), ("turtonator", "rare", "55-62", G), ("beldum", "ultra-rare", "50-55", G)],
    "ultra_plant": [("magnemite", "common", "50-58", G), ("voltorb", "common", "50-58", G), ("joltik", "common", "50-58", G),
                    ("klink", "uncommon", "50-58", G), ("elekid", "uncommon", "50-58", G), ("togedemaru", "uncommon", "52-60", G),
                    ("porygon", "rare", "52-60", G), ("galvantula", "rare", "55-62", G), ("rotom", "ultra-rare", "55-62", G)],
    "ancient_sea": [("omanyte", "common", "35-45", SUB), ("kabuto", "common", "35-45", SUB), ("tirtouga", "uncommon", "35-45", SURF),
                    ("lileep", "uncommon", "35-45", SUB), ("relicanth", "rare", "40-50", SUB), ("omastar", "rare", "45-52", SUB),
                    ("kabutops", "rare", "45-52", SUB)],
    "ancient_jungle": [("yanma", "common", "35-45", G), ("sewaddle", "common", "35-45", G), ("carnivine", "uncommon", "35-45", G),
                       ("tropius", "uncommon", "38-48", G), ("archen", "rare", "40-48", G), ("tyrunt", "rare", "40-48", G),
                       ("aerodactyl", "ultra-rare", "45-52", G)],
    "ancient_desert": [("anorith", "common", "35-45", G), ("cubone", "common", "35-45", G), ("sandshrew", "common", "35-45", G),
                       ("cranidos", "uncommon", "38-46", G), ("shieldon", "uncommon", "38-46", G), ("armaldo", "rare", "45-52", G),
                       ("gible", "ultra-rare", "35-42", G)],
    "ancient_volcano": [("slugma", "common", "35-45", G), ("numel", "common", "35-45", G), ("heatmor", "uncommon", "38-48", G),
                        ("torkoal", "uncommon", "38-48", G), ("larvitar", "rare", "35-42", G), ("larvesta", "rare", "38-45", G),
                        ("aerodactyl", "ultra-rare", "45-52", G)],
    "ancient_grassland": [("cyndaquil", "uncommon", "35-42", G), ("archen", "uncommon", "38-46", G), ("tyrunt", "uncommon", "38-46", G),
                          ("amaura", "uncommon", "38-46", G), ("cranidos", "rare", "40-48", G), ("shieldon", "rare", "40-48", G),
                          ("rampardos", "ultra-rare", "48-54", G), ("bastiodon", "ultra-rare", "48-54", G)],
    "future_sea": [("chinchou", "common", "35-45", SUB), ("tynamo", "common", "35-45", SUB), ("lanturn", "uncommon", "40-50", SUB),
                   ("eelektrik", "rare", "42-50", SUB)],
    "future_steel_peaks": [("magnemite", "common", "35-45", G), ("klink", "common", "35-45", G), ("aron", "common", "35-45", G),
                           ("bronzor", "uncommon", "35-45", G), ("lairon", "uncommon", "40-48", G), ("magneton", "rare", "42-50", G),
                           ("klang", "rare", "42-50", G), ("beldum", "ultra-rare", "35-42", G)],
    "future_crystal": [("carbink", "common", "35-45", G), ("nosepass", "common", "35-45", G), ("glimmet", "uncommon", "35-45", G),
                       ("sableye", "uncommon", "38-46", G), ("bronzong", "rare", "45-52", G), ("probopass", "rare", "45-52", G),
                       ("glimmora", "ultra-rare", "48-54", G)],
    "future_neon_forest": [("joltik", "common", "35-45", G), ("elgyem", "common", "35-45", G), ("golett", "uncommon", "35-45", G),
                           ("elekid", "uncommon", "35-45", G), ("galvantula", "rare", "42-50", G), ("beheeyem", "rare", "42-50", G),
                           ("golurk", "ultra-rare", "45-52", G)],
    "future_flats": [("voltorb", "common", "35-45", G), ("magnemite", "common", "35-45", G), ("varoom", "uncommon", "35-45", G),
                     ("tinkatink", "uncommon", "35-45", G), ("porygon", "rare", "38-46", G), ("electabuzz", "rare", "42-50", G),
                     ("porygon2", "ultra-rare", "45-52", G), ("rotom", "ultra-rare", "45-52", G)],
}
# Ultra Beasts, after Pixelmon 9.4.1's table; Poipole at 40, Naganadel only by evolution.
ULTRA_BEASTS = [("nihilego", "ultra_deep_sea", 60), ("buzzwole", "ultra_jungle", 60), ("poipole", "ultra_jungle", 40),
                ("pheromosa", "ultra_desert", 60), ("guzzlord", "ultra_desert", 60), ("stakataka", "ultra_desert", 60),
                ("xurkitree", "ultra_plant", 60), ("celesteela", "ultra_crater", 60), ("kartana", "ultra_forest", 60),
                ("blacephalon", "ultra_forest", 60)]
BUCKET_KO = {"common": "흔함", "uncommon": "가끔", "rare": "드묾", "ultra-rare": "아주 드묾"}
POSITION_KO = {G: "땅", SUB: "물속", SURF: "수면"}


def biome_for(dimension, biome):
    name = biome.split(":", 1)[1]
    for target, fragments in dimension["rules"]:
        if any(f in name for f in fragments):
            return target
    return dimension["default"]


def surface_rule(biomes):
    """Bedrock floor and deepslate depths like the overworld, then each biome's top and under blocks."""
    rules = [
        {"type": "minecraft:condition", "if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:bedrock_floor",
                                                     "true_at_and_below": {"above_bottom": 0}, "false_at_and_above": {"above_bottom": 5}},
         "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:bedrock"}}},
    ]
    for name, (_, _, s) in biomes.items():
        top = {"type": "minecraft:block", "result_state": {"Name": s["top"]}}
        under = {"type": "minecraft:block", "result_state": {"Name": s["under"]}}
        if s["grass"]:  # grass only where no water stands on it
            top = {"type": "minecraft:sequence", "sequence": [
                {"type": "minecraft:condition", "if_true": {"type": "minecraft:water", "offset": -1, "surface_depth_multiplier": 0,
                                                             "add_stone_depth": False}, "then_run": top}, under]}
        floor = lambda add: {"type": "minecraft:stone_depth", "offset": 0, "surface_type": "floor", "add_surface_depth": add,
                             "secondary_depth_range": 0}
        rules.append({"type": "minecraft:condition", "if_true": {"type": "minecraft:biome", "biome_is": [f"{NS}:{name}"]},
                      "then_run": {"type": "minecraft:sequence", "sequence": [
                          {"type": "minecraft:condition", "if_true": floor(False), "then_run": top},
                          {"type": "minecraft:condition", "if_true": floor(True), "then_run": under}]}})
    rules.append({"type": "minecraft:condition", "if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:deepslate",
                                                              "true_at_and_below": {"absolute": 0}, "false_at_and_above": {"absolute": 8}},
                  "then_run": {"type": "minecraft:block", "result_state": {"Name": "minecraft:deepslate", "Properties": {"axis": "y"}}}})
    return {"type": "minecraft:sequence", "sequence": rules}


def spawn(id_, species, bucket, level, position, dimension, biome):
    return {"id": f"{NS}-{id_}", "pokemon": species, "presets": ["natural"], "type": "pokemon",
            "spawnablePositionType": position, "bucket": bucket, "level": level, "weight": WEIGHT[bucket],
            "condition": {"dimensions": [f"{NS}:{dimension}"], "biomes": [f"{NS}:{biome}"]}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--terralith", default=str(next(SERVER_MODS.glob("Terralith_*.jar"))))
    parser.add_argument("--cobblemon", default=str(next(SERVER_MODS.glob("Cobblemon-fabric-*.jar"))))
    args = parser.parse_args()
    with zipfile.ZipFile(args.terralith) as jar:
        layout = json.loads(jar.read("data/minecraft/dimension/overworld.json"))["generator"]["biome_source"]["biomes"]
        overworld_settings = json.loads(jar.read("data/minecraft/worldgen/noise_settings/overworld.json"))
        sources = {}
        for dimension in DIMENSIONS.values():
            for source, _, _ in dimension["biomes"].values():
                ns, name = source.split(":")
                sources[source] = json.loads(jar.read(f"data/{ns}/worldgen/biome/{name}.json"))
    with zipfile.ZipFile(args.cobblemon) as jar:
        species_ko = json.loads(jar.read("assets/cobblemon/lang/ko_kr.json"))
    biome_ko = json.loads((LANG / "ko_kr.json").read_text(encoding="utf-8"))

    for dim_name, dimension in DIMENSIONS.items():
        entries, counts = [], {}
        for entry in layout:
            if any(f in entry["biome"] for f in UNDERGROUND):
                continue  # underground biomes would scatter through the ground; it takes the surface biome above
            target = biome_for(dimension, entry["biome"])
            counts[target] = counts.get(target, 0) + 1
            entries.append({"biome": f"{NS}:{target}", "parameters": entry["parameters"]})
        settings = f"{NS}:{dim_name}"
        write(DATA / f"dimension/{dim_name}.json", {"type": f"{NS}:{dim_name}", "generator": {
            "type": "minecraft:noise", "settings": settings, "biome_source": {"type": "minecraft:multi_noise", "biomes": entries}}})
        if dim_name != "ultra_space":
            write(DATA / f"worldgen/noise_settings/{dim_name}.json", dict(overworld_settings, surface_rule=surface_rule(dimension["biomes"])))
        print(f"{dim_name}: {len(entries)} entries,", ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))

        for name, (source, colors, _) in dimension["biomes"].items():
            terralith = sources[source]
            write(DATA / f"worldgen/biome/{name}.json", {
                "has_precipitation": False, "temperature": 0.8, "downfall": 0.4,
                "effects": {"sky_color": colors["sky"], "fog_color": colors["fog"], "water_color": colors["water"],
                            "water_fog_color": colors["fog"], "grass_color": colors["grass"], "foliage_color": colors["foliage"]},
                "spawners": {}, "spawn_costs": {},  # vanilla mobs stay out; Pokemon come from Cobblemon's spawn pools
                "carvers": terralith.get("carvers", {}), "features": terralith["features"]})

        wild = [spawn(f"{dim_name}-{biome}-{s}", s, b, lv, pos, dim_name, biome)
                for biome in dimension["biomes"] for s, b, lv, pos in SPAWNS[biome]]
        write(DATA / f"spawn_pool_world/{dim_name}_wild.json",
              {"enabled": True, "neededInstalledMods": [], "neededUninstalledMods": [], "spawns": wild})

    write(DATA / "spawn_pool_world/ultra_beasts.json", {"enabled": True, "neededInstalledMods": [], "neededUninstalledMods": [],
          "spawns": [spawn(s, s, "ultra-rare", str(lv), G, "ultra_space", b) for s, b, lv in ULTRA_BEASTS]})

    # The spawn table, from the same data.
    def ko(species):
        return species_ko.get(f"cobblemon.species.{species}.name", species)
    lines = ["# 차원 스폰표", "",
             "`works/tools/cobblemon-dimensions/gen_dimensions.py`가 스폰 데이터와 함께 만드는 표입니다. 직접 고치지 말고 스크립트를 고친 뒤 다시 돌립니다.", "",
             "등급은 Cobblemon 스폰 버킷입니다(흔함·가끔·드묾·아주 드묾). 같은 등급 안에서는 가중치가 같습니다. 바닐라 몹은 나오지 않습니다.", ""]
    titles = {"ultra_space": "울트라스페이스", "ancient": "고대", "future": "미래"}
    for dim_name, dimension in DIMENSIONS.items():
        lines += [f"## {titles[dim_name]}", ""]
        if dim_name == "ultra_space":
            lines += ["### 울트라비스트", "", "| 바이옴 | 포켓몬 | 레벨 | 등급 |", "|---|---|---|---|"]
            lines += [f"| {biome_ko[f'biome.{NS}.{b}']} | {ko(s)} | {lv} | 아주 드묾 |" for s, b, lv in ULTRA_BEASTS]
            lines += ["", "아고용은 베베놈 진화로만 얻습니다.", ""]
        for biome in dimension["biomes"]:
            lines += [f"### {biome_ko[f'biome.{NS}.{biome}']}", "", "| 포켓몬 | 레벨 | 등급 | 위치 |", "|---|---|---|---|"]
            lines += [f"| {ko(s)} | {lv} | {BUCKET_KO[b]} | {POSITION_KO[pos]} |" for s, b, lv, pos in SPAWNS[biome]]
            lines.append("")
    (MOD / "docs").mkdir(exist_ok=True)
    (MOD / "docs/SPAWNS.md").write_text("\n".join(lines), encoding="utf-8")


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
