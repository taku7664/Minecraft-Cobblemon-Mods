"""Rebuild the bundled Galar catalog against a specific installed Cobblemon jar.

Roster reference: https://www.serebii.net/swordshield/gyms.shtml and championcup.shtml.
Teams are adaptations: fixed starters, custom moves, Dynamax Piers and six-member hard teams.
Run with Python 3: generate_galar.py --cobblemon <Cobblemon-fabric-1.8.1.jar>
"""
from __future__ import annotations

import argparse
import io
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOD = ROOT / "works/mods/more-cobblemon-contents-league-challenge"
NS = "more_cobblemon_contents_league_challenge"
DATA = MOD / f"src/main/resources/data/{NS}/league-challenge"
ASSETS = MOD / f"src/main/resources/assets/{NS}"

# id, Korean name, specialty, battle cap, normal roster, original levels, hard roster
GYMS = [
    ("milo", "아킬", "grass", 22, "gossifleur eldegoss", [19, 20], "whimsicott sceptile ferrothorn rillaboom appletun eldegoss"),
    ("nessa", "야청", "water", 26, "goldeen arrokuda drednaw", [22, 23, 24], "pelipper barraskewda toxapex seismitoad kingdra drednaw"),
    ("kabu", "순무", "fire", 29, "ninetales arcanine centiskorch", [25, 25, 27], "torkoal ninetales arcanine salazzle chandelure centiskorch"),
    ("bea", "채두", "fighting", 38, "hitmontop pangoro sirfetchd machamp", [34, 34, 35, 36], "hitmontop pangoro sirfetchd hawlucha conkeldurr machamp"),
    ("allister", "어니언", "ghost", 38, "yamask@galarian mimikyu cursola gengar", [34, 34, 35, 36], "dusknoir mimikyu cursola chandelure polteageist gengar"),
    ("opal", "포플러", "fairy", 40, "weezing@galarian mawile togekiss alcremie", [36, 36, 37, 38], "weezing@galarian mawile togekiss clefable sylveon alcremie"),
    ("gordie", "마쿠와", "rock", 44, "barbaracle shuckle stonjourner coalossal", [40, 40, 41, 42], "barbaracle shuckle stonjourner tyranitar lycanroc coalossal"),
    ("melony", "멜론", "ice", 44, "frosmoth darmanitan@galarian eiscue lapras", [40, 40, 41, 42], "frosmoth darmanitan@galarian eiscue mamoswine weavile lapras"),
    ("piers", "두송", "dark", 48, "scrafty malamar skuntank obstagoon", [44, 45, 45, 46], "scrafty malamar skuntank krookodile bisharp obstagoon"),
    ("raihan", "금랑", "dragon", 50, "gigalith flygon sandaconda duraludon", [46, 47, 46, 48], "gigalith flygon sandaconda tyranitar excadrill duraludon"),
]
CUP = [
    ("cup_marnie", "마리", "dark", "liepard scrafty toxicroak morpeko grimmsnarl", [47, 47, 47, 48, 49], "liepard scrafty toxicroak morpeko bisharp grimmsnarl", "marnie"),
    ("cup_hop", "호브", "normal", "dubwool corviknight pincurchin snorlax rillaboom", [48, 48, 47, 47, 49], "dubwool corviknight pincurchin snorlax dragapult rillaboom", "hop"),
    ("cup_bede", "비트", "fairy", "mawile gardevoir rapidash@galarian hatterene", [51, 51, 52, 53], "mawile gardevoir rapidash@galarian togekiss grimmsnarl hatterene", "bede"),
    ("cup_nessa", "야청", "water", "golisopod pelipper barraskewda seaking drednaw", [51, 51, 52, 52, 53], "pelipper barraskewda toxapex seismitoad kingdra drednaw", "nessa"),
    ("cup_bea", "채두", "fighting", "hawlucha grapploct sirfetchd falinks machamp", [52, 52, 53, 53, 54], "hitmontop pangoro sirfetchd hawlucha conkeldurr machamp", "bea"),
    ("cup_allister", "어니언", "ghost", "dusknoir chandelure cursola polteageist gengar", [52, 52, 53, 53, 54], "dusknoir mimikyu cursola chandelure polteageist gengar", "allister"),
    ("cup_raihan", "금랑", "dragon", "torkoal goodra turtonator flygon duraludon", [53, 54, 54, 54, 55], "torkoal goodra turtonator flygon sandaconda duraludon", "raihan"),
    ("leon", "단델", "fire", "aegislash dragapult haxorus mrrime inteleon charizard", [62, 62, 63, 64, 64, 65], "aegislash dragapult haxorus mrrime inteleon charizard", "leon"),
]
PREFERRED = {
    "gossifleur": "magicalleaf razorleaf sing rapidspin", "eldegoss": "gigadrain pollenpuff synthesis cottonguard",
    "goldeen": "waterpulse hornattack agility supersonic", "arrokuda": "aquajet bite liquidation agility",
    "drednaw": "liquidation rockslide superpower swordsdance", "ninetales": "flamethrower solarbeam willowswisp nastyplot",
    "arcanine": "flareblitz extremespeed closecombat wildcharge", "centiskorch": "firelash leechlife powerwhip coil",
    "hitmontop": "fakeout closecombat suckerpunch wideguard", "pangoro": "knockoff drainpunch gunkshot bulletpunch",
    "sirfetchd": "closecombat bravebird leafblade knockoff", "machamp": "dynamicpunch stoneedge knockoff bulletpunch",
    "yamask": "hex willowisp protect nightshade", "mimikyu": "playrough shadowclaw shadowsneak swordsdance",
    "cursola": "shadowball earthpower icebeam strengthsap", "gengar": "shadowball sludgebomb focusblast thunderbolt",
    "weezing": "strangesteam sludgebomb willowisp painsplit", "mawile": "playrough ironhead suckerpunch swordsdance",
    "togekiss": "airslash dazzlinggleam flamethrower roost", "alcremie": "dazzlinggleam mysticalfire recover calmmind",
    "barbaracle": "shellsmash liquidation stoneedge earthquake", "shuckle": "stickyweb stealthrock toxic encore",
    "stonjourner": "rockslide earthquake heatcrash heavyslam", "coalossal": "heatwave meteorbeam earthpower protect",
    "frosmoth": "quiverdance icebeam bugbuzz gigadrain", "darmanitan": "iciclecrash flareblitz uturn superpower",
    "eiscue": "bellydrum iciclecrash liquidation zenheadbutt", "lapras": "hydropump freezedry thunderbolt icebeam",
    "scrafty": "fakeout drainpunch knockoff icepunch", "malamar": "superpower knockoff psychocut rockslide",
    "skuntank": "poisonjab suckerpunch fireblast crunch", "obstagoon": "facade knockoff closecombat obstruct",
    "gigalith": "rockslide stealthrock earthquake explosion", "flygon": "earthquake dragonclaw rockslide uturn",
    "sandaconda": "coil earthquake stoneedge glare", "duraludon": "flashcannon dracometeor thunderbolt darkpulse",
    "whimsicott": "tailwind moonblast encore taunt", "sceptile": "leafstorm dragonpulse focusblast substitute",
    "ferrothorn": "leechseed powerwhip gyroball protect", "rillaboom": "grassyglide woodhammer knockoff uturn",
    "appletun": "appleacid dragonpulse recover leechseed", "pelipper": "hurricane hydropump tailwind icebeam",
    "barraskewda": "liquidation closecombat psychicfangs flipturn", "toxapex": "scald recover haze toxicspikes",
    "seismitoad": "hydropump earthpower sludgewave grassknot", "kingdra": "hydropump dracometeor hurricane flipturn",
    "torkoal": "eruption heatwave earthpower protect", "salazzle": "flamethrower sludgewave nastyplot toxic",
    "chandelure": "shadowball flamethrower energyball psychic", "hawlucha": "acrobatics closecombat swordsdance roost",
    "conkeldurr": "drainpunch machpunch facade knockoff", "dusknoir": "poltergeist shadowsneak icepunch willowisp",
    "polteageist": "shellsmash shadowball storedpower gigadrain", "clefable": "moonblast flamethrower moonlight calmmind",
    "sylveon": "hypervoice mysticalfire psyshock wish", "tyranitar": "rockslide crunch icepunch lowkick",
    "lycanroc": "accelerock stoneedge closecombat swordsdance", "mamoswine": "earthquake iciclecrash iceshard stealthrock",
    "weavile": "tripleaxel knockoff iceshard swordsdance", "krookodile": "earthquake knockoff closecombat stoneedge",
    "bisharp": "ironhead knockoff suckerpunch swordsdance", "excadrill": "earthquake ironhead rockslide swordsdance",
    "liepard": "fakeout knockoff thunderwave encore", "toxicroak": "drainpunch gunkshot suckerpunch swordsdance",
    "morpeko": "aurawheel crunch partingshot psychicfangs", "grimmsnarl": "spiritbreak suckerpunch reflect lightscreen",
    "dubwool": "cottonguard bodypress rest sleeptalk", "corviknight": "bravebird bodypress roost irondefense",
    "pincurchin": "risingvoltage scald recover spikes", "snorlax": "bodyslam highhorsepower curse rest",
    "dragapult": "dracometeor shadowball flamethrower thunderbolt", "gardevoir": "moonblast psychic focusblast thunderbolt",
    "rapidash": "playrough psychocut highhorsepower swordsdance", "hatterene": "psychic dazzlinggleam mysticalfire calmmind",
    "golisopod": "firstimpression liquidation leechlife aquajet", "seaking": "waterfall megahorn drillrun knockoff",
    "falinks": "noretreat closecombat throat chop rockslide", "grapploct": "drainpunch liquidation icepunch bulkup",
    "goodra": "dracometeor muddywater flamethrower thunderbolt", "turtonator": "shellsmash flamethrower dragonpulse focusblast",
    "aegislash": "shadowball flashcannon shadowsneak kingsshield", "haxorus": "dragondance dragonclaw earthquake poisonjab",
    "mrrime": "freezedry psychic focusblast rapidspin", "inteleon": "hydropump icebeam darkpulse uturn",
    "charizard": "flamethrower hurricane solarbeam scorching sands",
}
ABILITIES = {"whimsicott": "prankster", "rillaboom": "grassy surge", "pelipper": "drizzle", "barraskewda": "swiftswim",
    "seismitoad": "swiftswim", "kingdra": "swiftswim", "torkoal": "drought", "ninetales": "drought", "arcanine": "intimidate",
    "hitmontop": "intimidate", "machamp": "noguard", "conkeldurr": "guts", "obstagoon": "guts", "duraludon": "stalwart",
    "gigalith": "sandstream", "tyranitar": "sandstream", "excadrill": "sandrush", "darmanitan": "gorillatactics",
    "frosmoth": "icescales", "clefable": "magicguard", "sylveon": "pixilate", "drednaw": "swiftswim",
    "charizard": "solarpower", "hatterene": "magicbounce", "grimmsnarl": "prankster", "dubwool": "fluffy",
    "hawlucha": "unburden", "malamar": "contrary", "pincurchin": "electricsurge", "togekiss": "serenegrace",
    "mawile": "intimidate", "toxapex": "regenerator", "eldegoss": "regenerator", "cursola": "perishbody"}
