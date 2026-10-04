"""Gives every Battle Tower trainer an RCT Trainers+ skin. The Battle Factory's trainers, looks included, come from
gen_factory_trainers.py.

Writes `skin` and `slim` into each trainer's JSON. RCT Trainers+ ships about one look per trainer class (its many
files per class are copies of one image), so a trainer gets a look, not a personal skin: Tower Aces one of the strong
classes, everyone else one of the grown-up classes, matching the gender of the trainer's English name. Trainers take
the looks of their gender in turn, so neighbours differ and every look is used about as often. Named characters,
villains, children and swimmers are left out.

    python tools/facility-trainers/gen_facility_skins.py [path/to/RCT Trainers+.zip]
"""
import io
import json
import os
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PACK = ROOT / "develop-product/client/resourcepacks/RCT Trainers+ [1.7] v2.2.zip"
TOWER = ROOT / "more-cobblemon-contents-battle-tower/src/main/resources"

FEMALE_NAMES = {"Alice", "Amy", "Anna", "Bianca", "Claire", "Daphne", "Emily", "Fiona", "Hazel", "Jenna", "Julia", "Kate",
                "Laura", "Leah", "Lena", "Lucy", "Maya", "Nina", "Ruby", "Sarah", "Zoe"}

# One file per look; the gender is the one the image shows.
ACE_MALE = ["ace_trainer_abel_04a5", "mega_trainer_aloysius_05cc", "dragon_tamer_andre_0388", "veteran_armando_03fb",
            "black_belt_aaron_0140"]
ACE_FEMALE = ["battle_girl_helen_0308", "psychic_abigail_0345", "beauty_bridget_0109", "ranger_beth_0255",
              "crush_girl_cyndy_024f"]
MALE = ["gentleman_arthur_01a6", "hiker_alan_00b9", "sailor_damian_03eb", "gambler_darian_0105", "biker_alex_00c6",
        "cue_ball_camron_00fb", "cyclist_axel_02fb", "engineer_baily_00dc", "fisher_alec_036a", "juggler_dalton_011e",
        "pokemon_ranger_kyler_0547", "police_officer_alex_0330", "rancher_marco_03be", "rocker_luca_011d",
        "super_nerd_aavery_00b2", "worker_braden_0460", "collector_brady_03ab", "black_belt_aaron_0140",
        "veteran_armando_03fb", "ace_trainer_abel_04a5"]
FEMALE = ["aroma_lady_alison_0480", "battle_girl_helen_0308", "beauty_bridget_0109", "breeder_alize_0208",
          "channeler_amanda_01ce", "cowgirl_shelley_0312", "crush_girl_cyndy_024f", "cyclist_kayla_0301",
          "lady_gillian_0234", "madame_rebecca_0488", "painter_celina_0232", "parasol_lady_alexa_03e1",
          "psychic_abigail_0345", "ranger_beth_0255", "skier_andrea_0352", "waitress_kati_03fc"]


def slim_flags(pack_path, files):
    """Whether each file has slim arms, and a check that the pack has it at all."""
    flags = {}
    with zipfile.ZipFile(pack_path) as pack:
        for file in files:
            image = Image.open(io.BytesIO(pack.read(f"assets/rctmod/textures/trainers/single/{file}.png"))).convert("RGBA")
            flags[file] = image.getpixel((54, 20))[3] == 0
    return flags


def trainers(resources, directory):
    """(path, raw text, document, trainer, English name) for every trainer file of a facility."""
    lang = json.loads(next((resources / "assets").glob("*/lang/en_us.json")).read_text(encoding="utf-8"))
    for path in sorted((resources / "data/more_cobblemon_contents" / directory / "trainers").glob("*.json")):
        raw = path.read_bytes().decode("utf-8")
        document = json.loads(raw)
        for trainer in document["trainers"]:
            yield path, raw, document, trainer, lang[trainer["display_name_key"]]


def main():
    pack = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PACK
    slim = slim_flags(pack, set(ACE_MALE + ACE_FEMALE + MALE + FEMALE))
    turns = {key: 0 for key in ("ace_male", "ace_female", "male", "female")}
    looks = {"ace_male": ACE_MALE, "ace_female": ACE_FEMALE, "male": MALE, "female": FEMALE}
    written = {}
    entries = list(trainers(TOWER, "mcc-battle-tower"))
    for path, raw, document, trainer, name in entries:
        ace = name.startswith("Tower Ace ")
        female = name.removeprefix("Tower Ace ").split()[0] in FEMALE_NAMES
        key = ("ace_" if ace else "") + ("female" if female else "male")
        file = looks[key][turns[key] % len(looks[key])]
        turns[key] += 1
        trainer["skin"] = f"rctmod:textures/trainers/single/{file}.png"
        trainer["slim"] = slim[file]
        written[path] = (raw, document)

    for path, (raw, document) in written.items():
        text = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
        if "\r\n" in raw:
            text = text.replace("\n", "\r\n")
        path.write_bytes(text.encode("utf-8"))
    print(f"{len(entries)} trainers dressed: {turns}")


if __name__ == "__main__":
    main()
