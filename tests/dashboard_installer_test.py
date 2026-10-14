"""Offline checks for the one-command dashboard installer package."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

PROJECT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT / 'dashboard-installer'))
import bootstrap  # noqa: E402
import install  # noqa: E402
import build  # noqa: E402


class DashboardInstallerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.TemporaryDirectory(prefix='dashboard-installer-fixture-')
        cls.package = Path(cls.folder.name) / 'installer.zip'
        build.build(cls.package)

    @classmethod
    def tearDownClass(cls):
        cls.folder.cleanup()

    def test_enterprise_linux_version(self):
        with tempfile.TemporaryDirectory() as folder:
            release = Path(folder) / 'os-release'
            release.write_text('ID="ol"\nVERSION_ID="8.10"\n', encoding='utf-8')
            self.assertEqual(bootstrap.el_major(release), 8)
            release.write_text('ID="rhel"\nVERSION_ID="7.9"\n', encoding='utf-8')
            with self.assertRaisesRegex(RuntimeError, '8 or later'):
                bootstrap.el_major(release)

    def test_url_selection_requires_confirmation(self):
        self.assertEqual(bootstrap.choose_public_url('dashboard.example.com'),
                         'https://dashboard.example.com')
        with mock.patch.object(bootstrap, 'detect_public_ip', return_value=('8.8.8.8', 'test')):
            self.assertEqual(bootstrap.choose_public_url(None, input_fn=lambda _: '', interactive=True),
                             'https://8.8.8.8')
            self.assertEqual(bootstrap.choose_public_url(None, input_fn=lambda _: 'dash.example.com', interactive=True),
                             'https://dash.example.com')
            with self.assertRaisesRegex(RuntimeError, '--public-url'):
                bootstrap.choose_public_url(None, interactive=False)
        with self.assertRaises(RuntimeError):
            install.parse_url('https://localhost')

    def test_generated_tls_matches_ip_and_dns(self):
        for host in ('8.8.8.8', 'dashboard.example.com'):
            with self.subTest(host=host), tempfile.TemporaryDirectory() as folder:
                cert, key, ca, ca_key = bootstrap.generate_tls(folder, host)
                install.validate_certificate(cert, key, ca, host)
                with self.assertRaises(RuntimeError):
                    install.validate_certificate(cert, key, ca, '1.1.1.1')
                self.assertEqual(ca_key.stat().st_mode & 0o077, 0)

    def test_package_manifest_and_ca_replacement(self):
        package = self.package
        self.assertTrue(package.is_file(), 'Run dashboard-installer/build.py first')
        with tempfile.TemporaryDirectory() as folder:
            with zipfile.ZipFile(package) as archive:
                self.assertIsNone(archive.testzip())
                archive.extractall(folder)
            root = Path(folder)
            install.verify_manifest(root)
            self.assertEqual((root / 'GUIA_INSTALACAO.md').read_bytes(),
                             (PROJECT / 'dashboard-installer/GUIA_INSTALACAO.md').read_bytes())
            self.assertFalse((root / 'conf/server.properties').exists())
            self.assertFalse((root / 'conf/tls/privkey.pem').exists())
            with zipfile.ZipFile(root / 'dist/assessment-collector.zip') as agent:
                self.assertIn(b'--switch-dashboard', agent.read('install.sh'))
                self.assertIn(b'--switch-dashboard', agent.read('root-system-install.sh'))
                self.assertIn(b'--switch-dashboard', agent.read('INSTALL.md'))
                self.assertNotIn('conf/bootstrap.properties', agent.namelist())
            with tempfile.TemporaryDirectory() as tls_folder:
                cert, key, ca, ca_key = bootstrap.generate_tls(tls_folder, '8.8.8.8')
                configured = Path(tls_folder) / 'collector.zip'
                install.repackage_collector(root / 'dist/assessment-collector.zip',
                                            configured, ca.read_bytes())
                with zipfile.ZipFile(configured) as agent:
                    self.assertEqual(agent.read('conf/ca.pem'), ca.read_bytes())
                    hashes = agent.read('SHA256SUMS').decode('ascii').splitlines()
                    self.assertTrue(all(hashlib.sha256(agent.read(line[66:])).hexdigest() == line[:64]
                                        for line in hashes))
                    self.assertNotIn('conf/bootstrap.properties', agent.namelist())

    def test_preflight_checks_generated_tls_without_installing(self):
        package = self.package
        with tempfile.TemporaryDirectory() as folder:
            with zipfile.ZipFile(package) as archive:
                archive.extractall(folder)
            args = argparse.Namespace(public_url='https://8.8.8.8', java_home=None,
                                      tls_cert=None, tls_key=None, agent_ca=None,
                                      admin_password_file=None, no_package_install=True,
                                      admin_name='Fixture Admin', admin_email='fixture@example.com',
                                      check=True)
            with mock.patch.object(install, 'SOURCE', Path(folder)), \
                 mock.patch.object(install, 'ensure_java', return_value=Path('/fake/jdk')), \
                 contextlib.redirect_stdout(io.StringIO()) as output:
                install.install(args)
            self.assertIn('PRECHECK OK', output.getvalue())

    def test_first_admin_requires_explicit_valid_identity_without_terminal(self):
        for name, email in [(None, None), ('Admin', 'missing-domain'), ('bad\nname', 'admin@example.com')]:
            with self.subTest(name=name, email=email), self.assertRaises(RuntimeError):
                install.admin_identity(name, email, interactive=False)
        self.assertEqual(('Admin Name', 'admin@example.com'),
                         install.admin_identity(' Admin Name ', 'ADMIN@EXAMPLE.COM ', interactive=False))

    def test_first_admin_password_has_no_legacy_short_or_shared_file_exception(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'password'
            path.write_text('1234567\n'); path.chmod(0o600)
            with self.assertRaisesRegex(RuntimeError, '8–128'):
                install.read_password(path)
            path.write_text('Abcd123!\n')
            self.assertEqual('Abcd123!', install.read_password(path))
            path.write_text('a' * 129 + '\n')
            with self.assertRaisesRegex(RuntimeError, '8–128'):
                install.read_password(path)
            path.write_text('fixture-personal-password\n'); path.chmod(0o644)
            with self.assertRaisesRegex(RuntimeError, 'mode 600 or 400'):
                install.read_password(path)
            path.chmod(0o400)
            self.assertEqual('fixture-personal-password', install.read_password(path))

    def test_provisioning_uses_private_ephemeral_file_and_erases_it_on_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            stage = Path(folder) / 'stage'; stage.mkdir()
            observed = []
            def provision(argv):
                self.assertIn('assessment.UserAdminTool', argv)
                self.assertNotIn('fixture-temporary-password', argv)
                secret = Path(argv[argv.index('--password-file') + 1]); observed.append(secret)
                self.assertEqual(0o600, secret.stat().st_mode & 0o777)
                self.assertEqual('fixture-temporary-password\n', secret.read_text())
                raise RuntimeError('fixture provisioning failure')
            with mock.patch.object(install, 'command', side_effect=provision), self.assertRaises(RuntimeError):
                install.provision_admin(stage, Path('/fixture/jdk'), 'Fixture Admin', 'fixture@example.com', 'fixture-temporary-password')
            self.assertEqual(1, len(observed))
            self.assertFalse(observed[0].exists())


if __name__ == '__main__':
    unittest.main()
