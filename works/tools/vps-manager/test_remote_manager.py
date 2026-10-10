import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from remote_manager import Manager, ManagerError, safe_path


class Runtime:
    def __init__(self, running=True):
        self.running = running
        self.stops = self.starts = 0

    def is_running(self):
        return self.running

    def stop(self):
        self.stops += 1
        self.running = False

    def start(self):
        self.starts += 1
        self.running = True


class ManagerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'server'
        self.root.mkdir()
        self.remote = Path(self.temp.name) / 'origin.git'
        self.run_git('init', '--bare', str(self.remote), cwd=Path(self.temp.name))
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'core.autocrlf', 'false')
        self.write('mods/mod.jar', b'old mod')
        self.write('config/example.json', b'{"volume":1}')
        self.write('server.properties', b'max-players=10\n')
        self.write('startup-hooks.json', b'{"rules":[]}')
        self.write('run.sh', b'#!/bin/sh\n')
        self.write('world/dimensions/jbro_policy/plaza/region/r.0.0.mca', b'old plaza')
        self.write('world/datapacks/cobblemon-startup-hooks.zip', b'generated')
        self.write('world/datapacks/features.zip', b'old pack')
        self.write('config/more-cobblemon-contents/wiki/index.html', b'old wiki')
        self.git('add', '.')
        self.git('commit', '-m', 'base')
        self.git('remote', 'add', 'origin', str(self.remote))
        self.git('push', '-u', 'origin', 'main')
        self.old = self.git('rev-parse', 'HEAD').strip()
        self.runtime = Runtime()
        self.manager = Manager(self.root, runtime=self.runtime,
                               backup_root=Path(self.temp.name) / 'backups',
                               author_name='Fixture', author_email='fixture@example.invalid')

    def run_git(self, *args, cwd=None):
        result = subprocess.run(['git', *args], cwd=cwd or self.root,
                                capture_output=True, text=True, encoding='utf-8')
        if result.returncode:
            raise AssertionError(result.stderr)
        return result.stdout

    def git(self, *args):
        return self.run_git(*args)

    def write(self, path, data):
        p = self.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)

    def upstream(self, changes):
        for path, data in changes.items():
            if data is None:
                (self.root / path).unlink()
            else:
                self.write(path, data)
        self.git('add', '-A')
        self.git('commit', '-m', 'upstream')
        new = self.git('rev-parse', 'HEAD').strip()
        self.git('push', 'origin', 'main')
        self.git('reset', '--hard', self.old)
        return new

    def test_selected_json_and_properties_only_are_committed(self):
        self.write('config/example.json', b'{"volume":2}')
        self.write('server.properties', b'max-players=12\n')
        self.write('startup-hooks.json', b'{"rules":[1]}')
        result = self.manager.commit(['config/example.json', 'server.properties'], 'settings', self.old)
        paths = self.git('diff-tree', '--no-commit-id', '--name-only', '-r', result['commit']).splitlines()
        self.assertEqual(set(paths), {'config/example.json', 'server.properties'})
        self.assertIn('startup-hooks.json', self.git('status', '--short'))
        self.assertEqual((self.runtime.stops, self.runtime.starts), (1, 1))

    def test_literal_path_does_not_stage_matching_other_file(self):
        self.write('config/[a].json', b'{}')
        self.write('config/a.json', b'{}')
        result = self.manager.commit(['config/[a].json'], 'literal', self.old)
        paths = self.git('diff-tree', '--no-commit-id', '--name-only', '-r', result['commit']).splitlines()
        self.assertEqual(paths, ['config/[a].json'])

    def test_secrets_and_player_records_are_excluded_from_status(self):
        for path in ['config/jbro-policy-discord.json', 'world/playerdata/user.dat',
                     '.env', 'config/credentials.json']:
            self.write(path, b'private')
        shown = {x['path'] for x in self.manager.status()['changes']}
        self.assertFalse(shown)

    def test_secret_json_is_rejected_without_disclosing_value(self):
        self.write('config/example.json', b'{"apiKey":"VERY_PRIVATE_VALUE"}')
        with self.assertRaises(ManagerError) as caught:
            self.manager.commit(['config/example.json'], 'bad', self.old)
        self.assertNotIn('VERY_PRIVATE_VALUE', str(caught.exception))
        self.assertEqual(self.runtime.stops, 0)

    def test_properties_password_is_rejected(self):
        self.write('server.properties', b'rcon.password=private\n')
        with self.assertRaises(ManagerError):
            self.manager.commit(['server.properties'], 'bad', self.old)
        self.assertEqual(self.runtime.stops, 0)

    def test_whitespace_and_escaped_properties_secrets_are_rejected(self):
        for data in [b'rcon.password private\n', b'rcon\\u002epassword=private\n']:
            self.write('server.properties', data)
            with self.assertRaises(ManagerError):
                self.manager.commit(['server.properties'], 'bad', self.old)
        self.assertEqual(self.runtime.stops, 0)

    def test_invalid_json_is_rejected_before_stopping(self):
        self.write('config/example.json', b'{oops')
        with self.assertRaises(ManagerError):
            self.manager.commit(['config/example.json'], 'bad', self.old)
        self.assertEqual(self.runtime.stops, 0)

    def test_existing_staged_work_is_not_touched(self):
        self.write('server.properties', b'max-players=12\n')
        self.git('add', 'server.properties')
        self.write('config/example.json', b'{"volume":3}')
        with self.assertRaises(ManagerError):
            self.manager.commit(['config/example.json'], 'bad', self.old)
        self.assertEqual(self.git('diff', '--cached', '--name-only').strip(), 'server.properties')
        self.assertEqual(self.runtime.stops, 0)

    def test_unsafe_paths_are_rejected(self):
        for path in ['../outside', '/etc/passwd', '.git/config', 'world/playerdata/u.dat',
                     'world/level.dat', 'config/jbro-policy-discord.json', '-x', 'config/a\\b.json']:
            with self.subTest(path=path), self.assertRaises(ManagerError):
                safe_path(path)

    def test_symlink_selection_is_rejected(self):
        if os.name == 'nt':
            self.skipTest('Windows fixture cannot assume symlink privilege')
        (self.root / 'config/link.json').symlink_to('/etc/passwd')
        with self.assertRaises(ManagerError):
            self.manager.commit(['config/link.json'], 'bad', self.old)

    def test_stale_selection_is_rejected_before_stop(self):
        self.write('config/example.json', b'{"volume":2}')
        with self.assertRaises(ManagerError):
            self.manager.commit(['config/example.json'], 'bad', '0' * 40)
        self.assertEqual(self.runtime.stops, 0)

    def test_update_preserves_world_settings_and_generated_datapack(self):
        target = self.upstream({'mods/mod.jar': b'new mod', 'config/example.json': b'{"volume":9}',
                               'server.properties': b'max-players=99\n', 'run.sh': b'new runner',
                               'startup-hooks.json': b'{"rules":[9]}',
                               'world/dimensions/jbro_policy/plaza/region/r.0.0.mca': b'new plaza',
                               'world/datapacks/cobblemon-startup-hooks.zip': b'new generated',
                               'world/datapacks/features.zip': b'new pack',
                               'config/more-cobblemon-contents/wiki/index.html': b'new wiki'})
        self.write('config/example.json', b'{"volume":4}')
        self.write('world/playerdata/user.dat', b'player progression')
        self.write('world/dimensions/jbro_policy/plaza/region/r.0.0.mca', b'live plaza')
        self.manager.update(self.old, target)
        self.assertEqual(self.git('rev-parse', 'HEAD').strip(), target)
        for path, expected in {'mods/mod.jar': b'new mod', 'config/example.json': b'{"volume":4}',
                               'server.properties': b'max-players=10\n', 'run.sh': b'#!/bin/sh\n',
                               'startup-hooks.json': b'{"rules":[]}',
                               'world/playerdata/user.dat': b'player progression',
                               'world/dimensions/jbro_policy/plaza/region/r.0.0.mca': b'live plaza',
                               'world/datapacks/cobblemon-startup-hooks.zip': b'generated',
                               'world/datapacks/features.zip': b'new pack',
                               'config/more-cobblemon-contents/wiki/index.html': b'new wiki'}.items():
            self.assertEqual((self.root / path).read_bytes(), expected, path)
        self.assertEqual((self.runtime.stops, self.runtime.starts), (1, 1))

    def test_dirty_feature_update_is_rejected(self):
        target = self.upstream({'mods/mod.jar': b'new mod'})
        self.write('mods/mod.jar', b'local mod')
        with self.assertRaises(ManagerError):
            self.manager.update(self.old, target)
        self.assertEqual((self.root / 'mods/mod.jar').read_bytes(), b'local mod')
        self.assertEqual(self.runtime.stops, 0)

    def test_update_deletes_retired_mod_but_preserves_deleted_config(self):
        target = self.upstream({'mods/mod.jar': None, 'config/example.json': None})
        self.manager.update(self.old, target)
        self.assertFalse((self.root / 'mods/mod.jar').exists())
        self.assertEqual((self.root / 'config/example.json').read_bytes(), b'{"volume":1}')

    def test_update_failure_restores_feature_bytes_and_head(self):
        target = self.upstream({'mods/mod.jar': b'new mod'})
        with patch.object(self.manager, '_write_target', side_effect=OSError('fixture disk failure')):
            with self.assertRaises(ManagerError):
                self.manager.update(self.old, target)
        self.assertEqual(self.git('rev-parse', 'HEAD').strip(), self.old)
        self.assertEqual((self.root / 'mods/mod.jar').read_bytes(), b'old mod')
        self.assertTrue(self.runtime.running)

    def test_lfs_pointer_uses_verified_real_bytes_and_rejects_corruption(self):
        content = b'real mod bytes'
        oid = hashlib.sha256(content).hexdigest()
        pointer = ('version https://git-lfs.github.com/spec/v1\noid sha256:' + oid + '\nsize ' + str(len(content)) + '\n').encode()
        self.write('mods/pointer.jar', pointer)
        self.git('add', 'mods/pointer.jar')
        self.git('commit', '-m', 'pointer fixture')
        obj = self.root / '.git/lfs/objects' / oid[:2] / oid[2:4] / oid
        obj.parent.mkdir(parents=True)
        obj.write_bytes(content)
        data, _ = self.manager._target_bytes(self.manager.head(), 'mods/pointer.jar')
        self.assertIsInstance(data, Path)
        self.assertEqual(data.read_bytes(), content)
        obj.write_bytes(b'corrupt')
        with self.assertRaises(ManagerError):
            self.manager._target_bytes(self.manager.head(), 'mods/pointer.jar')

    def test_failed_new_server_start_restores_previous_feature_and_restarts(self):
        target = self.upstream({'mods/mod.jar': b'new mod'})
        original_start = self.runtime.start
        starts = []
        def start():
            starts.append(True)
            if len(starts) == 1:
                raise ManagerError('new mod startup failed')
            original_start()
        self.runtime.start = start
        with self.assertRaises(ManagerError):
            self.manager.update(self.old, target)
        self.assertEqual(self.git('rev-parse', 'HEAD').strip(), self.old)
        self.assertEqual((self.root / 'mods/mod.jar').read_bytes(), b'old mod')
        self.assertTrue(self.runtime.running)
        self.assertEqual(len(starts), 2)

    def test_protected_only_update_has_no_downtime(self):
        target = self.upstream({'server.properties': b'max-players=99\n'})
        self.manager.update(self.old, target)
        self.assertEqual(self.git('rev-parse', 'HEAD').strip(), target)
        self.assertEqual((self.root / 'server.properties').read_bytes(), b'max-players=10\n')
        self.assertEqual((self.runtime.stops, self.runtime.starts), (0, 0))

    def test_push_failure_retains_commit_and_restarts_server(self):
        self.write('config/example.json', b'{"volume":2}')
        with patch.object(self.manager, '_push', side_effect=ManagerError('push failed')):
            with self.assertRaises(ManagerError):
                self.manager.commit(['config/example.json'], 'settings', self.old)
        self.assertNotEqual(self.git('rev-parse', 'HEAD').strip(), self.old)
        self.assertTrue(self.runtime.running)

    def test_no_update_has_no_downtime(self):
        self.manager.update(self.old, self.old)
        self.assertEqual((self.runtime.stops, self.runtime.starts), (0, 0))

    def test_stopped_server_stays_stopped_after_commit(self):
        self.runtime.running = False
        self.write('config/example.json', b'{"volume":2}')
        self.manager.commit(['config/example.json'], 'settings', self.old)
        self.assertEqual((self.runtime.stops, self.runtime.starts), (0, 0))


if __name__ == '__main__':
    unittest.main()
