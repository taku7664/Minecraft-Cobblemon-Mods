'use strict';
/*
 * Builds docs/betterai/engine/AI_엔진_구현_현황.xlsx: every ability, move and item of the game's own
 * Showdown data, split into one row per effect, with Korean names from the game's language files,
 * usage ranks from the bundled Smogon usage data, and implementation status from the engine's
 * coverage report. The "요청·메모" column survives regeneration.
 *
 * node generate.cjs --showdown <dir> --lang <ko_kr.json>... --usage <build.json>... --moves <move.json>...
 *                   --source <betterai kotlin dir> [--coverage <coverage.json>] --out <xlsx>
 */
const fs = require('fs');
const path = require('path');
const ExcelJS = require('exceljs');

// ---------- arguments ----------
const args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i].replace(/^--/, '');
  (args[key] = args[key] || []).push(process.argv[i + 1]);
}
const one = key => (args[key] || [])[0];
const showdown = path.resolve(one('showdown'));
const outPath = path.resolve(one('out'));

// ---------- sources ----------
const { Abilities } = require(path.join(showdown, 'data/abilities.js'));
const { Moves } = require(path.join(showdown, 'data/moves.js'));
const { Items } = require(path.join(showdown, 'data/items.js'));
const text = name => {
  try { return require(path.join(showdown, 'data/text', name + '.js')); } catch (e) { return {}; }
};
const abilityText = Object.values(text('abilities'))[0] || {};
const moveText = Object.values(text('moves'))[0] || {};
const itemText = Object.values(text('items'))[0] || {};

const lang = {};
// Minecraft reads language files leniently and Mega Showdown ships one with a missing comma, so a
// file that is not valid JSON is read line by line the same way.
function readLang(file) {
  const raw = fs.readFileSync(file, 'utf8').replace(/^﻿/, '');
  try { return JSON.parse(raw); } catch (e) {
    const entries = {};
    for (const m of raw.matchAll(/"((?:[^"\\]|\\.)+)"\s*:\s*"((?:[^"\\]|\\.)*)"/g)) entries[m[1]] = JSON.parse('"' + m[2] + '"');
    return entries;
  }
}
for (const file of args.lang || []) Object.assign(lang, readLang(file));
const canon = s => String(s || '').toLowerCase().replace(/[^a-z0-9]/g, '');
const langByCanon = {};
for (const key of Object.keys(lang)) {
  const m = key.match(/^(cobblemon\.ability|cobblemon\.move|item\.cobblemon|item\.mega_showdown)\.([^.]+)(\.(desc|tooltip(_\d+)?))?$/);
  if (!m) continue;
  const kind = m[1].startsWith('item') ? 'item' : m[1].split('.')[1];
  const id = canon(m[2]);
  const slot = (langByCanon[kind + ':' + id] = langByCanon[kind + ':' + id] || { name: '', desc: [] });
  if (!m[3]) slot.name = slot.name || lang[key];
  else slot.desc.push([m[4], lang[key]]);
}
const korean = (kind, id) => {
  const slot = langByCanon[kind + ':' + id];
  if (!slot) return { name: '', desc: '' };
  const desc = slot.desc.sort((a, b) => a[0].localeCompare(b[0])).map(d => d[1]).join(' ');
  return { name: slot.name, desc };
};

// usage: sum of per-species shares across every usage file
function usage(files, pick) {
  const score = {};
  for (const file of files || []) {
    const species = JSON.parse(fs.readFileSync(file, 'utf8')).species || {};
    for (const entry of Object.values(species)) {
      for (const [id, share] of Object.entries(pick(entry) || {})) score[canon(id)] = (score[canon(id)] || 0) + share;
    }
  }
  const ranked = Object.entries(score).filter(e => e[1] > 0).sort((a, b) => b[1] - a[1]);
  const rank = {};
  ranked.forEach(([id], i) => { rank[id] = i + 1; });
  return rank;
}
const abilityRank = usage(args.usage, e => e.abilities);
const itemRank = usage(args.usage, e => e.items);
const moveRank = usage(args.moves, e => e);

