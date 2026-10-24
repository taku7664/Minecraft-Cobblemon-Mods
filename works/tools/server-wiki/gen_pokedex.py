"""Generates the wiki's Pokedex from what a server runs: every implemented Cobblemon species with its forms, base
stats, abilities, evolutions, learnset and wild spawns, plus move and ability tables. The data is fixed: rerun this
when the server's mods or species change, the wiki does not refresh it on its own.

Species include enabled data-pack overrides and species_additions, as recorded in the server world's level.dat.
Spawns use the same enabled pack order, with presets, exclusions, context compatibility and per-member herd
properties. Move numbers come from the Showdown data the server unpacks into its showdown/ folder.

    python works/tools/server-wiki/gen_pokedex.py [--server DIR] [--cobblemon JAR] [--minecraft-lang FILE]

Writes server-wiki/assets/data/dex/: index.js, moves.js, abilities.js and species/<id>.js.
"""
import argparse, json, pathlib, re, shutil, subprocess, sys, zipfile
from collections import defaultdict

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
from biomes import STRUCTURES, TAGS  # noqa: E402
from pokedex_sources import load_species, server_sources, mod_sources, open_archive, minecraft_source  # noqa: E402
from pokedex_spawns import generate_spawns  # noqa: E402

OUT = REPO.parent / "server-wiki" / "assets" / "data" / "dex"
DEFAULT_SERVER = REPO.parent / "develop-product" / "server"
LOOM_ASSETS = pathlib.Path.home() / ".gradle/caches/fabric-loom/assets"

BUCKETS = {"common": "흔함", "uncommon": "보통", "rare": "드묾", "ultra-rare": "매우 드묾", "boss": "알파"}
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
            # Fabric accepts literal newlines in strings; Mega Showdown's description uses them.
            ids = [json.loads(z.read("fabric.mod.json"), strict=False).get("id")]
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


def drop_table(form, item_name):
    """Preserve configured roll percentages, not final acquisition probabilities."""
    table = form.get("drops") or {}
    return {"amount": table.get("amount", 1), "entries": [
        {"id": entry["item"], "name": item_name(entry["item"]),
         "quantity": entry.get("quantityRange", entry.get("quantity", 1)),
         "percentage": entry.get("percentage", 100)}
        for entry in table.get("entries", []) if "item" in entry
    ]}


def form_signature(form):
    return tuple(form[key] for key in ("types", "stats", "abilities", "learn", "drops"))


