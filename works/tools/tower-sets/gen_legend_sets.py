"""Gives every regular Battle Tower pool, tier 1 to 4 of each gimmick, the same choice of restricted Legendaries.

With legendaries allowed a regular trainer answers each restricted Legendary the challenger brings with one drawn from
its own pool. The Mega pools had only Mewtwo, and tier 1 of the Dynamax and Tera pools only Calyrex, so every trainer
there brought the same one. This fills the pools from two sources:

- the restricted Legendaries the Champions field (Groudon, Kyogre, Rayquaza, ...), trained down to each tier's IVs and
  EVs with the Champion's moves, ability, nature, item and Tera type;
- the regular Dialga, Giratina, Eternatus and Calyrex sets, copied from the Tera pools to the Mega pools and from tier 2
  down to tier 1 where tier 1 lacked them.

Mega Mewtwo's special set holds Mewtwonite Y, the stone that matches it, and a physical set holds Mewtwonite X.

Tier 1 has IVs of 15 and no EVs, tier 2 IVs of 20 and 252 in the set's main stat, tiers 3 and 4 the spreads of
gen_upper_tiers.py. Running it again rewrites the same sets.

    python tools/tower-sets/gen_legend_sets.py
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gen_upper_tiers import STATS, TIERS, TOWER, spread  # noqa: E402

SETS = TOWER / "pokemon-sets"
MECHANICS = ("mega", "dynamax", "tera")
CHAMPION_LEGENDS = (
    "mewtwo", "groudon", "kyogre", "rayquaza", "reshiram", "zekrom", "kyurem", "xerneas", "koraidon", "miraidon",
)
REGULAR_LEGENDS = ("dialga", "giratina", "eternatus", "calyrex")
IVS = {1: 15, 2: 20, **{tier: iv for tier, (iv, _) in TIERS.items()}}


def read(species):
    path = SETS / f"cobblemon_{species}.json"
    if not path.exists():
        return path, {"schema_version": 4, "pokemon_sets": []}
    return path, json.loads(path.read_bytes().decode("utf-8"))


def write(path, document):
    text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    if path.exists() and "\r\n" in path.read_bytes().decode("utf-8"):
        text = text.replace("\n", "\r\n")
    path.write_bytes(text.encode("utf-8"))


def trained(source, tier, set_id):
    """[source] at [tier]'s IVs and EVs, keeping its main stat."""
    derived = dict(source)
    derived["set_id"] = set_id
    derived["set_tier"] = tier
    derived["ivs"] = {stat: IVS[tier] for stat in STATS}
    evs = source["evs"]
    main = next((s for s in ("attack", "special_attack") if evs.get(s, 0) >= 252), None) \
        or max(STATS, key=lambda s: evs.get(s, 0))
    tier2 = {stat: 252 if stat == main else 0 for stat in STATS}
    if tier == 1:
        derived["evs"] = {stat: 0 for stat in STATS}
    elif tier == 2:
        derived["evs"] = tier2
    else:
        derived["evs"] = spread(tier2, source["nature_id"].split(":")[1], TIERS[tier][1])
    return derived


def for_mechanic(source, mechanic, extras):
    """[source] with [mechanic]'s fields: none for Mega, a Dynamax level for Dynamax, a Tera type for Tera."""
    entry = {k: v for k, v in source.items() if k not in ("dmax_level", "gmax_factor", "tera_type")}
    entry["mechanic_id"] = mechanic
    if mechanic == "dynamax":
        entry["dmax_level"] = extras.get("dmax_level", 10)
        entry["gmax_factor"] = extras.get("gmax_factor", False)
    elif mechanic == "tera":
        entry["tera_type"] = extras["tera_type"]
    return entry


def replace(document, sets):
    ids = {s["set_id"] for s in sets}
    kept = [s for s in document["pokemon_sets"] if s["set_id"] not in ids]
    document["pokemon_sets"] = kept + sets
    document["pokemon_sets"].sort(key=lambda s: (s["set_tier"], MECHANICS.index(s["mechanic_id"]), s["set_id"]))


def champion_sets():
    """The Champions' restricted Legendary sets by species, Tera type included."""
    found = {}
    for path in sorted(SETS.glob("champion_*.json")):
        for entry in json.loads(path.read_bytes().decode("utf-8"))["pokemon_sets"]:
            species = entry["species_id"].split(":")[1]
            if species in CHAMPION_LEGENDS and entry["mechanic_id"] == "tera":
                found.setdefault(species, entry)
    return found


def main():
    written = 0
    champions = champion_sets()
    for species in CHAMPION_LEGENDS:
        source = champions[species]
        path, document = read(species)
        regular_mechanics = {s["mechanic_id"] for s in document["pokemon_sets"]}
        sets = []
        for mechanic in MECHANICS:
            if mechanic in regular_mechanics:
                continue
            base = for_mechanic(source, mechanic, source)
            base["form_id"] = None
            for tier in IVS:
                sets.append(trained(base, tier, f"generated_{mechanic}_tier{tier}_{species}"))
        replace(document, sets)
        write(path, document)
        written += len(sets)

    for species in REGULAR_LEGENDS:
        path, document = read(species)
        by_id = {s["set_id"]: s for s in document["pokemon_sets"]}
        sets = []
        for mechanic in ("dynamax", "tera"):
            tier1 = f"generated_{mechanic}_tier1_{species}"
            if tier1 not in by_id:
                sets.append(trained(by_id[f"generated_{mechanic}_tier2_{species}"], 1, tier1))
        tera = {s["set_tier"]: s for s in document["pokemon_sets"] + sets if s["mechanic_id"] == "tera"}
        for tier in IVS:
            mega = for_mechanic(tera[tier], "mega", {})
            mega["set_id"] = f"generated_mega_tier{tier}_{species}"
            sets.append(mega)
        replace(document, sets)
        write(path, document)
        written += len(sets)

    path, document = read("mewtwo")
    sets = []
    for tier in IVS:
        special = next(s for s in document["pokemon_sets"] if s["set_id"] == f"generated_mega_tier{tier}_mewtwo")
        sets.append({**special, "held_item_id": "mega_showdown:mewtwonite_y"})
        physical = {
            **special,
            "nature_id": "cobblemon:jolly",
            "held_item_id": "mega_showdown:mewtwonite_x",
            "moves": ["cobblemon:bulkup", "cobblemon:drainpunch", "cobblemon:zenheadbutt", "cobblemon:icepunch"],
            "evs": {stat: 0 for stat in STATS},
        }
        sets.append(trained({**physical, "evs": {**physical["evs"], "attack": 252}}, tier,
                            f"generated_mega_tier{tier}_mewtwo_x"))
    replace(document, sets)
    write(path, document)
    written += len(sets)
    print(f"wrote {written} sets")


if __name__ == "__main__":
    main()
