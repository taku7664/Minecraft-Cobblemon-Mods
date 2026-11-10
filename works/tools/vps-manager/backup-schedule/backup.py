#!/usr/bin/env python3
"""Verified, dated backups; never stop or restart the Minecraft process."""
import argparse
import contextlib
import datetime
import fcntl
import json
import os
from pathlib import Path
import pwd
import shutil
import signal
import subprocess
import sys
import tempfile
import time


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)


def validate_paths(source, state):
    source, state = source.resolve(), state.resolve()
    if source == state or source in state.parents:
        raise ValueError('Backup state must be outside the server directory')
    if not source.is_dir():
        raise ValueError('Server directory does not exist')


def server_pids(source):
    result = []
    for p in Path('/proc').iterdir():
        if not p.name.isdigit():
            continue
        try:
            if (p / 'comm').read_text().strip() == 'java' and (p / 'cwd').resolve() == source.resolve():
                result.append(int(p.name))
        except (OSError, RuntimeError):
            continue
    return result


def ancestor(pid, parent):
    seen = set()
    while pid > 1 and pid not in seen:
        if pid == parent:
            return True
        seen.add(pid)
        try:
            pid = int(next(x.split()[1] for x in Path(f'/proc/{pid}/status').read_text().splitlines() if x.startswith('PPid:')))
        except (OSError, StopIteration):
            return False
    return False


def process_start(pid):
    return Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[19]


class Console:
    def __init__(self, uid, pane, log):
        self.prefix = [] if uid == os.getuid() else ['/usr/sbin/runuser', '-u', pwd.getpwuid(uid).pw_name, '--']
        self.pane, self.log = pane, Path(log)

    @classmethod
    def find(cls, source, pid):
        uid = Path(f'/proc/{pid}').stat().st_uid
        prefix = [] if uid == os.getuid() else ['/usr/sbin/runuser', '-u', pwd.getpwuid(uid).pw_name, '--']
        listing = run(prefix + ['/usr/bin/tmux', 'list-panes', '-a', '-F', '#{pane_id} #{pane_pid}'], capture_output=True, text=True).stdout
        matches = [line.split()[0] for line in listing.splitlines() if ancestor(pid, int(line.split()[1]))]
        if len(matches) != 1:
            raise RuntimeError('Cannot identify the running server console; backup refused')
        return cls(uid, matches[0], source / 'logs/latest.log')

    def command(self, text, expected, timeout=120):
        offset = self.log.stat().st_size
        run(self.prefix + ['/usr/bin/tmux', 'send-keys', '-t', self.pane, '-l', text])
        run(self.prefix + ['/usr/bin/tmux', 'send-keys', '-t', self.pane, 'Enter'])
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self.log.open('rb') as f:
                f.seek(offset)
                output = f.read().decode('utf-8', errors='replace')
            if expected in output or (text == 'save-on' and 'Saving is already turned on' in output):
                return
            if 'Saving is already turned off' in output:
                raise RuntimeError('Saving was already disabled; preserve existing state')
            time.sleep(0.5)
        raise RuntimeError(f'Console did not confirm {text}')


def restore_saving(state):
    pending = state / 'save-pending.json'
    if not pending.exists():
        return
    data = json.loads(pending.read_text())
    if Path(f"/proc/{data['pid']}").exists() and process_start(data['pid']) == data['start_ticks']:
        console = Console(data['uid'], data['pane'], data['log'])
        console.command('save-on', 'Automatic saving is now enabled')
    pending.unlink()


@contextlib.contextmanager
def saved_server(source, state):
    pids = server_pids(source)
    if len(pids) > 1:
        raise RuntimeError('Multiple server processes; backup refused')
    if not pids:
        yield
        if server_pids(source):
            raise RuntimeError('Server started during snapshot; snapshot refused')
        return
    pid = pids[0]
    console = Console.find(source, pid)
    pending = state / 'save-pending.json'
    pending.write_text(json.dumps({'pid': pid, 'start_ticks': process_start(pid), 'uid': Path(f'/proc/{pid}').stat().st_uid, 'pane': console.pane, 'log': str(console.log)}))
    try:
        try:
            console.command('save-off', 'Automatic saving is now disabled')
        except RuntimeError as e:
            if 'already disabled' in str(e):
                pending.unlink()
            raise
        console.command('save-all flush', 'Saved the game')
        yield
        if server_pids(source) != [pid]:
            raise RuntimeError('Server process changed during snapshot; snapshot refused')
    finally:
        restore_saving(state)


