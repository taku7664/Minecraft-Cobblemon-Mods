"""SSH stdin RPC; installed on the operator PC, never in the server repository."""
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import sys
import tarfile
import time
import uuid


class ManagerError(Exception):
    pass


PLAZA = 'world/dimensions/jbro_policy/plaza/'
WIKI = 'config/more-cobblemon-contents/wiki/'
PRIVATE_NAMES = {'jbro-policy-discord.json', 'jbro-policy-discord-status.json',
                 'openrouter.json', 'metrics.properties', 'credentials.json',
                 'ops.json', 'whitelist.json', 'banned-ips.json', 'banned-players.json',
                 'usercache.json', 'eula.txt'}
PRIVATE_PARTS = {'.git', '.fabric', 'logs', 'crash-reports', 'backups', 'cache',
                 'libraries', 'versions', 'playerdata', 'stats', 'advancements',
                 'credentials', 'secrets', '.ssh', '.codex', '.claude'}
SECRET_KEYS = {'token', 'bottoken', 'apikey', 'password', 'rconpassword', 'secret',
               'clientsecret', 'accesstoken', 'refreshtoken', 'privatekey', 'authorization'}


def safe_path(value):
    if not isinstance(value, str) or not value or '\\' in value or any(ord(c) < 32 for c in value):
        raise ManagerError('파일 경로가 올바르지 않습니다.')
    p = PurePosixPath(value)
    if p.is_absolute() or str(p) != value or any(x in {'.', '..'} for x in p.parts) or value.startswith('-'):
        raise ManagerError('서버 밖의 경로는 선택할 수 없습니다.')
    lower = value.lower()
    if (set(x.lower() for x in p.parts) & PRIVATE_PARTS or p.name.lower() in PRIVATE_NAMES
            or p.name.startswith('.env') or p.suffix.lower() in {'.pem', '.key', '.secret', '.secrets', '.log', '.bak', '.tmp', '.md'}):
        raise ManagerError('비밀 설정·개인 데이터·작업 파일은 Git 대상에서 제외합니다: ' + value)
    if lower.startswith('world/') and not (value.startswith(PLAZA) or value.startswith('world/datapacks/')
                                            or value == 'world/data/jbro_policy_plaza_biome.dat'):
        raise ManagerError('일반 월드와 플레이어 데이터는 Git 대상에서 제외합니다: ' + value)
    if value in {'deploy-from-dev.bat', 'tools/deploy-server.ps1', 'tools/server-deployment.psm1'}:
        raise ManagerError('로컬 패쳐는 운영 저장소에 올리지 않습니다.')
    return p


def validate_content(path, data):
    """Reject known credential fields without including their values in diagnostics."""
    def secret(key, value):
        key = re.sub(r'[^a-z]', '', str(key).lower())
        if key in SECRET_KEYS and value not in (None, '', False, 0):
            if not (isinstance(value, str) and re.fullmatch(r'\$\{[A-Z_][A-Z_0-9]*\}', value)):
                raise ManagerError('비밀값 필드가 있어 커밋하지 않습니다: ' + path + ' (' + key + ')')
    if path.endswith('.json'):
        try:
            obj = json.loads(data.decode('utf-8-sig'))
        except (ValueError, UnicodeError):
            raise ManagerError('JSON 형식을 확인해주세요: ' + path) from None
        def walk(obj):
            if isinstance(obj, dict):
                for key, value in obj.items():
                    secret(key, value)
                    walk(value)
            elif isinstance(obj, list):
                for value in obj:
                    walk(value)
        walk(obj)
    elif Path(path).suffix in {'.properties', '.toml', '.yaml', '.yml', '.cfg', '.conf'}:
        for line in data.decode('utf-8-sig', errors='replace').splitlines():
            if line.lstrip().startswith(('#', '!')):
                continue
            match = re.match(r'\s*((?:\\.|[^=:\s])+)(?:\s*[=:]\s*|\s+)(.*)', line)
            if match:
                key = re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m[1], 16)), match[1])
                value = match[2].strip().strip('\"\'')
                secret(key, value)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


