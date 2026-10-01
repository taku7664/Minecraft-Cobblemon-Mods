#!/usr/bin/env python3
"""Build the real-tournament test sets (src/test/resources/betterai/tournament) from Showdown replays.

Each game keeps the three Pokemon both players brought, in the order they came in, and who won. Everything a
replay showed (moves, items, abilities) is the player's own; the rest is the most used choice of that month's ladder
usage statistics. A side that sent in only two has the third filled in from the team preview, the teammate the ladder
pairs most often with the two shown.

    python tools/build_tournament_sets.py --cobblemon-jar <cobblemon-fabric jar> --cache <dir>
"""

from __future__ import annotations

import argparse
import io
import json
import re
import urllib.request
import zipfile
from pathlib import Path


SOURCE = "Smogon BSS Open VIII Round 2 ([Gen 9] BSS Reg I)"
STATS_URL = "https://www.smogon.com/stats/2026-02/chaos/gen9bssregi-1500.json"
REPLAYS = [
    "gen9bssregi-2536255305-jwrlkir9whgxfupuxturc6w4ka5opkhpw",
    "gen9bssregi-2536259704-fcfxmmvmvhdlv2dw6jyews6vc06aafnpw",
    "gen9bssregi-2537505103-8k7k3vxosmucfas6d91gt9yy3p759p4pw",
    "gen9bssregi-2537508073-4h77u36xmsehjw550fx3dupgr94rv18pw",
    "gen9bssregi-2537511694-w0g6ysegjagmki2srfqn6n4353c7rxtpw",
    "gen9bssregi-2538165058",
    "gen9bssregi-2538167126",
    "gen9bssregi-2538169175",
    "gen9bssregi-2539082356-7eo0ysprtn5rwyby8t3xd04ini2vebypw",
    "gen9bssregi-2539085882-gd02eiafkucq7xuy24o40rf7udar3aepw",
    "gen9bssregi-2539250706-wm6wrb81gp53ctd0eje31aw5x7a46vopw",
    "gen9bssregi-2539253186-de57qsdm91lsq8xlwm6bs04fyug16qbpw",
    "gen9bssregi-2539254104-952aoweyvq73vbhh535xp6jq3km7rn7pw",
    "gen9bssregi-2539254760-a9bi832v6rbvtwqso1s4shh3qplja3bpw",
    "gen9bssregi-2539255359-ptjv3jhsefr4k5fo58xj9rophduk1ifpw",
    "gen9bssregi-2539257082-ou32ho5l4dya57mie9nrpo366jemntspw",
    "gen9bssregi-2539257784-gt0m97780laq0w4d1cfp0k1whk3nggkpw",
    "gen9bssregi-2539259086-srt82djs26rgek9crihbx00gey3j2r1pw",
    "gen9bssregi-2539260000-ufahtcb77oa02ccoe5f5qu3d4l8hfyrpw",
    "gen9bssregi-2539272203-fsptx3foym0tpvp8g5vxfqi971wmrh4pw",
    "gen9bssregi-2539274424-ay9e88wkeffvr9jaw2u2xu5yupjmuexpw",
    "gen9bssregi-2539562430-wgm6lfytra3x373aa21xlykb1d1lbgkpw",
    "gen9bssregi-2539564591-hwddy1wkc3hwf3u6wkfvwoucqkdhkavpw",
    "gen9bssregi-2539566719-agsuumtn0qwjoapfjxttqmv167jydf6pw",
    "gen9bssregi-2539769818-ch8r10bby9wfn25cu2l9kxgjl27tuqopw",
    "gen9bssregi-2539771877-pufns5mntxxtyqhijlwiflowixbix7spw",
    "smogtours-gen9bssregi-908500",
    "smogtours-gen9bssregi-908504",
    "smogtours-gen9bssregi-909373",
    "smogtours-gen9bssregi-909377",
]
# Showdown names whose Cobblemon species carries the form as its own entry.
FORMS = {
    "Calyrex-Ice": ("calyrex", "Ice"), "Calyrex-Shadow": ("calyrex", "Shadow"), "Kyurem-Black": ("kyurem", "Black"),
    "Landorus-Therian": ("landorus", "Therian"), "Necrozma-Dusk-Mane": ("necrozma", "Dusk-Mane"),
    "Ogerpon-Hearthflame": ("ogerpon", "Hearthflame"), "Ursaluna-Bloodmoon": ("ursaluna", "Bloodmoon"),
    "Urshifu-Rapid-Strike": ("urshifu", "Rapid-Strike"), "Zacian-Crowned": ("zacian", "Crowned"),
    "Zamazenta-Crowned": ("zamazenta", "Crowned"),
}
STATS = ["hp", "attack", "defense", "special_attack", "special_defense", "speed"]
# A move another move calls is still the caller's own move slot only when that caller is Sleep Talk.
OWN_CALLERS = {"[from] move: Sleep Talk"}


