'use strict';

const path = require('node:path');
const { Battle, extractChannelMessages } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const dex = Dex.mod('cobblemon');
const format = { id: 'betteraistateoracle', name: 'Better AI State Oracle',
  mod: 'cobblemon', gameType: 'singles', ruleset: [], gen: 9 };
const set = (level, moves, index) => ({ species: 'Smeargle', level, ability: 'Own Tempo',
  item: '', nature: 'Serious', gender: 'M', moves,
  uuid: `00000000-0000-0000-0000-00000000000${index}`,
  movesInfo: moves.map(id => ({ pp: dex.moves.get(id).pp, maxPp: dex.moves.get(id).pp })),
  evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
  ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } });
const allowed = new Set(['switch', 'move', '-start', '-end', '-activate', '-fail', '-damage', 'faint', 'turn']);
function run(id) {
  const battle = new Battle({ format, seed: [1, 2, 3, 4] });
  const log = () => extractChannelMessages(battle.log.join('\n'), [0])[0]
    .filter(line => allowed.has(line.split('|')[1]));
  const choose = (side, action) => {
    if (!battle.choose(side, action)) throw new Error(`${id}: rejected ${side} ${action}`);
  };
  try {
    battle.setPlayer('p1', { name: 'p1', team: [
      set(50, ['substitute', 'batonpass', 'shedtail', 'splash'], 1), set(50, ['splash'], 2)] });
    battle.setPlayer('p2', { name: 'p2', team: [set(100, ['splash', 'seismictoss'], 3)] });
    choose('p1', id === 'shedtail' ? 'move 3' : 'move 1');
    choose('p2', 'move 1');
    if (!battle.p1.active[0].volatiles.substitute) throw new Error(`${id}: setup did not create substitute`);
    let before = log().length;
    if (id === 'shedtail') {
      choose('p1', 'switch 2');
    } else if (id === 'batonpass') {
      choose('p1', 'move 2');
      choose('p2', 'move 1');
      before = log().length;
      choose('p1', 'switch 2');
    } else {
      choose('p1', id === 'ordinary-switch' ? 'switch 2' : 'move 1');
      choose('p2', id === 'break-recreate' ? 'move 2' : 'move 1');
    }
    return { id, events: log().slice(before),
      activeSubstitute: !!battle.p1.active[0].volatiles.substitute };
  } finally { battle.destroy(); }
}
process.stdout.write(JSON.stringify({ status: 'COMPLETE',
  cases: ['repeat', 'break-recreate', 'ordinary-switch', 'batonpass', 'shedtail'].map(run) }));
