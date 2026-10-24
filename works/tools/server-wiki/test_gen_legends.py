import json
import pathlib
import unittest

from gen_legends import spawn_dimensions


class LegendDimensionsTest(unittest.TestCase):
    def test_explicit_dimension_overrides_biome_name_inference(self):
        self.assertEqual(["오버월드"], spawn_dimensions({
            "dimensions": ["minecraft:overworld"], "biomes": ["#cobblemon:is_end"],
        }))

    def test_multiple_vanilla_dimensions_do_not_duplicate(self):
        self.assertEqual(["오버월드", "네더", "엔드"], spawn_dimensions({
            "dimensions": ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"],
            "biomes": ["minecraft:soul_sand_valley", "minecraft:the_end", "#cobblemon:is_spooky"],
        }))

    def test_paradox_dimension_is_preserved(self):
        self.assertEqual(["고대 차원"], spawn_dimensions({"dimensions": ["cobblemon_dimensions:ancient"]}))
        self.assertEqual(["미래 차원"], spawn_dimensions({"dimensions": ["cobblemon_dimensions:future"]}))

    def test_legacy_spawn_without_dimension_keeps_biome_inference(self):
        self.assertEqual(["오버월드", "엔드"], spawn_dimensions({
            "biomes": ["minecraft:dark_forest", "minecraft:the_end"],
        }))

    def test_generated_guide_matches_all_source_dimensions_and_aquatic_conditions(self):
        repo = pathlib.Path(__file__).resolve().parents[3]
        pool = repo / "works/mods/jbro-policy/src/main/resources/resourcepacks/legendary_spawns/data/jbro_policy/spawn_pool_world/legendary_wild_spawns.json"
        guide = (repo / "server-wiki/assets/data/legends.js").read_text(encoding="utf-8")
        entries = {entry["id"]: entry for entry in json.loads(guide.split("window.WIKI_LEGENDS = ", 1)[1].rsplit(";", 1)[0])}
        spawns = json.loads(pool.read_text(encoding="utf-8"))["spawns"]
        self.assertEqual(103, len(entries))
        self.assertEqual({spawn["id"].removeprefix("jbro-legendary-") for spawn in spawns}, set(entries))
        for spawn in spawns:
            entry = entries[spawn["id"].removeprefix("jbro-legendary-")]
            self.assertEqual(spawn_dimensions(spawn["condition"]), entry["dimensions"], entry["id"])
            if spawn["spawnablePositionType"] != "grounded":
                self.assertEqual([], entry["exclusions"], entry["id"])
                self.assertIn("용암에서" if entry["id"] == "chiyu" else "물에서", [c["text"] for c in entry["conditions"]], entry["id"])
        self.assertEqual("용암 위", entries["chiyu"]["position"])


if __name__ == "__main__":
    unittest.main()
