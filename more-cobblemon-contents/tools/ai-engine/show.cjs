'use strict';
// Prints Showdown entries with their handler source, for porting: node show.cjs <showdown> <moves|abilities|items|conditions> id...
const { load } = require('./showdown-runtime.cjs');
const [showdown, kind, ...ids] = process.argv.slice(2);
const rt = load(showdown, process.env.MSD_DIR || null);
const table = { moves: rt.dex.data.Moves, abilities: rt.dex.data.Abilities, items: rt.dex.data.Items, conditions: rt.dex.data.Conditions }[kind];
const registry = { items: rt.Cobblemon.registries.heldItem, abilities: rt.Cobblemon.registries.ability }[kind];
function print(obj, indent) {
  const pad = ' '.repeat(indent);
  for (const [k, v] of Object.entries(obj)) {
    if (['desc', 'shortDesc', 'contestType', 'spritenum', 'num', 'gen', 'name', 'rating', 'fling', 'zMove', 'maxMove'].includes(k)) continue;
    if (typeof v === 'function') console.log(pad + v.toString().replace(/\n/g, '\n' + pad));
    else if (v && typeof v === 'object' && !Array.isArray(v) && Object.values(v).some((x) => typeof x === 'function')) {
      console.log(pad + k + ': {'); print(v, indent + 2); console.log(pad + '}');
    } else console.log(pad + k + ': ' + JSON.stringify(v));
  }
}
for (const id of ids) {
  const entry = (registry && registry.get(id)) || table[id];
  console.log(`=== ${kind}:${id}`);
  if (entry) print(entry, 2); else console.log('  (missing)');
}
