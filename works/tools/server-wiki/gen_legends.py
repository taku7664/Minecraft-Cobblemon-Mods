"""Generates the wiki's Legendary spawn guide data, server-wiki/assets/data/legends.js, from what the server runs:
jbro-policy's spawn pool and Legend catalog, its announcement lines, Cobblemon's species data and Korean names, and
Minecraft's Korean biome and block names. Rerun it whenever the spawn pool or the catalog changes.

    python tools/server-wiki/gen_legends.py [--cobblemon JAR] [--minecraft-lang FILE]
"""
import argparse, json, pathlib, re, sys, zipfile

sys.path.insert(0, str(pathlib.Path(__file__).parent))

REPO = pathlib.Path(__file__).resolve().parents[2]
POLICY = REPO / "mods" / "jbro-policy" / "src" / "main"
POOL = POLICY / "resources/resourcepacks/legendary_spawns/data/jbro_policy/spawn_pool_world/legendary_wild_spawns.json"
CATALOG = POLICY / "kotlin/jbro/cobblemon/policy/legend/LegendCatalog.kt"
POLICY_LANG = POLICY / "resources/assets/jbro_policy/lang/ko_kr.json"
OUT = REPO.parent / "server-wiki" / "assets" / "data" / "legends.js"
DEFAULT_JAR = REPO.parent / "develop-product/client/mods/Cobblemon-fabric-1.8.1+1.21.1.jar"
LOOM_ASSETS = pathlib.Path.home() / ".gradle/caches/fabric-loom/assets"

TIER = {"LEGENDARY": "일반 전설", "MYTHICAL": "환상", "RESTRICTED": "제한급", "PARADOX": "패러독스"}
RANK = {"ULTRA_BALL": "하이퍼볼", "MASTER_BALL": "마스터볼", "CHAMPION": "챔피언"}
RARITY = {0.3: "희귀", 0.2: "매우 희귀", 0.15: "극희귀"}
POSITION = {"grounded": "땅 위", "surface": "물 위", "submerged": "물속"}
TIME = {"day": "낮", "night": "밤"}
# Minecraft's moon phases, 0 to 7; each lasts one in-game day.
MOON = ["보름달", "기우는 볼록달", "하현달", "그믐달", "삭", "초승달", "상현달", "차오르는 볼록달"]

from biomes import TAGS  # noqa: E402

END = {"minecraft:the_end", "minecraft:end_barrens", "minecraft:end_highlands", "minecraft:end_midlands",
       "minecraft:small_end_islands", "#cobblemon:is_end"}
NETHER_HINT = ("nether", "basalt", "crimson", "warped", "soul_sand")


def minecraft_lang(explicit):
    if explicit:
        return json.loads(pathlib.Path(explicit).read_text(encoding="utf-8"))
    for index in sorted((LOOM_ASSETS / "indexes").glob("*.json"), reverse=True):
        entry = json.loads(index.read_text(encoding="utf-8"))["objects"].get("minecraft/lang/ko_kr.json")
        if entry:
            path = LOOM_ASSETS / "objects" / entry["hash"][:2] / entry["hash"]
            return json.loads(path.read_text(encoding="utf-8"))
    raise SystemExit("Minecraft ko_kr.json not found; pass --minecraft-lang")


