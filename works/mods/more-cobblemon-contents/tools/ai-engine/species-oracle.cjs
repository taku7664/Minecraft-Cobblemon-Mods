'use strict';
// Oracle for the engine's port of Showdown's Species constructor: builds `new Species(data)` for every
// Pokedex entry and for hand-written entries shaped like the data Cobblemon sends (receiveSpeciesData),
// and prints the input next to what Showdown made of it.
// Usage: node species-oracle.cjs --showdown <dev-server/showdown> [--msd <dir>] --out <result.json>
const fs = require('node:fs');
const path = require('node:path');
const { load } = require('./showdown-runtime.cjs');

const args = process.argv.slice(2);
const arg = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : null; };
const runtime = load(arg('--showdown'), arg('--msd'));
const { Species } = require(path.join(runtime.root, 'sim', 'dex-species.js'));

const inputs = Object.values(runtime.dex.data.Pokedex).map((entry) => JSON.parse(JSON.stringify(entry)));
// Shapes Cobblemon's ShowdownSpecies produces, including cases the Pokedex does not cover.
inputs.push(
  { num: 36, name: 'Clefable-Mega', baseSpecies: 'Clefable', forme: 'Mega', types: ['Fairy', 'Flying'],
    genderRatio: { M: 0.25, F: 0.75 }, baseStats: { hp: 95, atk: 80, def: 93, spa: 135, spd: 110, spe: 70 },
    abilities: { 0: 'Magic Bounce' }, heightm: 1.7, weightkg: 42.3, requiredItem: 'Clefablite', eggGroups: ['Fairy'],
    preevo: 'Clefairy', evos: [], nfe: false, cannotDynamax: false },
  { num: 445, name: 'Garchomp-Mega-Z', baseSpecies: 'Garchomp', forme: 'Mega-Z', types: ['Dragon', 'Ground'],
    baseStats: { hp: 108, atk: 130, def: 85, spa: 141, spd: 85, spe: 151 }, abilities: { 0: 'Sand Force' },
    heightm: 1.9, weightkg: 99, requiredItem: 'Garchompite Z' },
  { num: 0, name: 'Custommon', types: ['Normal'], baseStats: { hp: 50, atk: 50, def: 50, spa: 50, spd: 50, spe: 50 },
    abilities: { 0: 'Run Away' }, gender: 'N', weightkg: 7.3, heightm: 0.4 },
  { num: 382, name: 'Kyogre-Primal', baseSpecies: 'Kyogre', forme: 'Primal', types: ['Water'],
    baseStats: { hp: 100, atk: 150, def: 90, spa: 180, spd: 160, spe: 90 }, abilities: { 0: 'Primordial Sea' },
    weightkg: 430, requiredItem: 'Blue Orb', gender: 'N' },
  { num: 718, name: 'Zygarde-Complete', baseSpecies: 'Zygarde', forme: 'Complete', types: ['Dragon', 'Ground'],
    baseStats: { hp: 216, atk: 100, def: 121, spa: 91, spd: 95, spe: 85 }, abilities: { 0: 'Power Construct' },
    battleOnly: ['Zygarde', 'Zygarde-10%'], weightkg: 610, gender: 'N' },
  { num: 10, name: '  Spacedmon  ', types: ['Bug'], gender: 'F', weightkg: 0.1, changesFrom: ['Caterpie', 'Other'] },
  { num: 150, name: 'Mewtwo-Mega-X', baseSpecies: 'Mewtwo', forme: 'Mega-X', types: ['Psychic', 'Fighting'], gender: 'M',
    baseStats: { hp: 106, atk: 190, def: 100, spa: 154, spd: 100, spe: 130 }, weightkg: 127, requiredItems: ['Mewtwonite X'] },
);

const pairs = inputs.map((raw) => ({ raw, species: JSON.parse(JSON.stringify(new Species(JSON.parse(JSON.stringify(raw))))) }));
fs.writeFileSync(arg('--out'), JSON.stringify(pairs));
