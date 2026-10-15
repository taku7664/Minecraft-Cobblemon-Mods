"""Build the server TM preset from pinned Champions and Showdown checkouts.

Only factual data files are read; neither upstream project's code is executed.
The output is a reviewable preset, never a write to the running server config.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import unicodedata
import zipfile

sys.dont_write_bytecode = True


def to_id(value):
    value = unicodedata.normalize("NFKD", value).lower()
    return re.sub(r"[^a-z0-9]", "", value)


def ts_blocks(path):
    text = Path(path).read_text(encoding="utf-8")
    blocks = dict(re.findall(r"^\t([a-z0-9]+): \{\n(.*?)^\t\},?$", text, re.M | re.S))
    if not blocks:
        raise ValueError(f"No data entries parsed: {path}")
    return blocks


def parse_pokedex(path):
    fields = ("name", "baseSpecies", "forme", "prevo", "changesFrom", "type")
    return {key: {field: json.loads(match.group(1)) for field in fields
                  if (match := re.search(r'^\t\t' + field + r': ("(?:[^"\\]|\\.)*"),', block, re.M))}
            for key, block in ts_blocks(path).items()}


def parse_learnsets(path):
    return {key: {move: re.findall(r'"([^"]+)"', sources)
                  for move, sources in re.findall(r'^\t\t\t([a-z0-9]+): \[([^\]]*)\],', block, re.M)}
            for key, block in ts_blocks(path).items() if "\t\tlearnset: {" in block}


def parse_champions(text, available_moves=None):
    result = {}
    for block in text.strip().split("\n\n"):
        lines = block.splitlines()
        match = re.fullmatch(r"\d{4} - (.+)", lines[0])
        if not match or "Moves:" not in lines:
            raise ValueError(f"Invalid Champions entry: {lines[0]}")
        key = to_id(match.group(1))
        moves = {to_id(line[2:]) for line in lines[lines.index("Moves:") + 1:] if line.startswith("- ")}
        if available_moves is not None:
            moves.intersection_update(available_moves)
        if not moves or key in result:
            raise ValueError(f"Empty/duplicate Champions entry: {key}")
        result[key] = moves
    return result


def load_champions(repo):
    # personal_dump includes disabled moves. Only waza.available=1 is usable.
    waza = {int(row["id"]): row for row in json.loads((repo / "masterdata/waza.json").read_text(encoding="utf-8-sig"))}
    names = {int(line.split("\t", 1)[0]): to_id(line.split("\t", 1)[1])
             for line in (repo / "parse/move_availability.txt").read_text(encoding="utf-8-sig").splitlines()}
    available = {name for number, name in names.items() if waza[number]["available"] == "1"}
    personal = json.loads((repo / "masterdata/personal.json").read_text(encoding="utf-8-sig"))
    if any(row["is_valid"] != "1" for row in personal):
        raise ValueError("Champions dump contains unreleased species; update the parser before generating")
    dump = (repo / "parse/personal_dump.txt").read_text(encoding="utf-8-sig")
    raw = parse_champions(dump)
    learned = {row["id"]: row["waza"] for row in json.loads((repo / "masterdata/waza_learn.json").read_text(encoding="utf-8-sig"))}
    ordered = sorted(personal, key=lambda row: (int(row["no"]), int(row["fo"])))
    if len(raw) != len(ordered):
        raise ValueError("Champions personal table and text dump have different row counts")
    for row, (key, moves) in zip(ordered, raw.items()):
        expected = {names[int(number)] for number in learned[row["id"]].split(",") if number}
        if moves != expected:
            raise ValueError(f"Champions text dump disagrees with master data: {key}")
    result = parse_champions(dump, available)
    if len(result) != len(personal):
        raise ValueError("Champions personal table and text dump have different row counts")
    return result


def learnset_chain(key, dex, learnsets):
    """Follow Showdown's full-learnset parent rules, retaining regional isolation."""
    seen = set()
    while key and key not in seen:
        seen.add(key)
        meta = dex.get(key, {})
        own = key in learnsets
        if own:
            yield key
        if not own and meta.get("forme"):
            key = to_id(meta.get("changesFrom") or meta["baseSpecies"])
        elif meta.get("prevo"):
            key = to_id(meta["prevo"])
        elif meta.get("changesFrom") and meta.get("baseSpecies") != "Kyurem":
            key = to_id(meta["changesFrom"])
        elif own and meta.get("baseSpecies"):
            ancestor = to_id(meta["baseSpecies"])
            if not dex.get(ancestor, {}).get("prevo"):
                break
            while dex.get(ancestor, {}).get("prevo"):
                ancestor = to_id(dex[ancestor]["prevo"])
            key = ancestor
        else:
            break


