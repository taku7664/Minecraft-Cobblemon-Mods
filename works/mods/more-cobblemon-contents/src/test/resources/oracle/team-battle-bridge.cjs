'use strict';

const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const { Battle, extractChannelMessages } = require(path.join(process.argv[2], 'sim/battle.js'));
const { Dex } = require(path.join(process.argv[2], 'sim/dex.js'));
const dex = Dex.mod('cobblemon');
dex.includeData(); // getMovePool reads dex.gen before lazily loading species data.
// The dev server's Showdown is Mega Showdown's patch, whose canMegaEvo reads a stone only as a map of species to forme
// (`stone[species.name]`): a stone kept as one `megaEvolves`/`megaStone` pair (Showdown's own Tyranitarite, or one
// given below in that shape) offered no Mega Evolution, so a trainer's Mega Tyranitar never appeared in a mock battle
// (2026-10-18). Such a pair is read here as well.
{
  const { BattleActions } = require(path.join(process.argv[2], 'sim/battle-actions.js'));
  const canMegaEvo = BattleActions.prototype.canMegaEvo;
  BattleActions.prototype.canMegaEvo = function (pokemon) {
    const forme = canMegaEvo.call(this, pokemon);
    if (forme) return forme;
    const item = pokemon.getItem();
    return typeof item.megaStone === 'string' && item.megaEvolves === pokemon.baseSpecies.baseSpecies &&
      !pokemon.volatiles['dynamax'] && !pokemon.terastallized ? item.megaStone : null;
  };
}
// What Mega Showdown registers when the game starts (process.argv[4], unpacked by the harness): its held items and
// abilities, and the battle forms its Mega Stones turn into, which Cobblemon sends Showdown as species
// (receiveSpeciesData). A form is built from the Cobblemon form data only where the stone names a forme Showdown lacks.
if (process.argv[4]) {
  const msd = process.argv[4];
  const msdId = value => String(value || '').toLowerCase().replace(/[^a-z0-9]+/g, ''); // toId is declared further down
  const { Cobblemon } = require(path.join(process.argv[2], 'sim/cobblemon/cobblemon.js'));
  const scripts = dir => !fs.existsSync(dir) ? [] : fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory() ? scripts(path.join(dir, entry.name)) : entry.name.endsWith('.js') ? [path.join(dir, entry.name)] : []).sort();
  // Each entry is an object literal, some already parenthesised; Cobblemon wraps it the same way.
  const entry = file => (0, eval)('(' + fs.readFileSync(file, 'utf8').trim().replace(/;$/, '') + ')');
  // The bridge plays on Cobblemon's own Showdown, which reads a Mega Stone as one `megaEvolves` species and one
  // `megaStone` forme; Mega Showdown's patched Showdown reads a map of species to forme. A one-entry map is given in
  // the shape this Showdown reads, with the stone kept on its holder the way this Showdown's own stones are.
  const stones = [];
  const items = scripts(path.join(msd, 'showdown', 'held_items')).map(file => {
    const data = entry(file);
    const pairs = data.megaStone && typeof data.megaStone === 'object' ? Object.entries(data.megaStone) : [];
    if (pairs.length === 1) {
      [data.megaEvolves, data.megaStone] = pairs[0];
      data.onTakeItem = (item, source) => item.megaEvolves !== source.baseSpecies.baseSpecies;
    }
    const item = Cobblemon.registries.heldItem.register(data, path.basename(file, '.js'));
    stones.push([item, pairs]);
    return item;
  });
  for (const file of scripts(path.join(msd, 'showdown', 'abilities'))) {
    Cobblemon.registries.ability.register(entry(file), path.basename(file, '.js'));
  }
  const additions = {};
  const additionsDir = path.join(msd, 'species_additions');
  for (const name of fs.existsSync(additionsDir) ? fs.readdirSync(additionsDir) : []) {
    const data = JSON.parse(fs.readFileSync(path.join(additionsDir, name), 'utf8'));
    if (typeof data.target === 'string' && Array.isArray(data.forms)) {
      (additions[msdId(data.target.split(':').pop())] ||= []).push(...data.forms);
    }
  }
  const STATS = { hp: 'hp', attack: 'atk', defence: 'def', special_attack: 'spa', special_defence: 'spd', speed: 'spe' };
  const titled = value => value.charAt(0).toUpperCase() + value.slice(1);
  for (const [item, pairs] of stones) {
    for (const [baseName, target] of pairs) {
      const base = dex.data.Pokedex[msdId(baseName)];
      if (!base || dex.data.Pokedex[msdId(target)] || Cobblemon.registries.species.has(msdId(target))) continue;
      const forme = target.slice(base.name.length + 1);
      const form = (additions[msdId(baseName)] || []).find(candidate => candidate.name === forme);
      if (!form) continue;
      const abilities = {};
      (form.abilities || []).forEach((id, index) => {
        const hidden = id.startsWith('h:');
        const ability = dex.abilities.get(hidden ? id.slice(2) : id);
        abilities[hidden ? 'H' : String(index)] = ability.exists ? ability.name : id;
      });
      Cobblemon.registries.species.register({
        num: base.num, name: target, baseSpecies: base.name, forme, battleOnly: base.name, requiredItem: item.name,
        types: [form.primaryType, form.secondaryType].filter(Boolean).map(titled),
        baseStats: Object.fromEntries(Object.entries(form.baseStats).map(([key, value]) => [STATS[key], value])),
        abilities, heightm: (form.height ?? base.heightm * 10) / 10, weightkg: (form.weight ?? base.weightkg * 10) / 10,
        gender: base.gender, genderRatio: base.genderRatio, eggGroups: base.eggGroups,
      });
      base.otherFormes = [...(base.otherFormes || []), target];
    }
  }
  process.stderr.write(`Mega Showdown data: ${items.length} held items, forms ${Cobblemon.registries.species.contents.size}
`);
}
if (dex.gen !== 9) throw new Error('Public learnset export requires the declared Gen 9 engine');
const input = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const battleFormat = input.battleFormat || 'SINGLE';
if (!['SINGLE', 'DOUBLE'].includes(battleFormat)) throw new Error(`Unsupported native team format ${battleFormat}`);
if (!Array.isArray(input.battleSeed) || input.battleSeed.length !== 4 ||
    !input.battleSeed.every(value => Number.isInteger(value) && value >= 0 && value <= 65535) ||
    !Number.isInteger(input.maxTurns) || input.maxTurns < 1 || input.maxTurns > 10000) {
  throw new Error('Invalid battle seed or turn limit');
}
const team = (side, sets) => {
  if (!Array.isArray(sets) || sets.length < 1 || sets.length > 6) throw new Error('Expected 1 to 6 complete sets');
  return sets.map((set, slot) => {
    if (!Array.isArray(set.moves) || !set.moves.length) throw new Error('Missing preset moves');
    const uuid = `00000000-0000-0000-0000-${String(side * 100 + slot + 1).padStart(12, '0')}`;
    // Native request names are truncated to 20 characters, whereas public events
    // use Cobblemon UUIDs. Keep native names unique and normalize request IDs below.
    return { ...structuredClone(set), name: `p${side}slot${slot + 1}`, uuid,
      movesInfo: set.moves.map(id => {
        const move = dex.moves.get(id);
        if (!move.exists) throw new Error(`Unknown move ${id}`);
        return { pp: move.pp, maxPp: move.pp };
      }) };
  });
};
const publicEvents = new Set(['gametype', 'player', 'teamsize', 'gen', 'tier', 'start',
  'switch', 'drag', 'replace', 'detailschange', '-formechange', 'move', '-damage', '-heal',
  'faint', 'win', 'tie', 'turn', '-supereffective', '-extremelyeffective', '-resisted', '-mostlyineffective',
  '-immune', '-crit', '-hitcount', '-fail',
  'cant', '-status', '-curestatus', '-cureteam', '-start', '-end', '-activate', '-ability',
  '-endability', '-item', '-enditem', '-boost', '-unboost', '-setboost', '-swapboost',
  '-copyboost', '-clearboost', '-clearallboost', '-clearpositiveboost', '-clearnegativeboost',
  '-invertboost', '-weather', '-fieldstart', '-fieldend', '-fieldactivate', '-sidestart',
  '-sideend', '-swapsideconditions', '-singleturn', '-singlemove', '-prepare', '-mustrecharge',
  '-notarget', '-miss', '-block', '-transform', '-terastallize']);
