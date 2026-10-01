"""Generates the wiki's Pokedex from what a server runs: every implemented Cobblemon species with its forms, base
stats, abilities, evolutions, learnset and wild spawns, plus move and ability tables. The data is fixed: rerun this
when the server's mods or species change, the wiki does not refresh it on its own.

Spawns come from every spawn pool in the server's mods (built-in data packs included), skipping pools whose
neededInstalledMods are missing or neededUninstalledMods are present. Move numbers come from the Showdown data the
server unpacks into its showdown/ folder.

    python tools/server-wiki/gen_pokedex.py [--server DIR] [--cobblemon JAR] [--minecraft-lang FILE]

Writes server-wiki/assets/data/dex/: index.js, moves.js, abilities.js and species/<id>.js.
"""
import argparse, json, pathlib, re, shutil, subprocess, sys, zipfile
from collections import defaultdict

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
from biomes import STRUCTURES, TAGS  # noqa: E402

OUT = REPO / "server-wiki" / "assets" / "data" / "dex"
DEFAULT_SERVER = REPO / "dev-server"
LOOM_ASSETS = pathlib.Path.home() / ".gradle/caches/fabric-loom/assets"

BUCKETS = {"common": "흔함", "uncommon": "보통", "rare": "드묾", "ultra-rare": "매우 드묾", "boss": "오야붕"}
REGIONS = {"alolan": "알로라의 모습", "galarian": "가라르의 모습", "hisuian": "히스이의 모습", "paldean": "팔데아의 모습",
           "valencian": "발렌시아의 모습"}
POSITIONS = {"grounded": "땅 위", "surface": "물 위", "submerged": "물속", "seafloor": "바다 밑바닥", "lavafloor": "용암 바닥",
             "fishing": "낚시"}
TIMES = {"day": "낮", "night": "밤", "dawn": "새벽", "dusk": "해 질 녘", "morning": "아침", "noon": "한낮", "afternoon": "오후",
         "midnight": "한밤중", "twilight": "해 질 녘·새벽"}
MOON = ["보름달", "기우는 볼록달", "하현달", "그믐달", "삭", "초승달", "상현달", "차오르는 볼록달"]
STATS = ["hp", "attack", "defence", "special_attack", "special_defence", "speed"]
LEARN_KINDS = {"tm": "기술머신", "egg": "알 기술", "tutor": "가르침 기술", "legacy": "이전 세대 기술", "special": "특별한 기술",
               "evolution": "진화할 때", "form_change": "폼 변화"}
FORMS = {"Alola": "알로라의 모습", "Galar": "가라르의 모습", "Hisui": "히스이의 모습", "Paldea": "팔데아의 모습",
         "Paldea-Combat": "팔데아의 모습(컴뱃종)", "Paldea-Blaze": "팔데아의 모습(블레이즈종)", "Paldea-Aqua": "팔데아의 모습(워터종)",
         "F": "암컷", "Small": "작은 사이즈", "Large": "큰 사이즈", "Super": "특대 사이즈", "Dusk": "황혼의 모습",
         "Midnight": "한밤중의 모습", "Blue-Striped": "파랑줄무늬의 모습", "White-Striped": "하양줄무늬의 모습",
         "Eternal": "영원의 꽃", "Roaming": "도보폼", "Bond": "유대 변화", "Heat": "히트로토무", "Wash": "워시로토무",
         "Frost": "프로스트로토무", "Fan": "스핀로토무", "Mow": "커트로토무", "Yellow": "노랑", "White": "하양",
         "Low-Key": "로우한 모습", "Bloodmoon": "붉은 달"}
TYPES_KO = {"normal": "노말", "fire": "불꽃", "water": "물", "grass": "풀", "electric": "전기", "ice": "얼음", "fighting": "격투",
            "poison": "독", "ground": "땅", "flying": "비행", "psychic": "에스퍼", "bug": "벌레", "rock": "바위", "ghost": "고스트",
            "dragon": "드래곤", "dark": "악", "steel": "강철", "fairy": "페어리"}
