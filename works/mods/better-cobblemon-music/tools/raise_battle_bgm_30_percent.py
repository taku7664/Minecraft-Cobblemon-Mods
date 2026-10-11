"""Raise only the 61 official battle BGM OGG files by 30% of current amplitude.

The previous all-BGM report pins the input hashes. The new report makes this
one-time operation idempotent; field/screen BGM and effects stay untouched.
"""

import argparse
import json
import math
from pathlib import Path
import re
import shutil
import tempfile

from scale_all_bgm_and_alert import inspect_vorbis, rms_db, run, sha256


GAIN = 1.3
EXPECTED_TRACKS = 61
REPORT_NAME = "battle-gain-2026-10-11.json"


def peak_db(log):
    values = re.findall(r"Peak level dB:\s*([-+]?(?:\d+(?:\.\d+)?|inf))", log)
    if not values:
        raise ValueError("FFmpeg did not report decoded peak level")
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
    sounds = module / "resource-pack/src/assets/better_cobblemon_music/sounds"
    battle = sounds / "music/battle"
    layout = json.loads((module / "resource-pack/catalog-layout.json").read_text(encoding="utf-8"))
    expected = {"music/" + key.removeprefix("better_cobblemon_music:") + ".ogg"
                for key in layout["trackTitles"] if key.startswith("better_cobblemon_music:battle/")}
    actual = {"music/battle/" + path.relative_to(battle).as_posix()
              for path in battle.rglob("*.ogg")}
    if len(expected) != EXPECTED_TRACKS or actual != expected:
        raise ValueError(f"Battle inventory mismatch: missing={expected - actual}, extra={actual - expected}")

    previous = json.loads((module / "resource-pack/all-bgm-alert-volume-2026-10-11.json")
                           .read_text(encoding="utf-8"))
    previous_tracks = {track["target"]: track for track in previous["tracks"]
                     if track["target"].startswith("music/battle/")}
    if set(previous_tracks) != expected:
        raise ValueError("Previous gain report has a different battle inventory")

    report_path = module / "resource-pack" / REPORT_NAME
    if report_path.exists():
        report = json.loads(report_path.read_text(encoding="utf-8"))
        recorded = {track["target"]: track for track in report["tracks"]}
        if report.get("multiplier") != GAIN or set(recorded) != expected:
            raise ValueError("Existing report has another scope")
        for name in expected:
            if recorded[name]["beforeSha256"] != previous_tracks[name]["afterSha256"]:
                raise ValueError(f"Previous gain does not match {name}")
            if sha256(sounds / name) != recorded[name]["afterSha256"]:
                raise ValueError(f"Current audio does not match {name}")
        print(f"Already raised and verified {len(expected)} tracks; no second gain pass")
        return

    report = {"schemaVersion": 1, "multiplier": GAIN,
              "scope": "official battle BGM only; other BGM and effects excluded",
              "codec": "Ogg Vorbis", "quality": 4, "tracks": []}
    before_bytes = after_bytes = 0
    with tempfile.TemporaryDirectory(prefix="bcm-battle-gain-", dir=args.scratch) as temporary:
        stage = Path(temporary)
        for index, name in enumerate(sorted(expected), start=1):
            source = sounds / name
            if sha256(source) != previous_tracks[name]["afterSha256"]:
                raise ValueError(f"Input differs from approved previous output: {name}")
            channels, sample_rate, duration = inspect_vorbis(source)
            output = stage / name
            output.parent.mkdir(parents=True, exist_ok=True)
            input_gain = GAIN
            for _ in range(5):
                before_log = run(args.ffmpeg, "-i", source, "-map", "0:a:0", "-vn", "-sn", "-dn",
                                 "-map_metadata", "-1", "-map_chapters", "-1", "-af",
                                 f"asetpts=N/SR/TB,astats=metadata=0:reset=0,volume={input_gain:.8f}",
                                 "-c:a", "libvorbis", "-q:a", "4", "-ac", str(channels),
                                 "-ar", "44100", "-f", "ogg", "-y", output)
                out_channels, out_rate, out_duration = inspect_vorbis(output)
                if out_channels != channels or out_rate != 44100 or abs(out_duration - duration) > 0.25:
                    raise ValueError(f"Channels, rate or duration changed: {name}")
                after_log = run(args.ffmpeg, "-i", output, "-map", "0:a:0",
                                "-af", "astats=metadata=0:reset=0", "-f", "null", "-")
                before_rms, after_rms = rms_db(before_log), rms_db(after_log)
                error_db = (after_rms - before_rms) - 20 * math.log10(GAIN)
                if abs(error_db) <= 0.25:
                    break
                input_gain *= 10 ** (-error_db / 20)
                if not 0 < input_gain < 2:
                    raise ValueError(f"Invalid corrective gain for {name}: {input_gain}")
            else:
                raise ValueError(f"Gain verification failed: {name}: {error_db:+.3f} dB")
            before_peak, after_peak = peak_db(before_log), peak_db(after_log)
            before_bytes += source.stat().st_size
            after_bytes += output.stat().st_size
            report["tracks"].append({"target": name, "beforeSha256": sha256(source),
                                     "afterSha256": sha256(output), "beforeRmsDb": round(before_rms, 3),
                                     "afterRmsDb": round(after_rms, 3), "beforePeakDb": round(before_peak, 3),
                                     "afterPeakDb": round(after_peak, 3),
                                     "inputGain": round(input_gain, 8), "channels": channels,
                                     "inputSampleRate": sample_rate, "outputSampleRate": 44100,
                                     "durationSeconds": round(out_duration, 3)})
            if index % 5 == 0 or index == len(expected):
                print(f"Validated {index}/{len(expected)} tracks", flush=True)
        report["inputBytes"] = before_bytes
        report["outputBytes"] = after_bytes
        # A partial copy must fail the report check rather than apply the gain twice.
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for track in report["tracks"]:
            shutil.copyfile(stage / track["target"], sounds / track["target"])
    print(f"Raised {len(expected)} battle BGMs: {before_bytes} -> {after_bytes} bytes")


if __name__ == "__main__":
    main()
