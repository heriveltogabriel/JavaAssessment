"""Installer subprocess compatibility regression using the Python 3.6 API surface.
Real OpenSSL and published public signatures; no installation or private signer.
"""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'dashboard-installer'))
import agent_updates

REAL_RUN = subprocess.run

# Python 3.6 does not accept capture_output or text. Keep actual OpenSSL execution.
def python36_run(args, *, stdout=None, stderr=None, timeout=None):
    return REAL_RUN(args, stdout=stdout, stderr=stderr, timeout=timeout)


class Python36InstallerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='installer-python36-test-')
        cls.root = Path(cls.temp.name)
        with zipfile.ZipFile(ROOT / 'install/v2.0.53/assessment-dashboard-installer-2.0.53.zip') as archive:
            for name in archive.namelist():
                if name.startswith('dist/'):
                    archive.extract(name, cls.root)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def test_public_distribution_accepts_python36_subprocess_contract(self):
        with patch.object(agent_updates.subprocess, 'run', python36_run):
            assets = agent_updates.distribution_assets(self.root, '2.0.53')
        self.assertIn('dist/agent-update-public.der', assets)
        self.assertTrue(any('agent-bundles/2.0.53/' in name for name in assets))

    def test_invalid_signature_still_rejected(self):
        root = self.root / 'dist'
        public = (root / 'agent-update-public.der').read_bytes()
        raw = (root / 'agent-updates/2.0.53/manifest.json').read_bytes()
        with patch.object(agent_updates.subprocess, 'run', python36_run):
            with self.assertRaisesRegex(ValueError, 'signature is invalid'):
                agent_updates.signature(public, raw, bytes(384))


if __name__ == '__main__': unittest.main()