GMAX = {"drednaw", "centiskorch", "machamp", "gengar", "alcremie", "coalossal", "lapras", "duraludon", "grimmsnarl", "hatterene", "charizard"}

# First gym battles, independently checked against Serebii and Bulbapedia's trainer tables.
# No invented fourth move on an original three-move member. IVs/EVs/natures are our own defaults.
NORMAL_GYMS = {
    "milo": [("regenerator", "magicalleaf round rapidspin"), ("regenerator", "magicalleaf leafage round")],
    "nessa": [("swiftswim", "waterpulse whirlpool hornattack agility"), ("swiftswim", "aquajet bite whirlpool furyattack"), ("swiftswim", "razorshell watergun bite headbutt")],
    "kabu": [("flashfire", "willowisp firespin quickattack ember"), ("intimidate", "willowisp flamewheel bite agility"), ("flashfire", "flamewheel coil bugbite smokescreen")],
    "bea": [("technician", "triplekick quickattack counter revenge"), ("moldbreaker", "bulletpunch circlethrow nightslash workup"), ("steadfast", "detect swordsdance brutalswing revenge"), ("guts", "scaryface revenge knockoff strength")],
    "allister": [("wanderingspirit", "brutalswing hex disable"), ("disguise", "shadowsneak babydolleyes honeclaws slash"), ("weakarmor", "ancientpower curse hex"), ("cursedbody", "venoshock hex hypnosis payback")],
    "opal": [("levitate", "sludge fairywind tackle"), ("intimidate", "drainingkiss crunch irondefense astonish"), ("hustle", "airslash drainingkiss ancientpower"), ("sweetveil", "drainingkiss acidarmor sweetkiss")],
    "gordie": [("toughclaws", "shellsmash rocktomb razorshell"), ("contrary", "powersplit stoneedge strugglebug rocktomb"), ("powerspot", "stealthrock rocktomb bodyslam wonderroom"), ("steamengine", "tarshot stealthrock heatcrash rocktomb")],
    "melony": [("shielddust", "icywind featherdance bugbuzz hail"), ("zenmode", "iciclecrash headbutt taunt firefang"), ("iceface", "amnesia freezedry hail icywind"), ("shellarmor", "icywind icebeam surf sing")],
    "piers": [("intimidate", "fakeout sandattack brickbreak payback"), ("contrary", "nightslash foulplay psychocut payback"), ("aftermath", "suckerpunch screech toxic snarl"), ("reckless", "obstruct throatchop counter shadowclaw")],
    "raihan": [("sandstream", "stealthrock sandtomb rockblast bodypress"), ("levitate", "thunderpunch breakingswipe crunch steelwing"), ("sandspit", "protect glare firefang earthpower"), ("heavymetal", "breakingswipe bodypress stoneedge ironhead")],
}

