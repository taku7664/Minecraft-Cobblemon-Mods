"""Generates the League's wild trainer data from kinds.py, Cobblemon's species data and the RCT Trainers+ pack.

Only skin file names and arm shapes are read from the pack; no image is copied. Writes, under the League module:
NPC classes, trainer definitions, the world spawn pool, the skin variation, the skin list and the class names in
both lang files. Files of kinds no longer listed are removed.

    python tools/wild-trainers/gen_wild_trainers.py [--cobblemon JAR] [--pack ZIP]
"""
import argparse, json, pathlib, re, struct, sys, zipfile, zlib
from collections import Counter

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from kinds import ACE, NORMAL  # noqa: E402

PROFILE = pathlib.Path(__file__).resolve().parents[3] / "develop-product/client"
DEFAULT_JAR = PROFILE / "mods/Cobblemon-fabric-1.8.1+1.21.1.jar"
DEFAULT_PACK = PROFILE / "resourcepacks/RCT Trainers+ [1.7] v2.2.zip"
RES = pathlib.Path(__file__).resolve().parents[2] / "mods/more-cobblemon-contents-league-challenge/src/main/resources"
NS = "more_cobblemon_contents_league_challenge"
NAME_KEY = f"npc.{NS}.wild_trainer."

# A light side income: the Tower and the Factory pay better for the harder battles.
BP = {"normal": 3, "ace": 6}
SKILL = {"normal": 2, "ace": 3}
EXCLUDED_LABELS = {"legendary", "mythical", "ultra_beast", "paradox", "restricted"}
REGIONAL = re.compile(r"\b(alolan|galarian|hisuian|paldean|region_bias)")
# Skin classes that are named characters, villains or pairs: never worn by a wild trainer.
BLOCKED_SKINS = re.compile(r"^(leader|gym_leader|sinnoh_leader|elite_four|champion|rival|title_defense|battleground|boss|commander|"
                           r"rocket_admin|shadow_admin|light_of_ruin|professor|prof|player|pokemon_trainer|expert|sucessor|game_freaks|"
                           r"dumbass|terror|friendly|pi|interviewers|reporter|cameraman|idol|team_rocket|team_galactic|shadow_grunt|"
                           r"burglar|double_team|black_emboar|black_ferrothorn)(_[a-z0-9_]+)?")
# The world spawn weight shared by every trainer kind that can appear in one biome, per bucket. A biome's uncommon
# Pokemon weigh well over a thousand together, so at 32 a trainer turned up once in thousands of spawns.
BIOME_WEIGHT = {"uncommon": 320.0, "rare": 100.0}


def png_alpha(data, x, y):
    """Alpha of pixel (x, y) of an 8-bit PNG, enough to tell slim arms from wide ones."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos, chunks, idat = 8, {}, b""
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind in (b"IHDR", b"PLTE", b"tRNS"):
            chunks[kind.decode()] = body
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    width, height, depth, color = struct.unpack(">IIBB", chunks["IHDR"][:10])
    assert depth == 8, depth
    channels = {6: 4, 2: 3, 3: 1, 4: 2, 0: 1}[color]
    raw = zlib.decompress(idat)
    stride = width * channels
    rows, prev, p = [], bytearray(stride), 0
    for _ in range(height):
        ftype = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            b = prev[i]
            c = prev[i - channels] if i >= channels else 0
            if ftype == 1: line[i] = (line[i] + a) & 255
            elif ftype == 2: line[i] = (line[i] + b) & 255
            elif ftype == 3: line[i] = (line[i] + (a + b) // 2) & 255
            elif ftype == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else (b if pb <= pc else c))) & 255
        rows.append(line); prev = line
    px = rows[y][x * channels:(x + 1) * channels]
    if color == 6: return px[3]
    if color == 4: return px[1]
    if color == 3:
        trns = chunks.get("tRNS", b"")
        return trns[px[0]] if px[0] < len(trns) else 255
    return 255


def pack_skins(pack_path):
    """Every single-trainer skin in the pack with a personal name, by skin class."""
    by_class = {}
    with zipfile.ZipFile(pack_path) as pack:
        for info in pack.infolist():
            m = re.match(r"assets/rctmod/textures/trainers/single/([a-z0-9_]+)\.png$", info.filename)
            if not m:
                continue
            name = m.group(1)
            parts = name.split("_")
            if len(parts) < 3 or not re.fullmatch(r"[0-9a-f]{4}", parts[-1]) or not parts[-2].isalpha():
                continue
            skin_class = "_".join(parts[:-2])
            try:
                slim = png_alpha(pack.read(info), 54, 20) == 0
            except Exception:
                slim = False
            by_class.setdefault(skin_class, []).append({"file": name, "slim": slim})
    return by_class


def load_species(jar_path):
    species = {}
    with zipfile.ZipFile(jar_path) as jar:
        for name in jar.namelist():
            if name.startswith("data/cobblemon/species/") and name.endswith(".json"):
                data = json.loads(jar.read(name))
                species[name.rsplit("/", 1)[1][:-5]] = data
    return species


def start_level(data):
    """The first level a line's first stage appears at, from how strong it is."""
    total = sum(data.get("baseStats", {}).values())
    for bound, level in ((350, 1), (420, 5), (470, 12), (520, 20), (560, 28)):
        if total < bound:
            return level
    return 36


