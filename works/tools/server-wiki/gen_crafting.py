"""Generate the major Cobblemon crafting guide from a JAR and committed policy overrides.

Usage: python works/tools/server-wiki/gen_crafting.py --cobblemon PATH
The output is static; regenerate after a recipe or policy change. Optional-mod
compatibility recipes are excluded, so the guide does not promise missing mods.
"""
import argparse
import collections
import json
import pathlib
import re
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
POLICY = ROOT / "works/mods/jbro-policy/src/main/resources"
OUTPUT = ROOT / "server-wiki/assets/data/crafting.js"
METHODS = {
    "minecraft:crafting_shaped": "조합대", "minecraft:crafting_shapeless": "조합대 · 배치 무관",
    "cobblemon:cooking_pot": "모닥불 냄비", "cobblemon:cooking_pot_shapeless": "모닥불 냄비 · 배치 무관",
    "cobblemon:brewing_stand": "양조기", "minecraft:smithing_transform": "대장장이 작업대",
    "minecraft:smelting": "화로", "minecraft:blasting": "용광로",
    "minecraft:stonecutting": "석재 절단기",
    "minecraft:campfire_cooking": "모닥불", "minecraft:smoking": "훈연기",
}
TAG_NAMES = {
    "minecraft:planks": "나무 판자(종류 무관)", "minecraft:wool": "양털(색상 무관)",
    "minecraft:logs": "원목(종류 무관)", "minecraft:logs_that_burn": "불에 타는 원목",
    "minecraft:flowers": "꽃", "minecraft:small_flowers": "작은 꽃",
    "minecraft:wooden_slabs": "나무 반 블록", "minecraft:wooden_buttons": "나무 버튼",
    "minecraft:leaves": "나뭇잎", "minecraft:coals": "석탄 또는 목탄",
    "c:drinks/milk": "우유", "c:crops/wheat": "밀",
    "c:ingots/copper": "구리 주괴", "c:ingots/iron": "철 주괴", "c:ingots/gold": "금 주괴",
    "c:ingots/netherite": "네더라이트 주괴", "c:nuggets/iron": "철 조각", "c:nuggets/gold": "금 조각",
    "c:gems/amethyst": "자수정 조각", "c:gems/lapis": "청금석", "c:gems/diamond": "다이아몬드",
    "c:gems/emerald": "에메랄드", "c:gems/quartz": "네더 석영",
    "c:dusts/redstone": "레드스톤 가루", "c:dusts/glowstone": "발광석 가루",
    "c:strings": "실", "c:glass_blocks": "유리", "c:glass_blocks/colorless": "유리",
    "c:slime_balls": "슬라임볼", "c:feathers": "깃털", "c:eggs": "달걀",
    "c:bones": "뼈", "c:bricks/normal": "벽돌", "c:buckets/empty": "양동이",
    "c:chains": "사슬", "c:chests/wooden": "나무 상자", "c:concretes": "콘크리트",
    "c:foods/bread": "빵", "c:foods/raw_meat": "익히지 않은 고기", "c:gems/prismarine": "프리즈머린 수정",
    "c:leathers": "가죽", "c:mushrooms": "버섯", "c:raw_materials/gold": "금 원석",
    "c:rods/blaze": "블레이즈 막대기", "c:rods/wooden": "막대기", "c:seeds": "씨앗",
    "c:storage_blocks/iron": "철 블록", "c:tools/shield": "방패",
    "minecraft:buttons": "버튼", "minecraft:enchantable/fishing": "낚싯대",
    "c:fertilizers": "뼛가루",
    "c:foods/berry": "달콤한 열매 또는 발광 열매",
}
for colour, korean in {"black": "검은색", "blue": "파란색", "brown": "갈색", "cyan": "청록색",
                       "gray": "회색", "green": "초록색", "light_blue": "하늘색", "light_gray": "회백색",
                       "lime": "연두색", "magenta": "자홍색", "orange": "주황색", "pink": "분홍색",
                       "purple": "보라색", "red": "빨간색", "white": "하얀색", "yellow": "노란색"}.items():
    TAG_NAMES["c:dyes/" + colour] = korean + " 염료"
