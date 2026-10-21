"""Better AI self-play arena: plays boss-vs-boss battles with a real mod JAR, keeps every record and builds the viewer.

  python arena.py run BATTLES [--threads N] [--seed S] [--jar PATH] [--commit SHA] [--note TEXT]
  python arena.py viewer          rebuild every run's viewer.html and the combined site/ folder
  python arena.py play [--jar PATH] [--port N]   serve "play against the boss" (play.html) and open a tunnel for a phone
  python arena.py play-page       rebuild that page (fonts and Showdown data put in) without restarting the server

Paths come from arena.properties next to this file. Each run lands in <runs>/<timestamp>/ with run.json (the summary),
arena.log (the runner's output) and battle-<i>/ (decisions.jsonl, result.json, referee-input.json). <runs>/index.json
lists all runs. Nothing under <runs> is ever deleted by this script.
"""
import argparse
import datetime
import hashlib
import json
import os
import random
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
MAIN_CLASS = 'jbro.cobblemon.mcc.betterai.BetterAiArenaKt'


def config():
    values = {}
    for line in (HERE / 'arena.properties').read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip()
    return values


def resolve(value):
    path = Path(value)
    return path if path.is_absolute() else (HERE / path).resolve()


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as file:
        for block in iter(lambda: file.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def git_head(repo):
    try:
        return subprocess.run(['git', '-C', str(repo), 'rev-parse', 'HEAD'], capture_output=True, text=True,
                              check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def write_json(path, value):
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=1), encoding='utf-8')
    os.replace(tmp, path)


def team_names(pair, side):
    return [s.get('species', '?') for s in (pair.get(side) or {}).get('sets', [])]


def battle_summary(folder, index):
    entry = {'index': index, 'folder': folder.name}
    result_file = folder / 'result.json'
    if result_file.exists():
        result = json.loads(result_file.read_text(encoding='utf-8'))
        entry.update(winner=result.get('winner'), turns=result.get('turn'), status=result.get('status'))
    else:
        entry['status'] = 'no result'
    referee = folder / 'referee-input.json'
    if referee.exists():
        pair = json.loads(referee.read_text(encoding='utf-8'))
        entry['teams'] = {side: team_names(pair, side) for side in ('p1', 'p2')}
        entry['format'] = pair.get('battleFormat', 'SINGLE')
    return entry


def update_index(runs):
    rows = []
    for folder in sorted(runs.iterdir()):
        summary = folder / 'run.json'
        if folder.is_dir() and summary.exists():
            run = json.loads(summary.read_text(encoding='utf-8'))
            battles = run.get('battles', [])
            rows.append({key: run.get(key) for key in ('id', 'started', 'finished', 'status', 'requested', 'seed',
                                                       'threads', 'jar', 'jarSha256', 'jarCommit', 'repoHead', 'note')}
                        | {'played': len([b for b in battles if b.get('winner') is not None or b.get('status')]),
                           'wins': {side: len([b for b in battles if b.get('winner') == side]) for side in ('p1', 'p2')}})
    write_json(runs / 'index.json', {'runs': rows})
    return rows


