'use strict';
// Native mechanics referee fixtures, not legal team presets or hidden Brain input.
const path = require('node:path');
const { Battle } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const dex = Dex.mod('cobblemon');
const format = { id: 'betteraipressurepp', name: 'Better AI Pressure PP',
  mod: 'cobblemon', gameType: 'doubles', ruleset: [], gen: 9 };
function set(species, move, ability, index) {
  return { species, level: 100, moves: [move], ability, item: '', nature: 'Serious',
    uuid: `00000000-0000-0000-0000-00000000000${index}`,
    movesInfo: [{ pp: dex.moves.get(move).pp, maxPp: dex.moves.get(move).pp }],
    evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
    ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } };
}
function run(id, move, partnerAbility = 'Illuminate') {
  const battle = new Battle({ format, seed: [1, 2, 3, 4] });
  try {
    battle.setPlayer('p1', { name: 'p1', team: [
      set('Snorlax', move, 'Illuminate', 1), set('Shuckle', 'splash', partnerAbility, 2)] });
    battle.setPlayer('p2', { name: 'p2', team: [
      set('Shuckle', 'splash', 'Pressure', 3), set('Shuckle', 'splash', 'Pressure', 4)] });
    const target = dex.moves.get(move).target === 'normal' ? ' 1' : '';
    if (!battle.choose('p1', `move 1${target}, move 1`) || !battle.choose('p2', 'move 1, move 1')) {
      throw new Error(`${id}: rejected scripted choices`);
    }
    const pokemon = battle.p1.pokemon.find(p => p.uuid.endsWith('1'));
    const pp = pokemon.moveSlots[0].pp;
    return { id, move, spent: dex.moves.get(move).pp - pp,
      publicLog: battle.log.filter(line => !line.startsWith('|pp_update|')) };
  } finally { battle.destroy(); }
}
process.stdout.write(JSON.stringify({ status: 'COMPLETE', cases: [
  run('selected', 'tackle'), run('spread', 'rockslide'), run('self', 'protect'),
  run('ally_pressure', 'earthquake', 'Pressure'), run('suppressed', 'rockslide', 'Neutralizing Gas'),
] }));
