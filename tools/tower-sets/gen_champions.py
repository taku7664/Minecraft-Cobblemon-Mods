"""Writes the Battle Tower's Champion bosses: Blue, Lance and Cynthia with their own competitive entries.

Each Champion has six Pokemon from their main-series teams, trained like a real battle team (IVs of 31, full EVs,
battle items, one item each), plus a legendary that joins only when the Tower allows the legendary class. Every
entry exists once per gimmick: in Mega runs the ace holds its Mega Stone, in Dynamax runs everyone has a Dynamax
level and the ace Gigantamaxes where it can, in Tera runs everyone has a Tera Type. The script also points the boss
encounters at the Champions (Blue at the 5th win, Lance at the 10th, Cynthia at the 15th and 20th, any of the three
for the Master Ball bosses) and moves the Tower Aces, who used to be the bosses, to the advanced and pro regulars.

    python tools/tower-sets/gen_champions.py [path/to/RCT Trainers+.zip]
"""
import io
import json
import os
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
TOWER = ROOT / "more-cobblemon-contents-battle-tower/src/main/resources/data/more_cobblemon_contents/mcc-battle-tower"
DEFAULT_PACK = Path(os.path.expandvars(r"%APPDATA%\ModrinthApp\profiles\cobblemon-dev\resourcepacks\RCT Trainers+ [1.7] v2.2.zip"))
CHAMPION_TIER = 9  # outside every pool: only a Champion's roster uses these sets
STATS = ["hp", "attack", "defense", "special_attack", "special_defense", "speed"]
SHORT = {"hp": "hp", "atk": "attack", "def": "defense", "spa": "special_attack", "spd": "special_defense", "spe": "speed"}


def mon(species, ability, nature, item, moves, evs, tera, form=None, legendary=False):
    return {"species": species, "form": form, "ability": ability, "nature": nature, "item": item, "moves": moves,
            "evs": evs, "tera": tera, "legendary": legendary}


def ns(value):
    return value if ":" in value else f"cobblemon:{value}"


