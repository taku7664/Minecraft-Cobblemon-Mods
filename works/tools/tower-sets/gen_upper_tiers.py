"""Adds the Battle Tower's tier 3 and tier 4 sets, derived from its tier 2 sets.

Tier 2 (from the 6th win) has IVs of 20 and one 252 EV stat. Tier 3 (the advanced stage, wins 11 to 20) and tier 4
(the pro stage, from the 21st win) keep each set's species, moves, ability, item, nature and gimmick fields and
train it further, so EVs climb 0, 252, 384, 508 through the stages: tier 3 has IVs of 25 and a second stat at 128,
tier 4 IVs of 31 and the second stat at 252, plus 4 elsewhere. The second stat is picked from what the set does and
its nature. Running it again rewrites the upper tiers from the current tier 2 sets.

    python tools/tower-sets/gen_upper_tiers.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOWER = ROOT / "mods/more-cobblemon-contents-battle-tower/src/main/resources/data/more_cobblemon_contents/mcc-battle-tower"
TIERS = {3: (25, 128), 4: (31, 252)}  # tier: (IVs, EVs in the second stat)
STATS = ["hp", "attack", "defense", "special_attack", "special_defense", "speed"]

SLOW = {"brave", "relaxed", "quiet", "sassy"}
LOWERS_SPECIAL_ATTACK = {"adamant", "impish", "jolly", "careful"}
LOWERS_ATTACK = {"modest", "bold", "timid", "calm"}
RAISES_DEFENSE = {"bold", "impish", "relaxed", "lax"}
RAISES_SPECIAL_DEFENSE = {"calm", "careful", "sassy", "gentle"}


def spread(tier2_evs, nature, second_evs):
    """A 252/second/4 spread that keeps tier 2's 252 stat."""
    main = max(STATS, key=lambda s: tier2_evs.get(s, 0))
    if main in ("attack", "special_attack"):
        second = "hp" if nature in SLOW else "speed"
    elif main == "speed":
        second = "special_attack" if nature in LOWERS_ATTACK else "attack"
    elif main == "hp":
        second = "special_defense" if nature in RAISES_SPECIAL_DEFENSE else "defense"
    else:
        second = "hp"
    rest = "hp" if "hp" not in (main, second) else ("defense" if "defense" not in (main, second) else "special_defense")
    evs = {stat: 0 for stat in STATS}
    evs[main], evs[second], evs[rest] = 252, second_evs, 4
    return evs


def main():
    written = 0
    for path in sorted((TOWER / "pokemon-sets").glob("cobblemon_*.json")):
        raw = path.read_bytes().decode("utf-8")
        document = json.loads(raw)
        base = [s for s in document["pokemon_sets"] if s["set_tier"] <= 2]
        upper = []
        for tier, (iv, second_evs) in TIERS.items():
            for source in (s for s in base if s["set_tier"] == 2):
                nature = source["nature_id"].split(":")[1]
                derived = dict(source)
                derived["set_id"] = source["set_id"].replace("_tier2_", f"_tier{tier}_")
                assert derived["set_id"] != source["set_id"], source["set_id"]
                derived["set_tier"] = tier
                derived["ivs"] = {stat: iv for stat in STATS}
                derived["evs"] = spread(source["evs"], nature, second_evs)
                upper.append(derived)
        document["pokemon_sets"] = base + upper
        text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
        if "\r\n" in raw:
            text = text.replace("\n", "\r\n")
        path.write_bytes(text.encode("utf-8"))
        written += len(upper)

    for mechanic in ("mega", "dynamax", "tera"):
        for tier in TIERS:
            pool = {"schema_version": 1, "pools": [{"pool_id": f"{mechanic}_tier_{tier}", "mechanic_id": mechanic, "set_tiers": [tier]}]}
            write(TOWER / "pools" / f"{mechanic}_tier_{tier}.json", pool)

    # The regular encounter that covered the practical, advanced and pro stages on tier 2 keeps the practical stage;
    # the advanced and pro stages get one each on tier 3 and tier 4, with the same trainers.
    split = 0
    for path in sorted((TOWER / "encounters").glob("*_regular_*.json")):
        if path.stem.endswith(("_advanced", "_pro")):
            continue
        document = json.loads(path.read_text(encoding="utf-8"))
        encounter = document["encounters"][0]
        if encounter["pool_id"].endswith("_tier_1"):
            continue
        encounter["stage_ids"] = ["practical"]
        write(path, document)
        mechanic = encounter["mechanic_id"]
        for stage, tier in (("advanced", 3), ("pro", 4)):
            upper = dict(encounter, encounter_id=f"{encounter['encounter_id']}_{stage}", stage_ids=[stage],
                         pool_id=f"{mechanic}_tier_{tier}")
            write(path.with_name(f"{path.stem}_{stage}.json"), {"schema_version": document["schema_version"], "encounters": [upper]})
        split += 1
    print(f"{written} upper tier sets written, {split} regular encounters split by stage")


def write(path, document):
    """Writes JSON the way the Tower's data files are: two-space indent, CRLF line ends."""
    text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    path.write_bytes(text.replace("\n", "\r\n").encode("utf-8"))


if __name__ == "__main__":
    main()
