"""Generates the wild spawns and the spawn table of cobblemon-dimensions.

The dimensions and their biomes are in `src/main/resources/cobblemon_dimensions/worldgen.json`; the mod builds the
biome layouts, terrain settings and biomes from it at startup (from Terralith when installed, vanilla Minecraft when
not), so nothing here touches worldgen. This script reads the biome list from that file.

Outputs: `spawn_pool_world/*.json` and `docs/SPAWNS.md` (the spawn table). Rerun after changing anything here or the
biome list.

    python tools/cobblemon-dimensions/gen_dimensions.py [--cobblemon JAR]
"""
import argparse, json, pathlib, zipfile

REPO = pathlib.Path(__file__).resolve().parents[2]
MOD = REPO / "mods/cobblemon-dimensions"
DATA = MOD / "src/main/resources/data/cobblemon_dimensions"
LANG = MOD / "src/main/resources/assets/cobblemon_dimensions/lang"
SERVER_MODS = REPO.parent / "develop-product/server/mods"
NS = "cobblemon_dimensions"

SPEC = json.loads((MOD / "src/main/resources/cobblemon_dimensions/worldgen.json").read_text(encoding="utf-8"))
DIMENSIONS = SPEC["dimensions"]


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


def spawn(id_, species, bucket, level, position, dimension, biome):
    return {"id": f"{NS}-{id_}", "pokemon": species, "presets": ["natural"], "type": "pokemon",
            "spawnablePositionType": position, "bucket": bucket, "level": level, "weight": WEIGHT[bucket],
            "condition": {"dimensions": [f"{NS}:{dimension}"], "biomes": [f"{NS}:{biome}"]}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cobblemon", default=str(next(SERVER_MODS.glob("Cobblemon-fabric-*.jar"))))
    args = parser.parse_args()
    with zipfile.ZipFile(args.cobblemon) as jar:
        species_ko = json.loads(jar.read("assets/cobblemon/lang/ko_kr.json"))
        by_tag = cobblemon_spawns(jar)
    biome_ko = json.loads((LANG / "ko_kr.json").read_text(encoding="utf-8"))

    wilds = {}
    for dim_name, dimension in DIMENSIONS.items():
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
