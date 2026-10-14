#!/usr/bin/env python3
"""Build complete real-code HTTP/lifecycle fixtures with a private test-only RSA pin.

Requires the independently verified local WF0 fixture. Does not download, touch
VMs, install services or read the product signing identity. Source version
overrides are confined to temporary compilation copies.
"""
import argparse
import io
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ops'))
from agent_update_signing import (ensure_keypair, legacy_bridge_profile, package_entries, sha,
                                 sign_bridge_release, sign_bundle_release, sign_release, _signed_bytes)
from unified_agent import build_bridge, compile_product
from full_agent_release import embed_full_update, inherited_bootstrap


def require(value, message):
    if not value:
        raise RuntimeError(message)


def package(path, entries):
    entries = {name: data for name, data in entries.items() if name != 'SHA256SUMS'}
    entries['SHA256SUMS'] = ''.join(sha(data) + '  ' + name + '\n' for name, data in sorted(entries.items())).encode()
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            archive.writestr(name, data)


def jar_bytes(classes, version):
    files = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob('*.class')}
    require(files and all(int.from_bytes(data[6:8], 'big') == 50 for data in files.values()), 'Fixture product code must be major 50')
    files['META-INF/MANIFEST.MF'] = ('Manifest-Version: 1.0\nImplementation-Version: ' + version + '\n\n').encode()
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100600 << 16
            archive.writestr(info, data)
    return stream.getvalue()


def target_jar(scratch, java, version):
    sources = scratch / 'target-sources'
    sources.mkdir()
    staged = []
    original = {p: p.read_bytes() for p in [*sorted((ROOT / 'shared').glob('*.java')), *[p for p in sorted((ROOT / 'collector').glob('*.java')) if not p.stem.endswith('Test')]]}
    for path, data in original.items():
        if path.name == 'AgentTransport.java':
            text, count = re.subn(r'static final String VERSION = "[^"]+";', 'static final String VERSION = "' + version + '";', data.decode())
            require(count == 1, 'Ambiguous staged target version')
            data = text.encode()
        copy = sources / path.name
        copy.write_bytes(data)
        staged.append(copy)
    classes = scratch / 'target-classes'
    classes.mkdir()
    compile_product(ROOT, classes, Path(java), staged)
    require(all(path.read_bytes() == data for path, data in original.items()), 'Source changed during fixture compilation')
    return jar_bytes(classes, version)


def save(root, assets):
    for name, data in assets.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        target.write_bytes(data)
        target.chmod(0o600)


