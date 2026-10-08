"""Read species resources and additions from the server's enabled data packs."""
import copy
import gzip
import io
import json
import pathlib
import re
import struct
import zipfile


def enabled_packs(level_dat):
    stream = io.BytesIO(gzip.decompress(pathlib.Path(level_dat).read_bytes()))

    def number(fmt):
        return struct.unpack(">" + fmt, stream.read(struct.calcsize(">" + fmt)))[0]

    def string():
        return stream.read(number("H")).decode("utf-8")

    def value(kind):
        if kind in (1, 2, 3, 4, 5, 6):
            return number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        if kind == 8:
            return string()
        if kind == 9:
            child = number("B")
            return [value(child) for _ in range(number("i"))]
        if kind == 10:
            result = {}
            while True:
                child = number("B")
                if child == 0:
                    return result
                key = string()
                result[key] = value(child)
        if kind in (7, 11, 12):
            length = number("i")
            stream.seek(length * {7: 1, 11: 4, 12: 8}[kind], io.SEEK_CUR)
            return None  # Array contents are irrelevant to DataPacks.
        raise ValueError(f"Unknown NBT tag: {kind}")

    root_kind = number("B")
    string()
    return value(root_kind)["Data"]["DataPacks"]["Enabled"]


def server_sources(server, jars_by_id):
    properties = (server / "server.properties").read_text(encoding="utf-8")
    match = re.search(r"^level-name=(.*)$", properties, re.MULTILINE)
    world = server / (match.group(1).strip() if match else "world")
    sources = []
    for pack in enabled_packs(world / "level.dat"):
        if pack in jars_by_id:
            sources.append((jars_by_id[pack], ""))
        elif pack.startswith("file/"):
            path = world / "datapacks" / pack[5:]
            if not path.exists():
                raise FileNotFoundError(f"Enabled data pack missing: {path}")
            sources.append((path, ""))
        elif ":" in pack:
            namespace, name = pack.split(":", 1)
            if namespace in jars_by_id:
                sources.append((jars_by_id[namespace], f"resourcepacks/{name}/"))
    return sources


def apply_addition(species, addition):
    # Cobblemon SpeciesAdditions.reload appends forms/evolutions and replaces other properties.
    for key, value in addition.items():
        if key == "target":
            continue
        if key in ("forms", "evolutions"):
            species.setdefault(key, []).extend(copy.deepcopy(value))
        else:
            species[key] = copy.deepcopy(value)


def load_species(sources):
    resources = {}
    for path, prefix in sources:
        if path.is_dir():
            entries = [(p.relative_to(path).as_posix(), p.read_bytes()) for p in (path / "data").rglob("*.json")
                       if "/species/" in p.as_posix() or "/species_additions/" in p.as_posix()]
        else:
            with zipfile.ZipFile(path) as archive:
                entries = [(name[len(prefix):], archive.read(name)) for name in archive.namelist()
                           if name.startswith(prefix + "data/") and name.endswith(".json")
                           and ("/species/" in name or "/species_additions/" in name)]
        # A higher-priority pack replaces the same resource location, not all additions to a species.
        for name, data in entries:
            resources[name] = json.loads(data)
    species = {}
    for name, data in resources.items():
        if "/species/" in name:
            parts = name.split("/")
            species[parts[-1][:-5]] = (data, parts[-2])
    for name, addition in sorted(resources.items()):
        if "/species_additions/" not in name:
            continue
        target = addition["target"].split(":")[-1]
        if target not in species:
            raise ValueError(f"Unknown species addition target {target}: {name}")
        apply_addition(species[target][0], addition)
    return species
