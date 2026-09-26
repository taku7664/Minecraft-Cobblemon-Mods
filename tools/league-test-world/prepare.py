"""Create a NEW disposable test world; never copy player or progression records.

Requires nbtlib==2.0.4. Minecraft must be closed before targeting its saves folder.
"""
import argparse
import shutil
from pathlib import Path

import nbtlib
from nbtlib import Byte, Int, List, String


def prepare(template: Path, destination: Path) -> None:
    template = template.resolve(strict=True)
    destination = destination.resolve()
    if destination.exists():
        raise ValueError("Destination already exists; refusing to change any existing world")
    if destination.name != "league-cap-test" or destination.parent.name != "saves":
        raise ValueError("Destination must be a new saves/league-cap-test directory")
    level = nbtlib.load(template / "level.dat")
    data = level["Data"]
    data.pop("Player", None)
    data["LevelName"] = String("League Cap Test - 15 to 20")
    data["allowCommands"] = Byte(1)
    data["GameType"] = Int(0)
    data["SpawnX"], data["SpawnY"], data["SpawnZ"] = Int(0), Int(127), Int(1)
    data["GameRules"]["spawnRadius"] = String("0")
    enabled = List[String]([String(str(pack)) for pack in data["DataPacks"]["Enabled"]])
    if String("file/league-test-kit") not in enabled:
        enabled.append(String("file/league-test-kit"))
    data["DataPacks"]["Enabled"] = enabled
    destination.mkdir(parents=True)
    # Terrain only. In particular: no pokemon/, playerdata/, data/, or CLC accounts.
    for folder in ("region", "entities", "poi"):
        if (template / folder).is_dir():
            shutil.copytree(template / folder, destination / folder)
    shutil.copytree(Path(__file__).parent / "datapack", destination / "datapacks/league-test-kit")
    level.save(destination / "level.dat")
    saved = nbtlib.load(destination / "level.dat")["Data"]
    assert "Player" not in saved
    assert str(saved["LevelName"]) == "League Cap Test - 15 to 20"
    assert not (destination / "pokemon").exists()
    assert not (destination / "data").exists()
    print(f"Prepared new test world: {destination}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("template", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    prepare(args.template, args.destination)