def run(args):
    cfg = config()
    jar = Path(args.jar or cfg['modJar']).resolve()
    if not jar.is_file():
        sys.exit(f'모드 JAR이 없습니다: {jar}')
    runner = resolve(cfg['runner'])
    runner_jar = runner / 'betterai-arena-runner.jar'
    if not runner_jar.is_file():
        sys.exit(f'실행부 JAR이 없습니다: {runner_jar} (build-runner.bat으로 만드세요)')
    runs = resolve(cfg['runs'])
    runs.mkdir(parents=True, exist_ok=True)
    started = datetime.datetime.now()
    run_id = started.strftime('%Y%m%d-%H%M%S')
    out = runs / run_id
    out.mkdir()
    threads = args.threads or int(cfg.get('threads', 3))
    seed = args.seed if args.seed is not None else random.randint(1, 2_000_000_000)
    summary = {
        'id': run_id, 'started': started.isoformat(timespec='seconds'), 'finished': None, 'status': 'running',
        'requested': args.battles, 'seed': seed, 'threads': threads,
        'jar': str(jar), 'jarSha256': sha256(jar), 'jarCommit': args.commit,
        'repoHead': git_head(resolve(cfg['repo'])), 'runnerSha256': sha256(runner_jar), 'note': args.note,
        'mode': 'boss vs boss, searchless, team preview on', 'battles': [],
    }
    write_json(out / 'run.json', summary)
    update_index(runs)
    showdown = resolve(cfg['showdown'])
    command = [cfg.get('java', 'java'), f"-Xmx{cfg.get('maxHeap', '6g')}", f'-Djava.io.tmpdir={out / "tmp"}',
               f'-Daiengine.showdown={showdown}', f"-Daiengine.tools={resolve(cfg['tools'])}",
               '-cp', os.pathsep.join(classpath(cfg, jar)), MAIN_CLASS, str(out), str(args.battles), str(seed), str(threads),
               str(showdown)]
    (out / 'tmp').mkdir()
    print(f'[arena] {run_id}: {args.battles}판, 스레드 {threads}, 시드 {seed}', flush=True)
    print(f'[arena] JAR {jar} ({summary["jarSha256"][:12]})', flush=True)
    done = 0
    with open(out / 'arena.log', 'w', encoding='utf-8') as log:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                   encoding='utf-8', errors='replace')
        for line in process.stdout:
            log.write(line)
            log.flush()
            if not line.startswith('ARENA '):
                continue
            event = json.loads(line[6:])
            if event.get('event') == 'battle':
                done += 1
                entry = battle_summary(out / f'battle-{event["index"]}', event['index'])
                if 'failed' in event:
                    entry['failed'] = event['failed']
                summary['battles'] = sorted(summary['battles'] + [entry], key=lambda b: b['index'])
                write_json(out / 'run.json', summary)
                print(f'[arena] {done}/{args.battles} battle-{event["index"]}: 승자 {entry.get("winner")} '
                      f'{entry.get("turns")}턴' + (f' 실패 {entry["failed"]}' if 'failed' in entry else ''), flush=True)
        code = process.wait()
    summary['finished'] = datetime.datetime.now().isoformat(timespec='seconds')
    summary['exitCode'] = code
    summary['status'] = 'done' if code == 0 and done == args.battles else 'incomplete'
    write_json(out / 'run.json', summary)
    # The engine scratch copy (Showdown sources for the run) and JVM temp files are not records.
    shutil.rmtree(out / 'engine', ignore_errors=True)
    shutil.rmtree(out / 'tmp', ignore_errors=True)
    update_index(runs)
    import viewer
    viewer.build(runs)
    wins = {side: len([b for b in summary['battles'] if b.get('winner') == side]) for side in ('p1', 'p2')}
    print(f'[arena] 끝: {summary["status"]}, p1 {wins["p1"]} : p2 {wins["p2"]}, 기록 {out}', flush=True)
    return 0 if summary['status'] == 'done' else 1


def classpath(cfg, jar):
    """The picked mod JAR first, so its classes and resources win; then the runner, its libraries and the data JARs."""
    runner = resolve(cfg['runner'])
    return [str(jar), str(runner / 'betterai-arena-runner.jar')] + sorted(str(p) for p in (runner / 'lib').glob('*.jar'))         + [p for p in cfg.get('dataJars', '').split(';') if p]


DEX_SCRIPT = r"""
const [moves, dex] = [require(process.argv[1] + '/data/moves.js').Moves, require(process.argv[1] + '/data/pokedex.js').Pokedex];
const id = s => String(s).toLowerCase().replace(/[^a-z0-9]/g, '');
const out = { moves: {}, sprites: {} };
for (const [key, m] of Object.entries(moves)) out.moves[key] = [m.type, m.category];
for (const [key, p] of Object.entries(dex)) out.sprites[key] = p.forme ? id(p.baseSpecies) + '-' + id(p.forme) : id(p.name);
process.stdout.write(JSON.stringify(out));
"""


STAT_KEYS = {'hp': 'hp', 'attack': 'atk', 'defence': 'def', 'defense': 'def', 'special_attack': 'spa',
             'special_defence': 'spd', 'special_defense': 'spd', 'speed': 'spe'}


def plain_id(value):
    """A Cobblemon id ('cobblemon:life_orb', 'Life Orb') as Showdown's (lifeorb)."""
    return ''.join(c for c in str(value).split(':')[-1].lower() if c.isalnum())


def league_set(properties, mechanic):
    """One League team line (Cobblemon Pokemon properties) as the bridge's set, as LeagueNativeAuditScenarioTest reads it."""
    tokens = properties.split()
    values = dict(token.split('=', 1) for token in tokens[1:] if '=' in token)
    spread = lambda suffix: {stat: int(values[f'{key}_{suffix}']) for key, stat in STAT_KEYS.items()
                             if f'{key}_{suffix}' in values}
    form = values.get('form')
    entry = {'species': f'{tokens[0]}-{form}' if form else tokens[0], 'level': int(values.get('level', 50)),
             'moves': values['moves'].split(','), 'ability': plain_id(values.get('ability', '')),
             'item': plain_id(values['held_item']) if 'held_item' in values else '',
             'nature': values.get('nature', 'serious'),
             'gender': {'male': 'M', 'female': 'F'}.get(values.get('gender'), ''),
             'evs': spread('ev'), 'ivs': spread('iv')}
    if mechanic == 'DYNAMAX':
        if values.get('gmax_factor') == 'true':
            entry['gigantamax'] = True
        if 'dynamaxPriority' in values:
            entry['dynamaxPriority'] = int(values['dynamaxPriority'])
        if 'canDynamax' in values:
            entry['canDynamax'] = values['canDynamax']
    else:
        # No Dynamax outside Galar: the league's own gimmick (Mega Evolution, by the held stone) is the only one.
        entry['canDynamax'] = 'none'
    return entry


