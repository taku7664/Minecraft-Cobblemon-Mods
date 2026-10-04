"""Writes the Battle Factory's trainers: 162 named trainers, each dressed in one of 81 RCT Trainers+ looks.

The Factory's opponents rent their team from the round's pool like the challenger, so a trainer is a name, a look,
an AI skill and a pair of battle objectives. Every trainer-class look in the pack, children and swimmers included,
dresses two trainers of the gender it shows, so no look is worn more than twice; neighbours in the list wear
different looks. Only villains (grunts, admins), named characters (leaders, Elite Four, Champions, players) and the
joke NPCs are left out, since a Factory trainer has an ordinary name. Names are Korean full names with an English
counterpart of the same gender. Running it again rewrites every trainer file and the trainers' language keys.

    python tools/facility-trainers/gen_factory_trainers.py [path/to/RCT Trainers+.zip]
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
FACTORY = ROOT / "more-cobblemon-contents-battle-factory/src/main/resources"
TRAINERS = FACTORY / "data/more_cobblemon_contents/mcc-battle-factory/trainers"
LANG = FACTORY / "assets/more_cobblemon_contents_battle_factory/lang"
KEY = "trainer.more_cobblemon_contents.factory_trainer_{:03d}"
DESCRIPTION_KEY = "factory.more_cobblemon_contents.trainer.shared.description"
AI_SUMMARY = "Build a legal team from the current round's complete rental presets and adapt only to public battle information."

MALE_LOOKS = [
    "ace_trainer_abel_04a5", "artist_ismael_0481", "biker_0212", "bird_keeper_alexandra_0326", "black_belt_aaron_0140",
    "cameraman_darryl_0483", "clown_lee_0443", "collector_brady_03ab", "cue_ball_camron_00fb", "cyclist_axel_02fb",
    "dragon_tamer_andre_0388", "engineer_baily_00dc", "fisher_alec_036a", "friendly_coach_0009", "gatekeeper_logan_0253",
    "gentleman_arthur_01a6", "guitarist_arturo_0432", "hiker_alan_00b9", "jogger_craig_03ce", "juggler_dalton_011e",
    "mega_trainer_aloysius_05cc", "pi_carlos_0346", "pokemaniac_ashton_00a8", "pokemon_ranger_kyler_0547",
    "pokemon_trainer_cedric_0396", "police_officer_alex_0330", "rancher_marco_03be", "rich_boy_jason_032b",
    "rocker_luca_011d", "ruin_mamoac_benjamin_025b", "sailor_damian_03eb", "science_society_scientist_rogue_electivire_05df",
    "skier_bjorn_0356", "super_nerd_aavery_00b2", "tamer_cole_0129", "veteran_armando_03fb", "worker_braden_0460",
    "expert_kenlawa_mimikyu_1_05e5", "bug_catcher_01ec", "camper_anthony_0304", "youngster_austin_0303",
    "school_kid_brunore_001e", "tuber_conner_03f8", "ninja_boy_antonio_0359", "swimmer_adrian_035e", "psychic_abigail_0345",
    "pokemon_ranger_allison_0337", "trainer_77_05f6", "crush_kin_mik_and_kia_022d", "sis_and_bro_ava_and_geb_0240",
    "interviewers_oli_03ff",
]
FEMALE_LOOKS = [
    "aroma_lady_alison_0480", "battle_girl_helen_0308", "beauty_bridget_0109", "breeder_alize_0208", "channeler_01c6",
    "cowgirl_shelley_0312", "crush_girl_cyndy_024f", "cyclist_kayla_0301", "friendly_teacher_0007", "idol_grace_0402",
    "lady_gillian_0234", "madame_rebecca_0488", "painter_celina_0232", "parasol_lady_alexa_03e1", "ranger_beth_0255",
    "reporter_kinsey_0401", "skier_andrea_0352", "waitress_kati_03fc", "lass_ali_007b", "picnicker_alicia_009a",
    "school_kid_christine_03f1", "tuber_alexis_022f", "poke_kid_ariel_0444", "swimmer_aubree_0375", "poke_fan_leonard_0479",
    "twins_eli_and_anne_01e4", "young_couple_eve_and_jon_024d", "young_couple_nat_047f", "cool_couple_lex_and_nya_0259",
    "double_team_al_0390",
]

KO_SURNAMES = ["한", "오", "서", "신", "권", "황", "안", "송", "류", "전", "홍", "고", "문", "양", "손", "배", "백", "허", "남", "노"]
KO_MALE = ["도윤", "서준", "하준", "은우", "시우", "지호", "예준", "유준", "수호", "이안", "선우", "주원", "건우", "우진", "현우",
           "지훈", "민준", "도현", "준우", "연우", "태윤", "시윤", "정우", "승우", "윤우", "지환", "은찬", "민재", "준혁", "서진",
           "재윤", "하람", "로운", "태오", "이준", "시후", "준서", "유찬", "준호", "성민", "동현", "민혁", "재민", "현수",
           "지성", "태민", "우현", "승민", "정훈", "상우", "영재"]
KO_FEMALE = ["서아", "하윤", "지안", "아린", "지우", "서윤", "하은", "수아", "윤서", "채원", "지유", "시아", "다은", "예린", "소율",
             "유나", "하린", "은서", "가은", "나은", "민지", "예은", "수빈", "지민", "서연", "다인", "채은", "소윤", "유진",
             "혜원"]
EN_SURNAMES = ["Adams", "Baker", "Brooks", "Clark", "Cole", "Davis", "Ellis", "Foster", "Gray", "Hall", "Hill", "Hughes",
               "King", "Lane", "Long", "Moore", "Nash", "Price", "Scott", "Shaw", "Stone", "Ward", "West", "Wood", "Young",
               "Bell", "Cross", "Fox", "Hart"]
EN_MALE = ["Aaron", "Adrian", "Austin", "Blake", "Brandon", "Carter", "Colin", "Connor", "Daniel", "David", "Dylan", "Eli",
           "Ethan", "Evan", "Felix", "Finn", "Henry", "Hugo", "Ian", "Jack", "Jacob", "Jason", "Jonah", "Kevin", "Leo",
           "Liam", "Lucas", "Marcus", "Mason", "Max", "Miles", "Nathan", "Noah", "Oliver", "Owen", "Peter", "Ryan", "Simon",
           "Tyler", "Victor", "Wesley", "Zach", "Caleb", "Gavin", "Isaac", "Jasper", "Kyle", "Logan", "Nolan", "Reid", "Toby"]
EN_FEMALE = ["Alice", "Amelia", "Anna", "Ava", "Bella", "Chloe", "Clara", "Daisy", "Ella", "Emma", "Grace", "Hannah",
             "Ivy", "Julia", "Kate", "Lily", "Lucy", "Mia", "Nora", "Ruby", "Zoe", "Sophie", "Rose", "Paige", "Olivia",
             "Naomi", "Maya", "Leah", "Hazel", "Fiona"]

# The AI skills and objective pairs the Factory's trainers had, spread over the new list in turn.
SKILLS = [2, 3, 4, 5, 3, 4, 5]
OBJECTIVES = [
    ["speed_control", "setup_sweep"], ["setup_sweep", "preserve_core"], ["field_control", "balanced_pressure"],
    ["field_control", "preserve_core"], ["pivoting", "balanced_pressure"], ["field_control", "setup_sweep"],
    ["balanced_pressure", "preserve_core"], ["balanced_pressure", "focus_fire"], ["speed_control", "pivoting"],
    ["speed_control", "preserve_core"], ["pivoting", "setup_sweep"], ["pivoting", "preserve_core"],
    ["field_control", "speed_control"], ["focus_fire", "preserve_core"], ["setup_sweep", "status_pressure"],
    ["balanced_pressure", "speed_control"], ["field_control", "focus_fire"],
]


def names(given_ko, given_en, count):
    """[count] distinct (Korean, English) full names: each given name with a new surname on every pass."""
    out = []
    for index in range(count):
        round_, slot = divmod(index, len(given_ko))
        ko = KO_SURNAMES[(slot + round_ * 7) % len(KO_SURNAMES)] + given_ko[slot]
        en = f"{given_en[slot % len(given_en)]} {EN_SURNAMES[(slot + round_ * 11) % len(EN_SURNAMES)]}"
        out.append((ko, en))
    assert len({k for k, _ in out}) == count and len({e for _, e in out}) == count
    return out


def main():
    pack = zipfile.ZipFile(Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PACK)

    def slim(look):
        image = Image.open(io.BytesIO(pack.read(f"assets/rctmod/textures/trainers/single/{look}.png"))).convert("RGBA")
        return image.getpixel((54, 20))[3] == 0

    male = names(KO_MALE, EN_MALE, 2 * len(MALE_LOOKS))
    female = names(KO_FEMALE, EN_FEMALE, 2 * len(FEMALE_LOOKS))
    # Each look twice, its two wearers half a list apart; men and women alternate two to one.
    male_looks = MALE_LOOKS + MALE_LOOKS
    female_looks = FEMALE_LOOKS + FEMALE_LOOKS
    queue_m = list(zip(male, male_looks))
    queue_f = list(zip(female, female_looks))
    order = []
    while queue_m or queue_f:
        for take in ("m", "f", "m"):
            queue = queue_m if take == "m" else queue_f
            if queue:
                order.append(queue.pop(0))

    for path in TRAINERS.glob("*.json"):
        path.unlink()
    lang = {"ko_kr": {}, "en_us": {}}
    for index, ((ko, en), look) in enumerate(order, start=1):
        trainer_id = f"factory_trainer_{index:03d}"
        trainer = {
            "trainer_id": trainer_id,
            "display_name_key": KEY.format(index),
            "description_key": DESCRIPTION_KEY,
            "formats": ["single"],
            "weight": 10,
            "ai_skill": SKILLS[(index - 1) % len(SKILLS)],
            "ai_summary": AI_SUMMARY,
            "objectives": OBJECTIVES[(index - 1) % len(OBJECTIVES)],
            "skin": f"rctmod:textures/trainers/single/{look}.png",
            "slim": slim(look),
        }
        text = json.dumps({"schema_version": 1, "trainers": [trainer]}, ensure_ascii=False, indent=2) + "\n"
        (TRAINERS / f"{trainer_id}.json").write_bytes(text.replace("\n", "\r\n").encode("utf-8"))
        lang["ko_kr"][KEY.format(index)] = ko
        lang["en_us"][KEY.format(index)] = en

    for locale, values in lang.items():
        path = LANG / f"{locale}.json"
        raw = path.read_bytes().decode("utf-8")
        newline = "\r\n" if "\r\n" in raw else "\n"
        data = json.loads(raw)
        # The old team-concept keys and any earlier run's trainer keys give way to this list.
        kept = {k: v for k, v in data.items()
                if not k.startswith("factory.more_cobblemon_contents.concept.") and not k.startswith("trainer.more_cobblemon_contents.factory_trainer_")}
        kept.update(values)
        text = "{" + newline + ("," + newline).join(
            f"  {json.dumps(k)}: {json.dumps(v, ensure_ascii=False)}" for k, v in kept.items()) + newline + "}" + newline
        json.loads(text)
        path.write_bytes(text.encode("utf-8"))
    print(f"{len(order)} trainers written: {len(male)} men, {len(female)} women, {len(MALE_LOOKS) + len(FEMALE_LOOKS)} looks")


if __name__ == "__main__":
    main()
