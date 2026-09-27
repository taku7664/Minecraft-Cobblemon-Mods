'use strict';

const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const { Battle, extractChannelMessages } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const dex = Dex.mod('cobblemon');
dex.includeData(); // getMovePool reads dex.gen before lazily loading species data.
if (dex.gen !== 9) throw new Error('Public learnset export requires the declared Gen 9 engine');
const input = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const battleFormat = input.battleFormat || 'SINGLE';
if (!['SINGLE', 'DOUBLE'].includes(battleFormat)) throw new Error(`Unsupported native team format ${battleFormat}`);
if (!Array.isArray(input.battleSeed) || input.battleSeed.length !== 4 ||
    !input.battleSeed.every(value => Number.isInteger(value) && value >= 0 && value <= 65535) ||
    !Number.isInteger(input.maxTurns) || input.maxTurns < 1 || input.maxTurns > 10000) {
  throw new Error('Invalid battle seed or turn limit');
}
const team = (side, sets) => {
  if (!Array.isArray(sets) || sets.length < 1 || sets.length > 6) throw new Error('Expected 1 to 6 complete sets');
  return sets.map((set, slot) => {
    if (!Array.isArray(set.moves) || !set.moves.length) throw new Error('Missing preset moves');
    const uuid = `00000000-0000-0000-0000-${String(side * 100 + slot + 1).padStart(12, '0')}`;
    // Native request names are truncated to 20 characters, whereas public events
    // use Cobblemon UUIDs. Keep native names unique and normalize request IDs below.
    return { ...structuredClone(set), name: `p${side}slot${slot + 1}`, uuid,
      movesInfo: set.moves.map(id => {
        const move = dex.moves.get(id);
        if (!move.exists) throw new Error(`Unknown move ${id}`);
        return { pp: move.pp, maxPp: move.pp };
      }) };
  });
};
const publicEvents = new Set(['gametype', 'player', 'teamsize', 'gen', 'tier', 'start',
  'switch', 'drag', 'replace', 'detailschange', '-formechange', 'move', '-damage', '-heal',
  'faint', 'win', 'tie', 'turn', '-supereffective', '-resisted', '-immune', '-crit', '-hitcount', '-fail',
  'cant', '-status', '-curestatus', '-cureteam', '-start', '-end', '-activate', '-ability',
  '-endability', '-item', '-enditem', '-boost', '-unboost', '-setboost', '-swapboost',
  '-copyboost', '-clearboost', '-clearallboost', '-clearpositiveboost', '-clearnegativeboost',
  '-invertboost', '-weather', '-fieldstart', '-fieldend', '-fieldactivate', '-sidestart',
  '-sideend', '-swapsideconditions', '-singleturn', '-singlemove', '-prepare', '-mustrecharge',
  '-notarget', '-miss', '-block', '-transform', '-terastallize']);
const battle = new Battle({ format: { id: 'betteraiteambridge', name: 'Better AI Team Bridge',
  mod: 'cobblemon', gameType: battleFormat === 'DOUBLE' ? 'doubles' : 'singles', ruleset: [], gen: 9 }, seed: input.battleSeed });
let pending = [];
let closed = false;
let lines;
const close = () => {
  if (closed) return;
  closed = true;
  battle.destroy();
  lines?.close();
  process.stdin.destroy();
};
const publicLog = () => extractChannelMessages(battle.log.join('\n'), [0])[0]
  .filter(line => publicEvents.has(line.split('|')[1]));
const moveInfo = id => {
  // Native requests expose this forced action without a corresponding Dex Move.
  if (id === 'recharge') return { id, type: 'normal', category: 'status', power: 0,
    accuracy: 100, priority: 0, target: 'self', pp: 0 };
  const move = dex.moves.get(id);
  if (!move.exists) throw new Error(`Unknown public move ${id}`);
  return { id: move.id, type: move.type.toLowerCase(), category: move.category.toLowerCase(),
    power: move.basePower, accuracy: move.accuracy === true ? 100 : move.accuracy,
    priority: move.priority, target: move.target, pp: move.pp,
    maxPp: move.noPPBoosts ? move.pp : Math.floor(move.pp * 8 / 5),
    pressureTarget: move.flags.mustpressure ? 'allAdjacentFoes' : move.target, charge: !!move.flags.charge };
};
const targetLocations = (target, actorSlot, opposingSlots, alliedSlots) => {
  if (battleFormat === 'SINGLE') return [''];
  if (['normal', 'adjacentFoe'].includes(target)) {
    return opposingSlots.map(slot => ` ${slot + 1}`);
  }
  const partnerSlot = actorSlot === 0 ? 1 : 0;
  if (target === 'adjacentAlly') return alliedSlots.includes(partnerSlot) ? [` ${-(partnerSlot + 1)}`] : [];
  if (target === 'adjacentAllyOrSelf') {
    return [` ${-(actorSlot + 1)}`, ...(alliedSlots.includes(partnerSlot) ? [` ${-(partnerSlot + 1)}`] : [])];
  }
  if (target === 'any') {
    return [
      ...opposingSlots.map(slot => ` ${slot + 1}`),
      ...(alliedSlots.includes(partnerSlot) ? [` ${-(partnerSlot + 1)}`] : []),
    ];
  }
  return [''];
};
const combine = choicesBySlot => choicesBySlot.reduce(
  (combinations, choices) => combinations.flatMap(prefix => choices.map(choice => [...prefix, choice])), [[]]);
