'use strict';

// Native mechanics fixture. Exact referee HP stays in test output, never Brain input.
const path = require('node:path');
const { Battle } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const dex = Dex.mod('cobblemon');
const format = { id: 'betteraidamagetransfer', name: 'Better AI Damage Transfer',
  mod: 'cobblemon', gameType: 'singles', ruleset: [], gen: 9 };
function set(species, move, index) {
  const pp = dex.moves.get(move).pp;
  return { species, level: 50, moves: [move], ability: 'Illuminate', item: '', nature: 'Serious', gender: 'M',
    uuid: `00000000-0000-0000-0000-00000000000${index}`, movesInfo: [{ pp, maxPp: pp }],
    evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
    ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } };
}
function run(move, lowTarget) {
  const battle = new Battle({ format, seed: [1, 2, 3, 4] });
  try {
    battle.setPlayer('p1', { name: 'p1', team: [set(lowTarget ? 'Blissey' : 'Snorlax', move, 1)] });
    battle.setPlayer('p2', { name: 'p2', team: [set(lowTarget ? 'Snorlax' : 'Blissey', 'splash', 2)] });
    const actor = battle.p1.active[0];
    const target = battle.p2.active[0];
    actor.hp = Math.floor(actor.maxhp / 2);
    if (lowTarget) target.hp = 7;
    const actorBefore = actor.hp;
    const targetBefore = target.hp;
    const definition = dex.moves.get(move);
    const kind = definition.drain ? 'drain' : 'recoil';
    const ratio = definition.drain || definition.recoil;
    const start = battle.log.length;
    if (!battle.choose('p1', 'move 1') || !battle.choose('p2', 'move 1')) throw new Error('rejected choice');
    const publicLog = battle.log.slice(start).filter(line => !line.startsWith('|pp_update|'));
    const selfLine = publicLog.find(line => line.startsWith('|-heal|p1a:') || line.startsWith('|-damage|p1a:'));
    if (!selfLine) throw new Error(`${move}: no self HP event`);
    return { id: `${move}-${lowTarget ? 'low' : 'full'}`, kind, numerator: ratio[0], denominator: ratio[1],
      actorMax: actor.maxhp, targetMax: target.maxhp, actorBefore, targetBefore,
      actorAfter: actor.hp, targetAfter: target.hp, selfEvent: selfLine.split('|')[1], publicLog };
  } finally { battle.destroy(); }
}
process.stdout.write(JSON.stringify({ cases: ['gigadrain', 'drainingkiss', 'doubleedge', 'takedown']
  .flatMap(move => [run(move, false), run(move, true)]) }));