OTHER_REQUIREMENTS = {"structure": "특정 구조물 근처", "property_range": "특수 조건", "party_member": "특정 포켓몬과 함께",
                      "blocks_traveled": "일정 거리 이동", "stat_compare": "능력치 비교", "stat_equal": "능력치 비교",
                      "recoil": "반동 피해 누적", "defeat": "특정 포켓몬 쓰러뜨리기", "battle_critical_hits": "한 배틀에서 급소 여러 번",
                      "use_move": "특정 기술 여러 번 사용", "advancement": "발전 과제 달성", "moon_phase": "특정 달 위상",
                      "damage_taken": "피해 누적", "attack_defence_ratio": "능력치 비교"}
EXCLUDED_LABELS = {"legendary": "전설", "mythical": "환상", "ultra_beast": "울트라비스트", "paradox": "패러독스",
                   "restricted": "제한급", "baby": "아기", "starter": "스타팅", "fossil": "화석"}


def minecraft_lang(explicit):
    if explicit:
        return json.loads(pathlib.Path(explicit).read_text(encoding="utf-8"))
    for index in sorted((LOOM_ASSETS / "indexes").glob("*.json"), reverse=True):
        entry = json.loads(index.read_text(encoding="utf-8"))["objects"].get("minecraft/lang/ko_kr.json")
        if entry:
            return json.loads((LOOM_ASSETS / "objects" / entry["hash"][:2] / entry["hash"]).read_text(encoding="utf-8"))
    raise SystemExit("Minecraft ko_kr.json not found; pass --minecraft-lang")


def lenient_lang(text):
    """Cobblemon's ko_kr.json is not strict JSON; read its "key": "value" pairs one by one."""
    out = {}
    for key, value in re.findall(r'"([^"\\]+)"\s*:\s*"((?:[^"\\]|\\.)*)"', text):
        try:
            out[key] = json.loads(f'"{value}"')
        except json.JSONDecodeError:
            out[key] = value
    return out


def mod_ids(jar):
    try:
        with zipfile.ZipFile(jar) as z:
            ids = [json.loads(z.read("fabric.mod.json")).get("id")]
            for name in z.namelist():
                if name.startswith("META-INF/jars/") and name.endswith(".jar"):
                    pass  # nested library jars carry no spawn pools worth reading
            return {i for i in ids if i}
    except (KeyError, zipfile.BadZipFile, json.JSONDecodeError):
        return set()


