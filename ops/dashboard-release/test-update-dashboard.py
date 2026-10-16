#!/usr/bin/env python3
"""Local stop-verification regression checks; never contacts a service or host."""
import importlib.util
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    'dashboard_update', Path(__file__).with_name('update-dashboard.py'))
update = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(update)

GROUP = '/system.slice/java-assessment-dashboard.service'


def snapshot(**changes):
    state = {
        'LoadState': 'loaded', 'ActiveState': 'inactive', 'SubState': 'dead',
        'MainPID': '0', 'ControlPID': '0', 'ControlGroup': GROUP,
        'Result': 'success', 'ExecMainCode': '1', 'ExecMainStatus': '0',
    }
    state.update(changes)
    return state


class FakeClock:
    def __init__(self):
        self.now = 0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class ServiceStopTests(unittest.TestCase):
    def setUp(self):
        # Fail closed if these tests accidentally reach any real subprocess.
        for name in ('run', 'check_output', 'Popen'):
            patcher = mock.patch.object(
                update.subprocess, name,
                side_effect=AssertionError('Real subprocess prohibited in regression tests'))
            patcher.start()
            self.addCleanup(patcher.stop)
        self.before = snapshot(ActiveState='active', SubState='running', MainPID='127')

    def wait(self, states, empty=True):
        clock = FakeClock()
        if isinstance(states, dict):
            state_mock = mock.Mock(return_value=states)
        else:
            state_mock = mock.Mock(side_effect=states)
        with mock.patch.object(update, 'service_snapshot', state_mock), \
             mock.patch.object(update, 'cgroup_empty', return_value=empty) as cgroup, \
             mock.patch.object(update.time, 'monotonic', clock.monotonic), \
             mock.patch.object(update.time, 'sleep', clock.sleep):
            result = update.wait_service_stopped(self.before, timeout=1)
        return result, cgroup

    def test_inactive_dead_without_members_is_stopped(self):
        expected = snapshot()
        actual, cgroup = self.wait(expected)
        self.assertEqual(actual, expected)
        cgroup.assert_called_with(GROUP)

    def test_failed_without_processes_is_stopped_and_result_is_retained(self):
        expected = snapshot(ActiveState='failed', SubState='failed',
                            Result='exit-code', ExecMainStatus='143')
        actual, cgroup = self.wait(expected)
        self.assertEqual(actual, expected)
        self.assertEqual(actual['ExecMainStatus'], '143')
        cgroup.assert_called_with(GROUP)

    def test_main_process_prevents_file_replacement(self):
        with self.assertRaises(RuntimeError):
            self.wait(snapshot(MainPID='127'))

    def test_control_process_prevents_file_replacement(self):
        with self.assertRaises(RuntimeError):
            self.wait(snapshot(ControlPID='128'))

    def test_remaining_cgroup_member_prevents_file_replacement(self):
        with self.assertRaises(RuntimeError):
            self.wait(snapshot(), empty=False)

    def test_waits_for_deactivating_service_to_finish(self):
        final = snapshot()
        actual, cgroup = self.wait([
            snapshot(ActiveState='deactivating', SubState='stop-sigterm', MainPID='127'),
            final,
        ])
        self.assertEqual(actual, final)
        cgroup.assert_called_with(GROUP)

    def test_changed_cgroup_is_rejected(self):
        with self.assertRaises(RuntimeError):
            self.wait(snapshot(ControlGroup='/system.slice/another.service'))

    def test_cleared_control_group_still_checks_original_group(self):
        expected = snapshot(ControlGroup='')
        actual, cgroup = self.wait(expected)
        self.assertEqual(actual, expected)
        cgroup.assert_called_with(GROUP)

    def test_unknown_original_cgroup_is_rejected(self):
        self.before['ControlGroup'] = ''
        with self.assertRaises(RuntimeError):
            self.wait(snapshot(ControlGroup=''))

    def test_stop_command_failure_keeps_diagnostics(self):
        report = {}
        result = types.SimpleNamespace(returncode=1, stdout='', stderr='Stop request diagnostic')
        with mock.patch.object(update, 'service_snapshot', return_value=self.before), \
             mock.patch.object(update.subprocess, 'run', return_value=result), \
             mock.patch.object(update, 'wait_service_stopped',
                               side_effect=RuntimeError('Service still has processes')):
            with self.assertRaises(RuntimeError):
                update.stop_service(report, 'stop')
        self.assertIn('stop', report)
        detail = report['stop']
        self.assertEqual(detail['before'], self.before)
        self.assertEqual(detail['commandReturnCode'], 1)
        self.assertIn('Stop request diagnostic', detail['commandOutput'])
        self.assertIn('Stop request diagnostic', detail['error'])

    def test_stop_verification_failure_keeps_diagnostics(self):
        report = {}
        result = types.SimpleNamespace(returncode=0, stdout='', stderr='')
        with mock.patch.object(update, 'service_snapshot', return_value=self.before), \
             mock.patch.object(update.subprocess, 'run', return_value=result), \
             mock.patch.object(update, 'wait_service_stopped',
                               side_effect=RuntimeError('Service still has processes')):
            with self.assertRaises(RuntimeError):
                update.stop_service(report, 'stop')
        detail = report['stop']
        self.assertEqual(detail['before'], self.before)
        self.assertEqual(detail['commandReturnCode'], 0)
        self.assertIn('Service still has processes', detail['error'])

    def test_rollback_stop_checks_original_cgroup_if_current_group_was_cleared(self):
        report = {'stop': {'before': self.before}}
        cleared = snapshot(ControlGroup='')
        result = types.SimpleNamespace(returncode=0, stdout='', stderr='')
        with mock.patch.object(update, 'service_snapshot', return_value=cleared), \
             mock.patch.object(update.subprocess, 'run', return_value=result), \
             mock.patch.object(update, 'wait_service_stopped', return_value=cleared) as wait:
            update.stop_service(report, 'rollbackStop')
        self.assertEqual(wait.call_args.args[0]['ControlGroup'], GROUP)
        self.assertEqual(report['rollbackStop']['before']['ControlGroup'], '')
        self.assertEqual(report['rollbackStop']['verified'], cleared)


class CgroupFilesystemTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='dashboard-cgroup-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        patcher = mock.patch.object(update, 'CGROUP_ROOT', self.root)
        patcher.start()
        self.addCleanup(patcher.stop)

    def v2(self):
        (self.root / 'cgroup.controllers').write_text('cpu memory pids\n')
        return self.root / GROUP.lstrip('/')

    def test_v2_empty_cgroup_is_safe(self):
        group = self.v2()
        group.mkdir(parents=True)
        (group / 'cgroup.events').write_text('populated 0\nfrozen 0\n')
        self.assertTrue(update.cgroup_empty(GROUP))

    def test_v2_populated_cgroup_is_unsafe(self):
        group = self.v2()
        group.mkdir(parents=True)
        (group / 'cgroup.events').write_text('populated 1\nfrozen 0\n')
        self.assertFalse(update.cgroup_empty(GROUP))

    def test_v2_removed_cgroup_is_safe(self):
        self.v2()
        self.assertTrue(update.cgroup_empty(GROUP))

    def test_v2_existing_cgroup_without_events_is_rejected(self):
        self.v2().mkdir(parents=True)
        with self.assertRaises(RuntimeError):
            update.cgroup_empty(GROUP)

    def test_v1_descendant_process_is_detected(self):
        hierarchy = self.root / 'systemd'
        hierarchy.mkdir()
        (hierarchy / 'cgroup.procs').write_text('1\n')
        group = hierarchy / GROUP.lstrip('/')
        child = group / 'child'
        child.mkdir(parents=True)
        (group / 'cgroup.procs').write_text('')
        (child / 'cgroup.procs').write_text('999\n')
        self.assertFalse(update.cgroup_empty(GROUP))
        (child / 'cgroup.procs').write_text('')
        self.assertTrue(update.cgroup_empty(GROUP))

    def test_parent_cgroup_is_rejected(self):
        self.v2()
        for group in ('/', '/system.slice', '/system.slice/../' + update.UNIT):
            with self.subTest(group=group), self.assertRaises(RuntimeError):
                update.cgroup_empty(group)


