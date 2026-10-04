'use strict';

const path = require('node:path');
const { Battle } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const seed = [1, 2, 3, 4];
const format = { id: 'betteraiturnorder', name: 'Better AI Turn Order Oracle',
  mod: 'cobblemon', gameType: 'singles', ruleset: [], gen: 9 };
// Expected sides are fixture assertions, declared before observing the engine.
// Both combatants start at 1 HP: every connecting attack is lethal at every damage roll.
const fixtures = [
  { id: 'normal-faster', ally: 'Pikachu', opponent: 'Snorlax', expectedFirstSide: 'p1' },
  { id: 'normal-slower', ally: 'Snorlax', opponent: 'Pikachu', expectedFirstSide: 'p2' },
  { id: 'priority-over-speed', ally: 'Snorlax', opponent: 'Pikachu', allyMove: 'quickattack', expectedFirstSide: 'p1' },
  { id: 'trick-room-speed', ally: 'Pikachu', opponent: 'Snorlax', trickRoom: true, expectedFirstSide: 'p2' },
  { id: 'trick-room-priority', ally: 'Pikachu', opponent: 'Snorlax', allyMove: 'quickattack', trickRoom: true, expectedFirstSide: 'p1' },
  { id: 'positive-speed-stage', ally: 'Snorlax', opponent: 'Snorlax', allyStage: 1, expectedFirstSide: 'p1' },
  { id: 'negative-speed-stage', ally: 'Pikachu', opponent: 'Pikachu', allyStage: -1, expectedFirstSide: 'p2' },
  { id: 'tailwind-over-negative-stage', ally: 'Pikachu', opponent: 'Pikachu', allyStage: -1, allyTailwind: true, expectedFirstSide: 'p1' },
];
const set = (species, move) => {
  const pp = Dex.mod('cobblemon').moves.get(move).pp;
  return { species, name: species, level: 50, gender: 'M', ability: '', item: '',
    nature: 'Serious', moves: [move], movesInfo: [{ pp, maxPp: pp }],
    evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
    ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } };
};
const cases = fixtures.map(fixture => {
  const battle = new Battle({ format, seed });
  try {
    battle.setPlayer('p1', { name: 'p1', team: [set(fixture.ally, fixture.allyMove || 'tackle')] });
    battle.setPlayer('p2', { name: 'p2', team: [set(fixture.opponent, 'tackle')] });
    const ally = battle.p1.active[0];
    const opponent = battle.p2.active[0];
    ally.hp = opponent.hp = 1;
    ally.boosts.spe = fixture.allyStage || 0;
    if (fixture.trickRoom && !battle.field.addPseudoWeather('trickroom', ally)) {
      throw new Error(`${fixture.id}: could not establish Trick Room`);
    }
    if (fixture.allyTailwind && !battle.p1.addSideCondition('tailwind', ally)) {
      throw new Error(`${fixture.id}: could not establish Tailwind`);
    }
    const combatantInput = pokemon => {
      const move = battle.dex.moves.get(pokemon.moveSlots[0].id);
      return { speciesId: pokemon.species.id, level: pokemon.level, hp: pokemon.hp,
        maxHp: pokemon.maxhp, speed: pokemon.calculateStat('spe', 0, 1),
        attack: pokemon.calculateStat('atk', 0, 1), defence: pokemon.calculateStat('def', 0, 1),
        specialAttack: pokemon.calculateStat('spa', 0, 1), specialDefence: pokemon.calculateStat('spd', 0, 1),
        moveId: move.id, power: move.basePower, priority: move.priority, speedStage: pokemon.boosts.spe };
    };
    const input = { ally: combatantInput(ally), opponent: combatantInput(opponent),
      trickRoom: !!fixture.trickRoom, allyTailwind: !!fixture.allyTailwind };
    const start = battle.log.length;
    for (const side of ['p1', 'p2']) {
      if (!battle.choose(side, 'move 1')) throw new Error(`${fixture.id}: rejected ${side} move 1`);
    }
    const turnLog = battle.log.slice(start);
    const moveLog = turnLog.filter(line => line.startsWith('|move|'));
    const firstSide = moveLog[0]?.split('|')[2].slice(0, 2);
    const faintedSide = ally.fainted ? 'p1' : opponent.fainted ? 'p2' : null;
    const survivor = firstSide === 'p1' ? ally : opponent;
    if (firstSide !== fixture.expectedFirstSide || moveLog.length !== 1 || !battle.ended ||
        battle.winner !== firstSide || faintedSide === firstSide || faintedSide === null || survivor.hp !== 1) {
      throw new Error(`${fixture.id}: unexpected native order or lethal cancellation: ${JSON.stringify(turnLog)}`);
    }
    return { id: fixture.id, expectedFirstSide: fixture.expectedFirstSide, input,
      result: { firstSide, moveCount: moveLog.length, faintedSides: [faintedSide], winner: battle.winner,
        allyHp: ally.hp, opponentHp: opponent.hp, allyFainted: ally.fainted, opponentFainted: opponent.fainted },
      refereeTurnLog: turnLog };
  } finally { battle.destroy(); }
});
process.stdout.write(JSON.stringify({ status: 'COMPLETE', nodeVersion: process.version, format, seed,
  scope: 'SCRIPTED_NATIVE_TURN_ORDER_AND_LETHAL_CANCELLATION_ONLY',
  exclusions: ['speed-tie-distribution', 'accuracy-distribution', 'critical-distribution', 'paralysis',
    'abilities', 'items', 'weather', 'switching', 'doubles', 'end-turn', 'runtime-registrations', 'live-battle-quality'],
  // Exact combatant stats and state mutations belong to this isolated referee, not live AI inputs.
  cases }));
