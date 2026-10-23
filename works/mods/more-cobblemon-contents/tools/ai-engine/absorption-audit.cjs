'use strict';
// Native TryHit fixtures for LocalMoveAbsorptionNativeParityTest. No files or game processes are changed.
const path = require('node:path');
const { load } = require('./showdown-runtime.cjs');
const root = process.argv[2];
const runtime = load(root);
const fixtures = [
  ['voltabsorb', 'thunderbolt'], ['waterabsorb', 'watergun'], ['dryskin', 'watergun'],
  ['eartheater', 'earthquake'], ['sapsipper', 'vinewhip'], ['motordrive', 'thunderbolt'],
  ['lightningrod', 'thunderbolt'], ['stormdrain', 'watergun'], ['wellbakedbody', 'ember'],
  ['flashfire', 'ember'], ['windrider', 'gust'],
];
// Detect newly added healing/boosting absorptions instead of silently leaving them outside the fixtures.
const { Abilities } = require(path.join(root, 'data/abilities.js'));
const discovered = Object.entries(Abilities).filter(([, ability]) => {
  if (ability.num <= 0 || ['CAP', 'LGPE', 'Custom', 'Future', 'Past'].includes(ability.isNonstandard)) return false;
  const code = String(ability.onTryHit || '');
  return /this\.(heal|boost)\(/.test(code) && /return null/.test(code);
}).map(([id]) => id);
const missing = discovered.filter(id => !fixtures.some(([known]) => id === known));
if (missing.length) throw new Error(`Absorption fixtures missing: ${missing.join(', ')}`);
const cases = [];
for (const [ability, move] of fixtures) {
  for (const mode of ['ordinary', 'full', 'healblock', 'moldbreaker', 'shield', 'gastroacid', 'dynamax', 'gas']) {
    const battle = new runtime.Battle({ format: { id: 'gen9customgame', name: 'Absorption audit', mod: 'gen9',
      gameType: 'singles', ruleset: [] }, seed: [3, 1, 4, 1], strictChoices: true });
    const set = (trait, moves, item = '') => ({ species: 'Mew', level: 50, ability: trait, moves,
      movesInfo: moves.map(() => ({ pp: 20, maxPp: 20 })), item, uuid: trait });
    battle.setPlayer('p1', { name: 'p1', team: [set(ability, [mode === 'dynamax' ? 'tackle' : 'splash'],
      mode === 'shield' ? 'abilityshield' : '')] });
    battle.setPlayer('p2', { name: 'p2', team: [set(['moldbreaker', 'shield'].includes(mode) ? 'moldbreaker' :
      mode === 'gas' ? 'neutralizinggas' : 'none', [move])] });
    const target = battle.p1.active[0];
    target.baseMaxhp = 146; target.maxhp = 146; target.hp = mode === 'full' ? 140 : 45;
    if (mode === 'healblock') target.addVolatile('healblock');
    if (mode === 'gastroacid') target.addVolatile('gastroacid');
    if (mode === 'dynamax') target.addVolatile('dynamax');
    const before = { hp: target.hp, maxhp: target.maxhp };
    if (!battle.p1.choose('move 1') || !battle.p2.choose('move 1')) throw new Error(`Illegal fixture: ${ability}/${mode}`);
    battle.commitDecisions();
    const details = runtime.dex.moves.get(move);
    cases.push({ ability, mode, move, type: details.type, flags: Object.keys(details.flags).filter(f => details.flags[f]),
      before, after: { hp: target.hp, maxhp: target.maxhp, boosts: target.boosts, flashfire: !!target.volatiles.flashfire } });
  }
}
process.stdout.write(JSON.stringify({ discovered, cases }));
