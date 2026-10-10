import collections
import json
import pathlib
import tempfile
import unittest
import zipfile

import gen_crafting


class CraftingGuideTest(unittest.TestCase):
    def test_shaped_quantities_nested_tags_brewing_and_policy_override(self):
        lang = {"item.cobblemon." + item: name for item, name in {
            "poke_ball": "몬스터볼", "exp_candy_xs": "경험사탕XS", "ether": "PP에이드",
            "medicinal_brew": "약용 물약", "leppa_berry": "과사열매", "rare_candy": "이상한사탕",
            "lucky_egg": "행복의알", "red_apricorn": "빨간 규토리"}.items()}
        files = {
            "assets/cobblemon/lang/ko_kr.json": lang,
            "data/cobblemon/tags/item/poke_balls.json": {"values": ["cobblemon:poke_ball"]},
            "data/cobblemon/tags/item/ball_material.json": {"values": ["#c:ingots/copper"]},
            "data/cobblemon/recipe/poke_ball.json": {
                "type": "minecraft:crafting_shaped", "pattern": [" A ", "ACA", " A "],
                "key": {"A": {"item": "cobblemon:red_apricorn"}, "C": {"tag": "cobblemon:ball_material"}},
                "result": {"id": "cobblemon:poke_ball", "count": 4}},
            "data/cobblemon/recipe/brewing_stand/ether.json": {
                "type": "cobblemon:brewing_stand", "bottle": {"item": "cobblemon:medicinal_brew"},
                "input": {"item": "cobblemon:leppa_berry"}, "result": {"id": "cobblemon:ether"}},
            "data/cobblemon/recipe/campfire_pot/exp_candy_xs.json": {
                "type": "cobblemon:cooking_pot_shapeless", "category": "medicines",
                "ingredients": [{"item": "minecraft:sculk"}, {"item": "minecraft:honeycomb"}],
                "result": {"id": "cobblemon:exp_candy_xs"}},
        }
        with tempfile.TemporaryDirectory() as directory:
            jar = pathlib.Path(directory) / "recipes.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                for name, content in files.items():
                    archive.writestr(name, json.dumps(content))
            data = gen_crafting.generate(jar, {"item.minecraft.honeycomb": "벌집 조각", "block.minecraft.sculk": "스컬크"})
        recipes = {row["id"]: row for row in data["recipes"]}
        ball = recipes["poke_ball"]
        self.assertEqual(4, ball["count"])
        self.assertEqual([["", "빨간 규토리", ""], ["빨간 규토리", "구리 주괴", "빨간 규토리"], ["", "빨간 규토리", ""]], ball["grid"])
        self.assertEqual([{"name": "빨간 규토리", "count": 4}, {"name": "구리 주괴", "count": 1}], ball["ingredients"])
        self.assertEqual(3, recipes["campfire_pot/exp_candy_xs"]["count"])
        self.assertEqual("아래 병 슬롯: 약용 물약", recipes["brewing_stand/ether"]["steps"][0])

    def test_published_guide_has_core_items_and_consistent_grids(self):
        text = gen_crafting.OUTPUT.read_text(encoding="utf-8")
        data = json.loads(text.split("window.WIKI_CRAFTING = ", 1)[1].removesuffix(";\n"))
        items = {row["item"] for row in data["recipes"]}
        for item in ("pc", "healing_machine", "pasture", "fossil_analyzer", "restoration_tank", "exp_share", "ether", "max_ether", "elixir", "max_elixir", "poke_ball", "great_ball", "ultra_ball", "master_ball", "revive", "max_revive", "pp_up", "protein", "health_candy", "fire_stone", "water_stone", "link_cable"):
            self.assertIn("cobblemon:" + item, items)
        for row in data["recipes"]:
            if row["grid"]:
                self.assertEqual([3, 3, 3], [len(line) for line in row["grid"]])
                counts = collections.Counter(cell for line in row["grid"] for cell in line if cell)
                self.assertEqual(counts, {entry["name"]: entry["count"] for entry in row["ingredients"]})
            self.assertNotIn("fabric:", row["name"])
            self.assertNotIn("cobblemon:", row["name"])
        xs = next(row for row in data["recipes"] if row["id"] == "campfire_pot/exp_candy_xs")
        self.assertEqual(3, xs["count"])
        splits = {row["id"]: row["count"] for row in data["recipes"] if "_from_exp_candy_" in row["id"]}
        self.assertEqual({"exp_candy_xs_from_exp_candy_s": 6, "exp_candy_s_from_exp_candy_m": 3,
                          "exp_candy_m_from_exp_candy_l": 3, "exp_candy_l_from_exp_candy_xl": 3}, splits)
        rare = next(row for row in data["recipes"] if row["item"] == "cobblemon:rare_candy")
        self.assertEqual("제작 불가", rare["method"])


if __name__ == "__main__":
    unittest.main()
