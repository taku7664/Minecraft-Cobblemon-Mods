"""Writes the Battle Tower's Champion bosses and lays out the Tower's boss battles.

Champions: Blue, Lance and Cynthia (skins from RCT Trainers+), and Steven, Wallace, Alder, Iris, Diantha, Geeta,
Nemona and N (skins from the More Radical Textures pack of the More Radical Trainers add-on, "mrt").
Bosses: every 5th win is a Champion drawn at random. The Tower Aces, bosses once, fight among the
regular opponents of the advanced and pro stages (from the 11th win). Each Champion's roster holds several battle-trained sets per member (IVs of 31, full EVs, battle items),
once per gimmick:
- Mega runs: members that can Mega Evolve also have Mega Stone sets; the Tower draws one of them to hold its stone.
- Dynamax runs: everyone has a Dynamax level; Blastoise and Charizard Gigantamax.
- Tera runs: everyone has a Tera Type.
Each Champion also has a legend table. With the legendary class allowed they bring as many legendaries as the
challenger: the main line (a restricted legendary) first, then sub lines (sub-legendary, Mythical, Ultra Beast,
Paradox) at random, each in place of the member it stands for.

    python tools/tower-sets/gen_champions.py [path/to/RCT Trainers+.zip] [path/to/MoreRadicalTextures.zip]
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
DEFAULT_MRT_PACK = Path(os.path.expandvars(r"%APPDATA%\ModrinthApp\profiles\cobblemon-dev\resourcepacks\MoreRadicalTextures1.8.zip"))
CHAMPION_TIER = 9  # outside every pool: only a Champion's roster uses these sets
STATS = ["hp", "attack", "defense", "special_attack", "special_defense", "speed"]
SHORT = {"hp": "hp", "atk": "attack", "def": "defense", "spa": "special_attack", "spd": "special_defense", "spe": "speed"}
SPECIAL = "spa spe hp"
PHYSICAL = "atk spe hp"
# The Tower Aces of each gimmick and format: strong regular opponents from the advanced stage on.
ACES = {
    ("dynamax", "double"): range(97, 101), ("dynamax", "single"): range(101, 105),
    ("mega", "double"): range(105, 109), ("mega", "single"): range(109, 113),
    ("tera", "double"): range(113, 117), ("tera", "single"): range(117, 121),
}


def s(ability, nature, item, moves, evs, tera=None):
    """One set: ability, nature, item, four moves, a 252/252/4 spread and an optional Tera Type override."""
    return {"ability": ability, "nature": nature, "item": item, "moves": moves.split(), "evs": evs, "tera": tera}


def member(species, tera, sets, stones=(), gmax=False, form=None):
    return {"species": species, "tera": tera, "sets": list(sets), "stones": list(stones), "gmax": gmax, "form": form}


CHAMPIONS = {
    "blue": {
        "skin": "champion_terry_01b6",
        "names": ("Champion Blue", "챔피언 그린"),
        "ace": "blastoise",
        "team": [
            member("pidgeot", "normal", [s("keeneye", "timid", "heavy_duty_boots", "hurricane heatwave roost uturn", SPECIAL)],
                   [s("keeneye", "timid", "mega_showdown:pidgeotite", "hurricane heatwave roost uturn", SPECIAL)]),
            member("alakazam", "psychic", [s("magicguard", "timid", "life_orb", "psyshock shadowball focusblast nastyplot", SPECIAL),
                                           s("magicguard", "timid", "choice_specs", "psychic shadowball focusblast dazzlinggleam", SPECIAL)],
                   [s("magicguard", "timid", "mega_showdown:alakazite", "psyshock shadowball focusblast nastyplot", SPECIAL)]),
            member("rhyperior", "rock", [s("solidrock", "adamant", "weakness_policy", "earthquake stoneedge megahorn icepunch", "hp atk def")]),
            member("gyarados", "ground", [s("intimidate", "jolly", "lum_berry", "dragondance waterfall earthquake icefang", PHYSICAL)],
                   [s("intimidate", "adamant", "mega_showdown:gyaradosite", "dragondance waterfall crunch earthquake", PHYSICAL)]),
            member("exeggutor", "grass", [s("harvest", "modest", "sitrus_berry", "leafstorm psychic sludgebomb sleeppowder", "spa hp def")]),
            member("blastoise", "water", [s("torrent", "modest", "white_herb", "shellsmash hydropump icebeam darkpulse", SPECIAL),
                                          s("torrent", "calm", "assault_vest", "scald icebeam aurasphere rapidspin", "hp spd def")],
                   [s("torrent", "modest", "mega_showdown:blastoisinite", "waterpulse aurasphere darkpulse icebeam", SPECIAL),
                    s("torrent", "modest", "mega_showdown:blastoisinite", "shellsmash waterpulse darkpulse aurasphere", SPECIAL)], gmax=True),
        ],
        "legends": [
            ("mewtwo", "alakazam", True, member("mewtwo", "psychic", [s("pressure", "timid", "life_orb", "psystrike icebeam fireblast recover", SPECIAL)])),
            ("zapdos", "pidgeot", False, member("zapdos", "electric", [s("static", "timid", "heavy_duty_boots", "thunderbolt hurricane heatwave roost", SPECIAL)])),
            ("moltres", "exeggutor", False, member("moltres", "fire", [s("flamebody", "timid", "rocky_helmet", "fireblast hurricane roost willowisp", SPECIAL)])),
            ("mew", "rhyperior", False, member("mew", "psychic", [s("synchronize", "timid", "expert_belt", "nastyplot psyshock aurasphere fireblast", SPECIAL)])),
        ],
    },
    "lance": {
        "skin": "champion_lance_0027",
        "names": ("Champion Lance", "챔피언 목호"),
        "ace": "dragonite",
        "team": [
            member("gyarados", "flying", [s("intimidate", "jolly", "lum_berry", "dragondance waterfall earthquake taunt", PHYSICAL)],
                   [s("intimidate", "adamant", "mega_showdown:gyaradosite", "dragondance waterfall crunch earthquake", PHYSICAL)]),
            member("aerodactyl", "rock", [s("unnerve", "jolly", "focus_sash", "stealthrock stoneedge earthquake taunt", PHYSICAL)],
                   [s("unnerve", "jolly", "mega_showdown:aerodactylite", "stoneedge dualwingbeat earthquake firefang", PHYSICAL)]),
            member("charizard", "fire", [s("blaze", "timid", "expert_belt", "fireblast airslash focusblast roost", SPECIAL)],
                   [s("blaze", "jolly", "mega_showdown:charizardite_x", "dragondance flareblitz dragonclaw earthquake", PHYSICAL),
                    s("blaze", "timid", "mega_showdown:charizardite_y", "fireblast solarbeam airslash focusblast", SPECIAL)], gmax=True),
            member("kingdra", "water", [s("swiftswim", "modest", "choice_specs", "dracometeor hydropump icebeam flipturn", SPECIAL)]),
            member("salamence", "dragon", [s("intimidate", "jolly", "leftovers", "dragondance dualwingbeat earthquake roost", PHYSICAL)],
                   [s("intimidate", "adamant", "mega_showdown:salamencite", "doubleedge earthquake dragondance roost", PHYSICAL)]),
            member("dragonite", "normal", [s("multiscale", "adamant", "life_orb", "dragondance extremespeed earthquake firepunch", PHYSICAL),
                                           s("multiscale", "adamant", "choice_band", "extremespeed outrage earthquake firepunch", PHYSICAL)],
                   [s("multiscale", "adamant", "mega_showdown:dragoninite", "dragondance extremespeed earthquake firepunch", PHYSICAL)]),
        ],
        "legends": [
            ("rayquaza", "salamence", True, member("rayquaza", "normal", [s("airlock", "jolly", "weakness_policy", "dragonascent extremespeed earthquake dragondance", PHYSICAL)])),
            ("latios", "kingdra", False, member("latios", "dragon", [s("levitate", "timid", "choice_specs", "dracometeor lusterpurge surf flipturn", SPECIAL)])),
            ("latias", "charizard", False, member("latias", "dragon", [s("levitate", "timid", "expert_belt", "dracometeor mistball psyshock recover", SPECIAL)])),
            ("roaringmoon", "aerodactyl", False, member("roaringmoon", "flying", [s("protosynthesis", "jolly", "focus_sash", "dragondance acrobatics knockoff earthquake", PHYSICAL)])),
        ],
    },
    "cynthia": {
        "skin": "champion_cynthia_03a5",
        "names": ("Champion Cynthia", "챔피언 난천"),
        "ace": "garchomp",
        "team": [
            member("spiritomb", "ghost", [s("infiltrator", "careful", "sitrus_berry", "willowisp foulplay painsplit suckerpunch", "hp spd atk")]),
            member("roserade", "poison", [s("naturalcure", "timid", "focus_sash", "spikes toxicspikes sleeppowder sludgebomb", SPECIAL),
                                          s("naturalcure", "timid", "choice_specs", "leafstorm sludgebomb shadowball extrasensory", SPECIAL)]),
            member("milotic", "water", [s("marvelscale", "bold", "flame_orb", "scald recover icebeam haze", "hp def spd")]),
            member("togekiss", "fairy", [s("serenegrace", "calm", "leftovers", "airslash dazzlinggleam thunderwave roost", "hp spd def"),
                                         s("serenegrace", "timid", "choice_scarf", "airslash dazzlinggleam aurasphere trick", SPECIAL)]),
            member("lucario", "steel", [s("justified", "jolly", "life_orb", "swordsdance closecombat meteormash extremespeed", PHYSICAL),
                                        s("innerfocus", "timid", "expert_belt", "nastyplot aurasphere flashcannon vacuumwave", SPECIAL)],
                   [s("justified", "jolly", "mega_showdown:lucarionite", "swordsdance closecombat meteormash extremespeed", PHYSICAL),
                    s("innerfocus", "timid", "mega_showdown:lucarionite", "nastyplot aurasphere flashcannon vacuumwave", SPECIAL)]),
            member("garchomp", "ground", [s("roughskin", "jolly", "lum_berry", "swordsdance earthquake dragonclaw stoneedge", PHYSICAL),
                                          s("roughskin", "jolly", "choice_band", "outrage earthquake stoneedge firefang", PHYSICAL)],
                   [s("roughskin", "jolly", "mega_showdown:garchompite", "swordsdance earthquake dragonclaw stoneedge", PHYSICAL),
                    # Pokemon Champions players' Mega Garchomp Z: Power Gem, Draco Meteor, Flamethrower, Earth Power.
                    s("roughskin", "timid", "mega_showdown:garchompite_z", "dracometeor powergem flamethrower earthpower", SPECIAL)]),
        ],
        "legends": [
            ("giratina", "spiritomb", True, member("giratina", "ghost", [s("levitate", "modest", "mega_showdown:griseous_core", "willowisp hex dracometeor earthpower", "hp spa spd")], form="origin")),
            ("cresselia", "togekiss", False, member("cresselia", "psychic", [s("levitate", "bold", "leftovers", "moonblast psyshock moonlight thunderwave", "hp def spd")])),
            ("heatran", "lucario", False, member("heatran", "steel", [s("flashfire", "modest", "air_balloon", "magmastorm earthpower flashcannon taunt", SPECIAL)])),
            ("azelf", "roserade", False, member("azelf", "psychic", [s("levitate", "timid", "focus_sash", "nastyplot psychic fireblast dazzlinggleam", SPECIAL)])),
        ],
    },
    # From the More Radical Trainers textures (pack "mrt"): Hoenn, Unova, Kalos and Paldea, and N.
    "steven": {
        "skin": "hoennchampion_steven", "pack": "mrt",
        "names": ("Champion Steven", "챔피언 성호"),
        "ace": "metagross",
        "team": [
            member("skarmory", "ghost", [s("sturdy", "impish", "rocky_helmet", "bravebird spikes roost whirlwind", "hp def spd")],
                   [s("sturdy", "impish", "mega_showdown:skarmorite", "bravebird bodypress roost spikes", "hp def spd")]),
            member("claydol", "ground", [s("levitate", "bold", "light_clay", "reflect lightscreen earthpower stealthrock", "hp def spa")]),
            member("aggron", "steel", [s("rockhead", "adamant", "weakness_policy", "heavyslam headsmash earthquake rockpolish", PHYSICAL)],
                   [s("rockhead", "impish", "mega_showdown:aggronite", "heavyslam bodypress earthquake roar", "hp def atk")]),
            member("cradily", "grass", [s("stormdrain", "careful", "leftovers", "gigadrain recover stealthrock toxic", "hp spd def")]),
            member("armaldo", "rock", [s("battlearmor", "adamant", "choice_band", "stoneedge xscissor earthquake aquatail", PHYSICAL)]),
            member("metagross", "steel", [s("clearbody", "jolly", "life_orb", "meteormash zenheadbutt earthquake bulletpunch", PHYSICAL),
                                          s("clearbody", "adamant", "assault_vest", "meteormash zenheadbutt earthquake bulletpunch", "hp atk spd")],
                   [s("clearbody", "jolly", "mega_showdown:metagrossite", "meteormash zenheadbutt earthquake icepunch", PHYSICAL)]),
        ],
        "legends": [
            ("groudon", "aggron", True, member("groudon", "fire", [s("drought", "adamant", "lum_berry", "precipiceblades heatcrash stoneedge swordsdance", "atk hp spe")])),
            ("jirachi", "claydol", False, member("jirachi", "steel", [s("serenegrace", "jolly", "choice_scarf", "ironhead uturn icepunch healingwish", PHYSICAL)])),
            ("deoxys", "cradily", False, member("deoxys", "psychic", [s("pressure", "timid", "focus_sash", "psychoboost superpower icebeam knockoff", SPECIAL)])),
            ("ironthorns", "armaldo", False, member("ironthorns", "rock", [s("quarkdrive", "jolly", "sitrus_berry", "dragondance stoneedge wildcharge earthquake", PHYSICAL)])),
        ],
    },
    "wallace": {
        "skin": "hoennleader_wallace", "pack": "mrt",
        "names": ("Champion Wallace", "챔피언 윤진"),
        "ace": "milotic",
        "team": [
            member("wailord", "water", [s("pressure", "modest", "choice_specs", "waterspout hydropump icebeam hypervoice", "hp spa def")]),
            member("tentacruel", "poison", [s("liquidooze", "timid", "black_sludge", "scald sludgebomb rapidspin toxicspikes", "hp spe def")]),
            member("ludicolo", "grass", [s("swiftswim", "modest", "life_orb", "raindance hydropump gigadrain icebeam", SPECIAL)]),
            member("whiscash", "ground", [s("oblivious", "adamant", "lum_berry", "dragondance waterfall earthquake stoneedge", PHYSICAL)]),
            member("gyarados", "flying", [s("intimidate", "jolly", "heavy_duty_boots", "dragondance waterfall earthquake taunt", PHYSICAL)],
                   [s("intimidate", "adamant", "mega_showdown:gyaradosite", "dragondance waterfall crunch earthquake", PHYSICAL)]),
            member("milotic", "water", [s("marvelscale", "bold", "flame_orb", "scald recover icebeam haze", "hp def spd"),
                                        s("competitive", "modest", "leftovers", "hydropump icebeam recover dragonpulse", "hp spa spd")]),
        ],
        "legends": [
            ("kyogre", "wailord", True, member("kyogre", "water", [s("drizzle", "modest", "choice_scarf", "waterspout originpulse icebeam thunder", SPECIAL)])),
            ("manaphy", "whiscash", False, member("manaphy", "water", [s("hydration", "timid", "expert_belt", "tailglow surf icebeam energyball", SPECIAL)])),
            ("walkingwake", "ludicolo", False, member("walkingwake", "dragon", [s("protosynthesis", "timid", "focus_sash", "hydrosteam dracometeor flamethrower flipturn", SPECIAL)])),
            ("volcanion", "tentacruel", False, member("volcanion", "fire", [s("waterabsorb", "modest", "assault_vest", "steameruption fireblast earthpower sludgebomb", "hp spa spd")])),
        ],
    },
    "alder": {
        "skin": "unovachampion_alder", "pack": "mrt",
        "names": ("Champion Alder", "챔피언 노간주"),
        "ace": "volcarona",
        "team": [
            member("accelgor", "bug", [s("stickyhold", "timid", "focus_sash", "bugbuzz focusblast energyball spikes", SPECIAL)]),
            member("bouffalant", "normal", [s("sapsipper", "adamant", "choice_band", "headcharge earthquake megahorn superpower", PHYSICAL)]),
            member("druddigon", "dragon", [s("roughskin", "adamant", "rocky_helmet", "outrage glare suckerpunch stealthrock", "hp atk def")]),
            member("vanilluxe", "ice", [s("snowwarning", "modest", "light_clay", "blizzard freezedry flashcannon auroraveil", SPECIAL)]),
            member("escavalier", "steel", [s("overcoat", "adamant", "assault_vest", "ironhead megahorn knockoff drillrun", "hp atk spd")]),
            # No member of Alder's team Mega Evolves: in Mega runs Emboar joins with its stone.
            member("emboar", "fire", [], [s("reckless", "jolly", "mega_showdown:emboarite", "flareblitz closecombat wildcharge knockoff", PHYSICAL)]),
            member("volcarona", "water", [s("flamebody", "timid", "heavy_duty_boots", "quiverdance fierydance bugbuzz gigadrain", SPECIAL),
                                          s("flamebody", "modest", "lum_berry", "quiverdance fireblast bugbuzz psychic", SPECIAL)]),
        ],
        "legends": [
            ("kyurem", "vanilluxe", True, member("kyurem", "ice", [s("pressure", "timid", "life_orb", "icebeam freezedry earthpower dracometeor", SPECIAL)])),
            ("slitherwing", "accelgor", False, member("slitherwing", "fighting", [s("protosynthesis", "adamant", "leftovers", "firstimpression closecombat flareblitz uturn", "atk hp spe")])),
            ("keldeo", "bouffalant", False, member("keldeo", "water", [s("justified", "timid", "choice_specs", "hydropump secretsword vacuumwave icywind", SPECIAL)])),
            ("victini", "escavalier", False, member("victini", "fire", [s("victorystar", "jolly", "weakness_policy", "vcreate boltstrike zenheadbutt uturn", PHYSICAL)])),
        ],
    },
    "iris": {
        "skin": "unovachampion_iris", "pack": "mrt",
        "names": ("Champion Iris", "챔피언 아이리스"),
        "ace": "haxorus",
        "team": [
            member("hydreigon", "dark", [s("levitate", "timid", "choice_specs", "dracometeor darkpulse flamethrower uturn", SPECIAL)]),
            member("druddigon", "dragon", [s("roughskin", "adamant", "rocky_helmet", "outrage suckerpunch glare stealthrock", "hp atk def")]),
            member("aggron", "steel", [s("rockhead", "adamant", "weakness_policy", "heavyslam headsmash earthquake rockpolish", PHYSICAL)],
                   [s("rockhead", "impish", "mega_showdown:aggronite", "heavyslam bodypress earthquake roar", "hp def atk")]),
            member("archeops", "flying", [s("defeatist", "jolly", "life_orb", "stoneedge dualwingbeat earthquake uturn", PHYSICAL)]),
            member("lapras", "ice", [s("waterabsorb", "modest", "assault_vest", "freezedry surf thunderbolt icebeam", "hp spa spd")], gmax=True),
            member("haxorus", "dragon", [s("moldbreaker", "jolly", "lum_berry", "dragondance outrage earthquake poisonjab", PHYSICAL),
                                         s("moldbreaker", "adamant", "choice_band", "outrage earthquake closecombat poisonjab", PHYSICAL)]),
        ],
        "legends": [
            ("zekrom", "druddigon", True, member("zekrom", "electric", [s("teravolt", "adamant", "heavy_duty_boots", "boltstrike outrage dragondance crunch", "atk spe hp")])),
            ("ragingbolt", "lapras", False, member("ragingbolt", "electric", [s("protosynthesis", "modest", "leftovers", "thunderbolt dracometeor dragonpulse calmmind", "hp spa spe")])),
            ("ironjugulis", "archeops", False, member("ironjugulis", "dark", [s("quarkdrive", "timid", "choice_scarf", "hurricane darkpulse earthpower uturn", SPECIAL)])),
            ("naganadel", "hydreigon", False, member("naganadel", "poison", [s("beastboost", "timid", "focus_sash", "sludgewave dracometeor fireblast nastyplot", SPECIAL)])),
        ],
    },
    "diantha": {
        "skin": "kaloschampion_diantha", "pack": "mrt",
        "names": ("Champion Diantha", "챔피언 카르네"),
        "ace": "gardevoir",
        "team": [
            member("hawlucha", "fighting", [s("unburden", "jolly", "sitrus_berry", "swordsdance closecombat acrobatics roost", PHYSICAL)],
                   [s("moldbreaker", "jolly", "mega_showdown:hawluchanite", "swordsdance closecombat bravebird roost", PHYSICAL)]),
            member("tyrantrum", "rock", [s("rockhead", "adamant", "choice_band", "headsmash outrage earthquake firefang", PHYSICAL)]),
            member("aurorus", "ice", [s("refrigerate", "modest", "choice_specs", "hypervoice freezedry earthpower ancientpower", SPECIAL)]),
            member("gourgeist", "ghost", [s("frisk", "impish", "leftovers", "willowisp leechseed poltergeist synthesis", "hp def atk")]),
            member("goodra", "dragon", [s("sapsipper", "modest", "assault_vest", "dracometeor fireblast sludgebomb thunderbolt", "hp spa spd")]),
            member("gardevoir", "fairy", [s("trace", "timid", "choice_scarf", "moonblast psychic mysticalfire trick", SPECIAL),
                                          s("trace", "modest", "life_orb", "calmmind moonblast psyshock mysticalfire", "spa hp spe")],
                   [s("trace", "timid", "mega_showdown:gardevoirite", "hypervoice psyshock mysticalfire calmmind", SPECIAL)]),
        ],
        "legends": [
            ("xerneas", "aurorus", True, member("xerneas", "fairy", [s("fairyaura", "modest", "power_herb", "geomancy moonblast focusblast thunderbolt", SPECIAL)])),
            ("diancie", "tyrantrum", False, member("diancie", "rock", [s("clearbody", "modest", "rocky_helmet", "moonblast powergem earthpower stealthrock", "hp spa def")])),
            ("hoopa", "gourgeist", False, member("hoopa", "psychic", [s("magician", "modest", "expert_belt", "psychic shadowball focusblast nastyplot", SPECIAL)])),
            ("ironvaliant", "hawlucha", False, member("ironvaliant", "fairy", [s("quarkdrive", "timid", "focus_sash", "moonblast shadowball psychic calmmind", SPECIAL)])),
        ],
    },
    "geeta": {
        "skin": "paldeachampion_geeta", "pack": "mrt",
        "names": ("Champion Geeta", "챔피언 테사"),
        "ace": "glimmora",
        "team": [
            member("espathra", "psychic", [s("opportunist", "timid", "life_orb", "luminacrash dazzlinggleam shadowball calmmind", SPECIAL)]),
            member("gogoat", "grass", [s("sapsipper", "adamant", "leftovers", "hornleech earthquake bulkup milkdrink", "hp atk def")]),
            member("veluza", "water", [s("sharpness", "adamant", "choice_band", "aquacutter psychocut nightslash aquajet", PHYSICAL)]),
            member("avalugg", "ice", [s("sturdy", "impish", "heavy_duty_boots", "avalanche bodypress recover rapidspin", "hp def atk")]),
            member("kingambit", "dark", [s("supremeoverlord", "adamant", "black_glasses", "kowtowcleave suckerpunch ironhead swordsdance", "atk hp spe")]),
            member("glimmora", "rock", [s("toxicdebris", "timid", "focus_sash", "stealthrock powergem sludgewave earthpower", SPECIAL),
                                        s("toxicdebris", "modest", "air_balloon", "powergem sludgewave earthpower energyball", SPECIAL)],
                   [s("toxicdebris", "timid", "mega_showdown:glimmoranite", "powergem sludgewave earthpower dazzlinggleam", SPECIAL)]),
        ],
        "legends": [
            ("koraidon", "gogoat", True, member("koraidon", "fighting", [s("orichalcumpulse", "jolly", "choice_scarf", "collisioncourse flareblitz outrage uturn", PHYSICAL)])),
            ("ironcrown", "espathra", False, member("ironcrown", "steel", [s("quarkdrive", "modest", "choice_specs", "tachyoncutter psyshock focusblast voltswitch", SPECIAL)])),
            ("ironboulder", "avalugg", False, member("ironboulder", "rock", [s("quarkdrive", "jolly", "lum_berry", "mightycleave zenheadbutt closecombat swordsdance", PHYSICAL)])),
            ("pecharunt", "veluza", False, member("pecharunt", "poison", [s("poisonpuppeteer", "bold", "rocky_helmet", "malignantchain shadowball recover partingshot", "hp def spa")])),
        ],
    },
    "nemona": {
        "skin": "rival_nemona", "pack": "mrt",
        "names": ("Champion Nemona", "챔피언 네모"),
        "ace": "pawmot",
        "team": [
            member("lycanroc", "rock", [s("steadfast", "jolly", "life_orb", "accelerock stoneedge closecombat swordsdance", PHYSICAL)]),
            member("goodra", "dragon", [s("sapsipper", "modest", "assault_vest", "dracometeor fireblast sludgebomb thunderbolt", "hp spa spd")]),
            member("dudunsparce", "ghost", [s("serenegrace", "calm", "leftovers", "boomburst earthpower calmmind roost", "hp spd spa")]),
            member("orthworm", "electric", [s("eartheater", "impish", "rocky_helmet", "shedtail ironhead bodypress stealthrock", "hp def atk")]),
            member("meowscarada", "grass", [s("protean", "jolly", "choice_scarf", "flowertrick knockoff uturn tripleaxel", PHYSICAL)]),
            # No member of Nemona's team Mega Evolves: in Mega runs Baxcalibur joins with its stone.
            member("baxcalibur", "ground", [], [s("thermalexchange", "jolly", "mega_showdown:baxcalibrite", "iciclespear glaiverush earthquake dragondance", PHYSICAL)]),
            member("pawmot", "electric", [s("ironfist", "jolly", "choice_band", "doubleshock closecombat machpunch icepunch", PHYSICAL),
                                          s("voltabsorb", "jolly", "focus_sash", "revivalblessing doubleshock closecombat machpunch", PHYSICAL)]),
        ],
        "legends": [
            ("miraidon", "orthworm", True, member("miraidon", "electric", [s("hadronengine", "timid", "choice_specs", "electrodrift dracometeor voltswitch dazzlinggleam", SPECIAL)])),
            ("ironhands", "lycanroc", False, member("ironhands", "fighting", [s("quarkdrive", "adamant", "sitrus_berry", "drainpunch wildcharge icepunch swordsdance", "hp atk def")])),
            ("zeraora", "goodra", False, member("zeraora", "electric", [s("voltabsorb", "jolly", "expert_belt", "plasmafists closecombat knockoff voltswitch", PHYSICAL)])),
            ("irontreads", "dudunsparce", False, member("irontreads", "ground", [s("quarkdrive", "jolly", "heavy_duty_boots", "earthquake ironhead knockoff rapidspin", PHYSICAL)])),
        ],
    },
    "n": {
        "skin": "plasma_n", "pack": "mrt",
        "names": ("N", "N"),
        "ace": "zoroark",
        "team": [
            member("carracosta", "water", [s("solidrock", "adamant", "white_herb", "shellsmash liquidation stoneedge aquajet", PHYSICAL)]),
            member("vanilluxe", "ice", [s("snowwarning", "modest", "light_clay", "blizzard freezedry flashcannon auroraveil", SPECIAL)]),
            member("archeops", "flying", [s("defeatist", "jolly", "focus_sash", "stoneedge dualwingbeat earthquake uturn", PHYSICAL)]),
            member("klinklang", "steel", [s("clearbody", "adamant", "leftovers", "shiftgear geargrind wildcharge substitute", PHYSICAL)]),
            # N fielded a Scraggy on his way through Unova.
            member("scrafty", "dark", [s("intimidate", "careful", "sitrus_berry", "drainpunch knockoff bulkup rest", "hp spd atk")],
                   [s("intimidate", "adamant", "mega_showdown:scraftinite", "drainpunch knockoff bulkup icepunch", "hp atk spd")]),
            member("zoroark", "dark", [s("illusion", "timid", "choice_specs", "darkpulse flamethrower sludgebomb focusblast", SPECIAL),
                                       s("illusion", "timid", "life_orb", "nastyplot darkpulse flamethrower sludgebomb", SPECIAL)]),
        ],
        "legends": [
            ("reshiram", "vanilluxe", True, member("reshiram", "fire", [s("turboblaze", "modest", "heavy_duty_boots", "blueflare dracometeor earthpower roost", SPECIAL)])),
            ("genesect", "klinklang", False, member("genesect", "bug", [s("download", "jolly", "choice_scarf", "uturn ironhead leechlife blazekick", PHYSICAL)])),
            ("meloetta", "carracosta", False, member("meloetta", "normal", [s("serenegrace", "timid", "expert_belt", "hypervoice psyshock focusblast calmmind", SPECIAL)])),
            ("ironmoth", "archeops", False, member("ironmoth", "fire", [s("quarkdrive", "timid", "assault_vest", "fierydance sludgewave energyball dazzlinggleam", "hp spa spd")])),
        ],
    },
}


def ns(value):
    return value if ":" in value else f"cobblemon:{value}"


def evs(spec):
    first, second, rest = (SHORT[x] for x in spec.split())
    values = {stat: 0 for stat in STATS}
    values[first], values[second], values[rest] = 252, 252, 4
    return values


def sets_of(name, mon, mechanic):
    """Every set of one member for one gimmick; Mega Stone sets only in Mega runs."""
    variants = mon["sets"] + (mon["stones"] if mechanic == "mega" else [])
    out = []
    for index, variant in enumerate(variants, start=1):
        entry = {
            "set_id": f"champion_{name}_{mechanic}_{mon['species']}_{index}",
            "set_tier": CHAMPION_TIER,
            "mechanic_id": mechanic,
            "species_id": f"cobblemon:{mon['species']}",
            "form_id": mon["form"],
            "ability_id": ns(variant["ability"]),
            "nature_id": ns(variant["nature"]),
            "held_item_id": ns(variant["item"]),
            "moves": [ns(m) for m in variant["moves"]],
            "ivs": {stat: 31 for stat in STATS},
            "evs": evs(variant["evs"]),
        }
        if mechanic == "dynamax":
            entry["dmax_level"] = 10
            entry["gmax_factor"] = mon["gmax"]
        if mechanic == "tera":
            entry["tera_type"] = variant["tera"] or mon["tera"]
        out.append(entry)
    return out


def write(path, document):
    """Writes JSON the way the Tower's data files are: two-space indent, CRLF line ends."""
    text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    path.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))