def showdown_moves(server):
    """Move numbers from the server's unpacked Showdown data."""
    moves_js = server / "showdown" / "data" / "moves.js"
    if not moves_js.exists():
        raise SystemExit(f"{moves_js} not found; start the server once so Cobblemon unpacks Showdown")
    script = ("const m=require(process.argv[1]).Moves;const o={};for(const[k,v]of Object.entries(m)){"
              "o[k]={t:v.type,c:v.category,p:v.basePower,a:v.accuracy,pp:v.pp,pr:v.priority||0}};"
              "process.stdout.write(JSON.stringify(o))")
    node = shutil.which("node") or "node"
    return json.loads(subprocess.run([node, "-e", script, str(moves_js.resolve())], capture_output=True, check=True).stdout)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default=str(DEFAULT_SERVER))
    parser.add_argument("--cobblemon")
    parser.add_argument("--minecraft-lang")
    args = parser.parse_args()
    server = pathlib.Path(args.server)
    jars = sorted((server / "mods").glob("*.jar"))
    cobblemon_jar = pathlib.Path(args.cobblemon) if args.cobblemon else next(j for j in jars if j.name.lower().startswith("cobblemon-fabric"))
    installed = set().union(*(mod_ids(j) for j in jars)) | {"minecraft", "fabric", "cobblemon"}
    mc = minecraft_lang(args.minecraft_lang)
    moves_data = showdown_moves(server)

    with zipfile.ZipFile(cobblemon_jar) as jar:
        lang = lenient_lang(jar.read("assets/cobblemon/lang/ko_kr.json").decode("utf-8"))
        species = {}
        for entry in jar.namelist():
            if entry.startswith("data/cobblemon/species/") and entry.endswith(".json"):
                data = json.loads(jar.read(entry))
                species[entry.rsplit("/", 1)[1][:-5]] = (data, entry.split("/")[3])

    def tr(key, fallback):
        return lang.get(key) or fallback

    def item_name(id_):
        namespace, path = id_.split(":", 1) if ":" in id_ else ("cobblemon", id_)
        return (lang.get(f"item.{namespace}.{path}") or mc.get(f"item.{namespace}.{path}") or mc.get(f"block.{namespace}.{path}")
                or path.replace("_", " "))

    def present(id_):
        """Whether a biome or tag can exist on this server: its namespace is a mod the server runs."""
        return id_.lstrip("#").split(":", 1)[0] in installed

    def biome(id_, unknown):
        if id_.startswith("#"):
            key = id_.split(":", 1)[1]
            if key in TAGS:
                return {"name": TAGS[key], "group": True}
            unknown.add(id_)
            return {"name": id_, "group": True}
        namespace, path = id_.split(":", 1)
        label = mc.get(f"biome.{namespace}.{path}")
        if not label:
            unknown.add(id_)
        return {"name": label or id_, "group": False}

    # ---- Spawns from every pool on the server -------------------------------------------------------------------
    spawns = defaultdict(list)
    unknown = set()
    pools = 0
    for jar_path in jars:
        with zipfile.ZipFile(jar_path) as z:
            for name in z.namelist():
                if not re.search(r"(^|/)data/[^/]+/spawn_pool_world/.+\.json$", name):
                    continue
                try:
                    pool = json.loads(z.read(name))
                except json.JSONDecodeError:
                    continue
                if not pool.get("enabled", True):
                    continue
                if any(m not in installed for m in pool.get("neededInstalledMods", [])):
                    continue
                if any(m in installed for m in pool.get("neededUninstalledMods", [])):
                    continue
                pools += 1
                for spawn in pool.get("spawns", []):
                    kind = spawn.get("type")
                    if kind == "pokemon":
                        targets = [spawn.get("pokemon", "")]
                    elif kind == "pokemon-herd":
                        targets = [h.get("pokemon", "") for h in spawn.get("herdablePokemon", [])]
                    else:
                        continue
                    cond = spawn.get("condition") or {}
                    entry = {
                        "bucket": BUCKETS.get(spawn.get("bucket"), spawn.get("bucket")),
                        "position": POSITIONS.get(spawn.get("spawnablePositionType"), spawn.get("spawnablePositionType")),
                        "biomes": [biome(b, unknown) for b in cond.get("biomes", []) if present(b)],
                        "except": [biome(b, unknown)["name"] for b in (spawn.get("anticondition") or {}).get("biomes", []) if present(b)],
                        "conditions": conditions(cond, item_name),
                        "herd": kind == "pokemon-herd",
                    }
                    # A spawn listing only biomes of mods this server lacks can never happen here.
                    if cond.get("biomes") and not entry["biomes"]:
                        continue
                    boosts = []
                    for multiplier in spawn.get("weightMultipliers", []) + ([spawn["weightMultiplier"]] if "weightMultiplier" in spawn else []):
                        c = multiplier.get("condition") or {}
                        if c.get("isPokeSnack"):
                            continue
                        text = ", ".join(conditions(c, item_name))
                        if text:
                            boosts.append(f"{text}일 때 {multiplier.get('multiplier', 1):g}배")
                    if boosts:
                        entry["boosts"] = boosts
                    for target in targets:
                        species_id = target.split(" ")[0].split(":")[-1]
                        notes = aspect_notes(target.split(" ")[1:], item_name)
                        if notes:
                            entry = dict(entry, notes=notes)
                        if entry not in spawns[species_id]:
                            spawns[species_id].append(entry)

    # ---- Moves and abilities -------------------------------------------------------------------------------------
    used_moves, used_abilities = set(), set()
    index, details = [], {}
    for id_, (data, generation) in species.items():
        if data.get("implemented") is not True:
            continue
        forms = [("기본", data)] + [(f.get("name", "?"), {**data, **f}) for f in data.get("forms", []) if not f.get("battleOnly")]
        detail_forms = []
        for form_name, form in forms:
            learn = defaultdict(list)
            for move in form.get("moves", []):
                kind, _, name = move.partition(":")
                (learn["level"].append([int(kind), name]) if kind.isdigit() else learn[kind].append(name))
                used_moves.add(name)
            abilities = []
            for ability in form.get("abilities", []):
                hidden = ability.startswith("h:")
                ability_id = ability[2:] if hidden else ability
                used_abilities.add(ability_id)
                abilities.append({"id": ability_id, "hidden": hidden})
            detail_forms.append({
                "name": FORMS.get(form_name, form_name),
                "types": [t for t in (form.get("primaryType"), form.get("secondaryType")) if t],
                "stats": [form.get("baseStats", {}).get(s, 0) for s in STATS],
                "abilities": abilities,
                "evolutions": [evolution(e, species, lang, item_name) for e in form.get("evolutions", [])],
                "learn": {k: (sorted(v) if k == "level" else sorted(set(v))) for k, v in learn.items()},
            })
        # Forms that differ only in looks (colours, seasons, regional spawn bias) add nothing to a battle guide.
        def battle_data(f):
            return (f["types"], f["stats"], f["abilities"], f["learn"])
        detail_forms = detail_forms[:1] + [f for f in detail_forms[1:] if battle_data(f) != battle_data(detail_forms[0])]
        base = detail_forms[0]
        labels = [EXCLUDED_LABELS[l] for l in data.get("labels", []) if l in EXCLUDED_LABELS]
        pre = next((sid for sid, (d, _) in species.items() if any(e.get("result", "").split(" ")[0] == id_ for e in d.get("evolutions", []))), None)
        index.append({
            "id": id_, "dex": data.get("nationalPokedexNumber", 0), "name": tr(f"cobblemon.species.{id_}.name", data.get("name", id_)),
            "types": base["types"], "stats": base["stats"], "gen": int(re.sub(r"\D", "", generation) or 0), "labels": labels,
            "spawns": len(spawns.get(id_, [])),
        })
        details[id_] = {
            "desc": tr(f"cobblemon.species.{id_}.desc", ""),
            "catchRate": data.get("catchRate"), "eggGroups": data.get("eggGroups", []), "maleRatio": data.get("maleRatio"),
            "height": data.get("height"), "weight": data.get("weight"), "evYield": [data.get("evYield", {}).get(s, 0) for s in STATS],
            "experienceGroup": data.get("experienceGroup"), "pre": pre, "forms": detail_forms, "spawns": spawns.get(id_, []),
        }

    moves = {}
    for move in sorted(used_moves):
        numbers = moves_data.get(move)
        moves[move] = {"name": tr(f"cobblemon.move.{move}", move), "desc": tr(f"cobblemon.move.{move}.desc", ""),
                       **({"type": numbers["t"].lower(), "cat": numbers["c"], "power": numbers["p"],
                           "acc": numbers["a"] if numbers["a"] is not True else None, "pp": numbers["pp"], "pri": numbers["pr"]} if numbers else {})}
    abilities = {a: {"name": tr(f"cobblemon.ability.{a}", a), "desc": tr(f"cobblemon.ability.{a}.desc", "")} for a in sorted(used_abilities)}
    names = {row["id"]: row["name"] for row in index}

    index.sort(key=lambda row: (row["dex"], row["id"]))
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "species").mkdir(parents=True)
    header = "/* Generated by tools/server-wiki/gen_pokedex.py from the server's species and spawn data; do not edit. */\n"
    (OUT / "index.js").write_text(header + "window.WIKI_DEX = " + json.dumps(index, ensure_ascii=False, separators=(",", ":")) + ";\n", encoding="utf-8")
    (OUT / "moves.js").write_text(header + "window.WIKI_MOVES = " + json.dumps(moves, ensure_ascii=False, separators=(",", ":")) + ";\n", encoding="utf-8")
    (OUT / "abilities.js").write_text(header + "window.WIKI_ABILITIES = " + json.dumps(abilities, ensure_ascii=False, separators=(",", ":")) + ";\n"
                                      "window.WIKI_DEX_NAMES = " + json.dumps(names, ensure_ascii=False, separators=(",", ":")) + ";\n", encoding="utf-8")
    for id_, detail in details.items():
        (OUT / "species" / f"{id_}.js").write_text(
            "window.WIKI_SPECIES = window.WIKI_SPECIES || {};\nwindow.WIKI_SPECIES[" + json.dumps(id_) + "] = "
            + json.dumps(detail, ensure_ascii=False, separators=(",", ":")) + ";\n", encoding="utf-8")
    missing_numbers = [m for m in moves if "type" not in moves[m]]
    print(f"{len(index)} species, {len(moves)} moves ({len(missing_numbers)} without numbers), {len(abilities)} abilities, "
          f"{sum(len(v) for v in spawns.values())} spawn entries from {pools} pools")
    if unknown:
        print("Biomes without a Korean name:", ", ".join(sorted(unknown)))