class ReleaseVersionTests(unittest.TestCase):
    def test_selects_exact_known_baseline(self):
        first = {'label': 'CSV', 'version': '1.0-beta', 'hashes': {'web/app.js': 'before'}}
        second = {'label': 'Runtime', 'version': '1.0-beta', 'hashes': {'web/app.js': 'runtime'}}
        self.assertEqual(update.select_baseline({'web/app.js': 'runtime'}, [first, second]), second)

    def test_modified_or_extra_file_is_not_a_known_baseline(self):
        candidate = {'label': 'known', 'version': '1.0-beta', 'hashes': {'web/app.js': 'before'}}
        for actual in ({'web/app.js': 'modified'}, {'web/app.js': 'before', 'web/extra.js': 'extra'}):
            with self.subTest(actual=actual), self.assertRaises(RuntimeError):
                update.select_baseline(actual, [candidate])

    def test_health_requires_exact_dashboard_release(self):
        import json
        payload = json.dumps({'status': 'ok', 'storage': 'sqlite', 'version': '1.0.2-beta'}).encode()
        with mock.patch.object(update, 'get', return_value=payload), mock.patch.object(update.time, 'sleep'):
            self.assertEqual(update.check_health('1.0.2-beta')['version'], '1.0.2-beta')
            with self.assertRaisesRegex(RuntimeError, 'version mismatch'):
                update.check_health('1.0.1-beta')

    def test_old_generic_beta_cannot_confirm_new_release(self):
        import json
        payload = json.dumps({'status': 'ok', 'storage': 'sqlite', 'version': '1.0-beta'}).encode()
        with mock.patch.object(update, 'get', return_value=payload), mock.patch.object(update.time, 'sleep'):
            self.assertEqual(update.check_health('1.0-beta')['version'], '1.0-beta')
            with self.assertRaisesRegex(RuntimeError, 'version mismatch'):
                update.check_health('1.0.1-beta')

    def test_version_is_one_of_atomic_backup_and_rollback_components(self):
        self.assertIn('VERSION', update.COMPONENTS)


class InstallAccessBackupTests(unittest.TestCase):
    def test_backup_key_and_reject_links_or_unsafe_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); data = root / 'data'; data.mkdir()
            backup = root / 'backup'; backup.mkdir()
            self.assertFalse(update.backup_install_access_key(data, backup))
            key = data / '.environment-install.key'; key.write_bytes(b'k' * 32); key.chmod(0o600)
            self.assertTrue(update.backup_install_access_key(data, backup))
            self.assertEqual((backup / key.name).read_bytes(), key.read_bytes())
            self.assertEqual((backup / key.name).stat().st_mode & 0o777, 0o600)
            (backup / key.name).unlink(); key.chmod(0o644)
            with self.assertRaisesRegex(RuntimeError, 'Unsafe'):
                update.backup_install_access_key(data, backup)
            key.unlink(); key.symlink_to(root / 'absent')
            with self.assertRaises(OSError):
                update.backup_install_access_key(data, backup)


