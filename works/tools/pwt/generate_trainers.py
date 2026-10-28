"""Writes the PWT trainers (`data/more_cobblemon_contents_pwt/pwt/trainers/*.json`) from the table below.

Every entry is a level-50 Pokemon with full IVs and one of the EV spreads in SPREADS. A pool keeps one Pokemon per
species and one of each held item, since a battle draws three of it. No Legendary or Mythical Pokemon: the
tournament runs the original World Tournament's no-legend rule on both sides. Run after editing the table:

    python works/tools/pwt/generate_trainers.py           # write the files
    python works/tools/pwt/generate_trainers.py --check   # fail when a file is out of date
"""
import json
import pathlib
import sys

MODULE = pathlib.Path(__file__).resolve().parents[2] / "mods" / "more-cobblemon-contents-pwt"
OUT = MODULE / "src/main/resources/data/more_cobblemon_contents_pwt/pwt/trainers"
OWN = "more_cobblemon_contents_pwt:textures/npcs/pwt/"
RCT = "rctmod:textures/trainers/single/"

STATS = ["hp", "attack", "defence", "special_attack", "special_defence", "speed"]
SPREADS = {
    "atk_spe": {"attack": 252, "speed": 252, "hp": 4},
    "spa_spe": {"special_attack": 252, "speed": 252, "hp": 4},
    "hp_atk": {"hp": 252, "attack": 252, "special_defence": 4},
    "hp_spa": {"hp": 252, "special_attack": 252, "special_defence": 4},
    "hp_def": {"hp": 252, "defence": 252, "special_defence": 4},
    "hp_spd": {"hp": 252, "special_defence": 252, "defence": 4},
}

