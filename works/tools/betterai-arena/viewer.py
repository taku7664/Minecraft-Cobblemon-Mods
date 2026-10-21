"""Builds the arena viewer from the record files.

For each run folder: viewer-data.json (the condensed battles) and viewer.html (one self-contained page, that run's data
embedded). For all runs: <runs>/../site/ with index.html (the run list embedded) and data/<run>.json, the folder the
Artifact page is published from. Only these generated files are rewritten; the record files are only read.
"""
import json
import re
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOP_CANDIDATES = 6
SIDES = ('p1', 'p2')
STATS = {'atk': '공격', 'def': '방어', 'spa': '특공', 'spd': '특방', 'spe': '스피드', 'accuracy': '명중', 'evasion': '회피',
         'attack': '공격', 'defence': '방어', 'defense': '방어', 'special_attack': '특공', 'special_defence': '특방',
         'special_defense': '특방', 'speed': '스피드'}
STATUS = {'brn': '화상', 'par': '마비', 'psn': '독', 'tox': '맹독', 'slp': '잠듦', 'frz': '얼음'}
WEATHER = {'SunnyDay': '쾌청', 'RainDance': '비', 'Sandstorm': '모래바람', 'Hail': '싸라기눈', 'Snow': '설경',
           'DesolateLand': '끝의대지', 'PrimordialSea': '시작의바다', 'DeltaStream': '델타스트림', 'none': '없음'}


class Names:
    """Korean names from the real Cobblemon JAR's ko_kr.json; English stays where it has none."""

    def __init__(self, jars):
        self.species, self.moves, self.abilities, self.items = {}, {}, {}, {}
        for jar in jars:
            try:
                with zipfile.ZipFile(jar) as archive:
                    lang = json.loads(archive.read('assets/cobblemon/lang/ko_kr.json').decode('utf-8'))
            except (OSError, KeyError, zipfile.BadZipFile):
                continue
            for key, value in lang.items():
                parts = key.split('.')
                if len(parts) == 4 and parts[1] == 'species' and parts[3] == 'name':
                    self.species[self.id(parts[2])] = value
                elif len(parts) == 3 and parts[1] == 'move':
                    self.moves[self.id(parts[2])] = value
                elif len(parts) == 3 and parts[1] == 'ability':
                    self.abilities[self.id(parts[2])] = value
                elif len(parts) == 3 and parts[0] == 'item' and parts[1] == 'cobblemon':
                    self.items[self.id(parts[2])] = value
            break

    @staticmethod
    def id(text):
        return re.sub(r'[^a-z0-9]', '', str(text).lower())

    def mon(self, name):
        key = self.id(name)
        if key in self.species:
            return self.species[key]
        # Forms (basculegionf, lycanrocmidnight): the longest species id the name starts with, form kept in English.
        for end in range(len(key) - 1, 3, -1):
            if key[:end] in self.species:
                form = str(name).split('-', 1)[1] if '-' in str(name) else key[end:]
                return f'{self.species[key[:end]]}-{form}'
        return str(name)

    def move(self, name):
        return self.moves.get(self.id(name), str(name))

    def ability(self, name):
        return self.abilities.get(self.id(name), str(name))

    def item(self, name):
        return self.items.get(self.id(name), str(name))


def hp(text):
    """Public HP ('73/100', '0 fnt', '120/155 par') as the game shows it."""
    match = re.match(r'(\d+)(?:/(\d+))?', text or '')
    if not match:
        return text
    now, total = match.groups()
    return f'{now}%' if total in (None, '100') else f'{now}/{total}'


def rounded(value, digits=1):
    return round(value, digits) if isinstance(value, (int, float)) else value


