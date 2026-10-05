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


# Particles drifting in the air of a biome: (vanilla particle, chance per block shown each tick). Basalt deltas use
# white_ash at 0.118, warped forests warped_spore at 0.0143.
PARTICLES = {
    "ancient_volcano": ("minecraft:ash", 0.05),
    "ancient_jungle": ("minecraft:spore_blossom_air", 0.006),
    "ancient_desert": ("minecraft:white_ash", 0.006),
    "future_neon_forest": ("minecraft:warped_spore", 0.012),
    "future_crystal": ("minecraft:end_rod", 0.0015),
    "future_steel_peaks": ("minecraft:electric_spark", 0.004),
    "future_flats": ("minecraft:electric_spark", 0.002),
}


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

# Wild spawns come from Cobblemon's own spawn data: each biome here stands in for some of Cobblemon's biome tags and takes
# every spawn Cobblemon gives those tags, with Cobblemon's bucket, weight, position, conditions and level as they are.
# Only the biome and the dimension change; League Challenge sets wild levels from the player's cap anyway.
# Legendaries, Mythicals, Ultra Beasts and paradoxes never come along.
BIOME_TAGS = {
    "ultra_deep_sea": ["is_spooky", "is_deep_dark", "is_end", "is_mushroom", "is_dripstone", "is_magical", "is_freezing"],
    "ultra_desert": ["is_desert", "is_arid", "is_badlands"],
    "ultra_jungle": ["is_jungle", "is_floral"],
    "ultra_forest": ["is_forest", "is_taiga", "is_magical", "is_spooky"],
    "ultra_crater": ["is_volcanic", "is_thermal", "is_mountain", "is_peak", "is_hills"],
    "ultra_plant": ["is_plains", "is_grassland", "is_temperate", "is_sky"],
    "ancient_sea": ["is_ocean", "is_warm_ocean", "is_lukewarm_ocean", "is_deep_ocean", "is_coast", "is_beach", "is_tropical_island"],
    "ancient_jungle": ["is_jungle", "is_swamp", "is_lush", "is_bamboo", "is_forest", "is_tropical_island"],
    "ancient_desert": ["is_desert", "is_arid", "is_badlands", "is_savanna"],
    "ancient_volcano": ["is_volcanic", "is_thermal", "is_mountain", "is_peak", "is_hills"],
    "ancient_grassland": ["is_grassland", "is_plains", "is_savanna", "is_temperate", "is_floral", "is_hills"],
    "future_sea": ["is_ocean", "is_cold_ocean", "is_frozen_ocean", "is_deep_ocean", "is_coast", "is_beach", "is_freshwater"],
    "future_steel_peaks": ["is_mountain", "is_peak", "is_hills", "is_highlands", "is_dripstone", "is_tundra"],
    "future_crystal": ["is_badlands", "is_arid", "is_desert", "is_magical", "is_dripstone"],
    "future_neon_forest": ["is_forest", "is_taiga", "is_magical", "is_spooky", "is_mushroom", "is_snowy_forest"],
    "future_flats": ["is_plains", "is_grassland", "is_temperate", "is_tundra", "is_cherry_blossom", "is_floral"],
}
EXCLUDED_LABELS = {"legendary", "mythical", "ultra_beast", "paradox"}
WEIGHT = {"common": 10.0, "uncommon": 5.0, "rare": 2.0, "ultra-rare": 1.0}
G, SUB, SURF = "grounded", "submerged", "surface"
# Ultra Beasts, after Pixelmon 9.4.1's table; Poipole at 40, Naganadel only by evolution.
ULTRA_BEASTS = [("nihilego", "ultra_deep_sea", 60), ("buzzwole", "ultra_jungle", 60), ("poipole", "ultra_jungle", 40),
                ("pheromosa", "ultra_desert", 60), ("guzzlord", "ultra_desert", 60), ("stakataka", "ultra_desert", 60),
                ("xurkitree", "ultra_plant", 60), ("celesteela", "ultra_crater", 60), ("kartana", "ultra_forest", 60),
                ("blacephalon", "ultra_forest", 60)]
