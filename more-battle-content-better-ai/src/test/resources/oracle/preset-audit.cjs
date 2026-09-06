'use strict';

const path = require('node:path');
const fs = require('node:fs');
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
process.stdout.write(JSON.stringify({ schemaVersion: 1, total: rows.length,
  registryCompatible: rows.filter(row => row.registryCompatible).length,
  obtainable: rows.filter(row => row.obtainable).length,
  initialized: rows.filter(row => row.initialization).length,
  initializationErrors: rows.filter(row => row.initializationError).length,
  initialSpeciesMismatches: rows.filter(row => row.initialization && !row.initialization.speciesMatches).length,
  initialTypeMismatches: rows.filter(row => row.initialization && !row.initialization.typesMatch).length,
  initializationFormat,
  auditDefaults: { level: 50, absentIvs: 31, gender: 'FIXED_SPECIES_GENDER_OTHERWISE_M' },
  ruleset, mod: 'cobblemon', generation: dex.gen, nodeVersion: process.version,
  scope: 'RAW_FACTORY_SET_AUDIT_BASE_GEN9_OBTAINABLE_NOT_RUNTIME_ADDONS_OR_TEAM_LEGALITY', sets: rows }));
