'use strict';

const path = require('node:path');
const { Battle, extractChannelMessages } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const seed = [1, 2, 3, 4];
const format = { id: 'betteraitypechange', name: 'Better AI Type Change Oracle',
  mod: 'cobblemon', gameType: 'singles', ruleset: [], gen: 9 };
const dex = Dex.mod('cobblemon');
const set = (species, moves, index) => ({ species, level: 50, ability: '', item: '',
  nature: 'Serious', gender: 'M', moves,
  uuid: `00000000-0000-0000-0000-00000000000${index}`,
  movesInfo: moves.map(id => ({ pp: dex.moves.get(id).pp, maxPp: dex.moves.get(id).pp })),
  evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
  ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } });
const publicEvents = new Set(['gametype', 'player', 'teamsize', 'gen', 'tier', 'start',
  'switch', 'move', '-damage', '-heal', 'faint', 'win', 'turn', '-supereffective',
  '-resisted', '-immune', '-crit', '-fail', 'cant', '-status', '-start', '-end']);
function fixture(id, moves, steps) {
  const battle = new Battle({ format, seed });
  try {
    battle.setPlayer('p1', { name: 'p1', team: [set('Smeargle', moves, 1)] });
    battle.setPlayer('p2', { name: 'p2', team: [set('Pikachu', ['splash'], 2), set('Snorlax', ['splash'], 3)] });
    const checkpoints = [];
    function checkpoint(name, expectedP1, expectedP2) {
      const p1 = battle.p1.active[0];
      const p2 = battle.p2.active[0];
      const p1Types = p1.getTypes().map(type => type.toLowerCase());
      const p2Types = p2.getTypes().map(type => type.toLowerCase());
      if (JSON.stringify(p1Types) !== JSON.stringify(expectedP1) || JSON.stringify(p2Types) !== JSON.stringify(expectedP2)) {
        throw new Error(`${id}/${name}: unexpected native types ${JSON.stringify({ p1Types, p2Types })}`);
      }
      const publicLog = extractChannelMessages(battle.log.join('\n'), [0])[0]
        .filter(line => publicEvents.has(line.split('|')[1]));
      checkpoints.push({ id: name, publicLog,
        referee: { p1Types, p2Types, p1Ident: p1.fullname, p2Ident: p2.fullname } });
    }
    checkpoint('initial', ['normal'], ['electric']);
    for (const step of steps) {
      for (const [side, choice] of [['p1', step.p1], ['p2', step.p2 || 'move 1']]) {
        if (!battle.choose(side, choice)) throw new Error(`${id}/${step.id}: rejected ${side} ${choice}`);
      }
      checkpoint(step.id, step.p1Types || ['normal'], step.p2Types);
      if (step.expectedEvent) {
        const last = checkpoints[checkpoints.length - 1];
        const before = checkpoints[checkpoints.length - 2].publicLog.length;
        if (!last.publicLog.slice(before).some(line => line.includes(step.expectedEvent))) {
          throw new Error(`${id}/${step.id}: missing public event ${step.expectedEvent}`);
        }
      }
    }
    return { id, checkpoints };
  } finally { battle.destroy(); }
}
const cases = [
  fixture('soak-switch-reset', ['soak', 'splash'], [
    { id: 'soaked', p1: 'move 1', p2Types: ['water'], expectedEvent: '|typechange|Water' },
    { id: 'switched-out', p1: 'move 2', p2: 'switch 2', p2Types: ['normal'] },
    { id: 'switched-back', p1: 'move 2', p2: 'switch 2', p2Types: ['electric'] },
  ]),
  fixture('added-type-replacement', ['forestscurse', 'trickortreat', 'soak'], [
    { id: 'grass-added', p1: 'move 1', p2Types: ['electric', 'grass'], expectedEvent: '|typeadd|Grass' },
    { id: 'ghost-replaces-grass', p1: 'move 2', p2Types: ['electric', 'ghost'], expectedEvent: '|typeadd|Ghost' },
    { id: 'soak-clears-added-type', p1: 'move 3', p2Types: ['water'], expectedEvent: '|typechange|Water' },
  ]),
  fixture('reflect-type-unexplicit', ['reflecttype'], [
    { id: 'reflected', p1: 'move 1', p1Types: ['electric'], p2Types: ['electric'],
      expectedEvent: '|typechange|[from] move: Reflect Type|' },
  ]),
];
process.stdout.write(JSON.stringify({ status: 'COMPLETE', nodeVersion: process.version, format, seed,
  scope: 'SCRIPTED_NATIVE_PUBLIC_TYPE_PROTOCOL_AND_SWITCH_RESET_NOT_AI_QUALITY',
  exclusions: ['legal-team-presets', 'doubles', 'tera', 'transform', 'illusion', 'other-type-mechanics',
    'runtime-registrations', 'live-battle-quality'], cases }));
