"""No network: orchestration and credential handling of the manual Mac wrapper."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('recovery_wrapper', ROOT / 'ops/dashboard-release/atualizar-e-recuperar-acesso.py')
wrapper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wrapper)


class RecoveryWrapperTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.here = Path(self.folder.name)
        helper = self.here / 'recover-access-on-host.py'
        helper.write_text('print("fixture recovery only")\n')
        self.module = mock.Mock(HOST='opc@fixture.invalid', OPTIONS=['-o', 'StrictHostKeyChecking=yes'])
        self.output = io.StringIO()
        for patch in [mock.patch.object(wrapper, 'HERE', self.here),
                      mock.patch.object(wrapper, 'VERSION', '1.5.6'),
                      mock.patch.object(wrapper, 'HELPER_SHA256', hashlib.sha256(helper.read_bytes()).hexdigest()),
                      mock.patch.object(wrapper, 'publisher', return_value=self.module),
                      mock.patch('sys.stdin.isatty', return_value=True),
                      mock.patch('sys.stdout', self.output)]:
            patch.start(); self.addCleanup(patch.stop)
        self.output.isatty = lambda: True

    def receipt(self):
        (self.here / 'deployment-result.json').write_text(json.dumps({'status': 'deployed', 'dashboardVersion': '1.5.6', 'health': {'version': '1.5.6'}}))

    def test_refuses_non_interactive_before_any_network(self):
        with mock.patch('sys.stdin.isatty', return_value=False), self.assertRaisesRegex(RuntimeError, 'Terminal interativo'):
            wrapper.main()
        wrapper.publisher.assert_not_called()

    def test_tampered_helper_refused_before_publication(self):
        (self.here / 'recover-access-on-host.py').write_text('changed')
        with self.assertRaisesRegex(RuntimeError, 'difere'):
            wrapper.main()
        wrapper.publisher.assert_not_called()

    def test_publish_then_interactive_ssh_without_output_capture(self):
        self.module.run.side_effect = ['1.5.6\n', '/home/opc/dashboard-access-A1b2C3d4\n', '']
        with mock.patch.object(wrapper.subprocess, 'run', return_value=mock.Mock(returncode=0)) as process:
            wrapper.main()
        self.module.main.assert_called_once_with()
        argv = process.call_args.args[0]
        self.assertEqual(argv[:2], ['ssh', '-tt'])
        self.assertEqual(process.call_args.kwargs, {})
        self.assertIn('hashlib.sha256', argv[-1])
        self.assertIn(wrapper.HELPER_SHA256, argv[-1])
        self.assertNotIn('password', argv[-1])
        self.assertEqual(len(list(self.here.iterdir())), 1, 'no local credential or transcript written')

    def test_retry_skips_only_confirmed_current_publication(self):
        self.receipt()
        self.module.run.side_effect = ['1.5.6', '1.5.6', '/home/opc/dashboard-access-A1b2C3d4', '']
        with mock.patch.object(wrapper.subprocess, 'run', return_value=mock.Mock(returncode=0)):
            wrapper.main()
        self.module.main.assert_not_called()

    def test_recovery_only_never_publishes_without_local_receipt(self):
        self.module.run.side_effect = ['1.5.6', '/home/opc/dashboard-access-A1b2C3d4', '']
        with mock.patch.object(wrapper.subprocess, 'run', return_value=mock.Mock(returncode=0)), mock.patch.object(wrapper, 'confirmed_current') as confirmed:
            wrapper.main(recovery_only=True)
        self.module.main.assert_not_called(); confirmed.assert_not_called()

    def test_recovery_only_version_mismatch_never_publishes_or_resets(self):
        self.module.run.return_value = '1.5.4'
        with mock.patch.object(wrapper.subprocess, 'run') as process, self.assertRaisesRegex(RuntimeError, 'Versão esperada'):
            wrapper.main(recovery_only=True)
        self.module.main.assert_not_called(); process.assert_not_called()

    def test_version_mismatch_prevents_recovery(self):
        self.module.run.return_value = '1.5.4'
        with self.assertRaisesRegex(RuntimeError, 'Versão esperada'):
            wrapper.main()
        self.assertEqual(self.module.run.call_count, 1)

    def test_untrusted_stage_refused(self):
        self.module.run.side_effect = ['1.5.6', '/tmp/unexpected;payload']
        with self.assertRaisesRegex(RuntimeError, 'Diretório remoto'):
            wrapper.main()
        self.assertEqual(self.module.run.call_count, 2)

    def test_nonzero_recovery_never_reported_success(self):
        self.module.run.side_effect = ['1.5.6', '/home/opc/dashboard-access-A1b2C3d4', '']
        with mock.patch.object(wrapper.subprocess, 'run', return_value=mock.Mock(returncode=1)), self.assertRaisesRegex(RuntimeError, 'não confirmada'):
            wrapper.main()
        self.assertNotIn('Escolha sua senha pessoal', self.output.getvalue())


if __name__ == '__main__':
    unittest.main()