class Battle:
    def __init__(self, folder, names):
        self.folder = folder
        self.names = names
        self.who = {}

    def ident(self, text):
        match = re.match(r'(p\d)[a-z]?: (\S+)', text or '')
        if not match:
            return text
        side, uid = match.groups()
        return f'{side} {self.who.get(uid, uid[-4:])}'

    def learn(self, line):
        match = re.match(r'\|(?:switch|drag|replace)\|p\d[a-z]?: (\S+)\|([^,|]+)', line)
        if match:
            self.who[match.group(1)] = self.names.mon(match.group(2))

    def line(self, raw):
        """One public log line in Korean, or None for bookkeeping lines."""
        self.learn(raw)
        parts = raw.split('|')[1:]
        if not parts:
            return None
        kind, args = parts[0], parts[1:]
        tags = [a for a in args if a.startswith('[')]
        args = [a for a in args if not a.startswith('[')]
        source = next((t[7:].strip() for t in tags if t.startswith('[from]')), None)
        if '[silent]' in tags or kind in ('player', 'teamsize', 'gen', 'tier', 'gametype', 'start', 'rule', 't:', '',
                                          'upkeep', 'turn', 'teampreview', 'poke', 'clearpoke', 'j', 'l'):
            return None
        a = lambda i: args[i] if len(args) > i else ''
        src = f' ({self.source(source)})' if source else ''
        if kind == 'move':
            return f'{self.ident(a(0))}의 {self.names.move(a(1))}'
        if kind in ('switch', 'drag'):
            return f'{a(0)[:2]} {"교체" if kind == "switch" else "강제 교체"} → {self.ident(a(0)).split(" ", 1)[1]} ({hp(a(2))})'
        if kind == '-damage':
            return f'  {self.ident(a(0))} HP {hp(a(1))}{src}'
        if kind == '-heal':
            return f'  {self.ident(a(0))} 회복 HP {hp(a(1))}{src}'
        if kind == 'faint':
            return f'  {self.ident(a(0))} 쓰러짐'
        if kind in ('-boost', '-unboost'):
            sign = '+' if kind == '-boost' else '-'
            return f'  {self.ident(a(0))} {STATS.get(a(1), a(1))} {sign}{a(2)}{src}'
        if kind == '-status':
            return f'  {self.ident(a(0))} {STATUS.get(a(1), a(1))}{src}'
        if kind == '-curestatus':
            return f'  {self.ident(a(0))} {STATUS.get(a(1), a(1))} 회복'
        if kind == '-weather':
            return None if '[upkeep]' in tags else f'날씨: {WEATHER.get(a(0), a(0))}{src}'
        if kind == '-crit':
            return '  급소'
        if kind == '-supereffective':
            return '  효과가 굉장했다'
        if kind == '-extremelyeffective':
            return '  효과가 엄청났다(4배)'
        if kind == '-resisted':
            return '  효과가 별로'
        if kind == '-immune':
            return f'  {self.ident(a(0))} 효과 없음{src}'
        if kind == '-miss':
            return f'  {self.ident(a(1) or a(0))} 빗나감'
        if kind == '-fail':
            return f'  실패: {self.ident(a(0))} {a(1)}'
        if kind == 'cant':
            return f'  {self.ident(a(0))} 행동 못 함 ({a(1)})'
        if kind == '-enditem':
            return f'  {self.ident(a(0))} {self.names.item(a(1))} 사용·소실{src}'
        if kind == '-item':
            return f'  {self.ident(a(0))} {self.names.item(a(1))}{src}'
        if kind == '-ability':
            return f'  {self.ident(a(0))} 특성 {self.names.ability(a(1))}'
        if kind in ('-start', '-end'):
            return f'  {self.ident(a(0))} {self.source(a(1))} {"시작" if kind == "-start" else "끝"}'
        if kind in ('-sidestart', '-sideend'):
            return f'{a(0)[:2]} 진영 {self.source(a(1))} {"설치" if kind == "-sidestart" else "제거"}'
        if kind in ('-fieldstart', '-fieldend'):
            return f'필드 {self.source(a(0))} {"시작" if kind == "-fieldstart" else "끝"}'
        if kind == 'win':
            return f'승리: {a(0)}'
        return '  ' + ' '.join([kind.lstrip('-')] + [self.ident(x) if re.match(r'p\d[a-z]?: ', x) else x for x in args])

    def source(self, text):
        text = (text or '').strip()
        for prefix, fn in (('move: ', self.names.move), ('item: ', self.names.item), ('ability: ', self.names.ability)):
            if text.startswith(prefix):
                return fn(text[len(prefix):])
        if re.match(r'p\d[a-z]?: ', text):
            return self.ident(text)
        return WEATHER.get(text, self.names.move(text) if self.names.id(text) in self.names.moves else text)

    def label(self, action, moves):
        target = (moves or {}).get(action, '')
        if str(target).startswith('switch:'):
            return f'교체 → {self.names.mon(target[7:])}'
        extra = [w for w in action.split()[2:] if w not in ('1', '2', '-1', '-2')]
        word = {'dynamax': '다이맥스 ', 'terastallize': '테라스탈 ', 'mega': '메가진화 ', 'zmove': 'Z ', 'ultra': '울트라버스트 '}
        prefix = ''.join(word.get(w, w + ' ') for w in extra)
        return prefix + (self.names.move(target) if target else action)

    def board(self, text):
        match = re.match(r'(ALLY|OPPONENT):(\S+) hp=([\d.]+) status=(\S+) stages=\{(.*?)\} moves=\[(.*)\]$', text)
        if not match:
            return {'raw': text}
        owner, mon, hp_, status, stages, moves = match.groups()
        boosts = []
        for item in filter(None, (s.strip() for s in stages.split(','))):
            key, _, value = item.partition('=')
            if value not in ('0', ''):
                boosts.append(f'{STATS.get(key, key)} {int(value):+d}' if value.lstrip('-').isdigit() else item)
        return {'own': owner == 'ALLY', 'mon': self.names.mon(mon), 'hp': float(hp_),
                'status': None if status == 'null' else STATUS.get(status.lower(), status), 'stages': boosts,
                'moves': [self.names.move(m.strip()) for m in moves.split(',') if m.strip()]}

    def decision(self, row):
        stages = row.get('stages') or {}
        moves = row.get('candidateMoves') or {}
        base, refined = stages.get('base') or {}, stages.get('refined') or {}
        rules, after_rules = stages.get('rules') or {}, stages.get('afterRules') or {}
        weights = stages.get('afterKindWeights') or {}
        draws = stages.get('drawProbabilities') or {}
        exclusions = {**(stages.get('drawExclusions') or {}), **(stages.get('ruleExclusions') or {})}
        quick = stages.get('quickBonus') or {}
        ranked = sorted(refined, key=lambda k: -refined[k])
        shown = ranked[:TOP_CANDIDATES]
        for extra in [row.get('actionId'), stages.get('drawn'), stages.get('selected'), *draws]:
            if extra and extra in refined and extra not in shown:
                shown.append(extra)
        candidates = []
        for key in shown:
            adjust = {name: rounded(values[key]) for name, values in rules.items()
                      if key in values and abs(values[key]) >= 0.05}
            if key in weights and key in after_rules and abs(weights[key] - after_rules[key]) >= 0.05:
                adjust['종류 가중'] = rounded(weights[key] - after_rules[key])
            if key in refined and key in weights and abs(refined[key] - weights[key]) >= 0.05:
                adjust['정제'] = rounded(refined[key] - weights[key])
            candidates.append({'id': key, 'label': self.label(key, moves), 'base': rounded(base.get(key)),
                               'refined': rounded(refined.get(key)), 'rules': adjust,
                               'p': rounded(draws.get(key), 3), 'ex': exclusions.get(key), 'quick': quick.get(key)})
        return {'side': row.get('side'), 'turn': row.get('turn'), 'action': row.get('actionId'),
                'label': self.label(row.get('actionId', ''), moves), 'drawn': stages.get('drawn'),
                'selected': stages.get('selected'), 'ms': row.get('decisionMillis'),
                'board': [self.board(b) for b in row.get('board') or []], 'candidates': candidates,
                'hidden': max(0, len(refined) - len(shown))}

    def build(self, summary):
        rows = []
        decisions = self.folder / 'decisions.jsonl'
        if decisions.exists():
            with open(decisions, encoding='utf-8') as file:
                rows = [json.loads(line) for line in file if line.strip()]
        result = {}
        if (self.folder / 'result.json').exists():
            result = json.loads((self.folder / 'result.json').read_text(encoding='utf-8'))
        seen = 0
        out = []
        for row in rows:
            log = (row.get('input') or {}).get('publicLog') or []
            fresh = [text for text in (self.line(raw) for raw in log[seen:]) if text] if len(log) > seen else []
            seen = max(seen, len(log))
            item = self.decision(row)
            item['log'] = fresh
            out.append(item)
        final_log = result.get('publicLog') or []
        tail = [text for text in (self.line(raw) for raw in final_log[seen:]) if text]
        return {**summary, 'sets': self.sets(), 'decisions': out, 'tail': tail}

    def sets(self):
        referee = self.folder / 'referee-input.json'
        if not referee.exists():
            return {}
        pair = json.loads(referee.read_text(encoding='utf-8'))
        teams = {}
        for side in SIDES:
            teams[side] = [{'mon': self.names.mon(s.get('species')), 'item': self.names.item(s.get('item', '')),
                            'ability': self.names.ability(s.get('ability', '')), 'nature': s.get('nature'),
                            'moves': [self.names.move(m) for m in s.get('moves', [])]}
                           for s in (pair.get(side) or {}).get('sets', [])]
        return teams


