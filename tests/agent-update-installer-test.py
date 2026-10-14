#!/usr/bin/env python3
"""Signed installer catalog regression; disposable local keys and packages only."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / 'dashboard-installer'))
sys.path.insert(0, str(PROJECT / 'ops'))
import agent_update_signing as signing
SPEC = importlib.util.spec_from_file_location('installer_update_build', PROJECT / 'dashboard-installer/build.py')
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)
VERSION = '1.5.8'
PREFIX = 'dist/agent-updates/' + VERSION + '/'


def zipped(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return buffer.getvalue()


class InstallerUpdateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.signing_temporary = tempfile.TemporaryDirectory(prefix='installer-signing-fixture-')
        cls.signing_root = Path(cls.signing_temporary.name)
        _, cls.public = signing.ensure_keypair(cls.signing_root, create=True)

    @classmethod
    def tearDownClass(cls):
        cls.signing_temporary.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='installer-catalog-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.stage = self.root / 'stage'
        (self.stage / 'dist').mkdir(parents=True)
        (self.root / 'collector').mkdir()
        for name, data in {'INSTALL.md': b'current docs', 'install.sh': b'changed workspace installer',
                           'root-system-install.sh': b'changed workspace root installer',
                           'collector.properties.example': b'current example'}.items():
            (self.root / 'collector' / name).write_bytes(data)
        self.entries = {
            'collector.jar': zipped({'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\nImplementation-Version: ' + VERSION + '\n\n',
                                    'assessment/AssessmentCollector.class': b'\xca\xfe\xba\xbe\x00\x00\x00\x32',
                                    'assessment/AgentUpdateProbe.class': b'\xca\xfe\xba\xbe\x00\x00\x00\x32'}),
            'DEPENDENCIES.json': json.dumps({'version': VERSION}).encode(), 'update-public.der': self.public,
            'INSTALL.md': b'released docs', 'conf/ca.pem': b'public fixture CA',
            'conf/collector.properties.example': b'released example', 'lib/public-fixture.jar': b'stable fixture library',
        }
        for name in ['install.sh', 'root-system-install.sh', 'root-service.sh', 'root-trust.sh',
                     'update-install.sh', 'update-service.sh', 'update-local.sh']:
            self.entries[name] = ('#!/bin/sh\n# released ' + name + '\nexit 0\n').encode()
        self.collector = self.package(self.entries)
        agent = self.root / 'agent.zip'
        agent.write_bytes(self.collector)
        self.assets = signing.sign_release(self.signing_root, agent, VERSION)
        for change in (patch.object(builder, 'PROJECT', self.root), patch.object(builder, 'RELEASE_VERSION', VERSION),
                       patch.object(builder, 'private_literals', return_value=[])):
            change.start()
            self.addCleanup(change.stop)

    def package(self, entries):
        values = dict(entries)
        values['SHA256SUMS'] = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n'
                                      for name, data in sorted(values.items())).encode()
        return zipped(values)

    def runtime(self, assets=None):
        release = self.root / 'output/dashboard-releases' / VERSION
        packages = release / 'packages'
        packages.mkdir(parents=True, exist_ok=True)
        runtime_name = 'packages/assessment-dashboard-runtime-' + VERSION + '.zip'
        runtime = release / runtime_name
        runtime.write_bytes(zipped(self.assets if assets is None else assets))
        (packages / 'package-manifest.json').write_text(json.dumps({'artifacts': [
            {'file': runtime_name, 'sha256': builder.digest(runtime.read_bytes())}]}))
        (self.stage / 'dist/assessment-collector.zip').write_bytes(self.collector)
        return runtime

    def test_signed_generic_preserves_immutable_scripts_despite_workspace_drift(self):
        release = self.root / 'output/dashboard-releases' / VERSION / 'packages'
        release.mkdir(parents=True)
        (release / ('assessment-collector-' + VERSION + '.zip')).write_bytes(self.collector)
        result, ca = builder.configured_generic(self.root / 'dist/assessment-collector.zip')
        with zipfile.ZipFile(io.BytesIO(result)) as archive:
            self.assertEqual(archive.read('install.sh'), self.entries['install.sh'])
            self.assertEqual(archive.read('root-system-install.sh'), self.entries['root-system-install.sh'])
            self.assertEqual(archive.read('INSTALL.md'), b'current docs')
            self.assertEqual(ca, self.entries['conf/ca.pem'])
            self.assertIn('  DEPENDENCIES.json\n', archive.read('SHA256SUMS').decode())
        builder.validate_update_catalog(result, self.assets)

    def test_verified_public_catalog_is_copied(self):
        self.runtime()
        builder.copy_update_catalog(self.stage)
        for name, data in self.assets.items():
            self.assertEqual((self.stage / name).read_bytes(), data)

    def checkout_catalog(self, directory='release'):
        (self.stage / 'dist/assessment-collector.zip').write_bytes(self.collector)
        for name, data in self.assets.items():
            path = self.root / directory / name[len('dist/'):]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    def test_clean_checkout_and_source_archive_reuse_verified_public_catalog(self):
        for directory in ('release', 'dist'):
            with self.subTest(directory=directory):
                self.checkout_catalog(directory)
                builder.copy_update_catalog(self.stage)
                for name, data in self.assets.items():
                    self.assertEqual((self.stage / name).read_bytes(), data)
                for name in self.assets:
                    (self.root / directory / name[len('dist/'):]).unlink()

    def test_checkout_rejects_tampered_signature_and_missing_catalog(self):
        (self.stage / 'dist/assessment-collector.zip').write_bytes(self.collector)
        with self.assertRaisesRegex(ValueError, 'missing its public update catalog'):
            builder.copy_update_catalog(self.stage)
        self.checkout_catalog()
        signature = self.root / 'release' / PREFIX[len('dist/'):] / 'manifest.sig'
        signature.write_bytes(b'x' * 384)
        with self.assertRaisesRegex(ValueError, 'signature'):
            builder.copy_update_catalog(self.stage)
        self.assertFalse((self.stage / 'dist/agent-update-public.der').exists())

    def test_checkout_rejects_incomplete_and_unexpected_catalog(self):
        self.checkout_catalog()
        payload = self.root / 'release' / PREFIX[len('dist/'):] / 'collector.jar'
        payload.unlink()
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            builder.copy_update_catalog(self.stage)
        self.checkout_catalog()
        (payload.parent / 'unexpected.txt').write_text('unexpected')
        with self.assertRaisesRegex(ValueError, 'unexpected entries'):
            builder.copy_update_catalog(self.stage)

    def test_mutated_stable_asset_or_pin_or_jar_refuses_installer(self):
        for name in ('install.sh', 'root-system-install.sh', 'update-public.der', 'collector.jar'):
            with self.subTest(name=name):
                changed = dict(self.entries)
                changed[name] += b'changed'
                with self.assertRaisesRegex(ValueError, 'immutable collector assets'):
                    builder.validate_update_catalog(self.package(changed), self.assets)

    def test_signed_requirement_inventory_must_match_exactly(self):
        changed = dict(self.entries)
        changed['extra-helper.sh'] = b'#!/bin/sh\nexit 0\n'
        with self.assertRaisesRegex(ValueError, 'immutable collector assets'):
            builder.validate_update_catalog(self.package(changed), self.assets)

    def test_signature_tampering_fails_before_copy(self):
        assets = dict(self.assets)
        raw = bytearray(assets[PREFIX + 'manifest.sig'])
        raw[0] ^= 1
        assets[PREFIX + 'manifest.sig'] = bytes(raw)
        self.runtime(assets)
        with self.assertRaisesRegex(ValueError, 'signature'):
            builder.copy_update_catalog(self.stage)
        self.assertFalse((self.stage / 'dist/agent-update-public.der').exists())

    def test_runtime_checksum_change_refuses_copy(self):
        runtime = self.runtime()
        runtime.write_bytes(runtime.read_bytes() + b'tampered')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            builder.copy_update_catalog(self.stage)

    def test_missing_catalog_for_capable_agent_refuses_copy(self):
        self.runtime({})
        with self.assertRaisesRegex(ValueError, 'missing its update catalog'):
            builder.copy_update_catalog(self.stage)

    def test_partial_catalog_refuses_copy(self):
        assets = dict(self.assets)
        del assets[PREFIX + 'manifest.sig']
        self.runtime(assets)
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            builder.copy_update_catalog(self.stage)

    def test_historical_uncapable_release_still_allows_absent_catalog(self):
        entries = dict(self.entries)
        del entries['update-public.der']
        self.collector = self.package(entries)
        self.runtime({})
        builder.copy_update_catalog(self.stage)
        self.assertFalse((self.stage / 'dist/agent-update-public.der').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
