'use strict';
// Loads the dev server's patched Showdown and applies the runtime registrations the game makes on top of it
// (Mega Showdown's held items and abilities), so the dex exporter and the engine referee see the same rules.
const fs = require('node:fs');
const path = require('node:path');

function listScripts(dir) {
  if (!dir || !fs.existsSync(dir)) return [];
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...listScripts(full));
    else if (entry.name.endsWith('.js')) out.push(full);
  }
  return out.sort();
}

function evalEntry(file) {
  // Mega Showdown ships each entry as an object literal, some already parenthesised; Cobblemon wraps it the same way.
  return (0, eval)('(' + fs.readFileSync(file, 'utf8').trim().replace(/;$/, '') + ')');
}

/**
 * @param {string} showdownRoot dev-server/showdown
 * @param {string|null} msdShowdownDir extracted `data/mega_showdown/mega_showdown/showdown` directory, or null
 */
function load(showdownRoot, msdShowdownDir) {
  const root = path.resolve(showdownRoot);
  const { Dex } = require(path.join(root, 'sim/dex.js'));
  const { Battle } = require(path.join(root, 'sim/battle.js'));
  const { Cobblemon } = require(path.join(root, 'sim/cobblemon/cobblemon.js'));
  const registered = { heldItem: [], ability: [] };
  if (msdShowdownDir) {
    for (const file of listScripts(path.join(msdShowdownDir, 'held_items'))) {
      const id = path.basename(file, '.js');
      Cobblemon.registries.heldItem.register(evalEntry(file), id);
      registered.heldItem.push(id);
    }
    for (const file of listScripts(path.join(msdShowdownDir, 'abilities'))) {
      const id = path.basename(file, '.js');
      Cobblemon.registries.ability.register(evalEntry(file), id);
      registered.ability.push(id);
    }
  }
  const dex = Dex.mod('cobblemon');
  let msdPatch = null;
  const patchFile = path.join(root, 'MSDPatch.json');
  if (fs.existsSync(patchFile)) msdPatch = JSON.parse(fs.readFileSync(patchFile, 'utf8')).version;
  return { Dex, Battle, Cobblemon, dex, registered, msdPatch, root };
}

module.exports = { load };