const battle = new Battle({ format: { id: 'betteraiteambridge', name: 'Better AI Team Bridge',
  mod: 'cobblemon', gameType: battleFormat === 'DOUBLE' ? 'doubles' : 'singles', ruleset: [], gen: 9 }, seed: input.battleSeed });
let pending = [];
let closed = false;
let lines;
const close = () => {
  if (closed) return;
  closed = true;
  battle.destroy();
  lines?.close();
  process.stdin.destroy();
};
const publicLog = () => extractChannelMessages(battle.log.join('\n'), [0])[0]
  .filter(line => publicEvents.has(line.split('|')[1]));
const moveInfo = id => {
  // Native requests expose this forced action without a corresponding Dex Move.
  if (id === 'recharge') return { id, type: 'normal', category: 'status', power: 0,
    accuracy: 100, priority: 0, target: 'self', pp: 0 };
  const move = dex.moves.get(id);
  if (!move.exists) throw new Error(`Unknown public move ${id}`);
  return { id: move.id, type: move.type.toLowerCase(), category: move.category.toLowerCase(),
    power: move.basePower, accuracy: move.accuracy === true ? 100 : move.accuracy,
    priority: move.priority, target: move.target, pp: move.pp,
    maxPp: move.noPPBoosts ? move.pp : Math.floor(move.pp * 8 / 5),
    pressureTarget: move.flags.mustpressure ? 'allAdjacentFoes' : move.target, charge: !!move.flags.charge };
};
const targetLocations = (target, actorSlot, opposingSlots, alliedSlots) => {
  if (battleFormat === 'SINGLE') return [''];
  if (['normal', 'adjacentFoe'].includes(target)) {
    return opposingSlots.map(slot => ` ${slot + 1}`);
  }
  const partnerSlot = actorSlot === 0 ? 1 : 0;
  if (target === 'adjacentAlly') return alliedSlots.includes(partnerSlot) ? [` ${-(partnerSlot + 1)}`] : [];
  if (target === 'adjacentAllyOrSelf') {
    return [` ${-(actorSlot + 1)}`, ...(alliedSlots.includes(partnerSlot) ? [` ${-(partnerSlot + 1)}`] : [])];
  }
  if (target === 'any') {
    return [
      ...opposingSlots.map(slot => ` ${slot + 1}`),
      ...(alliedSlots.includes(partnerSlot) ? [` ${-(partnerSlot + 1)}`] : []),
    ];
  }
  return [''];
};
const combine = choicesBySlot => choicesBySlot.reduce(
  (combinations, choices) => combinations.flatMap(prefix => choices.map(choice => [...prefix, choice])), [[]]);