# Role-specific hard builds. Values are custom balancing decisions, not original game stats.
# nature, held item, EVs; other attackers use 252 offense / 252 speed / 4 HP.
HARD_SUPPORT = {
    "whimsicott": ("timid", "focus_sash", {"hp": 252, "speed": 252, "defence": 4}),
    "ferrothorn": ("relaxed", "rocky_helmet", {"hp": 252, "defence": 88, "special_defence": 168}),
    "appletun": ("bold", "leftovers", {"hp": 252, "defence": 252, "special_defence": 4}),
    "eldegoss": ("bold", "sitrus_berry", {"hp": 252, "defence": 252, "special_defence": 4}),
    "toxapex": ("bold", "black_sludge", {"hp": 252, "defence": 252, "special_defence": 4}),
    "torkoal": ("quiet", "heat_rock", {"hp": 252, "special_attack": 252, "defence": 4}),
    "hitmontop": ("careful", "sitrus_berry", {"hp": 252, "special_defence": 252, "attack": 4}),
    "dusknoir": ("impish", "leftovers", {"hp": 252, "defence": 252, "attack": 4}),
    "weezing": ("bold", "black_sludge", {"hp": 252, "defence": 252, "special_defence": 4}),
    "clefable": ("bold", "leftovers", {"hp": 252, "defence": 252, "special_defence": 4}),
    "sylveon": ("modest", "throat_spray", {"hp": 252, "special_attack": 252, "defence": 4}),
    "alcremie": ("bold", "sitrus_berry", {"hp": 252, "defence": 252, "special_defence": 4}),
    "shuckle": ("relaxed", "mental_herb", {"hp": 252, "defence": 252, "special_defence": 4}),
    "lapras": ("modest", "light_clay", {"hp": 252, "special_attack": 252, "defence": 4}),
    "scrafty": ("careful", "assault_vest", {"hp": 252, "special_defence": 252, "attack": 4}),
    "gigalith": ("brave", "smooth_rock", {"hp": 252, "attack": 252, "special_defence": 4}),
    "grimmsnarl": ("careful", "light_clay", {"hp": 252, "special_defence": 252, "defence": 4}),
    "dubwool": ("impish", "chesto_berry", {"hp": 252, "defence": 252, "special_defence": 4}),
    "corviknight": ("impish", "leftovers", {"hp": 252, "defence": 252, "special_defence": 4}),
    "pincurchin": ("relaxed", "terrain_extender", {"hp": 252, "defence": 252, "special_attack": 4}),
    "snorlax": ("careful", "chesto_berry", {"hp": 252, "special_defence": 252, "attack": 4}),
}