class ReleaseUpdateFixtureTests(unittest.TestCase):
    """Exercise replacement/rollback in a temp tree, with every OS/network operation mocked."""
    def run_fixture(self, fail_version=False, unknown_baseline=False, activated=False, previous_accounts=False,
                    stopped_delta=0, live_delta=0, fail_live_database=False, fail_asset=False, fail_file=False,
                    missing_live_table=False, existing_pin=False, changed_pin=False, updates_after_start=False):
        import contextlib
        import io
        import json
        with tempfile.TemporaryDirectory(prefix='dashboard-update-fixture-') as temporary:
            root = Path(temporary)
            base, backups, units = root / 'dashboard', root / 'backups', root / 'units'
            backups.mkdir(); units.mkdir()
            for name in update.COMPONENTS:
                p = base / name
                if name in ('VERSION', 'dist/assessment-collector.zip'):
                    p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(b'1.0-beta\n' if name == 'VERSION' else b'old collector')
                else:
                    p.mkdir(parents=True, exist_ok=True)
            for name, data in {'web/index.html': b'old web', 'server/AssessmentServer.java': b'old server', 'shared/Json.java': b'old json',
                               'classes/assessment/AssessmentServer.class': b'old class', 'conf/agent-ca.pem': b'fixture public CA',
                               'run-server.sh': b'protected launcher', 'database.sh': b'protected database launcher'}.items():
                p = base / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)
            (base / 'dist/historical-release.bin').write_bytes(b'preserved previous catalog')
            if existing_pin or changed_pin:
                (base / 'dist/agent-update-public.der').write_bytes(b'other public key' if changed_pin else b'fixture public update key')
            (units / update.UNIT).write_bytes(b'protected service')
            if previous_accounts:
                for name in ('UserAuth.java', 'UserAdminTool.java'):
                    (base / 'server' / name).write_bytes(b'old account source')
            hashes = {str(p.relative_to(base)): update.sha(p.read_bytes())
                      for d in ('server', 'shared', 'web', 'lib') for p in (base / d).rglob('*') if p.is_file()}
            if unknown_baseline:
                hashes['web/index.html'] = 'unknown'
            candidates = [{'label': 'fixture baseline', 'version': '1.0-beta', 'hashes': hashes}]
            source = {'server/AssessmentServer.java': b'new server', 'shared/Json.java': b'new json',
                      'server/UserAuth.java': b'new accounts', 'server/UserAdminTool.java': b'new account tool',
                      'expected-baselines.json': json.dumps(candidates).encode()}
            runtime = {'classes/assessment/AssessmentServer.class': b'new class', 'dist/assessment-collector.zip': b'new collector',
                       'dist/agent-update-public.der': b'fixture public update key',
                       'dist/agent-updates/1.0.1-beta/manifest.json': b'fixture signed metadata',
                       'VERSION': b'1.0.1-beta\n', **{'web/' + name: ('new ' + name).encode() for name in ('index.html', 'app.js', 'styles.css', 'version.js')}}
            events = []
            running = True
            updates_seen = False
            failure_expected = fail_version or stopped_delta != 0 or fail_live_database or fail_asset or fail_file or missing_live_table
            def path(value):
                return backups if value == '/root' else units if value == '/etc/systemd/system' else Path(value)
            def command(*args):
                nonlocal running, updates_seen
                if args == ('hostname',): return 'dashboard'
                if args == ('systemctl', 'is-active', update.UNIT): return 'active'
                if args[:2] == ('systemctl', 'show'): return 'java-assessment-dashboard'
                if len(args)>1 and args[1]=='backup': return 'BACKUP_OK'
                if args == ('systemctl', 'start', update.UNIT):
                    running = True
                    version = (base / 'VERSION').read_text().strip()
                    events.append('start:' + version)
                    if version == '1.0.1-beta' and updates_after_start: updates_seen = True
                    if fail_file and version == '1.0.1-beta':
                        (base / 'web/app.js').write_bytes(b'changed deployed file')
                return ''
            def stop(*args):
                nonlocal running
                running = False
                events.append('stop')
            def database(reject_agent_updates=True):
                if updates_seen and reject_agent_updates:
                    raise RuntimeError("Agent updates still pending or unconfirmed; dashboard update refused")
                version = (base / 'VERSION').read_text().strip()
                events.append('database:' + version + (':running' if running else ':stopped'))
                delta = 0
                if version == '1.0.1-beta':
                    if running and fail_live_database:
                        raise RuntimeError('Database integrity failed')
                    delta = live_delta if running else stopped_delta
                counts = {'hosts': 1 + delta, 'inventory': 1 + delta, 'uninstall_requests': 4 + delta, 'agent_update_requests': 1 if updates_seen else 0}
                if running and version == '1.0.1-beta' and missing_live_table:
                    del counts['inventory']
                return counts
            def health(expected=None):
                current = (base / 'VERSION').read_text().strip()
                if fail_version and current == '1.0.1-beta':
                    raise RuntimeError('Dashboard version mismatch: expected 1.0.1-beta, got 1.0-beta')
                if expected is not None and current != expected:
                    raise AssertionError('Incorrect health expectation')
                return {'status': 'ok', 'storage': 'sqlite', 'version': current}
            account = types.SimpleNamespace(pw_uid=999, pw_gid=999)
            real_mkdtemp = tempfile.mkdtemp
            real_stat = Path.stat
            def fixture_stat(file, *args, **kwargs):
                result = real_stat(file, *args, **kwargs)
                if file == base:
                    values = list(result); values[4] = 0
                    return update.os.stat_result(values)
                return result
            with contextlib.ExitStack() as stack:
                def patch(target, name, **kwargs): return stack.enter_context(mock.patch.object(target, name, **kwargs))
                patch(update, 'BASE', new=base); patch(update, 'DASHBOARD_VERSION', new='1.0.1-beta'); patch(update, 'Path', side_effect=path)
                patch(update.os, 'geteuid', return_value=0); patch(update.os, 'umask', return_value=0); patch(update.os, 'chown', return_value=None)
                patch(Path, 'stat', autospec=True, side_effect=fixture_stat)
                patch(update.pwd, 'getpwnam', return_value=account); patch(update, 'command', side_effect=command)
                patch(update, 'read_zip', side_effect=[dict(runtime), dict(source)])
                patch(update, 'database', side_effect=database)
                patch(update, 'human_auth_activated', return_value=activated)
                patch(update, 'check_health', side_effect=health); patch(update, 'stop_service', side_effect=stop)
                patch(update, 'fix_sqlite_sidecars', side_effect=lambda account: events.append('sidecars')); patch(update, 'collector_with_ca', return_value=b'new collector')
                patch(update, 'collector_version', return_value='1.0.1-beta')
                patch(update.shutil, 'which', return_value=None); patch(update.tarfile, 'open', return_value=mock.MagicMock())
                patch(update.tempfile, 'mkdtemp', side_effect=lambda **kw: real_mkdtemp(prefix=kw['prefix'], dir=backups))
                patch(update, 'get', side_effect=lambda url: b'wrong served asset' if fail_asset and url == '/app.js' else (base / 'web' / url.lstrip('/')).read_bytes())
                patch(update.sys, 'argv', new=['fixture', 'runtime.zip', 'sources.zip'])
                for name in ('run', 'check_output', 'Popen'):
                    patch(update.subprocess, name, side_effect=AssertionError('Real subprocess prohibited'))
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    if failure_expected or unknown_baseline or changed_pin:
                        with self.assertRaises(RuntimeError): update.main()
                    else:
                        update.main()
                if unknown_baseline or changed_pin:
                    self.assertEqual(list(backups.iterdir()), [])
                    self.assertEqual((base / 'VERSION').read_text(), '1.0-beta\n')
                    return
                report = json.loads(output.getvalue())
                if failure_expected:
                    self.assertEqual(report['status'], 'failed')
                    if (activated and not previous_accounts) or updates_after_start:
                        self.assertTrue(report['rollbackBlocked'])
                        self.assertIn('Agent updates still pending' if updates_after_start else 'shared-password', report['rollbackReason'])
                        self.assertEqual((base / 'VERSION').read_text(), '1.0.1-beta\n')
                        self.assertEqual((base / 'server/AssessmentServer.java').read_bytes(), b'new server')
                        self.assertEqual((base / 'classes/assessment/AssessmentServer.class').read_bytes(), b'new class')
                        self.assertNotIn('rollbackHealth', report)
                    else:
                        self.assertEqual((base / 'VERSION').read_text(), '1.0-beta\n')
                        self.assertEqual((base / 'server/AssessmentServer.java').read_bytes(), b'old server')
                        self.assertEqual((base / 'classes/assessment/AssessmentServer.class').read_bytes(), b'old class')
                        self.assertEqual(report['rollbackHealth']['version'], '1.0-beta')
                    if stopped_delta:
                        self.assertIn('while service was stopped', report['error'])
                        self.assertFalse(report['databasePreservedBeforeStart'])
                        self.assertNotIn('start:1.0.1-beta', events)
                        self.assertEqual(report['stoppedCounts']['hosts'], 1 + stopped_delta)
                    elif fail_live_database:
                        self.assertIn('Database integrity failed', report['error'])
                    elif fail_asset:
                        self.assertIn('Served asset mismatch', report['error'])
                    elif fail_file:
                        self.assertIn('Deployed file mismatch', report['error'])
                    elif missing_live_table:
                        self.assertIn('Database tables missing', report['error'])
                else:
                    self.assertEqual(report['status'], 'deployed')
                    self.assertEqual(report['beforeCounts']['uninstall_requests'], 4)
                    self.assertEqual(report['afterCounts']['uninstall_requests'], 4 + live_delta)
                    self.assertEqual(report['afterCounts']['hosts'], 1 + live_delta)
                    self.assertTrue(report['databasePreservedBeforeStart'])
                    self.assertEqual(report['stoppedCounts'], report['beforeCounts'])
                    self.assertEqual(report['health']['version'], '1.0.1-beta')
                    self.assertEqual(report['collectorVersion'], report['dashboardVersion'])
                    self.assertEqual((base / 'VERSION').read_text(), '1.0.1-beta\n')
                    self.assertEqual((Path(report['backup']) / 'previous-components/VERSION').read_text(), '1.0-beta\n')
                    stopped_check = events.index('database:1.0.1-beta:stopped')
                    self.assertEqual(events[stopped_check + 1:stopped_check + 3], ['sidecars', 'start:1.0.1-beta'])
                    self.assertGreater(events.index('database:1.0.1-beta:running'), events.index('start:1.0.1-beta'))
                self.assertEqual((base / 'conf/agent-ca.pem').read_bytes(), b'fixture public CA')
                if not failure_expected:
                    self.assertEqual((base / 'dist/agent-update-public.der').read_bytes(), b'fixture public update key')
                    self.assertEqual((base / 'dist/agent-updates/1.0.1-beta/manifest.json').read_bytes(), b'fixture signed metadata')
                elif (not activated or previous_accounts) and not updates_after_start:
                    if existing_pin:
                        self.assertEqual((base / 'dist/agent-update-public.der').read_bytes(), b'fixture public update key')
                    else:
                        self.assertFalse((base / 'dist/agent-update-public.der').exists(), 'first-time update trust rolls back with dist')
                self.assertEqual((base / 'dist/historical-release.bin').read_bytes(), b'preserved previous catalog')

    def test_success_updates_version_and_retains_previous_version_in_backup(self):
        self.run_fixture()

    def test_new_request_after_start_does_not_undo_successful_publication(self):
        self.run_fixture(updates_after_start=True)

    def test_post_start_failure_cannot_remove_catalog_already_authorizing_an_update(self):
        self.run_fixture(updates_after_start=True, fail_asset=True)

    def test_same_signing_pin_and_historical_catalog_are_preserved(self):
        self.run_fixture(existing_pin=True)

    def test_existing_pin_is_retained_after_rollback(self):
        self.run_fixture(existing_pin=True, fail_version=True)

    def test_changed_signing_pin_is_refused_before_service_stop(self):
        self.run_fixture(changed_pin=True)

    def test_wrong_served_version_rolls_back_version_source_and_classes(self):
        self.run_fixture(fail_version=True)

    def test_unknown_baseline_refused_before_backup_or_stop(self):
        self.run_fixture(unknown_baseline=True)

    def test_completed_bootstrap_refuses_shared_password_rollback_and_keeps_new_code(self):
        self.run_fixture(fail_version=True, activated=True)

    def test_incomplete_bootstrap_allows_previous_code_when_no_account_activation(self):
        self.run_fixture(fail_version=True, activated=False)

    def test_accounts_can_roll_back_to_another_account_capable_release(self):
        self.run_fixture(fail_version=True, activated=True, previous_accounts=True)

    def test_stopped_database_decrease_refuses_start_and_rolls_back_files(self):
        self.run_fixture(stopped_delta=-1)

    def test_stopped_database_increase_refuses_start_and_rolls_back_files(self):
        self.run_fixture(stopped_delta=1)

    def test_legitimate_deletion_after_start_does_not_trigger_rollback(self):
        self.run_fixture(live_delta=-1)

    def test_live_integrity_failure_still_rejects_deployment(self):
        self.run_fixture(fail_live_database=True)

    def test_wrong_served_asset_still_rejects_deployment(self):
        self.run_fixture(fail_asset=True)

    def test_changed_deployed_file_still_rejects_deployment(self):
        self.run_fixture(fail_file=True)

    def test_missing_table_after_start_still_rejects_deployment(self):
        self.run_fixture(missing_live_table=True)


