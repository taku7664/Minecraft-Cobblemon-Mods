import unittest

from gen_pokedex import drop_table, form_signature


class DropDataTest(unittest.TestCase):
    def test_missing_table_is_empty(self):
        self.assertEqual(drop_table({}, lambda item: item), {"amount": 1, "entries": []})

    def test_defaults_range_and_fractional_chance(self):
        table = drop_table({"drops": {"amount": 3, "entries": [
            {"item": "minecraft:salmon"},
            {"item": "minecraft:gold_nugget", "quantityRange": "0-1"},
            {"item": "cobblemon:quick_claw", "percentage": 2.5},
        ]}}, lambda item: "번역:" + item)
        self.assertEqual(table["amount"], 3)
        self.assertEqual(table["entries"][0], {
            "id": "minecraft:salmon", "name": "번역:minecraft:salmon", "quantity": 1, "percentage": 100,
        })
        self.assertEqual(table["entries"][1]["quantity"], "0-1")
        self.assertEqual(table["entries"][2]["percentage"], 2.5)

    def test_explicit_zero_is_preserved(self):
        table = drop_table({"drops": {"amount": 0, "entries": [
            {"item": "minecraft:stick", "quantity": 0, "percentage": 0},
        ]}}, lambda item: item)
        self.assertEqual(table["amount"], 0)
        self.assertEqual(table["entries"][0]["quantity"], 0)
        self.assertEqual(table["entries"][0]["percentage"], 0)

    def test_drop_only_form_difference_is_kept(self):
        base = {"types": [], "stats": [], "abilities": [], "learn": {}, "drops": {"entries": []}}
        other = dict(base, drops={"entries": [{"id": "minecraft:stick"}]})
        self.assertNotEqual(form_signature(base), form_signature(other))


if __name__ == "__main__":
    unittest.main()