# Held items for one trainer's member, where the slot order above would hand two members the same item. The hard
# League keeps Item Clause like the challenger: no two members of a team hold the same item. Keyed by trainer,
# since the same species keeps its slot item on another trainer's team. Custom balancing decisions (2026-10-18).
HARD_ITEMS = {
    ("allister", "chandelure"): "choice_specs", ("allister", "polteageist"): "white_herb",
    ("cup_allister", "chandelure"): "choice_specs", ("cup_allister", "polteageist"): "white_herb",
    # Machamp is the team's Gigantamax ace: no Choice item works while Dynamaxed, a Weakness Policy does.
    ("bea", "machamp"): "weakness_policy", ("cup_bea", "machamp"): "weakness_policy",
    ("cup_hop", "dubwool"): "rocky_helmet",
    ("nessa", "barraskewda"): "choice_band", ("cup_nessa", "barraskewda"): "choice_band",
    ("cup_raihan", "goodra"): "assault_vest",
    ("raihan", "flygon"): "choice_scarf",
    ("kabu", "chandelure"): "choice_specs",
    ("leon", "inteleon"): "choice_specs",
    ("milo", "rillaboom"): "choice_band",
    ("piers", "bisharp"): "black_glasses",
}


def write(kind: str, name: str, obj: dict) -> None:
    path = DATA / kind / (name + ".json")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cobblemon", type=Path, required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.cobblemon) as jar:
        korean = json.loads(jar.read("assets/cobblemon/lang/ko_kr.json"))
        species = {Path(n).stem: json.loads(jar.read(n)) for n in jar.namelist()
                   if n.startswith("data/cobblemon/species/") and n.endswith(".json")}
        with zipfile.ZipFile(io.BytesIO(jar.read("data/cobblemon/showdown.zip"))) as showdown:
            raw = showdown.read("data/moves.js").decode()
            moves = {m[1]: m[2] for m in re.finditer(r"^  (\w+): \{(.*?)(?=^  \w+: \{|\Z)", raw, re.M | re.S)}
    adjustments = []

    def member(token: str, level: int, hard: bool, index: int, ace: bool, original: tuple | None = None, gmax: bool = True,
               trainer: str = "") -> str:
        name, _, form = token.partition("@")
        form = {"galarian": "galar"}.get(form, form)
        base = species[name]
        variants = base.get("forms", [])
        variant = next((f for f in variants if f.get("name", "").lower() == form), None) if form else None
        if form and variant is None:
            raise ValueError(f"Missing form {token}")
        stats = (variant or {}).get("baseStats", base["baseStats"])
        learnset = (variant or {}).get("moves", base["moves"])
        available = {m.split(":", 1)[1] for m in learnset}
        wanted = (original[1] if original else PREFERRED[name]).replace("throat chop", "throatchop").replace("scorching sands", "scorchingsands").replace("willowswisp", "willowisp").split()
        chosen = [m for m in wanted if m in (moves if original else available)]
        if original and chosen != wanted:
            raise ValueError(f"Unsupported original move: {token}, {set(wanted) - set(chosen)}")
        if not original and len(chosen) != 4:
            types = [(variant or {}).get("primaryType", base["primaryType"]), (variant or {}).get("secondaryType", base.get("secondaryType"))]
            def score(move: str) -> tuple:
                record = moves.get(move, "")
                power = re.search(r"basePower: (\d+)", record)
                category = re.search(r'category: "(\w+)"', record)
                mtype = re.search(r'type: "(\w+)"', record)
                power = int(power[1]) if power else 0
                best = "Physical" if stats["attack"] > stats["special_attack"] else "Special"
                return (bool(mtype and mtype[1].lower() in types), bool(category and category[1] == best), min(power, 100), move)
            for move in sorted(available - set(chosen), key=score, reverse=True):
                if move in moves and len(chosen) < 4:
                    chosen.append(move)
            adjustments.append((token, sorted(set(wanted) - available), chosen))
        assert 1 <= len(chosen) <= 4, (token, chosen)
        abilities = [(a if isinstance(a, str) else str(a)).removeprefix("h:") for a in (variant or {}).get("abilities", base["abilities"])]
        ability = (original[0] if original else ABILITIES.get(name, "")).replace(" ", "")
        if original and ability not in abilities:
            raise ValueError(f"Unsupported original ability: {token}, {ability}")
        if ability not in abilities:
            ability = abilities[0]
        physical = stats["attack"] > stats["special_attack"]
        nature = "jolly" if physical else "timid"
        support = HARD_SUPPORT.get(name) if hard else None
        if support:
            nature = support[0]
        iv = 31 if hard else 20
        props = [name, "level="+str(level), "moves="+",".join(chosen), "ability="+ability, "nature="+nature,
                 "dmax_level=10", "gmax_factor="+str(gmax and ace and name in GMAX).lower()]
        if form:
            props.append("form="+form)
        if hard:
            items = ["focus_sash", "expert_belt", "life_orb", "leftovers", "expert_belt", "sitrus_berry"]
            held = items[index]
            if support:
                held = support[1]
            if name == "hawlucha":
                held = "power_herb"
                chosen = ["skyattack", "acrobatics", "closecombat", "swordsdance"]
                props[2] = "moves="+",".join(chosen)
            if name == "darmanitan":
                held = "choice_scarf"
            if name == "coalossal":
                held = "power_herb"
            if name in {"obstagoon", "conkeldurr"}:
                held = "flame_orb"
            if name == "grimmsnarl":
                held = "light_clay"
            held = HARD_ITEMS.get((trainer, name), held)
            props.append("held_item=cobblemon:"+held)
        for stat in ("hp", "attack", "defence", "special_attack", "special_defence", "speed"):
            props.append(f"{stat}_iv={iv}")
            ev = support[2].get(stat, 0) if support else 252 if hard and stat in {"speed", "attack" if physical else "special_attack"} else 4 if hard and stat == "hp" else 0
            props.append(f"{stat}_ev={ev}")
        return " ".join(props)

    def team(name: str, members: list[str]) -> dict:
        if name.endswith("_hard"):
            held = [p for m in members for p in m.split() if p.startswith("held_item=")]
            assert len(held) == len(set(held)), (name, held)
        return {"schema_version": 1, "pokemon": members}

    normal_ids = []
    hard_ids = []
    for index, (id_, ko, type_, cap, normal, levels, hard_team) in enumerate(GYMS):
        for hard in (False, True):
            name = "galar/"+id_+("_hard" if hard else "")
            tokens = (hard_team if hard else normal).split()
            stage = [1, 2, 3, 4, 4, 5, 6, 6, 7, 8][index]
            low = [79, 79, 80, 80, 81, 81, 82, 83][stage-1]
            team_levels = [low, low+1, low+1, low+1, low+1, low+2] if hard else levels
            write("teams", name, team(name, [member(t, lv, hard, i, i == len(tokens)-1,
                None if hard else NORMAL_GYMS[id_][i], gmax=hard or id_ != "nessa", trainer=id_) for i, (t, lv) in enumerate(zip(tokens, team_levels, strict=True))]))
            write("challenges", name, {"schema_version": 1, "trainer": NS+":"+name, "reward": NS+":"+name,
                **({"badge": "pokebadges:"+type_+"_galar_badge"} if not hard else {}), "mechanic": "DYNAMAX",
                "format": "DOUBLE" if id_ == "raihan" else "SINGLE", "battle_cap": 80 if hard else cap, "type": type_})
            write("rewards", name, {"schema_version": 1, "unlock_cap": 100 if hard and stage == 8 else 80, "first_bp": 30 if hard else 10, "repeat_bp": 0})
            (hard_ids if hard else normal_ids).append(NS+":"+name)

    final_ids, hard_final_ids = [], []
    all_people = {row[0]: row[1] for row in GYMS}
    for index, (id_, ko, type_, normal, levels, hard_team, person) in enumerate(CUP):
        all_people[person] = ko
        for hard in (False, True):
            name = "galar/"+id_+("_hard" if hard else "")
            tokens = (hard_team if hard else normal).split()
            team_levels = ([101, 102, 103, 102, 104, 105] if id_ == "leon" else [94, 95, 95, 96, 96, 97]) if hard else levels
            write("teams", name, team(name, [member(t, lv, hard, i, i == len(tokens)-1, trainer=id_) for i, (t, lv) in enumerate(zip(tokens, team_levels, strict=True))]))
            write("challenges", name, {"schema_version": 1, "trainer": NS+":"+name, "reward": NS+":"+name,
                "mechanic": "DYNAMAX", "format": "SINGLE", "battle_cap": 100 if hard else 67, "type": type_})
            write("rewards", name, {"schema_version": 1, "unlock_cap": 100 if hard else 80,
                "first_bp": (300 if hard else 150) if id_ == "leon" else 0, "repeat_bp": 0})
            if id_ != "cup_allister":
                (hard_final_ids if hard else final_ids).append(NS+":"+name)

    def stages(ids: list[str]) -> list:
        return ids[:3]+[ids[3:5], ids[5], ids[6:8]]+ids[8:]
    write("leagues", "galar", {"schema_version": 2, "progress_id": NS+":galar", "name_key": "league."+NS+".galar",
        "initial_cap": 80, "unlock_league": NS+":sinnoh", "player_mechanic": "DYNAMAX", "lock_mechanics_until_champion": False,
        "gyms": stages(normal_ids), "finals": final_ids,
        "final_choices": {NS+":galar/cup_bea": [NS+":galar/cup_bea", NS+":galar/cup_allister"]},
        "hard": {"gyms": stages(hard_ids), "finals": hard_final_ids,
            "final_choices": {NS+":galar/cup_bea_hard": [NS+":galar/cup_bea_hard", NS+":galar/cup_allister_hard"]}}})
    for person in all_people:
        write("appearances", "galar/"+person, {"schema_version": 1, "model": "default", "skin": NS+":textures/npcs/galar/"+person+".png"})
    for challenge in sorted((DATA/"challenges/galar").glob("*.json")):
        name = "galar/"+challenge.stem
        person = challenge.stem.removesuffix("_hard").removeprefix("cup_")
        hard = challenge.stem.endswith("_hard")
        skill = 5 if person == "leon" or hard and challenge.stem.startswith("cup_") else 4 if hard or challenge.stem.startswith("cup_") else 2
        write("trainers", name, {"schema_version": 1, "ai_skill": skill, "name_key": "trainer."+NS+".galar."+person,
            "team": NS+":"+name, "appearance": NS+":galar/"+person,
            "scenes": {moment: ["trainer."+NS+".galar."+person+(".hard" if hard else "")+"."+moment]
                       for moment in ["battle_start", "last_pokemon", "player_won", "player_lost"]}})
    for lang in ("ko_kr", "en_us"):
        path = ASSETS / "lang" / (lang+".json")
        translations = json.loads(path.read_text(encoding="utf-8"))
        translations["league."+NS+".galar"] = "가라르지방" if lang == "ko_kr" else "Galar"
        for person, ko in all_people.items():
            key = "trainer."+NS+".galar."+person
            translations[key] = ko if lang == "ko_kr" else person.title()
            for hard in (False, True):
                prefix = key+(".hard" if hard else "")
                lines = {"battle_start": "이번에는 전력을 다하겠습니다!" if hard else "좋아요! 멋진 승부를 해 봅시다!",
                    "last_pokemon": "승부는 아직 끝나지 않았습니다!", "player_won": "멋진 승부였습니다. 다음에도 도전해 주세요!",
                    "player_lost": "좋은 승부였습니다. 다시 기다리겠습니다!"} if lang == "ko_kr" else {
                    "battle_start": "This time, I am giving it everything!" if hard else "Let us make this a great battle!",
                    "last_pokemon": "The battle is not over yet!", "player_won": "Well played! Come challenge me again!", "player_lost": "A good battle. I will be waiting!"}
                translations.update({prefix+"."+moment: text for moment, text in lines.items()})
        path.write_text(json.dumps(translations, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    def pokemon_name(token: str) -> str:
        species, _, form = token.partition("@")
        return ("가라르 " if form else "") + korean.get(f"cobblemon.species.{species}.name", species)
    report = ["# 가라르 리그 엔트리", "", "일반 관장전의 종족·폼·순서·레벨·기술·특성·무도구는 원작 첫 관장전을 기준으로 합니다.",
        "참조: [Serebii 관장전](https://www.serebii.net/swordshield/gyms.shtml), [Bulbapedia 관장별 표](https://bulbapedia.bulbagarden.net/wiki/Category:Galar_Gym_Leaders).",
        "일반의 개체값 20·노력치 0·성격은 자체 설정입니다. 공식 개체값·노력치라고 주장하지 않습니다.",
        "하드는 자체 구성으로 전원 6마리, 개체값 31·노력치 합계 508입니다. 마지막 포켓몬이 에이스이며, 실제 AI 난이도·승률은 실전 밸런스 검증 대상입니다.",
        "", "## 관장 구성", "", "| 관장 | 일반 | 하드 | 하드 레벨 범위 |", "|---|---|---|---|"]
    for id_, ko, type_, cap, normal, levels, hard_team in GYMS:
        normal_text = " / ".join(f"{pokemon_name(t)} {lv}" for t, lv in zip(normal.split(), levels, strict=True))
        hard_lines = json.loads((DATA/f"teams/galar/{id_}_hard.json").read_text(encoding="utf-8"))["pokemon"]
        actual_levels = [int(line.split("level=")[1].split()[0]) for line in hard_lines]
        report.append(f"| {ko} | {normal_text} | {' / '.join(pokemon_name(t) for t in hard_team.split())} | {min(actual_levels)}–{max(actual_levels)} |")
    report += ["", "일반 도전 캡은 22·26·29·38·40·44·48·50, 하드 관장 도전 캡은 80입니다. 금랑 관장전은 더블이며 나머지는 싱글입니다.",
        "채두/어니언과 마쿠와/멜론은 각각 같은 단계의 선택지입니다. 한 명만 이겨도 다음 단계가 열리며 나머지도 도전할 수 있습니다.",
        "원작 예외: 두송도 다이맥스를 사용합니다. 야청 일반 관장전은 일반 다이맥스, 야청 컵·하드는 거다이맥스입니다.",
        "", "## 컵 구성", "", "마리 → 호브 → 비트 → 야청 → 채두/어니언 → 금랑 → 단델. 전원 싱글입니다.",
        "호브의 스타팅은 고릴타, 단델의 스타팅 대응은 인텔리레온으로 고정한 원작 기반 구성입니다. 일반 컵의 기술·성격 등은 자체 조정이며 첫 관장전의 원작 재현과 구분합니다.",
        "일반 컵 도전 캡 67, 하드 컵 도전 캡 100. 하드 컵은 94–97, 단델 하드는 101–105레벨입니다.",
        "", "## 하드 상세 설정", "", "아래 값은 생성된 데이터와 동일합니다. 노력치 약자는 HP/공격/방어/특공/특방/스피드 순서이며, 0인 항목은 생략합니다."]
    stat_names = {"hp": "HP", "attack": "공격", "defence": "방어", "special_attack": "특공", "special_defence": "특방", "speed": "스피드"}
    for id_, ko, *_ in GYMS:
        report += ["", f"### {ko}", "", "| 포켓몬 | 레벨 | 특성 / 성격 / 도구 | 노력치 | 기술 |", "|---|---|---|---|---|"]
        for line in json.loads((DATA/f"teams/galar/{id_}_hard.json").read_text(encoding="utf-8"))["pokemon"]:
            parts = line.split(); f = dict(p.split("=", 1) for p in parts[1:])
            evs = ", ".join(f"{label} {f[stat+'_ev']}" for stat, label in stat_names.items() if f[stat+'_ev'] != "0")
            token = parts[0] + ("@galar" if f.get("form") == "galar" else "")
            report.append(f"| {pokemon_name(token)} | {f['level']} | {f['ability']} / {f['nature']} / {f['held_item'].split(':')[1]} | {evs} | {f['moves'].replace(',', ', ')} |")
    doc = MOD / "docs/GALAR_TEAMS.md"
    doc.parent.mkdir(parents=True, exist_ok=True)
    doc.write_text("\n".join(report)+"\n", encoding="utf-8")
    print(json.dumps({"challenges": 36, "trainers": len(all_people), "adapted_moves": sorted(set(str(x) for x in adjustments))}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
