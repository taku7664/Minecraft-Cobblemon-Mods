"""Trains every Battle Tower set like a competitive set: IVs of 31 and a full 252/252/4 spread, from the first win on.

The Tower's difficulty climbs through its opponents' level (one more every 5 wins), its sets and its AI stages, not
through half-trained Pokemon. Tier 2 and tier 3 sets take the spread of their tier 4 counterpart (the same set,
derived from tier 2 by gen_upper_tiers.py); tier 1 sets, which have their own species, take the spread their nature
asks for. Champions are already trained. Running it again changes nothing.

    python tools/tower-sets/competitive_spreads.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SETS = ROOT / "more-cobblemon-contents-battle-tower/src/main/resources/data/more_cobblemon_contents/mcc-battle-tower/pokemon-sets"
STATS = ["hp", "attack", "defense", "special_attack", "special_defense", "speed"]

# Tier 1 natures: what each raises, with the stats that go with it.
BY_NATURE = {
    "adamant": ("attack", "speed", "hp"),
    "jolly": ("attack", "speed", "hp"),
    "modest": ("special_attack", "speed", "hp"),
    "timid": ("special_attack", "speed", "hp"),
    "bold": ("hp", "defense", "special_defense"),
    "impish": ("hp", "defense", "special_defense"),
    "careful": ("hp", "special_defense", "defense"),
    "calm": ("hp", "special_defense", "defense"),
}


def spread(main, second, rest):
    evs = {stat: 0 for stat in STATS}
    evs[main], evs[second], evs[rest] = 252, 252, 4
    return evs


def main():
    changed = 0
    for path in sorted(SETS.glob("cobblemon_*.json")):
        raw = path.read_bytes().decode("utf-8")
        document = json.loads(raw)
        by_id = {s["set_id"]: s for s in document["pokemon_sets"]}
        for pokemon_set in document["pokemon_sets"]:
            tier = pokemon_set["set_tier"]
            if tier >= 4:
                continue
            if tier == 1:
                nature = pokemon_set["nature_id"].split(":")[1]
                evs = spread(*BY_NATURE[nature])
            else:
                evs = dict(by_id[pokemon_set["set_id"].replace(f"_tier{tier}_", "_tier4_")]["evs"])
            ivs = {stat: 31 for stat in STATS}
            if pokemon_set["evs"] != evs or pokemon_set["ivs"] != ivs:
                pokemon_set["evs"], pokemon_set["ivs"] = evs, ivs
                changed += 1
        text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
        if "\r\n" in raw:
            text = text.replace("\n", "\r\n")
        if text != raw:
            path.write_bytes(text.encode("utf-8"))
    print(f"{changed} sets trained")


if __name__ == "__main__":
    main()