def aspect_notes(parts, item_name):
    """What a spawn's properties say about the Pokemon in words: its regional form, gender or held item."""
    notes = []
    for part in parts:
        key, _, value = part.partition("=")
        if not part or key in ("alpha", "region_bias") or part.endswith("bias"):
            continue  # Alphas are their own bucket; a regional bias only leans which form appears.
        if part in REGIONS:
            notes.append(REGIONS[part])
        elif part in ("male", "female"):
            notes.append("수컷" if part == "male" else "암컷")
        elif key == "held_item":
            notes.append(f"{item_name(value)} 지님")
        elif value:
            notes.append("특정 모습")
    return sorted(set(notes), key=notes.index)


def conditions(cond, item_name):
    out = []
    if cond.get("timeRange"):
        out.append(TIMES.get(cond["timeRange"], cond["timeRange"]))
    if cond.get("canSeeSky") is True:
        out.append("하늘이 보이는 곳")
    elif cond.get("canSeeSky") is False:
        out.append("하늘이 가려진 곳")
    if cond.get("minSkyLight", 0) >= 8 and "canSeeSky" not in cond:
        out.append("바깥(하늘빛 8 이상)")
    elif cond.get("maxSkyLight", 15) <= 7:
        out.append("하늘빛이 약한 곳")
    if cond.get("isThundering"):
        out.append("뇌우")
    elif cond.get("isRaining") is True:
        out.append("비")
    elif cond.get("isRaining") is False:
        out.append("맑은 날")
    if "moonPhase" in cond:
        phase = cond["moonPhase"]
        out.append(MOON[int(phase)] if str(phase).isdigit() else f"달 {phase}")
    if "minLight" in cond or "maxLight" in cond:
        low, high = cond.get("minLight", 0), cond.get("maxLight", 15)
        out.append(f"밝기 {low}~{high}")
    if "minY" in cond or "maxY" in cond:
        low, high = cond.get("minY"), cond.get("maxY")
        out.append(f"Y {low}~{high}" if low is not None and high is not None else f"Y {low} 이상" if low is not None else f"Y {high} 이하")
    for structure in cond.get("structures", []):
        out.append(f"{STRUCTURES.get(structure, structure)} 근처")
    for block in cond.get("neededNearbyBlocks", []):
        out.append(f"근처에 {item_name(block.lstrip('#'))}")
    for block in cond.get("neededBaseBlocks", []):
        out.append(f"{item_name(block.lstrip('#'))} 위")
    if cond.get("isSlimeChunk"):
        out.append("슬라임 청크")
    if "minLureLevel" in cond:
        out.append(f"미끼 효과 {cond['minLureLevel']} 이상")
    if "rodType" in cond:
        out.append(f"{item_name(cond['rodType'])} 사용")
    return out