# The ace's gimmick overrides: Mega Stone and set for Mega runs, Gigantamax for Dynamax runs.
CHAMPIONS = {
    "blue": {
        "skin": "champion_terry_01b6",
        "names": ("Champion Blue", "챔피언 그린"),
        "ace": "blastoise",
        "team": [
            mon("pidgeot", "keeneye", "timid", "heavy_duty_boots", ["hurricane", "heatwave", "roost", "uturn"], "spa spe hp", "normal"),
            mon("alakazam", "magicguard", "timid", "life_orb", ["psyshock", "shadowball", "focusblast", "nastyplot"], "spa spe hp", "psychic"),
            mon("rhyperior", "solidrock", "adamant", "weakness_policy", ["earthquake", "stoneedge", "megahorn", "icepunch"], "hp atk def", "rock"),
            mon("gyarados", "intimidate", "jolly", "lum_berry", ["dragondance", "waterfall", "earthquake", "icefang"], "atk spe hp", "ground"),
            mon("exeggutor", "harvest", "modest", "sitrus_berry", ["leafstorm", "psychic", "sludgebomb", "sleeppowder"], "spa hp def", "grass"),
            mon("blastoise", "torrent", "modest", "white_herb", ["shellsmash", "hydropump", "icebeam", "darkpulse"], "spa spe hp", "water"),
            mon("mewtwo", "pressure", "timid", "leftovers", ["psystrike", "icebeam", "fireblast", "recover"], "spa spe hp", "psychic", legendary=True),
        ],
        "mega": {"item": "mega_showdown:blastoisinite", "ability": "torrent", "moves": ["waterpulse", "aurasphere", "darkpulse", "icebeam"]},
        "gmax": True,
    },
    "lance": {
        "skin": "champion_lance_0027",
        "names": ("Champion Lance", "챔피언 목호"),
        "ace": "dragonite",
        "team": [
            mon("gyarados", "intimidate", "jolly", "lum_berry", ["dragondance", "waterfall", "earthquake", "taunt"], "atk spe hp", "flying"),
            mon("aerodactyl", "unnerve", "jolly", "focus_sash", ["stealthrock", "stoneedge", "earthquake", "taunt"], "atk spe hp", "rock"),
            mon("charizard", "blaze", "timid", "expert_belt", ["fireblast", "airslash", "focusblast", "roost"], "spa spe hp", "fire"),
            mon("kingdra", "swiftswim", "modest", "choice_specs", ["dracometeor", "hydropump", "icebeam", "flipturn"], "spa spe hp", "water"),
            mon("salamence", "intimidate", "jolly", "leftovers", ["dragondance", "dualwingbeat", "earthquake", "roost"], "atk spe hp", "dragon"),
            mon("dragonite", "multiscale", "adamant", "life_orb", ["dragondance", "extremespeed", "earthquake", "firepunch"], "atk spe hp", "normal"),
            mon("rayquaza", "airlock", "jolly", "weakness_policy", ["dragonascent", "extremespeed", "earthquake", "dragondance"], "atk spe hp", "normal", legendary=True),
        ],
        "mega": {"item": "mega_showdown:dragoninite", "ability": "multiscale", "moves": ["dragondance", "extremespeed", "earthquake", "firepunch"]},
        "gmax": False,
    },
    "cynthia": {
        "skin": "champion_cynthia_03a5",
        "names": ("Champion Cynthia", "챔피언 난천"),
        "ace": "garchomp",
        "team": [
            mon("spiritomb", "infiltrator", "careful", "sitrus_berry", ["willowisp", "foulplay", "painsplit", "suckerpunch"], "hp spd atk", "ghost"),
            mon("roserade", "naturalcure", "timid", "focus_sash", ["spikes", "toxicspikes", "sleeppowder", "sludgebomb"], "spa spe hp", "poison"),
            mon("milotic", "marvelscale", "bold", "flame_orb", ["scald", "recover", "icebeam", "haze"], "hp def spd", "water"),
            mon("togekiss", "serenegrace", "calm", "leftovers", ["airslash", "dazzlinggleam", "thunderwave", "roost"], "hp spd def", "fairy"),
            mon("lucario", "justified", "jolly", "life_orb", ["swordsdance", "closecombat", "meteormash", "extremespeed"], "atk spe hp", "steel"),
            mon("garchomp", "roughskin", "jolly", "lum_berry", ["swordsdance", "earthquake", "dragonclaw", "stoneedge"], "atk spe hp", "ground"),
            mon("giratina", "levitate", "modest", "mega_showdown:griseous_core", ["willowisp", "hex", "dracometeor", "earthpower"], "hp spa spd", "ghost",
                form="origin", legendary=True),
        ],
        # Pokemon Champions players' set: Power Gem, Draco Meteor, Flamethrower, Earth Power around Protect.
        "mega": {"item": "mega_showdown:garchompite_z", "ability": "roughskin", "nature": "timid", "evs": "spa spe hp",
                 "moves": ["dracometeor", "powergem", "flamethrower", "earthpower"]},
        "gmax": False,
    },
}


def evs(spec):
    first, second, rest = (SHORT[s] for s in spec.split())
    values = {stat: 0 for stat in STATS}
    values[first], values[second], values[rest] = 252, 252, 4
    return values


def champion_sets(name, champion):
    sets = []
    for mechanic in ("mega", "dynamax", "tera"):
        for member in champion["team"]:
            ace = member["species"] == champion["ace"]
            entry = dict(member)
            if ace and mechanic == "mega":
                entry.update(champion["mega"])
            moves = entry["moves"]
            spread = entry["evs"]
            set_ = {
                "set_id": f"champion_{name}_{mechanic}_{member['species']}",
                "set_tier": CHAMPION_TIER,
                "mechanic_id": mechanic,
                "species_id": f"cobblemon:{member['species']}",
                "form_id": member["form"],
                "ability_id": ns(entry["ability"]),
                "nature_id": ns(entry["nature"]),
                "held_item_id": ns(entry["item"]),
                "moves": [ns(m) for m in moves],
                "ivs": {stat: 31 for stat in STATS},
                "evs": evs(spread),
            }
            if mechanic == "dynamax":
                set_["dmax_level"] = 10
                set_["gmax_factor"] = bool(ace and champion["gmax"])
            if mechanic == "tera":
                set_["tera_type"] = member["tera"]
            sets.append(set_)
    return sets


