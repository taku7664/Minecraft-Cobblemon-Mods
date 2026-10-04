"""Build the offline server-startup wiki bundle from tracked static wiki files."""
import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path


def build(output: Path) -> dict:
    repo = Path(__file__).resolve().parents[2]
    wiki = repo.parent / "server-wiki"
    files = [wiki / "index.html"]
    files += sorted(path for folder in ("pages", "assets") for path in (wiki / folder).rglob("*") if path.is_file())
    if any(path.is_symlink() for path in files):
        raise ValueError("Wiki source must not contain symbolic links")
    output.parent.mkdir(parents=True, exist_ok=True)
    size = 0
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(files):
            data = path.read_bytes()
            size += len(data)
            info = zipfile.ZipInfo(path.relative_to(wiki).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compresslevel=9)
    metadata = {
        "schema_version": 1,
        "source_repository": "https://github.com/taku7664/Minecraft-Cobblemon-Mods",
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
        "archive": output.name,
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "file_count": len(files),
        "unpacked_bytes": size,
    }
    output.with_suffix(".bundle.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    print(json.dumps(build(parser.parse_args().output), indent=2))
