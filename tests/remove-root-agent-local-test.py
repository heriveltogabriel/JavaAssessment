"""No shell or VM: redirect fixed paths to temporary fixtures and mock subprocess."""
from contextlib import ExitStack, redirect_stdout
import importlib.util
import io
import os
import fcntl
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'ops/remover-agente-antigo.py'
spec = importlib.util.spec_from_file_location('local_agent_removal', SOURCE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class RemovalTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.base = Path(self.stack.enter_context(tempfile.TemporaryDirectory())).resolve()
        for constant, relative in {'COLLECTOR': 'opt/java-assessment-collector',
                                   'HELPER': 'opt/java-assessment-uninstall',
                                   'UNIT_DIR': 'etc/systemd/system', 'BACKUP_PARENT': 'root',
                                   'PROC': 'proc', 'CGROUP': 'cgroup', 'SYSTEMD_RUN': 'run/systemd/system'}.items():
            value = self.base / relative
            value.mkdir(parents=True, exist_ok=True)
            self.stack.enter_context(patch.object(m, constant, value))
        self.stack.enter_context(patch.object(m, 'CRON_DIRS', (self.base / 'cron.d',)))
        self.stack.enter_context(patch.object(m, 'CRONTAB', self.base / 'crontab'))
        self.stack.enter_context(patch.object(m, 'UPDATE_HELPER', self.base / 'opt/java-assessment-update'))
        self.stack.enter_context(patch.object(m, 'LIFECYCLE_LOCK', self.base / 'opt/java-assessment-lifecycle.lock'))
        (m.PROC / 'self').mkdir()
        (m.PROC / 'self/mountinfo').write_text('')
        for root, script in ((m.COLLECTOR, 'root-service.sh'), (m.HELPER, 'uninstall-service.sh')):
            (root / 'collector.jar').write_bytes(b'collector-fixture')
            (root / script).write_text('fixture')
        (m.COLLECTOR / 'conf').mkdir()
        (m.COLLECTOR / 'conf/collector.properties').write_text('agentToken=FIXTURE_SECRET\n')
        for unit in m.UNITS:
            if unit.endswith('.timer'):
                content = 'Unit=java-assessment-collector.service\n'
            else:
                command = str(m.HELPER) + '/uninstall-service.sh loop' if 'uninstall' in unit else str(m.COLLECTOR) + '/root-service.sh once'
                content = 'ExecStart=/bin/sh ' + command + '\n'
            (m.UNIT_DIR / unit).write_text(content)
        self.calls = []
        self.states = {unit: 'active' for unit in m.UNITS}
        self.pid = '0'
        self.dropin = ''
        self.cron = ''
        self.fail_stop = False
        self.lock_uid = 0
        self.stack.enter_context(patch.object(m.sys, 'platform', 'linux'))
        self.stack.enter_context(patch.object(m.os, 'geteuid', return_value=0))
        self.stack.enter_context(patch.object(m.os, 'umask'))
        self.stack.enter_context(patch.dict(os.environ))
        # Temporary fixture files belong to the test user; separate tests cover trust rejection.
        self.stack.enter_context(patch.object(m, 'trusted', side_effect=self.fixture_trust))
        original_lstat, original_fstat = Path.lstat, os.fstat

        def root_stat(value):
            fields = list(value)
            fields[4] = self.lock_uid
            return os.stat_result(fields)

        def fixture_lstat(path, *args, **kwargs):
            value = original_lstat(path, *args, **kwargs)
            return root_stat(value) if path == m.LIFECYCLE_LOCK else value

        self.stack.enter_context(patch.object(Path, 'lstat', fixture_lstat))
        self.stack.enter_context(patch.object(m.os, 'fstat', side_effect=lambda fd: root_stat(original_fstat(fd))))
        self.stack.enter_context(patch.object(m.subprocess, 'run', side_effect=self.command))

    def fixture_trust(self, path):
        m.require(not any(member.is_symlink() for member in (path, *path.parents)), 'Caminho sem protecao esperada')

    def command(self, args, **kwargs):
        self.assertIs(kwargs.get('close_fds'), True, 'Child commands must never inherit the lifecycle lock')
        args = tuple(args)
        self.calls.append(args)
        code, output, error = 0, '', ''
        if args[:2] == ('systemctl', 'show'):
            unit = args[2]
            exists = (m.UNIT_DIR / unit).exists()
            output = '\n'.join(['LoadState=' + ('loaded' if exists else 'not-found'),
                                'ActiveState=' + (self.states[unit] if exists else 'inactive'),
                                'MainPID=' + self.pid, 'ControlPID=0',
                                'ControlGroup=/test-' + unit,
                                'FragmentPath=' + (str(m.UNIT_DIR / unit) if exists else ''),
                                'DropInPaths=' + self.dropin, 'NeedDaemonReload=no'])
        elif args[:2] == ('systemctl', 'stop'):
            if self.fail_stop:
                code = 1
            else:
                self.states[args[2]] = 'inactive'
        elif args[:2] in (('systemctl', 'disable'), ('systemctl', 'daemon-reload')):
            pass
        elif args == ('getent', 'passwd'):
            output = 'opc:x:1000:1000::/home/opc:/bin/bash\n'
        elif args[:2] == ('crontab', '-u'):
            if self.cron:
                output = self.cron
            else:
                code, error = 1, 'no crontab for opc'
        else:
            raise AssertionError('Unexpected external command: ' + repr(args))
        return subprocess.CompletedProcess(args, code, output, error)

    def execute(self):
        output = io.StringIO()
        with redirect_stdout(output):
            m.main()
        return output.getvalue()

    def refuses(self, match):
        with self.assertRaisesRegex(RuntimeError, match):
            self.execute()
        self.assertTrue((m.COLLECTOR / 'collector.jar').exists())

    def test_removes_only_fixed_installation_and_keeps_private_backup(self):
        app = self.base / 'unrelated-java-application'
        app.write_text('preserve')
        output = self.execute()
        self.assertIn('AGENTE_REMOVIDO', output)
        self.assertNotIn('FIXTURE_SECRET', output)
        backup, = m.BACKUP_PARENT.glob('java-assessment-removido-*')
        self.assertEqual(backup.stat().st_mode & 0o777, 0o700)
        self.assertEqual((backup / m.COLLECTOR.name / 'conf/collector.properties').read_text(), 'agentToken=FIXTURE_SECRET\n')
        self.assertFalse(m.COLLECTOR.exists())
        self.assertFalse(m.HELPER.exists())
        self.assertEqual(app.read_text(), 'preserve')
        self.assertIn('AGENTE_JA_AUSENTE', self.execute())

    def test_incomplete_install_without_units_or_mode_marker(self):
        for unit in m.UNITS:
            (m.UNIT_DIR / unit).unlink()
        for path in m.HELPER.iterdir():
            path.unlink()
        m.HELPER.rmdir()
        self.assertIn('AGENTE_REMOVIDO', self.execute())

    def test_stop_failure_keeps_files(self):
        self.fail_stop = True
        self.refuses('Falha no comando')

    def test_live_pid_blocks_move(self):
        self.pid = '999'
        self.refuses('Servico ainda ativo')

    def test_failed_unit_without_pid_is_stopped(self):
        self.states[m.UNITS[0]] = 'failed'
        m.stopped(m.UNITS[0])

    def test_nested_cgroup_process_blocks_move(self):
        path = m.CGROUP / ('test-' + m.UNITS[0]) / 'child'
        path.mkdir(parents=True)
        (path / 'cgroup.procs').write_text('123\n')
        self.refuses('Processo residual')

    def test_relative_classpath_process_cwd_blocks_move(self):
        process = m.PROC / '999999'
        process.mkdir()
        (process / 'cmdline').write_bytes(b'java\0-cp\0collector.jar\0assessment.AssessmentCollector')
        (process / 'cwd').symlink_to(m.COLLECTOR)
        (process / 'exe').symlink_to(self.base / 'java')
        (process / 'fd').mkdir()
        self.refuses('Processo ainda usa')

    def test_unit_override_blocks_before_stop(self):
        self.dropin = '/etc/systemd/system/overridden.conf'
        self.refuses('Override')
        self.assertFalse(any(call[:2] == ('systemctl', 'stop') for call in self.calls))

    def test_unexpected_unit_command_blocks_before_stop(self):
        (m.UNIT_DIR / m.UNITS[-1]).write_text('ExecStart=/bin/sh /application/start.sh\n')
        self.refuses('Comandos de servico')

    def test_second_timer_target_blocks(self):
        (m.UNIT_DIR / m.UNITS[0]).write_text('Unit=java-assessment-collector.service\nUnit=other.service\n')
        self.refuses('Timer nao reconhecido')

    def test_cmdline_match_blocks_even_when_process_links_disappear(self):
        process = m.PROC / '999999'
        process.mkdir()
        (process / 'cmdline').write_bytes(('java\0-cp\0' + str(m.COLLECTOR / 'collector.jar')).encode())
        self.refuses('Processo ainda usa')

    def test_alternative_cron_blocks_before_stop(self):
        self.cron = '* * * * * /home/opc/java-assessment/poll.sh\n'
        self.refuses('Instalacao cron')

    def test_pending_install_or_uninstall_blocks(self):
        for path in (m.COLLECTOR / '.root-install-lock', m.HELPER / 'claim.json'):
            path.touch()
            self.refuses('Operacao pendente')
            path.unlink()

    def test_nested_mount_blocks(self):
        (m.PROC / 'self/mountinfo').write_text('10 1 1:1 / ' + str(m.COLLECTOR / 'data') + ' rw - ext4 none rw\n')
        self.refuses('Montagem dentro')

    def test_dashboard_cohabitation_blocks(self):
        (m.COLLECTOR / 'web').mkdir()
        self.refuses('Possivel dashboard')

    def test_symlink_unit_blocks(self):
        unit = m.UNIT_DIR / m.UNITS[0]
        original = self.base / 'unit'
        unit.rename(original)
        unit.symlink_to(original)
        self.refuses('Caminho sem protecao')

    def assert_before_stop(self):
        self.assertFalse(any(call[:2] in (('systemctl', 'stop'), ('systemctl', 'disable')) for call in self.calls))
        self.assertTrue(m.COLLECTOR.exists())
        self.assertFalse(list(m.BACKUP_PARENT.glob('java-assessment-removido-*')))

    def test_update_helper_directory_file_and_broken_symlink_refuse_before_stop(self):
        for kind in ('directory', 'file', 'symlink'):
            with self.subTest(kind=kind):
                if kind == 'directory':
                    m.UPDATE_HELPER.mkdir()
                elif kind == 'file':
                    m.UPDATE_HELPER.write_text('unknown updater')
                else:
                    m.UPDATE_HELPER.symlink_to(self.base / 'missing-updater')
                self.refuses('use a desinstalacao pelo dashboard')
                self.assert_before_stop()
                if kind == 'directory':
                    m.UPDATE_HELPER.rmdir()
                else:
                    m.UPDATE_HELPER.unlink()

    def test_pending_update_request_even_without_helper_refuses_before_stop(self):
        spool = m.COLLECTOR / 'collector-spool'
        spool.mkdir()
        request = spool / 'update-request.json'
        for broken in (False, True):
            with self.subTest(broken=broken):
                if broken:
                    request.symlink_to(self.base / 'missing-request')
                else:
                    request.write_text('{}')
                self.refuses('use a desinstalacao pelo dashboard')
                self.assert_before_stop()
                request.unlink()

    def test_nonblocking_shared_flock_refuses_competing_lifecycle_operation(self):
        with m.lifecycle_lock():
            self.refuses('Outra operacao do agente')
            self.assert_before_stop()
        self.assertIn('AGENTE_REMOVIDO', self.execute())

    def test_lock_stays_in_place_private_and_is_released_after_failure(self):
        self.fail_stop = True
        self.refuses('Falha no comando')
        inode = m.LIFECYCLE_LOCK.stat().st_ino
        self.assertEqual(m.LIFECYCLE_LOCK.stat().st_mode & 0o7777, 0o600)
        self.assertEqual(m.LIFECYCLE_LOCK.stat().st_nlink, 1)
        original = m.os.set_inheritable
        with patch.object(m.os, 'set_inheritable', wraps=original) as inherit:
            with m.lifecycle_lock():
                fd = inherit.call_args.args[0]
                self.assertFalse(os.get_inheritable(fd))
                self.assertEqual(m.LIFECYCLE_LOCK.stat().st_ino, inode)
        self.assertTrue(m.LIFECYCLE_LOCK.exists())

    def test_lock_mode_owner_hardlink_symlink_directory_fifo_refused(self):
        for kind in ('mode', 'owner', 'hardlink', 'symlink', 'directory', 'fifo'):
            with self.subTest(kind=kind):
                self.calls.clear()
                self.lock_uid = 1000 if kind == 'owner' else 0
                if kind == 'symlink':
                    m.LIFECYCLE_LOCK.symlink_to(self.base / 'missing-lock')
                elif kind == 'directory':
                    m.LIFECYCLE_LOCK.mkdir(mode=0o600)
                elif kind == 'fifo':
                    os.mkfifo(m.LIFECYCLE_LOCK, 0o600)
                else:
                    m.LIFECYCLE_LOCK.touch(mode=0o644 if kind == 'mode' else 0o600)
                    if kind == 'hardlink':
                        os.link(m.LIFECYCLE_LOCK, self.base / 'second-lock-name')
                self.refuses('Bloqueio de operacao sem protecao')
                self.assert_before_stop()
                if kind == 'directory':
                    m.LIFECYCLE_LOCK.rmdir()
                else:
                    m.LIFECYCLE_LOCK.unlink()

    def test_lock_inode_replacement_refused_before_stop(self):
        original = fcntl.flock

        def replace_after_lock(fd, mode):
            original(fd, mode)
            m.LIFECYCLE_LOCK.unlink()
            m.LIFECYCLE_LOCK.touch(mode=0o600)

        with patch.object(m.fcntl, 'flock', side_effect=replace_after_lock):
            self.refuses('Bloqueio de operacao mudou')
        self.assert_before_stop()

    def test_lifecycle_lock_is_held_during_all_service_commands(self):
        command = self.command

        def assert_locked(args, **kwargs):
            fd = os.open(m.LIFECYCLE_LOCK, os.O_RDWR)
            try:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            finally:
                os.close(fd)
            return command(args, **kwargs)

        with patch.object(m.subprocess, 'run', side_effect=assert_locked):
            self.assertIn('AGENTE_REMOVIDO', self.execute())


if __name__ == '__main__':
    unittest.main(verbosity=2)
