"""Convert a locally downloaded Skybound schematic into an offline plaza dimension.

Requires numpy and nbtlib. The creator's map is deliberately not redistributed.
Prepare first; --install copies verified regions into an absent dimension only.
"""
import argparse
import copy
import hashlib
import io
import json
import math
from pathlib import Path
import shutil
import struct
import time
import zlib

import nbtlib as nbt
import numpy as np

SOURCE = "https://www.planetminecraft.com/project/skybound-village-floating-medieval-island-free-map/"
OFFSET = (-60, -50, -62)


def pack(values, bits):
    per_long = 64 // bits
    result = []
    for start in range(0, len(values), per_long):
        value = sum(int(v) << (i * bits) for i, v in enumerate(values[start:start + per_long]))
        result.append(value if value < 1 << 63 else value - (1 << 64))
    return nbt.LongArray(result)


def unpack(values, bits, count):
    per_long = 64 // bits
    mask = (1 << bits) - 1
    return np.array([(int(values[i // per_long]) >> ((i % per_long) * bits)) & mask
                     for i in range(count)], dtype=np.int32)


def block_state(name):
    block, _, properties = name.partition("[")
    state = nbt.Compound({"Name": nbt.String(block)})
    if properties:
        state["Properties"] = nbt.Compound({k: nbt.String(v) for k, v in
                                           (p.split("=") for p in properties[:-1].split(","))})
    return state


def state_name(state):
    result = str(state["Name"])
    if "Properties" in state:
        result += "[" + ",".join(f"{k}={v}" for k, v in state["Properties"].items()) + "]"
    return result


def read_schematic(path):
    schematic = nbt.load(path)["Schematic"]
    if int(schematic["Version"]) != 3 or int(schematic["DataVersion"]) != 3953:
        raise ValueError("Expected the original Sponge v3 / DataVersion 3953 map")
    w, h, length = (int(schematic[key]) for key in ("Width", "Height", "Length"))
    if (w, h, length) != (118, 179, 125):
        raise ValueError("Unexpected Skybound dimensions; placement must be reviewed")
    palette = {int(v): str(k) for k, v in schematic["Blocks"]["Palette"].items()}
    if palette.get(0) != "minecraft:air":
        raise ValueError("Expected palette entry zero to be air")
    values = []
    value = shift = 0
    for byte in schematic["Blocks"]["Data"]:
        byte = int(byte) & 255
        value |= (byte & 127) << shift
        if byte & 128:
            shift += 7
            if shift > 28:
                raise ValueError("Invalid palette varint")
        else:
            values.append(value)
            value = shift = 0
    if shift or len(values) != w * h * length or not set(values) <= palette.keys():
        raise ValueError("Invalid schematic block data")
    if len(schematic.get("Entities", [])):
        raise ValueError("Entity placement is unsupported")
    return schematic, np.array(values, dtype=np.int32).reshape(h, length, w), palette


def make_chunk(cx, cz, blocks, palette, block_entities):
    h, length, w = blocks.shape
    ox, oy, oz = OFFSET
    sections = []
    expected = {}
    for sy in range(-4, 20):
        section = np.zeros((16, 16, 16), dtype=np.int32)
        x0, x1 = max(cx * 16, ox), min(cx * 16 + 16, ox + w)
        z0, z1 = max(cz * 16, oz), min(cz * 16 + 16, oz + length)
        y0, y1 = max(sy * 16, oy), min(sy * 16 + 16, oy + h)
        if x0 < x1 and z0 < z1 and y0 < y1:
            section[y0-sy*16:y1-sy*16, z0-cz*16:z1-cz*16, x0-cx*16:x1-cx*16] = \
                blocks[y0-oy:y1-oy, z0-oz:z1-oz, x0-ox:x1-ox]
        ids, inverse = np.unique(section.flatten(), return_inverse=True)
        states = nbt.Compound({"palette": nbt.List[nbt.Compound]([block_state(palette[int(i)]) for i in ids])})
        if len(ids) > 1:
            states["data"] = pack(inverse, max(4, math.ceil(math.log2(len(ids)))))
        sections.append(nbt.Compound({"Y": nbt.Byte(sy), "block_states": states,
                                     "biomes": nbt.Compound({"palette": nbt.List[nbt.String](["jbro_policy:plaza"])})}))
        expected[sy] = section.flatten()
    chunk = nbt.Compound({
        "DataVersion": nbt.Int(3955), "xPos": nbt.Int(cx), "zPos": nbt.Int(cz), "yPos": nbt.Int(-4),
        "Status": nbt.String("minecraft:full"), "LastUpdate": nbt.Long(0), "InhabitedTime": nbt.Long(0),
        "isLightOn": nbt.Byte(0), "sections": nbt.List[nbt.Compound](sections),
        "block_entities": nbt.List[nbt.Compound](block_entities),
        "block_ticks": nbt.List[nbt.Compound]([]), "fluid_ticks": nbt.List[nbt.Compound]([]),
        "PostProcessing": nbt.List[nbt.List[nbt.Short]]([nbt.List[nbt.Short]([]) for _ in range(24)]),
        "structures": nbt.Compound({"starts": nbt.Compound(), "References": nbt.Compound()}),
    })
    return chunk, expected


def write_region(path, chunks):
    header = bytearray(8192)
    data = bytearray()
    sector = 2
    for (cx, cz), chunk in sorted(chunks.items()):
        raw = io.BytesIO()
        nbt.File(chunk).write(raw)
        compressed = zlib.compress(raw.getvalue())
        payload = struct.pack(">I", len(compressed) + 1) + b"\x02" + compressed
        sectors = (len(payload) + 4095) // 4096
        if sectors > 255:
            raise ValueError("Chunk exceeds standard region capacity")
        index = (cx & 31) + (cz & 31) * 32
        struct.pack_into(">I", header, index * 4, (sector << 8) | sectors)
        struct.pack_into(">I", header, 4096 + index * 4, int(time.time()))
        data.extend(payload + bytes(sectors * 4096 - len(payload)))
        sector += sectors
    path.write_bytes(header + data)


def verify_region(path, expected, palette, expected_entities):
    raw = path.read_bytes()
    count = 0
    for index in range(1024):
        location = struct.unpack_from(">I", raw, index * 4)[0]
        if not location:
            continue
        offset = (location >> 8) * 4096
        size = struct.unpack_from(">I", raw, offset)[0]
        assert raw[offset + 4] == 2
        chunk = nbt.File.parse(io.BytesIO(zlib.decompress(raw[offset + 5:offset + 4 + size])))
        key = (int(chunk["xPos"]), int(chunk["zPos"]))
        assert chunk["block_entities"] == nbt.List[nbt.Compound](expected_entities.get(key, [])), key
        for section in chunk["sections"]:
            states = section["block_states"]
            names = [state_name(s) for s in states["palette"]]
            inv_palette = {name: i for i, name in palette.items()}
            ids = np.array([inv_palette[name] for name in names])
            values = unpack(states["data"], max(4, math.ceil(math.log2(len(ids)))), 4096) \
                if len(ids) > 1 else np.zeros(4096, dtype=np.int32)
            assert np.array_equal(ids[values], expected[key][int(section["Y"])]), key
            assert list(section["biomes"]["palette"]) == ["jbro_policy:plaza"]
        count += 1
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("schematic", type=Path)
    parser.add_argument("world", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    if int(nbt.load(args.world / "level.dat")["Data"]["DataVersion"]) != 3955:
        raise ValueError("Target must be Minecraft Java 1.21.1")
    config = args.world.parent.parent / "config" / "jbro-policy.json"
    if config.exists():
        spawn = json.loads(config.read_text(encoding="utf-8-sig")).get("plaza", {})
        if any(spawn.get(k, v) != v for k, v in {"x": .5, "y": 80., "z": .5}.items()):
            raise ValueError("Non-default plaza spawn; review placement before installation")
    schematic, blocks, palette = read_schematic(args.schematic)
    assert palette[int(blocks[129, 62, 60])] == "minecraft:tuff"
    assert np.all(blocks[130:134, 61:64, 59:62] == 0), "Spawn needs clear space"
    assert np.all(blocks[129, 61:64, 59:62] != 0), "Spawn needs solid footing"
    target = args.world / "dimensions" / "jbro_policy" / "plaza"
    if target.exists():
        raise FileExistsError(f"Refusing to overwrite existing plaza: {target}")
    region = args.output / "region"
    region.mkdir(parents=True, exist_ok=False)
    entities = {}
    for source in schematic["Blocks"]["BlockEntities"]:
        pos = [int(v) + delta for v, delta in zip(source["Pos"], OFFSET)]
        entity = copy.deepcopy(source["Data"])
        entity.update({"id": nbt.String(str(source["Id"])), **{k: nbt.Int(v) for k, v in zip("xyz", pos)}})
        entities.setdefault((pos[0] // 16, pos[2] // 16), []).append(entity)
    regions, expected = {}, {}
    for cx in range(OFFSET[0] // 16, (OFFSET[0] + blocks.shape[2] - 1) // 16 + 1):
        for cz in range(OFFSET[2] // 16, (OFFSET[2] + blocks.shape[1] - 1) // 16 + 1):
            chunk, expected[(cx, cz)] = make_chunk(cx, cz, blocks, palette, entities.get((cx, cz), []))
            regions.setdefault((cx // 32, cz // 32), {})[(cx, cz)] = chunk
    verified = 0
    for (rx, rz), chunks in regions.items():
        path = region / f"r.{rx}.{rz}.mca"
        write_region(path, chunks)
        verified += verify_region(path, expected, palette, entities)
    assert verified == len(expected)
    manifest = {
        "source": SOURCE, "creator": "Itzz_Aspect", "schematic_sha256": hashlib.sha256(args.schematic.read_bytes()).hexdigest(),
        "world": str(args.world.resolve()), "dimension": "jbro_policy:plaza", "translation": OFFSET,
        "spawn": {"x": .5, "y": 80., "z": .5, "yaw": 0., "pitch": 0.},
        "chunks": verified, "non_air_blocks": int(np.count_nonzero(blocks)), "block_entities": sum(map(len, entities.values())),
        "regions": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(region.glob("*.mca"))},
        "verification": "Every block in every generated section was decoded and compared with the source; plaza biome and clear spawn checked.",
        "runtime_verified": False,
    }
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if args.install:
        # Hold the same Windows byte-range lock Minecraft uses for the duration of the copy.
        import msvcrt
        with (args.world / "session.lock").open("r+b") as lock:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            backup = args.output / "backup"
            backup.mkdir()
            shutil.copy2(args.world / "level.dat", backup / "level.dat")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(region, target / "region")
            for p in (target / "region").glob("*.mca"):
                assert hashlib.sha256(p.read_bytes()).hexdigest() == manifest["regions"][p.name]
            msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
        print(f"Installed {verified} chunks into {target}")
    print(json.dumps(manifest, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
