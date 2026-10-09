const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const wiki = path.resolve(__dirname, '../../../server-wiki');
const context = { window: {} };
vm.runInNewContext(fs.readFileSync(path.join(wiki, 'assets/pokemon-text.js'), 'utf8'), context);
const text = context.window.WikiPokemonText;

test('교배 분류를 코블몬의 한국어 이름으로 읽을 수 있다', () => {
  assert.equal(text.eggGroups(['monster', 'grass']), '괴수, 식물');
  assert.equal(text.eggGroups(['water_1', 'undiscovered']), '수중 1, 알미발견');
});

test('구조물 출현 조건의 위치 의미를 보존하고 내부 ID를 숨긴다', () => {
  assert.equal(text.condition('minecraft:desert_well 근처'), '사막 우물 근처');
  assert.equal(text.condition('#cobblemon:ruins/arch 근처'), '유적 아치 근처');
  assert.equal(text.condition('밤 · 비가 올 때'), '밤 · 비가 올 때');
  assert.equal(text.condition('other:unknown_structure 근처'), '이름을 확인 중인 구조물 근처');
});

test('도감의 모든 알 그룹과 구조물 조건을 내부 ID 없이 표시할 수 있다', () => {
  const speciesDir = path.join(wiki, 'assets/data/dex/species');
  let speciesCount = 0;
  for (const file of fs.readdirSync(speciesDir).filter(file => file.endsWith('.js'))) {
    const dataContext = { window: {} };
    vm.runInNewContext(fs.readFileSync(path.join(speciesDir, file), 'utf8'), dataContext);
    for (const data of Object.values(dataContext.window.WIKI_SPECIES)) {
      const groups = text.eggGroups(data.eggGroups || []);
      assert.doesNotMatch(groups, /[a-z_]|확인 중/, file);
      for (const spawn of data.spawns) {
        for (const condition of [...(spawn.conditions || []), ...(spawn.notes || []), ...(spawn.boosts || [])]) {
          const visible = text.condition(condition);
          assert.doesNotMatch(visible, /#?[a-z][a-z_]+:[a-z_./]+|확인 중/, `${file}: ${condition}`);
          if (condition.includes('근처')) assert.ok(visible.includes('근처'), `${file}: ${condition}`);
        }
      }
      speciesCount++;
    }
  }
  assert.equal(speciesCount, 1025);
});
