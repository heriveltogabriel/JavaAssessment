#!/usr/bin/env python3
"""Exercise full/bridge signing with a disposable key and real original public bytes."""
import argparse
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ops'))
from agent_update_signing import (bundle_entries, bundle_role, ensure_keypair, legacy_bridge_profile,
                                 sha, sign_bridge_release, sign_bundle_release, sign_release)
from unified_agent import build_bridge

checks = 0


def check(value, message):
    global checks
    if not value:
        raise AssertionError(message)
    checks += 1


def reject(operation, message):
    try:
        operation()
    except (RuntimeError, ValueError, zipfile.BadZipFile, OSError, UnicodeError):
        check(True, message)
        return
    raise AssertionError('Accepted ' + message)


def package(path, entries):
    data = {name: value for name, value in entries.items() if name != 'SHA256SUMS'}
    data['SHA256SUMS'] = ''.join(sha(value) + '  ' + name + '\n' for name, value in sorted(data.items())).encode()
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, value in sorted(data.items()):
            archive.writestr(name, value)


def save_assets(root, assets):
    for name, data in assets.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


def java_verify(java, classpath, files, version):
    result = subprocess.run([str(java), '-Xmx96m', '-cp', classpath, 'assessment.AgentBundleManifestTest',
                             str(files / 'manifest.json'), str(files / 'manifest.sig'), str(files.parent.parent / 'agent-update-public.der'),
                             str(files / 'bundle.zip'), version], capture_output=True, text=True, timeout=90)
    check(result.returncode == 0 and 'SIGNED_BUNDLE_VERIFIED=' in result.stdout, 'Java 6 bytecode verifier accepts independently built Python ZIP: ' + result.stderr)