class TmuxRuntime:
    def __init__(self, root, session='ppakemon'):
        if not re.fullmatch(r'[a-zA-Z0-9_-]+', session):
            raise ManagerError('tmux 세션 이름이 올바르지 않습니다.')
        self.root, self.session = root, session
        self.pane = session + ':0.0'
        self.log = root / 'logs/latest.log'

    def run(self, *args, check=True):
        r = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', timeout=15)
        if check and r.returncode:
            raise ManagerError('콘솔 작업 실패: ' + r.stderr.strip()[:400])
        return r

    def is_running(self):
        r = self.run('tmux', 'display-message', '-p', '-t', self.pane, '#{pane_dead}', check=False)
        if r.returncode == 0 and r.stdout.strip() == '0':
            return True
        if self.port_open():
            raise ManagerError('관리 콘솔 밖에서 서버가 실행 중입니다. 기존 콘솔에서 정상 종료해주세요.')
        for proc in Path('/proc').glob('[0-9]*'):
            try:
                if (proc / 'comm').read_text().strip() == 'java' and (proc / 'cwd').resolve() == self.root:
                    raise ManagerError('관리 콘솔 밖에서 Java가 실행 중이므로 파일 작업을 중단합니다.')
            except (OSError, RuntimeError):
                continue
        return False

    def port_open(self):
        port = 25565
        for line in (self.root / 'server.properties').read_text().splitlines():
            if line.startswith('server-port='):
                port = int(line.split('=', 1)[1])
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=1):
                return True
        except OSError:
            return False

    def log_lines(self):
        return self.log.read_text(errors='replace').splitlines() if self.log.exists() else []

    def stop(self):
        if not self.is_running():
            if self.port_open():
                raise ManagerError('tmux 밖에서 실행 중인 서버가 있습니다. 기존 콘솔에서 정상 종료해주세요.')
            return
        pid = self.run('tmux', 'display-message', '-p', '-t', self.pane, '#{pane_pid}').stdout.strip()
        proc = Path('/proc') / pid
        if (proc / 'cwd').resolve() != self.root or (proc / 'comm').read_text().strip() != 'java':
            raise ManagerError('서버가 시작 중이거나 콘솔 상태가 다릅니다. 기동 후 다시 시도해주세요.')
        offset = len(self.log_lines())
        for command in ('save-all flush', 'stop'):
            self.run('tmux', 'send-keys', '-t', self.pane, '-l', command)
            self.run('tmux', 'send-keys', '-t', self.pane, 'Enter')
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if not proc.exists() and not self.is_running() and not self.port_open():
                lines = self.log_lines()[offset:]
                if any('All dimensions are saved' in x for x in lines):
                    return
                raise ManagerError('서버는 종료됐지만 전체 월드 저장 로그를 확인하지 못했습니다. 파일 작업을 중단합니다.')
            time.sleep(1)
        raise ManagerError('정상 종료를 확인하지 못했습니다. 강제 종료나 파일 교체는 하지 않았습니다.')

    def start(self):
        if self.is_running():
            return
        if self.port_open():
            raise ManagerError('게임 포트를 사용 중인 서버가 있어 중복 실행하지 않습니다.')
        for proc in Path('/proc').glob('[0-9]*'):
            try:
                if (proc / 'comm').read_text().strip() == 'java' and (proc / 'cwd').resolve() == self.root:
                    raise ManagerError('기존 Java 프로세스가 있어 중복 실행하지 않습니다.')
            except (OSError, RuntimeError):
                continue
        offset = len(self.log_lines())
        inode = self.log.stat().st_ino if self.log.exists() else None
        exists = self.run('tmux', 'has-session', '-t', self.session, check=False).returncode == 0
        if exists:
            self.run('tmux', 'respawn-pane', '-t', self.pane)
        else:
            command = ('tmux set-window-option -t ' + self.session + ':0 remain-on-exit on; '
                       'export PATH="$HOME/.local/bin:$PATH"; export JAVA_XMS=2G JAVA_XMX=4G; bash ./run.sh')
            self.run('tmux', 'new-session', '-d', '-s', self.session, '-c', str(self.root), command)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            lines = self.log_lines()
            if self.log.exists() and (self.log.stat().st_ino != inode or len(lines) < offset):
                offset = 0
            if any(re.search(r'\[Server thread/INFO\]: Done \(', x) for x in lines[offset:]) and self.port_open():
                return
            if not self.is_running():
                raise ManagerError('서버 기동이 중단됐습니다. 콘솔에서 시작 훅·모드 오류를 확인해주세요.')
            time.sleep(1)
        raise ManagerError('3분 안에 기동 완료를 확인하지 못했습니다. 콘솔을 확인해주세요.')


