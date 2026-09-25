'use strict';

(function() {
const { Battle } = require('./sim/battle.js');
const { Dex } = require('./sim/dex.js');
const dex = Dex.mod('cobblemon');
dex.includeData();

const ruleReceivers = {
  ABILITY: ['receiveAbilityData', 'ability'],
  MOVE: ['receiveMoveData', 'move'],
  SCRIPT: ['receiveScriptData', null],
  CONDITION: ['receiveConditionData', null],
  HELD_ITEM: ['receiveHeldItemData', 'heldItem'],
  TYPE_CHART: ['receiveTypeChartData', null],
};

globalThis.mbcApplyRules = function(payload) {
  const sources = JSON.parse(payload);
  for (const source of sources) {
    const receiver = ruleReceivers[source.registry];
    if (!receiver) throw new Error(`Unsupported native rule registry ${source.registry}`);
    const specialized = globalThis[receiver[0]];
    if (typeof specialized === 'function') {
      specialized(source.id, source.javaScript);
      continue;
    }
    if (receiver[1] && typeof globalThis.receiveData === 'function') {
      const objectSource = `{${JSON.stringify(source.id)}:${source.javaScript}}`;
      globalThis.receiveData(objectSource, receiver[1]);
      continue;
    }
    throw new Error(`Showdown index.js is missing ${receiver[0]}`);
  }
};

function normalizeSet(set, openingByUuid) {
  const movesInfo = set.moves.map(id => {
    const move = dex.moves.get(id);
    if (!move.exists) throw new Error(`Unknown move ${id}`);
    return { pp: move.pp, maxPp: move.pp };
  });
  const opening = openingByUuid && openingByUuid.get(set.uuid);
  return {
    ...set,
    movesInfo,
    ...(opening ? {
      currentHealth: opening.hp,
      status: opening.status,
      statusDuration: -1,
    } : {}),
  };
}

function toID(value) {
  return String(value || '').toLowerCase().replace(/[^a-z0-9]+/g, '');
}

function indexPokemonOpeningState(seed) {
  if (!seed) return null;
  const byUuid = new Map();
  for (const opening of seed.pokemon) {
    if (!opening.uuid || byUuid.has(opening.uuid)) {
      throw new Error(`Native public opening state has a missing or duplicate Pokemon UUID ${opening.uuid || ''}`);
    }
    byUuid.set(opening.uuid, opening);
  }
  return byUuid;
}

function validatePokemonOpeningState(battle, seed) {
  if (battle.started || battle.turn !== 0) {
    throw new Error('Native public opening state can only be applied before Showdown starts');
  }
  const pokemonByUuid = new Map();
  for (const side of battle.sides) {
    for (const pokemon of side.pokemon) {
      if (!pokemon.uuid || pokemonByUuid.has(pokemon.uuid)) {
        throw new Error(`Synthetic battle has a missing or duplicate Pokemon UUID ${pokemon.uuid || ''}`);
      }
      pokemonByUuid.set(pokemon.uuid, pokemon);
    }
  }
  if (pokemonByUuid.size !== seed.pokemon.length) {
    throw new Error('Native public opening state must cover the whole synthetic roster');
  }
  for (const patch of seed.pokemon) {
    const pokemon = pokemonByUuid.get(patch.uuid);
    if (!pokemon) throw new Error(`Native public opening state names unknown Pokemon ${patch.uuid}`);
    if (toID(pokemon.set.ability) !== toID(patch.ability) ||
        toID(pokemon.set.item) !== toID(patch.item)) {
      throw new Error(`Seeded ability or item disagrees with synthetic set ${patch.uuid}`);
    }
    const status = patch.status ? dex.conditions.get(patch.status) : null;
    if (patch.status && !status.exists) throw new Error(`Unknown seeded status ${patch.status}`);

    if (pokemon.maxhp !== patch.maxHp) {
      throw new Error(`Opening max HP disagrees with synthetic set ${patch.uuid}: ${patch.maxHp} != ${pokemon.maxhp}`);
    }
    if (pokemon.hp !== patch.hp || pokemon.status !== (status ? status.id : '')) {
      throw new Error(`Showdown did not construct the requested public opening state ${patch.uuid}`);
    }
  }
  for (const side of battle.sides) {
    if (!side.pokemon[0].hp) {
      throw new Error('Native opening state cannot select a fainted lead Pokemon');
    }
  }
}

function pokemonFrame(pokemon, activeSlot) {
  return {
    uuid: pokemon.uuid || '',
    sourceSet: {
      species: pokemon.set.species || '',
      ability: pokemon.set.ability || '',
      item: pokemon.set.item || '',
      moves: (pokemon.set.moves || []).slice(),
      nature: pokemon.set.nature || '',
      gender: pokemon.set.gender || '',
      evs: { ...(pokemon.set.evs || {}) },
      ivs: { ...(pokemon.set.ivs || {}) },
      teraType: pokemon.set.teraType || '',
      openingHp: Number.isInteger(pokemon.set.currentHealth) ? pokemon.set.currentHealth : null,
      openingMaxHp: pokemon.maxhp,
      openingStatus: pokemon.set.status || '',
    },
    species: pokemon.species.id,
    hp: pokemon.hp,
    maxHp: pokemon.maxhp,
    status: pokemon.status || '',
    ability: pokemon.ability || '',
    item: pokemon.item || '',
    types: pokemon.getTypes(),
    boosts: pokemon.boosts,
    volatiles: Object.keys(pokemon.volatiles).sort(),
    moves: pokemon.moveSlots.map(slot => ({
      id: slot.id,
      pp: slot.pp,
      maxPp: slot.maxpp,
      disabled: !!slot.disabled,
    })),
    activeSlot,
    level: pokemon.level,
    stats: {
      atk: pokemon.storedStats.atk,
      def: pokemon.storedStats.def,
      spa: pokemon.storedStats.spa,
      spd: pokemon.storedStats.spd,
      spe: pokemon.storedStats.spe,
    },
  };
}

function sideFrames(side) {
  return side.pokemon.map(pokemon => {
    const activeSlot = side.active.indexOf(pokemon);
    return pokemonFrame(pokemon, activeSlot >= 0 ? activeSlot : null);
  });
}

function activeFrames(side) {
  return side.active
    .map((pokemon, activeSlot) => pokemon ? pokemonFrame(pokemon, activeSlot) : null)
    .filter(Boolean);
}

function timedEffectFrame(id, state) {
  const duration = state && Number.isInteger(state.duration) && state.duration > 0 ? state.duration : null;
  const layers = state && Number.isInteger(state.layers) && state.layers > 0 ? state.layers : null;
  return { id, remainingTurns: duration, stacks: layers };
}

function timedEffectFrames(effects) {
  return Object.entries(effects || {})
    .map(([id, state]) => timedEffectFrame(id, state))
    .sort((left, right) => left.id.localeCompare(right.id));
}

function fieldFrame(battle) {
  const field = battle.field;
  return {
    weather: field.weather ? timedEffectFrame(field.weather, field.weatherState) : null,
    terrain: field.terrain ? timedEffectFrame(field.terrain, field.terrainState) : null,
    pseudoWeather: timedEffectFrames(field.pseudoWeather),
    p1SideConditions: timedEffectFrames(battle.p1.sideConditions),
    p2SideConditions: timedEffectFrames(battle.p2.sideConditions),
  };
}

function frame(battle) {
  return {
    snapshotJson: JSON.stringify(battle.toJSON()),
    turn: battle.turn,
    requestState: battle.requestState || '',
    ended: !!battle.ended,
    p1Active: activeFrames(battle.p1),
    p2Active: activeFrames(battle.p2),
    p1Team: sideFrames(battle.p1),
    p2Team: sideFrames(battle.p2),
    p1RequestJson: JSON.stringify(battle.p1.activeRequest || null),
    p2RequestJson: JSON.stringify(battle.p2.activeRequest || null),
    field: fieldFrame(battle),
    log: battle.log.slice(),
  };
}

globalThis.mbcCreateBattle = function(payload) {
  const input = JSON.parse(payload);
  const openingByUuid = indexPokemonOpeningState(input.openingState);
  const battle = new Battle({
    formatid: input.formatId,
    seed: input.seed,
    deserialized: !!input.openingState,
  });
  try {
    battle.setPlayer('p1', { name: 'p1', team: input.p1Team.map(set => normalizeSet(set, openingByUuid)) });
    battle.setPlayer('p2', { name: 'p2', team: input.p2Team.map(set => normalizeSet(set, openingByUuid)) });
    if (input.openingState) {
      validatePokemonOpeningState(battle, input.openingState);
      battle.deserialized = false;
      battle.start();
    }
    return JSON.stringify(frame(battle));
  } finally {
    battle.destroy();
  }
};

globalThis.mbcBranchBattle = function(payload) {
  const input = JSON.parse(payload);
  const battle = Battle.fromJSON(JSON.parse(input.snapshotJson));
  battle.restart(function() {});
  try {
    if (!battle.choose('p1', input.p1Choice)) {
      throw new Error(`Showdown rejected p1 choice: ${input.p1Choice}`);
    }
    if (!battle.choose('p2', input.p2Choice)) {
      throw new Error(`Showdown rejected p2 choice: ${input.p2Choice}`);
    }
    return JSON.stringify(frame(battle));
  } finally {
    battle.destroy();
  }
};
})();
