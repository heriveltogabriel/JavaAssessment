#!/usr/bin/env python3
"""Build a credential-free installer for a new Java Assessment dashboard."""
import argparse
import hashlib
import io
import json
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path
from dependencies import verify_libraries
import agent_updates

PROJECT = Path(__file__).resolve().parent.parent
SOURCE = PROJECT / 'dashboard-installer'
RELEASE_VERSION = (SOURCE / 'VERSION').read_text().strip()
if not re.fullmatch(r'[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-beta)?', RELEASE_VERSION):
    raise ValueError('Invalid dashboard installer version')
OUTPUT = PROJECT / ('dist/assessment-dashboard-installer-' + RELEASE_VERSION + '.zip')
COMPATIBILITY_OUTPUT = PROJECT / 'dist/assessment-dashboard-installer.zip'
SOURCE_OUTPUT = PROJECT / ('dist/assessment-dashboard-source-' + RELEASE_VERSION + '.zip')
INSTALLER_FILES = ('install.sh', 'install.py', 'bootstrap.py', 'dependencies.py', 'agent_updates.py',
                   'DatabaseTool.java', 'README.md', 'GUIA_INSTALACAO.md',
                   'BETA_RELEASE.md', 'BETA_MIGRATION.md', 'VERSION')
WEB_SUFFIXES = {'.html', '.css', '.js', '.json', '.svg', '.png', '.ico', '.woff2'}
UPDATE_ASSET = re.compile(r'(?:[A-Za-z0-9_-]+\.sh|lib/[A-Za-z0-9_.-]+\.jar|update-public\.der)')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def private_literals():
    """Catch accidental copies of local credentials without displaying their values."""
    values = set()
    for name in ('conf/server.properties', 'conf/collector.properties'):
        path = PROJECT / name
        if path.is_file():
            for line in path.read_text(encoding='utf-8').splitlines():
                key, separator, value = line.partition('=')
                if separator and key.strip() in ('agentToken', 'adminSalt', 'adminPasswordSha256', 'enrollmentToken'):
                    values.add(value.strip())
    access = PROJECT / 'conf/access.txt'
    if access.is_file():
        values.update(re.findall(r'(?m)^Password: (.+)$', access.read_text(encoding='utf-8')))
    registry = PROJECT / 'data/auth-registry.json'
    if registry.is_file():
        document = json.loads(registry.read_text(encoding='utf-8'))
        for collection in ('hosts', 'enrollments'):
            for record in document.get(collection, {}).values():
                for key in ('agentToken', 'tokenHash'):
                    if isinstance(record.get(key), str):
                        values.add(record[key])
    return [value.encode('utf-8') for value in values if len(value) >= 12]


def copy_libraries(destination):
    lock = verify_libraries(PROJECT / 'lib')
    names = {'dependencies-lock.json'}
    for entry in lock['dependencies']:
        names.add(entry['file'])
        names.add('provenance/' + entry['file'][:-4] + '.pom')
        names.add('provenance/' + entry['file'] + '.sha1')
    names.update(entry['file'] for entry in lock['licenses'])
    for name in sorted(names):
        source = PROJECT / 'lib' / name
        if source.is_file():
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)


def archive_stage(stage, output):
    """Validate the public staging tree and write a checksummed archive."""
    files = sorted(path for path in stage.rglob('*') if path.is_file())
    secrets = private_literals()
    private_key = re.compile(br'(?m)^-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----\r?$')
    for path in files:
        relative = path.relative_to(stage)
        if (path.is_symlink() or relative.parts[0] in ('conf', 'data', 'logs') or
                path.suffix in ('.key', '.p12', '.jks', '.sqlite')):
            raise ValueError('Unsafe source package entry: ' + str(relative))
        content = path.read_bytes()
        if private_key.search(content):
            raise ValueError('Private key found in public package: ' + str(relative))
        if any(secret in content for secret in secrets):
            raise ValueError('Local credential found in public package: ' + str(relative))
    (stage / 'SHA256SUMS').write_text(''.join(
        f'{digest(path.read_bytes())}  {path.relative_to(stage)}\n' for path in files), encoding='utf-8')
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(path for path in stage.rglob('*') if path.is_file()):
            archive.write(path, path.relative_to(stage))
    checksum = digest(output.read_bytes())
    output.with_suffix(output.suffix + '.sha256').write_text(checksum + '  ' + output.name + '\n')
    return checksum


