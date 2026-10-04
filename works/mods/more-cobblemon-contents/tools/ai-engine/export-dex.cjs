'use strict';
// Exports the non-code half of the dev server's Showdown data for the Kotlin AI engine.
// Every function-valued field (an event handler or callback) is dropped from the data and listed under
// `hooks`, so the engine knows which behaviour it must implement in Kotlin for each entry.
// Usage: node export-dex.cjs --showdown <dev-server/showdown> [--msd <mega showdown showdown dir>] --out <dex.json.gz>
const fs = require('node:fs');
const zlib = require('node:zlib');
const { load } = require('./showdown-runtime.cjs');

const args = process.argv.slice(2);
const arg = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : null; };
const showdown = arg('--showdown');
const out = arg('--out');
if (!showdown || !out) throw new Error('Expected --showdown <dir> --out <file>');
const runtime = load(showdown, arg('--msd'));
const dex = runtime.dex;

const DROP = new Set(['desc', 'shortDesc', 'contestType', 'spritenum', 'color', 'eggGroups', 'evos', 'prevo', 'evoLevel',
  'evoType', 'evoCondition', 'evoItem', 'evoMove', 'evoRegion', 'canHatch', 'formeOrder', 'cosmeticFormes',
  'exists', 'effectType', 'fullname', 'gen', 'tier', 'doublesTier', 'natDexTier']);

/** Copies plain data, recording function-valued keys (with their nested path) into `hooks`. */
function strip(value, hooks, prefix, seen) {
  if (value === null || value === undefined) return value;
  if (typeof value === 'function') return undefined;
  if (typeof value !== 'object') return value;
  if (seen.has(value)) return undefined;
  seen.add(value);
  if (Array.isArray(value)) {
    const copy = value.map((item, i) => strip(item, hooks, `${prefix}${i}.`, seen));
    seen.delete(value);
    return copy;
  }
  const copy = {};
  for (const key of Object.keys(value)) {
    if (prefix === '' && DROP.has(key)) continue;
    const v = value[key];
    if (typeof v === 'function') { hooks.push(prefix + key); continue; }
    const s = strip(v, hooks, `${prefix}${key}.`, seen);
    if (s !== undefined) copy[key] = s;
  }
  seen.delete(value);
  return copy;
}

function table(ids, get) {
  const result = {};
  for (const id of [...ids].sort()) {
    const entry = get(id);
    if (!entry || !entry.exists) continue;
    const hooks = [];
    const data = strip(entry, hooks, '', new Set());
    data.effectType = entry.effectType;
    if (hooks.length) data.hooks = hooks;
    result[entry.id] = data;
  }
  return result;
}

const moveIds = new Set([...Object.keys(dex.data.Moves), ...runtime.Cobblemon.registries.move.contents.keys()]);
const itemIds = new Set([...Object.keys(dex.data.Items), ...runtime.Cobblemon.registries.heldItem.contents.keys()]);
const abilityIds = new Set([...Object.keys(dex.data.Abilities), ...runtime.Cobblemon.registries.ability.contents.keys()]);
const speciesIds = new Set([...Object.keys(dex.data.Pokedex), ...runtime.Cobblemon.registries.species.contents.keys()]);

const species = {};
for (const id of [...speciesIds].sort()) {
  const s = dex.species.get(id);
  if (!s.exists) continue;
  const hooks = [];
  const data = strip(s, hooks, '', new Set());
  species[s.id] = data;
}

const typechart = {};
for (const id of Object.keys(dex.data.TypeChart).sort()) {
  const t = dex.types.get(id);
  typechart[t.name] = { damageTaken: t.damageTaken, isNonstandard: t.isNonstandard || null };
}
const natures = {};
for (const id of Object.keys(dex.data.Natures).sort()) {
  const n = dex.natures.get(id);
  natures[n.id] = { name: n.name, plus: n.plus || null, minus: n.minus || null };
}

const result = {
  meta: { format: 1, msdPatch: runtime.msdPatch, registered: runtime.registered },
  typechart,
  natures,
  species,
  moves: table(moveIds, (id) => dex.moves.get(id)),
  abilities: table(abilityIds, (id) => dex.abilities.get(id)),
  items: table(itemIds, (id) => dex.items.get(id)),
  conditions: table(Object.keys(dex.data.Conditions), (id) => dex.conditions.get(id)),
};
const json = JSON.stringify(result);
fs.writeFileSync(out, zlib.gzipSync(Buffer.from(json, 'utf8'), { level: 9 }));
process.stdout.write(`species=${Object.keys(species).length} moves=${Object.keys(result.moves).length} ` +
  `abilities=${Object.keys(result.abilities).length} items=${Object.keys(result.items).length} ` +
  `conditions=${Object.keys(result.conditions).length} bytes=${json.length}\n`);
