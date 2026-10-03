---
name: minecraft-audio-ogg
description: Convert music or sound effects to compact, validated OGG Vorbis for Minecraft Java resource packs. Use for MP3, FLAC, WAV or video audio imports, compression and thumbnail removal, not music selection or pack deployment.
---

# Minecraft audio OGG

Use `scripts/convert_audio.py` with an available FFmpeg binary containing `libvorbis`. The helper uses Python's standard library only. Keep the original input files.

## Choose the audio policy

- BGM without positioning: `--mode music` (default). Preserve mono/stereo; downmix sources with more than two channels to stereo. Do not discard stereo merely because the destination is Minecraft.
- Effects with direction/distance attenuation: `--mode positional`, which mixes to mono. Do not just drop one stereo channel.
- Default: Vorbis `--quality 4`, at most 44,100 Hz. This is a size/quality compromise, not a fixed bitrate or a claim of transparent quality. Use another quality when the user specifies it or audible comparison justifies it. No gain normalization, silence trimming or looping edits by default.
- OGG is a container: require **Vorbis**, not Opus, FLAC or a renamed MP3. Select the first audio stream explicitly; discard video, attached artwork, subtitles, chapters and inherited metadata. BGM's `sounds.json` entry should use `stream: true`; this script does not edit pack mappings.

Fabric's [custom sound guidance](https://docs.fabricmc.net/develop/sounds/custom) recommends mono to avoid **distance/positioning** problems. For an existing music implementation, check its `SoundInstance` attenuation/relative flags before deciding the channel policy.

## Convert and verify

```powershell
py -3.14 -X utf8 scripts/convert_audio.py "song.flac" "song.ogg" --ffmpeg "C:\tools\ffmpeg.exe" --mode music
```

Use absolute paths in a real task. `--overwrite` permits replacing an existing destination only; input and output must differ. Existing Vorbis inputs are rejected by default to avoid lossy-to-lossy recompression; use `--allow-ogg-input` only when a deliberate conversion is required. MP3 inputs are also lossy: prefer an original FLAC/WAV when both exist.

The script encodes to a temporary file, checks one Vorbis logical stream, channel count/sample rate and duration, fully decodes with FFmpeg's error checks, then publishes. A failure leaves the original destination untouched. A JSON report returns input/output sizes, duration and channel policy. Report actual aggregate byte savings; do not infer audible quality from successful decoding.

For a batch, inventory sources and destination IDs first, reject ambiguous filename matches and duplicate targets, then run the helper for each source. Do not silently skip failed conversions. Keep conversion policy and source-to-target manifest with the project when this supports reproducible imports.

## Dependency and regression checks

Prefer an installed FFmpeg. If unavailable, obtain a binary into a task-specific tool directory without altering global Python/FFmpeg configuration. One option is a pinned `imageio-ffmpeg` wheel from PyPI: verify its published SHA-256 before extraction and locate `imageio_ffmpeg/binaries/ffmpeg*.exe`. Do not put the binary or user's music inside the skill.

```powershell
$env:MINECRAFT_AUDIO_FFMPEG = "C:\tools\ffmpeg.exe"
py -3.14 -X utf8 scripts/test_convert_audio.py
```

Tests synthesize small fixtures including an MP3 with an attached cover, check mono/stereo behavior, and confirm failures cannot replace an existing destination. Pack build validation and actual in-game listening remain separate checks.
