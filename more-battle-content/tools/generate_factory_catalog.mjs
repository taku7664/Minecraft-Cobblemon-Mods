import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import {
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputRoot = resolve(
  process.argv[2] ?? join(projectRoot, "src/main/resources/data/cobblemon_more_battle_content/mbc-battle-factory"),
);
const trainerDirectory = join(outputRoot, "trainers");
const rentalSetDirectory = join(outputRoot, "rental-sets");
const cobblemonJar = resolve(process.argv[3] ?? findCobblemonJar());
const work = mkdtempSync(join(tmpdir(), "mbc-factory-catalog-"));

function main() {
try {
  execFileSync("jar", ["xf", cobblemonJar, "data/cobblemon/showdown.zip", "data/cobblemon/species"], { cwd: work });
  const showdownRoot = join(work, "showdown");
  mkdirSync(showdownRoot);
  execFileSync(
    "jar",
    ["xf", join(work, "data/cobblemon/showdown.zip"), "data/random-sets.json", "data/abilities.js", "data/moves.js"],
    { cwd: showdownRoot },
  );

  const randomSets = JSON.parse(readFileSync(join(showdownRoot, "data/random-sets.json"), "utf8"));
  const abilities = require(join(showdownRoot, "data/abilities.js")).Abilities;
  const moves = require(join(showdownRoot, "data/moves.js")).Moves;
  const speciesIndex = indexCobblemonSpecies(join(work, "data/cobblemon/species"));
  const trainers = readTrainerFragments(trainerDirectory);
  const sets = [];
  const skipped = [];

  for (const showdownKey of Object.keys(randomSets).sort()) {
    const species = speciesIndex.get(showdownKey);
    if (!species) {
      skipped.push(`${showdownKey}: no exact implemented final Cobblemon species or form`);
      continue;
    }
    const roles = (randomSets[showdownKey].sets ?? [])
      .map((role) => ({
        name: role.role ?? "Generalist",
        moves: unique((role.movepool ?? []).map(toId).filter((id) => species.learnset.has(id) && moves[id])),
      }))
      .filter((role) => role.moves.length >= 4);
    if (roles.length === 0) {
      skipped.push(`${showdownKey}: no four-move role survives the exact learnset check`);
      continue;
    }

    const combinations = moveCombinations(roles, species, moves);
    const level = Number(randomSets[showdownKey].level ?? 84);
    const baseWindow = startingWindow(level);
    for (let preset = 0; preset < 4; preset += 1) {
      const candidate = combinations[preset % combinations.length];
      const fixedMoves = candidate.moves;
      const profile = battleProfile(species, candidate.name, fixedMoves, moves);
      const window = WINDOWS[Math.min(WINDOWS.length - 1, baseWindow + preset)];
      const item = fixedItem(profile, species, preset, showdownKey);
      sets.push({
        set_id: `${showdownKey}_preset_${preset + 1}`,
        pool_group: window.group,
        variant: window.variant,
        species_id: `cobblemon:${species.speciesId}`,
        ...(species.formId ? { form_id: species.formId } : {}),
        ability_id: `cobblemon:${bestAbility(species.abilities, abilities, preset)}`,
        held_item_id: item,
        nature_id: `cobblemon:${profile.nature}`,
        moves: fixedMoves.map((move) => `cobblemon:${move}`),
        roles: profile.roles,
        preferred_move_ids: profile.preferredMoves.map((move) => `cobblemon:${move}`),
        lead_priority: profile.leadPriority,
        preservation_priority: profile.preservationPriority,
        evs: profile.evs,
      });
    }
  }

  const catalog = { trainers, sets };
  validateGeneratedCatalog(catalog);
  writeRentalSetFragments(rentalSetDirectory, sets);
  process.stdout.write(
    JSON.stringify(
      {
        sourceJar: basename(cobblemonJar),
        trainers: trainers.length,
        rentalSets: sets.length,
        speciesAndForms: new Set(sets.map((set) => `${set.species_id}|${set.form_id ?? ""}`)).size,
        poolSizes: Object.fromEntries(
          WINDOWS.map((window) => [
            window.key,
            sets.filter((set) => set.pool_group === window.group && set.variant === window.variant).length,
          ]),
        ),
        skipped,
      },
      null,
      2,
    ) + "\n",
  );
} finally {
  rmSync(work, { recursive: true, force: true });
}
}

function findCobblemonJar() {
  const userProfile = process.env.USERPROFILE;
  if (!userProfile) throw new Error("USERPROFILE is unavailable; pass the Cobblemon JAR as the second argument");
  const versionRoot = join(userProfile, ".gradle/caches/modules-2/files-2.1/maven.modrinth/cobblemon/1.7.3");
  const jars = walk(versionRoot).filter((path) => path.endsWith(".jar"));
  if (jars.length !== 1) throw new Error(`Expected one Cobblemon 1.7.3 JAR, found ${jars.length}; pass an explicit path`);
  return jars[0];
}

function walk(root) {
  if (!existsSync(root)) return [];
  return readdirSync(root).flatMap((name) => {
    const path = join(root, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

function indexCobblemonSpecies(root) {
  const index = new Map();
  for (const path of walk(root).filter((entry) => entry.endsWith(".json"))) {
    const data = JSON.parse(readFileSync(path, "utf8"));
    if (data.implemented === false) continue;
    const speciesId = basename(path, ".json");
    addSpecies(index, toId(data.name), speciesId, null, data, null);
    for (const form of data.forms ?? []) {
      if (form.battleOnly === true || !form.name) continue;
      const formId = form.name.toLowerCase();
      if (!/^[a-z0-9_.-]+$/.test(formId)) continue;
      addSpecies(index, toId(`${data.name}${form.name}`), speciesId, formId, data, form);
    }
  }
  return index;
}

function addSpecies(index, key, speciesId, formId, base, form) {
  if (!key || index.has(key)) return;
  const learnset = new Set(
    [...(base.moves ?? []), ...(form?.moves ?? [])].map((entry) => toId(entry.split(":").at(-1))).filter(Boolean),
  );
  const rawAbilities = form && Object.hasOwn(form, "abilities") ? form.abilities : base.abilities;
  const stats = form?.baseStats ?? base.baseStats;
  index.set(key, {
    speciesId,
    formId,
    learnset,
    abilities: unique((rawAbilities ?? []).map((ability) => ability.replace(/^h:/, ""))),
    stats: {
      hp: Number(stats.hp),
      attack: Number(stats.attack),
      defense: Number(stats.defence),
      specialAttack: Number(stats.special_attack),
      specialDefense: Number(stats.special_defence),
      speed: Number(stats.speed),
    },
    primaryType: form?.primaryType ?? base.primaryType,
    secondaryType: form?.secondaryType ?? base.secondaryType ?? null,
  });
}

function readTrainerFragments(directory) {
  const trainers = readdirSync(directory)
    .filter((name) => name.endsWith(".json"))
    .sort()
    .flatMap((name) => {
      const root = JSON.parse(readFileSync(join(directory, name), "utf8"));
      if (root.schema_version !== 1 || !Array.isArray(root.trainers)) {
        throw new Error(`Invalid Factory trainer fragment: ${name}`);
      }
      return root.trainers;
    });
  if (new Set(trainers.map((trainer) => trainer.trainer_id)).size !== trainers.length) {
    throw new Error("Factory trainer fragments contain duplicate trainer IDs");
  }
  return trainers;
}

function writeRentalSetFragments(directory, sets) {
  rmSync(directory, { recursive: true, force: true });
  mkdirSync(directory, { recursive: true });
  const groups = new Map();
  for (const set of sets) {
    const group = groups.get(set.species_id) ?? [];
    group.push(set);
    groups.set(set.species_id, group);
  }
  for (const [speciesId, speciesSets] of [...groups].sort(([left], [right]) => left.localeCompare(right))) {
    const fileName = speciesId.toLowerCase().replace(/[^a-z0-9._-]+/g, "_");
    const path = join(directory, `${fileName}.json`);
    writeFileSync(path, `${JSON.stringify({ schema_version: 4, rental_sets: speciesSets }, null, 2)}\n`, "utf8");
    JSON.parse(readFileSync(path, "utf8"));
  }
}

function moveCombinations(roles, species, moveData) {
  const byRole = [];
  const seen = new Set();
  for (const role of roles) {
    const candidates = combinationsOfFour(role.moves)
      .map((selected) => ({ name: role.name, moves: selected, score: scoreMoveSet(selected, role.name, species, moveData) }))
      .sort((left, right) => right.score - left.score || left.moves.join("|").localeCompare(right.moves.join("|")))
      .slice(0, 6);
    byRole.push(candidates);
  }
  const candidates = [];
  for (let depth = 0; depth < 6; depth += 1) {
    for (const roleCandidates of byRole) {
      const candidate = roleCandidates[depth];
      if (!candidate) continue;
      const signature = [...candidate.moves].sort().join("|");
      if (seen.has(signature)) continue;
      seen.add(signature);
      candidates.push(candidate);
    }
  }
  return candidates;
}

function combinationsOfFour(values) {
  const result = [];
  for (let a = 0; a < values.length - 3; a += 1) {
    for (let b = a + 1; b < values.length - 2; b += 1) {
      for (let c = b + 1; c < values.length - 1; c += 1) {
        for (let d = c + 1; d < values.length; d += 1) result.push([values[a], values[b], values[c], values[d]]);
      }
    }
  }
  return result;
}

function scoreMoveSet(selected, roleName, species, moveData) {
  const role = roleName.toLowerCase();
  const data = selected.map((id) => moveData[id]);
  const attacks = data.filter((move) => move.category !== "Status" && move.basePower > 0);
  const stabTypes = new Set([species.primaryType, species.secondaryType].filter(Boolean).map((type) => type.toLowerCase()));
  const stab = attacks.filter((move) => stabTypes.has(move.type.toLowerCase())).length;
  const attackTypes = new Set(attacks.map((move) => move.type)).size;
  const setup = selected.some((move) => SETUP_MOVES.has(move));
  const recovery = selected.some((move) => RECOVERY_MOVES.has(move));
  const hazards = selected.filter((move) => HAZARD_MOVES.has(move)).length;
  const supportRole = /(support|defensive|wall)/.test(role);
  let score = stab * 8 + attackTypes * 3 + attacks.length * 2;
  if (attacks.length < (supportRole ? 1 : 2)) score -= 30;
  if (setup && attacks.length >= 2) score += 8;
  if (recovery && attacks.length >= 1) score += supportRole ? 7 : 3;
  if (hazards > 1) score -= 7 * (hazards - 1);
  if (supportRole && data.filter((move) => move.category === "Status").length >= 2) score += 4;
  return score;
}

function battleProfile(species, roleName, selectedMoves, moveData) {
  const role = roleName.toLowerCase();
  const selectedData = selectedMoves.map((id) => moveData[id]);
  const physical = selectedData.filter((move) => move.category === "Physical" && move.basePower > 0).length;
  const special = selectedData.filter((move) => move.category === "Special" && move.basePower > 0).length;
  const attacking = physical + special;
  const defensive = /(bulky|defensive|wall|support)/.test(role);
  const fast = /(fast|sweeper|cleaner)/.test(role);
  const setup = selectedMoves.some((move) => SETUP_MOVES.has(move));
  const pivot = selectedMoves.some((move) => PIVOT_MOVES.has(move));
  const speedControl = selectedMoves.some((move) => SPEED_CONTROL_MOVES.has(move));
  const support = selectedMoves.some((move) => SUPPORT_MOVES.has(move));
  const mainPhysical = physical > special || (physical === special && species.stats.attack >= species.stats.specialAttack);
  let nature;
  let evs;
  if (defensive && attacking <= 2) {
    const physicalWall = species.stats.defense <= species.stats.specialDefense;
    nature = mainPhysical
      ? physicalWall ? "impish" : "careful"
      : physicalWall ? "bold" : "calm";
    evs = spread("hp", physicalWall ? "defense" : "special_defense", mainPhysical ? "attack" : "special_attack");
  } else if (mainPhysical) {
    nature = fast ? "jolly" : "adamant";
    evs = spread("attack", "speed", "hp");
  } else {
    nature = fast ? "timid" : "modest";
    evs = spread("special_attack", "speed", "hp");
  }
  const roles = [];
  if (setup) roles.push("wallbreaker", "cleaner");
  if (pivot) roles.push("pivot");
  if (speedControl) roles.push("speed_control");
  if (support) roles.push("field_support", "disruptor");
  if (roles.length === 0) roles.push(defensive ? "weakness_cover" : fast ? "cleaner" : "wallbreaker");
  const preferredMoves = selectedMoves
    .filter((move) => (moveData[move]?.basePower ?? 0) >= 70 || SETUP_MOVES.has(move) || PIVOT_MOVES.has(move))
    .slice(0, 2);
  return {
    nature,
    evs,
    roles: unique(roles),
    preferredMoves: preferredMoves.length ? preferredMoves : selectedMoves.slice(0, 1),
    leadPriority: speedControl || support ? 85 : pivot ? 70 : fast ? 60 : 40,
    preservationPriority: setup || /sweeper|cleaner/.test(role) ? 85 : defensive ? 70 : 60,
    attackingMoves: attacking,
    mainPhysical,
    defensive,
  };
}

function spread(first, second, remainder) {
  const result = { hp: 0, attack: 0, defense: 0, special_attack: 0, special_defense: 0, speed: 0 };
  result[first] = 252;
  result[second] = 252;
  result[remainder] = 4;
  return result;
}

function fixedItem(profile, species, preset, key) {
  if (key === "porygon2" || key === "chansey") return "cobblemon:eviolite";
  if (key === "pikachu") return "cobblemon:light_ball";
  const offensive = profile.mainPhysical ? "cobblemon:choice_band" : "cobblemon:choice_specs";
  const palettes = [
    ["cobblemon:sitrus_berry", "cobblemon:expert_belt", "cobblemon:wide_lens", "cobblemon:focus_sash", "cobblemon:assault_vest", "cobblemon:leftovers"],
    ["cobblemon:life_orb", "cobblemon:rocky_helmet", "cobblemon:heavy_duty_boots", "cobblemon:clear_amulet", "cobblemon:weakness_policy", "cobblemon:white_herb"],
    [offensive, "cobblemon:choice_scarf", "cobblemon:focus_sash", "cobblemon:life_orb", "cobblemon:assault_vest", "cobblemon:expert_belt"],
    [offensive, "cobblemon:choice_scarf", "cobblemon:life_orb", "cobblemon:weakness_policy", "cobblemon:expert_belt", "cobblemon:focus_sash"],
  ];
  let palette = palettes[preset];
  if (profile.attackingMoves < 4) {
    palette = ["cobblemon:leftovers", "cobblemon:sitrus_berry", "cobblemon:rocky_helmet", "cobblemon:heavy_duty_boots", "cobblemon:focus_sash", "cobblemon:mental_herb"];
  } else if (profile.defensive) {
    palette = ["cobblemon:assault_vest", "cobblemon:leftovers", "cobblemon:sitrus_berry", "cobblemon:rocky_helmet", "cobblemon:heavy_duty_boots", "cobblemon:expert_belt"];
  }
  return palette[stableHash(`${key}:${species.primaryType}:${preset}`) % palette.length];
}

function bestAbility(speciesAbilities, abilityData, preset) {
  if (speciesAbilities.length === 0) throw new Error("A generated species has no ability");
  const ordered = [...speciesAbilities].sort((left, right) => {
    const rating = Number(abilityData[toId(right)]?.rating ?? 0) - Number(abilityData[toId(left)]?.rating ?? 0);
    return rating || left.localeCompare(right);
  });
  return ordered[Math.min(preset === 0 ? 1 : 0, ordered.length - 1)];
}

function startingWindow(level) {
  if (level >= 88) return 0;
  if (level >= 85) return 1;
  if (level >= 82) return 2;
  if (level >= 79) return 3;
  if (level >= 76) return 4;
  if (level >= 72) return 5;
  return 6;
}

function validateGeneratedCatalog(catalog) {
  if (catalog.trainers.length < 80) throw new Error(`Expected at least 80 trainers, found ${catalog.trainers.length}`);
  if (catalog.sets.length < 2000) throw new Error(`Expected at least 2000 complete rental sets, found ${catalog.sets.length}`);
  const ids = new Set();
  for (const set of catalog.sets) {
    if (ids.has(set.set_id)) throw new Error(`Duplicate set ID: ${set.set_id}`);
    ids.add(set.set_id);
    if (set.moves.length !== 4 || new Set(set.moves).size !== 4) throw new Error(`${set.set_id} is not a fixed four-move set`);
    if (!set.held_item_id || !set.nature_id || !set.ability_id) throw new Error(`${set.set_id} is incomplete`);
  }
  for (const window of WINDOWS) {
    const pool = catalog.sets.filter((set) => set.pool_group === window.group && set.variant === window.variant);
    if (new Set(pool.map((set) => set.species_id)).size < 24) throw new Error(`${window.key} has fewer than 24 species`);
    if (new Set(pool.map((set) => set.held_item_id)).size < 6) throw new Error(`${window.key} cannot satisfy the item clause`);
  }
}

function stableHash(value) {
  let hash = 2166136261;
  for (const character of value) {
    hash ^= character.codePointAt(0);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function unique(values) {
  return [...new Set(values)];
}

function toId(value) {
  return String(value ?? "").toLowerCase().replace(/[^a-z0-9]+/g, "");
}

const WINDOWS = [
  { key: "starter-1", group: "starter", variant: 1 },
  { key: "intermediate-1", group: "intermediate", variant: 1 },
  { key: "intermediate-2", group: "intermediate", variant: 2 },
  { key: "advanced-1", group: "advanced", variant: 1 },
  { key: "advanced-2", group: "advanced", variant: 2 },
  { key: "advanced-3", group: "advanced", variant: 3 },
  { key: "advanced-4", group: "advanced", variant: 4 },
];
const SETUP_MOVES = new Set([
  "agility", "bellydrum", "bulkup", "calmmind", "clangoroussoul", "coil", "curse", "dragondance", "filletaway",
  "growth", "honeclaws", "irondefense", "nastyplot", "quiverdance", "rockpolish", "shellsmash", "shiftgear",
  "swordsdance", "tailglow", "tidyup", "trailblaze",
]);
const PIVOT_MOVES = new Set(["batonpass", "chillyreception", "flipturn", "partingshot", "teleport", "uturn", "voltswitch"]);
const SPEED_CONTROL_MOVES = new Set(["electroweb", "glare", "icywind", "stickyweb", "tailwind", "thunderwave", "trickroom"]);
const SUPPORT_MOVES = new Set([
  "auroraveil", "defog", "encore", "fakeout", "followme", "haze", "helpinghand", "lightscreen", "protect", "rapidspin",
  "recover", "reflect", "roost", "spikes", "stealthrock", "taunt", "toxic", "toxicspikes", "wideguard", "willowisp",
]);
const RECOVERY_MOVES = new Set([
  "aquaring", "healingwish", "junglehealing", "lifedew", "milkdrink", "moonlight", "morningsun", "recover", "rest",
  "roost", "shoreup", "slackoff", "softboiled", "strengthsap", "synthesis", "wish",
]);
const HAZARD_MOVES = new Set(["ceaselessedge", "spikes", "stealthrock", "stoneaxe", "stickyweb", "toxicspikes"]);

main();
