'use strict';
// Referee for the Kotlin AI engine: plays scripted battles on the dev server's Showdown and prints their
// protocol logs, so the engine can be compared with it line by line under the same seed.
// Usage: node referee.cjs --showdown <dev-server/showdown> [--msd <dir>] --in <scenarios.json> --out <result.json>
//
// scenarios.json: { "scenarios": [ { "id", "gameType", "seed": [4 ints], "p1": [sets], "p2": [sets],
//                   "turns": [ ["move 1", "move 2"], ... ] } ] }
// A set is a Showdown PokemonSet plus Cobblemon's `uuid` and `movesInfo` ([{pp, maxPp}]).
const fs = require('node:fs');
const { load } = require('./showdown-runtime.cjs');

const args = process.argv.slice(2);
const arg = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : null; };
const runtime = load(arg('--showdown'), arg('--msd'));
const input = JSON.parse(fs.readFileSync(arg('--in'), 'utf8'));

function fillSet(set) {
  const copy = JSON.parse(JSON.stringify(set));
  if (!copy.movesInfo) {
    copy.movesInfo = copy.moves.map((id) => {
      const move = runtime.dex.moves.get(id);
      const pp = move.noPPBoosts ? move.pp : Math.floor(move.pp * 8 / 5);
      return { pp, maxPp: pp };
    });
  }
  return copy;
}

// Scripts stay usable when the battle goes its own way: a side with nothing to choose is skipped, a side
// that must replace a fainted Pokemon switches in the first one available, and a choice Showdown rejects
// (a disabled or locked move) falls back to the default choice. The engine test harness does the same.
// One choice per active slot: slots that must be replaced take the next healthy bench Pokemon, others pass.
function firstSwitch(side) {
  const used = new Set();
  return side.active.map((pokemon) => {
    if (!pokemon || !pokemon.switchFlag) return 'pass';
    for (let i = side.active.length; i < side.pokemon.length; i++) {
      if (!side.pokemon[i].fainted && !used.has(i)) { used.add(i); return 'switch ' + (i + 1); }
    }
    return 'pass';
  }).join(', ');
}

function choose(side, input) {
  const state = side.requestState;
  if (!state) return;
  let choice = input || 'default';
  // Showdown's own auto-switch crashes on an undefined slot, so a move or default choice at a switch
  // request becomes an explicit one. Choices made of switches and passes are kept as given.
  if (state === 'switch' && (choice === 'default' || choice.includes('move'))) choice = firstSwitch(side);
  try {
    if (side.choose(choice)) return;
  } catch (e) {
    rejections.push(`turn ${side.battle.turn} ${side.id}: '${choice}' rejected (${e.message})`);
  }
  side.choose(state === 'switch' ? firstSwitch(side) : 'default');
}

let rejections = [];

// State the protocol log does not show, compared after every step: team order, HP, and the request's
// move list with targets (which reveals move locks). The engine harness builds the same line.
function stateLine(battle) {
  return battle.sides.map((side) => {
    const team = side.pokemon.map((p) => `${p.uuid}:${p.hp}:${p.fainted ? 'F' : ''}`).join(',');
    const request = side.activeRequest;
    let moves = '';
    if (request && request.active) {
      moves = request.active.map((a) => a ? a.moves.map((m) => `${m.id}/${m.target || ''}/${m.disabled ? 1 : 0}`).join('+') : '-').join('|');
    } else if (request && request.forceSwitch) {
      moves = 'switch:' + request.forceSwitch.map((f) => f ? 1 : 0).join('');
    } else if (request && request.wait) {
      moves = 'wait';
    }
    return `${side.id}[${team}]{${moves}}`;
  }).join(' ') + ' rng=' + battle.prng.seed.join('.');
}

function run(scenario) {
  const format = { id: 'aienginereferee', name: scenario.formatName || 'Cobblemon Singles', mod: 'cobblemon',
    gameType: scenario.gameType || 'singles', ruleset: [] };
  const battle = new runtime.Battle({ format, seed: scenario.seed, strictChoices: true });
  const result = { id: scenario.id };
  rejections = [];
  result.rejections = rejections;
  result.states = [];
  if (scenario.traceRng) {
    result.rngTrace = [];
    const prng = battle.prng;
    const original = prng.next.bind(prng);
    prng.next = function(from, to) {
      const value = original(from, to);
      const frames = new Error().stack.split('\n').slice(2, 8).map((l) => (l.match(/at ([^ ]+)/) || [])[1]).filter(Boolean)
        .filter((f) => !/PRNG\.|Battle\.random|Battle\.sample|randomChance|shuffle/.test(f)).slice(0, 2);
      result.rngTrace.push(`${from === undefined ? 'null' : from},${to === undefined ? 'null' : to}=${value} @${frames.join('<')}`);
      return value;
    };
  }
  try {
    battle.setPlayer('p1', { name: 'p1', team: scenario.p1.map(fillSet) });
    battle.setPlayer('p2', { name: 'p2', team: scenario.p2.map(fillSet) });
    for (const choices of scenario.turns || []) {
      if (battle.ended) break;
      choices.forEach((input, i) => choose(battle.sides[i], input));
      battle.commitDecisions();
      result.states.push(stateLine(battle));
    }
  } catch (error) {
    result.error = String(error && error.stack || error);
  }
  result.log = battle.log.filter((line) => !line.startsWith('|t:|'));
  result.ended = battle.ended;
  result.turn = battle.turn;
  result.seed = battle.prng.seed.slice();
  battle.destroy();
  return result;
}

const results = input.scenarios.map(run);
fs.writeFileSync(arg('--out'), JSON.stringify({ msdPatch: runtime.msdPatch, results }));