class Manager:
    def __init__(self, root, runtime=None, backup_root=None, author_name='', author_email=''):
        self.root = Path(root).resolve()
        self.runtime = runtime or TmuxRuntime(self.root)
        self.backup_root = Path(backup_root or Path.home() / '.local/share/ppakemon-backups/manager')
        self.author_name, self.author_email = author_name, author_email
        self.progress = lambda message: None

    def git(self, *args, check=True, raw=False):
        r = subprocess.run(['git', '--literal-pathspecs', *args], cwd=self.root,
                           capture_output=True, timeout=300)
        if check and r.returncode:
            raise ManagerError('Git 작업 실패: ' + r.stderr.decode('utf-8', errors='replace').strip()[-1200:])
        return r.stdout if raw else r.stdout.decode('utf-8', errors='strict')

    def head(self):
        return self.git('rev-parse', 'HEAD').strip()

    def _check_head(self, expected):
        if self.git('branch', '--show-current').strip() != 'main' or self.head() != expected:
            raise ManagerError('브랜치·커밋이 변경됐습니다. 변경 목록을 다시 확인해주세요.')
        if self.git('diff', '--cached', '--name-only').strip():
            raise ManagerError('기존에 스테이징한 작업이 있습니다. 그 작업을 먼저 정리해주세요.')

    def _path(self, name):
        p = self.root / safe_path(name)
        # Check each component even for a deleted file or a dangling symlink.
        for candidate in [p, *p.parents]:
            if candidate == self.root:
                break
            if candidate.is_symlink():
                raise ManagerError('심볼릭 링크는 파일 작업에서 제외합니다: ' + name)
        if not p.resolve().is_relative_to(self.root):
            raise ManagerError('서버 밖의 파일은 처리할 수 없습니다.')
        return p

    def _changes(self):
        parts = self.git('-c', 'status.renames=false', 'status', '--porcelain=v1', '-z',
                         '--untracked-files=all').split('\0')
        return [{'path': p[3:], 'status': p[:2]} for p in parts if p]

    def status(self):
        changes = []
        excluded = 0
        for entry in self._changes():
            try:
                p = self._path(entry['path'])
                if p.is_file():
                    if p.suffix in {'.json', '.properties', '.toml', '.yaml', '.yml', '.cfg', '.conf'}:
                        validate_content(entry['path'], p.read_bytes())
                entry['size'] = p.stat().st_size if p.is_file() else 0
                changes.append(entry)
            except ManagerError:
                excluded += 1
        counts = self.git('rev-list', '--left-right', '--count', 'HEAD...origin/main').split()
        return {'head': self.head(), 'running': self.runtime.is_running(), 'changes': changes,
                'ahead': int(counts[0]), 'behind': int(counts[1]), 'excluded': excluded}

    def preserve(self, name):
        if name.startswith('world/'):
            return not (name.startswith('world/datapacks/') and name != 'world/datapacks/cobblemon-startup-hooks.zip')
        if name.startswith(WIKI):
            return False
        if name.startswith('config/') and (self.root / name).exists():
            return True
        return name in {'server.properties', 'startup-hooks.json', 'run.sh', 'run.bat'}

    def plan_update(self, fetch=True):
        if fetch:
            self.git('fetch', 'origin', 'main')
        old, new = self.head(), self.git('rev-parse', 'origin/main').strip()
        self._check_head(old)
        if self.git('merge-base', old, new).strip() != old:
            raise ManagerError('로컬 커밋이 있거나 원격과 분기됐습니다. 기존 커밋 푸시를 먼저 사용해주세요.')
        names = [p for p in self.git('diff', '--name-only', '--no-renames', '-z', old, new).split('\0') if p]
        apply, keep = [], []
        for name in names:
            try:
                self._path(name)
            except ManagerError:
                keep.append(name)
                continue
            (keep if self.preserve(name) else apply).append(name)
        dirty = {x['path'] for x in self._changes()}
        conflict = sorted(dirty.intersection(apply))
        if conflict:
            raise ManagerError('직접 수정한 기능 파일과 업데이트가 겹칩니다. 먼저 선택 커밋·푸시해주세요: ' + ', '.join(conflict))
        return {'head': old, 'target': new, 'apply': apply, 'preserve': keep}

    def _backup(self, names):
        self.backup_root.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.backup_root.resolve().is_relative_to(self.root):
            raise ManagerError('백업은 서버 폴더 밖에 저장해야 합니다.')
        world = self.root / 'world'
        candidates = [world, self.root / 'config']
        size = sum(p.stat().st_size for d in candidates if d.is_dir() for p in d.rglob('*') if p.is_file() and not p.is_symlink())
        size += sum(self._path(n).stat().st_size for n in names if self._path(n).is_file())
        if shutil.disk_usage(self.backup_root).free < size + 512 * 1024 * 1024:
            raise ManagerError('백업용 여유 공간이 부족합니다. 기존 백업을 확인해주세요.')
        stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8]
        out = self.backup_root / stamp
        out.mkdir(mode=0o700)
        with tarfile.open(out / 'world-snapshot.tar', 'w') as archive:
            if world.is_dir():
                archive.add(world, arcname='world')
        with tarfile.open(out / 'settings-snapshot.tar', 'w') as archive:
            for name in ['config', 'server.properties', 'startup-hooks.json', 'run.sh', 'run.bat']:
                p = self.root / name
                if p.exists():
                    archive.add(p, arcname=name)
        entries = {}
        for name in names:
            p = self._path(name)
            entries[name] = {'exists': p.is_file(), 'mode': p.stat().st_mode & 0o777 if p.is_file() else None}
            if p.is_file():
                dest = out / 'files' / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(p, dest)
        (out / 'manifest.json').write_text(json.dumps({'head': self.head(), 'files': entries}, indent=2))
        for p in out.iterdir():
            if p.is_file():
                p.chmod(0o600)
        return out

    def _restore(self, backup):
        manifest = json.loads((backup / 'manifest.json').read_text())
        for name, entry in manifest['files'].items():
            p = self._path(name)
            if entry['exists']:
                p.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(backup / 'files' / name, p)
            elif p.is_file():
                p.unlink()
        # --mixed advances only Git metadata; preserved runtime files are never reset.
        self.git('reset', '--mixed', manifest['head'])

    def _target_bytes(self, target, name):
        mode = self.git('ls-tree', target, '--', name).split(' ', 1)[0]
        if mode not in {'100644', '100755'}:
            raise ManagerError('일반 파일이 아닌 업데이트는 중단합니다: ' + name)
        data = self.git('show', target + ':' + name, raw=True)
        if data.startswith(b'version https://git-lfs.github.com/spec/v1\n'):
            match = re.search(rb'oid sha256:([0-9a-f]{64})\nsize (\d+)', data)
            if not match:
                raise ManagerError('LFS 정보가 올바르지 않습니다: ' + name)
            oid = match[1].decode()
            common = Path(self.git('rev-parse', '--git-common-dir').strip())
            if not common.is_absolute():
                common = self.root / common
            obj = common / 'lfs/objects' / oid[:2] / oid[2:4] / oid
            if not obj.exists():
                self.git('lfs', 'fetch', 'origin', target)
            if not obj.is_file() or obj.stat().st_size != int(match[2]) or digest(obj) != oid:
                raise ManagerError('LFS 실파일 검증이 실패했습니다: ' + name)
            data = obj
        if isinstance(data, bytes) or Path(name).suffix in {'.json', '.properties', '.toml', '.yaml', '.yml', '.cfg', '.conf'}:
            validate_content(name, data.read_bytes() if isinstance(data, Path) else data)
        return data, int(mode, 8) & 0o777

    def _write_target(self, target, name, data, mode):
        p = self._path(name)
        p.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, Path):
            shutil.copyfile(data, p)
        else:
            p.write_bytes(data)
        p.chmod(mode)

    def update(self, expected_head, expected_target):
        plan = self.plan_update(fetch=False)
        if plan['head'] != expected_head or plan['target'] != expected_target:
            raise ManagerError('업데이트 대상 커밋이 바뀌었습니다. 다시 검사해주세요.')
        if expected_head == expected_target:
            return {'message': '이미 최신 커밋입니다. 서버를 재시작하지 않았습니다.', 'head': expected_head}
        if not plan['apply']:
            self.git('reset', '--mixed', expected_target)
            return {'message': '원격 커밋 정보를 갱신했습니다. 기존 설정과 월드는 그대로이며 재시작하지 않았습니다.',
                    'head': self.head(), 'applied': [], 'preserved': plan['preserve']}
        self.progress('업데이트 파일·LFS 실파일을 확인하고 있습니다.')
        target_files = set(p for p in self.git('ls-tree', '-r', '--name-only', '-z', expected_target).split('\0') if p)
        prepared = {name: self._target_bytes(expected_target, name) for name in plan['apply'] if name in target_files}
        running = self.runtime.is_running()
        backup = None
        try:
            if running:
                self.progress('월드를 저장하고 서버를 정상 종료하고 있습니다.')
                self.runtime.stop()
            self.progress('서버 밖에 월드와 설정을 백업하고 있습니다.')
            backup = self._backup(plan['apply'])
            self.progress('기능 파일을 설치하고 해시를 확인하고 있습니다.')
            for name in plan['apply']:
                if name in prepared:
                    data, mode = prepared[name]
                    self._write_target(expected_target, name, data, mode)
                    expected = digest(data) if isinstance(data, Path) else hashlib.sha256(data).hexdigest()
                    if digest(self._path(name)) != expected:
                        raise ManagerError('설치 파일 해시가 다릅니다: ' + name)
                else:
                    self._path(name).unlink(missing_ok=True)
            self.git('reset', '--mixed', expected_target)
            if running:
                self.progress('서버를 다시 시작하고 기동 완료를 확인하고 있습니다.')
                self.runtime.start()
            return {'message': '기능 업데이트를 완료했습니다. 기존 설정과 월드·플레이어·광장을 보존했습니다.',
                    'head': self.head(), 'backup': str(backup), 'applied': plan['apply'], 'preserved': plan['preserve']}
        except Exception as error:
            try:
                if backup:
                    if self.runtime.is_running():
                        self.runtime.stop()
                    self._restore(backup)
                if running and not self.runtime.is_running():
                    self.runtime.start()
            except Exception as recovery:
                raise ManagerError('업데이트 중단. 복구도 완료하지 못했습니다. 백업: ' + str(backup) + '\n' + str(error) + '\n' + str(recovery)) from None
            raise ManagerError('업데이트를 중단하고 이전 기능 파일을 복구했습니다. 월드 복구는 하지 않았습니다.\n' + str(error)) from None

    def _push(self):
        self.git('push', 'origin', 'main')
        remote = self.git('ls-remote', 'origin', 'refs/heads/main').split()[0]
        if remote != self.head():
            raise ManagerError('원격 main과 로컬 커밋이 일치하지 않습니다. 상태를 다시 확인해주세요.')

    def commit(self, names, message, expected_head):
        self._check_head(expected_head)
        if not names or not isinstance(message, str) or not message.strip() or len(message) > 400 or '\0' in message:
            raise ManagerError('파일을 선택하고 커밋 메시지를 입력해주세요.')
        if not self.author_name or not self.author_email:
            raise ManagerError('관리 프로그램 설정에서 Git 작성자 이름과 이메일을 설정해주세요.')
        names = sorted(set(names))
        changed = {x['path'] for x in self._changes()}
        if not set(names).issubset(changed):
            raise ManagerError('선택한 파일의 변경 상태가 달라졌습니다. 다시 검사해주세요.')
        for name in names:
            p = self._path(name)
            if p.is_file():
                validate_content(name, p.read_bytes())
        running = self.runtime.is_running()
        committed = None
        try:
            if running:
                self.progress('월드를 저장하고 서버를 정상 종료하고 있습니다.')
                self.runtime.stop()
            self.progress('서버 밖에 월드와 설정을 백업하고 있습니다.')
            backup = self._backup(names)
            for name in names:
                p = self._path(name)
                if p.is_file():
                    validate_content(name, p.read_bytes())
            self.git('add', '-A', '--', *names)
            staged = {p for p in self.git('diff', '--cached', '--name-only', '-z').split('\0') if p}
            if not staged.issubset(set(names)):
                raise ManagerError('선택 외 파일이 스테이징돼 작업을 중단합니다.')
            if not staged:
                return {'message': '저장·종료 후 커밋할 변경이 없어 커밋하지 않았습니다.'}
            self.git('diff', '--cached', '--check')
            self.progress('선택한 파일만 커밋하고 있습니다.')
            self.git('-c', 'user.name=' + self.author_name, '-c', 'user.email=' + self.author_email,
                     'commit', '-m', message.strip(), '--only', '--', *sorted(staged))
            committed = self.head()
            actual = set(p for p in self.git('diff-tree', '--no-commit-id', '--name-only', '-r', '-z', committed).split('\0') if p)
            if actual != staged:
                raise ManagerError('커밋 파일 범위가 선택과 다릅니다. 푸시를 중단합니다.')
            self.progress('커밋·LFS 파일을 푸시하고 원격 동기화를 확인하고 있습니다.')
            self._push()
            return {'message': '선택 파일만 커밋·푸시했습니다.', 'commit': committed, 'files': sorted(actual), 'backup': str(backup)}
        except Exception as error:
            if not committed:
                self.git('reset', '-q', 'HEAD', '--', *names, check=False)
            suffix = ('\n로컬 커밋 ' + committed + '은 보존됐습니다. 기존 커밋 푸시로 재시도할 수 있습니다.') if committed else ''
            raise ManagerError(str(error) + suffix) from None
        finally:
            if running and not self.runtime.is_running():
                self.progress('서버를 다시 시작하고 기동 완료를 확인하고 있습니다.')
                self.runtime.start()