def names_for(run):
    jars = []
    try:
        import arena
        jars = [p for p in arena.config().get('dataJars', '').split(';') if p]
    except Exception:
        pass
    return Names(jars + [run.get('jar', '')])


LOCAL_HEAD = ('<!doctype html><html lang="ko"><meta charset="utf-8">'
              '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n')


def page(index_rows, embedded, local=False):
    """local: a file opened straight from disk, so it carries its own document head (the Artifact host adds one)."""
    template = (LOCAL_HEAD if local else '') + (HERE / 'viewer-template.html').read_text(encoding='utf-8')
    data = json.dumps({'runs': index_rows, 'data': embedded}, ensure_ascii=False, separators=(',', ':'))
    return template.replace('"__ARENA_DATA__"', data.replace('</', '<\\/'))


def build_run(folder, names=None):
    run = json.loads((folder / 'run.json').read_text(encoding='utf-8'))
    names = names or names_for(run)
    battles = []
    for summary in run.get('battles', []):
        battle_folder = folder / summary.get('folder', f'battle-{summary["index"]}')
        battle = Battle(battle_folder, names).build(summary)
        battle['teams'] = {side: [m['mon'] for m in battle['sets'].get(side, [])] for side in SIDES}
        battles.append(battle)
    data = {key: value for key, value in run.items() if key != 'battles'} | {'battles': battles}
    (folder / 'viewer-data.json').write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')),
                                              encoding='utf-8')
    return data


def build(runs):
    runs = Path(runs)
    index = json.loads((runs / 'index.json').read_text(encoding='utf-8'))['runs'] if (runs / 'index.json').exists() else []
    site = runs.parent / 'site'
    (site / 'data').mkdir(parents=True, exist_ok=True)
    names = None
    for row in index:
        folder = runs / row['id']
        data_file = folder / 'viewer-data.json'
        if not data_file.exists() or data_file.stat().st_mtime < (folder / 'run.json').stat().st_mtime:
            names = names or names_for(json.loads((folder / 'run.json').read_text(encoding='utf-8')))
            data = build_run(folder, names)
        else:
            data = json.loads(data_file.read_text(encoding='utf-8'))
        (folder / 'viewer.html').write_text(page([row], {row['id']: data}, local=True), encoding='utf-8')
        (site / 'data' / f'{row["id"]}.json').write_text(data_file.read_text(encoding='utf-8'), encoding='utf-8')
    (site / 'index.html').write_text(page(index, {}), encoding='utf-8')
    print(f'[viewer] {len(index)}개 실행, {site / "index.html"}')
    return site
