'use strict';

const path = require('node:path');
const { Battle } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const seed = [1, 2, 3, 4];
const format = { id: 'betteraiweatherresidual', name: 'Better AI Weather Residual Oracle',
  mod: 'cobblemon', gameType: 'singles', ruleset: [], gen: 9 };
// Synthetic, sometimes illegal species/ability combinations isolate mechanics.
const profiles = [
  { id: 'ordinary' },
  { id: 'leftovers', item: 'leftovers' },
  { id: 'poisonheal-leftovers', ability: 'poisonheal', item: 'leftovers', status: 'psn' },
  { id: 'rock', species: 'Sudowoodo' },
  { id: 'ground', species: 'Sandslash' },
  { id: 'steel', species: 'Registeel' },
  ...['magicguard', 'overcoat', 'sandveil', 'sandrush', 'sandforce'].map(ability => ({ id: ability, ability })),
  { id: 'safetygoggles', item: 'safetygoggles' },
  { id: 'opponent-cloudnine', opponentAbility: 'cloudnine' },
  { id: 'opponent-airlock', opponentAbility: 'airlock' },
  { id: 'snow-control', weather: 'snow' },
];
const set = (species, level, ability = '', item = '') => {
  const pp = Dex.mod('cobblemon').moves.get('splash').pp;
  return { species, name: species, level, gender: 'M', ability, item,
    nature: 'Serious', moves: ['splash'], movesInfo: [{ pp, maxPp: pp }],
    evs: { hp: 0, atk: 0, def: 0, spa: 0, spd: 0, spe: 0 },
    ivs: { hp: 31, atk: 31, def: 31, spa: 31, spd: 31, spe: 31 } };
};
const pokemonInput = pokemon => ({ maxHp: pokemon.maxhp, hp: pokemon.hp,
  status: pokemon.status || null, ability: pokemon.ability, item: pokemon.item,
  types: pokemon.getTypes().map(type => type.toLowerCase()), level: pokemon.level, speciesId: pokemon.species.id });
const cases = profiles.flatMap(profile => [50, 1].flatMap(level => ['full', 'one'].flatMap(hpMode =>
  [2, 1].map(remainingTurns => {
    const id = `${profile.id}-L${level}-${hpMode}-duration${remainingTurns}`;
    const battle = new Battle({ format, seed });
    try {
      battle.setPlayer('p1', { name: 'p1', team: [set(profile.species || 'Snorlax', level, profile.ability, profile.item)] });
      // A healthy sand-immune opponent survives every fixture, including when own Pokemon faints.
      battle.setPlayer('p2', { name: 'p2', team: [set('Steelix', 50, profile.opponentAbility)] });
      const own = battle.p1.active[0];
      const opponent = battle.p2.active[0];
      own.hp = hpMode === 'one' ? 1 : own.maxhp;
      if (profile.status && !own.setStatus(profile.status)) throw new Error(`${id}: could not establish status`);
      const weather = profile.weather || 'sandstorm';
      if (!battle.field.setWeather(weather, own)) throw new Error(`${id}: could not establish weather`);
      battle.field.weatherState.duration = remainingTurns;
      const input = { own: pokemonInput(own), opponent: pokemonInput(opponent), weather, remainingTurns };
      const initialTurn = battle.turn;
      const start = battle.log.length;
      for (const side of ['p1', 'p2']) {
        if (!battle.choose(side, 'move 1')) throw new Error(`${id}: rejected ${side} move 1`);
      }
      const refereeTurnLog = battle.log.slice(start);
      const moves = refereeTurnLog.filter(line => line.startsWith('|move|'));
      if (moves.length !== 2 || (!battle.ended && battle.turn !== initialTurn + 1) ||
          own.hp < 0 || own.hp > own.maxhp || own.fainted !== (own.hp === 0) ||
          opponent.hp !== opponent.maxhp || opponent.fainted) {
        throw new Error(`${id}: native weather turn did not complete: ${JSON.stringify(refereeTurnLog)}`);
      }
      return { id, input, result: { hp: own.hp, fainted: own.fainted,
        weather: battle.field.weather || null,
        remainingTurns: battle.field.weather ? battle.field.weatherState.duration : null }, refereeTurnLog };
    } finally { battle.destroy(); }
  }))));
process.stdout.write(JSON.stringify({ status: 'COMPLETE', nodeVersion: process.version, format, seed,
  scope: 'SYNTHETIC_NATIVE_SAND_RESIDUAL_IMMUNITY_SUPPRESSION_EXPIRY_AND_SNOW_CONTROL',
  exclusions: ['legal-team-presets', 'weather-infliction-chance', 'weather-damage-stat-modifiers',
    'hail', 'other-abilities', 'other-items', 'other-residual-effects', 'doubles',
    'runtime-registrations', 'live-battle-quality'], cases }));
