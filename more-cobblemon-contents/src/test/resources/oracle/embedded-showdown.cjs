'use strict';

// Independent referee: imports only the embedded engine, never Better AI's projector.
const path = require('node:path');
const { Battle, extractChannelMessages } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const hiddenVariant = process.argv[3] === 'true';
const seed = [1, 2, 3, 4];
const format = { id: 'betteraioracle', name: 'Better AI Oracle', mod: 'cobblemon',
  gameType: 'singles', ruleset: [], gen: 9 };
const set = (name, species, level, moves, ability, item = '') => ({
  name, species, level, moves, ability, item, nature: 'Serious', gender: 'M',
  uuid: `00000000-0000-0000-0000-00000000000${['Lead', 'Reserve', 'Target', 'Backup'].indexOf(name) + 1}`,
  // Cobblemon requires explicit current/max PP; upstream set defaults are insufficient.
  movesInfo: moves.map(id => { const pp = Dex.mod('cobblemon').moves.get(id).pp; return { pp, maxPp: pp }; }),
  evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
  ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 },
});
const team1 = [set('Lead', 'Pikachu', 50, ['thunderbolt'], 'Static'),
  set('Reserve', 'Raichu', 50, ['thunderbolt'], 'Static')];
const team2 = [set('Target', 'Magikarp', 5, hiddenVariant ? ['splash', 'tackle'] : ['splash'],
  'Swift Swim', hiddenVariant ? 'Heavy-Duty Boots' : ''), set('Backup', 'Magikarp', 5, ['splash'], 'Swift Swim')];
const battle = new Battle({ format, seed });
// Cobblemon pp_update is NOT split-private: spectator filtering alone leaks unrevealed moves.
// Fail closed on unknown/custom events; extend only with a public-evidence regression test.
const publicEvents = new Set(['gametype', 'player', 'teamsize', 'gen', 'tier', 'start',
  'switch', 'move', '-damage', '-heal', 'faint', 'win', 'turn', '-supereffective',
  '-resisted', '-immune', '-crit', '-fail', 'cant', '-status']);
const publicObservation = () => extractChannelMessages(battle.log.join('\n'), [0])[0]
  .filter(line => publicEvents.has(line.split('|')[1]));
try {
  battle.setPlayer('p1', { name: 'p1', team: team1 });
  battle.setPlayer('p2', { name: 'p2', team: team2 });
  const initialPublicObservation = publicObservation();
  const commands = [
    ['p1', 'switch 2'], ['p2', 'move 1'],
    ['p1', 'move 1'], ['p2', 'move 1'],
    ['p2', 'switch 2'],
    ['p1', 'move 1'], ['p2', 'move 1'],
  ];
  for (const [side, choice] of commands) {
    if (!battle.choose(side, choice)) throw new Error(`Oracle rejected ${side} ${choice}`);
  }
  if (!battle.ended || battle.winner !== 'p1') throw new Error('Script did not finish as expected');
  process.stdout.write(JSON.stringify({
    status: 'COMPLETE', nodeVersion: process.version, format, seed, commands,
    winner: battle.winner, turn: battle.turn, addonRegistrationsLoaded: false,
    coverage: 'EMBEDDED_COBBLEMON_BASE_REGISTRY_ONLY_SCRIPTED_SINGLES',
    initialPublicObservation, publicLog: publicObservation(),
    // Privileged fixtures/state belong to the referee, NEVER to a decision input.
    refereeTeams: { p1: team1, p2: team2 }, refereeInitialOpponent: team2,
    refereeRawLog: battle.log,
    refereeFinalState: battle.sides.map(side => ({ id: side.id,
      pokemon: side.pokemon.map(p => ({ name: p.name, hp: p.hp, maxhp: p.maxhp, fainted: p.fainted })) })),
  }));
} finally {
  battle.destroy();
}
