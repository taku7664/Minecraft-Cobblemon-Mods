"""Reduce every official battle BGM to 60% of its current decoded amplitude.

Effect sounds, field music, and user-provided extension packs are not touched.
The report makes repeat runs idempotent and records the exact input/output set.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile


GAIN = 0.6
EXPECTED_TRACKS = 61
FILTER_PREFIX = "asetpts=N/SR/TB,astats=metadata=0:reset=0,volume="


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_vorbis(path):
    serials = set()
    packet = bytearray()
    packet_complete = False
    final_granule = 0
    with path.open("rb") as source:
        while header := source.read(27):
            if len(header) != 27 or header[:5] != b"OggS\0":
                raise ValueError(f"Invalid Ogg page: {path}")
            granule = struct.unpack_from("<Q", header, 6)[0]
            serials.add(struct.unpack_from("<I", header, 14)[0])
            laces = source.read(header[26])
            body = source.read(sum(laces))
            if len(laces) != header[26] or len(body) != sum(laces):
                raise ValueError(f"Truncated Ogg page: {path}")
            if not packet_complete:
                offset = 0
                for size in laces:
                    packet.extend(body[offset:offset + size])
                    offset += size
                    if size < 255:
                        packet_complete = True
                        break
            if granule != 0xFFFFFFFFFFFFFFFF:
                final_granule = granule
    if len(serials) != 1 or packet[:7] != b"\x01vorbis" or len(packet) < 30:
        raise ValueError(f"Expected one Vorbis logical stream: {path}")
    channels = packet[11]
    sample_rate = struct.unpack_from("<I", packet, 12)[0]
    if channels not in (1, 2) or sample_rate != 44100 or not final_granule:
        raise ValueError(f"Unexpected music format: {path}")
    return channels, final_granule / sample_rate


def run(ffmpeg, *args):
    result = subprocess.run(
        [str(ffmpeg), "-nostdin", "-hide_banner", "-nostats", "-loglevel", "info",
         "-xerror", "-err_detect", "explode", *map(str, args)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode:
        raise RuntimeError(result.stderr[-4000:])
    return result.stderr


def rms_db(log):
    values = re.findall(r"RMS level dB:\s*([-+]?(?:\d+(?:\.\d+)?|inf))", log)
    if not values:
        raise ValueError("FFmpeg did not report decoded RMS level")
    return float(values[-1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", type=Path, required=True)
    args = parser.parse_args()

    module = Path(__file__).resolve().parents[1]
    sound_dir = module / "resource-pack/src/assets/better_cobblemon_music/sounds/music/battle"
    report_path = module / "resource-pack/battle-volume-2026-10-09.json"
    files = sorted(sound_dir.rglob("*.ogg"))
    titles = json.loads((module / "resource-pack/catalog-layout.json").read_text(encoding="utf-8"))["trackTitles"]
    titled = {key.removeprefix("better_cobblemon_music:battle/") + ".ogg"
              for key in titles if key.startswith("better_cobblemon_music:battle/")}
    actual = {path.relative_to(sound_dir).as_posix() for path in files}
    if len(files) != EXPECTED_TRACKS or actual != titled:
        raise ValueError(f"Battle BGM inventory changed: missing={titled - actual}, extra={actual - titled}")

    if report_path.exists():
        previous = json.loads(report_path.read_text(encoding="utf-8"))
        if previous.get("multiplier") != GAIN or {track["target"] for track in previous["tracks"]} != actual:
            raise ValueError("Existing battle volume report has another scope")
        for track in previous["tracks"]:
            if sha256(sound_dir / track["target"]) != track["afterSha256"]:
                raise ValueError(f"Battle volume report does not match {track['target']}")
        print(f"Already scaled and verified {len(files)} tracks; no second gain pass")
        return

    legendary = json.loads((module / "resource-pack/legendary-gain-2026-10-04.json").read_text(encoding="utf-8"))
    legendary_hashes = {"legendary/" + entry["target"]: entry["afterSha256"] for entry in legendary["tracks"]}
    if len(legendary_hashes) != 45:
        raise ValueError("Prior legendary gain report is incomplete")
    for name, expected in legendary_hashes.items():
        if sha256(sound_dir / name) != expected:
            raise ValueError(f"Prior legendary gain is not present: {name}")
    if sha256(sound_dir / "pvp/pokemon_champions_arena_battle.ogg") != \
            "88702ffa56a2a05e7c152232c0cd03724b713b278263ca5bd7c4076900f9fc50":
        raise ValueError("Approved Champions source has changed")

    (module / "build").mkdir(exist_ok=True)
    report = {"schemaVersion": 1, "multiplier": GAIN,
              "filter": FILTER_PREFIX + "<per-track inputGain>",
              "codec": "Ogg Vorbis", "quality": 4, "tracks": []}
    total_before = total_after = 0
    with tempfile.TemporaryDirectory(prefix="battle-volume-", dir=module / "build") as temporary:
        stage = Path(temporary)
        for index, source in enumerate(files, start=1):
            name = source.relative_to(sound_dir).as_posix()
            channels, duration = inspect_vorbis(source)
            output = stage / name
            output.parent.mkdir(parents=True, exist_ok=True)
            input_gain = GAIN
            lower_gain = upper_gain = None
            for attempt in range(7):
                before_log = run(args.ffmpeg, "-i", source, "-map", "0:a:0", "-vn", "-sn", "-dn",
                                 "-map_metadata", "-1", "-map_chapters", "-1", "-af",
                                 FILTER_PREFIX + f"{input_gain:.8f}",
                                 "-c:a", "libvorbis", "-q:a", "4", "-ac", str(channels),
                                 "-ar", "44100", "-f", "ogg", "-y", output)
                after_channels, after_duration = inspect_vorbis(output)
                if after_channels != channels or abs(after_duration - duration) > 0.25:
                    raise ValueError(f"Channels or duration changed: {name}")
                after_log = run(args.ffmpeg, "-i", output, "-map", "0:a:0",
                                "-af", "astats=metadata=0:reset=0", "-f", "null", "-")
                before_rms, after_rms = rms_db(before_log), rms_db(after_log)
                error_db = (after_rms - before_rms) - 20 * math.log10(GAIN)
                if abs(error_db) <= 0.25:
                    break
                if error_db > 0:
                    upper_gain = input_gain
                else:
                    lower_gain = input_gain
                input_gain = ((lower_gain + upper_gain) / 2
                              if lower_gain is not None and upper_gain is not None
                              else input_gain * 10 ** (-error_db / 20))
                if not 0 < input_gain < 1:
                    raise ValueError(f"Invalid corrective gain for {name}: {input_gain}")
            else:
                raise ValueError(f"Gain verification failed: {name}: {before_rms} -> {after_rms}")
            total_before += source.stat().st_size
            total_after += output.stat().st_size
            report["tracks"].append({"target": name, "beforeSha256": sha256(source),
                                     "afterSha256": sha256(output),
                                     "beforeRmsDb": round(before_rms, 3),
                                     "afterRmsDb": round(after_rms, 3),
                                     "inputGain": round(input_gain, 8),
                                     "channels": channels, "sampleRate": 44100,
                                     "durationSeconds": round(after_duration, 3)})
            if index % 5 == 0 or index == len(files):
                print(f"Validated {index}/{len(files)} tracks", flush=True)
        report["inputBytes"] = total_before
        report["outputBytes"] = total_after
        for track in report["tracks"]:
            shutil.copyfile(stage / track["target"], sound_dir / track["target"])
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Scaled {len(files)} battle BGMs: {total_before} -> {total_after} bytes")


if __name__ == "__main__":
    main()
