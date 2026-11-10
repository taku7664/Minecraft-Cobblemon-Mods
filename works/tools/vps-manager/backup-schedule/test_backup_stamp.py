import importlib.util
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('backup', Path(__file__).with_name('backup.py'))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)

with tempfile.TemporaryDirectory() as folder:
    root = Path(folder)
    source = root / 'server'
    source.mkdir()

    def fake_snapshot(_source, state):
        snapshot = state / 'snapshot-test'
        snapshot.mkdir()
        return snapshot

    def run_case(name, upload_error=False, busy=False, snapshot_only=False):
        state = root / name
        state.mkdir()
        argv = ['backup.py', '--source', str(source), '--state', str(state), '--remote', 'test:']
        if snapshot_only:
            argv.append('--snapshot-only')

        def upload(_snapshot, _remote, _config):
            if upload_error:
                raise RuntimeError('test upload failure')

        def flock(_lock, _flags):
            if busy:
                raise BlockingIOError()
        with patch.object(sys, 'argv', argv), \
             patch.object(backup, 'run', return_value=SimpleNamespace(stdout='')), \
             patch.object(backup, 'make_snapshot', side_effect=fake_snapshot), \
             patch.object(backup, 'upload', side_effect=upload), \
             patch.object(backup.fcntl, 'flock', side_effect=flock):
            if upload_error:
                try:
                    backup.main()
                except RuntimeError:
                    pass
                else:
                    raise AssertionError('upload failure was not raised')
            else:
                backup.main()
        return (state / 'last-success').exists()

    assert run_case('success') is True
    assert run_case('failure', upload_error=True) is False
    assert run_case('busy', busy=True) is False
    assert run_case('snapshot_only', snapshot_only=True) is False
print('4 backup stamp cases passed')
