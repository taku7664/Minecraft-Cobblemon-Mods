'use strict';

// Scripted referee fixture, not a legal-preset strength benchmark or a Brain input.
const path = require('node:path');
const { Battle, extractChannelMessages } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const dex = Dex.mod('cobblemon');
const format = { id: 'betterairetarget', name: 'Better AI Retarget Oracle',
  mod: 'cobblemon', gameType: 'doubles', ruleset: [], gen: 9 };
const set = (species, level, move, index) => ({ species, level, moves: [move],
  ability: 'Illuminate', item: '', nature: 'Serious', gender: 'M',
  uuid: `00000000-0000-0000-0000-00000000000${index}`,
  movesInfo: [{ pp: dex.moves.get(move).pp, maxPp: dex.moves.get(move).pp }],
  evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
  ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } });
const allowed = new Set(['move', '-damage', 'faint', '-fail', '-miss', 'turn']);
function run(id) {
  const battle = new Battle({ format, seed: [1, 2, 3, 4] });
  try {
    battle.setPlayer('p1', { name: 'p1', team: [
      set('Raichu', 100, 'thunderbolt', 1), set('Pikachu', 50, 'seismictoss', 2)] });
    battle.setPlayer('p2', { name: 'p2', team: [
      set('Magikarp', 5, 'splash', 3), set('Snorlax', 50, 'splash', 4)] });
    const choices = [['p1', `move 1 1, move 1 ${id === 'focused' ? 1 : 2}`],
      ['p2', 'move 1, move 1']];
    for (const [side, choice] of choices) {
      if (!battle.choose(side, choice)) throw new Error(`${id}: rejected ${side} ${choice}`);
    }
    const events = extractChannelMessages(battle.log.join('\n'), [0])[0]
      .filter(line => allowed.has(line.split('|')[1]));
    const remaining = battle.p2.pokemon.find(p => p.species.name === 'Snorlax');
    return { id, choices, events, remainingHp: remaining.hp, remainingMaxHp: remaining.maxhp };
  } finally { battle.destroy(); }
}
process.stdout.write(JSON.stringify({ status: 'COMPLETE', format, seed: [1, 2, 3, 4],
  cases: ['focused', 'split'].map(run) }));