EQUIPMENT = {"pc", "healing_machine", "pasture", "monitor", "tm_machine", "exp_share",
             "fossil_analyzer", "restoration_tank", "fossil_cleaner", "resurrection_machine",
             "fossil_machine", "campfire", "brewing_stand"}
SUPPLIES = {"poke_snack", "medicinal_brew", "vivichoke_seeds", "ability_capsule", "pp_up", "pp_max"}
SUPPLIES |= {"fire_stone", "water_stone", "thunder_stone", "leaf_stone", "moon_stone", "sun_stone",
             "shiny_stone", "dusk_stone", "dawn_stone", "ice_stone", "oval_stone", "razor_claw",
             "razor_fang", "dragon_scale", "prism_scale", "link_cable", "kings_rock", "metal_coat",
             "upgrade", "dubious_disc", "protector", "electirizer", "magmarizer", "reaper_cloth",
             "sachet", "whipped_dream", "energy_root", "energy_powder", "heal_powder", "revival_herb",
             "remedy", "fine_remedy", "superb_remedy", "moomoo_milk", "roasted_leek"}


def read_lang(text):
    return {key: json.loads('"' + value + '"') for key, value in
            re.findall(r'"([^"\\]+)"\s*:\s*"((?:[^"\\]|\\.)*)"', text)}


def minecraft_lang():
    assets = pathlib.Path.home() / ".gradle/caches/fabric-loom/assets"
    for index in sorted((assets / "indexes").glob("*.json"), reverse=True):
        entry = json.loads(index.read_text(encoding="utf-8"))["objects"].get("minecraft/lang/ko_kr.json")
        if entry:
            digest = entry["hash"]
            return json.loads((assets / "objects" / digest[:2] / digest).read_text(encoding="utf-8"))
    raise ValueError("Minecraft Korean language asset is required")


