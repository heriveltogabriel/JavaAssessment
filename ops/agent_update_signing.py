"""Offline release signing. Only the public key and signed Java payload leave this Mac."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import zipfile

MAX_JAR = 32 * 1024 * 1024
VERSION = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')
ASSET = re.compile(r'(?:[a-z][a-z0-9-]*\.sh|lib/[A-Za-z0-9_.-]+\.jar|update-public\.der)')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


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


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--initialize', action='store_true')
    args = parser.parse_args()
    require(args.initialize, 'Use --initialize to prepare the offline signing identity')
    _, public_key = ensure_keypair(Path(__file__).resolve().parents[1], create=True)
    print('Offline signing identity ready. Public SHA256: ' + sha(public_key))