def catalog():
    """Legend id -> (species, tier, rank, entry, entry_all, aspect), read from LegendCatalog.kt. A regional form that is
    a Legend of its own (Galarian Zapdos) has its own id and its Cobblemon aspect."""
    out = {}
    pattern = re.compile(r'Legend\("([a-z0-9]+)", LegendTier\.(\w+), LegendRank\.(\w+)(?:, listOf\(([^)]*)\))?'
                         r'(, entryAll = true)?(?:, aspect = "([a-z]+)", id = "([a-z0-9-]+)")?\)')
    for species, tier, rank, entry, entry_all, aspect, id_ in pattern.findall(CATALOG.read_text(encoding="utf-8")):
        out[id_ or species] = (species, tier, rank, re.findall(r'"([a-z0-9]+)"', entry or ""), bool(entry_all), aspect or None)
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cobblemon", default=str(DEFAULT_JAR))
    parser.add_argument("--minecraft-lang")
    args = parser.parse_args()
    mc = minecraft_lang(args.minecraft_lang)
    policy_lang = json.loads(POLICY_LANG.read_text(encoding="utf-8"))
    legends = catalog()
    with zipfile.ZipFile(args.cobblemon) as jar:
        # Cobblemon's ko_kr.json is not strict JSON, so the names are read by pattern.
        lang = jar.read("assets/cobblemon/lang/ko_kr.json").decode("utf-8")
        names = dict(re.findall(r'"cobblemon\.species\.([a-z0-9]+)\.name"\s*:\s*"([^"]*)"', lang))
        types = dict(re.findall(r'"cobblemon\.type\.([a-z]+)"\s*:\s*"([^"]*)"', lang))
        species = {}
        for entry in jar.namelist():
            if entry.startswith("data/cobblemon/species/") and entry.endswith(".json"):
                data = json.loads(jar.read(entry))
                species[entry.rsplit("/", 1)[1][:-5]] = data

    def biome(id_):
        if id_.startswith("#"):
            key = id_.split(":", 1)[1]
            label = TAGS.get(key)
            assert label, f"No Korean name for biome tag {id_}"
            return {"name": label, "group": True}
        namespace, path = id_.split(":", 1)
        label = mc.get(f"biome.{namespace}.{path}")
        assert label, f"No Korean name for biome {id_}"
        return {"name": label, "group": False}

    spawns = json.loads(POOL.read_text(encoding="utf-8"))["spawns"]
    assert {s["id"].removeprefix("jbro-legendary-") for s in spawns} == set(legends), "spawn pool and catalog name different Legends"
    out = []
    for spawn in spawns:
        id_ = spawn["id"].removeprefix("jbro-legendary-")
        species_id, tier, rank, entry, entry_all, aspect = legends[id_]
        cond = spawn.get("condition", {})
        biomes = cond.get("biomes", [])
        dimensions = []
        if any(b not in END and not any(h in b for h in NETHER_HINT) for b in biomes):
            dimensions.append("오버월드")
        if any(any(h in b for h in NETHER_HINT) for b in biomes):
            dimensions.append("네더")
        if any(b in END for b in biomes):
            dimensions.append("엔드")
        conditions = []
        if "timeRange" in cond:
            conditions.append({"kind": "time", "text": TIME[cond["timeRange"]]})
        if cond.get("canSeeSky"):
            conditions.append({"kind": "sky", "text": "하늘이 보이는 곳"})
        if cond.get("isThundering"):
            conditions.append({"kind": "weather", "text": "뇌우"})
        elif cond.get("isRaining"):
            conditions.append({"kind": "weather", "text": "비"})
        if "moonPhase" in cond:
            conditions.append({"kind": "moon", "text": MOON[int(cond["moonPhase"])]})
        if "minLight" in cond or "maxLight" in cond:
            low, high = cond.get("minLight", 0), cond.get("maxLight", 15)
            text = f"밝은 곳 (밝기 {low} 이상)" if low > 0 else f"어두운 곳 (밝기 {high} 이하)"
            conditions.append({"kind": "light", "text": text})
        if "minY" in cond or "maxY" in cond:
            low, high = cond.get("minY"), cond.get("maxY")
            text = f"Y {low}~{high}" if low is not None and high is not None else f"Y {low} 이상" if low is not None else f"Y {high} 이하"
            conditions.append({"kind": "height", "text": text})
        for block in cond.get("neededNearbyBlocks", []):
            namespace, path = block.split(":", 1)
            conditions.append({"kind": "block", "text": f"근처에 {mc.get(f'block.{namespace}.{path}', block)}"})
        data = species[species_id]
        if aspect:
            # The form's own types, falling back to the species' for what the form leaves out.
            form = next(f for f in data.get("forms", []) if aspect in f.get("aspects", []))
            data = {**data, **{k: v for k, v in form.items() if k in ("primaryType", "secondaryType")}}
        kinds = [data.get("primaryType"), data.get("secondaryType")]
        line = policy_lang[f"legend.jbro_policy.appeared.{id_}"].replace("%1$s", "(플레이어)")
        out.append({
            "id": id_,
            "species": species_id,
            "name": policy_lang[f"legend.jbro_policy.name.{id_}"] if aspect else names.get(species_id, species_id),
            "dex": data.get("nationalPokedexNumber", 0),
            "types": [{"id": t, "name": types.get(t, t)} for t in kinds if t],
            "tier": TIER[tier],
            "rank": RANK[rank],
            "rarity": RARITY[round(spawn["weight"], 2)],
            "weight": spawn["weight"],
            "position": POSITION[spawn["spawnablePositionType"]],
            "dimensions": dimensions,
            "biomes": [biome(b) for b in biomes],
            "except": [biome(b)["name"] for b in spawn.get("anticondition", {}).get("biomes", [])],
            "conditions": conditions,
            "entry": [{"id": e, "name": names.get(e, e)} for e in entry],
            "entryAll": entry_all,
            "line": line,
        })
    out.sort(key=lambda legend: legend["dex"])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("/* Generated by tools/server-wiki/gen_legends.py from the server's Legendary spawn pool; do not edit. */\n"
                   "window.WIKI_LEGENDS = " + json.dumps(out, ensure_ascii=False, indent=1) + ";\n", encoding="utf-8")
    print(f"{len(out)} Legends -> {OUT.relative_to(REPO)}")


main()
