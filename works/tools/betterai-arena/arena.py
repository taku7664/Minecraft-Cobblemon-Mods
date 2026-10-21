"""Better AI self-play arena: plays boss-vs-boss battles with a real mod JAR, keeps every record and builds the viewer.

  python arena.py run BATTLES [--threads N] [--seed S] [--jar PATH] [--commit SHA] [--note TEXT]
  python arena.py viewer          rebuild every run's viewer.html and the combined site/ folder

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
        'mode': 'boss vs boss, searchless, quick decider on, team preview on', 'battles': [],
    }
    write_json(out / 'run.json', summary)
    update_index(runs)
    # The picked mod JAR goes first, so its classes and resources win over anything else on the path.
    classpath = [str(jar), str(runner_jar)] + sorted(str(p) for p in (runner / 'lib').glob('*.jar')) \
        + [p for p in cfg.get('dataJars', '').split(';') if p]
    showdown = resolve(cfg['showdown'])
    command = [cfg.get('java', 'java'), f"-Xmx{cfg.get('maxHeap', '6g')}", f'-Djava.io.tmpdir={out / "tmp"}',
               f'-Daiengine.showdown={showdown}', f"-Daiengine.tools={resolve(cfg['tools'])}",
               '-cp', os.pathsep.join(classpath), MAIN_CLASS, str(out), str(args.battles), str(seed), str(threads),
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
    args = parser.parse_args()
    sys.path.insert(0, str(HERE))
    if args.command == 'run':
        sys.exit(run(args))
    import viewer
    viewer.build(resolve(config()['runs']))


if __name__ == '__main__':
    main()
