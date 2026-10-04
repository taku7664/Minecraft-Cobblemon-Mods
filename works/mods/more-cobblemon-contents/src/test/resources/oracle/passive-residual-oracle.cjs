'use strict';

const path = require('node:path');
const { Battle } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const seed = [1, 2, 3, 4];
const format = { id: 'betteraipassiveresidual', name: 'Better AI Passive Residual Oracle',
  mod: 'cobblemon', gameType: 'singles', ruleset: [], gen: 9 };
// Synthetic mechanics fixtures deliberately include species/ability combinations
// that are not legal team presets. No private referee data becomes live AI input.
const profiles = [
  { id: 'leftovers-poison', species: 'Snorlax', item: 'leftovers', status: 'psn' },
  { id: 'poisonheal-poison', species: 'Snorlax', ability: 'poisonheal', status: 'psn' },
  { id: 'poisonheal-leftovers-poison', species: 'Snorlax', ability: 'poisonheal', item: 'leftovers', status: 'psn' },
  { id: 'poisonheal-leftovers-poison-salt', species: 'Snorlax', ability: 'poisonheal', item: 'leftovers', status: 'psn', saltCure: true },
  { id: 'magicguard-leftovers-poison-salt', species: 'Snorlax', ability: 'magicguard', item: 'leftovers', status: 'psn', saltCure: true },
  { id: 'poison-salt', species: 'Snorlax', status: 'psn', saltCure: true },
  { id: 'water-poisonheal-leftovers-poison-salt', species: 'Blastoise', ability: 'poisonheal', item: 'leftovers', status: 'psn', saltCure: true },
  { id: 'leftovers-burn', species: 'Snorlax', item: 'leftovers', status: 'brn' },
  { id: 'leftovers-only', species: 'Snorlax', item: 'leftovers' },
];
const set = (species, level, ability = '', item = '') => {
  const pp = Dex.mod('cobblemon').moves.get('splash').pp;
  return { species, name: species, level, gender: 'M', ability, item,
    nature: 'Serious', moves: ['splash'], movesInfo: [{ pp, maxPp: pp }],
    evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
    ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } };
};
const cases = profiles.flatMap(profile => [50, 1].flatMap(level => ['full', 'half', 'one'].map(hpMode => {
  const id = `${profile.id}-L${level}-${hpMode}`;
  const battle = new Battle({ format, seed });
  try {
    battle.setPlayer('p1', { name: 'p1', team: [set(profile.species, level, profile.ability, profile.item)] });
    battle.setPlayer('p2', { name: 'p2', team: [set('Snorlax', 50)] });
    const source = battle.p1.active[0];
    const opponent = battle.p2.active[0];
    source.hp = hpMode === 'one' ? 1 : hpMode === 'half' ? Math.floor(source.maxhp / 2) : source.maxhp;
    if (profile.status && !source.setStatus(profile.status)) throw new Error(`${id}: could not establish status`);
    if (profile.saltCure && !source.addVolatile('saltcure', opponent)) throw new Error(`${id}: could not establish Salt Cure`);
    const input = { maxHp: source.maxhp, hp: source.hp, status: profile.status || null,
      ability: source.ability, item: source.item, saltCure: !!profile.saltCure,
      types: source.getTypes().map(type => type.toLowerCase()), level: source.level, speciesId: source.species.id };
    const initialTurn = battle.turn;
    const start = battle.log.length;
    for (const side of ['p1', 'p2']) {
      if (!battle.choose(side, 'move 1')) throw new Error(`${id}: rejected ${side} move 1`);
    }
    const refereeTurnLog = battle.log.slice(start);
    const moves = refereeTurnLog.filter(line => line.startsWith('|move|'));
    if (moves.length !== 2 || (!battle.ended && battle.turn !== initialTurn + 1) ||
        source.hp < 0 || source.hp > source.maxhp || source.fainted !== (source.hp === 0) ||
        opponent.hp !== opponent.maxhp || opponent.fainted) {
      throw new Error(`${id}: native residual turn did not complete: ${JSON.stringify(refereeTurnLog)}`);
    }
    return { id, input, result: { hp: source.hp, fainted: source.fainted }, refereeTurnLog };
  } finally { battle.destroy(); }
})));
process.stdout.write(JSON.stringify({ status: 'COMPLETE', nodeVersion: process.version, format, seed,
  scope: 'SYNTHETIC_NATIVE_LEFTOVERS_POISON_HEAL_MAGIC_GUARD_STATUS_SALT_CURE_RESIDUAL_ONLY',
  exclusions: ['legal-team-presets', 'status-infliction-chance', 'other-abilities', 'other-items',
    'weather', 'other-residual-effects', 'doubles', 'runtime-registrations', 'live-battle-quality'], cases }));