def usable(name, species):
    data = species.get(name)
    return data is not None and data.get("implemented") is True and not (set(data.get("labels", [])) & EXCLUDED_LABELS)


def line_stages(first, species):
    """(species, first level) for a line's first stage and every evolution after it, branches included."""
    out, queue = [], [(first, start_level(species[first]), 0)]
    seen = set()
    while queue:
        name, start, depth = queue.pop(0)
        if name in seen:
            continue
        seen.add(name)
        out.append((name, start))
        for evolution in species[name].get("evolutions", []):
            result = evolution.get("result", "")
            requirements = evolution.get("requirements", [])
            if REGIONAL.search(result) or any(REGIONAL.search(json.dumps(r)) for r in requirements if r.get("variant") == "properties"):
                continue
            target = result.split(" ")[0]
            if not usable(target, species):
                continue
            levels = [r["minLevel"] for r in requirements if r.get("variant") == "level" and "minLevel" in r]
            if levels:
                level = max(levels[0], start + 1)
            else:
                level = max(start + 8, 26 if depth == 0 else 38)
            queue.append((target, min(level, 100), depth + 1))
    return out


def pool(kind, species):
    """A kind's stages: each species lasts from its first level until the stage after it starts."""
    stages = {}
    for first in kind["species"]:
        assert usable(first, species), f"{kind['id']}: {first} is unknown, unimplemented or legendary"
        line = line_stages(first, species)
        children = {}
        for name, start in line:
            for evolution in species[name].get("evolutions", []):
                children.setdefault(name, []).append(evolution.get("result", "").split(" ")[0])
        starts = dict(line)
        for name, start in line:
            nexts = [starts[c] for c in children.get(name, []) if c in starts]
            end = min(nexts) - 1 if nexts else 100
            if name not in stages:
                stages[name] = {"species": name, "min_level": start, "max_level": max(start, end)}
    return list(stages.values())


def spawn_weights(kinds):
    """Each kind's weight, so the kinds sharing a biome together keep that biome's trainer weight."""
    per_biome = {bucket: Counter() for bucket in BIOME_WEIGHT}
    for kind in kinds:
        for biome in kind["biomes"]:
            per_biome[bucket_of(kind)][biome] += 1
    overworld = {bucket: counts.get("#cobblemon:is_overworld", 0) for bucket, counts in per_biome.items()}
    weights = {}
    for kind in kinds:
        bucket = bucket_of(kind)
        crowd = [per_biome[bucket][b] + (overworld[bucket] if b != "#cobblemon:is_overworld" else 0) for b in kind["biomes"]]
        if "#cobblemon:is_overworld" in kind["biomes"]:
            others = [c + overworld[bucket] for b, c in per_biome[bucket].items() if b != "#cobblemon:is_overworld"]
            crowd = others or crowd
        weights[kind["id"]] = round(BIOME_WEIGHT[bucket] / (sum(crowd) / len(crowd)), 2)
    return weights


def bucket_of(kind):
    return "rare" if kind["tier"] == "ace" else "uncommon"


def write_json(path, data, indent=2):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=indent) + "\n", encoding="utf-8")