def evolution(e, species, lang, item_name):
    target = e.get("result", "").split(" ")[0]
    target_name = lang.get(f"cobblemon.species.{target}.name", target)
    how = []
    variant = e.get("variant")
    if variant == "trade":
        how.append("교환")
    if variant == "item_interact" and e.get("requiredContext"):
        how.append(f"{item_name(e['requiredContext'])} 사용")
    for r in e.get("requirements", []):
        kind = r.get("variant")
        if kind == "level":
            how.append(f"Lv.{r.get('minLevel')}")
        elif kind == "friendship":
            how.append("친밀도")
        elif kind == "time_range":
            how.append(TIMES.get(r.get("range"), r.get("range", "")))
        elif kind == "held_item":
            how.append(f"{item_name(str(r.get('itemCondition', '')).lstrip('#'))} 지니기")
        elif kind == "biome":
            how.append("특정 바이옴")
        elif kind == "has_move":
            how.append(f"{lang.get('cobblemon.move.' + str(r.get('move')), r.get('move'))} 배움")
        elif kind == "has_move_type":
            how.append(f"{TYPES_KO.get(str(r.get('type')).lower(), r.get('type'))} 타입 기술 배움")
        elif kind in OTHER_REQUIREMENTS:
            how.append(OTHER_REQUIREMENTS[kind])
        elif kind == "weather":
            how.append("날씨")
        elif kind == "properties":
            target_props = str(r.get("target", ""))
            how.append({"gender=female": "암컷", "gender=male": "수컷"}.get(target_props, "특수 조건"))
        elif kind:
            how.append(kind)
    if variant == "level_up" and not any(h.startswith("Lv.") for h in how):
        how.insert(0, "레벨업")
    return {"to": target, "name": target_name, "how": [h for h in how if h]}


main()
