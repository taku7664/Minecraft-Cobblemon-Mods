"""Read species resources and additions from the server's enabled data packs."""
import copy
import gzip
import io
import json
import pathlib
import re
import struct
import zipfile
from dataclasses import dataclass


@dataclass(frozen=True)
class NestedJar:
    path: pathlib.Path
    entries: tuple

    def is_dir(self):
        return False

    def __str__(self):
        return str(self.path) + '!/' + '!/'.join(self.entries)


def open_archive(source):
    if not isinstance(source, NestedJar):
        return zipfile.ZipFile(source)
    data = source.path.read_bytes()
    for entry in source.entries:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            data = archive.read(entry)
    return zipfile.ZipFile(io.BytesIO(data))


def mod_sources(jars):
    """Include Fabric's nested mods, and aliases used by neededInstalledMods."""
    result = {}

    def visit(source):
        with open_archive(source) as archive:
            if 'fabric.mod.json' not in archive.namelist():
                return
            meta = json.loads(archive.read('fabric.mod.json'), strict=False)
            for id_ in [meta['id'], *meta.get('provides', [])]:
                result[id_] = source
            for entry in meta.get('jars', []):
                name = entry['file']
                outer = source.path if isinstance(source, NestedJar) else source
                parents = source.entries if isinstance(source, NestedJar) else ()
                visit(NestedJar(outer, parents + (name,)))

    for jar in sorted(jars):
        visit(jar)
    return result


def minecraft_source(server, explicit=None):
    candidates = [pathlib.Path(explicit)] if explicit else sorted((server/'versions').glob('*/server-*.jar'))
    if not candidates:
        candidates = sorted((server/'.fabric/server').glob('*-server.jar'))
    if len(candidates) > 1:
        raise ValueError('Multiple Minecraft server JARs found; select the running version with --minecraft-jar')
    for path in candidates:
        with zipfile.ZipFile(path) as archive:
            if any(n.startswith('data/minecraft/worldgen/biome/') for n in archive.namelist()):
                return path
            nested = [n for n in archive.namelist() if n.startswith('META-INF/versions/') and n.endswith('.jar')]
            if len(nested) == 1:
                return NestedJar(path, (nested[0],))
    raise FileNotFoundError('Minecraft server biome resources not found; pass --minecraft-jar with the installed server JAR')


def resource_entries(source, prefix=''):
    if source.is_dir():
        return [(p.relative_to(source).as_posix(), p.read_bytes())
                for p in sorted((source / 'data').rglob('*.json'))]
    with open_archive(source) as archive:
        return [(name[len(prefix):], archive.read(name)) for name in sorted(archive.namelist())
                if name.startswith(prefix + 'data/') and name.endswith('.json')]


def load_resources(sources, folders):
    """The last enabled pack wins for an identical resource location."""
    resources = {}
    for source, prefix in sources:
        for name, raw in resource_entries(source, prefix):
            if any('/' + folder + '/' in name for folder in folders):
                resources[name] = json.loads(raw)
    return resources


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
    resources = load_resources(sources, ('species', 'species_additions'))
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
