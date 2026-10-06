"""Writes the skins of MCC's battle trainer NPC (`more_cobblemon_contents:managed_trainer`).

Each MCC module that names trainer skins in its data gets a variation file under its own assets that maps the skin's
aspect to the texture, with Cobblemon's slim or wide player model. The aspect rule matches
`TrainerResourceSkin.aspect` in MCC core. Run after adding or changing a trainer skin:

    python works/tools/generate_managed_trainer_variations.py           # write the files
    python works/tools/generate_managed_trainer_variations.py --check   # fail when a file is out of date
"""
import json
import pathlib
import re
import sys

MODS = pathlib.Path(__file__).resolve().parent.parent / "mods"
NAME = "more_cobblemon_contents:managed_trainer"
POSER = "cobblemon:standard"
# Core holds the default look (a trainer with no skin of its own); each addon adds its skins after it.
ORDER = {
    "more-cobblemon-contents": 0,
    "more-cobblemon-contents-league-challenge": 10,
    "more-cobblemon-contents-battle-tower": 20,
    "more-cobblemon-contents-battle-factory": 30,
}


def aspect(texture: str, slim: bool) -> str:
    return "mcc_skin_" + re.sub(r"[^a-z0-9]+", "_", texture.lower()).strip("_") + ("_slim" if slim else "")


def variation(texture: str, slim: bool, aspects: list) -> dict:
    return {
        "aspects": aspects,
        "layers": [],
        "model": "cobblemon:alex.geo" if slim else "cobblemon:steve.geo",
        "poser": POSER,
        "texture": texture,
    }


def skins(module: pathlib.Path) -> set:
    """Every (texture, slim) a module's data dresses a battle trainer in."""
    found = set()
    for path in (module / "src/main/resources/data").rglob("*.json"):
        if "/npcs/" in path.as_posix() or "/mcc-bp-shop/" in path.as_posix():
            continue  # Cobblemon NPC classes and shopkeepers are not battle trainers.
        document = json.loads(path.read_text(encoding="utf-8"))
        stack = [document]
        while stack:
            node = stack.pop()
            if isinstance(node, dict):
                skin = node.get("skin")
                if isinstance(skin, str) and skin.endswith(".png"):
                    slim = node.get("slim") is True or node.get("model") == "slim"
                    found.add((skin, slim))
                stack.extend(node.values())
            elif isinstance(node, list):
                stack.extend(node)
    return found


def namespace(module: pathlib.Path) -> str:
    assets = module / "src/main/resources/assets"
    names = sorted(p.name for p in assets.iterdir() if p.is_dir() and p.name not in ("cobblemon", "minecraft"))
    return names[0]


def render(module_name: str, module: pathlib.Path) -> tuple:
    variations = []
    if module_name == "more-cobblemon-contents":
        variations.append(variation("minecraft:textures/entity/player/wide/steve.png", False, []))
    for texture, slim in sorted(skins(module)):
        variations.append(variation(texture, slim, [aspect(texture, slim)]))
    target = module / "src/main/resources/assets" / namespace(module) / "bedrock/npcs/variations/managed_trainer" / \
        f"{ORDER[module_name]}_{module_name.replace('-', '_')}.json"
    body = json.dumps({"name": NAME, "order": ORDER[module_name], "variations": variations}, indent=1) + "\n"
    return target, body


def main() -> int:
    check = "--check" in sys.argv[1:]
    stale = []
    for module_name in ORDER:
        target, body = render(module_name, MODS / module_name)
        current = target.read_text(encoding="utf-8") if target.exists() else None
        if current == body:
            continue
        if check:
            stale.append(target)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body, encoding="utf-8")
            print(f"wrote {target.relative_to(MODS)} ({body.count('\"aspects\"')} variations)")
    for target in stale:
        print(f"out of date: {target.relative_to(MODS)}")
    return 1 if stale else 0


if __name__ == "__main__":
    sys.exit(main())
