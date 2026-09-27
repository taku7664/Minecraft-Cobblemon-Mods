'use strict';

// This fixture verifies the exact primitive the production branch simulator will rely on.
// It imports only Cobblemon's bundled Showdown engine and never Better AI's projector.
const path = require('node:path');
const crypto = require('node:crypto');
const { Battle } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const dex = Dex.mod('cobblemon');
dex.includeData();

const seed = [17, 29, 41, 53];
const set = (name, species, moves, ability, uuidSuffix) => ({
  name, species, level: 50, moves, ability, item: '', nature: 'Serious', gender: 'M',
  uuid: `00000000-0000-0000-0000-${String(uuidSuffix).padStart(12, '0')}`,
  movesInfo: moves.map(id => {
    const move = dex.moves.get(id);
    if (!move.exists) throw new Error(`Unknown move ${id}`);
    return { pp: move.pp, maxPp: move.pp };
  }),
  evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
  ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 },
});

const makeSource = ability => {
  const battle = new Battle({ formatid: 'cobblemonsingles', seed });
  battle.setPlayer('p1', { name: 'p1', team: [set('Actor', 'Scizor', ['bulletpunch', 'swordsdance'], ability, 1)] });
  battle.setPlayer('p2', { name: 'p2', team: [set('Target', 'Mew', ['splash'], 'Synchronize', 2)] });
  if (battle.requestState !== 'move') throw new Error(`Unexpected initial request ${battle.requestState}`);
  // Advance one complete turn so the snapshot carries changed HP, PP, log, turn and PRNG state.
  if (!battle.choose('p1', 'move 1')) throw new Error('Source rejected p1 move 1');
  if (!battle.choose('p2', 'move 1')) throw new Error('Source rejected p2 move 1');
  if (battle.turn !== 2 || battle.requestState !== 'move') {
    throw new Error(`Expected an intermediate turn-2 request, got turn=${battle.turn} request=${battle.requestState}`);
  }
  return battle;
};

const view = battle => ({
  turn: battle.turn,
  requestState: battle.requestState,
  p1Hp: battle.p1.active[0].hp,
  p2Hp: battle.p2.active[0].hp,
  p2MaxHp: battle.p2.active[0].maxhp,
  p1AttackBoost: battle.p1.active[0].boosts.atk,
  p1MovePp: battle.p1.active[0].moveSlots.map(slot => slot.pp),
  p2MovePp: battle.p2.active[0].moveSlots.map(slot => slot.pp),
  ended: battle.ended,
});

const stateDigest = battle => {
  const state = battle.toJSON();
  // Showdown documents |t:| as the only wall-clock field that must be normalized
  // before comparing serialized states.
  state.log = state.log.map(line => line.startsWith('|t:|') ? '|t:|' : line);
  return crypto.createHash('sha256').update(JSON.stringify(state)).digest('hex');
};

const fork = (serialized, p1Choice) => {
  const battle = Battle.fromJSON(serialized);
  battle.restart(() => {});
  try {
    if (!battle.choose('p1', p1Choice)) throw new Error(`Clone rejected p1 ${p1Choice}`);
    if (!battle.choose('p2', 'move 1')) throw new Error('Clone rejected p2 move 1');
    return { view: view(battle), stateSha256: stateDigest(battle) };
  } finally {
    battle.destroy();
  }
};

const source = makeSource('Technician');
const control = makeSource('Swarm');
try {
  const sourceBefore = view(source);
  const sourceStateBefore = stateDigest(source);
  const serialized = JSON.stringify(source.toJSON());
  const controlSerialized = JSON.stringify(control.toJSON());
  const attackFirst = fork(serialized, 'move 1');
  const setupBranch = fork(serialized, 'move 2');
  const attackReplay = fork(serialized, 'move 1');
  const controlAttack = fork(controlSerialized, 'move 1');
  const sourceAfter = view(source);
  const sourceStateAfter = stateDigest(source);
  process.stdout.write(JSON.stringify({
    status: 'COMPLETE',
    cloneApi: 'Battle.toJSON/fromJSON',
    sourceBefore,
    sourceAfter,
    sourceStateBefore,
    sourceStateAfter,
    attackFirst,
    setupBranch,
    attackReplay,
    technicianDamage: sourceBefore.p2Hp - attackFirst.view.p2Hp,
    controlDamage: view(control).p2Hp - controlAttack.view.p2Hp,
    evidence: 'INDEPENDENT_BUNDLED_SHOWDOWN_BRANCH_CLONE',
  }));
} finally {
  source.destroy();
  control.destroy();
}
