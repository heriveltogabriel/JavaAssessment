"""Offline package/permissions checks and opt-in isolated Java 17 startup.

JAVA_HOME=/path/to/jdk BETA_INSTALLER_RUNTIME_TEST=1 python3 -B tests/beta-installer-test.py
This never executes useradd, systemctl, SSH or a remote deployment.
"""
import hashlib
import json
import os
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import types
import unittest
import zipfile
from pathlib import Path
from unittest import mock

PROJECT = Path(__file__).resolve().parent.parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(PROJECT / 'dashboard-installer'))
import bootstrap
import build
import install
from dependencies import verify_libraries


class BetaInstallerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='beta-installer-test-')
        cls.folder = Path(cls.temporary.name)
        cls.package = cls.folder / 'installer.zip'
        cls.checksum = build.build(cls.package)
        cls.source = cls.folder / 'source'
        with zipfile.ZipFile(cls.package) as archive:
            archive.extractall(cls.source)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_package_complete_and_without_runtime_credentials(self):
        install.verify_manifest(self.source)
        lock = verify_libraries(self.source / 'lib')
        self.assertEqual(7, len(lock['dependencies']))
        self.assertTrue((self.source / 'server/InventoryStore.java').is_file())
        self.assertEqual('1.0-beta', (self.source / 'VERSION').read_text().strip())
        self.assertEqual(self.checksum, hashlib.sha256(self.package.read_bytes()).hexdigest())
        self.assertEqual(self.checksum, self.package.with_suffix('.zip.sha256').read_text().split()[0])
        with zipfile.ZipFile(self.package) as archive:
            self.assertIsNone(archive.testzip())
            for name in archive.namelist():
                self.assertFalse(name.startswith(('conf/', 'data/', 'logs/', 'ops/')))
                self.assertFalse(name.endswith(('.key', '.p12', '.jks', '.sqlite')))
            with zipfile.ZipFile(self.source / 'dist/assessment-collector.zip') as collector:
                self.assertNotIn('conf/bootstrap.properties', collector.namelist())
                self.assertNotIn('conf/collector.properties', collector.namelist())
                self.assertIn('conf/ca.pem', collector.namelist())

    def test_dependencies_reject_tampered_or_extra_jars(self):
        with tempfile.TemporaryDirectory() as folder:
            copied = Path(folder) / 'lib'
            shutil.copytree(self.source / 'lib', copied)
            extra = copied / 'unexpected.jar'
            extra.write_bytes(b'not a reviewed library')
            with self.assertRaisesRegex(ValueError, 'JAR set'):
                verify_libraries(copied)
            extra.unlink()
            target = copied / 'sqlite-jdbc-3.53.4.0.jar'
            with target.open('ab') as output:
                output.write(b'altered')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                verify_libraries(copied)

    def test_generic_collector_rejects_enrolled_identity(self):
        bad = self.folder / 'enrolled.zip'
        with zipfile.ZipFile(bad, 'w') as archive:
            archive.writestr('INSTALL.md', 'example')
            archive.writestr('conf/bootstrap.properties', 'enrollmentToken=private')
        with self.assertRaisesRegex(ValueError, 'runtime identity'):
            build.configured_generic(bad)

    def test_legacy_input_requires_consistent_private_identities(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder)
            state = source / 'state.json'
            auth = source / 'auth-registry.json'
            state.write_text(json.dumps({'hosts': [{'id': 'host-fixture'}]}))
            state.chmod(0o600)
            with self.assertRaisesRegex(RuntimeError, 'existing regular file'):
                install.legacy_files(source)
            auth.write_text(json.dumps({'hosts': {}}))
            auth.chmod(0o600)
            with self.assertRaisesRegex(RuntimeError, 'do not match'):
                install.legacy_files(source)
            identity = {'hostId': 'host-fixture', 'agentToken': 'local-test-token', 'status': 'active'}
            auth.write_text(json.dumps({'hosts': {'host-fixture': identity}}))
            self.assertEqual({'state.json', 'auth-registry.json'}, set(install.legacy_files(source)))
            auth.chmod(0o644)
            with self.assertRaisesRegex(RuntimeError, 'mode 600 or 400'):
                install.legacy_files(source)

    def test_source_archive_has_only_public_code_and_pinned_libraries(self):
        target = self.folder / 'source.zip'
        build.build_source(target)
        with zipfile.ZipFile(target) as archive:
            names = archive.namelist()
            self.assertIn('dashboard-installer/build.py', names)
            self.assertIn('server/InventoryStore.java', names)
            self.assertIn('ops/prepare-beta-dependencies.py', names)
            self.assertNotIn('ops/remote.py', names)
            self.assertFalse(any(name.startswith(('conf/', 'data/', 'logs/', 'tests/fixtures/')) for name in names))
            self.assertFalse(any(name.endswith(('.key', '.p12', '.jks', '.sqlite')) for name in names))

    def test_private_data_owned_by_service_before_boot(self):
        with tempfile.TemporaryDirectory() as folder:
            stage = Path(folder)
            for directory in ('conf/tls', 'data/native', 'logs', 'classes'):
                (stage / directory).mkdir(parents=True)
            for name in ('conf/tls/privkey.pem', 'conf/server.properties', 'run-server.sh',
                         'data/inventory.sqlite', 'data/native/library.so', 'logs/gc.log'):
                (stage / name).write_text('fixture')
            account = types.SimpleNamespace(pw_uid=401, pw_gid=402)
            with mock.patch.object(install.os, 'chown') as change_owner:
                install.set_stage_ownership(stage, account)
            owners = {Path(call.args[0]): call.args[1:] for call in change_owner.call_args_list}
            for name in ('data', 'data/native', 'logs'):
                self.assertEqual((401, 402), owners[stage / name])
                self.assertEqual(0o700, stat.S_IMODE((stage / name).stat().st_mode))
            for name in ('data/inventory.sqlite', 'data/native/library.so', 'logs/gc.log'):
                self.assertEqual((401, 402), owners[stage / name])
                self.assertEqual(0o600, stat.S_IMODE((stage / name).stat().st_mode))
            for name in ('conf/server.properties', 'conf/tls/privkey.pem'):
                self.assertEqual((0, 402), owners[stage / name])
                self.assertEqual(0o640, stat.S_IMODE((stage / name).stat().st_mode))
            self.assertEqual((0, 402), owners[stage / 'run-server.sh'])

    def test_unit_recovers_failures_and_restricts_writes(self):
        unit = install.service_unit(443)
        for setting in ('Restart=on-failure', 'MemoryMax=768M', 'UMask=0077',
                        'StandardError=journal', 'ProtectSystem=strict', 'PrivateTmp=true',
                        'AmbientCapabilities=CAP_NET_BIND_SERVICE'):
            self.assertIn(setting + '\n', unit)
        self.assertIn('ReadWritePaths=' + str(install.DEST / 'data') + ' ' + str(install.DEST / 'logs'), unit)
        self.assertNotIn('CAP_NET_BIND_SERVICE', install.service_unit(8445))

    def test_stage_compiles_all_sources_for_java17_and_keeps_runtime_private(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            stage = base / 'stage'
            stage.mkdir()
            cert, key, ca, _ = bootstrap.generate_tls(base / 'tls', 'dashboard.example.com')
            with mock.patch.object(install, 'SOURCE', self.source), \
                 mock.patch.object(install, 'DEST', stage), \
                 mock.patch.object(install, 'command') as command:
                install.build_stage(stage, Path('/test/jdk'), 'https://dashboard.example.com',
                                    'dashboard.example.com', 443, cert, key, ca, 'test-password-1234')
            compile_args = command.call_args.args[0]
            self.assertEqual('17', compile_args[compile_args.index('--release') + 1])
            self.assertIn(str(stage / 'server/InventoryStore.java'), compile_args)
            runner = (stage / 'run-server.sh').read_text()
            self.assertIn('-XX:+ExitOnOutOfMemoryError', runner)
            self.assertIn('-Dorg.sqlite.tmpdir=' + str(stage / 'data/native'), runner)
            self.assertIn('-Xmx384m', runner)
            self.assertEqual(0o700, stat.S_IMODE((stage / 'data/native').stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE((stage / 'conf/tls/privkey.pem').stat().st_mode))

    @unittest.skipUnless(os.environ.get('BETA_INSTALLER_RUNTIME_TEST') == '1',
                         'Enable BETA_INSTALLER_RUNTIME_TEST=1 for actual Java/TLS/SQLite startup')
    def test_isolated_install_starts_https_with_sqlite(self):
        self.runtime_case()

    @unittest.skipUnless(os.environ.get('BETA_INSTALLER_RUNTIME_TEST') == '1',
                         'Enable BETA_INSTALLER_RUNTIME_TEST=1 for actual legacy import')
    def test_isolated_install_imports_legacy_before_first_boot(self):
        state = {'fleet': {'name': 'Migration fixture'}, 'hosts': [{'id': 'host-fixture', 'hostname': 'fixture'}],
                 'settings': {'enabled': False}, 'environments': [], 'jobs': [], 'eventIds': [],
                 'hostSchedules': {}, 'scheduleRevision': 'fixture'}
        auth = {'hosts': {'host-fixture': {'hostId': 'host-fixture', 'agentToken': 'local-migration-fixture-token',
                                          'status': 'active'}}, 'enrollments': {}}
        self.runtime_case({'state.json': json.dumps(state).encode(), 'auth-registry.json': json.dumps(auth).encode()})

    def runtime_case(self, legacy=None):
        java_home = install.validate_java(os.environ['JAVA_HOME'])
        with tempfile.TemporaryDirectory(prefix='beta-runtime-') as folder:
            stage = Path(folder) / 'stage'
            stage.mkdir()
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                port = listener.getsockname()[1]
            cert, key, ca, _ = bootstrap.generate_tls(Path(folder) / 'tls', 'dashboard.example.com')
            with mock.patch.object(install, 'SOURCE', self.source), mock.patch.object(install, 'DEST', stage):
                install.build_stage(stage, java_home, 'https://dashboard.example.com:%d' % port,
                                    'dashboard.example.com', port, cert, key, ca, 'test-password-1234', legacy)
            with (stage / 'conf/server.properties').open('a') as output:
                output.write('\nhttpPort=0\nhttpsBind=127.0.0.1\n')
            with (Path(folder) / 'startup.log').open('wb') as output:
                process = subprocess.Popen(['/bin/sh', str(stage / 'run-server.sh')],
                                           stdout=output, stderr=subprocess.STDOUT)
                try:
                    install.health_check('dashboard.example.com', port, ca)
                    self.assertTrue((stage / 'data/inventory.sqlite').is_file())
                    self.assertTrue(list((stage / 'data/native').glob('*sqlite*')))
                    self.assertEqual(0o600, stat.S_IMODE((stage / 'data/inventory.sqlite').stat().st_mode))
                    if legacy:
                        self.assertTrue(list((stage / 'data').glob('migration-backup-*/state.json')))
                    backup = Path(folder) / 'snapshot.sqlite'
                    report = subprocess.run(['/bin/sh', str(stage / 'database.sh'), 'backup',
                                             str(stage / 'data/inventory.sqlite'), str(backup)],
                                            capture_output=True, text=True, check=True)
                    self.assertIn('BACKUP_OK', report.stdout)
                    self.assertEqual(0o600, stat.S_IMODE(backup.stat().st_mode))
                    restored = Path(folder) / 'restored.sqlite'
                    shutil.copy2(backup, restored)
                    report = subprocess.run(['/bin/sh', str(stage / 'database.sh'), 'check', str(restored)],
                                            capture_output=True, text=True, check=True)
                    self.assertIn('QUICK_CHECK_OK; MIGRATION_V1_COMPLETE', report.stdout)
                    self.assertIn('hosts=' + ('1' if legacy else '0'), report.stdout)
                    self.assertIn('identities=' + ('1' if legacy else '0'), report.stdout)
                    refusal = subprocess.run(['/bin/sh', str(stage / 'database.sh'), 'backup',
                                              str(stage / 'data/inventory.sqlite'), str(backup)],
                                             capture_output=True, text=True)
                    self.assertNotEqual(0, refusal.returncode)
                except Exception:
                    print((Path(folder) / 'startup.log').read_text(), file=sys.stderr)
                    raise
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


if __name__ == '__main__':
    unittest.main()
