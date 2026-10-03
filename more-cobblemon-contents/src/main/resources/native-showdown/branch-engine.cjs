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

globalThis.mccApplyRules = function(payload) {
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
    baseStabTypes: pokemon.getTypes(false, true),
    terastallizedType: pokemon.terastallized || '',
    stellarBoostedTypes: (pokemon.stellarBoostedTypes || []).slice(),
    boosts: pokemon.boosts,
    volatiles: Object.keys(pokemon.volatiles).sort(),
    moves: pokemon.moveSlots.map(slot => ({
      id: slot.id,
      pp: slot.pp,
      maxPp: slot.maxpp,
      disabled: !!slot.disabled,
      category: pokemon.battle.dex.moves.get(slot.id).category || '',
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

function deterministicLog(log) {
  return (log || []).map(line => line.startsWith('|t:|') ? '|t:|0' : line);
}

function deterministicSnapshot(battle) {
  const snapshot = battle.toJSON();
  snapshot.log = deterministicLog(snapshot.log);
  delete snapshot.mccExecutedDamageRolls;
  return snapshot;
}

function ensureExecutedMoveOrder(battle) {
  if (!Array.isArray(battle.mccExecutedMoveOrder)) battle.mccExecutedMoveOrder = [];
  return battle.mccExecutedMoveOrder;
}

function recordExecutionTrace(battle, action, options = {}) {
  const moveHistory = ensureExecutedMoveOrder(battle);
  const hpByUuid = new Map(battle.sides.flatMap(side => side.pokemon)
    .map(pokemon => [pokemon.uuid, pokemon.hp]));
  let currentMove = null;
  let pendingDamageRolls = [];
  let damageCallIndex = 0;
  const directHits = [];
  battle.mccBranchRecoil = { p1: 0, p2: 0 };
  const originalAddMove = battle.addMove;
  const originalRandomizer = battle.randomizer;
  const originalAdd = battle.add;
  battle.addMove = function(...parts) {
    if (parts[0] === 'move' && parts[1] && parts[1].uuid) {
      const moveId = toID(parts[2]);
      if (!moveId) throw new Error(`Native executed move is missing its id on turn ${battle.turn}`);
      currentMove = { turn: battle.turn, attackerPokemonUuid: parts[1].uuid, moveId };
      pendingDamageRolls = [];
      if (options.persistMoveHistory !== false) {
        moveHistory.push({ turn: battle.turn, pokemonUuid: parts[1].uuid, moveId });
      }
    }
    return originalAddMove.apply(this, parts);
  };
  battle.randomizer = function(baseDamage) {
    const actualDamage = originalRandomizer.apply(this, arguments);
    if (currentMove && Number.isInteger(baseDamage) && baseDamage > 0) {
      const callIndex = damageCallIndex++;
      pendingDamageRolls.push({
        ...currentMove,
        callIndex,
      });
      const percent = options.forcedDamagePercents && options.forcedDamagePercents.get(callIndex);
      if (percent !== undefined) {
        if (!Number.isInteger(percent) || percent < 85 || percent > 100) {
          throw new Error(`Invalid forced native damage percentage ${percent}`);
        }
        return battle.trunc(battle.trunc(baseDamage * percent) / 100);
      }
    }
    return actualDamage;
  };
  battle.add = function(...parts) {
    const result = originalAdd.apply(this, parts);
    const kind = parts[0];
    const pokemon = parts[1];
    if ((kind === '-damage' || kind === '-heal') && pokemon && pokemon.uuid) {
      const previousHp = hpByUuid.get(pokemon.uuid);
      hpByUuid.set(pokemon.uuid, pokemon.hp);
      const hasPublicSource = parts.slice(3).some(part => String(part).startsWith('[from]'));
      const recoilSource = parts.slice(3).some(part => /^\[from\] recoil$/i.test(String(part)));
      if (kind === '-damage' && recoilSource && Number.isInteger(previousHp) && previousHp > pokemon.hp) {
        const side = pokemon.side && pokemon.side.id;
        if (side === 'p1' || side === 'p2') {
          battle.mccBranchRecoil[side] += (previousHp - pokemon.hp) / pokemon.maxhp;
        }
      }
      if (kind === '-damage' && currentMove && Number.isInteger(previousHp)) {
        const rollIndex = pendingDamageRolls.findIndex(roll =>
          roll.turn === currentMove.turn &&
          roll.attackerPokemonUuid === currentMove.attackerPokemonUuid &&
          roll.moveId === currentMove.moveId);
        if (rollIndex >= 0) {
          const roll = pendingDamageRolls.splice(rollIndex, 1)[0];
          const actualHpLoss = previousHp - pokemon.hp;
          if (!hasPublicSource && actualHpLoss > 0) {
            directHits.push({
              turn: currentMove.turn,
              attackerPokemonUuid: currentMove.attackerPokemonUuid,
              targetPokemonUuid: pokemon.uuid,
              moveId: currentMove.moveId,
              hpBefore: previousHp,
              maxHp: pokemon.maxhp,
              actualHpLoss,
              damageCallIndex: roll.callIndex,
            });
          }
        }
      }
    }
    return result;
  };
  try {
    action();
    return directHits;
  } finally {
    delete battle.addMove;
    delete battle.randomizer;
    delete battle.add;
  }
}

function replayDamageRoll(input, targetCallIndex, percent, forcedDamagePercents) {
  const battle = Battle.fromJSON(JSON.parse(input.snapshotJson));
  battle.restart(function() {});
  try {
    const p1Wait = !!(battle.p1.activeRequest && battle.p1.activeRequest.wait);
    const p2Wait = !!(battle.p2.activeRequest && battle.p2.activeRequest.wait);
    const replayPercents = new Map(forcedDamagePercents);
    replayPercents.set(targetCallIndex, percent);
    const hits = recordExecutionTrace(battle, () => {
      submitRequestedChoice(battle, 'p1', input.p1Choice, p1Wait);
      submitRequestedChoice(battle, 'p2', input.p2Choice, p2Wait);
    }, {
      persistMoveHistory: false,
      forcedDamagePercents: replayPercents,
    });
    return hits.find(hit => hit.damageCallIndex === targetCallIndex) || null;
  } finally {
    battle.destroy();
  }
}

function collectDamageRollEvidence(input, actualHits, forcedDamagePercents) {
  return actualHits.map(actual => {
    const possibleHpLosses = [];
    for (let percent = 85; percent <= 100; percent++) {
      const replayed = replayDamageRoll(input, actual.damageCallIndex, percent, forcedDamagePercents);
      if (!replayed || replayed.attackerPokemonUuid !== actual.attackerPokemonUuid ||
          replayed.targetPokemonUuid !== actual.targetPokemonUuid || replayed.moveId !== actual.moveId) {
        throw new Error(`Native damage replay lost direct hit ${actual.moveId} at call ${actual.damageCallIndex}`);
      }
      possibleHpLosses.push(replayed.actualHpLoss);
    }
    if (!possibleHpLosses.includes(actual.actualHpLoss)) {
      throw new Error(`Actual native damage is outside replayed support for ${actual.moveId}`);
    }
    return { ...actual, possibleHpLosses };
  });
}

function frame(battle, executedDamageRolls = []) {
  return {
    snapshotJson: JSON.stringify(deterministicSnapshot(battle)),
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
    log: deterministicLog(battle.log),
    executedMoveOrder: ensureExecutedMoveOrder(battle).map(entry => ({ ...entry })),
    executedDamageRolls: executedDamageRolls.map(entry => ({
      ...entry,
      possibleHpLosses: entry.possibleHpLosses.slice(),
    })),
    recoilLossP1: battle.mccBranchRecoil ? battle.mccBranchRecoil.p1 : 0,
    recoilLossP2: battle.mccBranchRecoil ? battle.mccBranchRecoil.p2 : 0,
  };
}

function submitRequestedChoice(battle, sideId, choice, requestWasWait) {
  if (requestWasWait) {
    if (choice !== 'pass') {
      throw new Error(`BetterAI supplied ${choice} for ${sideId} wait request`);
    }
    return;
  }
  if (!battle.choose(sideId, choice)) {
    throw new Error(`Showdown rejected ${sideId} choice: ${choice}`);
  }
}

function moveSlot(battle, moveId) {
  const move = battle.dex.moves.get(moveId);
  if (!move.exists) throw new Error(`Unknown rebound move ${moveId}`);
  return {
    move: move.name,
    id: move.id,
    pp: move.pp,
    maxpp: move.pp,
    target: move.target,
    disabled: false,
    disabledSource: '',
    used: false,
  };
}

function rebindPokemonMoves(battle, rebind) {
  const matches = battle.sides.flatMap(side => side.pokemon)
    .filter(pokemon => pokemon.uuid === rebind.pokemonUuid);
  if (matches.length !== 1) {
    throw new Error(`Move-set rebinding names unknown or duplicate Pokemon ${rebind.pokemonUuid}`);
  }
  const pokemon = matches[0];
  const expected = rebind.expectedMoveIds.map(toID);
  const replacement = rebind.replacementMoveIds.map(toID);
  if (!replacement.length || replacement.length > 4 || new Set(replacement).size !== replacement.length) {
    throw new Error(`Invalid replacement move set for ${rebind.pokemonUuid}`);
  }
  const sourceMoves = (pokemon.set.moves || []).map(toID);
  const baseMoves = pokemon.baseMoveSlots.map(slot => slot.id);
  const liveMoves = pokemon.moveSlots.map(slot => slot.id);
  if (pokemon.transformed || JSON.stringify(sourceMoves) !== JSON.stringify(expected) ||
      JSON.stringify(baseMoves) !== JSON.stringify(expected) ||
      JSON.stringify(liveMoves) !== JSON.stringify(expected) ||
      !pokemon.baseMoveSlots.every((slot, index) => slot === pokemon.moveSlots[index])) {
    throw new Error(`Unsafe history-sensitive move-set rebinding for ${rebind.pokemonUuid}`);
  }
  const removed = new Set(expected.filter(moveId => !replacement.includes(moveId)));
  const historyMoves = [pokemon.lastMove?.id, pokemon.lastMoveUsed?.id, pokemon.moveThisTurn];
  for (const volatile of Object.values(pokemon.volatiles || {})) historyMoves.push(volatile?.move);
  if (historyMoves.some(moveId => removed.has(toID(moveId)))) {
    throw new Error(`Move-set rebinding would erase referenced move history for ${rebind.pokemonUuid}`);
  }
  const previousById = new Map(pokemon.moveSlots.map(slot => [slot.id, slot]));
  const rebound = replacement.map(moveId => previousById.get(moveId) || moveSlot(battle, moveId));
  pokemon.set.moves = replacement.slice();
  pokemon.set.movesInfo = rebound.map(slot => ({ pp: slot.maxpp, maxPp: slot.maxpp }));
  pokemon.baseMoveSlots = rebound;
  pokemon.moveSlots = rebound.slice();
  return pokemon;
}

function refreshMoveDisables(battle, pokemon) {
  if (!pokemon.isActive || battle.requestState !== 'move') return;
  pokemon.maybeDisabled = false;
  for (const slot of pokemon.moveSlots) {
    slot.disabled = false;
    slot.disabledSource = '';
  }
  battle.runEvent('DisableMove', pokemon);
  for (const slot of pokemon.moveSlots) {
    const activeMove = battle.dex.getActiveMove(slot.id);
    battle.singleEvent('DisableMove', activeMove, null, pokemon);
    if (activeMove.flags['cantusetwice'] && pokemon.lastMove?.id === slot.id) {
      pokemon.disableMove(pokemon.lastMove.id);
    }
  }
}

function refreshRequestsAfterRebind(battle, reboundPokemon) {
  const reboundIds = new Set(reboundPokemon.map(pokemon => pokemon.uuid));
  const probe = Battle.fromJSON(battle.toJSON());
  probe.restart(function() {});
  try {
    const probeByUuid = new Map(probe.sides.flatMap(side => side.pokemon)
      .map(pokemon => [pokemon.uuid, pokemon]));
    for (const uuid of reboundIds) {
      const pokemon = probeByUuid.get(uuid);
      if (!pokemon) throw new Error(`Rebound request probe lost Pokemon ${uuid}`);
      refreshMoveDisables(probe, pokemon);
    }
    const requests = probe.getRequests(probe.requestState);
    const originalByUuid = new Map(battle.sides.flatMap(side => side.pokemon)
      .map(pokemon => [pokemon.uuid, pokemon]));
    for (const uuid of reboundIds) {
      const original = originalByUuid.get(uuid);
      const checked = probeByUuid.get(uuid);
      if (original.moveSlots.length !== checked.moveSlots.length ||
          original.moveSlots.some((slot, index) => slot.id !== checked.moveSlots[index].id)) {
        throw new Error(`Rebound request probe changed move identity for ${uuid}`);
      }
      original.maybeDisabled = checked.maybeDisabled;
      for (let index = 0; index < original.moveSlots.length; index++) {
        original.moveSlots[index].disabled = checked.moveSlots[index].disabled;
        original.moveSlots[index].disabledSource = checked.moveSlots[index].disabledSource;
      }
    }
    for (let index = 0; index < battle.sides.length; index++) {
      battle.sides[index].activeRequest = requests[index];
    }
  } finally {
    probe.destroy();
  }
}

// Public DTO seed, never a live opponent snapshot. Entry callbacks already ran when the sets
// were constructed; replace their board effects without executing them a second time.
function applyPublicBootstrap(battle, seed) {
  const byUuid = new Map(seed.pokemon.map(p => [p.uuid, p]));
  const effectState = effect => ({ id: effect.id,
    ...(effect.remainingTurns == null ? {} : { duration: effect.remainingTurns }),
    ...(effect.stacks == null ? {} : { layers: effect.stacks }) });
  battle.turn = seed.turn;
  for (const side of battle.sides) {
    side.pokemon.sort((a, b) => (byUuid.get(a.uuid).activeSlot ?? 99) - (byUuid.get(b.uuid).activeSlot ?? 99));
    side.active.fill(null);
    side.pokemon.forEach((mon, position) => {
      const value = byUuid.get(mon.uuid);
      mon.position = position;
      if (value.publicPercentHp && value.hpFraction >= 0.01 && value.hpFraction <= 0.99) {
        const compatible = [];
        for (let hp = 1; hp < mon.maxhp; hp++) {
          if (Math.abs(Math.min(99, Math.ceil(hp * 100 / mon.maxhp)) / 100 - value.hpFraction) < 1e-9) compatible.push(hp);
        }
        if (!compatible.length) throw new Error(`Public HP cannot be represented by hypothesis ${mon.uuid}`);
        mon.hp = compatible[Math.floor(compatible.length / 2)];
      } else mon.hp = Math.max(0, Math.min(mon.maxhp, Math.round(mon.maxhp * value.hpFraction)));
      mon.fainted = mon.hp === 0;
      mon.faintQueued = false;
      mon.isActive = value.activeSlot != null && !mon.fainted;
      mon.isStarted = mon.isActive;
      mon.status = value.status;
      mon.statusState = { id: value.status, target: mon };
      mon.volatiles = {};
      if (value.substituteHpFraction != null) mon.volatiles.substitute = {
        id: 'substitute', target: mon, hp: Math.max(1, Math.round(value.substituteHpFraction * mon.maxhp)) };
      for (const stat of Object.keys(mon.boosts)) mon.boosts[stat] = 0;
      Object.assign(mon.boosts, value.boosts);
      if (value.ability != null) { mon.ability = value.ability; mon.abilityState = { id: value.ability, target: mon }; }
      if (value.item != null) { mon.item = value.item; mon.itemState = { id: value.item, target: mon }; }
      mon.activeTurns = value.activeTurns;
      mon.activeMoveActions = value.activeMoveActions;
      mon.lastMove = value.lastMoveId ? battle.dex.getActiveMove(value.lastMoveId) : null;
      mon.lastMoveUsed = mon.lastMove;
      if (['choiceband', 'choicespecs', 'choicescarf'].includes(mon.item) && mon.lastMove) {
        mon.volatiles.choicelock = { id: 'choicelock', target: mon, move: mon.lastMove.id };
      }
      mon.moveThisTurn = '';
      mon.newlySwitched = false;
      mon.switchFlag = false;
      mon.forceSwitchFlag = false;
      for (const [move, pp] of Object.entries(value.movePp)) {
        const slot = mon.moveSlots.find(m => m.id === move);
        if (slot) { slot.maxpp = Math.max(slot.maxpp, pp); slot.pp = pp; }
      }
      if (mon.isActive) side.active[value.activeSlot] = mon;
    });
    side.pokemonLeft = side.pokemon.filter(p => !p.fainted).length;
    side.totalFainted = side.pokemon.filter(p => p.fainted).length;
    side.sideConditions = {};
    for (const effect of (side.n === 0 ? seed.p1SideConditions : seed.p2SideConditions)) {
      side.sideConditions[effect.id] = { ...effectState(effect), target: side };
    }
  }
  battle.field.weather = seed.weather?.id || '';
  battle.field.weatherState = seed.weather ? effectState(seed.weather) : { id: '' };
  battle.field.terrain = seed.terrain?.id || '';
  battle.field.terrainState = seed.terrain ? effectState(seed.terrain) : { id: '' };
  battle.field.pseudoWeather = {};
  for (const effect of seed.pseudoWeather) battle.field.pseudoWeather[effect.id] = effectState(effect);
  for (const mon of battle.sides.flatMap(side => side.active.filter(Boolean))) {
    refreshMoveDisables(battle, mon);
    mon.trapped = false;
    mon.maybeTrapped = false;
    battle.runEvent('TrapPokemon', mon);
    battle.runEvent('MaybeTrapPokemon', mon);
  }
  battle.makeRequest('move');
}

globalThis.mccCreateBattle = function(payload) {
  const input = JSON.parse(payload);
  const openingByUuid = indexPokemonOpeningState(input.openingState);
  const battle = new Battle({
    formatid: input.formatId,
    seed: input.seed,
    deserialized: !!input.openingState,
  });
  ensureExecutedMoveOrder(battle);
  try {
    battle.setPlayer('p1', { name: 'p1', team: input.p1Team.map(set => normalizeSet(set, openingByUuid)) });
    battle.setPlayer('p2', { name: 'p2', team: input.p2Team.map(set => normalizeSet(set, openingByUuid)) });
    if (input.openingState) {
      validatePokemonOpeningState(battle, input.openingState);
      battle.deserialized = false;
      battle.start();
    }
    if (input.publicBootstrap) applyPublicBootstrap(battle, input.publicBootstrap);
    return JSON.stringify(frame(battle));
  } finally {
    battle.destroy();
  }
};

globalThis.mccRebindBattleMoves = function(payload) {
  const input = JSON.parse(payload);
  if (!Array.isArray(input.rebindings) || !input.rebindings.length) {
    throw new Error('At least one native move-set rebinding is required');
  }
  const battle = Battle.fromJSON(JSON.parse(input.snapshotJson));
  battle.restart(function() {});
  try {
    const reboundPokemon = input.rebindings.map(rebind => rebindPokemonMoves(battle, rebind));
    refreshRequestsAfterRebind(battle, reboundPokemon);
    return JSON.stringify(frame(battle));
  } finally {
    battle.destroy();
  }
};

globalThis.mccBranchBattle = function(payload) {
  const input = JSON.parse(payload);
  const forcedDamagePercents = new Map();
  for (const forced of input.forcedDamageRolls || []) {
    if (!Number.isInteger(forced.damageCallIndex) || forced.damageCallIndex < 0 ||
        !Number.isInteger(forced.percent) || forced.percent < 85 || forced.percent > 100 ||
        forcedDamagePercents.has(forced.damageCallIndex)) {
      throw new Error(`Invalid or duplicate forced native damage roll ${JSON.stringify(forced)}`);
    }
    forcedDamagePercents.set(forced.damageCallIndex, forced.percent);
  }
  if (forcedDamagePercents.size && !input.captureDamageRolls) {
    throw new Error('Forced native damage rolls require damage evidence capture');
  }
  const battle = Battle.fromJSON(JSON.parse(input.snapshotJson));
  battle.restart(function() {});
  try {
    const p1Wait = !!(battle.p1.activeRequest && battle.p1.activeRequest.wait);
    const p2Wait = !!(battle.p2.activeRequest && battle.p2.activeRequest.wait);
    const actualHits = recordExecutionTrace(battle, () => {
      submitRequestedChoice(battle, 'p1', input.p1Choice, p1Wait);
      submitRequestedChoice(battle, 'p2', input.p2Choice, p2Wait);
    }, { forcedDamagePercents });
    const damageEvidence = input.captureDamageRolls && actualHits.length ?
      collectDamageRollEvidence(input, actualHits, forcedDamagePercents) : [];
    return JSON.stringify(frame(battle, damageEvidence));
  } finally {
    battle.destroy();
  }
};
})();