def ace_ids(mechanic, fmt):
    return [f"trainer_{n:03d}" for n in ACES[(mechanic, fmt)]]


def main():
    packs = {
        "rct": zipfile.ZipFile(Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PACK),
        "mrt": zipfile.ZipFile(Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_MRT_PACK),
    }
    lang = {}
    for name, champion in CHAMPIONS.items():
        members = champion["team"] + [legend[3] for legend in champion["legends"]]
        sets = [entry for mechanic in ("mega", "dynamax", "tera") for mon in members for entry in sets_of(name, mon, mechanic)]
        write(TOWER / "pokemon-sets" / f"champion_{name}.json", {"schema_version": 4, "pokemon_sets": sets})
        skin = champion["skin"]
        pack = packs[champion.get("pack", "rct")]
        image = Image.open(io.BytesIO(pack.read(f"assets/rctmod/textures/trainers/single/{skin}.png"))).convert("RGBA")
        trainer = {
            "trainer_id": f"champion_{name}",
            "display_name_key": f"trainer.more_cobblemon_contents.tower_champion_{name}",
            "team_style": "balanced",
            "signature_species_ids": [f"cobblemon:{champion['ace']}"],
            "roster_set_ids": [entry["set_id"] for entry in sets],
            "legend_lines": [{"species_id": f"cobblemon:{species}", "replaces": f"cobblemon:{replaces}", "main": main_line}
                             for species, replaces, main_line, _ in champion["legends"]],
            "skin": f"rctmod:textures/trainers/single/{skin}.png",
            "slim": image.getpixel((54, 20))[3] == 0,
        }
        write(TOWER / "trainers" / f"champion_{name}.json", {"schema_version": 1, "trainers": [trainer]})
        lang[trainer["display_name_key"]] = champion["names"]

    champions = [f"champion_{name}" for name in CHAMPIONS]
    for path in sorted((TOWER / "encounters").glob("*_boss_*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        encounter = document["encounters"][0]
        mechanic, fmt = encounter["mechanic_id"], encounter["format"]
        stage = encounter["stage_ids"][0]
        tier = {"introductory": 2, "practical": 2, "advanced": 3, "pro": 4}[stage]
        encounter["trainer_ids"] = champions
        encounter["pool_id"] = f"{mechanic}_tier_{tier}"
        write(path, document)
    # The Tower Aces join the advanced and pro regulars and leave every other regular encounter.
    for path in sorted((TOWER / "encounters").glob("*_regular_*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        encounter = document["encounters"][0]
        aces = ace_ids(encounter["mechanic_id"], encounter["format"])
        kept = [t for t in encounter["trainer_ids"] if t not in aces]
        if encounter["stage_ids"][0] in ("advanced", "pro"):
            kept += aces
        if kept != encounter["trainer_ids"]:
            encounter["trainer_ids"] = kept
            write(path, document)

    for locale, index in (("en_us", 0), ("ko_kr", 1)):
        path = ROOT / f"more-cobblemon-contents-battle-tower/src/main/resources/assets/more_cobblemon_contents_battle_tower/lang/{locale}.json"
        raw = path.read_bytes().decode("utf-8")
        data = json.loads(raw)
        missing = {key: names[index] for key, names in lang.items() if key not in data}
        if missing:
            newline = "\r\n" if "\r\n" in raw else "\n"
            body = raw.rstrip().rstrip("}").rstrip()
            body += "".join(f",{newline}  {json.dumps(k)}: {json.dumps(v, ensure_ascii=False)}" for k, v in missing.items())
            path.write_bytes((body + newline + "}" + newline).encode("utf-8"))
    print(f"{len(CHAMPIONS)} Champions written")


if __name__ == "__main__":
    main()