# (species, moves, ability, nature, held item or None, spread)
TRAINERS = {
    "red": dict(generation=1, skin=RCT + "player_red_0202.png", ace="pikachu", pokemon=[
        ("pikachu", "thunderbolt,voltswitch,grassknot,surf", "static", "timid", "light_ball", "spa_spe"),
        ("venusaur", "gigadrain,sludgebomb,sleeppowder,earthpower", "overgrow", "modest", "black_sludge", "hp_spa"),
        ("charizard", "flamethrower,airslash,focusblast,dragonpulse", "blaze", "timid", "life_orb", "spa_spe"),
        ("blastoise", "hydropump,icebeam,aurasphere,darkpulse", "torrent", "modest", "sitrus_berry", "hp_spa"),
        ("snorlax", "bodyslam,earthquake,crunch,curse", "thickfat", "adamant", "leftovers", "hp_atk"),
        ("lapras", "freezedry,hydropump,thunderbolt,icebeam", "waterabsorb", "modest", "assault_vest", "hp_spa"),
    ]),
    "blue": dict(generation=1, skin=RCT + "champion_terry_01b6.png", ace="pidgeot", pokemon=[
        ("pidgeot", "hurricane,heatwave,uturn,roost", "keeneye", "timid", "focus_sash", "spa_spe"),
        ("alakazam", "psychic,shadowball,focusblast,dazzlinggleam", "magicguard", "timid", "life_orb", "spa_spe"),
        ("rhyperior", "stoneedge,earthquake,megahorn,icepunch", "solidrock", "adamant", "weakness_policy", "hp_atk"),
        ("arcanine", "flareblitz,extremespeed,wildcharge,closecombat", "intimidate", "adamant", "choice_band", "atk_spe"),
        ("gyarados", "waterfall,icefang,earthquake,dragondance", "intimidate", "jolly", "lum_berry", "atk_spe"),
        ("exeggutor", "gigadrain,psychic,sleeppowder,sludgebomb", "harvest", "modest", "sitrus_berry", "hp_spa"),
    ]),
    "giovanni": dict(generation=1, skin=RCT + "boss_giovanni_0045.png", ace="nidoking", pokemon=[
        ("nidoking", "earthpower,sludgewave,icebeam,thunderbolt", "sheerforce", "modest", "life_orb", "spa_spe"),
        ("nidoqueen", "earthpower,sludgewave,icebeam,flamethrower", "sheerforce", "modest", "leftovers", "hp_spa"),
        ("rhyperior", "stoneedge,earthquake,megahorn,icepunch", "solidrock", "adamant", "choice_band", "hp_atk"),
        ("dugtrio", "earthquake,stoneedge,suckerpunch,rockslide", "arenatrap", "jolly", "focus_sash", "atk_spe"),
        ("persian", "fakeout,uturn,nightslash,hypnosis", "technician", "jolly", "choice_scarf", "atk_spe"),
        ("kangaskhan", "fakeout,doubleedge,suckerpunch,earthquake", "scrappy", "adamant", "sitrus_berry", "atk_spe"),
    ]),
    "lance": dict(generation=2, skin=RCT + "champion_lance_0027.png", ace="dragonite", pokemon=[
        ("dragonite", "dragondance,extremespeed,earthquake,firepunch", "multiscale", "adamant", "lum_berry", "atk_spe"),
        ("gyarados", "waterfall,icefang,bounce,dragondance", "intimidate", "adamant", "sitrus_berry", "atk_spe"),
        ("aerodactyl", "stoneedge,earthquake,crunch,ironhead", "pressure", "jolly", "focus_sash", "atk_spe"),
        ("charizard", "flamethrower,airslash,dragonpulse,focusblast", "blaze", "timid", "choice_specs", "spa_spe"),
        ("kingdra", "dracometeor,surf,icebeam,flashcannon", "sniper", "modest", "life_orb", "spa_spe"),
        ("salamence", "dracometeor,fireblast,hydropump,earthquake", "intimidate", "naive", "assault_vest", "spa_spe"),
    ]),
    "steven": dict(generation=3, skin=OWN + "steven.png", ace="metagross", pokemon=[
        ("metagross", "meteormash,zenheadbutt,earthquake,bulletpunch", "clearbody", "adamant", "life_orb", "hp_atk"),
        ("skarmory", "bravebird,ironhead,roost,spikes", "sturdy", "impish", "rocky_helmet", "hp_def"),
        ("aggron", "headsmash,heavyslam,earthquake,icepunch", "rockhead", "adamant", "choice_band", "hp_atk"),
        ("claydol", "earthpower,psychic,rapidspin,stealthrock", "levitate", "modest", "leftovers", "hp_spa"),
        ("cradily", "gigadrain,ancientpower,recover,toxic", "stormdrain", "calm", "sitrus_berry", "hp_spd"),
        ("armaldo", "xscissor,stoneedge,earthquake,swordsdance", "battlearmor", "adamant", "weakness_policy", "hp_atk"),
    ]),
    "wallace": dict(generation=3, skin=OWN + "wallace.png", ace="milotic", pokemon=[
        ("milotic", "scald,icebeam,recover,haze", "marvelscale", "bold", "leftovers", "hp_def"),
        ("wailord", "waterspout,hydropump,icebeam,hypervoice", "pressure", "modest", "choice_specs", "hp_spa"),
        ("tentacruel", "sludgebomb,surf,icebeam,rapidspin", "clearbody", "timid", "black_sludge", "spa_spe"),
        ("ludicolo", "hydropump,gigadrain,icebeam,fakeout", "swiftswim", "modest", "life_orb", "spa_spe"),
        ("whiscash", "earthquake,waterfall,stoneedge,dragondance", "oblivious", "adamant", "sitrus_berry", "atk_spe"),
        ("gyarados", "waterfall,earthquake,icefang,dragondance", "moxie", "jolly", "lum_berry", "atk_spe"),
    ]),
    "cynthia": dict(generation=4, skin=RCT + "champion_cynthia_03a5.png", ace="garchomp", pokemon=[
        ("garchomp", "earthquake,outrage,stoneedge,firefang", "roughskin", "jolly", "choice_scarf", "atk_spe"),
        ("spiritomb", "shadowball,darkpulse,willowisp,painsplit", "pressure", "calm", "leftovers", "hp_spd"),
        ("lucario", "aurasphere,flashcannon,darkpulse,vacuumwave", "innerfocus", "timid", "focus_sash", "spa_spe"),
        ("milotic", "scald,icebeam,recover,toxic", "marvelscale", "bold", "sitrus_berry", "hp_def"),
        ("togekiss", "airslash,dazzlinggleam,aurasphere,roost", "serenegrace", "timid", "expert_belt", "spa_spe"),
        ("roserade", "leafstorm,sludgebomb,shadowball,sleeppowder", "naturalcure", "timid", "life_orb", "spa_spe"),
    ]),
    "cyrus": dict(generation=4, skin=RCT + "team_galactic_boss_cyrus_041a.png", ace="weavile", pokemon=[
        ("weavile", "icepunch,knockoff,iceshard,lowkick", "pressure", "jolly", "choice_band", "atk_spe"),
        ("honchkrow", "bravebird,suckerpunch,nightslash,heatwave", "superluck", "adamant", "life_orb", "hp_atk"),
        ("crobat", "bravebird,crosspoison,uturn,roost", "innerfocus", "jolly", "black_sludge", "atk_spe"),
        ("gyarados", "waterfall,icefang,earthquake,dragondance", "intimidate", "adamant", "lum_berry", "atk_spe"),
        ("houndoom", "darkpulse,flamethrower,sludgebomb,nastyplot", "flashfire", "timid", "focus_sash", "spa_spe"),
    ]),
    "n": dict(generation=5, skin=OWN + "n.png", ace="zoroark", pokemon=[
        ("zoroark", "nastyplot,darkpulse,flamethrower,focusblast", "illusion", "timid", "life_orb", "spa_spe"),
        ("carracosta", "shellsmash,waterfall,stoneedge,aquajet", "sturdy", "adamant", "white_herb", "hp_atk"),
        ("vanilluxe", "blizzard,freezedry,flashcannon,icebeam", "snowwarning", "modest", "choice_specs", "spa_spe"),
        ("archeops", "acrobatics,stoneedge,earthquake,uturn", "defeatist", "jolly", None, "atk_spe"),
        ("klinklang", "shiftgear,geargrind,wildcharge,substitute", "clearbody", "adamant", "leftovers", "atk_spe"),
    ]),
    "alder": dict(generation=5, skin=OWN + "alder.png", ace="volcarona", pokemon=[
        ("volcarona", "quiverdance,fierydance,bugbuzz,gigadrain", "flamebody", "timid", "leftovers", "spa_spe"),
        ("accelgor", "bugbuzz,focusblast,energyball,uturn", "stickyhold", "timid", "choice_specs", "spa_spe"),
        ("bouffalant", "headcharge,earthquake,stoneedge,megahorn", "sapsipper", "adamant", "choice_band", "hp_atk"),
        ("druddigon", "outrage,superpower,suckerpunch,firepunch", "roughskin", "adamant", "life_orb", "hp_atk"),
        ("vanilluxe", "blizzard,flashcannon,freezedry,autotomize", "snowwarning", "modest", "focus_sash", "spa_spe"),
        ("escavalier", "megahorn,ironhead,drillrun,knockoff", "shellarmor", "adamant", "assault_vest", "hp_atk"),
    ]),
    "iris": dict(generation=5, skin=OWN + "iris.png", ace="haxorus", pokemon=[
        ("haxorus", "dragondance,outrage,earthquake,poisonjab", "moldbreaker", "jolly", "lum_berry", "atk_spe"),
        ("hydreigon", "dracometeor,darkpulse,flamethrower,focusblast", "levitate", "timid", "choice_specs", "spa_spe"),
        ("druddigon", "outrage,earthquake,suckerpunch,stealthrock", "roughskin", "adamant", "rocky_helmet", "hp_atk"),
        ("aggron", "headsmash,heavyslam,earthquake,icepunch", "rockhead", "adamant", "choice_band", "hp_atk"),
        ("archeops", "acrobatics,stoneedge,earthquake,uturn", "defeatist", "jolly", None, "atk_spe"),
        ("lapras", "hydropump,freezedry,thunderbolt,icebeam", "waterabsorb", "modest", "sitrus_berry", "hp_spa"),
    ]),
    "diantha": dict(generation=6, skin=OWN + "diantha.png", ace="gardevoir", pokemon=[
        ("gardevoir", "moonblast,psychic,mysticalfire,calmmind", "trace", "timid", "leftovers", "spa_spe"),
        ("hawlucha", "acrobatics,closecombat,swordsdance,roost", "unburden", "jolly", None, "atk_spe"),
        ("tyrantrum", "headsmash,dragonclaw,earthquake,dragondance", "strongjaw", "adamant", "life_orb", "atk_spe"),
        ("aurorus", "freezedry,earthpower,thunderbolt,blizzard", "refrigerate", "modest", "choice_specs", "hp_spa"),
        ("gourgeist", "willowisp,seedbomb,shadowsneak,leechseed", "frisk", "impish", "rocky_helmet", "hp_def"),
        ("goodra", "dracometeor,fireblast,sludgebomb,thunderbolt", "sapsipper", "modest", "assault_vest", "hp_spa"),
    ]),
    "kukui": dict(generation=7, skin=OWN + "kukui.png", ace="lycanroc", pokemon=[
        ("lycanroc", "accelerock,stoneedge,closecombat,swordsdance", "steadfast", "jolly", "focus_sash", "atk_spe"),
        ("incineroar", "fakeout,flareblitz,darkestlariat,uturn", "intimidate", "adamant", "assault_vest", "hp_atk"),
        ("braviary", "bravebird,closecombat,rockslide,tailwind", "sheerforce", "jolly", "choice_scarf", "atk_spe"),
        ("magnezone", "thunderbolt,flashcannon,voltswitch,triattack", "sturdy", "modest", "choice_specs", "hp_spa"),
        ("snorlax", "bodyslam,earthquake,crunch,curse", "thickfat", "adamant", "leftovers", "hp_atk"),
        ("primarina", "moonblast,sparklingaria,icebeam,psychic", "torrent", "modest", "life_orb", "hp_spa"),
    ]),
    "leon": dict(generation=8, skin=OWN + "leon.png", ace="charizard", pokemon=[
        ("charizard", "flamethrower,airslash,focusblast,dragonpulse", "blaze", "timid", "life_orb", "spa_spe"),
        ("aegislash", "shadowball,flashcannon,shadowsneak,kingsshield", "stancechange", "quiet", "leftovers", "hp_spa"),
        ("dragapult", "dracometeor,shadowball,flamethrower,uturn", "clearbody", "timid", "choice_specs", "spa_spe"),
        ("haxorus", "dragondance,dragonclaw,earthquake,poisonjab", "moldbreaker", "jolly", "lum_berry", "atk_spe"),
        ("rhyperior", "stoneedge,earthquake,megahorn,icepunch", "solidrock", "adamant", "weakness_policy", "hp_atk"),
        ("inteleon", "hydropump,icebeam,darkpulse,uturn", "sniper", "timid", "focus_sash", "spa_spe"),
    ]),
    "raihan": dict(generation=8, skin=OWN + "raihan.png", slim=True, ace="duraludon", pokemon=[
        ("duraludon", "dracometeor,flashcannon,thunderbolt,bodypress", "stalwart", "modest", "choice_specs", "spa_spe"),
        ("flygon", "earthquake,dragonclaw,uturn,firepunch", "levitate", "jolly", "choice_scarf", "atk_spe"),
        ("goodra", "dracometeor,fireblast,sludgebomb,thunderbolt", "sapsipper", "modest", "assault_vest", "hp_spa"),
        ("turtonator", "shellsmash,flamethrower,dragonpulse,earthquake", "shellarmor", "modest", "white_herb", "hp_spa"),
        ("sandaconda", "earthquake,coil,glare,stoneedge", "sandspit", "adamant", "leftovers", "hp_atk"),
        ("torkoal", "eruption,solarbeam,earthpower,willowisp", "drought", "quiet", "sitrus_berry", "hp_spa"),
    ]),
    "geeta": dict(generation=9, skin=OWN + "geeta.png", slim=True, ace="kingambit", pokemon=[
        ("kingambit", "kowtowcleave,ironhead,suckerpunch,swordsdance", "supremeoverlord", "adamant", "lum_berry", "hp_atk"),
        ("glimmora", "powergem,sludgewave,earthpower,stealthrock", "toxicdebris", "modest", "focus_sash", "spa_spe"),
        ("espathra", "luminacrash,dazzlinggleam,shadowball,calmmind", "speedboost", "timid", "leftovers", "spa_spe"),
        ("gogoat", "hornleech,earthquake,bulkup,milkdrink", "sapsipper", "impish", "sitrus_berry", "hp_def"),
        ("veluza", "aquacutter,psychocut,aquajet,nightslash", "sharpness", "adamant", "life_orb", "atk_spe"),
        ("avalugg", "avalanche,bodypress,recover,iciclecrash", "sturdy", "impish", "rocky_helmet", "hp_def"),
    ]),
    "nemona": dict(generation=9, skin=OWN + "nemona.png", slim=True, ace="pawmot", pokemon=[
        ("pawmot", "doubleshock,closecombat,machpunch,icepunch", "voltabsorb", "jolly", "life_orb", "atk_spe"),
        ("lycanroc", "accelerock,stoneedge,closecombat,crunch", "steadfast", "jolly", "focus_sash", "atk_spe"),
        ("goodra", "dracometeor,fireblast,sludgebomb,thunderbolt", "sapsipper", "modest", "assault_vest", "hp_spa"),
        ("dudunsparce", "boomburst,earthpower,roost,coil", "serenegrace", "modest", "leftovers", "hp_spa"),
        ("orthworm", "ironhead,bodypress,earthquake,coil", "eartheater", "impish", "rocky_helmet", "hp_def"),
        ("meowscarada", "flowertrick,knockoff,uturn,playrough", "protean", "jolly", "choice_band", "atk_spe"),
    ]),
}


