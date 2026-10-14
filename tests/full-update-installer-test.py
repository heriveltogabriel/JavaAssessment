#!/usr/bin/env python3
"""Public V3 installer assembly; signed disposable fixture, no services or hosts."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / 'dashboard-installer'))
import agent_updates as public
import install

SPEC = importlib.util.spec_from_file_location('full_installer_build', PROJECT / 'dashboard-installer/build.py')
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)
FIXTURE = PROJECT / 'tmp/full-update/http-fixture-v2'


def zipped(entries, duplicate=None, link=None):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            item = zipfile.ZipInfo(name)
            item.create_system = 3
            item.compress_type = zipfile.ZIP_DEFLATED
            item.external_attr = (stat.S_IFLNK | 0o777 if name == link else stat.S_IFREG | 0o600) << 16
            archive.writestr(item, data)
        if duplicate:
            archive.writestr(duplicate, entries[duplicate])
    return output.getvalue()


def package(entries):
    entries = {name: data for name, data in entries.items() if name != 'SHA256SUMS'}
    entries['SHA256SUMS'] = ''.join(public.digest(data) + '  ' + name + '\n'
                                  for name, data in sorted(entries.items())).encode()
    return zipped(entries)


class FullInstallerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.metadata = json.loads((FIXTURE / 'fixture.json').read_text())
        if not cls.metadata.get('testOnly') or cls.metadata.get('productionPrivateKeyRead'):
            raise ValueError('An exclusive, disposable signing fixture is required')
        cls.target = cls.metadata['targetVersion']
        cls.read = staticmethod(public.filesystem_reader(FIXTURE))
        published = public.generic_entries((FIXTURE / cls.metadata['genericInstaller']).read_bytes())
        cls.generic = package({name: data for name, data in published.items() if not name.startswith('full-update/')})
        cls.full = public.collect_full(cls.target, cls.read)
        cls.assets = dict(cls.full)
        cls.assets.update(public.classic_assets(cls.target, cls.read, cls.generic))
        cls.embedded = public.embed_full(cls.generic, cls.assets, cls.target)
        cls.entries = public.generic_entries(cls.generic)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='full-installer-public-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.stage = self.root / 'stage'
        (self.stage / 'dist').mkdir(parents=True)
        (self.stage / 'dist/assessment-collector.zip').write_bytes(self.generic)
        for change in (patch.object(builder, 'PROJECT', self.root), patch.object(builder, 'RELEASE_VERSION', self.target),
                       patch.object(builder, 'private_literals', return_value=[])):
            change.start()
            self.addCleanup(change.stop)

    def write_assets(self, assets=None, directory='dist'):
        for name, data in (self.assets if assets is None else assets).items():
            path = self.root / directory / name[len('dist/'):]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    def runtime(self, assets=None):
        release = self.root / 'output/dashboard-releases' / self.target
        packages = release / 'packages'
        packages.mkdir(parents=True)
        name = 'packages/assessment-dashboard-runtime-' + self.target + '.zip'
        runtime = release / name
        runtime.write_bytes(zipped(self.assets if assets is None else assets))
        (packages / 'package-manifest.json').write_text(json.dumps({'artifacts': [
            {'file': name, 'sha256': public.digest(runtime.read_bytes())}]}))

    def assert_no_partial_copy(self):
        self.assertEqual(self.generic, (self.stage / 'dist/assessment-collector.zip').read_bytes())
        self.assertEqual(['dist/assessment-collector.zip'], sorted(str(p.relative_to(self.stage)) for p in self.stage.rglob('*') if p.is_file()))

    def test_runtime_copies_exact_public_closure_and_embeds_fresh_bundle(self):
        unrelated = {'dist/agent-bundles/99.0.0/manifest.json': b'unrelated future release',
                     'dist/agent-bridges/99.0.0/unrelated/private.key': b'never copy',
                     'data/identity.json': b'never copy'}
        self.runtime({**self.assets, **unrelated})
        builder.copy_update_catalog(self.stage)
        self.assertEqual(set(self.assets) | {'dist/assessment-collector.zip'},
                         {str(p.relative_to(self.stage)) for p in self.stage.rglob('*') if p.is_file()})
        self.assertEqual(self.assets, public.distribution_assets(self.stage, self.target))
        embedded = public.generic_entries((self.stage / 'dist/assessment-collector.zip').read_bytes())
        self.assertEqual({name: data for name, data in self.entries.items() if name != 'SHA256SUMS'},
                         {name: data for name, data in embedded.items() if name in self.entries and name != 'SHA256SUMS'})
        self.assertEqual({'full-update/' + name for name in ('manifest.json', 'manifest.sig', 'bundle.zip')}, set(embedded) - set(self.entries))

    def test_checkout_full_catalog_preferred_over_classic_and_only_public_files(self):
        classic = {name: data for name, data in self.assets.items() if name.startswith('dist/agent-updates/') or name == 'dist/agent-update-public.der'}
        self.write_assets(classic, 'release')
        self.write_assets()
        builder.copy_update_catalog(self.stage)
        self.assertEqual(self.assets, public.distribution_assets(self.stage, self.target))
        self.assertFalse((self.stage / 'dist/agent-bundles/99.0.0').exists())

    def test_corrupt_or_partial_full_catalog_never_falls_back_to_valid_classic(self):
        names = [self.metadata['signature'], self.metadata['routes'].replace('routes.json', 'routes.sig'),
                 self.metadata['bridgeJar'].replace('collector.jar', 'manifest.sig')]
        for name in names:
            with self.subTest(name=name):
                changed = dict(self.assets)
                changed[name] = bytes(384)
                self.write_assets(changed)
                with self.assertRaisesRegex(ValueError, 'signature'):
                    builder.copy_update_catalog(self.stage)
                self.assert_no_partial_copy()
        self.write_assets()
        (self.root / self.metadata['bridgeJar']).unlink()
        with self.assertRaises(FileNotFoundError):
            builder.copy_update_catalog(self.stage)
        self.assert_no_partial_copy()

    def test_partial_route_marker_and_full_agent_without_catalog_are_refused(self):
        for name in (self.metadata['routes'], self.metadata['signature'], self.metadata['bundle']):
            with self.subTest(marker=name):
                self.write_assets({name: self.assets[name]})
                with self.assertRaises(FileNotFoundError):
                    builder.copy_update_catalog(self.stage)
                self.assert_no_partial_copy()
                (self.root / name).unlink()
        with self.assertRaisesRegex(ValueError, 'complete public update catalog'):
            builder.copy_update_catalog(self.stage)

    def test_duplicated_runtime_asset_is_refused_without_copy(self):
        assets = zipped(self.assets, duplicate=self.metadata['routes'])
        with zipfile.ZipFile(io.BytesIO(assets)) as archive:
            with self.assertRaisesRegex(ValueError, 'duplicated'):
                builder.copy_full_catalog(self.stage, self.generic, public.archive_reader(archive))
        self.assert_no_partial_copy()

    def test_linked_public_catalog_is_refused(self):
        self.write_assets()
        path = self.root / self.metadata['bundle']
        saved = self.root / 'saved-bundle.zip'
        path.rename(saved)
        path.symlink_to(saved)
        with self.assertRaisesRegex(ValueError, 'linked'):
            builder.copy_update_catalog(self.stage)
        self.assert_no_partial_copy()

    def test_bundle_content_hash_and_zip_metadata_limits_are_enforced(self):
        prefix = 'dist/agent-bundles/' + self.target + '/'
        manifest = public.document(self.assets[prefix + 'manifest.json'])
        bundle = self.assets[prefix + 'bundle.zip']
        with self.assertRaisesRegex(ValueError, 'hash or size'):
            public.bundle_files(manifest, bundle + b'changed', self.target)
        changed = dict(manifest)
        changed['bytes'] = len(bundle) + 1
        changed['sha256'] = public.digest(bundle + b'x')
        with self.assertRaisesRegex(ValueError, 'trailing data'):
            public.bundle_files(changed, bundle + b'x', self.target)
        with zipfile.ZipFile(io.BytesIO(bundle)) as archive:
            content = {name: archive.read(name) for name in archive.namelist()}
        unsafe = zipped(content, link='code/collector.jar')
        changed = {**manifest, 'bytes': len(unsafe), 'sha256': public.digest(unsafe)}
        with self.assertRaisesRegex(ValueError, 'unsafe metadata'):
            public.bundle_files(changed, unsafe, self.target)
        fields = dict(manifest['files'])
        fields['state/identity.json'] = dict(next(iter(fields.values())))
        with self.assertRaisesRegex(ValueError, 'Invalid signed bundle file'):
            public.bundle_files({**manifest, 'files': fields}, bundle, self.target)

    def test_repackaged_ca_has_complete_checksums_and_retains_every_signed_byte(self):
        self.write_assets()
        source = self.root / 'dist/assessment-collector.zip'
        source.write_bytes(self.embedded)
        before = source.read_bytes()
        output = self.root / 'configured.zip'
        ca = b'-----BEGIN CERTIFICATE-----\nfixture customer CA\n-----END CERTIFICATE-----\n'
        install.repackage_collector(source, output, ca)
        configured = public.generic_entries(output.read_bytes())
        original = public.generic_entries(before)
        self.assertEqual(ca, configured['conf/ca.pem'])
        self.assertEqual({name: value for name, value in original.items() if name not in ('SHA256SUMS', 'conf/ca.pem', 'DEPENDENCIES.json')},
                         {name: value for name, value in configured.items() if name not in ('SHA256SUMS', 'conf/ca.pem', 'DEPENDENCIES.json')})
        self.assertIn(b'  DEPENDENCIES.json\n', configured['SHA256SUMS'])
        self.assertEqual(public.digest(ca), json.loads(configured['DEPENDENCIES.json'])['ca']['sha256'])
        source.write_bytes(output.read_bytes())
        self.assertEqual(self.assets, public.distribution_assets(self.root, self.target))

    def test_existing_embedded_generic_is_immutable_and_conflicting_trio_refused(self):
        self.assertEqual(self.embedded, public.embed_full(self.embedded, self.assets, self.target))
        changed = public.generic_entries(self.embedded)
        changed['full-update/manifest.sig'] = bytes(384)
        with self.assertRaisesRegex(ValueError, 'Embedded full update differs'):
            public.embed_full(package(changed), self.assets, self.target)
        changed = dict(self.entries)
        changed['install.sh'] += b'\n# workspace drift\n'
        with self.assertRaisesRegex(ValueError, 'executable differs'):
            public.embed_full(package(changed), self.assets, self.target)

    def test_generic_checksum_inventory_must_cover_dependencies_and_no_links(self):
        changed = dict(self.entries)
        changed['SHA256SUMS'] = b'\n'.join(line for line in changed['SHA256SUMS'].splitlines() if not line.endswith(b'  DEPENDENCIES.json')) + b'\n'
        with self.assertRaisesRegex(ValueError, 'checksum inventory differs'):
            public.generic_entries(zipped(changed))
        with self.assertRaisesRegex(ValueError, 'Unsafe generic collector entry'):
            public.generic_entries(zipped(self.entries, link='collector.jar'))

    def test_missing_embedded_bundle_refused_at_install_preflight(self):
        self.write_assets()
        (self.root / 'dist/assessment-collector.zip').write_bytes(self.generic)
        (self.root / 'VERSION').write_text(self.target + '\n')
        with patch.object(install, 'SOURCE', self.root), patch.object(install, 'verify_manifest'), \
                patch.object(install, 'verify_libraries'), patch.object(install, 'ensure_java') as java:
            with self.assertRaisesRegex(ValueError, 'missing its embedded full update'):
                install.install(argparse.Namespace(check=True))
            java.assert_not_called()

    def test_build_stage_preserves_full_assets_and_compiles_every_shared_source(self):
        self.write_assets()
        (self.root / 'dist/assessment-collector.zip').write_bytes(self.embedded)
        for directory in ('shared', 'server', 'web', 'lib'):
            (self.root / directory).mkdir(exist_ok=True)
        for name in ('Json.java', 'AgentBundleManifest.java'):
            (self.root / 'shared' / name).write_text('// disposable source\n')
        (self.root / 'server/Server.java').write_text('// disposable source\n')
        for name in ('VERSION', 'BETA_RELEASE.md', 'BETA_MIGRATION.md', 'DatabaseTool.java'):
            (self.root / name).write_text(self.target if name == 'VERSION' else 'disposable fixture')
        for name, data in [('cert.pem', b'public fixture'), ('key.pem', b'fixture key'),
                           ('ca.pem', b'-----BEGIN CERTIFICATE-----\ncustomer fixture\n-----END CERTIFICATE-----\n')]:
            (self.root / name).write_bytes(data)
        stage = self.root / 'installed'; stage.mkdir()
        with patch.object(install, 'SOURCE', self.root), patch.object(install, 'verify_libraries'), \
                patch.object(install, 'command') as command, patch.object(install, 'provision_admin'):
            install.build_stage(stage, Path('/fixture/java'), 'https://fixture.example', 'fixture.example', 443,
                                self.root / 'cert.pem', self.root / 'key.pem', self.root / 'ca.pem', 'disposable password',
                                admin_name='Fixture', admin_email='fixture@example.com')
        self.assertEqual(self.assets, public.distribution_assets(stage, self.target))
        arguments = command.call_args[0][0]
        self.assertIn(str(stage / 'shared/Json.java'), arguments)
        self.assertIn(str(stage / 'shared/AgentBundleManifest.java'), arguments)
        self.assertFalse((stage / 'conf/collector.properties').exists())

    def test_full_dashboard_and_source_archives_are_self_contained(self):
        output = self.root / 'dashboard-installer.zip'
        source = self.root / 'dashboard-source.zip'
        # Exercise actual package assembly, using only this fixture's public assets.
        with patch.object(builder, 'PROJECT', PROJECT), \
                patch.object(builder, 'SOURCE', PROJECT / 'dashboard-installer'), \
                patch.object(builder, 'configured_generic', return_value=(self.generic, self.entries['conf/ca.pem'])), \
                patch.object(builder, 'copy_update_catalog', side_effect=lambda stage: builder.copy_full_catalog(stage, self.generic, self.read)):
            builder.build(output)
            builder.build_source(source)
        for path in (output, source):
            with self.subTest(archive=path.name), zipfile.ZipFile(path) as archive:
                self.assertIsNone(archive.testzip())
                names = archive.namelist()
                self.assertTrue(set(self.assets).issubset(names))
                self.assertIn('shared/AgentBundleManifest.java', names)
                helper = 'dashboard-installer/agent_updates.py' if path == source else 'agent_updates.py'
                self.assertEqual((PROJECT / 'dashboard-installer/agent_updates.py').read_bytes(), archive.read(helper))
                if path == source:
                    self.assertIn('ops/full_agent_release.py', names)
                    self.assertIn('ops/test-agent-migration.sh', names)
                    self.assertIn('docs/validation/agent-migration-baselines.json', names)
                    self.assertIn('docs/validation/full-agent-update.md', names)
                    self.assertIn('docs/runbooks/agent-update-1512-canary.md', names)
                stage = self.root / ('unpacked-' + path.stem)
                archive.extractall(stage)
                install.verify_manifest(stage)
                self.assertEqual(self.assets, public.distribution_assets(stage, self.target))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, default=FIXTURE)
    args, rest = parser.parse_known_args()
    FIXTURE = args.fixture.resolve()
    unittest.main(argv=[sys.argv[0], *rest], verbosity=2)
