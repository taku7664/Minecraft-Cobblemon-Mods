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

function normalizeSet(set) {
  const movesInfo = set.moves.map(id => {
    const move = dex.moves.get(id);
    if (!move.exists) throw new Error(`Unknown move ${id}`);
    return { pp: move.pp, maxPp: move.pp };
  });
  return { ...set, movesInfo };
}

function pokemonFrame(pokemon) {
  return {
    uuid: pokemon.uuid || '',
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
  };
}

function frame(battle) {
  return {
    snapshotJson: JSON.stringify(battle.toJSON()),
    turn: battle.turn,
    requestState: battle.requestState || '',
    ended: !!battle.ended,
    p1Active: battle.p1.active.filter(Boolean).map(pokemonFrame),
    p2Active: battle.p2.active.filter(Boolean).map(pokemonFrame),
    log: battle.log.slice(),
  };
}

globalThis.mbcCreateBattle = function(payload) {
  const input = JSON.parse(payload);
  const battle = new Battle({ formatid: input.formatId, seed: input.seed });
  try {
    battle.setPlayer('p1', { name: 'p1', team: input.p1Team.map(normalizeSet) });
    battle.setPlayer('p2', { name: 'p2', team: input.p2Team.map(normalizeSet) });
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