def build(args):
    os.umask(0o077)
    source = Path(args.legacy_fixture).resolve()
    contract = json.loads((source / 'fixture.json').read_text())
    original = (source / contract['baselineJar']).read_bytes()
    require(contract['fromVersion'] == '1.5.12' and sha(original) == contract['baselineSha256'], 'WF0 original baseline differs')
    original_entries = {p.relative_to(source / 'root').as_posix(): p.read_bytes() for p in (source / 'root').rglob('*') if p.is_file()}
    require(original_entries['collector.jar'] == original, 'WF0 copied root is no longer original')
    # Verify every WF0 source file, including its isolated pin, before repinning.
    checksums = dict((name, digest) for digest, name in (line.split('  ', 1) for line in original_entries['SHA256SUMS'].decode().splitlines()))
    require(set(checksums) == set(original_entries) - {'SHA256SUMS'}
            and all(sha(original_entries[name]) == digest for name, digest in checksums.items()), 'WF0 support fixture changed')
    output = Path(args.output).absolute()
    require(not output.exists() and not output.is_symlink(), 'Use fresh full-update fixture output')
    output.parent.mkdir(parents=True, exist_ok=True)
    java = args.java or (str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java'))
    require(java, 'A local Java runtime is required')
    if args.future_version:
        require(re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', args.future_version)
                and tuple(map(int, args.future_version.split('.'))) > tuple(map(int, args.target_version.split('.'))), 'Future fixture version must be newer than target')
    with tempfile.TemporaryDirectory(prefix='full-update-fixture-', dir=output.parent) as temp:
        scratch = Path(temp)
        stage = scratch / 'fixture'
        stage.mkdir(mode=0o700)
        signer = stage / 'test-signing-root'
        key, public = ensure_keypair(signer, create=True)
        require(sha(public) != sha((source / 'baseline/update-public.der').read_bytes()), 'Test key must not equal production pin')
        original_entries['update-public.der'] = public
        origin_zip = stage / 'assessment-collector-original-1.5.12.zip'
        package(origin_zip, original_entries)
        origin_hash = sha(origin_zip.read_bytes())
        reviewed_origin, profile = legacy_bridge_profile(origin_zip, origin_hash)
        payload = target_jar(scratch, java, args.target_version)
        current_entries = {**original_entries, 'collector.jar': payload}
        for path in sorted((ROOT / 'collector').glob('[a-z]*.sh')):
            current_entries[path.name] = path.read_bytes()
        dependencies = json.loads(current_entries['DEPENDENCIES.json'])
        dependencies['version'] = args.target_version
        current_entries['DEPENDENCIES.json'] = (json.dumps(dependencies, indent=2) + '\n').encode()
        current_zip = stage / 'dist/assessment-collector.zip'
        current_zip.parent.mkdir(mode=0o700)
        package(current_zip, current_entries)
        bootstrap = {'anchor.jar': payload, 'root-trust.sh': (ROOT / 'collector/root-trust.sh').read_bytes(),
                     'update-bootstrap.sh': (ROOT / 'collector/update-bootstrap.sh').read_bytes(),
                     **{name: data for name, data in current_entries.items() if name.startswith('lib/')}}
        require(b'assessment.AgentMigrationBootstrap' in bootstrap['update-bootstrap.sh'], 'WF2 bootstrap entrypoint unavailable')
        bundles = sign_bundle_release(signer, current_zip, args.target_version, bootstrap)
        save(stage, bundles)
        save(stage, sign_release(signer, current_zip, args.target_version))
        current_zip.write_bytes(embed_full_update(current_zip, bundles, args.target_version))
        bridge = build_bridge(ROOT, stage, origin_zip, args.bridge_version, args.target_version, Path(java), origin_hash)
        bridges = sign_bridge_release(signer, origin_zip, bridge, args.bridge_version, args.target_version, origin_hash, 1)
        save(stage, bridges)
        bridge_manifest_path = next(name for name in bridges if name.endswith('/manifest.json'))
        bridge_identity = bridge_manifest_path.split('/')[-2]
        route = {'id': bridge_identity, 'fromVersion': profile['fromVersion'], 'fromCollectorSha256': profile['fromCollectorSha256'],
                 'supportProfileId': profile['supportProfileId'], 'minUpdater': 1, 'bridgeVersion': args.bridge_version,
                 'bridgeManifestSha256': bridge_identity}
        bundle_manifest_path = 'dist/agent-bundles/' + args.target_version + '/manifest.json'
        index = {'schema': 1, 'type': 'ASSESSMENT_AGENT_ROUTES_V1', 'targetVersion': args.target_version,
                 'minimumAgentVersion': '1.5.12', 'bundleManifestSha256': sha(bundles[bundle_manifest_path]), 'routes': [route]}
        raw = (json.dumps(index, sort_keys=True, separators=(',', ':')) + '\n').encode()
        prefix = 'dist/agent-update-routes/' + args.target_version + '/'
        save(stage, {prefix + 'routes.json': raw, prefix + 'routes.sig': _signed_bytes(key, public, raw)})
        for dirname, installed in (('legacy-root', original), ('root', bridge.read_bytes())):
            for name, data in reviewed_origin.items():
                path = stage / dirname / name
                path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                path.write_bytes(installed if name == 'collector.jar' else data)
                path.chmod(0o700 if name.endswith('.sh') else 0o600)
            save(stage / dirname, {'conf/collector.properties': b'hostId=fixture-host\nagentToken=isolated-test-token\nserverUrl=http://127.0.0.1:1\nspoolDirectory=/opt/java-assessment-collector/collector-spool\n',
                                   'conf/root-service-mode': b'system-root\n', 'conf/update-capability': b'update-v1\n',
                                   'conf/java.path': (str(java) + '\n').encode()})
            (stage / dirname / 'collector-spool').mkdir(mode=0o700)
            (stage / dirname / 'logs').mkdir(mode=0o700)
        save(stage / 'home', {'helper.jar': original, 'java.path': (str(java) + '\n').encode(), 'scheduler': b'sysv\n',
                              **{name: reviewed_origin[name] for name in ('root-trust.sh', 'lifecycle-lock.sh', 'update-local.sh', 'update-service.sh', 'update-public.der')},
                              **{name: data for name, data in reviewed_origin.items() if name.startswith('lib/')}})
        summary = {'schema': 1, 'testOnly': True, 'productionPrivateKeyRead': False, 'fromVersion': profile['fromVersion'],
                   'targetVersion': args.target_version, 'bridgeVersion': args.bridge_version, 'bridgeId': bridge_identity,
                   'supportProfileId': profile['supportProfileId'], 'fromCollectorSha256': profile['fromCollectorSha256'],
                   'bundleManifestSha256': index['bundleManifestSha256'], 'root': 'root', 'legacyRoot': 'legacy-root', 'home': 'home',
                   'genericInstaller': 'dist/assessment-collector.zip', 'originalPackage': origin_zip.name,
                   'bridgeJar': bridge_manifest_path.rsplit('/', 1)[0] + '/collector.jar',
                   'manifest': bundle_manifest_path, 'signature': 'dist/agent-bundles/' + args.target_version + '/manifest.sig',
                   'bundle': 'dist/agent-bundles/' + args.target_version + '/bundle.zip', 'pin': 'dist/agent-update-public.der',
                   'managerSha256': sha(payload),
                   'routes': prefix + 'routes.json', 'testPublicKeySha256': sha(public),
                   'java6BytecodeVerified': True, 'realJava6RuntimeVerified': False, 'linuxServiceHandoffVerified': False}
        save(stage, {'fixture.json': (json.dumps(summary, indent=2) + '\n').encode()})
        if args.future_version:
            future_scratch = scratch / 'future'
            future_scratch.mkdir()
            future_payload = target_jar(future_scratch, java, args.future_version)
            future_entries = {**current_entries, 'collector.jar': future_payload}
            future_entries['launcher-common.sh'] += ('\n# Isolated evolution fixture ' + args.future_version + '\n').encode()
            marker_source = future_scratch / 'FullUpdateEvolutionMarker.java'
            marker_source.write_text('package assessment.fixture; public final class FullUpdateEvolutionMarker { '
                                     'public static final String VERSION = "' + args.future_version + '"; }\n')
            marker_classes = future_scratch / 'marker-classes'
            marker_classes.mkdir()
            compile_product(ROOT, marker_classes, Path(java), [marker_source])
            future_entries['lib/upgrade-fixture.jar'] = jar_bytes(marker_classes, args.future_version)
            future_dependencies = json.loads(future_entries['DEPENDENCIES.json'])
            future_dependencies['version'] = args.future_version
            future_entries['DEPENDENCIES.json'] = (json.dumps(future_dependencies, indent=2) + '\n').encode()
            future_zip = stage / 'dist/assessment-collector-future.zip'
            package(future_zip, future_entries)
            inherited = inherited_bootstrap(package_entries(current_zip), public)
            require(inherited == bootstrap, 'Public T installer must prove the original immutable anchor for U')
            future_assets = sign_bundle_release(signer, future_zip, args.future_version, inherited)
            save(stage, future_assets)
            save(stage, sign_release(signer, future_zip, args.future_version))
            future_zip.write_bytes(embed_full_update(future_zip, future_assets, args.future_version))
            future_prefix = 'dist/agent-bundles/' + args.future_version + '/'
            future_manifest = json.loads(future_assets[future_prefix + 'manifest.json'])
            future_index = {'schema': 1, 'type': 'ASSESSMENT_AGENT_ROUTES_V1', 'targetVersion': args.future_version,
                            'minimumAgentVersion': '1.5.12', 'bundleManifestSha256': sha(future_assets[future_prefix + 'manifest.json']), 'routes': []}
            future_raw = (json.dumps(future_index, sort_keys=True, separators=(',', ':')) + '\n').encode()
            future_routes = 'dist/agent-update-routes/' + args.future_version + '/'
            save(stage, {future_routes + 'routes.json': future_raw, future_routes + 'routes.sig': _signed_bytes(key, public, future_raw)})
            summary.update({'futureVersion': args.future_version, 'futureManifest': future_prefix + 'manifest.json',
                            'futureSignature': future_prefix + 'manifest.sig', 'futureBundle': future_prefix + 'bundle.zip',
                            'futureManagerSha256': sha(future_payload), 'futureBundleSha256': future_manifest['sha256'],
                            'futureGenericInstaller': 'dist/assessment-collector-future.zip',
                            'futureRoutes': future_routes + 'routes.json', 'futureChanges': ['collector', 'manager', 'launcher-common.sh', 'lib/upgrade-fixture.jar'],
                            'bootstrapPreservedFromTarget': True, 'futureDirectV3Only': True})
            save(stage, {'fixture.json': (json.dumps(summary, indent=2) + '\n').encode()})
        stage.rename(output)
    print('Full update fixture ready: ' + str(output) + '; original 1.5.12 -> bridge ' + args.bridge_version + ' -> target ' + args.target_version)
    print('All product classes major50, isolated RSA-3072 signatures; no production signing or Linux service/reboot claim.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--legacy-fixture', default='tmp/agent-migration/fixture')
    parser.add_argument('--output', default='tmp/full-update/http-fixture')
    parser.add_argument('--target-version', default='2.0.5')
    parser.add_argument('--bridge-version', default='2.0.4')
    parser.add_argument('--future-version', default='2.0.6', help='Isolated direct-V3 evolution release; empty string disables it')
    parser.add_argument('--java')
    build(parser.parse_args())
