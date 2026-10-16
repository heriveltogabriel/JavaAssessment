#!/usr/bin/env python3
"""Offline recovery regressions: temporary SQLite/files only, no real service/SSH."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import stat
import tempfile
import types
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('recover_access', Path(__file__).with_name('recover-access-on-host.py'))
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)

IDENTITY = {'mode': 'recover', 'name': 'Fixture Admin', 'email': 'admin@example.com'}
ACCOUNT = types.SimpleNamespace(pw_uid=os.getuid(), pw_gid=os.getgid())
STATE = {'LoadState': 'loaded', 'ActiveState': 'active', 'MainPID': '12', 'ControlPID': '0',
         'ControlGroup': '/system.slice/' + recovery.UNIT, 'User': recovery.ACCOUNT}
TEMP_PASSWORD = 'Temporary_fixture_password_123456'


class OfflineTest(unittest.TestCase):
    def setUp(self):
        guard = mock.patch.object(recovery.subprocess, 'run', side_effect=AssertionError('Real subprocess forbidden'))
        guard.start(); self.addCleanup(guard.stop)


class DatabaseTests(OfflineTest):
    def setUp(self):
        super().setUp()
        temp = tempfile.TemporaryDirectory(prefix='access-database-test-'); self.addCleanup(temp.cleanup)
        self.base = Path(temp.name); (self.base / 'data').mkdir()
        self.database = self.base / 'data/inventory.sqlite'
        with sqlite3.connect(self.database) as connection:
            connection.execute('CREATE TABLE metadata(key TEXT PRIMARY KEY,payload TEXT NOT NULL)')
            connection.execute('CREATE TABLE human_users(id TEXT,name TEXT,email TEXT,role TEXT)')
        patch = mock.patch.object(recovery, 'BASE', self.base); patch.start(); self.addCleanup(patch.stop)

    def test_pending_bootstrap_has_no_accounts_and_no_activation(self):
        self.assertEqual({'bootstrap': True, 'admins': []}, recovery.database_state())

    def test_only_existing_administrators_are_offered_without_modifying_database(self):
        with sqlite3.connect(self.database) as connection:
            connection.executemany('INSERT INTO human_users VALUES(?,?,?,?)',
                                   [('1', 'Admin', 'admin@example.com', 'ADMIN'), ('2', 'Reader', 'reader@example.com', 'READER')])
        before = self.database.read_bytes()
        self.assertEqual({'bootstrap': False, 'admins': [{'name': 'Admin', 'email': 'admin@example.com'}]}, recovery.database_state())
        self.assertEqual(before, self.database.read_bytes())

    def test_activated_empty_database_cannot_reopen_provisioning(self):
        with sqlite3.connect(self.database) as connection:
            connection.execute('INSERT INTO metadata VALUES(?,?)', ('humanAuthActivated', 'true'))
        state = recovery.database_state()
        self.assertFalse(state['bootstrap'])
        with self.assertRaisesRegex(recovery.RecoveryError, 'não há ADMIN|Não há ADMIN'):
            recovery.select_identity(state, lambda _: 'reader@example.com')

    def test_unexpected_marker_fails_closed(self):
        with sqlite3.connect(self.database) as connection:
            connection.execute('INSERT INTO metadata VALUES(?,?)', ('humanAuthActivated', '"unknown"'))
        with self.assertRaises(recovery.RecoveryError):
            recovery.database_state()


class IdentityAndPermissionsTests(OfflineTest):
    def test_pending_setup_prompts_name_and_email(self):
        values = iter(['Fixture Admin', 'ADMIN@EXAMPLE.COM'])
        with contextlib.redirect_stdout(io.StringIO()):
            result = recovery.select_identity({'bootstrap': True, 'admins': []}, lambda _: next(values))
        self.assertEqual({'mode': 'provision', 'name': 'Fixture Admin', 'email': 'admin@example.com'}, result)

    def test_reader_email_cannot_be_selected_for_recovery(self):
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(recovery.RecoveryError):
            recovery.select_identity({'bootstrap': False, 'admins': [{'name': 'Admin', 'email': 'admin@example.com'}]}, lambda _: 'reader@example.com')

    def test_invalid_name_or_email_rejected_before_service_stop(self):
        for values in [('bad\nname', 'admin@example.com'), ('Admin', 'not-an-email')]:
            inputs = iter(values)
            with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(recovery.RecoveryError):
                recovery.select_identity({'bootstrap': True, 'admins': []}, lambda _: next(inputs))

    def test_java_comes_from_shlex_parsed_launcher_not_path_search(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder); (base / 'run-server.sh').write_text("#!/bin/sh\nset -eu\nexec '/opt/Java 17/bin/java' -Xmx384m -cp 'classes:lib/*' assessment.AssessmentServer /opt/java-assessment-dashboard\n")
            with mock.patch.object(recovery, 'BASE', base), mock.patch.object(recovery, 'protected', side_effect=lambda p, **_: Path(p)), mock.patch.object(recovery, 'protected_executable', side_effect=Path), mock.patch.object(recovery.os, 'access', return_value=True):
                self.assertEqual(Path('/opt/Java 17/bin/java'), recovery.selected_java())
            (base / 'run-server.sh').write_text('exec /bin/sh -c evil\n')
            with mock.patch.object(recovery, 'BASE', base), mock.patch.object(recovery, 'protected', side_effect=lambda p, **_: Path(p)), self.assertRaises(recovery.RecoveryError):
                recovery.selected_java()

    def test_symlink_and_writable_paths_are_rejected(self):
        # A fixture under cwd avoids accepting world-writable /tmp parents.
        with tempfile.TemporaryDirectory(prefix='recovery-permissions-', dir=Path.cwd()) as folder:
            base = Path(folder); file = base / 'plain'; file.write_text('fixture'); file.chmod(0o600)
            self.assertEqual(file, recovery.protected(file, owners=(os.getuid(),), private=True))
            link = base / 'link'; link.symlink_to(file)
            with self.assertRaisesRegex(recovery.RecoveryError, 'simbólico'):
                recovery.protected(link, owners=(os.getuid(),))
            file.chmod(0o622)
            with self.assertRaisesRegex(recovery.RecoveryError, 'escrita'):
                recovery.protected(file, owners=(os.getuid(),))

    def test_root_maintained_executable_alias_resolves_to_protected_target(self):
        with tempfile.TemporaryDirectory(prefix='recovery-java-', dir=Path.cwd()) as folder:
            base = Path(folder); java = base / 'java'; java.write_text('fixture'); java.chmod(0o700)
            alias = base / 'java-alias'; alias.symlink_to(java)
            real_stat = Path.lstat
            def root_stat(path, *args, **kwargs):
                values = list(real_stat(path, *args, **kwargs)); values[4] = 0
                return os.stat_result(values)
            with mock.patch.object(Path, 'lstat', autospec=True, side_effect=root_stat):
                self.assertEqual(java, recovery.protected_executable(alias))
            def untrusted_alias(path, *args, **kwargs):
                values = list(root_stat(path, *args, **kwargs))
                if path == alias:
                    values[4] = 1234
                return os.stat_result(values)
            with mock.patch.object(Path, 'lstat', autospec=True, side_effect=untrusted_alias), self.assertRaisesRegex(recovery.RecoveryError, 'root'):
                recovery.protected_executable(alias)


class RootParentTests(OfflineTest):
    def setUp(self):
        super().setUp()
        temp = tempfile.TemporaryDirectory(prefix='recovery-root-policy-', dir=Path.cwd()); self.addCleanup(temp.cleanup)
        self.parent = Path(temp.name) / 'root'; self.parent.mkdir(mode=0o755)
        self.base = Path(temp.name) / 'dashboard'; self.base.mkdir()
        for name in ('conf', 'classes/assessment', 'lib', 'data/native'):
            (self.base / name).mkdir(parents=True, mode=0o700)
        for name in ('conf/server.properties', 'classes/assessment/UserAdminTool.class'):
            (self.base / name).write_text('fixture'); (self.base / name).chmod(0o640)
        self.database = self.base / 'data/inventory.sqlite'
        with sqlite3.connect(self.database) as db:
            db.execute('CREATE TABLE inventory(id INTEGER)'); db.execute('INSERT INTO inventory VALUES(42)')
        self.database.chmod(0o600); (self.base / 'data').chmod(0o700)
        original = Path.lstat
        def owners(path, *args, **kwargs):
            values = list(original(path, *args, **kwargs))
            values[4] = ACCOUNT.pw_uid if path == self.base / 'data' or self.base / 'data' in path.parents else 0
            return os.stat_result(values)
        patches = [mock.patch.object(recovery, 'BASE', self.base), mock.patch.object(recovery, 'ROOT_PRIVATE', self.parent),
                   mock.patch.object(Path, 'lstat', autospec=True, side_effect=owners),
                   mock.patch.object(recovery.sys, 'platform', 'linux'), mock.patch.object(recovery.os, 'geteuid', return_value=0),
                   mock.patch.object(recovery.sys.stdin, 'isatty', return_value=True), mock.patch.object(recovery.sys.stdout, 'isatty', return_value=True),
                   mock.patch.object(recovery.pwd, 'getpwnam', return_value=ACCOUNT), mock.patch.object(recovery.shutil, 'which', return_value='/usr/sbin/runuser'),
                   mock.patch.object(recovery, 'protected_executable', side_effect=Path), mock.patch.object(recovery, 'selected_java', return_value=Path('/java/bin/java'))]
        for patch in patches:
            patch.start(); self.addCleanup(patch.stop)

    def test_root_parent_allows_read_or_traverse_without_changing_mode(self):
        for mode in (0o700, 0o710, 0o750, 0o755):
            with self.subTest(mode=oct(mode)):
                self.parent.chmod(mode)
                self.assertEqual(ACCOUNT, recovery.inspect_installation()[0])
                self.assertEqual(mode, stat.S_IMODE(self.parent.stat().st_mode))

    def test_root_parent_still_rejects_group_or_world_write_and_symlinks(self):
        for mode in (0o770, 0o777):
            self.parent.chmod(mode)
            with self.assertRaisesRegex(recovery.RecoveryError, 'escrita'):
                recovery.inspect_installation()
        self.parent.chmod(0o750)
        link = self.parent.with_name('root-link'); link.symlink_to(self.parent)
        with mock.patch.object(recovery, 'ROOT_PRIVATE', link), self.assertRaisesRegex(recovery.RecoveryError, 'simbólico'):
            recovery.inspect_installation()

    def test_backup_and_credentials_remain_private_under_readable_parent(self):
        self.parent.chmod(0o755)
        folder = recovery.backup_installation()
        self.assertEqual(0o700, stat.S_IMODE(folder.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE((folder / 'inventory.sqlite').stat().st_mode))
        recovery.private_record(folder / 'secret.txt', IDENTITY, TEMP_PASSWORD, 'fixture')
        self.assertEqual(0o600, stat.S_IMODE((folder / 'secret.txt').stat().st_mode))
        self.assertEqual(0o755, stat.S_IMODE(self.parent.stat().st_mode))
        with sqlite3.connect(folder / 'inventory.sqlite') as db:
            self.assertEqual([(42,)], db.execute('SELECT id FROM inventory').fetchall())


class HealthTests(OfflineTest):
    def check(self, response_version='1.5.6'):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder); (base / 'conf').mkdir(); (base / 'conf/server.properties').write_text('httpPort=8099\n')
            (base / 'VERSION').write_text('1.5.6\n')
            response = mock.MagicMock(); response.__enter__.return_value = response
            response.status = 200; response.read.return_value = json.dumps({'status': 'ok', 'storage': 'sqlite', 'version': response_version}).encode()
            opener = mock.Mock(); opener.open.return_value = response
            with mock.patch.object(recovery, 'BASE', base), mock.patch.object(recovery, 'protected', side_effect=lambda p, **_: Path(p)), mock.patch.object(recovery.urllib.request, 'build_opener', return_value=opener), mock.patch.object(recovery.time, 'monotonic', side_effect=[0, 100]):
                result = recovery.check_local_health()
            opener.open.assert_called_once_with('http://127.0.0.1:8099/health', timeout=2)
            return result

    def test_local_health_requires_exact_version_and_sqlite(self):
        self.assertEqual('1.5.6', self.check()['version'])

    def test_wrong_version_cannot_confirm_recovery(self):
        with self.assertRaisesRegex(recovery.RecoveryError, 'health'):
            self.check('1.5.3')


class ServiceTests(OfflineTest):
    def test_no_mutation_until_inactive_without_processes(self):
        stopped = {**STATE, 'ActiveState': 'inactive', 'MainPID': '0'}
        with mock.patch.object(recovery, 'run'), mock.patch.object(recovery, 'service_state', return_value=stopped), mock.patch.object(recovery, 'empty_cgroup', return_value=True) as empty:
            recovery.stop_and_verify(STATE)
            empty.assert_called_once_with(STATE['ControlGroup'])

    def test_failed_without_pids_and_empty_previous_cgroup_is_stopped(self):
        stopped = {**STATE, 'ActiveState': 'failed', 'MainPID': '0', 'ControlPID': '0', 'ControlGroup': ''}
        with mock.patch.object(recovery, 'run'), mock.patch.object(recovery, 'service_state', return_value=stopped), mock.patch.object(recovery, 'empty_cgroup', return_value=True) as empty, mock.patch.object(recovery.time, 'monotonic', side_effect=[0, 100]):
            recovery.stop_and_verify(STATE)
            empty.assert_called_once_with(STATE['ControlGroup'])

    def test_failed_with_process_or_populated_cgroup_is_not_stopped(self):
        for pid, control, empty in [('123', '0', True), ('0', '123', True), ('0', '0', False)]:
            stopped = {**STATE, 'ActiveState': 'failed', 'MainPID': pid, 'ControlPID': control}
            with mock.patch.object(recovery, 'run'), mock.patch.object(recovery, 'service_state', return_value=stopped), mock.patch.object(recovery, 'empty_cgroup', return_value=empty), mock.patch.object(recovery.time, 'monotonic', side_effect=[0, 100]), self.assertRaises(recovery.RecoveryError):
                recovery.stop_and_verify(STATE)

    def test_active_process_or_populated_group_blocks_stop_confirmation(self):
        for state, empty in [(STATE, True), ({**STATE, 'ActiveState': 'inactive', 'MainPID': '0'}, False)]:
            with mock.patch.object(recovery, 'run'), mock.patch.object(recovery, 'service_state', return_value=state), mock.patch.object(recovery, 'empty_cgroup', return_value=empty), mock.patch.object(recovery.time, 'monotonic', side_effect=[0, 100]), self.assertRaises(recovery.RecoveryError):
                recovery.stop_and_verify(STATE)


class ApplyTests(OfflineTest):
    def test_secret_is_only_in_private_file_and_cleanup_runs_after_cli_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder); (base / 'data').mkdir(); seen = []
            def fail(args, **kwargs):
                self.assertNotIn(TEMP_PASSWORD, args)
                self.assertEqual(args[1:4], ['-u', recovery.ACCOUNT, '--'])
                secret = Path(args[args.index('--password-file') + 1]); seen.append(secret)
                self.assertEqual(0o600, stat.S_IMODE(secret.stat().st_mode))
                self.assertEqual(TEMP_PASSWORD + '\n', secret.read_text())
                raise recovery.RecoveryError('Falha protegida')
            with mock.patch.object(recovery, 'BASE', base), mock.patch.object(recovery, 'run', side_effect=fail), mock.patch.object(recovery.os, 'chown'), self.assertRaises(recovery.RecoveryError):
                recovery.apply_access(ACCOUNT, Path('/java/bin/java'), '/usr/sbin/runuser', IDENTITY, TEMP_PASSWORD)
            self.assertEqual(1, len(seen)); self.assertFalse(seen[0].exists())
            self.assertEqual([], list((base / 'data').iterdir()))

    def test_cli_response_must_confirm_admin_first_access(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder); (base / 'data').mkdir()
            payload = {'mode': 'recover', 'expiresAt': '2026-10-14T14:00:00Z', 'user': {'email': IDENTITY['email'], 'role': 'READER', 'mustChangePassword': True, 'status': 'FIRST_ACCESS'}}
            with mock.patch.object(recovery, 'BASE', base), mock.patch.object(recovery, 'run', return_value=json.dumps(payload)), mock.patch.object(recovery.os, 'chown'), self.assertRaises(recovery.RecoveryError):
                recovery.apply_access(ACCOUNT, Path('/java/bin/java'), '/usr/sbin/runuser', IDENTITY, TEMP_PASSWORD)

    def test_subprocess_failures_never_echo_stdout_stderr_or_secret(self):
        result = types.SimpleNamespace(returncode=1, stdout=TEMP_PASSWORD, stderr=TEMP_PASSWORD)
        with mock.patch.object(recovery.subprocess, 'run', return_value=result), self.assertRaises(recovery.RecoveryError) as error:
            recovery.run(['fixture-command'], label='Falha local')
        self.assertNotIn(TEMP_PASSWORD, str(error.exception))


class WorkflowTests(OfflineTest):
    def workflow(self, *, provision=False, failure=None, restart_failure=False, sidecar_failure=False):
        with tempfile.TemporaryDirectory() as folder, contextlib.ExitStack() as stack:
            backup = Path(folder) / 'backup'; backup.mkdir(mode=0o700)
            def patch(name, **kwargs): return stack.enter_context(mock.patch.object(recovery, name, **kwargs))
            patch('inspect_installation', return_value=(ACCOUNT, Path('/java/bin/java'), '/usr/sbin/runuser'))
            patch('service_state', return_value=STATE)
            state = {'bootstrap': provision, 'admins': [] if provision else [{'name': 'Fixture Admin', 'email': 'admin@example.com'}]}
            patch('database_state', return_value=state)
            stop = patch('stop_and_verify', side_effect=recovery.RecoveryError('parada não confirmada') if failure == 'stop' else None)
            patch('backup_installation', side_effect=recovery.RecoveryError('backup não confirmado') if failure == 'backup' else None, return_value=backup)
            patch('fix_sidecars', side_effect=recovery.RecoveryError('sidecar falhou') if sidecar_failure else None)
            apply = patch('apply_access', side_effect=RuntimeError(TEMP_PASSWORD) if failure == 'cli' else None, return_value={'expiresAt': '2026-10-14T14:00:00Z'})
            restart = patch('restart_and_verify', side_effect=recovery.RecoveryError('reinício falhou') if restart_failure else None,
                            return_value={'status': 'ok', 'storage': 'sqlite', 'version': '1.5.6'})
            stack.enter_context(mock.patch.object(recovery.secrets, 'token_urlsafe', return_value=TEMP_PASSWORD))
            answers = iter(['Fixture Admin', 'admin@example.com'] if provision else ['admin@example.com'])
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                if failure or restart_failure or sidecar_failure:
                    with self.assertRaises(recovery.RecoveryError) as error:
                        recovery.recover(lambda _: next(answers))
                    self.assertNotIn(TEMP_PASSWORD, str(error.exception))
                    self.assertNotIn(TEMP_PASSWORD, output.getvalue())
                else:
                    result = recovery.recover(lambda _: next(answers))
                    self.assertEqual('applied', result['status']); self.assertTrue(result['serviceRestarted'])
                    self.assertIn(TEMP_PASSWORD, output.getvalue())
                    self.assertEqual('provision' if provision else 'recover', apply.call_args.args[3]['mode'])
                    self.assertEqual(0o600, stat.S_IMODE((backup / 'acesso-temporario.txt').stat().st_mode))
                    self.assertNotIn(TEMP_PASSWORD, (backup / 'resultado.json').read_text())
            stop.assert_called_once(); restart.assert_called_once()
            if failure in ('stop', 'backup') or sidecar_failure:
                apply.assert_not_called()

    def test_provision_first_account(self): self.workflow(provision=True)
    def test_recover_existing_admin(self): self.workflow()
    def test_cli_failure_still_restarts_and_redacts(self): self.workflow(failure='cli')
    def test_backup_failure_still_restarts_without_mutation(self): self.workflow(failure='backup')
    def test_stop_failure_still_attempts_restart_without_mutation(self): self.workflow(failure='stop')
    def test_sidecar_failure_does_not_skip_restart(self): self.workflow(sidecar_failure=True)
    def test_restart_failure_keeps_private_recovery_record(self): self.workflow(restart_failure=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