def write(path, document):
    """Writes JSON the way the Tower's data files are: two-space indent, CRLF line ends."""
    text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    path.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))


def main():
    pack = zipfile.ZipFile(Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PACK)
    lang = {}
    for name, champion in CHAMPIONS.items():
        sets = champion_sets(name, champion)
        items = {}
        for s in sets:
            items.setdefault(s["mechanic_id"], []).append(s["held_item_id"])
        for mechanic, held in items.items():
            assert len(held) == len(set(held)), (name, mechanic, held)
        write(TOWER / "pokemon-sets" / f"champion_{name}.json", {"schema_version": 4, "pokemon_sets": sets})
        skin = champion["skin"]
        image = Image.open(io.BytesIO(pack.read(f"assets/rctmod/textures/trainers/single/{skin}.png"))).convert("RGBA")
        trainer = {
            "trainer_id": f"champion_{name}",
            "display_name_key": f"trainer.more_cobblemon_contents.tower_champion_{name}",
            "team_style": "balanced",
            "signature_species_ids": [f"cobblemon:{champion['ace']}"],
            "roster_set_ids": [s["set_id"] for s in sets],
            "skin": f"rctmod:textures/trainers/single/{skin}.png",
            "slim": image.getpixel((54, 20))[3] == 0,
        }
        write(TOWER / "trainers" / f"champion_{name}.json", {"schema_version": 1, "trainers": [trainer]})
        lang[trainer["display_name_key"]] = champion["names"]

    aces = {}
    for path in sorted((TOWER / "encounters").glob("*_boss_*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        encounter = document["encounters"][0]
        key = (encounter["mechanic_id"], encounter["format"])
        aces[key] = sorted(set(aces.get(key, [])) | {t for t in encounter["trainer_ids"] if t.startswith("trainer_")})
        if encounter["opponent_kind"] == "master_ball_boss":
            encounter["trainer_ids"] = [f"champion_{name}" for name in CHAMPIONS]
            write(path, document)
        else:
            # One tier boss encounter per stage, each with its Champion.
            for stage, champion in (("introductory", "blue"), ("practical", "lance"), ("advanced", "cynthia")):
                staged = dict(encounter, encounter_id=f"{encounter['encounter_id']}_{stage}", stage_ids=[stage],
                              trainer_ids=[f"champion_{champion}"])
                target = path if stage == "introductory" else path.with_name(f"{path.stem}_{stage}.json")
                write(target, {"schema_version": document["schema_version"], "encounters": [staged]})
    # The Tower Aces were the bosses; they join the regulars of the advanced and pro stages.
    for path in sorted((TOWER / "encounters").glob("*_regular_*_advanced.json")) + sorted((TOWER / "encounters").glob("*_regular_*_pro.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        encounter = document["encounters"][0]
        extra = aces.get((encounter["mechanic_id"], encounter["format"]), [])
        encounter["trainer_ids"] = [t for t in encounter["trainer_ids"] if t not in extra] + extra
        write(path, document)

    for locale, index in (("en_us", 0), ("ko_kr", 1)):
        path = ROOT / f"more-cobblemon-contents-battle-tower/src/main/resources/assets/more_cobblemon_contents_battle_tower/lang/{locale}.json"
        raw = path.read_bytes().decode("utf-8")
        data = json.loads(raw)
        missing = {key: names[index] for key, names in lang.items() if data.get(key) != names[index]}
        if missing:
            body = raw.rstrip().rstrip("}").rstrip()
            newline = "\r\n" if "\r\n" in raw else "\n"
            body += "".join(f",{newline}  {json.dumps(k)}: {json.dumps(v, ensure_ascii=False)}" for k, v in missing.items() if k not in data)
            text = body + newline + "}" + newline
            assert all(k in json.loads(text) for k in lang)
            path.write_bytes(text.encode("utf-8"))
    print(f"{len(CHAMPIONS)} Champions written; Tower Aces moved to the regulars: {sum(map(len, aces.values()))}")


if __name__ == "__main__":
    main()
