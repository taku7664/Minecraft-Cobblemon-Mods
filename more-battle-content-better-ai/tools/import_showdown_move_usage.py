#!/usr/bin/env python3
"""Create and cross-check a deterministic move-presence snapshot from Showdown stats."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_MONTH = "2025-12"
DEFAULT_FORMAT = "gen9bssregj"
DEFAULT_CUTOFF = 1500
DEFAULT_SHA256 = "1aae5d518447b12eb0a7ea3bbfe12d7b8de1303f985e0949055ca571580a54df"
DEFAULT_MOVESET_URL = (
    f"https://www.smogon.com/stats/{DEFAULT_MONTH}/moveset/"
    f"{DEFAULT_FORMAT}-{DEFAULT_CUTOFF}.txt"
)
DEFAULT_MOVESET_SHA256 = "e13bfc4812056be1b1d3226656c611848830f100e07d5c3a95ede3864a614661"


def canonical(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def finite_number(value: Any, label: str) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(f"{label} must be numeric")
    number = float(value)
    if not (number == number and abs(number) != float("inf")):
        raise ValueError(f"{label} must be finite")
    return number


def read_source(path: Path | None, url: str) -> bytes:
    if path is not None:
        return path.read_bytes()
    request = urllib.request.Request(url, headers={"User-Agent": "Cobblemon-Better-AI move-usage importer"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def transform(
    raw: bytes,
    source_url: str,
    expected_sha256: str | None,
    expected_format: str,
    expected_month: str,
    expected_cutoff: int,
) -> dict[str, Any]:
    digest = hashlib.sha256(raw).hexdigest()
    if expected_sha256 and digest.lower() != expected_sha256.lower():
        raise ValueError(f"source SHA-256 mismatch: expected {expected_sha256}, got {digest}")
    document = json.loads(raw)
    info = document.get("info")
    data = document.get("data")
    if not isinstance(info, dict) or not isinstance(data, dict) or not data:
        raise ValueError("source must contain non-empty info and data objects")
    format_id = str(info.get("metagame", ""))
    cutoff = int(finite_number(info.get("cutoff"), "info.cutoff"))
    battle_count = int(finite_number(info.get("number of battles"), "info.number of battles"))
    if format_id != expected_format or cutoff != expected_cutoff or battle_count <= 0:
        raise ValueError("source metadata does not match the requested snapshot")

    species_output: dict[str, dict[str, float]] = {}
    for raw_species, entry in sorted(data.items(), key=lambda item: canonical(item[0])):
        if not isinstance(entry, dict):
            raise ValueError(f"species entry must be an object: {raw_species}")
        species_id = canonical(raw_species)
        if not species_id or species_id in species_output:
            raise ValueError(f"empty or colliding species id: {raw_species}")
        abilities = entry.get("Abilities")
        moves = entry.get("Moves")
        if not isinstance(abilities, dict) or not isinstance(moves, dict):
            raise ValueError(f"missing Abilities or Moves: {raw_species}")
        denominator = sum(finite_number(value, f"{raw_species}.Abilities") for value in abilities.values())
        if denominator <= 0.0:
            continue
        move_output: dict[str, float] = {}
        for raw_move, weight in sorted(moves.items(), key=lambda item: canonical(item[0])):
            move_id = canonical(raw_move)
            # The chaos format uses the empty key for the aggregate unreported tail.
            if not move_id:
                continue
            if move_id in move_output:
                raise ValueError(f"colliding move id for {raw_species}: {raw_move}")
            rate = finite_number(weight, f"{raw_species}.Moves.{raw_move}") / denominator
            if rate < 0.0 or rate > 1.000000001:
                raise ValueError(f"invalid move-presence rate for {raw_species}/{raw_move}: {rate}")
            if rate == 0.0:
                continue
            rounded = round(min(rate, 1.0), 6)
            if rounded > 0.0:
                move_output[move_id] = rounded
        if move_output:
            species_output[species_id] = move_output

    if not species_output:
        raise ValueError("source produced no species")
    return {
        "schemaVersion": 1,
        "source": {
            "provider": "Smogon Pokemon Showdown usage stats",
            "format": format_id,
            "month": expected_month,
            "cutoff": cutoff,
            "battleCount": battle_count,
            "url": source_url,
            "rawSha256": digest,
        },
        "species": species_output,
    }


def parse_moveset_rates(raw: bytes) -> dict[str, dict[str, float]]:
    """Read the independently rendered Showdown moveset table for cross-validation."""
    result: dict[str, dict[str, float]] = {}
    current_species: str | None = None
    section: str | None = None
    after_separator = False
    section_names = {
        "Abilities", "Items", "Spreads", "Moves", "Tera Types", "Teammates",
        "Checks and Counters",
    }
    for raw_line in raw.decode("utf-8").splitlines():
        line = raw_line.strip()
        if line.startswith("+") and line.endswith("+"):
            after_separator = True
            continue
        if not (line.startswith("|") and line.endswith("|")):
            continue
        content = line[1:-1].strip()
        if after_separator:
            after_separator = False
            if content in section_names:
                section = content
            elif content.startswith(("Raw count:", "Avg. weight:", "Viability Ceiling:")):
                section = None
            else:
                current_species = content
                section = None
            continue
        if section != "Moves" or current_species is None:
            continue
        match = re.fullmatch(r"(.+?)\s+([0-9]+(?:\.[0-9]+)?)%", content)
        # `Nothing` is Showdown's rendered name for an empty move slot (the empty
        # key in chaos JSON), not a legal move hypothesis.
        if match is None or match.group(1) in {"Other", "Nothing"}:
            continue
        species_id = canonical(current_species)
        move_id = canonical(match.group(1))
        if not species_id or not move_id:
            raise ValueError(f"invalid moveset row: {content}")
        species = result.setdefault(species_id, {})
        if move_id in species:
            raise ValueError(f"duplicate moveset row: {current_species}/{match.group(1)}")
        species[move_id] = float(match.group(2)) / 100.0
    if not result:
        raise ValueError("moveset table produced no move rows")
    return result


def cross_validate_moveset(output: dict[str, Any], raw: bytes) -> int:
    """Require every named move in the text table to match chaos JSON after rounding."""
    rendered = parse_moveset_rates(raw)
    generated = output["species"]
    checked = 0
    for species_id, moves in rendered.items():
        if species_id not in generated:
            raise ValueError(f"moveset species missing from chaos JSON: {species_id}")
        for move_id, rendered_rate in moves.items():
            generated_rate = generated[species_id].get(move_id)
            if generated_rate is None:
                raise ValueError(f"moveset move missing from chaos JSON: {species_id}/{move_id}")
            if abs(generated_rate - rendered_rate) > 0.0000051:
                raise ValueError(
                    f"moveset rate mismatch for {species_id}/{move_id}: "
                    f"chaos={generated_rate:.6f}, moveset={rendered_rate:.6f}"
                )
            checked += 1
    if checked == 0:
        raise ValueError("moveset cross-validation checked no rows")
    return checked


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="Use a downloaded chaos JSON instead of HTTP")
    parser.add_argument("--format", default=DEFAULT_FORMAT)
    parser.add_argument("--month", default=DEFAULT_MONTH)
    parser.add_argument("--cutoff", type=int, default=DEFAULT_CUTOFF)
    parser.add_argument("--source-url")
    parser.add_argument("--expected-sha256")
    parser.add_argument("--moveset-input", type=Path)
    parser.add_argument("--moveset-url")
    parser.add_argument("--moveset-expected-sha256")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    source_url = args.source_url or (
        f"https://www.smogon.com/stats/{args.month}/chaos/{args.format}-{args.cutoff}.json"
    )
    output_path = args.output or (
        Path(__file__).resolve().parents[1]
        / "src/main/resources/data/cobblemon_more_battle_content_better_ai/opponent_move_usage"
        / f"{args.format}-{args.month}-{args.cutoff}.json"
    )
    expected_sha256 = args.expected_sha256
    is_default_snapshot = (
        args.format, args.month, args.cutoff
    ) == (DEFAULT_FORMAT, DEFAULT_MONTH, DEFAULT_CUTOFF)
    if expected_sha256 is None and is_default_snapshot:
        expected_sha256 = DEFAULT_SHA256
    raw = read_source(args.input, source_url)
    output = transform(raw, source_url, expected_sha256, args.format, args.month, args.cutoff)
    checked = 0
    moveset_url = args.moveset_url or (DEFAULT_MOVESET_URL if is_default_snapshot else None)
    moveset_expected_sha256 = args.moveset_expected_sha256 or (
        DEFAULT_MOVESET_SHA256 if is_default_snapshot else None
    )
    if args.moveset_input is not None or moveset_url is not None:
        moveset_raw = read_source(args.moveset_input, moveset_url or "")
        moveset_digest = hashlib.sha256(moveset_raw).hexdigest()
        if moveset_expected_sha256 and moveset_digest.lower() != moveset_expected_sha256.lower():
            raise ValueError(
                "moveset SHA-256 mismatch: "
                f"expected {moveset_expected_sha256}, got {moveset_digest}"
            )
        checked = cross_validate_moveset(output, moveset_raw)
        output["source"]["crossValidation"] = {
            "provider": "Smogon Pokemon Showdown moveset stats",
            "url": moveset_url or "local input",
            "rawSha256": moveset_digest,
            "verifiedMoveRows": checked,
        }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(output, ensure_ascii=True, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"wrote {len(output['species'])} species to {output_path} "
        f"from {output['source']['battleCount']} battles; cross-checked {checked} move rows"
    )


if __name__ == "__main__":
    main()