def combine_learnsets(learnsets, bdsp):
    combined = dict(learnsets)
    for species, moves in bdsp.items():
        combined[species] = {m: list(s) for m, s in combined.get(species, {}).items()}
        for move, sources in moves.items():
            combined[species].setdefault(move, []).extend(sources)
    return combined


def egg_moves(key, dex, learnsets, bdsp, max_generation=9):
    combined = combine_learnsets(learnsets, bdsp) if bdsp else learnsets
    chain = list(learnset_chain(key, dex, combined))
    generation = 0
    for species in chain:
        generations = [int(source[0]) for sources in combined[species].values() for source in sources
                       if re.match(r"^[1-9][LMTESDC]", source) and int(source[0]) <= max_generation]
        if generations:
            generation = max(generations)
            break
    eggs = {move for species in chain for move, sources in combined[species].items()
            if f"{generation}E" in sources}
    return generation, eggs


def load_server(server):
    # Reuse the repository's enabled-pack/NBT loader, without creating __pycache__.
    sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tools/server-wiki"))
    from pokedex_sources import load_species, server_sources
    jars = {}
    for jar in sorted((server / "mods").glob("*.jar")):
        with zipfile.ZipFile(jar) as archive:
            if "fabric.mod.json" in archive.namelist():
                meta = json.loads(archive.read("fabric.mod.json"), strict=False)
                jars[meta["id"]] = jar
    sources = server_sources(server, jars)
    species = {key: value[0] for key, value in load_species(sources).items()}
    tms = {}
    for path, prefix in sources:
        if path == jars.get("cobblemon_custom_species"):
            continue  # Regeneration must not count this preset's own TMs as upstream TMs.
        if path.is_dir():
            entries = [(p.relative_to(path).as_posix(), p.read_bytes())
                       for p in (path / "data").rglob("*.json") if "/tms/" in p.as_posix()]
        else:
            with zipfile.ZipFile(path) as archive:
                entries = [(name[len(prefix):], archive.read(name)) for name in archive.namelist()
                           if name.startswith(prefix + "data/") and "/tms/" in name and name.endswith(".json")]
        for name, data in entries:
            tms[name] = json.loads(data)
    with zipfile.ZipFile(jars["cobblemon"]) as archive:
        with zipfile.ZipFile(io.BytesIO(archive.read("data/cobblemon/showdown.zip"))) as showdown:
            known_moves = set()
            for name in ("data/moves.js.map", "data/mods/cobblemon/moves.js.map"):
                sourcemap = json.loads(showdown.read(name))
                for text in sourcemap["sourcesContent"]:
                    known_moves.update(re.findall(r'^\t([a-z0-9]+): \{', text, re.M))
    if len(known_moves) < 900:
        raise ValueError("Could not verify Cobblemon's bundled move database")
    return species, {data["moveName"] for data in tms.values()}, known_moves, [
        item for item in sources if item[0] != jars.get("cobblemon_custom_species")]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def generate(server, champions_repo, showdown_repo, output):
    print("Reading enabled server data packs", file=sys.stderr, flush=True)
    species, existing_tms, known_moves, packs = load_server(server)
    dex = parse_pokedex(showdown_repo / "data/pokedex.ts")
    move_meta = parse_pokedex(showdown_repo / "data/moves.ts")
    learnsets = parse_learnsets(showdown_repo / "data/learnsets.ts")
    bdsp = parse_learnsets(showdown_repo / "data/mods/gen8bdsp/learnsets.ts")
    combined = combine_learnsets(learnsets, bdsp)
    egg_cache = {}

    def fallback(key, max_generation=9):
        cache_key = key, max_generation
        if cache_key not in egg_cache:
            egg_cache[cache_key] = egg_moves(key, dex, combined, {}, max_generation)
        return egg_cache[cache_key]

    print("Matching species, forms and mainline generations", file=sys.stderr, flush=True)
    champions = load_champions(champions_repo)
    independent = parse_learnsets(showdown_repo / "data/mods/champions/learnsets.ts")
    comparisons = 0
    for key, moves in champions.items():
        if key in independent:
            if moves != set(independent[key]):
                raise ValueError(f"Champions sources disagree: {key}")
            comparisons += 1
    # Names used in the game dump differ from Showdown/Cobblemon's form names.
    aliases = {"gourgeistjumbo": "gourgeistsuper", "mausholdfamilyoffour": "mausholdfour"}
    champions = {aliases.get(key, key): moves for key, moves in champions.items()}
    target_ids = {key for key in species}
    target_ids.update(key + to_id(form["name"]) for key, data in species.items() for form in data.get("forms", []))
    collapsed = []
    for key in sorted(champions.keys() - target_ids):
        # These patterns are aspects of Cobblemon's one base Vivillon form.
        if key.startswith("vivillon") and champions[key] == champions.get("vivillon"):
            collapsed.append(key)
        else:
            raise ValueError(f"Champions entry has no verified server target: {key}")
    overrides, audit, all_moves = [], [], set()
    for sid, data in sorted(species.items()):
        if sid not in dex:
            raise ValueError(f"No mainline species metadata: {sid}")
        base_tm = {m[3:] for m in data["moves"] if m.startswith("tm:")}
        base_desired = champions.get(sid)
        if base_desired is None:
            _, base_desired = fallback(sid)
        base_add = base_desired - base_tm
        forms = {"base": data}
        # Runtime catalog also identifies forms by this normalized name.
        forms.update({to_id(f["name"]): f for f in data.get("forms", [])})
        for form_id, form in sorted(forms.items()):
            target_id = sid if form_id == "base" else sid + form_id
            baseline = form.get("moves", data["moves"])
            baseline_tm = {m[3:] for m in baseline if m.startswith("tm:")}
            desired = champions.get(target_id)
            source = "champions"
            generation = None
            if desired is None:
                source = "mainline-egg"
                # Cosmetic and regional-evolution bias forms without a distinct
                # Showdown entry share their server species' mainline learnset.
                reference = target_id if target_id in dex else sid
                # Gigantamax forms stopped at Sword/Shield even when their base
                # species returned in Scarlet/Violet. Keep their eggs at Gen 8.
                generation, desired = fallback(reference, 8 if "gmax" in form_id else 9)
            additions = desired - baseline_tm
            # Prevent a base-form expansion leaking into an inherited alternate form.
            removals = (base_add - baseline_tm - desired) if form_id != "base" and "moves" not in form else set()
            operations = {}
            if additions:
                operations["add"] = ["tm:" + move for move in sorted(additions)]
            if removals:
                operations["remove"] = ["tm:" + move for move in sorted(removals)]
            if operations:
                overrides.append({"species": "cobblemon:" + sid, "form": form_id, "moves": operations})
            audit.append({"species": sid, "form": form_id, "source": source, "generation": generation,
                          "source_moves": sorted(desired), "added": sorted(additions),
                          "blocked_inherited": sorted(removals)})
            all_moves.update(desired)
    unknown_moves = sorted((all_moves - move_meta.keys()) | (all_moves - known_moves))
    if unknown_moves:
        raise ValueError(f"Unknown moves in mainline metadata: {unknown_moves}")
    missing_tms = sorted(all_moves - existing_tms)
    new_tms = {move: {"moveName": move, "obtainMethods": [{"variant": "cobblemon:default"}],
                      "recipe": [{"count": 1, "item": "cobblemon:" + move_meta[move]["type"].lower() + "_gem"}],
                      "type": move_meta[move]["type"].lower()} for move in missing_tms}
    preset = {"schema": 1, "overrides": overrides}
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "species-overrides.json", preset)
    write_json(output / "coverage.json", audit)
    for move, tm in new_tms.items():
        write_json(output / "tms" / (move + ".json"), tm)
    manifest = {
        "policy": "Champions full learnsets; otherwise egg moves from the last mainline generation, including pre-evolutions; no Legends data",
        "sources": {name: {"repository": url, "commit": subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()}
                    for name, path, url in [("champions", champions_repo, "https://github.com/projectpokemon/champout"),
                                            ("mainline", showdown_repo, "https://github.com/smogon/pokemon-showdown")]},
        "server_data_inputs": [{"file": path.name, "prefix": prefix, "sha256": sha256(path)} for path, prefix in packs if path.is_file()],
        "species": len(species), "targets": len(audit), "overrides": len(overrides),
        "independently_compared_champions_learnsets": comparisons,
        "champions_targets": sum(row["source"] == "champions" for row in audit),
        "added_tm_entries": sum(len(row["added"]) for row in audit),
        "new_tm_definitions": len(new_tms), "collapsed_cosmetic_champions_entries": collapsed,
        "fallback_generations": {str(gen): sum(row["generation"] == gen for row in audit)
                                 for gen in sorted({row["generation"] for row in audit if row["generation"] is not None})},
        "preset_sha256": sha256(output / "species-overrides.json")
    }
    write_json(output / "sources.json", manifest)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("server", "champions-repo", "showdown-repo", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(generate(args.server, args.champions_repo, args.showdown_repo, args.output), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