// Showdown core files that name an id outside the data tables (e.g. Levitate in sim/pokemon.js)
const simDir = path.join(showdown, 'sim');
const simFiles = fs.readdirSync(simDir).filter(f => f.endsWith('.js'))
  .map(f => [f, fs.readFileSync(path.join(simDir, f), 'utf8')]);
const coreRefs = id => simFiles.filter(([, src]) => new RegExp(`['"]${id}['"]`).test(src)).map(([f]) => 'sim/' + f);

// legacy handmade calculator mentions
const sourceFiles = [];
(function walk(dir) {
  if (!dir || !fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (entry.name.endsWith('.kt')) sourceFiles.push([entry.name, fs.readFileSync(full, 'utf8')]);
  }
})(one('source'));
const legacyRefs = id => id.length < 4 ? [] :
  sourceFiles.filter(([, src]) => new RegExp(`"${id}"`).test(src)).map(([f]) => f.replace(/\.kt$/, ''));

// engine coverage report: { entries: [{ category, id, effect, status, test }] }
const coverage = {};
if (one('coverage') && fs.existsSync(one('coverage'))) {
  for (const e of JSON.parse(fs.readFileSync(one('coverage'), 'utf8')).entries || []) {
    coverage[`${e.category}|${e.id}|${e.effect}`] = e;
  }
}

// ---------- hook vocabulary ----------
const EVENT = {
  Accuracy: '명중 판정', AfterBoost: '능력 변화 뒤', AfterEachBoost: '능력 변화마다', AfterHit: '명중 뒤',
  AfterMove: '기술 사용 뒤', AfterMoveSecondary: '부가효과 처리 뒤', AfterMoveSecondarySelf: '자신 부가효과 뒤',
  AfterSetStatus: '상태이상에 걸린 뒤', AfterSubDamage: '대타출동이 맞은 뒤', AfterTerastallization: '테라스탈 뒤',
  AfterUseItem: '도구 사용 뒤', Attract: '헤롱헤롱', BasePower: '위력 보정', BeforeMove: '기술 사용 전',
  BeforeSwitchIn: '등장 직전', BeforeSwitchOut: '교체로 들어가기 직전', ChangeBoost: '능력 변화량 변경',
  ChargeMove: '모으기 기술', CheckShow: '특성 공개 판정', Copy: '복사될 때', CriticalHit: '급소 판정',
  Damage: '피해를 받을 때(모든 피해)', DamagingHit: '공격기에 맞았을 때', DeductPP: 'PP 감소', DisableMove: '기술 사용 제한',
  DragOut: '강제 교체', Eat: '열매를 먹을 때', EatItem: '도구를 먹을 때', Effectiveness: '타입 상성 배율',
  EmergencyExit: '위기회피', End: '효과 종료', EntryHazard: '설치 효과 피해', Faint: '기절', FieldEnd: '필드 효과 종료',
  FieldRestart: '필드 효과 재설정', FieldStart: '필드 효과 시작', Flinch: '풀죽음', Hit: '명중 시 효과',
  HitField: '필드 전체 대상 기술', HitSide: '한쪽 진영 대상 기술', Immunity: '무효 판정',
  Invulnerability: '공격이 닿지 않는 상태 판정', MaybeTrapPokemon: '교체 불가 가능성', ModifyAccuracy: '명중률 보정',
  ModifyAtk: '공격 보정', ModifyBoost: '랭크 적용 보정', ModifyCritRatio: '급소율 보정', ModifyDamage: '최종 피해 보정',
  ModifyDef: '방어 보정', ModifyMove: '기술 데이터 변경', ModifyPriority: '우선도 보정', ModifySTAB: '자속 보정',
  ModifySecondaries: '부가효과 변경', ModifySpA: '특수공격 보정', ModifySpD: '특수방어 보정', ModifySpe: '스피드 보정',
  ModifyTarget: '대상 변경', ModifyType: '타입 변경', ModifyWeight: '무게 보정', MoveAborted: '기술 중단',
  MoveFail: '기술 실패', NegateImmunity: '무효 무시', OverrideAction: '행동 덮어쓰기', PreStart: '등장 직전 준비',
  PrepareHit: '명중 준비', Primal: '원시회귀', RedirectTarget: '대상 끌어오기', Residual: '턴 종료 시',
  Restart: '같은 효과 재발동', SetAbility: '특성 변경', SetStatus: '상태이상 걸기', SideEnd: '진영 효과 종료',
  SideRestart: '진영 효과 재설정', SideStart: '진영 효과 시작', Start: '등장·효과 시작', Swap: '자리 바꾸기',
  SwitchIn: '등장', SwitchOut: '교체되어 들어감', TakeItem: '도구를 잃을 때', TerrainChange: '필드 변화',
  TrapPokemon: '교체 불가', Try: '기술 시도', TryAddVolatile: '일시 상태 추가 시도', TryBoost: '능력 변화 시도',
  TryEatItem: '먹기 시도', TryHeal: '회복 시도', TryHit: '명중 시도(방어·무효 판정)', TryImmunity: '무효 시도',
  TryMove: '기술 사용 시도', TryPrimaryHit: '주 명중 시도', Type: '타입 판정', Update: '상태 갱신(행동마다)',
  UseMoveMessage: '기술 메시지', Weather: '날씨 효과', WeatherChange: '날씨 변화', WeatherModifyDamage: '날씨 피해 보정',
};
const CALLBACK = {
  basePowerCallback: '위력 계산식', beforeMoveCallback: '기술 전 처리', beforeTurnCallback: '턴 시작 전 처리',
  damageCallback: '고정 피해 계산', durationCallback: '지속 턴 계산', priorityChargeCallback: '모으기 선행 처리',
};
const PREFIX = [['onAlly', '아군'], ['onAny', '필드 전체'], ['onFoe', '상대'], ['onSource', '내가 공격할 때']];
function hookMeaning(hook) {
  if (CALLBACK[hook]) return CALLBACK[hook];
  for (const [prefix, who] of PREFIX) {
    if (hook.startsWith(prefix) && EVENT[hook.slice(prefix.length)]) return `${who}: ${EVENT[hook.slice(prefix.length)]}`;
  }
  const base = hook.replace(/^on/, '');
  return EVENT[base] || hook;
}
const functionKeys = obj => Object.keys(obj || {}).filter(k => typeof obj[k] === 'function');