class HumanAuthRollbackDatabaseTests(unittest.TestCase):
    def setUp(self):
        import sqlite3
        folder = tempfile.TemporaryDirectory(prefix='human-auth-rollback-')
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name); (self.root / 'data').mkdir()
        self.file = self.root / 'data/inventory.sqlite'
        with sqlite3.connect(self.file) as con:
            con.execute('CREATE TABLE metadata(key TEXT PRIMARY KEY,payload TEXT NOT NULL)')
        patch = mock.patch.object(update, 'BASE', self.root); patch.start(); self.addCleanup(patch.stop)

    def write(self, statement, values=()):
        import sqlite3
        with sqlite3.connect(self.file) as con:
            con.execute(statement, values)

    def test_old_database_and_empty_accounts_have_no_activation(self):
        self.assertFalse(update.human_auth_activated())
        self.write('CREATE TABLE human_users(id TEXT PRIMARY KEY)')
        self.assertFalse(update.human_auth_activated())
        update.require_safe_auth_rollback(False)

    def test_permanent_marker_prohibits_rollback_even_if_users_absent(self):
        self.write('INSERT INTO metadata VALUES(?,?)', ('humanAuthActivated', 'true'))
        before = self.file.read_bytes()
        with self.assertRaisesRegex(RuntimeError, 'shared-password'):
            update.require_safe_auth_rollback(False)
        self.assertEqual(before, self.file.read_bytes())
        update.require_safe_auth_rollback(True)

    def test_existing_accounts_prohibit_rollback_even_without_marker(self):
        self.write('CREATE TABLE human_users(id TEXT PRIMARY KEY)')
        self.write('INSERT INTO human_users VALUES(?)', ('fixture-user',))
        with self.assertRaisesRegex(RuntimeError, 'shared-password'):
            update.require_safe_auth_rollback(False)

    def test_unknown_activation_marker_fails_closed(self):
        for value in ('null', '"true"', '{}', 'not-json'):
            self.write('INSERT OR REPLACE INTO metadata VALUES(?,?)', ('humanAuthActivated', value))
            with self.subTest(value=value), self.assertRaises((RuntimeError, ValueError)):
                update.require_safe_auth_rollback(False)

    def test_missing_database_refuses_rollback_instead_of_assuming_unactivated(self):
        self.file.unlink()
        with self.assertRaisesRegex(RuntimeError, 'rollback safety'):
            update.require_safe_auth_rollback(False)


