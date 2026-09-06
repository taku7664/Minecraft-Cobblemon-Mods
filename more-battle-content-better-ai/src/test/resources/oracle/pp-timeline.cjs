'use strict';

// Scripted native referee observation, not a legal-preset quality benchmark or Brain input.
const path = require('node:path');
const { Battle } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const dex = Dex.mod('cobblemon');
const format = { id: 'betteraipptimeline', name: 'Better AI PP Timeline',
  mod: 'cobblemon', gameType: 'singles', ruleset: [], gen: 9 };
const set = (species, move, index) => ({ species, level: index === 3 ? 100 : 50,
  moves: [move], ability: 'Illuminate', item: '', nature: 'Serious', gender: 'M',
  uuid: `00000000-0000-0000-0000-00000000000${index}`,
  movesInfo: [{ pp: dex.moves.get(move).pp, maxPp: dex.moves.get(move).pp }],
  evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
  ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } });

function run(id, species, move, options = {}) {
  const battle = new Battle({ format, seed: [1, 2, 3, 4] });
  try {
    const own = set(species, move, 1);
    own.item = options.item || '';
    if (options.initialPp !== undefined) own.movesInfo[0].pp = options.initialPp;
    const foe = set(options.foeSpecies || 'Magikarp', options.foeMove || 'splash', 3);
    foe.ability = options.foeAbility || 'Illuminate';
    battle.setPlayer('p1', { name: 'p1', team: [own, set('Eevee', 'splash', 2)] });
    battle.setPlayer('p2', { name: 'p2', team: [foe] });
    for (const side of ['p1', 'p2']) {
      if (!battle.choose(side, 'move 1')) throw new Error(`${id}: rejected ${side}`);
    }
    const pokemon = battle.p1.pokemon.find(p => p.uuid.endsWith('1'));
    const live = pokemon.moveSlots[0];
    const base = pokemon.baseMoveSlots[0];
    const updates = battle.log.filter(line => line.startsWith(`|pp_update|p1: ${pokemon.uuid}|`));
    const published = updates.at(-1);
    if (!published) throw new Error(`${id}: no PP publication`);
    const values = Object.fromEntries(published.split('|')[3].split(',').map(entry => {
      const [name, pp] = entry.trim().split(':').map(s => s.trim());
      return [name, Number(pp)];
    }));
    return { id, requestType: battle.requestState, liveMove: live.id, livePp: live.pp,
      baseMove: base.id, basePp: base.pp, publishedPp: values[live.id], updates,
      requestMoves: battle.p1.activeRequest?.active?.[0]?.moves ?? null,
      publicLog: battle.log.filter(line => !line.startsWith('|pp_update|')) };
  } finally { battle.destroy(); }
}
function runCalledMove() {
  const battle = new Battle({ format, seed: [1, 2, 3, 4] });
  try {
    const own = set('Snorlax', 'sleeptalk', 1);
    // Comatose makes Sleep Talk deterministic without manipulating hidden sleep counters.
    own.ability = 'Comatose';
    own.moves = ['sleeptalk', 'tackle'];
    own.movesInfo = own.moves.map(move => ({ pp: dex.moves.get(move).pp, maxPp: dex.moves.get(move).pp }));
    const foe = set('Shuckle', 'splash', 3);
    foe.ability = 'Pressure';
    battle.setPlayer('p1', { name: 'p1', team: [own] });
    battle.setPlayer('p2', { name: 'p2', team: [foe] });
    if (!battle.choose('p1', 'move 1') || !battle.choose('p2', 'move 1')) throw new Error('Sleep Talk choice rejected');
    const slots = battle.p1.active[0].moveSlots;
    return { id: 'sleeptalk', callerPp: slots.find(move => move.id === 'sleeptalk').pp,
      calledPp: slots.find(move => move.id === 'tackle').pp,
      publicLog: battle.log.filter(line => !line.startsWith('|pp_update|')) };
  } finally { battle.destroy(); }
}
process.stdout.write(JSON.stringify({ status: 'COMPLETE', format, seed: [1, 2, 3, 4], cases: [
  run('ordinary', 'Snorlax', 'tackle'), run('pivot', 'Scizor', 'uturn'), run('transform', 'Ditto', 'transform'),
  run('pressure', 'Snorlax', 'tackle', { foeAbility: 'Pressure' }),
  // Shuckle moves after Snorlax, so Spite can target the Tackle used in this turn.
  run('spite', 'Snorlax', 'tackle', { foeSpecies: 'Shuckle', foeMove: 'spite' }),
  run('leppa', 'Snorlax', 'tackle', { item: 'Leppa Berry', initialPp: 1 }),
  runCalledMove(),
] }));
