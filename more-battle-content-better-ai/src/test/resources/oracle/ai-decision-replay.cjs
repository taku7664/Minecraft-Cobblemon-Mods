'use strict';

const path = require('node:path');
const { Battle, extractChannelMessages } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
// ASCII transport avoids platform-specific command-line quote stripping; not encryption.
const input = JSON.parse(Buffer.from(process.argv[3], 'base64').toString('utf8'));
if (!Array.isArray(input.choices) || input.choices.some(choice => typeof choice !== 'string') ||
    typeof input.hiddenVariant !== 'boolean') throw new Error('Expected choices:string[] and hiddenVariant:boolean');
const seed = [1, 2, 3, 4];
const format = { id: 'betteraidecisionreplay', name: 'Better AI Decision Replay',
  mod: 'cobblemon', gameType: 'singles', ruleset: [], gen: 9 };
const dex = Dex.mod('cobblemon');
const set = (name, species, level, moves, ability, item = '') => ({
  name, species, level, moves, ability, item, gender: 'M', nature: 'Serious',
  uuid: `00000000-0000-0000-0000-00000000000${['Lead', 'Target', 'Backup'].indexOf(name) + 1}`,
  movesInfo: moves.map(id => ({ pp: dex.moves.get(id).pp, maxPp: dex.moves.get(id).pp })),
  evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
  ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 },
});
const team1 = [set('Lead', 'Pikachu', 50, ['thunderbolt', 'quickattack'], 'Static')];
const team2 = [set('Target', 'Magikarp', 5, input.hiddenVariant ? ['splash', 'tackle'] : ['splash'],
  'Swift Swim', input.hiddenVariant ? 'Heavy-Duty Boots' : ''),
  set('Backup', 'Magikarp', 5, ['splash'], 'Swift Swim')];
const battle = new Battle({ format, seed });
// Cobblemon pp_update is not split-private; spectator extraction alone is insufficient.
const publicEvents = new Set(['gametype', 'player', 'teamsize', 'gen', 'tier', 'start',
  'switch', 'move', '-damage', '-heal', 'faint', 'win', 'turn', '-supereffective',
  '-resisted', '-immune', '-crit', '-fail', 'cant', '-status']);
const publicObservation = () => extractChannelMessages(battle.log.join('\n'), [0])[0]
  .filter(line => publicEvents.has(line.split('|')[1]));
const apply = (side, choice) => {
  if (!battle.choose(side, choice)) throw new Error(`Native engine rejected ${side} ${choice}`);
};
const replaceOpponent = () => {
  while (!battle.ended && battle.p2.requestState === 'switch') {
    const slot = battle.p2.pokemon.findIndex(pokemon => !pokemon.fainted && !pokemon.isActive);
    if (slot < 0) throw new Error('Opponent replacement request has no living reserve');
    apply('p2', `switch ${slot + 1}`);
  }
};
try {
  battle.setPlayer('p1', { name: 'p1', team: team1 });
  battle.setPlayer('p2', { name: 'p2', team: team2 });
  const appliedChoices = [];
  for (const choice of input.choices) {
    if (battle.ended) throw new Error('Choices continue after battle end');
    if (battle.p1.requestState !== 'move' || battle.p2.requestState !== 'move') {
      throw new Error('Replay supports only current attacking-move requests');
    }
    // Never invent or substitute a p1 decision. The caller owns every submitted action.
    if (!/^move [12]$/.test(choice)) throw new Error('Fixture only accepts move 1 or move 2');
    apply('p1', choice);
    apply('p2', 'move 1');
    appliedChoices.push(choice);
    replaceOpponent();
  }
  const publicLog = publicObservation();
  let decisionInput = null;
  if (!battle.ended) {
    const request = battle.p1.activeRequest;
    const ownRequest = request?.side?.pokemon?.find(pokemon => pokemon.active);
    const movesRequest = request?.active?.[0]?.moves;
    if (battle.p1.requestState !== 'move' || !ownRequest || !movesRequest) throw new Error('Missing own move request');
    const details = ownRequest.details.split(',').map(part => part.trim());
    const species = dex.species.get(details[0]);
    const condition = ownRequest.condition.split(' ')[0].split('/').map(Number);
    if (condition.length !== 2 || !condition.every(Number.isFinite) || condition[1] <= 0) {
      throw new Error('Unsupported own HP request');
    }
    const teamSize = publicLog.find(line => line.startsWith('|teamsize|p2|'));
    if (!teamSize) throw new Error('Missing public opponent team size');
    const opponentRemaining = Number(teamSize.split('|')[3]) -
      publicLog.filter(line => line.startsWith('|faint|p2')).length;
    const publicSpecies = {};
    for (const line of publicLog.filter(line => line.startsWith('|switch|'))) {
      const revealedSpecies = dex.species.get(line.split('|')[3].split(',')[0].trim());
      if (!revealedSpecies.exists) throw new Error('Unknown publicly switched species');
      publicSpecies[revealedSpecies.id] = { types: revealedSpecies.types.map(type => type.toLowerCase()) };
    }
    decisionInput = { turn: battle.turn,
      own: { speciesId: species.id, level: Number(details.find(part => /^L\d+$/.test(part))?.slice(1) || 100),
        hpFraction: condition[0] / condition[1], fainted: false,
        types: species.types.map(type => type.toLowerCase()),
        moves: movesRequest.map((move, slot) => {
          const metadata = dex.moves.get(move.id);
          return { slot, id: metadata.id, power: metadata.basePower,
            accuracyProbability: metadata.accuracy === true ? 1 : metadata.accuracy / 100,
            category: metadata.category.toLowerCase(), typeId: metadata.type.toLowerCase(),
            priority: metadata.priority, disabled: !!move.disabled, pp: move.pp };
        }) }, publicLog, opponentRemaining, publicSpecies };
  }
  process.stdout.write(JSON.stringify({ status: battle.ended ? 'COMPLETE' : 'WAITING',
    nodeVersion: process.version, format, seed, winner: battle.winner || null,
    decisionInput, publicLog, appliedChoices,
    scope: 'SCRIPTED_OPPONENT_NATIVE_REPLAY_EXTERNAL_P1_DECISIONS_ONLY_NOT_BATTLE_QUALITY',
    // Private fixture definitions remain outside the decision-input boundary.
    refereeTeams: { p1: team1, p2: team2 } }));
} finally { battle.destroy(); }
