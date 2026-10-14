#!/usr/bin/env python3
"""Build isolated original-worker migration fixtures; never download or read a product private key.

Use --legacy-release RELEASE_DIR, or set ASSESSMENT_LEGACY_RELEASES to the local
directory containing historical release folders. Missing or changed baselines
fail closed. The bridge is test-only; platform probes are simulated by the
separate LegacyAgentMigrationTest harness.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PINS = ROOT / 'docs/validation/agent-migration-baselines.json'
BRIDGE_VERSION = '9.0.1'
RECOVERY_RESOURCE = 'migration/baseline/collector.jar'
ASSET = re.compile(r'(?:[a-zA-Z0-9_-]+[.]sh|lib/[a-zA-Z0-9_.-]+[.]jar|update-public[.]der)')
MANIFEST_FIELDS = {'schema', 'type', 'version', 'format', 'minUpdater', 'sha256', 'bytes', 'requiredFiles'}
MAX_JAR = 32 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode('utf-8')


def command(args):
    result = subprocess.run(list(map(str, args)), capture_output=True, timeout=120)
    require(result.returncode == 0, 'Fixture command failed (' + str(args[0]) + '): ' + result.stderr.decode(errors='replace')[-2000:])
    return result.stdout


def checked_bytes(path, digest, size=None):
    require(path.is_file() and not path.is_symlink(), 'Original baseline unavailable or linked: ' + str(path))
    data = path.read_bytes()
    require(sha(data) == digest and (size is None or len(data) == size), 'Original baseline checksum/size mismatch: ' + path.name)
    return data


def zip_entries(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        require(len(entries) <= 512 and len(entries) == len({item.filename for item in entries}), 'Duplicate/oversized baseline inventory')
        require(sum(item.file_size for item in entries) <= 128 * 1024 * 1024, 'Expanded baseline exceeds bound')
        for item in entries:
            path = Path(item.filename)
            mode = item.external_attr >> 16
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in item.filename,
                    'Unsafe baseline archive path')
            require(not item.is_dir() and stat.S_IFMT(mode) in (0, stat.S_IFREG), 'Non-regular baseline archive entry')
        return {item.filename: archive.read(item) for item in entries}


def jar_version(data):
    entries = zip_entries(data)
    versions = [line.partition(': ')[2] for line in entries['META-INF/MANIFEST.MF'].decode('utf-8').splitlines()
                if line.startswith('Implementation-Version: ')]
    require(len(versions) == 1, 'Invalid JAR version metadata')
    return versions[0], entries


def write_private(path, data, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.write_bytes(data)
    path.chmod(0o700 if executable else 0o600)


def original_release(release, version, pin, scratch):
    packages = release / 'packages'
    published = json.loads(checked_bytes(packages / 'package-manifest.json', pin['packageManifestSha256']))
    require(published.get('dashboardVersion') == version == published.get('agentVersion')
            and published.get('agentUpdateSigned') is True, 'Original package metadata differs from pinned release')
    artifacts = {}
    for name, expected in pin['artifacts'].items():
        listed = [item for item in published['artifacts'] if item['file'] == 'packages/' + name]
        require(len(listed) == 1 and listed[0]['sha256'] == expected['sha256'] and listed[0]['bytes'] == expected['bytes'],
                'Original artifact inventory differs from independent pins')
        artifacts[name] = checked_bytes(packages / name, expected['sha256'], expected['bytes'])
    entries = zip_entries(artifacts['assessment-collector-' + version + '.zip'])
    checksums = {}
    for line in entries['SHA256SUMS'].decode('ascii').splitlines():
        digest, name = line.split('  ', 1)
        require(name not in checksums and re.fullmatch('[a-f0-9]{64}', digest), 'Invalid package checksum inventory')
        checksums[name] = digest
    require(set(checksums) == set(entries) - {'SHA256SUMS'}
            and all(sha(entries[name]) == digest for name, digest in checksums.items()), 'Original package checksum mismatch')
    payload = entries['collector.jar']
    require(sha(payload) == pin['collectorJarSha256'] and len(payload) == pin['collectorJarBytes'], 'Original collector differs from pin')
    actual_version, classes = jar_version(payload)
    require(actual_version == version and sha(classes['assessment/AgentUpdateWorker.class']) == pin['workerClassSha256'],
            'Original worker/version differs from pinned binary')
    majors = {int.from_bytes(data[6:8], 'big') for name, data in classes.items() if name.endswith('.class')}
    require(majors == {pin['classfileMajor']}, 'Original collector is not uniformly Java 6 bytecode')
    with zipfile.ZipFile(io.BytesIO(artifacts['assessment-dashboard-runtime-' + version + '.zip'])) as runtime:
        prefix = 'dist/agent-updates/' + version + '/'
        manifest = runtime.read(prefix + 'manifest.json')
        signature = runtime.read(prefix + 'manifest.sig')
        require(runtime.read(prefix + 'collector.jar') == payload, 'Runtime/update and installer collectors differ')
        public = runtime.read('dist/agent-update-public.der')
    require(sha(manifest) == pin['manifestSha256'] and sha(signature) == pin['signatureSha256']
            and sha(public) == pin['publicKeySha256'] and entries['update-public.der'] == public,
            'Original signed update artifacts differ from pins')
    support = {name: sha(data) for name, data in sorted(entries.items()) if ASSET.fullmatch(name)}
    require(len(support) == pin['supportFiles'] and 'legacy-support-sha256:' + sha(canonical(support)) == pin['supportProfileId'],
            'Original support profile differs from pin')
    signed = json.loads(manifest)
    require(set(signed) == MANIFEST_FIELDS and signed == {
        'schema': 1, 'type': 'ASSESSMENT_AGENT_UPDATE_V1', 'version': version,
        'format': pin['format'], 'minUpdater': pin['minUpdater'], 'sha256': sha(payload),
        'bytes': len(payload), 'requiredFiles': support}, 'Original signed manifest differs from binary/support')
    write_private(scratch / 'public.der', public)
    write_private(scratch / 'manifest.json', manifest)
    write_private(scratch / 'signature.bin', signature)
    pem = command(['openssl', 'pkey', '-pubin', '-inform', 'DER', '-in', scratch / 'public.der', '-pubout'])
    write_private(scratch / 'public.pem', pem)
    command(['openssl', 'dgst', '-sha256', '-verify', scratch / 'public.pem', '-signature', scratch / 'signature.bin', scratch / 'manifest.json'])
    return entries, manifest, signature


def bridge_jar(version, original, profile, classes):
    metadata = {'schema': 1, 'testOnly': True, 'fromVersion': version, 'fromCollectorSha256': sha(original),
                'supportProfileId': profile, 'bridgeVersion': BRIDGE_VERSION,
                'recoveryResource': RECOVERY_RESOURCE, 'platformProbes': 'simulated-by-harness'}
    files = {'META-INF/MANIFEST.MF': ('Manifest-Version: 1.0\nImplementation-Version: ' + BRIDGE_VERSION + '\n\n').encode(),
             RECOVERY_RESOURCE: original, 'migration/profile.json': canonical(metadata) + b'\n'}
    for path in sorted(classes.rglob('*.class')):
        data = path.read_bytes()
        require(data[:4] == b'\xca\xfe\xba\xbe' and int.from_bytes(data[6:8], 'big') == 50, 'Bridge marker must be major 50')
        files[path.relative_to(classes).as_posix()] = data
    require(any(name.endswith('.class') for name in files), 'Bridge fixture marker missing')
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100600 << 16
            archive.writestr(info, data)
    payload = stream.getvalue()
    require(0 < len(payload) <= MAX_JAR and jar_version(payload)[0] == BRIDGE_VERSION, 'Bridge fixture exceeds legacy JAR constraints')
    return payload


def build(args):
    os.umask(0o077)
    pins = json.loads(PINS.read_text(encoding='utf-8'))
    require(args.from_version in pins['baselines'], 'No independently pinned baseline for requested version')
    pin = pins['baselines'][args.from_version]
    fallback = ROOT.parent / 'jms-teste-macos/assessment-pilot/output/dashboard-releases'
    releases = Path(os.environ.get('ASSESSMENT_LEGACY_RELEASES', str(fallback)))
    release = Path(args.legacy_release) if args.legacy_release else releases / args.from_version
    output = Path(args.output).absolute()
    require(not output.exists() and not output.is_symlink(), 'Fixture output already exists; choose a new destination rather than overwriting evidence')
    java = args.java or (str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java'))
    ecj = Path(args.ecj or os.environ.get('ASSESSMENT_ECJ_JAR', str(ROOT / 'tools/ecj-3.33.0.jar')))
    require(java and ecj.is_file(), 'Set JAVA_HOME and ASSESSMENT_ECJ_JAR to local Java/ECJ tools; no compiler is downloaded')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='migration-fixture-build-', dir=output.parent) as temp:
        scratch = Path(temp)
        original, original_manifest, original_signature = original_release(release, args.from_version, pin, scratch)
        stage = scratch / 'fixture'
        stage.mkdir(mode=0o700)
        baseline = stage / 'baseline'
        for name, data in {'collector.jar': original['collector.jar'], 'update-public.der': original['update-public.der'],
                           'manifest.json': original_manifest, 'signature.bin': original_signature}.items():
            write_private(baseline / name, data)
        key = stage / 'test-signing/private-key.pem'
        key.parent.mkdir(mode=0o700)
        command(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072', '-out', key])
        key.chmod(0o600)
        public = command(['openssl', 'pkey', '-in', key, '-pubout', '-outform', 'DER'])
        require(sha(public) != pin['publicKeySha256'], 'Test signing key must differ from the original trust pin')
        write_private(key.parent / 'public.der', public)
        test_pem = command(['openssl', 'pkey', '-in', key, '-pubout'])
        write_private(key.parent / 'public.pem', test_pem)
        copied = {**original, 'update-public.der': public}
        copied.pop('SHA256SUMS')
        copied['SHA256SUMS'] = ''.join(sha(data) + '  ' + name + '\n' for name, data in sorted(copied.items())).encode('ascii')
        for name, data in copied.items():
            write_private(stage / 'root' / name, data, name.endswith('.sh'))
        write_private(stage / 'home/helper.jar', original['collector.jar'])
        for name in ('root-trust.sh', 'lifecycle-lock.sh', 'update-local.sh', 'update-service.sh', 'update-public.der'):
            write_private(stage / 'home' / name, copied[name])
        for name, data in copied.items():
            if name.startswith('lib/'):
                write_private(stage / 'home' / name, data)
        source = scratch / 'AgentMigrationBridgeFixture.java'
        source.write_text('package assessment; public final class AgentMigrationBridgeFixture { '
                          'public static final String VERSION = "9.0.1"; '
                          'public static void main(String[] args) { '
                          'System.out.println("TEST_ONLY_BRIDGE_MARKER=9.0.1; PROBES_SIMULATED_BY_HARNESS"); } }\n', encoding='utf-8')
        classes = scratch / 'classes'
        classes.mkdir()
        command([java, '-jar', ecj, '-source', '1.6', '-target', '1.6', '-nowarn', '-d', classes, source])
        payload = bridge_jar(args.from_version, original['collector.jar'], pin['supportProfileId'], classes)
        write_private(stage / 'bridge/collector.jar', payload)
        required = {name: sha(data) for name, data in sorted(copied.items()) if ASSET.fullmatch(name)}
        require({name for name in required if required[name] != json.loads(original_manifest)['requiredFiles'][name]} == {'update-public.der'},
                'Fixture changed legacy support beyond its test trust pin')
        manifest = {'schema': 1, 'type': 'ASSESSMENT_AGENT_UPDATE_V1', 'version': BRIDGE_VERSION,
                    'format': 'collector-jar-v1', 'minUpdater': 1, 'sha256': sha(payload),
                    'bytes': len(payload), 'requiredFiles': required}
        raw = canonical(manifest) + b'\n'
        require(set(manifest) == MANIFEST_FIELDS and len(raw) <= 65536, 'Fixture differs from strict legacy manifest schema')
        write_private(stage / 'bridge/manifest.json', raw)
        signature = command(['openssl', 'dgst', '-sha256', '-sign', key, stage / 'bridge/manifest.json'])
        require(len(signature) == 384, 'Test RSA signature is not 3072 bits')
        write_private(stage / 'bridge/signature.bin', signature)
        command(['openssl', 'dgst', '-sha256', '-verify', key.parent / 'public.pem', '-signature', stage / 'bridge/signature.bin', stage / 'bridge/manifest.json'])
        contract = {'schema': 1, 'testOnly': True, 'fromVersion': args.from_version, 'bridgeVersion': BRIDGE_VERSION,
                    'baselineJar': 'baseline/collector.jar', 'bridgeJar': 'bridge/collector.jar',
                    'manifest': 'bridge/manifest.json', 'signature': 'bridge/signature.bin', 'root': 'root', 'home': 'home',
                    'recoveryResource': RECOVERY_RESOURCE, 'baselineSha256': pin['collectorJarSha256'],
                    'workerClassSha256': pin['workerClassSha256'], 'supportProfileId': pin['supportProfileId'],
                    'bridgeSha256': sha(payload), 'bridgeBytes': len(payload), 'bridgeClassfileMajor': 50,
                    'testPublicKeySha256': sha(public), 'testSignatureBytes': len(signature),
                    'originalSignatureVerified': True, 'originalPackageHashesVerified': True,
                    'originalWorkerRecompiled': False, 'productionPrivateKeyRead': False,
                    'platformProbes': 'simulated-by-harness', 'productBootstrapImplemented': False,
                    'linuxServiceHandoffVerified': False, 'realJava6RuntimeVerified': False}
        write_private(stage / 'fixture.json', json.dumps(contract, indent=2).encode() + b'\n')
        stage.rename(output)
    print('Original ' + args.from_version + ' fixture ready: ' + str(output))
    print('Original package/signature verified; isolated RSA-3072 pin; bridge 9.0.1 major 50 with original baseline embedded.')
    print('TEST ONLY: probes/service actions simulated by harness; production bootstrap and Linux handoff remain unverified.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--from-version', default='1.5.12')
    parser.add_argument('--output', default='tmp/agent-migration/fixture')
    parser.add_argument('--legacy-release', help='Local release folder containing packages/; never downloaded')
    parser.add_argument('--java', help='Local java executable used only to run ECJ')
    parser.add_argument('--ecj', help='Local compiler supporting Java 6 bytecode')
    parsed = parser.parse_args()
    try:
        build(parsed)
    except (RuntimeError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, 'Fixture build refused: ' + str(error) + '\n')