def configured_generic(source):
    """Carry current collector installation instructions into new dashboards."""
    unified = PROJECT / 'output/dashboard-releases' / RELEASE_VERSION / 'packages' / ('assessment-collector-' + RELEASE_VERSION + '.zip')
    if Path(source) == PROJECT / 'dist/assessment-collector.zip' and unified.is_file():
        source = unified
    with zipfile.ZipFile(source) as original:
        secrets = private_literals()
        entries = original.namelist()
        if len(entries) != len(set(entries)) or 'INSTALL.md' not in entries:
            raise ValueError('Generic collector ZIP is incomplete')
        if json.loads(original.read('DEPENDENCIES.json')).get('version') != RELEASE_VERSION:
            raise ValueError('Collector differs from dashboard version; prepare both with ops/build-dashboard-release.py')
        for name in entries:
            if (name.startswith('/') or '..' in Path(name).parts or
                    name in ('conf/bootstrap.properties', 'conf/collector.properties') or
                    name.startswith(('state/', 'spool/', 'logs/', 'data/')) or
                    name.endswith(('.key', '.p12', '.jks'))):
                raise ValueError('Generic collector contains runtime identity or an unsafe path: ' + name)
            if re.search(br'(?m)^-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----\r?$', original.read(name)):
                raise ValueError('Generic collector contains a private key')
            if any(secret in original.read(name) for secret in secrets):
                raise ValueError('Generic collector contains a local credential')
        updated = {
            'INSTALL.md': (PROJECT / 'collector/INSTALL.md').read_bytes(),
            'conf/collector.properties.example':
                (PROJECT / 'collector/collector.properties.example').read_bytes(),
        }
        # Signed releases bind every script byte. Preserve those immutable assets
        # even when the workspace has already moved on to the next release.
        if 'update-public.der' not in entries:
            for name in ('install.sh', 'root-system-install.sh'):
                updated[name] = (PROJECT / 'collector' / name).read_bytes()
        updated['conf/ca.pem'] = original.read('conf/ca.pem')
        with tempfile.NamedTemporaryFile(suffix='.zip') as temporary:
            with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as target:
                hashes = []
                for name in entries:
                    if name == 'SHA256SUMS':
                        continue
                    data = updated[name] if name in updated else original.read(name)
                    target.writestr(name, data)
                    hashes.append(f'{digest(data)}  {name}')
                target.writestr('SHA256SUMS', '\n'.join(hashes) + '\n')
            temporary.seek(0)
            return temporary.read(), updated['conf/ca.pem']


def build(output):
    """Build only the specified local artifact; never connect to a dashboard."""
    output = Path(output)
    verify_libraries(PROJECT / 'lib')
    collector, ca = configured_generic(PROJECT / 'dist/assessment-collector.zip')
    with tempfile.TemporaryDirectory(prefix='dashboard-installer-') as temporary:
        stage = Path(temporary)
        for name in INSTALLER_FILES:
            shutil.copy2(SOURCE / name, stage / name)
        for directory, pattern in (('shared', '*.java'), ('server', '*.java'), ('web', '*'), ('maintenance', '*.py')):
            (stage / directory).mkdir()
            for path in sorted((PROJECT / directory).glob(pattern)):
                if path.is_file() and (directory != 'web' or path.suffix in WEB_SUFFIXES):
                    shutil.copy2(path, stage / directory / path.name)
        copy_libraries(stage / 'lib')
        (stage / 'dist').mkdir()
        (stage / 'dist/assessment-collector.zip').write_bytes(collector)
        copy_update_catalog(stage)
        (stage / 'agent-ca.pem').write_bytes(ca)
        return archive_stage(stage, output)