def update_lang(path, names):
    """Replaces the trainer class names in a lang file, keeping every other key where it is."""
    lang = json.loads(path.read_text(encoding="utf-8"))
    out, placed = {}, False
    for key, value in lang.items():
        if key.startswith(NAME_KEY):
            if not placed:
                out.update(names); placed = True
            continue
        out[key] = value
    if not placed:
        out.update(names)
    write_json(path, out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cobblemon", default=str(DEFAULT_JAR))
    parser.add_argument("--pack", default=str(DEFAULT_PACK))
    args = parser.parse_args()
    kinds = NORMAL + ACE
    ids = [k["id"] for k in kinds]
    assert len(ids) == len(set(ids)), [i for i, c in Counter(ids).items() if c > 1]
    species = load_species(args.cobblemon)
    skins = pack_skins(args.pack)
    weights = spawn_weights(kinds)

    npc_dir = RES / "data" / NS / "npcs"
    def_dir = RES / "data" / NS / "league-challenge" / "wild_trainers"
    for stale in list(npc_dir.glob("wild_*.json")) + list(def_dir.glob("*.json")):
        stale.unlink()

    used, spawns, ko, en = {}, [], {}, {}
    for kind in kinds:
        worn = []
        for skin_class in kind["skins"]:
            assert not BLOCKED_SKINS.fullmatch(skin_class), f"{kind['id']}: {skin_class} is a blocked skin class"
            assert skin_class in skins, f"{kind['id']}: no skins of class {skin_class}"
            worn += skins[skin_class]
        for skin in worn:
            used[skin["file"]] = skin
        key = NAME_KEY + kind["id"]
        ko[key], en[key] = kind["ko"], kind["en"]
        write_json(npc_dir / f"wild_{kind['id']}.json", {
            "resourceIdentifier": f"{NS}:wild_trainer",
            "names": [key],
            "hitbox": "player",
            "canDespawn": True,
            "ai": [{"type": "apply_behaviours", "behaviours": ["cobblemon:core"]}],
            "battleConfiguration": {"canChallenge": True},
            "skill": SKILL[kind["tier"]],
            "variation": {"skin": sorted(f"rct_{s['file']}" for s in worn)},
        })
        write_json(def_dir / f"{kind['id']}.json", {
            "schema_version": 2,
            "npc_class": f"{NS}:wild_{kind['id']}",
            "tier": kind["tier"],
            "bp": BP[kind["tier"]],
            "pokemon": pool(kind, species),
        })
        condition = {"biomes": kind["biomes"], "canSeeSky": True, "minSkyLight": 8}
        if kind["time"]:
            condition["timeRange"] = kind["time"]
        spawns.append({
            "id": f"wild_trainer_{kind['id']}",
            "type": "npc",
            "npcClass": f"{NS}:wild_{kind['id']}",
            "bucket": bucket_of(kind),
            "weight": weights[kind["id"]],
            "spawnablePositionType": "grounded",
            "condition": condition,
            "anticondition": {"biomes": ["#cobblemon:is_deep_dark"]},
        })
    write_json(RES / "data" / NS / "spawn_pool_world" / "wild_trainers.json",
               {"enabled": True, "neededInstalledMods": [], "neededUninstalledMods": [], "spawns": spawns})

    files = sorted(used)
    variations = [{"aspects": [], "layers": [], "model": "cobblemon:steve.geo", "poser": "cobblemon:standard",
                   "texture": "minecraft:textures/entity/player/wide/steve.png"}]
    for f in files:
        variations.append({"aspects": [f"rct_{f}"], "layers": [], "model": "cobblemon:alex.geo" if used[f]["slim"] else "cobblemon:steve.geo",
                           "poser": "cobblemon:standard", "texture": f"rctmod:textures/trainers/single/{f}.png"})
    write_json(RES / "assets" / NS / "bedrock/npcs/variations/wild_trainer/0_wild_trainer.json",
               {"name": f"{NS}:wild_trainer", "order": 0, "variations": variations}, indent=1)
    write_json(RES / "assets" / NS / "wild_trainer_skins.json", {"skins": [{"file": f, "slim": used[f]["slim"]} for f in files]}, indent=1)
    update_lang(RES / "assets" / NS / "lang/ko_kr.json", ko)
    update_lang(RES / "assets" / NS / "lang/en_us.json", en)
    print(f"normal {len(NORMAL)}, ace {len(ACE)}, skins {len(files)} ({sum(1 for f in files if used[f]['slim'])} slim)")


main()