def dispatch(request):
    root = Path(request['root']).resolve()
    m = Manager(root, author_name=request.get('author_name', ''), author_email=request.get('author_email', ''))
    if m.git('rev-parse', '--show-toplevel').strip() != str(root):
        raise ManagerError('서버 저장소 경로가 올바르지 않습니다.')
    if m.git('remote', 'get-url', 'origin').strip() not in {
            'https://github.com/taku7664/MinecraftPPakemonServer.git',
            'https://github.com/taku7664/MinecraftPPakemonServer',
            'git@github.com:taku7664/MinecraftPPakemonServer.git'}:
        raise ManagerError('예상한 운영 저장소가 아니므로 중단합니다.')
    m.runtime = TmuxRuntime(root, request.get('session', 'ppakemon'))
    job = request.get('job')
    if job:
        m.progress = lambda message: write_job(job, 'progress.json', {'message': message})
    action = request['action']
    if action == 'status':
        return m.status()
    if action == 'plan':
        return m.plan_update()
    import fcntl
    lockroot = Path.home() / '.local/share/ppakemon-manager'
    lockroot.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (lockroot / 'operation.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ManagerError('다른 관리 작업이 진행 중입니다. 완료 후 다시 시도해주세요.') from None
        if action == 'update':
            return m.update(request['head'], request['target'])
        if action == 'commit':
            return m.commit(request['files'], request['message'], request['head'])
        if action == 'push':
            m._push()
            return {'message': '기존 main 커밋을 푸시하고 원격 동기화를 확인했습니다.', 'head': m.head()}
        if action == 'start':
            m.runtime.start()
            return {'message': '서버가 실행 중입니다.', 'running': True}
        if action == 'stop':
            m.runtime.stop()
            return {'message': '월드 저장과 서버 정상 종료를 확인했습니다.', 'running': False}
        raise ManagerError('알 수 없는 관리 작업입니다.')


def job_dir(job):
    if not isinstance(job, str) or not re.fullmatch(r'[0-9a-f]{32}', job):
        raise ManagerError('관리 작업 번호가 올바르지 않습니다.')
    return Path.home() / '.local/share/ppakemon-manager/jobs' / job


def write_job(job, name, value):
    folder = job_dir(job)
    temp = folder / (name + '.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=True))
    temp.chmod(0o600)
    temp.replace(folder / name)


def run_job(encoded):
    request = json.loads(base64.b64decode(encoded))
    try:
        write_job(request['job'], 'worker.json', {'pid': os.getpid()})
        write_job(request['job'], 'progress.json', {'message': '관리 작업을 시작하고 있습니다.'})
        result = {'ok': True, 'data': dispatch(request)}
    except Exception as error:
        result = {'ok': False, 'error': str(error)}
    write_job(request['job'], 'result.json', result)


def rpc(encoded, source64=''):
    try:
        request = json.loads(base64.b64decode(encoded))
        action = request['action']
        if action == 'job':
            folder = job_dir(request['job'])
            if (folder / 'result.json').exists():
                result = {'ok': True, 'data': {'finished': True, 'result': json.loads((folder / 'result.json').read_text())}}
            elif not folder.exists():
                result = {'ok': True, 'data': {'finished': True, 'result': {'ok': False, 'error': '원격 작업이 생성되지 않았습니다. 상태를 확인한 뒤 다시 시도해주세요.'}}}
            elif (folder / 'progress.json').exists():
                worker = folder / 'worker.json'
                dead = False
                if worker.exists():
                    pid = json.loads(worker.read_text())['pid']
                    proc = Path('/proc') / str(pid)
                    dead = not proc.exists()
                    if proc.exists():
                        try:
                            dead = (proc / 'stat').read_text().split(') ', 1)[1].startswith('Z ')
                        except OSError:
                            dead = True
                elif time.time() - folder.stat().st_mtime > 45:
                    dead = True
                if dead:
                    final = folder / 'result.json'
                    outcome = json.loads(final.read_text()) if final.exists() else {'ok': False, 'error': '원격 작업자가 종료됐습니다. 상태와 콘솔을 확인해주세요. 백업은 보존돼 있습니다.'}
                    result = {'ok': True, 'data': {'finished': True, 'result': outcome}}
                else:
                    result = {'ok': True, 'data': {'finished': False, **json.loads((folder / 'progress.json').read_text())}}
            else:
                result = {'ok': True, 'data': {'finished': False, 'message': '원격 작업 응답을 기다리고 있습니다.'}}
        elif action in {'update', 'commit', 'push', 'start', 'stop'}:
            folder = job_dir(request['job'])
            if not folder.exists():
                if not source64:
                    raise ManagerError('원격 실행 코드가 누락됐습니다.')
                folder.mkdir(parents=True, mode=0o700)
                write_job(request['job'], 'progress.json', {'message': '원격 작업을 준비하고 있습니다.'})
                child_source = base64.b64decode(source64).decode('utf-8') + '\nrun_job(' + repr(encoded) + ')\n'
                with (folder / 'worker.log').open('wb') as output:
                    child = subprocess.Popen([sys.executable, '-u', '-'], cwd=str(Path.home()),
                                             stdin=subprocess.PIPE, stdout=output, stderr=output,
                                             start_new_session=True)
                    child.stdin.write(child_source.encode('utf-8'))
                    child.stdin.close()
            result = {'ok': True, 'data': {'job': request['job']}}
        else:
            result = {'ok': True, 'data': dispatch(request)}
    except Exception as error:
        result = {'ok': False, 'error': str(error)}
    print(json.dumps(result, ensure_ascii=True))