# The fourteen paradoxes that are not Legends: free to catch like any wild Pokemon (the server announces them), each in
# one biome of its own dimension. Their weight is about a sixth of a typical ultra-rare spawn here (median 30).
# Koraidon, Miraidon and the six Legend paradoxes spawn from jbro-policy's Legend pool instead.
PARADOX_WEIGHT = 5.0
PARADOXES = [
    ("greattusk", "ancient", "ancient_desert", G, None), ("sandyshocks", "ancient", "ancient_desert", G, None),
    ("brutebonnet", "ancient", "ancient_jungle", G, None), ("fluttermane", "ancient", "ancient_jungle", G, "night"),
    ("screamtail", "ancient", "ancient_grassland", G, None), ("slitherwing", "ancient", "ancient_volcano", G, None),
    ("roaringmoon", "ancient", "ancient_volcano", G, "night"),
    ("irontreads", "future", "future_steel_peaks", G, None), ("ironthorns", "future", "future_steel_peaks", G, None),
    ("ironbundle", "future", "future_sea", SURF, None), ("ironhands", "future", "future_flats", G, None),
    ("ironmoth", "future", "future_crystal", G, None), ("ironjugulis", "future", "future_neon_forest", G, "night"),
    ("ironvaliant", "future", "future_neon_forest", G, None),
]
# Same as the paradoxes: about a sixth of a typical ultra-rare spawn. How rare that ends up differs by biome, since each
# biome's other ultra-rare spawns add up differently.
ULTRA_BEAST_WEIGHT = 5.0
BUCKET_KO = {"common": "흔함", "uncommon": "가끔", "rare": "드묾", "ultra-rare": "아주 드묾"}


def cobblemon_spawns(jar):
    """Cobblemon's spawns by biome tag, leaving out the special species and anything tied to a structure."""
    excluded = set()
    for name in jar.namelist():
        if name.startswith("data/cobblemon/species/") and name.endswith(".json"):
            if EXCLUDED_LABELS & set(json.loads(jar.read(name)).get("labels", [])):
                excluded.add(name.rsplit("/", 1)[1][:-5])
    by_tag = {}
    for name in sorted(jar.namelist()):
        if not (name.startswith("data/cobblemon/spawn_pool_world/") and name.endswith(".json")):
            continue
        for entry in json.loads(jar.read(name)).get("spawns", []):
            condition = entry.get("condition", {})
            if entry.get("type") != "pokemon" or entry["pokemon"].split()[0] in excluded or "structures" in condition:
                continue
            for tag in condition.get("biomes", []):
                if tag.startswith("#cobblemon:is_"):
                    by_tag.setdefault(tag[len("#cobblemon:"):], []).append(entry)
    return by_tag