BACKUP_PATHS = ('world', 'jbro-policy')


def make_snapshot(source, state):
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    snapshot = Path(tempfile.mkdtemp(prefix=f'snapshot-{stamp}-', dir=state))
    try:
        missing = [name for name in BACKUP_PATHS if not (source / name).exists()]
        if missing:
            raise RuntimeError(f'Backup path missing: {missing}')
        partial = snapshot / 'server.tar.gz.partial'
        with saved_server(source, state):
            # No staging copy: only the backup itself uses disk. Exit 1 means a file changed while being read.
            done = subprocess.run(['/usr/bin/nice', '-n', '10', '/usr/bin/tar', '--warning=no-file-changed', '--warning=no-file-removed',
                                   '-I', 'gzip -1', '-cf', str(partial), '-C', str(source), *BACKUP_PATHS])
            if done.returncode not in (0, 1):
                raise RuntimeError(f'tar failed: {done.returncode}')
        run(['/usr/bin/tar', '-tzf', partial], stdout=subprocess.DEVNULL)
        partial.rename(snapshot / 'server.tar.gz')
        hashes = run(['/usr/bin/sha256sum', 'server.tar.gz'], cwd=snapshot, capture_output=True, text=True).stdout
        (snapshot / 'SHA256SUMS').write_text(hashes)
        (snapshot / 'READY').write_text(stamp + '\n')
        return snapshot
    except BaseException:
        if snapshot.resolve().parent == state.resolve():
            shutil.rmtree(snapshot)
        raise


def upload(snapshot, remote, config):
    destination = remote.rstrip('/') + '/' + snapshot.name
    options = ['--config', str(config), '--retries', '3', '--log-level', 'INFO']
    run(['/usr/bin/rclone', 'copy', str(snapshot), destination, '--exclude', 'COMPLETE', *options])
    run(['/usr/bin/rclone', 'check', str(snapshot), destination, '--exclude', 'COMPLETE', '--one-way', *options])
    (snapshot / 'COMPLETE').write_text('Upload and checksum verification succeeded\n')
    run(['/usr/bin/rclone', 'copyto', str(snapshot / 'COMPLETE'), destination + '/COMPLETE', *options])
    print('BACKUP_VERIFIED', snapshot.name, flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, default=Path('/srv/MinecraftPPakemonServer'))
    parser.add_argument('--state', type=Path, default=Path('/var/lib/ppakemon-gdrive'))
    parser.add_argument('--remote', default='gdrive:VPS-backups/PPakemon')
    parser.add_argument('--snapshot-only', action='store_true')
    parser.add_argument('--restore-saving', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    validate_paths(args.source, args.state)
    args.state.mkdir(parents=True, exist_ok=True)
    with (args.state / 'backup.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print('BACKUP_ALREADY_RUNNING')
            return
        restore_saving(args.state)
        if args.restore_saving:
            return
        config = args.state / 'rclone.conf'
        if not args.snapshot_only:
            run(['/usr/bin/rclone', 'mkdir', args.remote, '--config', config])
            run(['/usr/bin/rclone', 'lsf', args.remote, '--config', config], stdout=subprocess.DEVNULL)
            for snapshot in sorted(args.state.glob('snapshot-*')):
                if (snapshot / 'READY').exists() and not (snapshot / 'UPLOADED').exists():
                    upload(snapshot, args.remote, config)
                    (snapshot / 'UPLOADED').touch()
        if not args.snapshot_only:
            # Keep backups on Drive only: drop verified uploads and failed partials before staging.
            for leftover in sorted(args.state.glob('snapshot-*')):
                if leftover.resolve().parent == args.state.resolve() and (
                        (leftover / 'UPLOADED').exists() or not (leftover / 'READY').exists()):
                    shutil.rmtree(leftover)
        snapshot = make_snapshot(args.source, args.state)
        print('SNAPSHOT_VERIFIED', snapshot.name, flush=True)
        if not args.snapshot_only:
            upload(snapshot, args.remote, config)
            (snapshot / 'UPLOADED').touch()
            if snapshot.resolve().parent == args.state.resolve():
                shutil.rmtree(snapshot)
            (args.state / 'last-success').touch()


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        main()
    except Exception as exc:
        print(f'BACKUP_FAILED: {exc}', file=sys.stderr)
        sys.exit(1)
