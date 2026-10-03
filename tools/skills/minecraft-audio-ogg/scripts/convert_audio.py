"""Compact Minecraft OGG/Vorbis import, no Python packages required."""
import argparse
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile


def run(ffmpeg, arguments, *, check=True):
    result = subprocess.run([str(ffmpeg), "-nostdin", *arguments], capture_output=True,
                            text=True, encoding="utf-8", errors="replace")
    if check and result.returncode:
        raise RuntimeError(result.stderr[-4000:])
    return result


def probe_source(ffmpeg, source):
    # FFmpeg intentionally returns nonzero with no output specified.
    result = run(ffmpeg, ["-hide_banner", "-i", str(source)], check=False)
    stream = re.search(r"Audio: [^\n]*?, (\d+) Hz, ([^,\n]+)", result.stderr)
    if not stream:
        raise ValueError("Input contains no readable audio stream: " + str(source))
    duration = re.search(r"Duration: (\d+):(\d+):(\d+\.\d+)", result.stderr)
    seconds = (int(duration[1]) * 3600 + int(duration[2]) * 60 + float(duration[3])) if duration else None
    return int(stream[1]), stream[2].strip(), seconds


def inspect_vorbis(path):
    """Inspect all Ogg page serials and the identification packet, not just the extension."""
    serials = set()
    first_packet = bytearray()
    packet_complete = False
    final_granule = 0
    with path.open("rb") as stream:
        while header := stream.read(27):
            if len(header) != 27 or header[:5] != b"OggS\x00":
                raise ValueError("Invalid Ogg page")
            granule = struct.unpack_from("<Q", header, 6)[0]
            serials.add(struct.unpack_from("<I", header, 14)[0])
            laces = stream.read(header[26])
            if len(laces) != header[26]:
                raise ValueError("Truncated Ogg lacing")
            body = stream.read(sum(laces))
            if len(body) != sum(laces):
                raise ValueError("Truncated Ogg page body")
            if not packet_complete:
                offset = 0
                for size in laces:
                    first_packet.extend(body[offset:offset + size])
                    offset += size
                    if size < 255:
                        packet_complete = True
                        break
            if granule != 0xFFFFFFFFFFFFFFFF:
                final_granule = granule
    if len(serials) != 1 or len(first_packet) < 30 or first_packet[:7] != b"\x01vorbis":
        raise ValueError("Expected exactly one OGG Vorbis audio stream")
    channels = first_packet[11]
    sample_rate = struct.unpack_from("<I", first_packet, 12)[0]
    if not sample_rate or not final_granule:
        raise ValueError("Empty/invalid Vorbis audio")
    return {"channels": channels, "sample_rate": sample_rate, "logical_streams": len(serials),
            "duration_seconds": final_granule / sample_rate}


def convert(source, output, ffmpeg, *, mode="music", quality=4, overwrite=False, allow_ogg_input=False):
    source, output = Path(source).resolve(), Path(output).resolve()
    if source == output:
        raise ValueError("Never overwrite the input audio")
    if not source.is_file():
        raise FileNotFoundError(source)
    if output.suffix.lower() != ".ogg":
        raise ValueError("Destination must have an .ogg extension")
    if output.exists() and not overwrite:
        raise FileExistsError(output)
    if source.suffix.lower() == ".ogg" and not allow_ogg_input:
        raise ValueError("OGG re-encoding requires --allow-ogg-input")
    if mode not in ("music", "positional") or not -1 <= quality <= 10:
        raise ValueError("Invalid mode or Vorbis quality (-1..10)")
    sample_rate, layout, source_duration = probe_source(ffmpeg, source)
    channels = 1 if mode == "positional" or layout == "mono" else 2
    sample_rate = min(sample_rate, 44100)
    output.parent.mkdir(parents=True, exist_ok=True)
    handle, name = tempfile.mkstemp(prefix=".audio-", suffix=".ogg", dir=output.parent)
    os.close(handle)
    temporary = Path(name)
    try:
        run(ffmpeg, ["-hide_banner", "-v", "error", "-xerror", "-err_detect", "explode",
                     "-i", str(source), "-map", "0:a:0", "-vn", "-sn", "-dn",
                     "-map_metadata", "-1", "-map_chapters", "-1", "-c:a", "libvorbis",
                     "-q:a", str(quality), "-ac", str(channels), "-ar", str(sample_rate),
                     "-f", "ogg", "-y", str(temporary)])
        report = inspect_vorbis(temporary)
        if (report["channels"], report["sample_rate"]) != (channels, sample_rate):
            raise ValueError("Output does not match requested audio policy")
        # MP3 container duration includes encoder padding; allow small probe rounding/padding only.
        if source_duration is not None and abs(report["duration_seconds"] - source_duration) > 0.25:
            raise ValueError("Conversion changed duration by more than 0.25 seconds")
        run(ffmpeg, ["-v", "error", "-xerror", "-err_detect", "explode", "-i", str(temporary),
                     "-map", "0:a:0", "-f", "null", "-"])
        report.update(input_bytes=source.stat().st_size, output_bytes=temporary.stat().st_size,
                      quality=quality, mode=mode, source=str(source), output=str(output))
        if overwrite:
            os.replace(temporary, output)
        else:
            # Hard-link publication is atomic and fails if another process created the destination.
            os.link(temporary, output)
        return report
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--mode", choices=("music", "positional"), default="music")
    parser.add_argument("--quality", type=float, default=4)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--allow-ogg-input", action="store_true")
    args = parser.parse_args()
    print(json.dumps(convert(args.source, args.output, args.ffmpeg, mode=args.mode,
                             quality=args.quality, overwrite=args.overwrite,
                             allow_ogg_input=args.allow_ogg_input), ensure_ascii=False))


if __name__ == "__main__":
    main()
