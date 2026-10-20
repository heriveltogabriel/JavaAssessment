"""Offline release signing. Only the public key and signed Java payload leave this Mac."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import struct
import subprocess
import tempfile
import zipfile

MAX_JAR = 32 * 1024 * 1024
# One helper hash per prepared release since 1.5.8; 2.1.6 was the 33rd.
MAX_HELPER_BASELINES = 64
VERSION = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')
ASSET = re.compile(r'(?:[a-z][a-z0-9-]*\.sh|lib/[A-Za-z0-9_.-]+\.jar|update-public\.der)')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def validate_helper_hashes(helpers, required):
    require(isinstance(helpers, list) and 0 < len(helpers) <= MAX_HELPER_BASELINES
            and all(isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) for value in helpers)
            and len(helpers) == len(set(helpers)) and required in helpers,
            'Bridge helper allowlist must contain bounded explicit known binary hashes')


def openssl(*args):
    result = subprocess.run(['openssl', *map(str, args)], capture_output=True, timeout=120)
    require(result.returncode == 0, 'Offline signing command failed: ' + str(args[0]))
    return result.stdout


def private(path, directory=False):
    info = path.lstat()
    require(not stat.S_ISLNK(info.st_mode) and info.st_uid == os.getuid(), 'Untrusted offline signing path')
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode), 'Invalid signing path type')
    require(stat.S_IMODE(info.st_mode) & 0o077 == 0, 'Offline signing material must be private')


def ensure_keypair(root, create=False):
    root = Path(root)
    directory = root / 'output/agent-update-signing'
    key = directory / 'private-key.pem'
    public = root / 'collector/public/agent-update-public.der'
    require(not directory.is_symlink() and not public.is_symlink(), 'Linked signing material refused')
    if not directory.exists():
        require(create and not public.exists(), 'Offline signing key is missing; do not rotate the installed trust key')
        directory.mkdir(mode=0o700, parents=True)
    private(directory, True)
    if not key.exists():
        require(create and not key.is_symlink() and not public.exists(), 'Offline key missing for existing trust key')
        with tempfile.TemporaryDirectory(prefix='generate-', dir=directory) as temporary:
            generated = Path(temporary) / 'private.pem'
            openssl('genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072', '-out', generated)
            generated.chmod(0o600)
            # A hard link refuses overwriting even if another build generated a key concurrently.
            os.link(generated, key)
    private(key)
    derived = openssl('pkey', '-in', key, '-pubout', '-outform', 'DER')
    require(350 <= len(derived) <= 1024, 'Unexpected update signing public key')
    public.parent.mkdir(parents=True, exist_ok=True)
    if public.exists():
        require(public.is_file() and public.read_bytes() == derived, 'Signing key does not match pinned public key')
    else:
        fd = os.open(public, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
        with os.fdopen(fd, 'wb') as output:
            output.write(derived)
    return key, derived


def package_entries(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate generic agent entries')
        require(all(not name.startswith('/') and '..' not in Path(name).parts for name in names), 'Unsafe generic agent path')
        require(sum(i.file_size for i in archive.infolist()) <= 128 * 1024 * 1024, 'Oversized generic package')
        entries = {name: archive.read(name) for name in names}
    require('SHA256SUMS' in entries, 'Generic agent has no checksums')
    expected = {}
    for line in entries['SHA256SUMS'].decode().splitlines():
        digest, name = line.split('  ', 1)
        require(name not in expected and re.fullmatch('[a-f0-9]{64}', digest), 'Invalid generic agent checksum')
        expected[name] = digest
    require(set(expected) == set(entries) - {'SHA256SUMS'}, 'Incomplete generic agent checksum inventory')
    require(all(sha(entries[name]) == digest for name, digest in expected.items()), 'Generic agent checksum mismatch')
    return entries


def validate_jar(data, version):
    require(0 < len(data) <= MAX_JAR, 'Invalid agent JAR size')
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        names = jar.namelist()
        require(len(names) == len(set(names)) and all(not n.startswith('/') and '..' not in Path(n).parts for n in names), 'Unsafe agent JAR')
        require(sum(i.file_size for i in jar.infolist()) <= 64 * 1024 * 1024, 'Expanded JAR limit exceeded')
        versions = [line.split(': ', 1)[1] for line in jar.read('META-INF/MANIFEST.MF').decode().splitlines() if line.startswith('Implementation-Version: ')]
        require(versions == [version], 'Signed agent version differs from binary')
        require('assessment/AgentUpdateProbe.class' in names and 'assessment/AssessmentCollector.class' in names, 'Agent update entrypoints missing')
        for name in names:
            if name.endswith('.class'):
                data = jar.read(name)
                require(data[:4] == b'\xca\xfe\xba\xbe' and int.from_bytes(data[6:8], 'big') <= 50, 'Agent update must preserve Java6 compatibility')


def sign_release(root, agent_path, version):
    require(VERSION.fullmatch(version), 'Stable full agent version required')
    key, public = ensure_keypair(root)
    entries = package_entries(agent_path)
    require(entries.get('update-public.der') == public, 'Agent lacks the pinned update key')
    require(json.loads(entries['DEPENDENCIES.json'])['version'] == version, 'Generic agent metadata version mismatch')
    payload = entries['collector.jar']
    validate_jar(payload, version)
    required = {name: sha(data) for name, data in sorted(entries.items()) if ASSET.fullmatch(name)}
    require(len(required) <= 100 and all(name in required for name in ('root-service.sh', 'root-trust.sh', 'update-install.sh', 'update-service.sh', 'update-local.sh', 'update-public.der')), 'Protected update installation assets missing')
    manifest = {'schema': 1, 'type': 'ASSESSMENT_AGENT_UPDATE_V1', 'version': version,
                'format': 'collector-jar-v1', 'minUpdater': 2, 'sha256': sha(payload),
                'bytes': len(payload), 'requiredFiles': required}
    raw = (json.dumps(manifest, sort_keys=True, separators=(',', ':')) + '\n').encode()
    require(len(raw) <= 32768, 'Manifest exceeds agent bound')
    with tempfile.TemporaryDirectory(prefix='sign-', dir=key.parent) as temporary:
        directory = Path(temporary)
        source = directory / 'manifest.json'; source.write_bytes(raw)
        signature = openssl('dgst', '-sha256', '-sign', key, source)
        sigfile = directory / 'manifest.sig'; sigfile.write_bytes(signature)
        pem = directory / 'public.pem'; pem.write_bytes(openssl('pkey', '-pubin', '-inform', 'DER', '-in', Path(root) / 'collector/public/agent-update-public.der', '-outform', 'PEM'))
        openssl('dgst', '-sha256', '-verify', pem, '-signature', sigfile, source)
    prefix = 'dist/agent-updates/' + version + '/'
    return {'dist/agent-update-public.der': public, prefix + 'manifest.json': raw,
            prefix + 'manifest.sig': signature, prefix + 'collector.jar': payload}


MAX_BUNDLE = 64 * 1024 * 1024
MAX_BUNDLE_EXPANDED = 128 * 1024 * 1024
MAX_BUNDLE_FILES = 256


def bundle_role(name):
    if not isinstance(name, str) or len(name) > 180 or '..' in name:
        return None
    if name == 'code/collector.jar' or re.fullmatch(r'code/[A-Za-z0-9_-]+[.]sh|code/lib/[A-Za-z0-9_.-]+[.]jar', name):
        return 'code'
    if name == 'manager/helper.jar' or re.fullmatch(r'manager/lib/[A-Za-z0-9_.-]+[.]jar', name):
        return 'manager'
    if name in ('bootstrap/anchor.jar', 'bootstrap/root-trust.sh', 'bootstrap/update-bootstrap.sh') or re.fullmatch(r'bootstrap/lib/[A-Za-z0-9_.-]+[.]jar', name):
        return 'bootstrap'
    if re.fullmatch(r'defaults/[A-Za-z0-9_.-]+[.]example', name):
        return 'default'
    return None


def bundle_entries(path):
    """Read only strict, bounded regular bundle files; no extraction or execution."""
    path = Path(path)
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= MAX_BUNDLE, 'Invalid bundle file/size')
    with zipfile.ZipFile(path) as archive, path.open('rb') as source:
        entries = archive.infolist()
        require(0 < len(entries) <= MAX_BUNDLE_FILES and len({item.filename for item in entries}) == len(entries), 'Duplicate/oversized bundle inventory')
        require(sum(item.file_size for item in entries) <= MAX_BUNDLE_EXPANDED, 'Expanded bundle exceeds limit')
        source.seek(-22, os.SEEK_END)
        end = struct.unpack('<4s4H2LH', source.read(22))
        require(end[0] == b'PK\x05\x06' and end[1:3] == (0, 0) and end[3] == end[4] == len(entries)
                and end[7] == 0 and end[5] + end[6] == path.stat().st_size - 22, 'Unsupported bundle ZIP directory')
        offset = 0
        content = {}
        for item in sorted(entries, key=lambda item: item.header_offset):
            role = bundle_role(item.filename)
            mode = 0o700 if item.filename.endswith('.sh') else 0o600
            require(role and item.create_system == 3 and stat.S_IFMT(item.external_attr >> 16) == stat.S_IFREG
                    and (item.external_attr >> 16) & 0o7777 == mode and item.external_attr & 0xffff == 0,
                    'Bundle links, special files and unexpected modes are refused')
            require(not item.is_dir() and not item.extra and not item.comment and item.extract_version <= 20
                    and item.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    and item.flag_bits & ~(8 | 2048) == 0 and item.header_offset == offset,
                    'Unsupported/overlapping bundle ZIP entry')
            source.seek(offset)
            header = struct.unpack('<4s5H3L2H', source.read(30))
            require(header[0] == b'PK\x03\x04' and header[1] <= 20 and header[2] == item.flag_bits
                    and header[3] == item.compress_type and header[10] == 0, 'Bundle local header differs')
            name = source.read(header[9])
            require(name == item.filename.encode('ascii'), 'Bundle local path differs')
            if item.flag_bits & 8:
                require(header[6:9] == (0, 0, 0), 'Bundle descriptor header differs')
            else:
                require(header[6:9] == (item.CRC, item.compress_size, item.file_size), 'Bundle local size/CRC differs')
            offset = source.tell() + item.compress_size
            require(offset <= end[6], 'Bundle entry overlaps directory')
            if item.flag_bits & 8:
                source.seek(offset)
                descriptor = struct.unpack('<4s3L', source.read(16))
                require(descriptor == (b'PK\x07\x08', item.CRC, item.compress_size, item.file_size), 'Invalid bundle descriptor')
                offset += 16
            data = archive.read(item)  # ZipFile also verifies the expanded CRC.
            require(len(data) == item.file_size, 'Bundle expanded size differs')
            content[item.filename] = data
        require(offset == end[6], 'Unexpected bytes outside bundle entries')
    require('code/collector.jar' in content and 'manager/helper.jar' in content, 'Bundle core entrypoints missing')
    bootstrap = [name for name in content if name.startswith('bootstrap/')]
    require(not bootstrap or {'bootstrap/anchor.jar', 'bootstrap/root-trust.sh', 'bootstrap/update-bootstrap.sh'}.issubset(content),
            'Initial bootstrap must include the complete pinned trio')
    return content


def _write_bundle(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED, allowZip64=False) as archive:
        for name, data in sorted(entries.items()):
            require(bundle_role(name), 'Unowned path in full bundle')
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IFREG | (0o700 if name.endswith('.sh') else 0o600)) << 16
            archive.writestr(info, data)
    result = stream.getvalue()
    require(len(result) <= MAX_BUNDLE and sum(len(data) for data in entries.values()) <= MAX_BUNDLE_EXPANDED
            and len(entries) <= MAX_BUNDLE_FILES, 'Full bundle limits exceeded')
    return result


def _signed_bytes(key, public, raw):
    with tempfile.TemporaryDirectory(prefix='sign-bundle-', dir=key.parent) as temp:
        scratch = Path(temp)
        source = scratch / 'manifest.json'
        source.write_bytes(raw)
        signature = openssl('dgst', '-sha256', '-sign', key, source)
        require(len(signature) == 384, 'Bundle signing requires RSA-3072')
        sigfile = scratch / 'signature.bin'
        sigfile.write_bytes(signature)
        der = scratch / 'public.der'
        der.write_bytes(public)
        pem = scratch / 'public.pem'
        pem.write_bytes(openssl('pkey', '-pubin', '-inform', 'DER', '-in', der, '-outform', 'PEM'))
        openssl('dgst', '-sha256', '-verify', pem, '-signature', sigfile, source)
        return signature


def sign_bundle_release(root, agent_path, version, bootstrap=None):
    """Build a full signed bundle; bootstrap bytes are explicit, never inferred or rotated.

    bootstrap is None for a normal update or {anchor.jar, root-trust.sh,
    update-bootstrap.sh, lib/*.jar} for first installation/migration. Its initial
    libraries remain pinned with the anchor, independent of manager upgrades.
    Configuration, CA and identity from the generic installer are excluded.
    """
    require(VERSION.fullmatch(version), 'Stable bundle version required')
    key, public = ensure_keypair(root)
    generic = package_entries(agent_path)
    require(generic.get('update-public.der') == public and json.loads(generic['DEPENDENCIES.json'])['version'] == version,
            'Full bundle must use the reviewed generic package and existing trust pin')
    validate_jar(generic['collector.jar'], version)
    require(all(name in ('conf/ca.pem', 'conf/collector.properties.example') for name in generic if name.startswith('conf/')),
            'Full bundle input must not contain an installed identity or active configuration')
    entries = {'code/collector.jar': generic['collector.jar'], 'manager/helper.jar': generic['collector.jar']}
    for name, data in generic.items():
        if re.fullmatch(r'[A-Za-z0-9_-]+[.]sh|lib/[A-Za-z0-9_.-]+[.]jar', name):
            entries['code/' + name] = data
            if name.startswith('lib/'):
                entries['manager/' + name] = data
        elif name.startswith('conf/') and name.endswith('.example'):
            entries['defaults/' + Path(name).name] = data
    if bootstrap is not None:
        core = {'anchor.jar', 'root-trust.sh', 'update-bootstrap.sh'}
        require(core.issubset(bootstrap) and all(name in core or re.fullmatch(r'lib/[A-Za-z0-9_.-]+[.]jar', name) for name in bootstrap)
                and all(isinstance(value, bytes) and value for value in bootstrap.values()), 'Provide the reviewed bootstrap trio and explicit pinned libraries')
        entries.update({'bootstrap/' + name: data for name, data in bootstrap.items()})
    bundle = _write_bundle(entries)
    with tempfile.TemporaryDirectory(prefix='check-bundle-', dir=key.parent) as temp:
        path = Path(temp) / 'bundle.zip'
        path.write_bytes(bundle)
        require(bundle_entries(path) == entries, 'Full bundle inventory differs after writing')
    inventory = {name: {'bytes': len(data), 'sha256': sha(data), 'mode': '700' if name.endswith('.sh') else '600', 'role': bundle_role(name)}
                 for name, data in sorted(entries.items())}
    manifest = {'schema': 1, 'type': 'ASSESSMENT_AGENT_BUNDLE_V1', 'format': 'agent-bundle-v1', 'version': version,
                'minUpdater': 3, 'minJava': 6, 'platform': 'linux', 'bytes': len(bundle), 'sha256': sha(bundle), 'files': inventory}
    raw = (json.dumps(manifest, sort_keys=True, separators=(',', ':')) + '\n').encode()
    require(len(raw) <= 65536, 'Bundle manifest exceeds verifier bound')
    signature = _signed_bytes(key, public, raw)
    prefix = 'dist/agent-bundles/' + version + '/'
    return {'dist/agent-update-public.der': public, prefix + 'manifest.json': raw,
            prefix + 'manifest.sig': signature, prefix + 'bundle.zip': bundle}


def legacy_bridge_profile(origin_zip, origin_sha256):
    """Origin ZIP must be independently pinned by its caller, not self-approved."""
    require(re.fullmatch('[a-f0-9]{64}', origin_sha256 or ''), 'An independent original ZIP hash is required')
    origin_zip = Path(origin_zip)
    require(origin_zip.is_file() and not origin_zip.is_symlink() and sha(origin_zip.read_bytes()) == origin_sha256,
            'Original bridge package differs from the pinned baseline')
    entries = package_entries(origin_zip)
    with zipfile.ZipFile(io.BytesIO(entries['collector.jar'])) as jar:
        versions = [line.split(': ', 1)[1] for line in jar.read('META-INF/MANIFEST.MF').decode().splitlines()
                    if line.startswith('Implementation-Version: ')]
    require(len(versions) == 1 and VERSION.fullmatch(versions[0]), 'Original bridge version is invalid')
    validate_jar(entries['collector.jar'], versions[0])
    required = {name: sha(data) for name, data in sorted(entries.items())
                if re.fullmatch(r'[A-Za-z0-9_-]+[.]sh|lib/[A-Za-z0-9_.-]+[.]jar|update-public[.]der', name)}
    require(required and len(required) <= 100 and 'update-public.der' in required, 'Original bridge support inventory is incomplete')
    profile = sha(json.dumps(required, sort_keys=True, separators=(',', ':')).encode())
    return entries, {'fromVersion': versions[0], 'fromCollectorSha256': sha(entries['collector.jar']),
                     'supportProfileId': profile, 'supportFiles': required}


def sign_bridge_release(root, origin_zip, bridge_jar, bridge_version, target_version,
                        origin_sha256, min_updater=1):
    require(VERSION.fullmatch(bridge_version) and VERSION.fullmatch(target_version) and min_updater in (1, 2), 'Invalid bridge route version/protocol')
    original, profile = legacy_bridge_profile(origin_zip, origin_sha256)
    numeric = lambda value: tuple(map(int, value.split('.')))
    require(numeric('1.5.12') <= numeric(profile['fromVersion']) < numeric(bridge_version) < numeric(target_version),
            'Bridge must monotonically connect a supported original version to its final target')
    key, public = ensure_keypair(root)
    require(original['update-public.der'] == public, 'Bridge cannot change the original signing trust')
    payload = Path(bridge_jar).read_bytes()
    validate_jar(payload, bridge_version)
    with zipfile.ZipFile(io.BytesIO(payload)) as jar:
        metadata = json.loads(jar.read('migration/bridge.json'))
        required_metadata = {'schema', 'fromVersion', 'fromCollectorSha256', 'supportProfileId', 'bridgeVersion',
                             'targetVersion', 'recoveryResource', 'supportFiles', 'allowedHelperHashes'}
        require(set(metadata) == required_metadata and metadata['schema'] == 1
                and all(metadata[name] == value for name, value in profile.items())
                and metadata['bridgeVersion'] == bridge_version and metadata['targetVersion'] == target_version
                and metadata['recoveryResource'] == 'migration/baseline/collector.jar'
                and jar.read(metadata['recoveryResource']) == original['collector.jar'], 'Bridge recovery provenance differs from pinned original')
        helpers = metadata['allowedHelperHashes']
        validate_helper_hashes(helpers, profile['fromCollectorSha256'])
    manifest = {'schema': 1, 'type': 'ASSESSMENT_AGENT_UPDATE_V1', 'version': bridge_version,
                'format': 'collector-jar-v1', 'minUpdater': min_updater, 'sha256': sha(payload),
                'bytes': len(payload), 'requiredFiles': profile['supportFiles']}
    raw = (json.dumps(manifest, sort_keys=True, separators=(',', ':')) + '\n').encode()
    require(len(raw) <= 65536, 'Legacy bridge manifest exceeds bound')
    signature = _signed_bytes(key, public, raw)
    identity = sha(raw)
    prefix = 'dist/agent-bridges/' + bridge_version + '/' + identity + '/'
    return {'dist/agent-update-public.der': public, prefix + 'manifest.json': raw,
            prefix + 'manifest.sig': signature, prefix + 'collector.jar': payload}


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--initialize', action='store_true')
    args = parser.parse_args()
    require(args.initialize, 'Use --initialize to prepare the offline signing identity')
    _, public_key = ensure_keypair(Path(__file__).resolve().parents[1], create=True)
    print('Offline signing identity ready. Public SHA256: ' + sha(public_key))