def run(args):
    os.umask(0o077)
    fixture = Path(args.fixture).resolve()
    contract = json.loads((fixture / 'fixture.json').read_text())
    baseline = (fixture / contract['baselineJar']).read_bytes()
    check(sha(baseline) == contract['baselineSha256'], 'Original binary fixture verified')
    java = args.java or (str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java'))
    output = Path(args.output).absolute()
    check(not output.exists(), 'Use fresh test output')
    output.mkdir(parents=True, mode=0o700)
    signer = output / 'isolated-signing-root'
    key, public = ensure_keypair(signer, create=True)
    check(key.is_relative_to(output), 'Signing private key stays under disposable test output')
    check(sha(public) != sha((fixture / 'baseline/update-public.der').read_bytes()), 'Disposable test pin differs from original production public pin')
    entries = {path.relative_to(fixture / 'root').as_posix(): path.read_bytes() for path in (fixture / 'root').rglob('*') if path.is_file()}
    entries['update-public.der'] = public
    original = output / 'origin.zip'
    package(original, entries)
    original_hash = sha(original.read_bytes())
    version = contract['fromVersion']
    normal = sign_bundle_release(signer, original, version)
    save_assets(output / 'normal', normal)
    directory = output / 'normal/dist/agent-bundles' / version
    signed = json.loads((directory / 'manifest.json').read_text())
    content = bundle_entries(directory / 'bundle.zip')
    check(signed['type'] == 'ASSESSMENT_AGENT_BUNDLE_V1' and signed['minUpdater'] == 3, 'Full V3 protocol declared')
    check(signed['bytes'] == (directory / 'bundle.zip').stat().st_size and signed['sha256'] == sha((directory / 'bundle.zip').read_bytes()), 'Full bundle size/hash pinned')
    check(set(signed['files']) == set(content), 'Signed exact bundle inventory')
    for name, data in content.items():
        spec = signed['files'][name]
        check(spec == {'bytes': len(data), 'sha256': sha(data), 'mode': '700' if name.endswith('.sh') else '600', 'role': bundle_role(name)}, 'Signed roles/modes/hash ' + name)
    check(content['code/collector.jar'] == baseline == content['manager/helper.jar'], 'Code and manager bytes explicit')
    check(not any(name.startswith(('conf/', 'trust/')) or name.endswith('.pem') or name.endswith('.der') for name in content), 'No active configuration/CA/identity/trust pin in bundle')
    check(not any(name.startswith('bootstrap/') for name in content), 'Normal update leaves bootstrap outside its bundle')
    bootstrap = {'anchor.jar': baseline, 'root-trust.sh': entries['root-trust.sh'], 'update-bootstrap.sh': b'#!/bin/sh\n# isolated fixture only\nexit 0\n',
                 **{name: data for name, data in entries.items() if name.startswith('lib/')}}
    initial = sign_bundle_release(signer, original, version, bootstrap)
    save_assets(output / 'initial', initial)
    initial_directory = output / 'initial/dist/agent-bundles' / version
    initial_content = bundle_entries(initial_directory / 'bundle.zip')
    check(all(initial_content['bootstrap/' + name] == value for name, value in bootstrap.items()), 'Initial bundle carries complete explicit bootstrap')
    reject(lambda: sign_bundle_release(signer, original, version, {'anchor.jar': baseline}), 'partial bootstrap')
    polluted = output / 'private-config.zip'
    package(polluted, {**entries, 'conf/collector.properties': b'customer identity must not be bundled'})
    reject(lambda: sign_bundle_release(signer, polluted, version), 'installed identity in generic package')
    wrong = output / 'wrong-pin.zip'
    package(wrong, {**entries, 'update-public.der': b'wrong pin'})
    reject(lambda: sign_bundle_release(signer, wrong, version), 'trust pin substitution')
    # Preserve the legacy signing API and format unchanged.
    legacy = sign_release(signer, original, version)
    legacy_manifest = json.loads(legacy['dist/agent-updates/' + version + '/manifest.json'])
    check(legacy_manifest['type'] == 'ASSESSMENT_AGENT_UPDATE_V1' and legacy_manifest['format'] == 'collector-jar-v1'
          and legacy_manifest['minUpdater'] == 2, 'Existing V1 signer output unchanged')
    java_verify(java, args.classpath, directory, version)
    java_verify(java, args.classpath, initial_directory, version)
    for case, name, mode in [('traversal', '../outside.sh', stat.S_IFREG | 0o700),
                             ('absolute', '/code/run.sh', stat.S_IFREG | 0o700),
                             ('symlink', 'code/run.sh', stat.S_IFLNK | 0o700),
                             ('fifo', 'code/run.sh', stat.S_IFIFO | 0o700),
                             ('mode', 'code/run.sh', stat.S_IFREG | 0o777)]:
        malformed = output / ('bad-' + case + '.zip')
        with zipfile.ZipFile(malformed, 'w') as archive:
            info = zipfile.ZipInfo(name)
            info.create_system = 3
            info.external_attr = mode << 16
            archive.writestr(info, b'fixture')
        reject(lambda: bundle_entries(malformed), 'ZIP ' + case)
    duplicate = output / 'bad-duplicate.zip'
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(duplicate, 'w') as archive:
            archive.writestr('code/run.sh', b'one')
            archive.writestr('code/run.sh', b'two')
    reject(lambda: bundle_entries(duplicate), 'duplicate ZIP paths')
    reject(lambda: legacy_bridge_profile(original, '0' * 64), 'unpinned original bridge package')
    # The checkout is read-only during the bridge compilation override.
    source_hashes = {path: sha(path.read_bytes()) for directory in ('shared', 'collector') for path in (ROOT / directory).glob('*.java')}
    bridge = build_bridge(ROOT, output / 'bridge-build', original, '9.0.1', '9.0.2', Path(java), original_hash)
    check(all(sha(path.read_bytes()) == digest for path, digest in source_hashes.items()), 'Bridge version override never mutates product source')
    bridge_assets = sign_bridge_release(signer, original, bridge, '9.0.1', '9.0.2', original_hash, 1)
    save_assets(output / 'bridge-signed', bridge_assets)
    bridge_name = next(name for name in bridge_assets if name.endswith('/manifest.json'))
    bridge_manifest = json.loads(bridge_assets[bridge_name])
    identity = bridge_name.split('/')[-2]
    check(identity == sha(bridge_assets[bridge_name]), 'Immutable bridge ID is exact signed manifest hash')
    check(bridge_manifest['minUpdater'] == 1 and bridge_manifest['format'] == 'collector-jar-v1', 'Original V1 worker receives its existing format')
    with zipfile.ZipFile(bridge) as archive:
        metadata = json.loads(archive.read('migration/bridge.json'))
        check(archive.read(metadata['recoveryResource']) == baseline and metadata['fromCollectorSha256'] == sha(baseline), 'Bridge embeds exact original post-ACK baseline')
        check(metadata['supportFiles'] == bridge_manifest['requiredFiles'] and metadata['allowedHelperHashes'] == [sha(baseline)], 'Bridge recovery pins exact support and known helper')
        check(all(int.from_bytes(archive.read(name)[6:8], 'big') == 50 for name in archive.namelist() if name.endswith('.class')), 'All bridge classes major 50')
    reject(lambda: sign_bridge_release(signer, original, bridge, '9.0.1', '9.0.1', original_hash, 1), 'non-monotonic final route')
    reject(lambda: sign_bridge_release(signer, original, bridge, '9.0.1', '9.0.2', '0' * 64, 1), 'changed original provenance')
    evidence = {'testOnly': True, 'productionPrivateKeyRead': False, 'checks': checks,
                'bundleVersion': version, 'bridgeVersion': '9.0.1', 'targetVersion': '9.0.2',
                'java6BytecodeVerified': True, 'realJava6RuntimeVerified': False,
                'linuxServiceHandoffVerified': False, 'fullBundleFiles': len(content)}
    (output / 'validation.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print('Agent bundle signing PASS ' + str(checks) + ' checks; exclusive test key; evidence ' + str(output / 'validation.json'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', default='tmp/agent-migration/fixture')
    parser.add_argument('--output', default='tmp/agent-migration/bundle-signing-fixture')
    parser.add_argument('--java')
    parser.add_argument('--classpath', default='tmp/agent-migration/bundle-classes')
    run(parser.parse_args())
