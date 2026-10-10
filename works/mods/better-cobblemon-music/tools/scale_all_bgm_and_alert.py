"""Halve every official BGM and the low-HP alert, leaving hit effects untouched.

Run once per approved source inventory. The report prevents accidental double gain.
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


GAIN = 0.5
EXPECTED_BGM = 88
REPORT_NAME = "all-bgm-alert-volume-2026-10-11.json"


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
    if channels not in (1, 2) or not 8000 <= sample_rate <= 192000 or not final_granule:
        raise ValueError(f"Unexpected audio format: {path}")
    return channels, sample_rate, final_granule / sample_rate


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
    parser.add_argument("--scratch", type=Path, required=True)
    args = parser.parse_args()
    if not args.ffmpeg.is_file():
        raise ValueError(f"FFmpeg not found: {args.ffmpeg}")
    args.scratch.mkdir(parents=True, exist_ok=True)

    module = Path(__file__).resolve().parents[1]
    sound_root = module / "resource-pack/src/assets/better_cobblemon_music/sounds"
    music_root = sound_root / "music"
    titles = json.loads((module / "resource-pack/catalog-layout.json").read_text(encoding="utf-8"))["trackTitles"]
    expected = {"music/" + key.removeprefix("better_cobblemon_music:") + ".ogg"
                for key in titles if key.startswith("better_cobblemon_music:")}
    actual = {path.relative_to(sound_root).as_posix() for path in music_root.rglob("*.ogg")}
    if len(expected) != EXPECTED_BGM or actual != expected:
        raise ValueError(f"BGM inventory mismatch: missing={expected - actual}, extra={actual - expected}")
    target_names = sorted(expected | {"battle/low_hp/alert.ogg"})
    report_path = module / "resource-pack" / REPORT_NAME
    if report_path.exists():
        previous = json.loads(report_path.read_text(encoding="utf-8"))
        reported = {track["target"]: track for track in previous["tracks"]}
        if previous.get("multiplier") != GAIN or set(reported) != set(target_names):
            raise ValueError("Existing volume report has another scope")
        for name in target_names:
            if sha256(sound_root / name) != reported[name]["afterSha256"]:
                raise ValueError(f"Existing volume report does not match {name}")
        print(f"Already scaled and verified {len(target_names)} files; no second gain pass")
        return

    report = {"schemaVersion": 1, "multiplier": GAIN,
              "scope": "official BGM and low-HP alert; hit effects excluded",
              "codec": "Ogg Vorbis", "quality": 4, "tracks": []}
    before_bytes = after_bytes = 0
    with tempfile.TemporaryDirectory(prefix="bcm-volume-", dir=args.scratch) as temporary:
        stage = Path(temporary)
        for index, name in enumerate(target_names, start=1):
            source = sound_root / name
            channels, sample_rate, duration = inspect_vorbis(source)
            output = stage / name
            output.parent.mkdir(parents=True, exist_ok=True)
            input_gain = GAIN
            for attempt in range(5):
                before_log = run(args.ffmpeg, "-i", source, "-map", "0:a:0", "-vn", "-sn", "-dn",
                                 "-map_metadata", "-1", "-map_chapters", "-1", "-af",
                                 f"asetpts=N/SR/TB,astats=metadata=0:reset=0,volume={input_gain:.8f}",
                                 "-c:a", "libvorbis", "-q:a", "4", "-ac", str(channels),
                                 "-ar", "44100", "-f", "ogg", "-y", output)
                after_channels, after_rate, after_duration = inspect_vorbis(output)
                if (after_channels != channels or after_rate != 44100
                        or abs(after_duration - duration) > 0.25):
                    raise ValueError(f"Channels, sample rate or duration changed: {name}")
                after_log = run(args.ffmpeg, "-i", output, "-map", "0:a:0",
                                "-af", "astats=metadata=0:reset=0", "-f", "null", "-")
                before_rms, after_rms = rms_db(before_log), rms_db(after_log)
                error_db = (after_rms - before_rms) - 20 * math.log10(GAIN)
                if abs(error_db) <= 0.25:
                    break
                input_gain *= 10 ** (-error_db / 20)
                if not 0 < input_gain < 1:
                    raise ValueError(f"Invalid corrective gain for {name}: {input_gain}")
            else:
                raise ValueError(f"Gain verification failed: {name}: {error_db:+.3f} dB")
            before_bytes += source.stat().st_size
            after_bytes += output.stat().st_size
            report["tracks"].append({"target": name, "beforeSha256": sha256(source),
                                     "afterSha256": sha256(output), "beforeRmsDb": round(before_rms, 3),
                                     "afterRmsDb": round(after_rms, 3), "inputGain": round(input_gain, 8),
                                     "channels": channels, "inputSampleRate": sample_rate,
                                     "outputSampleRate": 44100, "durationSeconds": round(after_duration, 3)})
            if index % 5 == 0 or index == len(target_names):
                print(f"Validated {index}/{len(target_names)} files", flush=True)
        report["inputBytes"] = before_bytes
        report["outputBytes"] = after_bytes
        # Persist the expected hashes first. An interrupted copy must fail the
        # idempotence check instead of silently applying another 0.5 gain.
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for track in report["tracks"]:
            shutil.copyfile(stage / track["target"], sound_root / track["target"])
    print(f"Scaled {len(target_names)} files: {before_bytes} -> {after_bytes} bytes")


if __name__ == "__main__":
    main()
