import unittest

from generate_tm_preset import egg_moves, parse_champions, to_id


class EggMovePolicyTest(unittest.TestCase):
    def test_latest_generation_is_not_latest_generation_with_egg_moves(self):
        dex = {"testmon": {}}
        learnsets = {"testmon": {"tackle": ["9L1"], "oldmove": ["8E"]}}
        self.assertEqual((9, set()), egg_moves("testmon", dex, learnsets, {}))

    def test_evolution_inherits_eggs_from_prevo_in_the_same_generation(self):
        dex = {"baby": {}, "adult": {"prevo": "Baby"}}
        learnsets = {"baby": {"wish": ["9E"], "oldmove": ["8E"]},
                    "adult": {"tackle": ["9L1"]}}
        self.assertEqual((9, {"wish"}), egg_moves("adult", dex, learnsets, {}))

    def test_bdsp_is_included_when_it_is_the_last_mainline_generation(self):
        dex = {"testmon": {}}
        old = {"testmon": {"oldmove": ["7E"]}}
        bdsp = {"testmon": {"wish": ["8E"], "tackle": ["8L1"]}}
        self.assertEqual((8, {"wish"}), egg_moves("testmon", dex, old, bdsp))

    def test_form_with_own_learnset_does_not_inherit_unrelated_base_eggs(self):
        dex = {"testmon": {}, "testmonregional": {"baseSpecies": "Testmon", "forme": "Regional"}}
        learnsets = {"testmon": {"wrongmove": ["9E"]},
                    "testmonregional": {"rightmove": ["9E"]}}
        self.assertEqual((9, {"rightmove"}), egg_moves("testmonregional", dex, learnsets, {}))

    def test_legends_sources_are_not_mainline_sources(self):
        dex = {"testmon": {}}
        learnsets = {"testmon": {"wish": ["8E"], "newmove": ["9V"]}}
        self.assertEqual((8, {"wish"}), egg_moves("testmon", dex, learnsets, {}))

    def test_gigantamax_uses_generation_eight_even_if_its_base_returned_in_nine(self):
        dex = {"testmon": {}}
        learnsets = {"testmon": {"newmove": ["9E"], "oldmove": ["8E"], "tackle": ["9L1", "8L1"]}}
        self.assertEqual((8, {"oldmove"}), egg_moves("testmon", dex, learnsets, {}, max_generation=8))

    def test_champions_dump_and_accented_names(self):
        dump = "0003 - Venusaur\n80/82/83/100/100/80\nGrass/Poison\nOvergrow\nMoves:\n- Sleep Powder\n- Leech Seed\n"
        self.assertEqual({"venusaur": {"sleeppowder", "leechseed"}}, parse_champions(dump))
        self.assertEqual("vivillonpokeball", to_id("Vivillon-Poké Ball"))

    def test_champions_disabled_moves_are_excluded(self):
        dump = "0003 - Venusaur\n80/82/83/100/100/80\nGrass/Poison\nOvergrow\nMoves:\n- Sleep Powder\n- Magical Leaf\n"
        self.assertEqual({"venusaur": {"sleeppowder"}}, parse_champions(dump, {"sleeppowder"}))


if __name__ == "__main__":
    unittest.main()
