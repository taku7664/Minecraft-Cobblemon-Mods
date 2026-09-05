'use strict';

const path = require('node:path');
const { Battle } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
// Diagnostic sets with no abilities/items: exact referee stats NEVER become product AI inputs.
const fixtures = [
  ['stab-super', 'Pikachu', 'Blastoise', 'thunderbolt'],
  ['neutral-special', 'Pikachu', 'Snorlax', 'swift'],
  ['stab-neutral-physical', 'Snorlax', 'Snorlax', 'tackle'],
  ['quad-resist', 'Bulbasaur', 'Charizard', 'megadrain'],
  ['immune', 'Gengar', 'Snorlax', 'shadowball'],
  ['resist', 'Pikachu', 'Bulbasaur', 'thunderbolt'],
  ['fire-super', 'Charmander', 'Bulbasaur', 'ember'],
  ['water-neutral', 'Blastoise', 'Snorlax', 'surf'],
];
const seed = [1, 2, 3, 4];
const format = { id: 'betteraidamage', name: 'Better AI Damage Oracle', mod: 'cobblemon',
  gameType: 'singles', ruleset: [], gen: 9 };
const set = (species, move, level, hpEv = 0) => ({ species, name: species, level, gender: 'M',
  ability: '', item: '', nature: 'Serious', moves: [move],
  movesInfo: [{ pp: Dex.mod('cobblemon').moves.get(move).pp, maxPp: Dex.mod('cobblemon').moves.get(move).pp }],
  evs: { hp: hpEv, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
  ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 },
});
const cases = fixtures.flatMap(([family, attacker, defender, moveId]) =>
  [1, 5, 25, 50, 75, 100].flatMap(level => [0, 4, 252].map(hpEv => {
  const id = `${family}-L${level}-hpEV${hpEv}`;
  const battle = new Battle({ format, seed });
  try {
    battle.setPlayer('p1', { name: 'p1', team: [set(attacker, moveId, level)] });
    battle.setPlayer('p2', { name: 'p2', team: [set(defender, 'splash', level, hpEv)] });
    const source = battle.p1.active[0];
    const target = battle.p2.active[0];
    const template = battle.dex.getActiveMove(moveId);
    const physical = template.category === 'Physical';
    const input = { level, power: template.basePower,
      attack: source.calculateStat(physical ? 'atk' : 'spa', 0, 1),
      defence: target.calculateStat(physical ? 'def' : 'spd', 0, 1),
      maxHp: target.maxhp, stab: source.hasType(template.type) ? 1.5 : 1,
      typeMultiplier: target.runImmunity(template.type) ? 2 ** target.runEffectiveness(template) : 0 };
    const rolls = [];
    for (let roll = 85; roll <= 100; roll++) {
      // Control only the RNG return value, NOT the native damage formula or randomizer.
      battle.random = n => { if (n !== 16) throw new Error(`Unexpected RNG request ${n}`); return 100 - roll; };
      const move = battle.dex.getActiveMove(moveId);
      move.willCrit = false;
      const damage = battle.actions.getDamage(source, target, move, true);
      if (damage !== false && !Number.isInteger(damage)) throw new Error('Unsupported damage result');
      rolls.push(damage === false ? 0 : damage);
    }
    return { id, attacker, defender, moveId, hpEv, input, rolls };
  } finally { battle.destroy(); }
})));
process.stdout.write(JSON.stringify({ status: 'COMPLETE', nodeVersion: process.version,
  format, seed, scope: 'BASE_NON_CRITICAL_DAMAGE_KERNEL_ONLY',
  exclusions: ['accuracy', 'natural-critical-chance', 'abilities', 'items', 'status', 'weather',
    'field', 'spread', 'turn-order', 'entry', 'end-turn', 'runtime-registrations'], cases }));