def refresh_spawns(existing, output, spawns, species):
    """Refresh only spawn fields; preserve authored species, moves, abilities and drops byte-for-byte in value."""
    index = json.loads((existing / 'index.js').read_text(encoding='utf-8').rsplit(' = ', 1)[-1].rstrip(';\r\n'))
    implemented = {sid for sid,(data,_) in species.items() if data.get('implemented') is True}
    if {row['id'] for row in index} != implemented:
        raise ValueError('Implemented species changed; run a full generation before --spawns-only')
    details = {}
    for row in index:
        sid = row['id']
        saved = json.loads((existing/'species'/f'{sid}.js').read_text(encoding='utf-8').rsplit(' = ', 1)[-1].rstrip(';\r\n'))
        saved['spawns'] = spawns.get(sid, [])
        row['spawns'] = len(saved['spawns'])
        details[sid] = saved
    (output/'species').mkdir(parents=True, exist_ok=True)
    for name in ('moves.js', 'abilities.js'):
        if existing.resolve() != output.resolve():
            shutil.copyfile(existing/name, output/name)
    header = "/* Generated by works/tools/server-wiki/gen_pokedex.py from enabled server resources; do not edit. */\n"
    (output/'index.js').write_text(header+'window.WIKI_DEX = '+json.dumps(index,ensure_ascii=False,separators=(',',':'))+';\n',encoding='utf-8')
    for sid,detail in details.items():
        (output/'species'/f'{sid}.js').write_text('window.WIKI_SPECIES = window.WIKI_SPECIES || {};\nwindow.WIKI_SPECIES['+json.dumps(sid)+'] = '+json.dumps(detail,ensure_ascii=False,separators=(',',':'))+';\n',encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default=str(DEFAULT_SERVER))
    parser.add_argument("--cobblemon")
    parser.add_argument("--minecraft-lang")
    parser.add_argument("--minecraft-jar", help="Installed Minecraft server JAR (auto-detected in versions/ or .fabric/server/)")
    parser.add_argument("--output", type=pathlib.Path, default=OUT)
    parser.add_argument("--spawns-only", action="store_true", help="Refresh spawns without changing other existing dex data")
    parser.add_argument("--existing-dex", type=pathlib.Path, default=OUT)
    parser.add_argument("--preserve-existing-spawns", action="store_true",
                        help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.preserve_existing_spawns:
        parser.error('--preserve-existing-spawns preserved obsolete spawn tables; regenerate from enabled server resources instead')
    output = args.output
    server = pathlib.Path(args.server)
    jars = sorted((server / "mods").glob("*.jar"))
    cobblemon_jar = pathlib.Path(args.cobblemon) if args.cobblemon else next(j for j in jars if j.name.lower().startswith("cobblemon-fabric"))
    jars_by_id = mod_sources(jars)
    jars_by_id['vanilla'] = minecraft_source(server, args.minecraft_jar)
    installed = set(jars_by_id) | {"minecraft", "fabric", "cobblemon"}
    jars_by_id["cobblemon"] = cobblemon_jar
    sources = server_sources(server, jars_by_id)
    mc = minecraft_lang(args.minecraft_lang)
    moves_data = None if args.spawns_only else showdown_moves(server)

    with zipfile.ZipFile(cobblemon_jar) as jar:
        lang = lenient_lang(jar.read("assets/cobblemon/lang/ko_kr.json").decode("utf-8"))
    species = load_species(sources)

    def tr(key, fallback):
        return lang.get(key) or fallback

    def item_name(id_):
        namespace, path = id_.split(":", 1) if ":" in id_ else ("cobblemon", id_)
        return (lang.get(f"item.{namespace}.{path}") or mc.get(f"item.{namespace}.{path}") or mc.get(f"block.{namespace}.{path}")
                or path.replace("_", " "))

    spawn_lang = dict(mc, **lang)
    for source in dict.fromkeys(jars_by_id.values()):
        with open_archive(source) as archive:
            for name in sorted(archive.namelist()):
                if name.startswith('assets/') and name.endswith('/lang/ko_kr.json'):
                    spawn_lang.update(lenient_lang(archive.read(name).decode('utf-8')))
    generated_biomes = set()
    if 'cobblemon_dimensions' in jars_by_id and any(prefix=='resourcepacks/worldgen/' for _,prefix in sources):
        with open_archive(jars_by_id['cobblemon_dimensions']) as archive:
            worldgen=json.loads(archive.read('cobblemon_dimensions/worldgen.json'))
        generated_biomes={f'cobblemon_dimensions:{name}' for dimension in worldgen['dimensions'].values() for name in dimension['biomes']}
    spawns, pools = generate_spawns(sources, installed, spawn_lang, generated_biomes)
    if args.spawns_only:
        refresh_spawns(args.existing_dex, output, spawns, species)
        print(f'{len(species)} species checked; {sum(len(v) for v in spawns.values())} spawn listings from {pools} enabled pools')
        return

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
                "drops": drop_table(form, item_name),
            })
        # Keep forms whose drop tables differ, even when their battle data is identical.
        detail_forms = detail_forms[:1] + [f for f in detail_forms[1:] if form_signature(f) != form_signature(detail_forms[0])]
        base = detail_forms[0]
        labels = [EXCLUDED_LABELS[l] for l in data.get("labels", []) if l in EXCLUDED_LABELS]
        pre = next((sid for sid, (d, _) in species.items() if any(e.get("result", "").split(" ")[0] == id_ for e in d.get("evolutions", []))), None)
        index.append({
            "id": id_, "dex": data.get("nationalPokedexNumber", 0), "name": tr(f"cobblemon.species.{id_}.name", data.get("name", id_)),
            "types": base["types"], "stats": base["stats"], "gen": int(re.sub(r"\D", "", generation) or 0), "labels": labels,
            "spawns": len(spawns.get(id_, [])), "ev": [data.get("evYield", {}).get(s, 0) for s in STATS],
            "dropSearch": " ".join(sorted({value for f in detail_forms for entry in f["drops"]["entries"]
                                          for value in (entry["name"], entry["id"])})),
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
    (output / "species").mkdir(parents=True, exist_ok=True)
    header = "/* Generated by works/tools/server-wiki/gen_pokedex.py from enabled server resources; do not edit. */\n"
    (output / "index.js").write_text(header + "window.WIKI_DEX = " + json.dumps(index, ensure_ascii=False, separators=(",", ":")) + ";\n", encoding="utf-8")
    (output / "moves.js").write_text(header + "window.WIKI_MOVES = " + json.dumps(moves, ensure_ascii=False, separators=(",", ":")) + ";\n", encoding="utf-8")
    (output / "abilities.js").write_text(header + "window.WIKI_ABILITIES = " + json.dumps(abilities, ensure_ascii=False, separators=(",", ":")) + ";\n"
                                      "window.WIKI_DEX_NAMES = " + json.dumps(names, ensure_ascii=False, separators=(",", ":")) + ";\n", encoding="utf-8")
    for id_, detail in details.items():
        (output / "species" / f"{id_}.js").write_text(
            "window.WIKI_SPECIES = window.WIKI_SPECIES || {};\nwindow.WIKI_SPECIES[" + json.dumps(id_) + "] = "
            + json.dumps(detail, ensure_ascii=False, separators=(",", ":")) + ";\n", encoding="utf-8")
    missing_numbers = [m for m in moves if "type" not in moves[m]]
    print(f"{len(index)} species, {len(moves)} moves ({len(missing_numbers)} without numbers), {len(abilities)} abilities, "
          f"{sum(len(v) for v in spawns.values())} spawn entries from {pools} pools")


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


if __name__ == "__main__":
    main()