// ---------- declarative move effects ----------
const STATUS_KO = { brn: '화상', par: '마비', psn: '독', tox: '맹독', slp: '잠듦', frz: '얼음' };
const STAT_KO = { atk: '공격', def: '방어', spa: '특공', spd: '특방', spe: '스피드', accuracy: '명중', evasion: '회피' };
const boostsText = b => Object.entries(b || {}).map(([s, n]) => `${STAT_KO[s] || s} ${n > 0 ? '+' : ''}${n}`).join(', ');
const TARGET_KO = {
  allAdjacentFoes: '상대 전체(인접)', allAdjacent: '자신 외 전체(인접)', all: '필드 전체', allySide: '자기 진영',
  foeSide: '상대 진영', self: '자신', adjacentAlly: '인접 아군', adjacentAllyOrSelf: '자신 또는 인접 아군',
  allies: '아군 전체', randomNormal: '무작위 상대', scripted: '스크립트 대상', any: '아무 대상',
};
function declarativeMoveEffects(m) {
  const out = [];
  if (m.category !== 'Status') out.push(`기본 피해 (${m.type}, ${m.category === 'Physical' ? '물리' : '특수'}, 위력 ${m.basePower || '가변'}, 명중 ${m.accuracy === true ? '반드시' : m.accuracy})`);
  if (m.priority) out.push(`우선도 ${m.priority > 0 ? '+' : ''}${m.priority}`);
  if (m.target && TARGET_KO[m.target]) out.push(`대상: ${TARGET_KO[m.target]}`);
  const secondaries = m.secondaries || (m.secondary ? [m.secondary] : []);
  for (const s of secondaries) {
    const parts = [];
    if (s.status) parts.push(STATUS_KO[s.status] || s.status);
    if (s.volatileStatus) parts.push(s.volatileStatus === 'flinch' ? '풀죽음' : s.volatileStatus === 'confusion' ? '혼란' : s.volatileStatus);
    if (s.boosts) parts.push(`상대 ${boostsText(s.boosts)}`);
    if (s.self && s.self.boosts) parts.push(`자신 ${boostsText(s.self.boosts)}`);
    if (typeof s.onHit === 'function') parts.push('특수 처리');
    out.push(`부가효과 ${s.chance || 100}%: ${parts.join(', ') || '효과'}`);
  }
  if (m.self && m.self.boosts) out.push(`사용 후 자신 ${boostsText(m.self.boosts)}`);
  if (m.self && m.self.volatileStatus) out.push(`사용 후 자신 상태: ${m.self.volatileStatus}`);
  if (m.selfBoost && m.selfBoost.boosts) out.push(`자신 ${boostsText(m.selfBoost.boosts)}`);
  if (m.boosts) out.push(`${m.target === 'self' ? '자신' : '대상'} ${boostsText(m.boosts)}`);
  if (m.status) out.push(`상태이상: ${STATUS_KO[m.status] || m.status}`);
  if (m.volatileStatus) out.push(`일시 상태: ${m.volatileStatus}`);
  if (m.sideCondition) out.push(`진영 효과: ${m.sideCondition}`);
  if (m.slotCondition) out.push(`자리 효과: ${m.slotCondition}`);
  if (m.pseudoWeather) out.push(`필드 효과: ${m.pseudoWeather}`);
  if (m.weather) out.push(`날씨: ${m.weather}`);
  if (m.terrain) out.push(`필드: ${m.terrain}`);
  if (m.heal) out.push(`회복 ${m.heal[0]}/${m.heal[1]}`);
  if (m.drain) out.push(`흡수 ${m.drain[0]}/${m.drain[1]}`);
  if (m.recoil) out.push(`반동 ${m.recoil[0]}/${m.recoil[1]}`);
  if (m.mindBlownRecoil) out.push('반동: 최대 HP 1/2');
  if (m.hasCrashDamage) out.push('빗나가면 자신 피해');
  if (m.multihit) out.push(`연속 ${Array.isArray(m.multihit) ? m.multihit.join('~') : m.multihit}회`);
  if (m.multiaccuracy) out.push('타격마다 명중 판정');
  if (m.critRatio && m.critRatio > 1) out.push(`급소율 +${m.critRatio - 1}`);
  if (m.willCrit) out.push('반드시 급소');
  if (m.ohko) out.push('일격필살');
  if (m.selfSwitch) out.push('사용 후 교체');
  if (m.forceSwitch) out.push('상대 강제 교체');
  if (m.selfdestruct) out.push('사용자 기절');
  if (m.breaksProtect) out.push('방어 무시·해제');
  if (m.ignoreImmunity) out.push('타입 무효 무시');
  if (m.ignoreAbility) out.push('특성 무시');
  if (m.ignoreDefensive) out.push('상대 방어 랭크 무시');
  if (m.ignoreEvasion) out.push('상대 회피 랭크 무시');
  if (m.overrideOffensivePokemon) out.push('상대 능력치로 공격');
  if (m.overrideOffensiveStat) out.push(`공격 능력치: ${m.overrideOffensiveStat}`);
  if (m.overrideDefensiveStat) out.push(`방어 능력치: ${m.overrideDefensiveStat}`);
  if (m.thawsTarget) out.push('상대 얼음 해제');
  if (m.stallingMove) out.push('연속 사용 시 실패 확률');
  if (m.sleepUsable) out.push('잠든 상태에서 사용 가능');
  if (m.stealsBoosts) out.push('상대 랭크 빼앗기');
  if (m.isMax) out.push('다이맥스 기술');
  return out;
}
const MOVE_FLAG_KO = {
  contact: '접촉', sound: '소리', punch: '펀치', bite: '물기', pulse: '파동', bullet: '구슬·폭탄', slicing: '베기',
  wind: '바람', powder: '가루', dance: '춤', protect: '방어 가능', reflectable: '매직코트 반사', snatch: '가로챔',
  heal: '회복', charge: '모으기', recharge: '반동으로 쉼', gravity: '중력 금지', defrost: '얼음 해제', distance: '원거리',
  bypasssub: '대타 관통', mirror: '따라하기', metronome: '손가락흔들기', nonsky: '공중 불가',
};
const flagsText = flags => Object.keys(flags || {}).map(f => MOVE_FLAG_KO[f]).filter(Boolean).join(', ');