def play_presets():
    """The hard Sinnoh and Galar League trainers' teams and the Battle Factory rental sets, from this checkout's sources."""
    mods = HERE.parents[1] / 'mods'
    league = mods / 'more-cobblemon-contents-league-challenge/src/main/resources'
    data = league / 'data/more_cobblemon_contents_league_challenge/league-challenge'
    lang = json.loads((league / 'assets/more_cobblemon_contents_league_challenge/lang/ko_kr.json').read_text(encoding='utf-8'))
    read = lambda path: json.loads(path.read_text(encoding='utf-8'))
    regions = []
    for file, region in (('active.json', '신오'), ('galar.json', '가라르')):
        hard = read(data / 'leagues' / file)['hard']
        order = []
        for step in hard['gyms'] + hard['finals']:
            order += step if isinstance(step, list) else [step]
        trainers = []
        for challenge_id in order:
            path = challenge_id.split(':', 1)[1]
            challenge = read(data / 'challenges' / f'{path}.json')
            trainer = read(data / 'trainers' / f"{challenge['trainer'].split(':', 1)[1]}.json")
            team = read(data / 'teams' / f"{trainer['team'].split(':', 1)[1]}.json")
            mechanic = challenge.get('mechanic', 'NONE')
            label = lang.get(trainer['name_key'], path) + (' (챔피언컵)' if '/cup_' in f'/{path}' else '')
            trainers.append({'id': path, 'label': label, 'mechanic': mechanic,
                             'sets': [league_set(line, mechanic) for line in team['pokemon']]})
        regions.append({'region': f'하드 {region}', 'trainers': trainers})
    rentals = []
    for path in sorted((mods / 'more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents'
                        '/mcc-battle-factory/rental-sets').glob('*.json')):
        for rental in read(path)['rental_sets']:
            rentals.append({'species': plain_id(rental['species_id']), 'level': 50,
                            'moves': [plain_id(m) for m in rental['moves']], 'ability': plain_id(rental['ability_id']),
                            'item': plain_id(rental.get('held_item_id', '')), 'nature': plain_id(rental['nature_id']),
                            'gender': '', 'evs': {STAT_KEYS[k]: v for k, v in rental.get('evs', {}).items() if k in STAT_KEYS},
                            'ivs': {}})
    return {'leagues': regions, 'rentals': rentals}


def play_page(cfg, home):
    """play.html with the wiki's Galmuri font and Showdown's move types and sprite names put in, as page.html."""
    import base64
    fonts = HERE.parents[2] / 'server-wiki' / 'assets' / 'fonts'
    faces = ''.join(
        f'@font-face {{ font-family: "Wiki Galmuri"; font-weight: {weight}; font-display: swap; '
        f'src: url(data:font/woff2;base64,{base64.b64encode((fonts / name).read_bytes()).decode()}) format("woff2"); }}\n'
        for name, weight in (('Galmuri11.woff2', 400), ('Galmuri11-Bold.woff2', 700)) if (fonts / name).is_file())
    dex = subprocess.run(['node', '-e', DEX_SCRIPT, str(resolve(cfg['showdown']))], capture_output=True, text=True,
                         encoding='utf-8', check=True).stdout
    page = (HERE / 'play.html').read_text(encoding='utf-8')
    # Mega Showdown's items (Mega Stones, cores) are named in its own JAR, not in Cobblemon's language file.
    dex = json.loads(dex)
    dex['itemNames'] = {}
    import zipfile
    for jar in sorted((resolve(cfg['showdown']).parent / 'mods').glob('mega_showdown*.jar')):
        with zipfile.ZipFile(jar) as archive:
            lang = json.loads(archive.read('assets/mega_showdown/lang/ko_kr.json').decode('utf-8'))
        dex['itemNames'] = {plain_id(key.split('.', 2)[2]): name for key, name in lang.items()
                            if key.startswith('item.mega_showdown.') and key.count('.') == 2}
    dex = json.dumps(dex, ensure_ascii=False, separators=(',', ':'))
    presets = json.dumps(play_presets(), ensure_ascii=False, separators=(',', ':'))
    page = page.replace('/*__FONTS__*/', faces).replace('"__DEX__"', dex.replace('</', '<\\/')) \
        .replace('"__PRESETS__"', presets.replace('</', '<\\/'))
    home.mkdir(parents=True, exist_ok=True)
    (home / 'page.html').write_text(page, encoding='utf-8')
    return home / 'page.html'