def wild_spawns(by_tag, dim_name, biome):
    seen, out = set(), []
    for tag in BIOME_TAGS[biome]:
        for entry in by_tag.get(tag, []):
            if entry["id"] in seen:
                continue
            seen.add(entry["id"])
            copy = json.loads(json.dumps(entry))
            copy["id"] = f"{NS}-{dim_name}-{biome}-{entry['id']}"
            copy["condition"] = dict(copy.get("condition", {}), dimensions=[f"{NS}:{dim_name}"], biomes=[f"{NS}:{biome}"])
            out.append(copy)
    return out


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
        by_tag = cobblemon_spawns(jar)
    biome_ko = json.loads((LANG / "ko_kr.json").read_text(encoding="utf-8"))

    wilds = {}
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
            effects = {"sky_color": colors["sky"], "fog_color": colors["fog"], "water_color": colors["water"],
                       "water_fog_color": colors["fog"], "grass_color": colors["grass"], "foliage_color": colors["foliage"]}
            if name in PARTICLES:
                particle, probability = PARTICLES[name]
                effects["particle"] = {"options": {"type": particle}, "probability": probability}
            write(DATA / f"worldgen/biome/{name}.json", {
                "has_precipitation": False, "temperature": 0.8, "downfall": 0.4,
                "effects": effects,
                "spawners": {}, "spawn_costs": {},  # vanilla mobs stay out; Pokemon come from Cobblemon's spawn pools
                "carvers": terralith.get("carvers", {}), "features": terralith["features"]})

        wild = [entry for biome in dimension["biomes"] for entry in wild_spawns(by_tag, dim_name, biome)]
        wilds[dim_name] = wild
        write(DATA / f"spawn_pool_world/{dim_name}_wild.json",
              {"enabled": True, "neededInstalledMods": [], "neededUninstalledMods": [], "spawns": wild})

    paradoxes = []
    for species, dim_name, biome, position, time in PARADOXES:
        entry = {"id": f"{NS}-paradox-{species}", "pokemon": species, "presets": ["water" if position == SURF else "natural"],
                 "type": "pokemon", "spawnablePositionType": position, "bucket": "ultra-rare", "level": "50-60",
                 "weight": PARADOX_WEIGHT,
                 "condition": {"dimensions": [f"{NS}:{dim_name}"], "biomes": [f"{NS}:{biome}"]}}
        if time:
            entry["condition"]["timeRange"] = time
        paradoxes.append(entry)
    write(DATA / "spawn_pool_world/paradoxes.json",
          {"enabled": True, "neededInstalledMods": [], "neededUninstalledMods": [], "spawns": paradoxes})

    write(DATA / "spawn_pool_world/ultra_beasts.json", {"enabled": True, "neededInstalledMods": [], "neededUninstalledMods": [],
          "spawns": [dict(spawn(s, s, "ultra-rare", str(lv), G, "ultra_space", b), weight=ULTRA_BEAST_WEIGHT)
                     for s, b, lv in ULTRA_BEASTS]})

    # The spawn table, from the same data.
    def ko(species):
        return species_ko.get(f"cobblemon.species.{species}.name", species)
    lines = ["# 차원 스폰표", "",
             "`works/tools/cobblemon-dimensions/gen_dimensions.py`가 스폰 데이터와 함께 만드는 표입니다. 직접 고치지 말고 스크립트를 고친 뒤 다시 돌립니다.", "",
             "야생 포켓몬은 Cobblemon 기본 스폰 데이터에서 가져옵니다. 바이옴마다 맡는 Cobblemon 바이옴 태그가 있고, 그 태그의 스폰을 등급·가중치·위치·조건(시간, 빛, 물속 등) 그대로 옮깁니다. 레벨은 리그 챌린지가 플레이어 레벨 캡에 맞춰 정합니다. 전설·환상·울트라비스트·패러독스는 빠집니다. 바닐라 몹은 나오지 않습니다.", "",
             "등급은 Cobblemon 스폰 버킷입니다(흔함·가끔·드묾·아주 드묾). 같은 포켓몬이 조건별로 여러 번 들어간 경우 가장 흔한 등급으로 적었습니다.", ""]
    titles = {"ultra_space": "울트라스페이스", "ancient": "고대", "future": "미래"}
    for dim_name, dimension in DIMENSIONS.items():
        lines += [f"## {titles[dim_name]}", ""]
        if dim_name == "ultra_space":
            lines += ["### 울트라비스트", "", "| 바이옴 | 포켓몬 | 등급 |", "|---|---|---|"]
            lines += [f"| {biome_ko[f'biome.{NS}.{b}']} | {ko(s)} | 아주 드묾 |" for s, b, lv in ULTRA_BEASTS]
            lines += ["", "아고용은 베베놈 진화로만 얻습니다.", ""]
        order = list(BUCKET_KO)
        if dim_name != "ultra_space":
            lines += ["### 패러독스", "",
                      "아주 드묾 등급 안에서도 일반 포켓몬의 약 6분의 1 확률입니다. 전설이 아니라서 몇 마리든 잡을 수 있고, 나타나면 서버에 알림이 뜹니다. "
                      "코라이돈·미라이돈과 전설 패러독스 6종은 `jbro-policy`의 전설 스폰(`docs/LEGENDARY_SPAWNS.md`)을 따릅니다.", "",
                      "| 바이옴 | 포켓몬 | 조건 |", "|---|---|---|"]
            lines += [f"| {biome_ko[f'biome.{NS}.{b}']} | {ko(sp)} | {'밤' if t == 'night' else ('물 위' if pos == SURF else '')} |"
                      for sp, d, b, pos, t in PARADOXES if d == dim_name]
            lines.append("")
        for biome in dimension["biomes"]:
            tags = ", ".join(f"`{t}`" for t in BIOME_TAGS[biome])
            best = {}
            for entry in wilds[dim_name]:
                if entry["condition"]["biomes"] == [f"{NS}:{biome}"]:
                    species = entry["pokemon"].split()[0]
                    rank = order.index(entry["bucket"])
                    best[species] = min(best.get(species, rank), rank)
            lines += [f"### {biome_ko[f'biome.{NS}.{biome}']} ({len(best)}종)", "", f"Cobblemon 태그: {tags}", "",
                      "| 등급 | 포켓몬 |", "|---|---|"]
            for rank, bucket in enumerate(order):
                names = sorted(ko(sp) for sp, r in best.items() if r == rank)
                if names:
                    lines.append(f"| {BUCKET_KO[bucket]} | {', '.join(names)} |")
            lines.append("")
    (MOD / "docs").mkdir(exist_ok=True)
    (MOD / "docs/SPAWNS.md").write_text("\n".join(lines), encoding="utf-8")


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
