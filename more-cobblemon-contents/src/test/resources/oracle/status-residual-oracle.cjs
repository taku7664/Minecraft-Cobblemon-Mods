'use strict';

const path = require('node:path');
const { Battle } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const seed = [1, 2, 3, 4];
const format = { id: 'betteraistatusresidual', name: 'Better AI Status Residual Oracle',
  mod: 'cobblemon', gameType: 'singles', ruleset: [], gen: 9 };
const set = (species, level) => {
  const pp = Dex.mod('cobblemon').moves.get('splash').pp;
  return { species, name: species, level, gender: 'M', ability: '', item: '',
    nature: 'Serious', moves: ['splash'], movesInfo: [{ pp, maxPp: pp }],
    evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
    ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } };
};
const statusFixtures = [
  { status: 'psn', toxicTurn: 0 }, { status: 'brn', toxicTurn: 0 },
  ...[1, 2, 3, 15].map(toxicTurn => ({ status: 'tox', toxicTurn })),
];
const cases = [['Snorlax', 50], ['Pikachu', 1]].flatMap(([species, level]) =>
  statusFixtures.flatMap(({ status, toxicTurn }) => ['full', 'one'].map(hpMode => {
    const id = `${species.toLowerCase()}-L${level}-${status}${toxicTurn || ''}-${hpMode}`;
    const battle = new Battle({ format, seed });
    try {
      battle.setPlayer('p1', { name: 'p1', team: [set(species, level)] });
      battle.setPlayer('p2', { name: 'p2', team: [set('Snorlax', 50)] });
      const source = battle.p1.active[0];
      const target = battle.p2.active[0];
      source.hp = hpMode === 'one' ? 1 : source.maxhp;
      if (!source.setStatus(status)) throw new Error(`${id}: could not establish ${status}`);
      // Native tox.onResidual increments stage (capped at 15) before applying damage.
      // This mutation represents prior elapsed toxic turns, not a damage formula override.
      if (status === 'tox') source.statusState.stage = toxicTurn - 1;
      const input = { maxHp: source.maxhp, hp: source.hp, status, toxicTurn,
        speciesId: source.species.id, level: source.level };
      const initialTurn = battle.turn;
      const start = battle.log.length;
      for (const side of ['p1', 'p2']) {
        if (!battle.choose(side, 'move 1')) throw new Error(`${id}: rejected ${side} move 1`);
      }
      const refereeTurnLog = battle.log.slice(start);
      const moves = refereeTurnLog.filter(line => line.startsWith('|move|'));
      if (moves.length !== 2 || (!battle.ended && battle.turn !== initialTurn + 1) ||
          source.hp >= input.hp || source.hp < 0 || source.fainted !== (source.hp === 0) ||
          target.hp !== target.maxhp || target.fainted) {
        throw new Error(`${id}: native residual turn did not complete: ${JSON.stringify(refereeTurnLog)}`);
      }
      return { id, input, result: { hp: source.hp, fainted: source.fainted }, refereeTurnLog };
    } finally { battle.destroy(); }
  })));
process.stdout.write(JSON.stringify({ status: 'COMPLETE', nodeVersion: process.version, format, seed,
  scope: 'SCRIPTED_NATIVE_POISON_BURN_TOXIC_RESIDUAL_ONLY',
  exclusions: ['status-infliction-chance', 'status-immunity', 'switch-toxic-reset', 'healing', 'abilities',
    'items', 'weather', 'salt-cure', 'other-residual-effects', 'doubles', 'runtime-registrations', 'live-battle-quality'],
  // Privileged exact HP and pre-established status are isolated referee fixtures, never live AI inputs.
  cases }));
