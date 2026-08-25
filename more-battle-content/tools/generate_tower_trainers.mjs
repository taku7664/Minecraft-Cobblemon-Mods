import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { readFileSync, writeFileSync, readdirSync } from "node:fs";

const moduleDir = dirname(fileURLToPath(import.meta.url));
const moduleRoot = join(moduleDir, "..");
const towerRoot = join(
  moduleRoot,
  "src",
  "main",
  "resources",
  "data",
  "cobblemon_more_battle_content",
  "mbc-battle-tower",
);
const trainerRoot = join(towerRoot, "trainers");
const encounterRoot = join(towerRoot, "encounters");
const pokemonSetRoot = join(towerRoot, "pokemon-sets");
const languageRoot = join(moduleRoot, "src", "main", "resources", "assets", "cobblemon_more_battle_content", "lang");

const styles = [
  "balanced",
  "physical_pressure",
  "special_pressure",
  "setup_sweep",
  "endurance",
  "field_control",
  "speed_control",
  "weather_control",
];
const englishFirstNames = ["Aiden", "Bianca", "Caleb", "Daphne", "Elliot", "Fiona", "Gavin", "Hazel", "Isaac", "Julia", "Kai", "Lena"];
const englishLastNames = ["Brooks", "Carter", "Diaz", "Foster", "Grant", "Hayes", "Kim", "Morgan", "Reed", "Sullivan"];
const koreanSurnames = ["김", "이", "박", "최", "정", "강", "조", "윤", "장", "임"];
const koreanGivenNames = ["도윤", "서준", "하린", "지우", "민재", "수아", "현우", "예린", "태윤", "나연", "시우", "채원"];

const trainerId = (number) => `trainer_${String(number).padStart(3, "0")}`;
const translationKey = (number) => `trainer.cobblemon_more_battle_content.tower_${trainerId(number)}`;
const writeJson = (path, value) => writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, "utf8");

const englishNames = [];
const koreanNames = [];
for (let index = 0; index < 120; index += 1) {
  const englishBase = `${englishFirstNames[index % englishFirstNames.length]} ${englishLastNames[Math.floor(index / englishFirstNames.length)]}`;
  const koreanBase = `${koreanSurnames[Math.floor(index / koreanGivenNames.length)]}${koreanGivenNames[index % koreanGivenNames.length]}`;
  const boss = index >= 96;
  englishNames.push(boss ? `Tower Ace ${englishBase}` : englishBase);
  koreanNames.push(boss ? `타워 에이스 ${koreanBase}` : koreanBase);
}

if (new Set(englishNames).size !== 120 || new Set(koreanNames).size !== 120) {
  throw new Error("Generated trainer names must be unique in both languages");
}

const pokemonSets = readdirSync(pokemonSetRoot)
  .filter((name) => name.endsWith(".json"))
  .flatMap((name) => JSON.parse(readFileSync(join(pokemonSetRoot, name), "utf8")).pokemon_sets);
const speciesFor = (mechanic, tier) => [...new Set(
  pokemonSets
    .filter((set) => set.mechanic_id === mechanic && set.set_tier === tier)
    .map((set) => set.species_id),
)].sort();
const allGroups = ["mega", "dynamax", "tera"].flatMap((mechanic) => [1, 2].map((tier) => speciesFor(mechanic, tier)));
const commonSpecies = allGroups[0].filter((species) => allGroups.every((group) => group.includes(species)));
if (commonSpecies.length < 6) throw new Error(`Only ${commonSpecies.length} species are shared by every regular pool`);

const signatureGroups = (species) => {
  const groups = [];
  for (let first = 0; first < species.length - 2; first += 1) {
    for (let second = first + 1; second < species.length - 1; second += 1) {
      for (let third = second + 1; third < species.length; third += 1) {
        groups.push([species[first], species[second], species[third]]);
      }
    }
  }
  return groups;
};
const regularSignatureGroups = signatureGroups(commonSpecies);
if (regularSignatureGroups.length < 96) throw new Error("Not enough distinct regular signature groups");
const signaturesForTrainer = (number) => {
  if (number <= 96) return regularSignatureGroups[number - 1];
  const bossGroup = Math.floor((number - 97) / 4);
  const mechanic = ["dynamax", "dynamax", "mega", "mega", "tera", "tera"][bossGroup];
  return signatureGroups(speciesFor(mechanic, 2))[(number - 97) % 4];
};

for (let number = 1; number <= 120; number += 1) {
  writeJson(join(trainerRoot, `${trainerId(number)}.json`), {
    schema_version: 1,
    trainers: [
      {
        trainer_id: trainerId(number),
        display_name_key: translationKey(number),
        team_style: styles[(number - 1) % styles.length],
        signature_species_ids: signaturesForTrainer(number),
      },
    ],
  });
}

const regularIds = Array.from({ length: 96 }, (_, index) => index + 1);
const regularByMechanic = {
  mega: regularIds.filter((number) => styles[(number - 1) % styles.length] !== "weather_control"),
  dynamax: regularIds,
  tera: regularIds,
};
const mechanicOrder = ["dynamax", "mega", "tera"];
const formatOrder = ["double", "single"];
const bossIds = (mechanic, format) => {
  const group = mechanicOrder.indexOf(mechanic) * formatOrder.length + formatOrder.indexOf(format);
  return Array.from({ length: 4 }, (_, index) => 97 + group * 4 + index);
};

for (const file of readdirSync(encounterRoot).filter((name) => name.endsWith(".json"))) {
  const path = join(encounterRoot, file);
  const document = JSON.parse(readFileSync(path, "utf8"));
  for (const encounter of document.encounters) {
    const numbers = encounter.opponent_kind === "regular"
      ? regularByMechanic[encounter.mechanic_id]
      : bossIds(encounter.mechanic_id, encounter.format);
    encounter.trainer_ids = numbers.map(trainerId);
  }
  writeJson(path, document);
}

for (const [code, names] of [["en_us", englishNames], ["ko_kr", koreanNames]]) {
  const path = join(languageRoot, `${code}.json`);
  const language = JSON.parse(readFileSync(path, "utf8"));
  for (const key of Object.keys(language)) {
    if (key.startsWith("trainer.cobblemon_more_battle_content.")) delete language[key];
  }
  names.forEach((name, index) => {
    language[translationKey(index + 1)] = name;
  });
  writeJson(path, language);
}

console.log(JSON.stringify({
  trainers: 120,
  regularTrainers: 96,
  dedicatedBosses: 24,
  styles: styles.length,
  commonSignatureSpecies: commonSpecies.length,
  regularPerMechanic: Object.fromEntries(Object.entries(regularByMechanic).map(([key, value]) => [key, value.length])),
  bossesPerMechanicFormat: 4,
}, null, 2));