def generate(jar, mc_lang):
    with zipfile.ZipFile(jar) as archive:
        lang = mc_lang | read_lang(archive.read("assets/cobblemon/lang/ko_kr.json").decode())
        tags = {}
        recipes = {}
        for path in archive.namelist():
            if not path.endswith(".json"):
                continue
            if path.startswith("data/cobblemon/tags/item/"):
                tags["cobblemon:" + path.removeprefix("data/cobblemon/tags/item/").removesuffix(".json")] = json.loads(archive.read(path))["values"]
            elif path.startswith("data/cobblemon/recipe/") and "/mod_compatibility/" not in path:
                recipes[path] = json.loads(archive.read(path))
        # Preserve recipe IDs: the legacy built-in pack is now an XS yield override.
        overrides = POLICY / "resourcepacks/no_stat_candy_l_xl/data/cobblemon/recipe"
        for path in overrides.rglob("*.json"):
            relative = path.relative_to(overrides).as_posix()
            if not relative.startswith("mod_compatibility/"):
                recipes["data/cobblemon/recipe/" + relative] = json.loads(path.read_text(encoding="utf-8"))

        def tag_items(tag, seen=frozenset()):
            if tag in seen:
                raise ValueError(f"Cyclic item tag: {tag}")
            result = set()
            for value in tags.get(tag, []):
                value = value if isinstance(value, str) else value["id"]
                result.update(tag_items(value[1:], seen | {tag}) if value.startswith("#") else {value})
            return result

        def label(item):
            namespace, name = item.split(":", 1)
            if namespace == "cobblemon" and name.endswith("_rod") and name != "poke_rod":
                return label("cobblemon:" + name.removesuffix("_rod") + "_ball") + " " + lang["item.cobblemon.poke_rod"]
            for kind in ("item", "block"):
                key = f"{kind}.{namespace}.{name}"
                if key in lang:
                    return lang[key]
            raise ValueError(f"Missing Korean item name: {item}")

        def ingredient(value, seen=frozenset()):
            if isinstance(value, list):
                return " 또는 ".join(dict.fromkeys(ingredient(entry, seen) for entry in value))
            if "item" in value:
                return label(value["item"])
            tag = value["tag"]
            if tag in TAG_NAMES:
                return TAG_NAMES[tag]
            if tag in seen:
                raise ValueError(f"Cyclic ingredient tag: {tag}")
            options = tags.get(tag, [])
            if options:
                values = [entry if isinstance(entry, str) else entry["id"] for entry in options]
                return " 또는 ".join(dict.fromkeys(ingredient({"tag": entry[1:]} if entry.startswith("#") else {"item": entry}, seen | {tag}) for entry in values))
            raise ValueError(f"Unresolved ingredient tag: {tag}")

        balls = tag_items("cobblemon:poke_balls")
        held = tag_items("cobblemon:held/is_held_item")
        vitamins = tag_items("cobblemon:vitamins")
        records = []
        for path, recipe in sorted(recipes.items()):
            result = recipe.get("result", {})
            result = {"id": result} if isinstance(result, str) else result
            item = result.get("id", result.get("item", ""))
            name = item.removeprefix("cobblemon:")
            if item in balls:
                category = "balls"
            elif recipe.get("category") == "medicines" or recipe["type"] == "cobblemon:brewing_stand" or item in vitamins or name.startswith("exp_candy_") or name.endswith("_candy") or name.endswith("_mint") or name in SUPPLIES:
                category = "consumables"
            elif item in held or name in EQUIPMENT or name.startswith(("campfire_pot_", "pokedex_")) or name.endswith("_rod"):
                category = "equipment"
            else:
                continue
            if recipe["type"] not in METHODS:
                raise ValueError(f"Unsupported recipe type: {path}")
            grid = []
            if "pattern" in recipe:
                grid = [[ingredient(recipe["key"][key]) if key != " " else "" for key in row.ljust(3)]
                        for row in recipe["pattern"]]
                grid += [["", "", ""]] * (3 - len(grid))
                materials = collections.Counter(cell for row in grid for cell in row if cell)
            elif recipe["type"] == "cobblemon:brewing_stand":
                materials = collections.Counter([ingredient(recipe["bottle"]), ingredient(recipe["input"])])
            elif recipe["type"] == "minecraft:smithing_transform":
                materials = collections.Counter(ingredient(recipe[key]) for key in ("template", "base", "addition"))
            else:
                values = recipe.get("ingredients", [recipe.get("ingredient")])
                materials = collections.Counter(ingredient(value) for value in values)
            record = {"id": path.removeprefix("data/cobblemon/recipe/").removesuffix(".json"),
                      "item": item, "name": label(item), "category": category,
                      "method": METHODS[recipe["type"]], "count": result.get("count", 1),
                      "ingredients": [{"name": material, "count": count} for material, count in materials.items()],
                      "grid": grid}
            if recipe["type"] == "cobblemon:brewing_stand":
                record["steps"] = ["아래 병 슬롯: " + ingredient(recipe["bottle"]), "위 재료 슬롯: " + ingredient(recipe["input"]), "연료 슬롯에 블레이즈 가루를 넣습니다."]
            elif recipe["type"] == "minecraft:smithing_transform":
                record["steps"] = [label_text + ingredient(recipe[key]) for label_text, key in
                                   (("형판: ", "template"), ("기본 장비: ", "base"), ("추가 재료: ", "addition"))]
            if recipe.get("seasoningProcessors"):
                record["note"] = "기본 재료 외에 양념을 넣어 효과를 바꿀 수 있습니다."
            records.append(record)
        crafted = {record["item"] for record in records}
        for item in sorted((balls | {"cobblemon:rare_candy", "cobblemon:lucky_egg"}) - crafted):
            note = "제작 레시피가 없습니다."
            if item == "cobblemon:rare_candy":
                note += " 유적의 금빛 상자나 우두머리 보상에서 얻습니다."
            records.append({"id": item, "item": item, "name": label(item), "category": "balls" if item in balls else "consumables" if item.endswith("rare_candy") else "equipment", "method": "제작 불가", "count": 0, "ingredients": [], "grid": [], "note": note})
        return {"version": "Cobblemon 1.8.1", "recipes": records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cobblemon", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, default=OUTPUT)
    args = parser.parse_args()
    data = generate(args.cobblemon, minecraft_lang())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("/* Generated by works/tools/server-wiki/gen_crafting.py. */\nwindow.WIKI_CRAFTING = " + json.dumps(data, ensure_ascii=False, indent=2) + ";\n", encoding="utf-8")
    print(dict(collections.Counter(recipe["category"] for recipe in data["recipes"])))


if __name__ == "__main__":
    main()