// ---------- code hints ----------
// A short, mechanical reading of a hook's source: multipliers, stat changes, statuses and the
// conditions it checks. It says what the hook does often enough to plan the work; the Korean
// description and the Showdown source stay the authority.
const TYPE_KO = {
  Normal: '노말', Fire: '불꽃', Water: '물', Electric: '전기', Grass: '풀', Ice: '얼음', Fighting: '격투', Poison: '독',
  Ground: '땅', Flying: '비행', Psychic: '에스퍼', Bug: '벌레', Rock: '바위', Ghost: '고스트', Dragon: '드래곤',
  Dark: '악', Steel: '강철', Fairy: '페어리', Stellar: '스텔라',
};
const WEATHER_KO = { sunnyday: '쾌청', desolateland: '끝의 대지', raindance: '비', primordialsea: '시작의 바다', sandstorm: '모래바람', snowscape: '설경', hail: '싸라기눈', deltastream: '난기류' };
const TERRAIN_KO = { electricterrain: '일렉트릭필드', grassyterrain: '그래스필드', mistyterrain: '미스트필드', psychicterrain: '사이코필드' };
function codeHints(fn) {
  const src = fn.toString();
  const hints = [];
  for (const m of src.matchAll(/chainModify\(\s*(\[\s*(\d+)\s*,\s*(\d+)\s*\]|[\d.]+)\s*\)/g)) {
    const value = m[2] ? Number(m[2]) / Number(m[3]) : Number(m[1]);
    hints.push(`×${Math.round(value * 100) / 100}`);
  }
  for (const m of src.matchAll(/\bboost\(\s*\{([^}]*)\}/g)) {
    const pairs = {};
    for (const p of m[1].matchAll(/(\w+)\s*:\s*(-?\d+)/g)) pairs[p[1]] = Number(p[2]);
    if (Object.keys(pairs).length) hints.push(`능력 변화 ${boostsText(pairs)}`);
  }
  for (const m of src.matchAll(/(?:trySetStatus|setStatus)\(\s*["'](\w+)["']/g)) hints.push(STATUS_KO[m[1]] || m[1]);
  for (const m of src.matchAll(/addVolatile\(\s*["'](\w+)["']/g)) hints.push(`일시 상태 ${m[1]}`);
  for (const m of src.matchAll(/\bheal\([^;]*maxhp\s*\/\s*(\d+)/gi)) hints.push(`회복 1/${m[1]}`);
  for (const m of src.matchAll(/\bdamage\([^;]*maxhp\s*\/\s*(\d+)/gi)) hints.push(`피해 1/${m[1]}`);
  if (/\.hp\s*-\s*1\b/.test(src)) hints.push('HP 1로 버팀');
  if (/useItem\(|eatItem\(/.test(src)) hints.push('도구 소모');
  for (const m of src.matchAll(/move\.type\s*={2,3}\s*["'](\w+)["']/g)) hints.push(`${TYPE_KO[m[1]] || m[1]} 타입 기술`);
  for (const m of src.matchAll(/hasType\(\s*["'](\w+)["']/g)) hints.push(`${TYPE_KO[m[1]] || m[1]} 타입 조건`);
  for (const m of src.matchAll(/flags\[\s*["'](\w+)["']\s*\]/g)) if (MOVE_FLAG_KO[m[1]]) hints.push(`${MOVE_FLAG_KO[m[1]]} 기술`);
  for (const m of src.matchAll(/["'](sunnyday|desolateland|raindance|primordialsea|sandstorm|snowscape|hail|deltastream)["']/g)) hints.push(`날씨 ${WEATHER_KO[m[1]]}`);
  for (const m of src.matchAll(/["'](electricterrain|grassyterrain|mistyterrain|psychicterrain)["']/g)) hints.push(TERRAIN_KO[m[1]]);
  if (/getEffectiveness\(|runEffectiveness/.test(src)) hints.push('상성 판정');
  if (/move\.category\s*={2,3}\s*["']Status["']/.test(src)) hints.push('변화기 조건');
  if (/\bimmune\b|return\s+(null|false)\s*;/.test(src) && /onTryHit|onImmunity|onTryImmunity/.test(String(fn.name))) hints.push('무효');
  return [...new Set(hints)].slice(0, 6);
}

// ---------- rows ----------
// A row-specific entry wins; effect "*" covers every row of the entity (the referee sweep verifies whole entries).
function coverageFor(category, id, key) {
  return coverage[`${category}|${id}|${key}`] || coverage[`${category}|${id}|*`] || null;
}

function entityRows(category, id, entry, ko, rank, en) {
  const rows = [];
  const hooks = functionKeys(entry);
  const conditionHooks = functionKeys(entry.condition).map(h => 'condition.' + h);
  const core = coreRefs(id);
  const legacy = legacyRefs(id);
  const base = {
    rank: rank || '', koName: ko.name, enName: entry.name, id, koDesc: ko.desc, enDesc: en,
    core: core.join(', '), legacy: legacy.join(', '),
  };
  // The key names a row across regenerations: the hook when there is one, else the effect text.
  const push = (effect, way, hook) => {
    const key = hook || effect;
    const cov = coverageFor(category, id, key);
    rows.push({ ...base, key, effect, way, hook, status: cov ? cov.status : '미구현', test: cov ? cov.test || '' : '' });
  };
  if (category === '기술') {
    for (const e of declarativeMoveEffects(entry)) push(e, '선언형', '');
    const flags = flagsText(entry.flags);
    if (flags && rows.length) rows[0].flags = flags;
  }
  if (category === '도구') {
    if (entry.megaStone) {
      const forms = typeof entry.megaStone === 'string' ? [entry.megaStone] : Object.values(entry.megaStone);
      push(`메가진화: ${forms.join(', ')}`, '선언형', '');
    }
    if (entry.fling) push(`내던지기 위력 ${entry.fling.basePower}${entry.fling.status ? ', ' + (STATUS_KO[entry.fling.status] || entry.fling.status) : ''}`, '선언형', '');
    if (entry.isChoice) push('구애 계열: 기술 고정', '선언형', '');
    if (entry.onPlate || entry.onMemory || entry.onDrive) push('타입 전용 도구', '선언형', '');
  }
  for (const hook of hooks.concat(conditionHooks)) {
    const bare = hook.replace(/^condition\./, '');
    const fn = hook.startsWith('condition.') ? entry.condition[bare] : entry[bare];
    const hints = codeHints(fn);
    const when = `${hook.startsWith('condition.') ? '지속 효과 · ' : ''}${hookMeaning(bare)}`;
    push(hints.length ? `${when}: ${hints.join(', ')}` : when, '훅', hook);
  }
  if (!rows.length) {
    if (core.length) push('Showdown 핵심 코드에서 처리', '핵심 코드', '');
    else push('전투 효과 없음으로 보임(훅·핵심 코드 언급 없음)', '해당 없음 후보', '');
  } else if (core.length && category !== '기술') {
    push('Showdown 핵심 코드에서 추가 처리', '핵심 코드', '');
  }
  return rows;
}

const excludedStandard = ['CAP', 'LGPE', 'Custom', 'Future'];
const abilityRows = Object.entries(Abilities)
  .filter(([, a]) => a.num > 0 && !excludedStandard.includes(a.isNonstandard) && a.isNonstandard !== 'Past')
  .flatMap(([id, a]) => entityRows('특성', id, a, korean('ability', id), abilityRank[id], (abilityText[id] || {}).shortDesc || ''));
const moveRows = Object.entries(Moves)
  .filter(([, m]) => !m.isZ && !excludedStandard.includes(m.isNonstandard) && (m.isNonstandard !== 'Past' || m.isMax))
  .flatMap(([id, m]) => entityRows('기술', id, m, korean('move', id), moveRank[id], (moveText[id] || {}).shortDesc || ''));
const itemRows = Object.entries(Items)
  .filter(([, i]) => !i.zMove && !excludedStandard.includes(i.isNonstandard) && (i.isNonstandard !== 'Past' || i.megaStone))
  .flatMap(([id, i]) => entityRows('도구', id, i, korean('item', id), itemRank[id], (itemText[id] || {}).shortDesc || ''));

// ---------- workbook ----------
async function main() {
  const memos = {};
  if (fs.existsSync(outPath)) {
    const previous = new ExcelJS.Workbook();
    await previous.xlsx.readFile(outPath);
    for (const name of ['특성', '기술', '도구']) {
      const sheet = previous.getWorksheet(name);
      if (!sheet) continue;
      const header = sheet.getRow(1).values;
      const col = label => header.indexOf(label);
      sheet.eachRow((row, n) => {
        if (n === 1) return;
        const memo = row.getCell(col('요청·메모')).value;
        if (memo) memos[`${name}|${row.getCell(col('ID')).value}|${row.getCell(col('훅 이름')).value || row.getCell(col('세부 효과')).value}`] = memo;
      });
    }
  }

  const workbook = new ExcelJS.Workbook();
  workbook.creator = 'AI engine coverage generator';
  const YELLOW = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFFFF200' } };
  const HEADER = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF2F3B52' } };
  const GREY = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFE7E9EE' } };

  const columns = [
    ['사용률 순위', 'rank', 9], ['한국어 이름', 'koName', 14], ['영어 이름', 'enName', 16], ['ID', 'id', 14],
    ['효과 설명(한국어)', 'koDesc', 44], ['세부 효과', 'effect', 40], ['구현 방식', 'way', 12], ['훅 이름', 'hook', 22],
    ['Showdown 핵심 코드', 'core', 22], ['기존 계산기 언급', 'legacy', 26], ['플래그', 'flags', 18],
    ['상태', 'status', 10], ['검증 테스트', 'test', 26], ['요청·메모', 'memo', 30], ['Showdown 설명(영문)', 'enDesc', 50],
  ];
  const statusCounts = {};

  function addSheet(name, rows) {
    const sheet = workbook.addWorksheet(name, { views: [{ state: 'frozen', xSplit: 2, ySplit: 1 }] });
    sheet.columns = columns.map(([header, key, width]) => ({ header, key, width }));
    rows.sort((a, b) => (a.rank || 1e9) - (b.rank || 1e9) || a.id.localeCompare(b.id));
    const counts = (statusCounts[name] = { entities: new Set(), rows: 0, done: 0, partial: 0, candidates: 0 });
    for (const r of rows) {
      r.memo = memos[`${name}|${r.id}|${r.key}`] || '';
      const row = sheet.addRow(r);
      row.alignment = { vertical: 'top', wrapText: true };
      counts.entities.add(r.id);
      counts.rows++;
      if (r.status === '적용 완료' && r.test) {
        counts.done++;
        row.eachCell({ includeEmpty: true }, cell => { cell.fill = YELLOW; });
      } else if (r.status === '부분') counts.partial++;
      if (r.way === '해당 없음 후보') {
        counts.candidates++;
        row.getCell('way').fill = GREY;
      }
    }
    const header = sheet.getRow(1);
    header.font = { bold: true, color: { argb: 'FFFFFFFF' } };
    header.eachCell(cell => { cell.fill = HEADER; cell.alignment = { vertical: 'middle', wrapText: true }; });
    header.height = 22;
    sheet.autoFilter = { from: { row: 1, column: 1 }, to: { row: 1, column: columns.length } };
    return sheet;
  }

  const summary = workbook.addWorksheet('요약');
  addSheet('특성', abilityRows);
  addSheet('기술', moveRows);
  addSheet('도구', itemRows);

  // hook sheet: the engine's event list
  const hookUse = {};
  for (const [category, rows] of [['특성', abilityRows], ['기술', moveRows], ['도구', itemRows]]) {
    for (const r of rows) if (r.hook) {
      const bare = r.hook.replace(/^condition\./, '');
      const slot = (hookUse[bare] = hookUse[bare] || { 특성: new Set(), 기술: new Set(), 도구: new Set(), open: 0 });
      slot[category].add(r.id);
      if (r.status !== '적용 완료') slot.open++;
    }
  }
  const hookSheet = workbook.addWorksheet('훅', { views: [{ state: 'frozen', ySplit: 1 }] });
  hookSheet.columns = [
    { header: '훅 이름', key: 'hook', width: 30 }, { header: '의미', key: 'meaning', width: 34 },
    { header: '특성 수', key: 'a', width: 9 }, { header: '기술 수', key: 'm', width: 9 },
    { header: '도구 수', key: 'i', width: 9 }, { header: '합계', key: 't', width: 9 },
    { header: '엔진 상태', key: 'status', width: 12 }, { header: '요청·메모', key: 'memo', width: 30 },
  ];
  Object.entries(hookUse)
    .map(([hook, s]) => ({ hook, meaning: hookMeaning(hook), a: s['특성'].size, m: s['기술'].size, i: s['도구'].size, open: s.open }))
    // A hook counts as done once every row that uses it is verified; an explicit coverage entry still wins.
    .map(r => ({ ...r, t: r.a + r.m + r.i, status: (coverage[`훅|${r.hook}|`] || {}).status ||
      (r.open === 0 ? '적용 완료' : r.open < r.a + r.m + r.i ? '부분' : '미구현') }))
    .sort((x, y) => y.t - x.t)
    .forEach(r => hookSheet.addRow(r));
  hookSheet.getRow(1).font = { bold: true, color: { argb: 'FFFFFFFF' } };
  hookSheet.getRow(1).eachCell(cell => { cell.fill = HEADER; });
  hookSheet.autoFilter = { from: { row: 1, column: 1 }, to: { row: 1, column: 8 } };

  summary.columns = [
    { header: '분류', key: 'k', width: 12 }, { header: '항목 수', key: 'e', width: 10 }, { header: '효과 행 수', key: 'r', width: 12 },
    { header: '적용 완료(노란색)', key: 'd', width: 18 }, { header: '부분', key: 'p', width: 8 }, { header: '해당 없음 후보', key: 'c', width: 16 },
  ];
  for (const [name, c] of Object.entries(statusCounts)) {
    summary.addRow({ k: name, e: c.entities.size, r: c.rows, d: c.done, p: c.partial, c: c.candidates });
  }
  summary.addRow({ k: '훅', e: Object.keys(hookUse).length });
  summary.getRow(1).font = { bold: true, color: { argb: 'FFFFFFFF' } };
  summary.getRow(1).eachCell(cell => { cell.fill = HEADER; });
  const notes = [
    '',
    '읽는 법',
    '· 한 항목의 효과가 여러 개면 효과 하나당 한 행입니다.',
    '· 노란색 행: 우리 엔진에 구현했고 Showdown 비교 테스트까지 통과한 효과입니다.',
    '· 비교 테스트는 항목마다 일반 전투 두 가지를 두 시드로 돌립니다. 발동 조건이 까다로운 효과는 이 전투에서 발동하지 않았을 수 있으므로, 전용 시나리오로 따로 확인합니다.',
    '· 상태는 엔진의 비교 테스트 결과로 자동으로 채워집니다. 손으로 고치지 마세요.',
    '· "요청·메모" 칸에 적으시면 다시 생성해도 보존됩니다.',
    '· 순서는 사용률 순(Smogon BSS·VGC 2025-12, 1500+)입니다. 사용 기록이 없으면 순위가 비어 있습니다.',
    '· "Showdown 핵심 코드": 데이터 표가 아니라 sim/ 코드에서 이름으로 처리되는 규칙의 위치(추정)입니다. 예: 부유는 sim/pokemon.js.',
    '· "기존 계산기 언급": 동결된 수제 계산기 코드에 이 ID가 등장하는 파일입니다. 구현 완료를 뜻하지 않습니다.',
    '· 범위: 게임 서버의 Showdown 데이터(Mega Showdown 포함). Z기술·이전 세대 전용·CAP 제외, 메가스톤·다이맥스 기술 포함.',
    `· 생성: ${(() => { const d = new Date(); const p = n => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`; })()}`,
  ];
  notes.forEach(line => summary.addRow({ k: line }));

  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  await workbook.xlsx.writeFile(outPath);
  console.log(JSON.stringify(Object.fromEntries(Object.entries(statusCounts).map(([k, c]) =>
    [k, { entities: c.entities.size, rows: c.rows, candidates: c.candidates }])), null, 0), 'hooks=' + Object.keys(hookUse).length);
}
main().catch(e => { console.error(e); process.exit(1); });
