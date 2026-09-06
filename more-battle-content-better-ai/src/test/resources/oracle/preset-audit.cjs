'use strict';

const path = require('node:path');
const fs = require('node:fs');
const { createHash } = require('node:crypto');
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const { Format } = require(path.join(process.argv[2], 'sim/dex-formats.js'));
const { TeamValidator } = require(path.join(process.argv[2], 'sim/team-validator.js'));
const { Battle } = require(path.join(process.argv[2], 'sim/battle.js'));
const input = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
if (!Array.isArray(input.sets) || !input.sets.length) throw new Error('Missing raw presets');
const dex = Dex.mod('cobblemon');
const ruleset = ['Obtainable', 'Max Level = 50'];
const validator = new TeamValidator(new Format({ id: 'betteraipresetaudit', name: 'Better AI Preset Audit',
  mod: 'cobblemon', gameType: 'singles', ruleset }));
const initializationFormat = { id: 'betteraipresetinit', name: 'Better AI Preset Init',
  mod: 'cobblemon', gameType: 'singles', ruleset: [], gen: 9 };
const initialize = engineSet => {
  const battle = new Battle({ format: initializationFormat, seed: [1, 2, 3, 4] });
  try {
    battle.setPlayer('p1', { name: 'p1', team: [{ ...engineSet,
      uuid: '00000000-0000-0000-0000-000000000001' }] });
    battle.setPlayer('p2', { name: 'p2', team: [{ species: 'Magikarp', level: 50, ability: 'Swift Swim',
      nature: 'Serious', moves: ['splash'], movesInfo: [{ pp: 40, maxPp: 40 }],
      uuid: '00000000-0000-0000-0000-000000000002' }] });
    const pokemon = battle.p1.active[0];
    const expected = dex.species.get(engineSet.species);
    const types = pokemon.getTypes();
    return { speciesId: pokemon.species.id, types, ability: pokemon.ability, item: pokemon.item,
      maxHp: pokemon.maxhp, speciesMatches: pokemon.species.id === expected.id,
      typesMatch: JSON.stringify([...types].sort()) === JSON.stringify([...expected.types].sort()) };
  } finally { battle.destroy(); }
};
const id = value => {
  if (typeof value !== 'string' || !value.startsWith('cobblemon:')) throw new Error(`Unsupported catalog ID: ${value}`);
  return value.slice('cobblemon:'.length).toLowerCase().replace(/[^a-z0-9]/g, '');
};
const statNames = { hp: 'hp', attack: 'atk', defense: 'def', special_attack: 'spa', special_defense: 'spd', speed: 'spe' };
const spread = (raw, maximum) => {
  const result = {};
  for (const [source, target] of Object.entries(statNames)) {
    if (!raw || !Number.isInteger(raw[source]) || raw[source] < 0 || raw[source] > maximum) {
      throw new Error(`Invalid ${source} spread value`);
    }
    result[target] = raw[source];
  }
  return result;
};
const rows = input.sets.map(raw => {
  const registryProblems = [];
  const row = { setId: raw.set_id, registryCompatible: false, registryProblems,
    obtainableChecked: false, obtainable: false, obtainableProblems: [], engineSet: null, validatedSet: null,
    initialization: null, initializationError: null };
  try {
    const species = dex.species.get(id(raw.species_id) + (raw.form_id || ''));
    const ability = dex.abilities.get(id(raw.ability_id));
    const item = dex.items.get(id(raw.held_item_id));
    const nature = dex.natures.get(id(raw.nature_id));
    for (const [kind, entry] of Object.entries({ species, ability, item, nature })) {
      if (!entry.exists) registryProblems.push(`Unknown ${kind}: ${entry.id}`);
    }
    if (!Array.isArray(raw.moves) || raw.moves.length !== 4) throw new Error('Expected four preset moves');
    const moves = raw.moves.map(value => dex.moves.get(id(value)));
    for (const move of moves) if (!move.exists) registryProblems.push(`Unknown move: ${move.id}`);
    if (new Set(moves.map(move => move.id)).size !== 4) registryProblems.push('Duplicate preset moves');
    const evs = spread(raw.evs, 252);
    if (Object.values(evs).reduce((sum, value) => sum + value, 0) > 510) registryProblems.push('EV total exceeds 510');
    const ivs = raw.ivs === undefined ? { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } : spread(raw.ivs, 31);
    if (registryProblems.length) return row;
    row.registryCompatible = true;
    row.engineSet = { species: species.name, ability: ability.name, item: item.name, nature: nature.name,
      gender: species.gender || 'M',
      level: 50, moves: moves.map(move => move.id), evs, ivs,
      movesInfo: moves.map(move => ({ pp: move.pp, maxPp: move.pp })) };
    // Validation can normalize or rewrite a set. Keep that output separate from the input.
    row.validatedSet = JSON.parse(JSON.stringify(row.engineSet));
    row.obtainableChecked = true;
    row.obtainableProblems = validator.validateTeam([row.validatedSet]) || [];
    row.obtainable = row.obtainableProblems.length === 0;
    try {
      // Privileged referee diagnostics only, NEVER input to a Brain or public adapter.
      row.initialization = initialize(JSON.parse(JSON.stringify(row.engineSet)));
    } catch (error) {
      row.initializationError = error.message;
    }
  } catch (error) {
    // Engine errors are infrastructure failures, not false claims that the preset is illegal.
    if (row.registryCompatible) throw error;
    registryProblems.push(error.message);
  }
  return row;
});
const sampleTeams = () => {
  const count = input.teamPairs || 0;
  const seed = input.teamSeed === undefined ? 20260906 : input.teamSeed;
  const partition = input.teamSplit || 'ALL';
  if (!['ALL', 'TUNING', 'HOLDOUT'].includes(partition)) throw new Error('Invalid corpus partition');
  const reserved = new Set(input.reservedTuningKeys || []);
  const encode = values => [...values].sort().map(value => `${value.length}:${value}`).join('');
  const corpusKey = (p1, p2) => createHash('sha256').update('native-single-set-pair-v1|' +
    encode([encode(p1.setIds), encode(p2.setIds)]), 'utf8').digest('hex');
  if (!Number.isInteger(count) || count < 0 || count > 1000 || !Number.isInteger(seed)) {
    throw new Error('Invalid team sampling count or seed');
  }
  // Evaluation policy, not the facilities' runtime legality policy.
  const teamRules = [...ruleset, 'Species Clause', 'Item Clause'];
  const teamValidator = new TeamValidator(new Format({ id: 'betteraiteamaudit', name: 'Better AI Team Audit',
    mod: 'cobblemon', gameType: 'singles', ruleset: teamRules }));
  const pool = rows.filter(row => row.obtainable && row.initialization && !row.initializationError);
  let state = seed >>> 0;
  const next = () => {
    state = (state + 0x6D2B79F5) >>> 0;
    let value = Math.imul(state ^ state >>> 15, 1 | state);
    value ^= value + Math.imul(value ^ value >>> 7, 61 | value);
    return ((value ^ value >>> 14) >>> 0) / 4294967296;
  };
  const draw = () => {
    const available = [...pool];
    const chosen = [];
    const species = new Set();
    const items = new Set();
    while (available.length && chosen.length < 3) {
      const index = Math.floor(next() * available.length);
      const row = available[index];
      available[index] = available[available.length - 1];
      available.pop();
      const base = dex.species.get(row.engineSet.species).baseSpecies;
      const item = row.engineSet.item;
      if (species.has(base) || (item && items.has(item))) continue;
      chosen.push(row);
      species.add(base);
      if (item) items.add(item);
    }
    if (chosen.length !== 3) throw new Error('Cannot draw three distinct species/items from eligible presets');
    const sets = chosen.map(row => JSON.parse(JSON.stringify(row.engineSet)));
    const validatedSets = JSON.parse(JSON.stringify(sets));
    const problems = teamValidator.validateTeam(validatedSets) || [];
    return { setIds: chosen.map(row => row.setId), sets, validatedSets, problems };
  };
  const pairs = [];
  const seen = new Set();
  let attemptedPairs = 0;
  let partitionSkipped = 0;
  let duplicatePairsSkipped = 0;
  while (pairs.length < count && attemptedPairs < count * 100) {
    attemptedPairs++;
    const p1 = draw();
    const p2 = draw();
    if (p1.problems.length || p2.problems.length) {
      throw new Error(`Drawn team failed validation: ${JSON.stringify([p1.problems, p2.problems])}`);
    }
    const battleSeed = Array.from({ length: 4 }, () => Math.floor(next() * 65536));
    const key = corpusKey(p1, p2);
    const split = !reserved.has(key) && parseInt(key.slice(0, 8), 16) % 5 === 0 ? 'HOLDOUT' : 'TUNING';
    if (partition !== 'ALL' && split !== partition) { partitionSkipped++; continue; }
    if (partition !== 'ALL' && seen.has(key)) { duplicatePairsSkipped++; continue; }
    seen.add(key);
    const battle = new Battle({ format: initializationFormat, seed: battleSeed });
    try {
      for (const [side, team] of [['p1', p1], ['p2', p2]]) {
        battle.setPlayer(side, { name: side, team: team.sets.map((set, slot) => ({
          ...JSON.parse(JSON.stringify(set)), uuid: `00000000-0000-0000-0000-${String((side === 'p1' ? 1 : 2) * 100 + slot).padStart(12, '0')}`,
        })) });
      }
      p1.initializedCount = battle.p1.pokemon.length;
      p2.initializedCount = battle.p2.pokemon.length;
      if (p1.initializedCount !== 3 || p2.initializedCount !== 3) throw new Error('Incomplete initialized team');
    } finally { battle.destroy(); }
    pairs.push({ index: pairs.length, battleSeed, p1, p2, corpusKey: key, partition: split });
  }
  if (pairs.length !== count) throw new Error('Cannot draw enough distinct pairs in corpus partition');
  return { seed, algorithm: 'MULBERRY32_SEQUENTIAL_SET_DRAW_V1', teamSize: 3,
    partition, partitionMethod: 'SHA256_UNORDERED_SET_IDS_V1_MOD5_RESERVED_TUNING',
    reservedTuningKeys: [...reserved].sort(), attemptedPairs, partitionSkipped, duplicatePairsSkipped,
    eligibleSets: pool.length, ruleset: teamRules, rejectedTeams: 0, pairs,
    scope: 'TEAM_VALIDATION_AND_INITIALIZATION_ONLY_NO_TURNS_OR_AI_INPUT' };
};
process.stdout.write(JSON.stringify({ schemaVersion: 2, total: rows.length,
  registryCompatible: rows.filter(row => row.registryCompatible).length,
  obtainable: rows.filter(row => row.obtainable).length,
  initialized: rows.filter(row => row.initialization).length,
  initializationErrors: rows.filter(row => row.initializationError).length,
  initialSpeciesMismatches: rows.filter(row => row.initialization && !row.initialization.speciesMatches).length,
  initialTypeMismatches: rows.filter(row => row.initialization && !row.initialization.typesMatch).length,
  initializationFormat,
  auditDefaults: { level: 50, absentIvs: 31, gender: 'FIXED_SPECIES_GENDER_OTHERWISE_M' },
  ruleset, mod: 'cobblemon', generation: dex.gen, nodeVersion: process.version,
  scope: 'RAW_FACTORY_SET_AUDIT_BASE_GEN9_OBTAINABLE_NOT_RUNTIME_ADDONS_OR_TEAM_LEGALITY',
  teamSampling: sampleTeams(), sets: rows }));