def validate_update_catalog(collector, assets):
    """Refuse a signed release whose trust, executable, or stable assets drifted."""
    prefix = 'dist/agent-updates/' + RELEASE_VERSION + '/'
    raw, signature = assets[prefix + 'manifest.json'], assets[prefix + 'manifest.sig']
    public, payload = assets['dist/agent-update-public.der'], assets[prefix + 'collector.jar']
    if not 0 < len(raw) <= 32768 or len(signature) != 384 or not 0 < len(public) <= 1024:
        raise ValueError('Signed update catalog has invalid size bounds')
    # Only public material is used here. Never load the offline private signer.
    with tempfile.TemporaryDirectory(prefix='dashboard-public-update-check-') as temporary:
        directory = Path(temporary)
        for name, data in (('manifest.json', raw), ('manifest.sig', signature), ('public.der', public)):
            (directory / name).write_bytes(data)
        result = subprocess.run(['openssl', 'pkey', '-pubin', '-inform', 'DER', '-in',
                                 str(directory / 'public.der'), '-out', str(directory / 'public.pem')],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if result.returncode == 0:
            result = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(directory / 'public.pem'),
                                     '-signature', str(directory / 'manifest.sig'), str(directory / 'manifest.json')],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if result.returncode != 0:
            raise ValueError('Signed update catalog signature is invalid')
    manifest = json.loads(raw)
    expected = {'schema', 'type', 'version', 'format', 'minUpdater', 'sha256', 'bytes', 'requiredFiles'}
    if (set(manifest) != expected or type(manifest.get('schema')) is not int or manifest['schema'] != 1 or
            type(manifest.get('minUpdater')) is not int or manifest['minUpdater'] not in (1, 2) or
            manifest.get('type') != 'ASSESSMENT_AGENT_UPDATE_V1' or manifest.get('version') != RELEASE_VERSION or
            manifest.get('format') != 'collector-jar-v1' or type(manifest.get('bytes')) is not int or
            manifest['bytes'] != len(payload) or not 0 < len(payload) <= 32 * 1024 * 1024 or
            manifest.get('sha256') != digest(payload)):
        raise ValueError('Signed update catalog differs from the target release')
    with zipfile.ZipFile(io.BytesIO(collector)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or len(names) > 100:
            raise ValueError('Signed collector has duplicate or excessive entries')
        required = {name: digest(archive.read(name)) for name in names if UPDATE_ASSET.fullmatch(name)}
        if (not required or manifest.get('requiredFiles') != required or
                archive.read('update-public.der') != public or archive.read('collector.jar') != payload or
                json.loads(archive.read('DEPENDENCIES.json')).get('version') != RELEASE_VERSION):
            raise ValueError('Signed update catalog differs from immutable collector assets')
    with zipfile.ZipFile(io.BytesIO(payload)) as jar:
        versions = [line[len('Implementation-Version: '):] for line in
                    jar.read('META-INF/MANIFEST.MF').decode().splitlines() if line.startswith('Implementation-Version: ')]
        if versions != [RELEASE_VERSION]:
            raise ValueError('Signed update binary version differs from target release')


def copy_update_catalog(stage):
    """Copy verified public assets from a release workspace or a clean checkout."""
    release = PROJECT / 'output/dashboard-releases' / RELEASE_VERSION
    if not (release / 'packages/package-manifest.json').is_file():
        return copy_checkout_catalog(stage)
    manifest = json.loads((release / 'packages/package-manifest.json').read_text())
    name = 'packages/assessment-dashboard-runtime-' + RELEASE_VERSION + '.zip'
    artifact = next(entry for entry in manifest['artifacts'] if entry['file'] == name)
    source = release / name
    if digest(source.read_bytes()) != artifact['sha256']:
        raise ValueError('Signed agent update source runtime checksum mismatch')
    collector = (stage / 'dist/assessment-collector.zip').read_bytes()
    with zipfile.ZipFile(source) as archive:
        read = agent_updates.archive_reader(archive)
        if agent_updates.has_full(read, RELEASE_VERSION):
            return copy_full_catalog(stage, collector, read)
        require_classic_installer(collector)
        names = [name for name in archive.namelist() if name == 'dist/agent-update-public.der' or name.startswith('dist/agent-updates/' + RELEASE_VERSION + '/')]
        if not names:
            # Existing immutable releases predate the update capability.
            with zipfile.ZipFile(io.BytesIO(collector)) as generic:
                if 'update-public.der' in generic.namelist():
                    raise ValueError('Signed collector is missing its update catalog')
            return
        expected = {'dist/agent-update-public.der', *{'dist/agent-updates/' + RELEASE_VERSION + '/' + file for file in ('manifest.json', 'manifest.sig', 'collector.jar')}}
        if set(names) != expected or len(names) != len(expected):
            raise ValueError('Signed update catalog is incomplete or duplicated')
        assets = {name: archive.read(name) for name in names}
    validate_update_catalog(collector, assets)
    for name, data in assets.items():
        target = stage / name; target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


def require_classic_installer(collector):
    with zipfile.ZipFile(io.BytesIO(collector)) as archive:
        if ('update-bootstrap.sh' in archive.namelist() or
                any(name.startswith('full-update/') for name in archive.namelist())):
            raise ValueError('V3 collector is missing its complete public update catalog')


def copy_full_catalog(stage, collector, read):
    """Validate everything before writing; copy only the exact signed route closure."""
    assets = agent_updates.collect_full(RELEASE_VERSION, read)
    assets.update(agent_updates.classic_assets(RELEASE_VERSION, read, collector))
    configured = agent_updates.embed_full(collector, assets, RELEASE_VERSION)
    for name, data in assets.items():
        target = stage / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (stage / 'dist/assessment-collector.zip').write_bytes(configured)


def copy_checkout_catalog(stage):
    """Rebuild an installer with the published signature, never the private signer."""
    collector = (stage / 'dist/assessment-collector.zip').read_bytes()
    prefix = 'dist/agent-updates/' + RELEASE_VERSION + '/'
    expected = {'dist/agent-update-public.der', *{prefix + name for name in
                ('manifest.json', 'manifest.sig', 'collector.jar')}}
    # Prefer a verified full catalog, even if another source retains a classic catalog.
    for source_root in (PROJECT / 'release', PROJECT / 'dist'):
        source_read = agent_updates.filesystem_reader(source_root)
        read = lambda name, maximum, source_read=source_read: source_read(name[len('dist/'):], maximum)
        if agent_updates.has_full(read, RELEASE_VERSION):
            return copy_full_catalog(stage, collector, read)
    require_classic_installer(collector)
    for source_root in (PROJECT / 'release', PROJECT / 'dist'):
        paths = {name: source_root / name[len('dist/'):] for name in expected}
        if not any(path.exists() or path.is_symlink() for path in paths.values()):
            continue
        if not all(path.is_file() and not path.is_symlink() for path in paths.values()):
            raise ValueError('Public update catalog is incomplete or linked')
        directory = source_root / 'agent-updates' / RELEASE_VERSION
        if directory.is_symlink() or set(path.name for path in directory.iterdir()) != {
                'manifest.json', 'manifest.sig', 'collector.jar'}:
            raise ValueError('Public update catalog has unexpected entries')
        assets = {name: path.read_bytes() for name, path in paths.items()}
        validate_update_catalog(collector, assets)
        for name, data in assets.items():
            target = stage / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        return
    with zipfile.ZipFile(io.BytesIO(collector)) as generic:
        if 'update-public.der' in generic.namelist():
            raise ValueError('Signed collector is missing its public update catalog')


def build_source(output):
    """Source release with only public code, generic collector and pinned libraries."""
    collector, _ = configured_generic(PROJECT / 'dist/assessment-collector.zip')
    with tempfile.TemporaryDirectory(prefix='dashboard-source-') as temporary:
        stage = Path(temporary)
        directory_suffixes = {
            'shared': {'.java'}, 'server': {'.java'}, 'web': WEB_SUFFIXES,
            'collector': {'.java', '.sh', '.md', '.example'},
            'tests': {'.java', '.py', '.js', '.cjs'},
        }
        for directory, suffixes in directory_suffixes.items():
            for source in sorted((PROJECT / directory).iterdir()):
                if source.is_file() and not source.is_symlink() and source.suffix in suffixes:
                    target = stage / directory / source.name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, target)
        for name in INSTALLER_FILES + ('build.py', 'README-SOURCE.md'):
            target = stage / 'dashboard-installer' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE / name, target)
        for source in sorted((PROJECT / 'tests/fixtures').glob('*.cjs')):
            target = stage / 'tests/fixtures' / source.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        for name in ('ops/prepare-beta-dependencies.py', 'ops/test-beta.sh', 'ops/test-agent-migration.sh',
                     'ops/agent_update_signing.py', 'ops/full_agent_release.py', 'ops/dashboard_release.py', 'ops/unified_agent.py', 'ops/test-source.sh',
                     'collector/public/agent-update-public.der',
                     'collector/public/ca.pem', 'CONTRACT.md', 'README.md', 'LICENSE',
                     'docs/validation/beta-1000-load.json', 'docs/validation/agent-migration-baselines.json',
                     'docs/validation/full-agent-update.md', 'docs/validation/agent-v3-uninstall.md', 'docs/validation/agent-profile-fallback.md', 'docs/runbooks/agent-update-1512-canary.md',
                     'docs/superpowers/specs/2026-10-14-full-agent-update-design.md',
                     'docs/superpowers/plans/2026-10-14-full-agent-update.md'):
            if not (PROJECT / name).is_file():
                if name in ('ops/test-beta.sh', 'docs/validation/beta-1000-load.json'):
                    raise ValueError('Final Beta source evidence is missing: ' + name)
                raise ValueError('Source release input is missing: ' + name)
            target = stage / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(PROJECT / name, target)
        for source in sorted((PROJECT / 'docs').glob('*')):
            if source.is_file() and source.suffix == '.md':
                target = stage / 'docs' / source.name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
        copy_libraries(stage / 'lib')
        (stage / 'dist').mkdir()
        (stage / 'dist/assessment-collector.zip').write_bytes(collector)
        copy_update_catalog(stage)
        return archive_stage(stage, Path(output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='Build an isolated package at this path')
    parser.add_argument('--with-source', action='store_true', help='Also build the credential-free source ZIP')
    parser.add_argument('--source-output', type=Path, help='Optional isolated destination for the source ZIP')
    args = parser.parse_args()
    output = args.output or OUTPUT
    checksum = build(output)
    if not args.output:
        shutil.copy2(output, COMPATIBILITY_OUTPUT)
        COMPATIBILITY_OUTPUT.with_suffix('.zip.sha256').write_text(
            checksum + '  ' + COMPATIBILITY_OUTPUT.name + '\n')
    print(output)
    print(checksum)
    if args.with_source or args.source_output:
        source_output = args.source_output or SOURCE_OUTPUT
        source_checksum = build_source(source_output)
        print(source_output)
        print(source_checksum)


if __name__ == '__main__':
    main()