def line(species, moves, ability, nature, item, spread):
    evs = SPREADS[spread]
    parts = [species, "level=50", f"moves={moves}", f"ability={ability}", f"nature={nature}"]
    if item:
        parts.append(f"held_item=cobblemon:{item}")
    for stat in STATS:
        parts += [f"{stat}_iv=31", f"{stat}_ev={evs.get(stat, 0)}"]
    return " ".join(parts)


def render(trainer_id, data):
    body = {
        "schema_version": 1,
        "name_key": f"trainer.more_cobblemon_contents_pwt.{trainer_id}",
        "generation": data["generation"],
        "skin": data["skin"],
        "slim": data.get("slim", False),
        "ace": data["ace"],
        "pokemon": [line(*entry) for entry in data["pokemon"]],
    }
    return json.dumps(body, ensure_ascii=False, indent=2) + "\n"


def main():
    check = "--check" in sys.argv
    stale = []
    OUT.mkdir(parents=True, exist_ok=True)
    expected = {f"{trainer_id}.json": render(trainer_id, data) for trainer_id, data in TRAINERS.items()}
    for name, text in expected.items():
        path = OUT / name
        if path.exists() and path.read_text(encoding="utf-8") == text:
            continue
        stale.append(name)
        if not check:
            path.write_text(text, encoding="utf-8", newline="\n")
    for path in OUT.glob("*.json"):
        if path.name not in expected:
            stale.append(path.name)
            if not check:
                path.unlink()
    if check and stale:
        print("Out of date: " + ", ".join(sorted(stale)))
        return 1
    print(f"{len(expected)} trainers" + ("" if check else f", {len(stale)} written"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
