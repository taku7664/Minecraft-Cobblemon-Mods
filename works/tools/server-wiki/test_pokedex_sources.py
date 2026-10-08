import json
import gzip
import struct
import pathlib
import tempfile
import unittest
import zipfile

from pokedex_sources import apply_addition, load_species, server_sources
from gen_pokedex import mod_ids


class SpeciesSourcesTest(unittest.TestCase):
    def test_only_enabled_external_packs_are_loaded_in_saved_order(self):
        def string(text):
            data = text.encode("utf-8")
            return struct.pack(">H", len(data)) + data

        with tempfile.TemporaryDirectory() as directory:
            server = pathlib.Path(directory)
            world = server / "custom_world"
            (world / "datapacks").mkdir(parents=True)
            for name in ("first.zip", "second.zip", "disabled.zip"):
                (world / "datapacks" / name).touch()
            (server / "server.properties").write_text("level-name=custom_world\n", encoding="utf-8")
            packs = ["vanilla", "cobblemon", "file/second.zip", "file/first.zip"]
            nbt = b"\x0a" + string("") + b"\x0a" + string("Data") + b"\x0a" + string("DataPacks")
            nbt += b"\x09" + string("Enabled") + b"\x08" + struct.pack(">i", len(packs))
            nbt += b"".join(string(p) for p in packs) + b"\x00\x00\x00"
            (world / "level.dat").write_bytes(gzip.compress(nbt))
            jar = server / "base.jar"
            self.assertEqual(server_sources(server, {"cobblemon": jar}), [
                (jar, ""), (world / "datapacks/second.zip", ""), (world / "datapacks/first.zip", ""),
            ])

    def test_mod_metadata_accepts_literal_newlines_used_by_mega_showdown(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "mod.jar"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("fabric.mod.json", '{"id":"mega_showdown","description":"line one\nline two"}')
            self.assertEqual(mod_ids(path), {"mega_showdown"})

    def test_addition_enables_species_and_replaces_drop_table(self):
        base = {"implemented": False, "drops": {"entries": [{"item": "old"}]}}
        apply_addition(base, {"implemented": True, "drops": {"entries": [{"item": "new"}]}})
        self.assertTrue(base["implemented"])
        self.assertEqual(base["drops"]["entries"], [{"item": "new"}])

    def test_forms_and_evolutions_append_other_collections_replace(self):
        base = {"forms": [{"name": "Base"}], "evolutions": [{"id": "old"}], "moves": ["old"]}
        apply_addition(base, {"forms": [{"name": "Alola"}], "evolutions": [{"id": "new"}], "moves": ["new"]})
        self.assertEqual([f["name"] for f in base["forms"]], ["Base", "Alola"])
        self.assertEqual([e["id"] for e in base["evolutions"]], ["old", "new"])
        self.assertEqual(base["moves"], ["new"])

    def test_resource_priority_and_all_additions(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [pathlib.Path(directory) / name for name in ["base.jar", "addon.jar", "pack.zip"]]
            contents = [
                {"data/cobblemon/species/generation3/groudon.json": {"implemented": False, "name": "Base"}},
                {"data/cobblemon/species/generation3/groudon.json": {"name": "Override"},
                 "data/cobblemon/species_additions/a.json": {"target": "cobblemon:groudon", "implemented": True}},
                {"data/cobblemon/species_additions/b.json": {"target": "cobblemon:groudon", "drops": {"amount": 5}},
                 "data/cobblemon/species_additions/a.json": {"target": "cobblemon:groudon", "implemented": True}},
            ]
            for p, entries in zip(paths, contents):
                with zipfile.ZipFile(p, "w") as archive:
                    for name, data in entries.items():
                        archive.writestr(name, json.dumps(data))
            species = load_species([(p, "") for p in paths])
            data, generation = species["groudon"]
            self.assertEqual(generation, "generation3")
            self.assertEqual(data["name"], "Override")
            self.assertTrue(data["implemented"])
            self.assertEqual(data["drops"]["amount"], 5)

    def test_unknown_addition_target_fails_visibly(self):
        with tempfile.TemporaryDirectory() as directory:
            p = pathlib.Path(directory) / "pack"
            (p / "data/cobblemon/species_additions").mkdir(parents=True)
            (p / "data/cobblemon/species_additions/bad.json").write_text(
                '{"target":"cobblemon:missing","implemented":true}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "missing"):
                load_species([(p, "")])


if __name__ == "__main__":
    unittest.main()