const toId = value => String(value || '').toLowerCase().replace(/[^a-z0-9]+/g, '');
// The product hands every trainer the opposing team preview (species, form, level) and its own exact sets.
// The preview is enriched from public species data only, as Cobblemon's FormData enriches it in the game.
const previewSlot = pokemon => Number(pokemon.set.uuid.slice(-12)) % 100 - 1;
function teamPreviewInputs(side) {
  const foePreview = [...side.foe.pokemon].sort((a, b) => previewSlot(a) - previewSlot(b)).map(pokemon => {
    const species = dex.species.get(pokemon.set.species);
    return { slot: previewSlot(pokemon), species: species.id, level: pokemon.level,
      types: species.types.map(type => type.toLowerCase()), baseStats: species.baseStats,
      abilities: Object.entries(species.abilities).map(([key, name]) => ({ id: toId(name), hidden: key === 'H' })),
      gender: species.gender || null, genderRatio: species.genderRatio || null,
      // Cobblemon learnsets span every generation (FormData all legal moves), unlike the Gen 9 pool.
      learnset: Object.fromEntries([...dex.species.getMovePool(species.id, true)].sort().map(id => [id, moveInfo(id)])) };
  });
  const ownBuilds = side.pokemon.map(pokemon => ({ ident: `${side.id}: ${pokemon.uuid}`,
    ability: pokemon.baseAbility, item: pokemon.set.item ? dex.items.get(pokemon.set.item).id : '',
    nature: dex.natures.get(pokemon.set.nature || 'serious').id || 'serious', gender: pokemon.gender || 'N',
    evs: pokemon.set.evs, ivs: pokemon.set.ivs, teraType: pokemon.teraType ? toId(pokemon.teraType) : null,
    gigantamax: !!pokemon.set.gigantamax,
    dynamaxAllowed: pokemon.set.canDynamax !== 'none', dynamaxPriority: pokemon.set.dynamaxPriority ?? null,
    species: pokemon.baseSpecies.id }));
  return { foePreview, ownBuilds };
}
function snapshot() {
  const log = publicLog();
  if (battle.ended || battle.turn > input.maxTurns) {
    process.stdout.write(JSON.stringify({ status: battle.ended ? 'COMPLETE' : 'TURN_LIMIT',
      turn: battle.turn, winner: battle.ended ? (battle.winner || null) : null, publicLog: log }) + '\n');
    close();
    return;
  }
  // Build every request before accepting either side's action. Private maps only
  // contain that requesting side; opponent metadata is derived solely from public events.
  const requests = [];
  for (const side of battle.sides) {
    if (!side.requestState) continue;
    if (!['move', 'switch'].includes(side.requestState)) throw new Error(`Unsupported request ${side.requestState}`);
    const request = structuredClone(side.activeRequest);
    const ownActiveSlots = {};
    side.active.forEach((pokemon, activeSlot) => {
      if (pokemon) ownActiveSlots[`${side.id}:${pokemon.uuid}`] = activeSlot;
    });
    for (const [slot, pokemon] of side.pokemon.entries()) {
      if (!request.side.pokemon[slot]) throw new Error('Own request roster is incomplete');
      request.side.pokemon[slot].ident = `${side.id}: ${pokemon.uuid}`;
    }
    const ownTypes = {}, ownAbilities = {}, ownItems = {}, ownCurrentPp = {}, ownMegaForms = {}, species = {}, moves = {}, publicLearnsets = {};
    const registerSpecies = name => {
      const value = dex.species.get(name);
      if (!value.exists) throw new Error(`Unknown revealed species ${name}`);
      if (species[value.id]) return;
      // The Mega forms are public species data, as Cobblemon publishes every battle form on the Pokemon.
      const megaForms = (value.otherFormes || []).map(name => dex.species.get(name)).filter(form => form.exists && form.isMega)
        .map(form => ({ formId: toId(form.forme), types: form.types.map(type => type.toLowerCase()), baseStats: form.baseStats,
          ability: toId(form.abilities[0]) }));
      species[value.id] = { types: value.types.map(type => type.toLowerCase()), baseStats: value.baseStats, megaForms };
      // Public species rules only: no opponent Pokemon object, move slots or referee team access.
      // Every generation, like the Cobblemon FormData learnsets the game reads (and the team preview here):
      // a species absent from Gen 9 (Cherrim) would otherwise have no candidates at all.
      // It does not prove ownership, current legality or compatibility of a four-move combination.
      publicLearnsets[value.id] = {
        sourceId: 'embedded:cobblemon/gen9_move_pool', coverage: 'PARTIAL',
        // A battle-only forme (Mimikyu-Busted) learns what its out-of-battle forme does.
        moves: Object.fromEntries([...dex.species.getMovePool(
          toId(typeof value.battleOnly === 'string' ? value.battleOnly : value.battleOnly ? value.baseSpecies : value.id),
          true)].sort().map(id => [id, moveInfo(id)])),
      };
    };
    for (const [slot, pokemon] of side.pokemon.entries()) {
      const ident = request.side.pokemon[slot]?.ident;
      if (!ident) throw new Error('Own request identity missing');
      ownTypes[ident] = pokemon.getTypes().map(type => type.toLowerCase());
      ownAbilities[ident] = pokemon.ability;
      ownItems[ident] = pokemon.item;
      ownCurrentPp[ident] = Object.fromEntries(pokemon.moveSlots.map(slot => [slot.id, slot.pp]));
      // The trainer's own Mega form, exact: the stone decides the form, the set the spread, and HP is kept.
      const mega = pokemon.canMegaEvo && dex.species.get(pokemon.canMegaEvo);
      if (mega?.exists) {
        ownMegaForms[ident] = { formId: toId(mega.forme), types: mega.types.map(type => type.toLowerCase()),
          stats: { ...battle.spreadModify(mega.baseStats, pokemon.set), hp: pokemon.maxhp }, ability: toId(mega.abilities[0]) };
      }
    }
    for (const pokemon of request.side.pokemon) {
      registerSpecies(pokemon.details.split(',')[0]);
      for (const id of pokemon.moves) moves[dex.moves.get(id).id] = moveInfo(id);
    }
    for (const line of log) {
      const parts = line.split('|');
      if (['switch', 'drag', 'replace', 'detailschange', '-formechange'].includes(parts[1])) {
        registerSpecies(parts[3].split(',')[0]);
      }
      if (parts[1] === 'move') {
        const move = moveInfo(parts[3]);
        moves[move.id] = move;
      }
      if (parts[1] === '-activate' && ['item: Leppa Berry', 'move: Spite'].includes(parts[3]) && parts[4]) {
        const move = moveInfo(parts[4]);
        moves[move.id] = move;
      }
    }
    const actions = [];
    if (side.requestState === 'move') {
      const alliedSlots = side.active.flatMap((pokemon, slot) => pokemon && !pokemon.fainted ? [slot] : []);
      const opposingSlots = side.foe.active.flatMap((pokemon, slot) => pokemon && !pokemon.fainted ? [slot] : []);
      const choicesBySlot = request.active.map((active, actorSlot) => {
        if (!active || !side.active[actorSlot] || side.active[actorSlot].fainted) {
          return [{ command: 'pass', actorSlot, kind: 'pass' }];
        }
        const choices = [];
        // Showdown lists the Max Moves with canDynamax, and alone while the Pokemon is already Dynamaxed.
        const maxMoves = active.maxMoves?.maxMoves;
        active.moves.forEach((move, moveSlot) => {
          if (move.disabled || move.pp === 0) return;
          // Recharge and Struggle can omit PP; they are still native legal requests.
          const metadata = moveInfo(move.id);
          // Once Dynamaxed, the request keeps the base move but the choice targets as its current Max Move: a spread
          // move's Max Move takes one target, and Showdown rejected the doubles choice without one (2026-10-18).
          const currentMaxMove = !active.canDynamax && maxMoves?.[moveSlot] ? maxMoves[moveSlot] : null;
          const target = currentMaxMove?.target || move.target || metadata.target;
          targetLocations(target, actorSlot, opposingSlots, alliedSlots).forEach(targetLocation => {
            const choice = { command: `move ${moveSlot + 1}${targetLocation}`, actorSlot,
              kind: 'move', slot: moveSlot, moveId: move.id, target,
              targetLoc: targetLocation.trim() ? Number(targetLocation.trim()) : null };
            // Already Dynamaxed: the move is its Max Move, as the game reads it (currentMaxMove).
            if (currentMaxMove) choice.maxMoveId = toId(currentMaxMove.move);
            choices.push(choice);
            // The game offers each gimmick with every move, as here.
            if (active.canMegaEvo) choices.push({ ...choice, command: `${choice.command} mega`, mechanic: 'mega' });
          });
          // As ManagedDynamax: set.canDynamax none keeps a member out, and a living member that may comes first by
          // set.dynamaxPriority (1 first, unmarked last) holds the Dynamax.
          const rank = pokemon => pokemon.set.dynamaxPriority ?? Infinity;
          // The same public species/form rule as LocalDynamax.canDynamax and Cobblemon FormData.dynamaxBlocked:
          // a species marked cannotDynamax and a Mega or Primal battle form cannot take or hold the turn.
          const mayAtAll = pokemon => pokemon.set.canDynamax !== 'none' && !pokemon.species.cannotDynamax &&
            !pokemon.species.isMega && !pokemon.species.isPrimal;
          const self = side.active[actorSlot];
          const mayDynamax = mayAtAll(self) && !side.pokemon.some(other =>
            other !== self && other.hp > 0 && mayAtAll(other) && rank(other) < rank(self));
          if (active.canDynamax && mayDynamax && maxMoves?.[moveSlot] && !maxMoves[moveSlot].disabled) {
            // Dynamaxing turns the move into its Max Move this turn, so the choice takes the Max Move's targets.
            const maxMove = maxMoves[moveSlot];
            const maxTarget = maxMove.target || moveInfo(maxMove.move).target;
            targetLocations(maxTarget, actorSlot, opposingSlots, alliedSlots).forEach(targetLocation => {
              choices.push({ command: `move ${moveSlot + 1}${targetLocation} dynamax`, actorSlot,
                kind: 'move', slot: moveSlot, moveId: move.id, target: maxTarget,
                targetLoc: targetLocation.trim() ? Number(targetLocation.trim()) : null,
                mechanic: 'dynamax', maxMoveId: toId(maxMove.move) });
            });
          }
          moves[move.id] = metadata;
          if (maxMoves?.[moveSlot]) moves[toId(maxMoves[moveSlot].move)] = moveInfo(maxMoves[moveSlot].move);
        });
        // A trap the player cannot see yet (Shadow Tag) comes as maybeTrapped; Showdown rejects the switch and asks
        // again with the trap shown, so a switch it would refuse is not offered.
        if (!active.trapped && !(active.maybeTrapped && side.active[actorSlot].trapped)) {
          request.side.pokemon.forEach((pokemon, slot) => {
            if (pokemon.active || /(?:^0(?:\s|$)|\bfnt\b)/.test(pokemon.condition)) return;
            choices.push({ command: `switch ${slot + 1}`, actorSlot, kind: 'switch', slot });
          });
        }
        return choices;
      });
      for (const components of combine(choicesBySlot)) {
        const switchSlots = components.filter(component => component.kind === 'switch').map(component => component.slot);
        if (new Set(switchSlots).size !== switchSlots.length) continue;
        // A side Mega Evolves (or Dynamaxes) once per battle, so never two in one turn.
        if (components.filter(component => component.mechanic).length > 1) continue;
        const command = components.map(component => component.command).join(', ');
        if (battleFormat === 'SINGLE') actions.push({ ...components[0], id: command });
        else actions.push({ id: command, kind: 'composite', components });
      }
    } else {
      // In doubles with fewer Pokemon left in the back than fainted slots, one slot switches in and the other passes;
      // offering only switches left no legal pair (2026-10-18).
      const forcedSlots = request.forceSwitch.flatMap((forced, actorSlot) => forced ? [actorSlot] : []);
      const available = request.side.pokemon.filter(pokemon =>
        !pokemon.active && !/(?:^0(?:\s|$)|\bfnt\b)/.test(pokemon.condition)).length;
      const replacementCount = Math.min(forcedSlots.length, available);
      const choicesBySlot = request.forceSwitch.map((forced, actorSlot) => {
        if (!forced) return [{ command: 'pass', actorSlot, kind: 'pass' }];
        const replacements = request.side.pokemon.flatMap((pokemon, slot) =>
          pokemon.active || /(?:^0(?:\s|$)|\bfnt\b)/.test(pokemon.condition) ? [] :
            [{ command: `switch ${slot + 1}`, actorSlot, kind: 'switch', slot }]);
        return replacements.concat(replacementCount < forcedSlots.length ? [{ command: 'pass', actorSlot, kind: 'pass' }] : []);
      });
      for (const components of combine(choicesBySlot)) {
        const switchSlots = components.filter(component => component.kind === 'switch').map(component => component.slot);
        if (new Set(switchSlots).size !== switchSlots.length) continue;
        if (switchSlots.length !== replacementCount) continue;
        const command = components.map(component => component.command).join(', ');
        if (battleFormat === 'SINGLE') actions.push({ ...components[0], id: command });
        else actions.push({ id: command, kind: 'composite', components });
      }
    }
    if (!actions.length) throw new Error(`No exposed legal actions for ${side.id}`);
    const nativeInputs = input.teamPreview ? teamPreviewInputs(side) : {};
    requests.push({ format: battleFormat, side: side.id, request, ownTypes, ownAbilities, ownItems, ownMegaForms, actions,
      publicLog: log, species, moves, publicLearnsets, ownCurrentPp, ownActiveSlots, ...nativeInputs });
  }
  if (!requests.length) throw new Error('Battle neither ended nor requested input');
  pending = requests;
  process.stdout.write(JSON.stringify({ status: 'WAITING', turn: battle.turn, requests, publicLog: log }) + '\n');
}
try {
  battle.setPlayer('p1', { name: 'p1', team: team(1, input.p1?.sets) });
  battle.setPlayer('p2', { name: 'p2', team: team(2, input.p2?.sets) });
  lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  lines.on('line', line => {
    if (closed) return;
    try {
      const message = JSON.parse(line);
      if (!message.choices || typeof message.choices !== 'object' || Array.isArray(message.choices)) {
        throw new Error('Expected choices object');
      }
      const keys = Object.keys(message.choices).sort();
      if (JSON.stringify(keys) !== JSON.stringify(pending.map(request => request.side).sort())) {
        throw new Error('Choices must match exactly the waiting sides');
      }
      for (const request of pending) {
        // A scripted replay plays the real players' choices, which may use a gimmick (Terastallization)
        // the exposed action list does not offer; Showdown still validates them below.
        if (input.scriptedChoices) continue;
        if (!request.actions.some(action => action.id === message.choices[request.side])) {
          throw new Error(`Action not exposed for ${request.side}`);
        }
      }
      for (const request of pending) {
        if (!battle.choose(request.side, message.choices[request.side])) {
          throw new Error(`Native rejected ${request.side} ${message.choices[request.side]}`);
        }
      }
      snapshot();
    } catch (error) {
      process.stderr.write(`${error.stack || error}\n`);
      process.exitCode = 1;
      close();
    }
  });
  lines.on('close', close);
  snapshot();
} catch (error) {
  process.stderr.write(`${error.stack || error}\n`);
  process.exitCode = 1;
  close();
}