def canonical(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def cached(url: str, path: Path) -> bytes:
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        path.write_bytes(urllib.request.urlopen(request, timeout=60).read())
    return path.read_bytes()


def most_used(weights: dict[str, float], exclude=(), count: int = 1) -> list[str]:
    ordered = sorted(weights.items(), key=lambda item: -item[1])
    return [key for key, _ in ordered if key not in exclude and key not in ("", "nothing")][:count]


def move_categories(cobblemon_jar: Path) -> dict[str, str]:
    showdown = zipfile.ZipFile(cobblemon_jar).read("data/cobblemon/showdown.zip")
    source = zipfile.ZipFile(io.BytesIO(showdown)).read("data/moves.js").decode()
    return dict(re.findall(r'\n  (\w+): \{[\s\S]*?\n    category: "(\w+)"', source))


def observe(log: list[str]):
    """What one replay showed: players, winner, team previews, who came in in which order, and each one's revealed set."""
    players, preview, order, nick, seen = {}, {"p1": [], "p2": []}, {"p1": [], "p2": []}, {}, {"p1": {}, "p2": {}}
    winner = None

    def who(token: str):
        if ": " not in token or token[:2] not in seen:
            return None, None
        return token[:2], nick.get((token[:2], token.split(": ", 1)[1]))

    for line in log:
        part = line.split("|")
        if len(part) < 2:
            continue
        kind = part[1]
        tail = part[4:]
        if kind == "player" and len(part) > 3 and part[3]:
            players[part[2]] = part[3]
        elif kind == "poke":
            preview[part[2]].append(part[3].split(",")[0])
        elif kind == "win":
            winner = part[2]
        elif kind in ("switch", "drag", "replace"):
            side, species = part[2][:2], part[3].split(",")[0]
            nick[(side, part[2].split(": ", 1)[1])] = species
            if species not in order[side]:
                order[side].append(species)
            seen[side].setdefault(species, {"moves": [], "item": None, "ability": None})
        if kind == "move":
            side, species = who(part[2])
            called = [x for x in tail if x.startswith("[from]")]
            if species and all(x in OWN_CALLERS for x in called):
                move = canonical(part[3])
                if move != "struggle" and move not in seen[side][species]["moves"]:
                    seen[side][species]["moves"].append(move)
        if kind in ("-item", "-enditem"):
            side, species = who(part[2])
            if species and not seen[side][species]["item"] and not any(x.startswith("[from] move") for x in tail):
                seen[side][species]["item"] = canonical(part[3])
        if kind == "-ability":
            side, species = who(part[2])
            if species:
                seen[side][species]["ability"] = seen[side][species]["ability"] or canonical(part[3])
        for token in part[3:]:
            found = re.match(r"\[from\] (item|ability): (.+)", token)
            if not found:
                continue
            # The holder of the item or ability is the [of] Pokemon when there is one: Rocky Helmet hurts the
            # attacker (the line's own Pokemon), and the helmet is the defender's.
            holder = next((x[5:] for x in part[3:] if x.startswith("[of] ")), part[2])
            side, species = who(holder)
            if species:
                seen[side][species][found.group(1)] = seen[side][species][found.group(1)] or canonical(found.group(2))
    return players, winner, preview, order, seen


def build(cobblemon_jar: Path, cache: Path) -> dict:
    chaos = json.loads(cached(STATS_URL, cache / "chaos.json"))["data"]
    category = move_categories(cobblemon_jar)
    games, sets = [], []
    for replay in REPLAYS:
        data = json.loads(cached(f"https://replay.pokemonshowdown.com/{replay}.json", cache / "replays" / f"{replay}.json"))
        players, winner, preview, order, seen = observe(data["log"].split("\n"))
        name = re.sub(r"-[a-z0-9]{20,}pw$", "", replay)
        team = {}
        guessed: set[tuple[str, str]] = set()
        for side in ("p1", "p2"):
            members = list(order[side])
            while len(members) < 3:
                rest = [x for x in preview[side] if x not in members and x in chaos]
                pick = max(rest, key=lambda x: sum(chaos[m]["Teammates"].get(x, 0) for m in members))
                members.append(pick)
                guessed.add((side, pick))
                seen[side][pick] = {"moves": [], "item": None, "ability": None}
            # Revealed items go first, so a filled-in item never takes one a teammate was seen holding.
            items = {seen[side][x]["item"] for x in members if seen[side][x]["item"]}
            ids = []
            for species_name in members:
                usage, shown = chaos[species_name], seen[side][species_name]
                moves = ["transform"] if species_name == "Ditto" else shown["moves"][:4]
                for move in most_used(usage["Moves"], exclude=moves + ["terablast"], count=8):
                    if len(moves) >= 4:
                        break
                    moves.append(move)
                status = [m for m in moves if category.get(m) == "Status"]

                def fits(item: str) -> bool:
                    if item in items:
                        return False
                    if item.startswith("choice") and status:
                        return False
                    return item != "powerherb" or bool(set(moves) & {"meteorbeam", "electroshock", "solarbeam"})

                item = shown["item"] or next(x for x in most_used(usage["Items"], count=20) if fits(x))
                items.add(item)
                ability = shown["ability"] or most_used(usage["Abilities"])[0]
                nature, spread = most_used(usage["Spreads"])[0].split(":")
                species, form = FORMS.get(species_name, (canonical(species_name), None))
                set_id = f"{name}-{side}-{canonical(species_name)}"
                entry = {
                    "set_id": set_id, "species_id": "cobblemon:" + species, "ability_id": "cobblemon:" + ability,
                    "held_item_id": "cobblemon:" + item, "nature_id": "cobblemon:" + nature.lower(),
                    "moves": ["cobblemon:" + m for m in moves], "evs": dict(zip(STATS, map(int, spread.split("/")))),
                    "revealed_moves": len(shown["moves"][:4]), "revealed_item": bool(shown["item"]),
                    "revealed_ability": bool(shown["ability"]), "guessed_member": (side, species_name) in guessed,
                }
                if form:
                    entry["form_id"] = form
                sets.append(entry)
                ids.append(set_id)
            team[side] = ids
        games.append({
            "name": name, "p1": team["p1"], "p2": team["p2"],
            "winner": "p1" if winner == players.get("p1") else "p2", "complete": not guessed,
        })
    return {
        "schema_version": 4,
        "source": f"{SOURCE}, replays and 2026-02 gen9bssregi-1500 usage stats",
        "rental_sets": sets,
        "games": games,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--cobblemon-jar", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True, help="where the replays and usage stats are kept")
    parser.add_argument("--out", type=Path,
                        default=Path(__file__).resolve().parents[1] / "src/test/resources/betterai/tournament/bss-open-viii-round2.json")
    args = parser.parse_args()
    root = build(args.cobblemon_jar, args.cache)
    args.out.write_text(json.dumps(root, indent=1) + "\n", encoding="utf-8")
    sets = root["rental_sets"]
    print(f"{len(root['games'])} games, {sum(g['complete'] for g in root['games'])} complete, {len(sets)} sets; revealed "
          f"{sum(s['revealed_moves'] for s in sets)}/{4 * len(sets)} moves, {sum(s['revealed_item'] for s in sets)} items, "
          f"{sum(s['revealed_ability'] for s in sets)} abilities")


if __name__ == "__main__":
    main()