class UninstallHistoryDatabaseTests(unittest.TestCase):
    """Actual SQLite with historical records; only read-only access is allowed to the updater."""
    def setUp(self):
        import sqlite3
        temporary = tempfile.TemporaryDirectory(prefix='dashboard-uninstall-history-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'data').mkdir()
        self.file = self.root / 'data/inventory.sqlite'
        with sqlite3.connect(self.file) as con:
            con.execute('CREATE TABLE uninstall_requests(id TEXT PRIMARY KEY, status TEXT NOT NULL, payload TEXT NOT NULL)')
            con.execute('CREATE TABLE hosts(id TEXT PRIMARY KEY)')
            con.execute("INSERT INTO hosts VALUES('fixture-host')")
        patcher = mock.patch.object(update, 'BASE', self.root)
        patcher.start(); self.addCleanup(patcher.stop)

    def insert(self, status):
        import sqlite3
        with sqlite3.connect(self.file) as con:
            con.execute('INSERT INTO uninstall_requests VALUES(?,?,?)', (status, status, '{"audit":"fixture preserved"}'))

    def test_empty_history_is_allowed(self):
        self.assertEqual(update.database()['uninstall_requests'], 0)

    def test_each_terminal_status_is_allowed_and_preserved(self):
        import sqlite3
        for index, status in enumerate(('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'), 1):
            self.insert(status)
            before = self.file.read_bytes()
            self.assertEqual(update.database()['uninstall_requests'], index)
            self.assertEqual(self.file.read_bytes(), before, 'Updater must not write or delete audit data')
        with sqlite3.connect(self.file) as con:
            self.assertEqual(con.execute('SELECT count(*) FROM uninstall_requests WHERE payload=?', ('{"audit":"fixture preserved"}',)).fetchone()[0], 4)

    def test_each_busy_status_blocks_with_status_and_count_only(self):
        import sqlite3
        self.insert('SUCCEEDED')
        for status in ('WAITING', 'UNINSTALLING', 'UNCONFIRMED'):
            self.insert(status)
            before = self.file.read_bytes()
            with self.assertRaisesRegex(RuntimeError, status): update.database()
            self.assertEqual(self.file.read_bytes(), before)
            with sqlite3.connect(self.file) as con: con.execute('DELETE FROM uninstall_requests WHERE id=?', (status,))

    def test_unknown_status_blocks(self):
        self.insert('UNKNOWN_FIXTURE_STATUS')
        with self.assertRaisesRegex(RuntimeError, 'Unknown uninstall request status'): update.database()

    def test_a_new_pending_request_is_detected_by_recheck(self):
        self.insert('EXPIRED')
        self.assertEqual(update.database()['uninstall_requests'], 1)
        self.insert('WAITING')
        with self.assertRaisesRegex(RuntimeError, 'WAITING'): update.database()

    def test_schema_without_uninstall_table_is_allowed(self):
        import sqlite3
        with sqlite3.connect(self.file) as con: con.execute('DROP TABLE uninstall_requests')
        self.assertEqual(update.database(), {'hosts': 1})


class UnifiedAgentPackageTests(unittest.TestCase):
    def package(self, metadata, binary):
        import io
        import json
        import zipfile
        inner, outer = io.BytesIO(), io.BytesIO()
        with zipfile.ZipFile(inner, 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nImplementation-Version: ' + binary + '\n\n')
        with zipfile.ZipFile(outer, 'w') as archive:
            archive.writestr('collector.jar', inner.getvalue())
            archive.writestr('DEPENDENCIES.json', json.dumps({'version': metadata}))
        return outer.getvalue()

    def test_agent_metadata_and_binary_match_dashboard_release(self):
        with mock.patch.object(update, 'DASHBOARD_VERSION', '1.5.2'):
            self.assertEqual(update.collector_version(self.package('1.5.2', '1.5.2')), '1.5.2')

    def test_old_agent_metadata_is_rejected(self):
        with mock.patch.object(update, 'DASHBOARD_VERSION', '1.5.2'), self.assertRaisesRegex(RuntimeError, 'Agent ZIP version'):
            update.collector_version(self.package('1.5.1', '1.5.1'))

    def test_old_binary_with_changed_metadata_is_rejected(self):
        with mock.patch.object(update, 'DASHBOARD_VERSION', '1.5.2'), self.assertRaisesRegex(RuntimeError, 'Agent JAR version'):
            update.collector_version(self.package('1.5.2', '1.5.1'))


class AgentUpdateDatabaseTests(unittest.TestCase):
    def setUp(self):
        import sqlite3
        temporary = tempfile.TemporaryDirectory(prefix='dashboard-agent-update-history-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name); (self.root / 'data').mkdir()
        self.file = self.root / 'data/inventory.sqlite'
        with sqlite3.connect(self.file) as con:
            con.execute('CREATE TABLE hosts(id TEXT PRIMARY KEY)')
            con.execute('CREATE TABLE agent_update_requests(id TEXT PRIMARY KEY,status TEXT NOT NULL,payload TEXT NOT NULL)')
        patcher = mock.patch.object(update, 'BASE', self.root); patcher.start(); self.addCleanup(patcher.stop)

    def write(self, status):
        import sqlite3
        with sqlite3.connect(self.file) as con:
            con.execute('DELETE FROM agent_update_requests')
            con.execute('INSERT INTO agent_update_requests VALUES(?,?,?)', ('fixture', status, '{"history":"preserved"}'))

    def test_every_pending_agent_update_blocks_dashboard_publication(self):
        for status in ('WAITING', 'DOWNLOADING', 'INSTALLING', 'VERIFYING', 'UNCONFIRMED'):
            with self.subTest(status=status):
                self.write(status); before = self.file.read_bytes()
                with self.assertRaisesRegex(RuntimeError, status): update.database()
                self.assertEqual(self.file.read_bytes(), before)

    def test_terminal_agent_updates_allow_publication_without_history_changes(self):
        for status in ('SUCCEEDED', 'FAILED', 'ROLLED_BACK', 'CANCELLED', 'EXPIRED'):
            with self.subTest(status=status):
                self.write(status); before = self.file.read_bytes()
                self.assertEqual(update.database()['agent_update_requests'], 1)
                self.assertEqual(self.file.read_bytes(), before)

    def test_unknown_agent_update_state_fails_closed(self):
        self.write('UNKNOWN')
        with self.assertRaisesRegex(RuntimeError, 'Unknown agent update status'): update.database()


class FullAgentOperationDatabaseTests(AgentUpdateDatabaseTests):
    def setUp(self):
        super().setUp()
        import sqlite3
        with sqlite3.connect(self.file) as con:
            con.execute('CREATE TABLE agent_update_operations(id TEXT PRIMARY KEY,status TEXT NOT NULL,payload TEXT NOT NULL)')

    def test_full_parent_blocks_even_without_legacy_child(self):
        import sqlite3
        for status in ('WAITING', 'DOWNLOADING', 'INSTALLING', 'VERIFYING', 'UNCONFIRMED'):
            with self.subTest(status=status):
                with sqlite3.connect(self.file) as con:
                    con.execute('DELETE FROM agent_update_operations')
                    con.execute('INSERT INTO agent_update_operations VALUES(?,?,?)', ('parent', status, '{"type":"full"}'))
                before = self.file.read_bytes()
                with self.assertRaisesRegex(RuntimeError, 'Full agent updates'): update.database()
                self.assertEqual(before, self.file.read_bytes())

    def test_final_parent_is_read_only_and_unknown_fails_closed(self):
        import sqlite3
        for status in ('SUCCEEDED', 'FAILED', 'ROLLED_BACK', 'CANCELLED', 'EXPIRED', 'UNKNOWN'):
            with self.subTest(status=status):
                with sqlite3.connect(self.file) as con:
                    con.execute('DELETE FROM agent_update_operations')
                    con.execute('INSERT INTO agent_update_operations VALUES(?,?,?)', ('parent', status, '{"history":"preserved"}'))
                before = self.file.read_bytes()
                if status == 'UNKNOWN':
                    with self.assertRaisesRegex(RuntimeError, 'Unknown full agent update state'): update.database()
                else: self.assertEqual(update.database()['agent_update_operations'], 1)
                self.assertEqual(before, self.file.read_bytes())


if __name__ == '__main__':
    unittest.main(verbosity=2)
