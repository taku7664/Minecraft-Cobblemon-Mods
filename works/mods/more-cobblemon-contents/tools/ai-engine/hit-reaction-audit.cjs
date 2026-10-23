'use strict';
const { load } = require('./showdown-runtime.cjs');
const runtime = load(process.argv[2]);
if (process.argv[3] === 'passive') {
  const make = (ability, item) => {
    const set = (a, i) => ({species: 'Mew', level: 50, ability: a || 'none', item: i || '', moves: ['tackle'],
      movesInfo: [{pp: 35, maxPp: 35}], uuid: 'fixture'});
    const battle = new runtime.Battle({format: {id: 'gen9customgame', mod: 'gen9', gameType: 'singles', ruleset: []}, seed: [3, 1, 4, 1]});
    battle.setPlayer('p1', {team: [set(ability, item), set('', '')]});
    battle.setPlayer('p2', {team: [set('', ''), set('', '')]});
    for (const side of battle.sides) for (const p of side.pokemon) { p.baseMaxhp = p.maxhp = p.hp = 146; }
    return battle;
  };
  const capture = p => ({hp: p.hp, maxhp: p.maxhp, volatiles: Object.keys(p.volatiles)});
  const regeneration = [false, true].map(dynamax => {
    const battle = make('regenerator', ''); const holder = battle.p1.active[0]; holder.hp = 45;
    if (dynamax) holder.addVolatile('dynamax');
    const before = capture(holder); battle.runEvent('SwitchOut', holder);
    return {before, after: capture(holder)};
  });
  const battle = make('truant', ''); const truant = [];
  for (let turn = 1; turn <= 4; turn++) {
    const holder = battle.p1.active[0]; const before = capture(holder); const start = battle.log.length;
    battle.makeChoices('move 1', 'move 1');
    truant.push({turn, before, after: capture(holder), moved: battle.log.slice(start).some(line => line.startsWith('|move|p1a:')),
      loafed: battle.log.slice(start).some(line => line.startsWith('|cant|p1a:') && line.includes('Truant'))});
  }
  const orbBattle = make('', 'lifeorb'); const orbHolder = orbBattle.p1.active[0]; orbHolder.addVolatile('dynamax');
  const orbBefore = capture(orbHolder); orbBattle.actions.runMove('tackle', orbHolder, 0);
  process.stdout.write(JSON.stringify({regeneration, truant, orb: {before: orbBefore, after: capture(orbHolder)}}));
  process.exit(0);
}
const cases = [];
const configs = [
  { id: 'stamina', ability: 'stamina' }, { id: 'weakarmor', ability: 'weakarmor' },
  { id: 'weaknesspolicy', item: 'weaknesspolicy', move: 'xscissor' },
  { id: 'weaknesspolicy-fixed', item: 'weaknesspolicy', move: 'nightshade' },
  { id: 'sitrus', item: 'sitrusberry', hp: 81 },
  { id: 'sitrus-threshold', item: 'sitrusberry', hp: 91 },
  { id: 'sitrus-faint', item: 'sitrusberry', hp: 1 },
  { id: 'sitrus-healblock', item: 'sitrusberry', hp: 81, healblock: true },
  { id: 'sitrus-unnerve', item: 'sitrusberry', hp: 81, actorAbility: 'unnerve' },
  { id: 'lifeorb', actorItem: 'lifeorb' },
  { id: 'lifeorb-magicguard', actorItem: 'lifeorb', actorAbility: 'magicguard' },
  { id: 'lifeorb-substitute', actorItem: 'lifeorb', substitute: true },
  { id: 'stamina-twohits', ability: 'stamina', move: 'doublehit' },
  { id: 'weakarmor-twohits', ability: 'weakarmor', move: 'doublehit' },
  { id: 'weaknesspolicy-twohits', item: 'weaknesspolicy', move: 'bonemerang', types: ['electric'] },
  { id: 'sitrus-twohits', item: 'sitrusberry', hp: 81, move: 'doublehit' },
  { id: 'angerpoint', ability: 'angerpoint' },
  { id: 'berserk-threshold', ability: 'berserk', hp: 91 },
  { id: 'steamengine', ability: 'steamengine', move: 'watergun' },
  { id: 'moxie-ko', hp: 1, actorAbility: 'moxie' },
  { id: 'moxie-no-ko', actorAbility: 'moxie' },
];
const set = (ability, item, moves) => ({ species: 'Mew', level: 50, ability: ability || 'none', item: item || '',
  moves, movesInfo: moves.map(() => ({ pp: 20, maxPp: 20 })), uuid: 'fixture' });
for (const config of configs) {
  for (const critical of [false, true]) for (let roll = 85; roll <= 100; roll++) {
    const moveId = config.move || 'tackle';
    const battle = new runtime.Battle({ format: { id: 'gen9customgame', name: 'Hit reaction audit', mod: 'gen9',
      gameType: 'singles', ruleset: [] }, seed: [3, 1, 4, 1], strictChoices: true });
    battle.setPlayer('p1', { name: 'p1', team: [set(config.ability, config.item, ['splash']), set('', '', ['splash'])] });
    battle.setPlayer('p2', { name: 'p2', team: [set(config.actorAbility, config.actorItem, [moveId])] });
    const target = battle.p1.active[0], actor = battle.p2.active[0];
    for (const pokemon of [target, actor]) {
      pokemon.baseMaxhp = 146; pokemon.maxhp = 146; pokemon.hp = 146;
      for (const stat of ['atk', 'def', 'spa', 'spd', 'spe']) pokemon.storedStats[stat] = 100;
    }
    target.hp = config.hp === undefined ? 146 : config.hp;
    if (config.types) target.types = config.types;
    if (config.healblock) target.addVolatile('healblock');
    if (config.substitute) target.addVolatile('substitute');
    const capture = pokemon => ({ hp: pokemon.hp, maxhp: pokemon.maxhp, item: pokemon.item,
      ability: pokemon.ability, types: pokemon.types, boosts: { ...pokemon.boosts }, status: pokemon.status,
      volatiles: Object.keys(pokemon.volatiles), substituteHp: pokemon.volatiles.substitute?.hp });
    const before = { target: capture(target), actor: capture(actor) };
    battle.randomizer = damage => battle.trunc(battle.trunc(damage * roll) / 100);
    const use = battle.actions.useMove.bind(battle.actions);
    battle.actions.useMove = (move, pokemon, ...rest) => {
      const resolved = runtime.dex.getActiveMove(move);
      resolved.willCrit = critical;
      resolved.accuracy = true;
      return use(resolved, pokemon, ...rest);
    };
    // Only the opponent's one attack. This avoids a turn-end event hiding a berry or hit reaction.
    battle.actions.runMove(moveId, actor, 0);
    const move = runtime.dex.moves.get(moveId);
    cases.push({ id: config.id, critical, roll, probability: (critical ? 1 / 24 : 23 / 24) / 16,
      move: { id: moveId, type: move.type, category: move.category, power: move.basePower,
        flags: Object.keys(move.flags).filter(key => move.flags[key]), multihit: move.multihit, damage: move.damage },
      before, after: { target: capture(target), actor: capture(actor) } });
  }
}
process.stdout.write(JSON.stringify(cases));