function snapshot() {
  const log = publicLog();
  if (battle.ended || battle.turn > input.maxTurns) {
    process.stdout.write(JSON.stringify({ status: battle.ended ? 'COMPLETE' : 'TURN_LIMIT',
      turn: battle.turn, winner: battle.ended ? (battle.winner || null) : null, publicLog: log }) + '\n');
    close();
    return;
  }
  // Build every request before accepting either side's action. Private maps only
  // contain that requesting side; opponent metadata is derived solely from public events.
  const requests = [];
  for (const side of battle.sides) {
    if (!side.requestState) continue;
    if (!['move', 'switch'].includes(side.requestState)) throw new Error(`Unsupported request ${side.requestState}`);
    const request = structuredClone(side.activeRequest);
    const ownActiveSlots = {};
    side.active.forEach((pokemon, activeSlot) => {
      if (pokemon) ownActiveSlots[`${side.id}:${pokemon.uuid}`] = activeSlot;
    });
    for (const [slot, pokemon] of side.pokemon.entries()) {
      if (!request.side.pokemon[slot]) throw new Error('Own request roster is incomplete');
      request.side.pokemon[slot].ident = `${side.id}: ${pokemon.uuid}`;
    }
    const ownTypes = {}, ownAbilities = {}, ownItems = {}, ownCurrentPp = {}, species = {}, moves = {}, publicLearnsets = {};
    const registerSpecies = name => {
      const value = dex.species.get(name);
      if (!value.exists) throw new Error(`Unknown revealed species ${name}`);
      if (species[value.id]) return;
      species[value.id] = { types: value.types.map(type => type.toLowerCase()), baseStats: value.baseStats };
      // Public species rules only: no opponent Pokemon object, move slots or referee team access.
      // Gen 9 pool is partial and differs from Cobblemon FormData learnsets used by the product.
      // It does not prove ownership, current legality or compatibility of a four-move combination.
      publicLearnsets[value.id] = {
        sourceId: 'embedded:cobblemon/gen9_move_pool', coverage: 'PARTIAL',
        moves: Object.fromEntries([...dex.species.getMovePool(value.id)].sort().map(id => [id, moveInfo(id)])),
      };
    };
    for (const [slot, pokemon] of side.pokemon.entries()) {
      const ident = request.side.pokemon[slot]?.ident;
      if (!ident) throw new Error('Own request identity missing');
      ownTypes[ident] = pokemon.getTypes().map(type => type.toLowerCase());
      ownAbilities[ident] = pokemon.ability;
      ownItems[ident] = pokemon.item;
      ownCurrentPp[ident] = Object.fromEntries(pokemon.moveSlots.map(slot => [slot.id, slot.pp]));
    }
    for (const pokemon of request.side.pokemon) {
      registerSpecies(pokemon.details.split(',')[0]);
      for (const id of pokemon.moves) moves[dex.moves.get(id).id] = moveInfo(id);
    }
    for (const line of log) {
      const parts = line.split('|');
      if (['switch', 'drag', 'replace', 'detailschange', '-formechange'].includes(parts[1])) {
        registerSpecies(parts[3].split(',')[0]);
      }
      if (parts[1] === 'move') {
        const move = moveInfo(parts[3]);
        moves[move.id] = move;
      }
      if (parts[1] === '-activate' && ['item: Leppa Berry', 'move: Spite'].includes(parts[3]) && parts[4]) {
        const move = moveInfo(parts[4]);
        moves[move.id] = move;
      }
    }
    const actions = [];
    if (side.requestState === 'move') {
      const alliedSlots = side.active.flatMap((pokemon, slot) => pokemon && !pokemon.fainted ? [slot] : []);
      const opposingSlots = side.foe.active.flatMap((pokemon, slot) => pokemon && !pokemon.fainted ? [slot] : []);
      const choicesBySlot = request.active.map((active, actorSlot) => {
        if (!active || !side.active[actorSlot] || side.active[actorSlot].fainted) {
          return [{ command: 'pass', actorSlot, kind: 'pass' }];
        }
        const choices = [];
        active.moves.forEach((move, moveSlot) => {
          if (move.disabled || move.pp === 0) return;
          // Recharge and Struggle can omit PP; they are still native legal requests.
          const metadata = moveInfo(move.id);
          const target = move.target || metadata.target;
          targetLocations(target, actorSlot, opposingSlots, alliedSlots).forEach(targetLocation => {
            choices.push({ command: `move ${moveSlot + 1}${targetLocation}`, actorSlot,
              kind: 'move', slot: moveSlot, moveId: move.id, target,
              targetLoc: targetLocation.trim() ? Number(targetLocation.trim()) : null });
          });
          moves[move.id] = metadata;
        });
        if (!active.trapped) {
          request.side.pokemon.forEach((pokemon, slot) => {
            if (pokemon.active || /(?:^0(?:\s|$)|\bfnt\b)/.test(pokemon.condition)) return;
            choices.push({ command: `switch ${slot + 1}`, actorSlot, kind: 'switch', slot });
          });
        }
        return choices;
      });
      for (const components of combine(choicesBySlot)) {
        const switchSlots = components.filter(component => component.kind === 'switch').map(component => component.slot);
        if (new Set(switchSlots).size !== switchSlots.length) continue;
        const command = components.map(component => component.command).join(', ');
        if (battleFormat === 'SINGLE') actions.push({ ...components[0], id: command });
        else actions.push({ id: command, kind: 'composite', components });
      }
    } else {
      const choicesBySlot = request.forceSwitch.map((forced, actorSlot) => {
        if (!forced) return [{ command: 'pass', actorSlot, kind: 'pass' }];
        return request.side.pokemon.flatMap((pokemon, slot) =>
          pokemon.active || /(?:^0(?:\s|$)|\bfnt\b)/.test(pokemon.condition) ? [] :
            [{ command: `switch ${slot + 1}`, actorSlot, kind: 'switch', slot }]);
      });
      for (const components of combine(choicesBySlot)) {
        const switchSlots = components.filter(component => component.kind === 'switch').map(component => component.slot);
        if (new Set(switchSlots).size !== switchSlots.length) continue;
        const command = components.map(component => component.command).join(', ');
        if (battleFormat === 'SINGLE') actions.push({ ...components[0], id: command });
        else actions.push({ id: command, kind: 'composite', components });
      }
    }
    if (!actions.length) throw new Error(`No exposed legal actions for ${side.id}`);
    requests.push({ format: battleFormat, side: side.id, request, ownTypes, ownAbilities, ownItems, actions,
      publicLog: log, species, moves, publicLearnsets, ownCurrentPp, ownActiveSlots });
  }
  if (!requests.length) throw new Error('Battle neither ended nor requested input');
  pending = requests;
  process.stdout.write(JSON.stringify({ status: 'WAITING', turn: battle.turn, requests, publicLog: log }) + '\n');
}
try {
  battle.setPlayer('p1', { name: 'p1', team: team(1, input.p1?.sets) });
  battle.setPlayer('p2', { name: 'p2', team: team(2, input.p2?.sets) });
  lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  lines.on('line', line => {
    if (closed) return;
    try {
      const message = JSON.parse(line);
      if (!message.choices || typeof message.choices !== 'object' || Array.isArray(message.choices)) {
        throw new Error('Expected choices object');
      }
      const keys = Object.keys(message.choices).sort();
      if (JSON.stringify(keys) !== JSON.stringify(pending.map(request => request.side).sort())) {
        throw new Error('Choices must match exactly the waiting sides');
      }
      for (const request of pending) {
        if (!request.actions.some(action => action.id === message.choices[request.side])) {
          throw new Error(`Action not exposed for ${request.side}`);
        }
      }
      for (const request of pending) {
        if (!battle.choose(request.side, message.choices[request.side])) {
          throw new Error(`Native rejected ${request.side} ${message.choices[request.side]}`);
        }
      }
      snapshot();
    } catch (error) {
      process.stderr.write(`${error.stack || error}\n`);
      process.exitCode = 1;
      close();
    }
  });
  lines.on('close', close);
  snapshot();
} catch (error) {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
  close();
}
