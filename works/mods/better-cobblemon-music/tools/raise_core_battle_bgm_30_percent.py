"""Raise five ordinary battle themes, including every use of each shared track.

Run once against the outputs pinned by battle-gain-2026-10-11.json.
The resulting report makes repeated runs validation-only.
"""

import argparse
import json
import math
from pathlib import Path
import shutil
import tempfile

from raise_battle_bgm_30_percent import peak_db
from scale_all_bgm_and_alert import inspect_vorbis, rms_db, run, sha256


GAIN = 1.3
TRACKS = (
    "music/battle/champion/sinnoh_cynthia_battle.ogg",
    "music/battle/elite/sinnoh_elite_four_battle.ogg",
    "music/battle/gym/sinnoh_gym_leader_battle.ogg",
    "music/battle/trainer/sinnoh_trainer_battle.ogg",
    "music/battle/wild/sinnoh_wild_pokemon_battle.ogg",
)
REPORT_NAME = "core-battle-gain-2026-10-13.json"


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
    prior_report = json.loads((module / "resource-pack/battle-gain-2026-10-11.json")
                              .read_text(encoding="utf-8"))
    prior = {track["target"]: track["afterSha256"] for track in prior_report["tracks"]}
    if not set(TRACKS) <= prior.keys():
        raise ValueError("Historical report is missing a requested track")

    report_path = module / "resource-pack" / REPORT_NAME
    if report_path.exists():
        report = json.loads(report_path.read_text(encoding="utf-8"))
        recorded = {track["target"]: track for track in report["tracks"]}
        if report.get("multiplier") != GAIN or set(recorded) != set(TRACKS) \
                or "preserved" in report:
            raise ValueError("Existing report has another scope")
        for name in TRACKS:
            if recorded[name]["beforeSha256"] != prior[name] \
                    or sha256(sounds / name) != recorded[name]["afterSha256"]:
                raise ValueError(f"Current audio differs from recorded result: {name}")
        print("Already raised five shared tracks")
        return

    for name in TRACKS:
        if sha256(sounds / name) != prior[name]:
            raise ValueError(f"Input differs from previous approved output: {name}")
    report = {"schemaVersion": 1, "multiplier": GAIN,
              "scope": "five shared battle tracks; other battle and field audio files unchanged",
              "codec": "Ogg Vorbis", "quality": 4, "tracks": []}
    with tempfile.TemporaryDirectory(prefix="bcm-core-battle-gain-", dir=args.scratch) as temporary:
        stage = Path(temporary)
        for name in TRACKS:
            source = sounds / name
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
            report["tracks"].append({"target": name, "beforeSha256": sha256(source),
                                     "afterSha256": sha256(output), "beforeRmsDb": round(before_rms, 3),
                                     "afterRmsDb": round(after_rms, 3), "beforePeakDb": round(peak_db(before_log), 3),
                                     "afterPeakDb": round(peak_db(after_log), 3),
                                     "inputGain": round(input_gain, 8), "channels": channels,
                                     "inputSampleRate": sample_rate, "outputSampleRate": 44100,
                                     "durationSeconds": round(out_duration, 3)})
        # A partial copy must fail validation rather than apply gain a second time.
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for track in report["tracks"]:
            shutil.copyfile(stage / track["target"], sounds / track["target"])
    print("Raised five shared battle themes")


if __name__ == "__main__":
    main()