def serve_play(args):
    """The play server on 127.0.0.1 and, when cloudflared is there, a quick tunnel to it. Runs until stopped."""
    import re
    import secrets
    import threading
    cfg = config()
    jar = Path(args.jar or cfg.get('playJar') or cfg['modJar']).resolve()
    if not jar.is_file():
        sys.exit(f'모드 JAR이 없습니다: {jar}')
    home = resolve(cfg.get('play', 'F:/AI/betterai-arena/play'))
    home.mkdir(parents=True, exist_ok=True)
    (home / 'tmp').mkdir(exist_ok=True)
    port = args.port or int(cfg.get('playPort', 8790))
    token = secrets.token_hex(16)
    showdown = resolve(cfg['showdown'])
    server = subprocess.Popen(
        [cfg.get('java', 'java'), f"-Xmx{cfg.get('maxHeap', '6g')}", f'-Djava.io.tmpdir={home / "tmp"}',
         f'-Daiengine.showdown={showdown}', f"-Daiengine.tools={resolve(cfg['tools'])}",
         '-cp', os.pathsep.join(classpath(cfg, jar)), 'jbro.cobblemon.mcc.betterai.BetterAiPlayServerKt',
         str(port), token, str(home), str(showdown), str(play_page(cfg, home))],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    info = {'started': datetime.datetime.now().isoformat(timespec='seconds'), 'jar': str(jar), 'jarSha256': sha256(jar),
            'local': f'http://127.0.0.1:{port}/{token}/', 'public': None, 'pid': server.pid}
    log = open(home / 'server.log', 'a', encoding='utf-8')

    def pump(stream, tag, on_line=None):
        for text in stream:
            log.write(f'[{tag}] {text}')
            log.flush()
            if on_line:
                on_line(text)

    ready = threading.Event()
    threading.Thread(target=pump, args=(server.stdout, 'server', lambda t: ready.set() if 'listening' in t else None),
                     daemon=True).start()
    if not ready.wait(120) or server.poll() is not None:
        server.kill()
        sys.exit(f'대전 서버가 뜨지 않았습니다. {home / "server.log"}를 보세요.')
    tunnel = None
    cloudflared = cfg.get('cloudflared')
    if cloudflared and Path(cloudflared).is_file():
        tunnel = subprocess.Popen([cloudflared, 'tunnel', '--no-autoupdate', '--url', f'http://127.0.0.1:{port}'],
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8',
                                  errors='replace')
        found = threading.Event()

        def seen(text):
            match = re.search(r'https://[a-z0-9-]+\.trycloudflare\.com', text)
            if match and not found.is_set():
                info['public'] = f'{match.group(0)}/{token}/'
                found.set()
        threading.Thread(target=pump, args=(tunnel.stdout, 'tunnel', seen), daemon=True).start()
        found.wait(60)
    info['tunnelPid'] = tunnel.pid if tunnel else None
    write_json(home / 'server.json', info)
    print(f"[play] PC: {info['local']}", flush=True)
    print(f"[play] 휴대폰: {info['public'] or '(터널 없음: arena.properties의 cloudflared 경로를 확인하세요)'}", flush=True)
    try:
        server.wait()
    finally:
        if tunnel:
            tunnel.terminate()
        info['stopped'] = datetime.datetime.now().isoformat(timespec='seconds')
        write_json(home / 'server.json', info)


def main():
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    play = sub.add_parser('run')
    play.add_argument('battles', type=int)
    play.add_argument('--threads', type=int)
    play.add_argument('--seed', type=int)
    play.add_argument('--jar')
    play.add_argument('--commit', help='git commit the picked JAR was built from, when known')
    play.add_argument('--note')
    sub.add_parser('viewer')
    sub.add_parser('play-page', help='rebuild the play page only; the running server serves it on the next load')
    serve = sub.add_parser('play')
    serve.add_argument('--jar')
    serve.add_argument('--port', type=int)
    args = parser.parse_args()
    sys.path.insert(0, str(HERE))
    if args.command == 'run':
        sys.exit(run(args))
    if args.command == 'play':
        return serve_play(args)
    if args.command == 'play-page':
        cfg = config()
        print(play_page(cfg, resolve(cfg.get('play', 'F:/AI/betterai-arena/play'))))
        return
    import viewer
    viewer.build(resolve(config()['runs']))


if __name__ == '__main__':
    main()
