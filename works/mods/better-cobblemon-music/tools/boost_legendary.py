"""Rebuild the approved legendary battle tracks with 1.3x input gain.

The source manifest is authoritative. Three imported tracks have no surviving
source manifest entry and are deliberately re-encoded from the existing OGGs.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from zipfile import ZipFile


GAIN_FILTER = "asetpts=N/SR/TB,alimiter=level_in=1.3:limit=0.85:attack=5:release=50:level=false:latency=true"
FALLBACK_OGG = {
    "oras_dialga_palkia_battle.ogg",
    "oras_reshiram_zekrom_battle.ogg",
    "za_darkrai_battle.ogg",
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(ffmpeg, *arguments):
    return subprocess.run([str(ffmpeg), "-hide_banner", "-loglevel", "error", *map(str, arguments)],
                          check=True, capture_output=True, text=True)


def peak_db(ffmpeg, audio):
    result = subprocess.run(
        [str(ffmpeg), "-hide_banner", "-nostats", "-i", str(audio),
         "-af", "astats=metadata=0:reset=0", "-f", "null", "NUL"],
        check=True, capture_output=True, text=True,
    )
    peaks = re.findall(r"Peak level dB:\s*([-+]?[0-9.]+)", result.stderr)
    if not peaks:
        raise ValueError(f"Could not read decoded peak for {audio}")
    return float(peaks[-1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--lugia", type=Path, required=True)
    parser.add_argument("--fallback-zip", type=Path,
                        help="Prior official ZIP containing the three OGG-only source tracks, for safe reruns")
    args = parser.parse_args()

    module = Path(__file__).resolve().parents[1]
    manifest = json.loads((module / "resource-pack/import-lineup-2026-10-03.json").read_text(encoding="utf-8"))
    sound_dir = module / "resource-pack/src/assets/better_cobblemon_music/sounds/music/battle/legendary"
    report_path = module / "resource-pack/legendary-gain-2026-10-04.json"
    previous = json.loads(report_path.read_text(encoding="utf-8")) if report_path.is_file() else None
    prior_tracks = {entry["target"]: entry for entry in previous["tracks"]} if previous else {}
    plan = {}
    for folder, pattern, target in manifest["files"]:
        if not target.startswith("battle/legendary/"):
            continue
        matches = list((args.source_root / folder).glob(pattern))
        if len(matches) != 1:
            raise ValueError(f"Expected one original for {target}, got {len(matches)}")
        plan[Path(target).name] = matches[0]
    plan["hgss_lugia_battle.ogg"] = args.lugia
    for name in FALLBACK_OGG:
        plan[name] = sound_dir / name

    actual = {path.name for path in sound_dir.glob("*.ogg")}
    if set(plan) != actual or len(plan) != 45:
        raise ValueError(f"Legendary source inventory differs: missing={actual - set(plan)}, extra={set(plan) - actual}")
    for name, source in plan.items():
        if not source.is_file() or not (sound_dir / name).is_file():
            raise FileNotFoundError(f"Missing source or target for {name}: {source}")
        if previous and sha256(sound_dir / name) != prior_tracks[name]["afterSha256"]:
            raise ValueError(f"Current track differs from the prior gain report: {name}")
    if previous and not args.fallback_zip:
        raise ValueError("A rerun needs --fallback-zip to avoid amplifying the three OGG-only tracks twice")

    (module / "build").mkdir(exist_ok=True)
    report = {"gain": 1.3, "filter": GAIN_FILTER, "codec": "Ogg Vorbis", "quality": 4,
              "sampleRate": 44100, "tracks": []}
    with tempfile.TemporaryDirectory(prefix="legendary-gain-", dir=module / "build") as temp:
        stage = Path(temp)
        if args.fallback_zip:
            with ZipFile(args.fallback_zip) as archive:
                for name in FALLBACK_OGG:
                    old_source = stage / ("original-" + name)
                    old_source.write_bytes(archive.read(
                        "assets/cobleserver/sounds/music/battle/legendary/" + name))
                    if previous and sha256(old_source) != prior_tracks[name]["beforeSha256"]:
                        raise ValueError(f"Prior ZIP contains the wrong original track: {name}")
                    plan[name] = old_source
        for index, (name, source) in enumerate(sorted(plan.items()), start=1):
            target = sound_dir / name
            output = stage / name
            level_out = 1.0
            for attempt in range(4):
                run(args.ffmpeg, "-i", source, "-map", "0:a:0", "-vn", "-sn", "-dn",
                    "-map_metadata", "-1", "-map_chapters", "-1", "-af",
                    GAIN_FILTER + f":level_out={level_out:.8f}",
                    "-c:a", "libvorbis", "-q:a", "4", "-ar", "44100", "-y", output)
                decoded = run(args.ffmpeg, "-i", output, "-f", "null", "NUL")
                if decoded.stderr:
                    raise ValueError(f"Decode warning for {name}: {decoded.stderr.strip()}")
                peak = peak_db(args.ffmpeg, output)
                if peak <= -0.1:
                    break
                level_out *= 10 ** ((-0.5 - peak) / 20)
            else:
                raise ValueError(f"Could not keep decoded peak below 0 dB for {name}")
            report["tracks"].append({"target": name, "source": "existing-ogg" if name in FALLBACK_OGG else "original",
                                     "beforeSha256": prior_tracks[name]["beforeSha256"] if previous else sha256(target),
                                     "afterSha256": sha256(output), "decodedPeakDb": round(peak, 3),
                                     "limiterOutputGain": round(level_out, 8)})
            if index % 5 == 0:
                print(f"Validated {index}/{len(plan)} tracks", flush=True)
        for name in sorted(plan):
            shutil.copyfile(stage / name, sound_dir / name)

    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Published {len(plan)} validated tracks and {report_path}")


if __name__ == "__main__":
    main()
