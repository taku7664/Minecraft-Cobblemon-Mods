'use strict';
// Writes the engine's work order: the workbook's entries (same filters) ranked by the bundled usage data,
// then the unused rest alphabetically. The referee sweep tests walk this list, and porting follows it.
// Usage (module dir): node tools/ai-engine/priority.cjs
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const moduleDir = path.resolve(__dirname, '../..');
const dex = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(moduleDir, 'src/main/resources/ai-engine/dex.json.gz'))));
const usageDir = path.join(moduleDir, 'src/main/resources/data/more_cobblemon_contents');
const canon = (id) => String(id).toLowerCase().replace(/[^a-z0-9]/g, '');
const buildFiles = ['gen9bssregj-2025-12-1500.json', 'gen9vgc2025regj-2025-12-1500.json'].map((f) => path.join(usageDir, 'opponent_build_usage', f));
const moveFiles = ['gen9bssregj-2025-12-1500.json', 'gen9vgc2025regj-2025-12-1500.json'].map((f) => path.join(usageDir, 'opponent_move_usage', f));

function score(files, pick) {
  const total = {};
  for (const file of files) {
    for (const entry of Object.values(JSON.parse(fs.readFileSync(file, 'utf8')).species || {})) {
      for (const [id, share] of Object.entries(pick(entry) || {})) total[canon(id)] = (total[canon(id)] || 0) + share;
    }
  }
  return total;
}
const excluded = ['CAP', 'LGPE', 'Custom', 'Future'];
function order(table, keep, usage) {
  const ids = Object.keys(table).filter((id) => keep(table[id]));
  return ids.sort((a, b) => ((usage[b] || 0) - (usage[a] || 0)) || a.localeCompare(b))
    .map((id) => ({ id, usage: Math.round((usage[id] || 0) * 1e6) / 1e6, hooks: table[id].hooks || [] }));
}
const result = {
  abilities: order(dex.abilities, (a) => a.num > 0 && !excluded.includes(a.isNonstandard) && a.isNonstandard !== 'Past', score(buildFiles, (e) => e.abilities)),
  moves: order(dex.moves, (m) => !m.isZ && !excluded.includes(m.isNonstandard) && m.isNonstandard !== 'Past', score(moveFiles, (e) => e)),
  items: order(dex.items, (i) => !i.zMove && !excluded.includes(i.isNonstandard) && (i.isNonstandard !== 'Past' || i.megaStone), score(buildFiles, (e) => e.items)),
};
const out = path.join(moduleDir, 'src/test/resources/ai-engine/priority.json');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify(result, null, 1));
for (const [k, v] of Object.entries(result)) {
  console.log(k, v.length, 'used', v.filter((e) => e.usage > 0).length, 'withHooks', v.filter((e) => e.hooks.length).length);
}
