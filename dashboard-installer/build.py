#!/usr/bin/env python3
"""Build a credential-free installer for a new Java Assessment dashboard."""
import argparse
import hashlib
import json
import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from dependencies import verify_libraries

PROJECT = Path(__file__).resolve().parent.parent
SOURCE = PROJECT / 'dashboard-installer'
OUTPUT = PROJECT / 'dist/assessment-dashboard-installer-1.0-beta.zip'
COMPATIBILITY_OUTPUT = PROJECT / 'dist/assessment-dashboard-installer.zip'
SOURCE_OUTPUT = PROJECT / 'dist/assessment-dashboard-source-1.0-beta.zip'
INSTALLER_FILES = ('install.sh', 'install.py', 'bootstrap.py', 'dependencies.py',
                   'DatabaseTool.java', 'README.md', 'GUIA_INSTALACAO.md',
                   'BETA_RELEASE.md', 'BETA_MIGRATION.md', 'VERSION')
WEB_SUFFIXES = {'.html', '.css', '.js', '.svg', '.png', '.ico', '.woff2'}


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
    with zipfile.ZipFile(source) as original:
        secrets = private_literals()
        entries = original.namelist()
        if len(entries) != len(set(entries)) or 'INSTALL.md' not in entries:
            raise ValueError('Generic collector ZIP is incomplete')
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
            'install.sh': (PROJECT / 'collector/install.sh').read_bytes(),
            'root-system-install.sh': (PROJECT / 'collector/root-system-install.sh').read_bytes(),
            'conf/collector.properties.example':
                (PROJECT / 'collector/collector.properties.example').read_bytes(),
        }
        updated['conf/ca.pem'] = original.read('conf/ca.pem')
        with tempfile.NamedTemporaryFile(suffix='.zip') as temporary:
            with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as target:
                hashes = []
                for name in entries:
                    if name == 'SHA256SUMS':
                        continue
                    data = updated[name] if name in updated else original.read(name)
                    target.writestr(name, data)
                    if name not in ('DEPENDENCIES.json',):
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
        for name in ('shared/Json.java',):
            dest = stage / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(PROJECT / name, dest)
        for directory, pattern in (('server', '*.java'), ('web', '*')):
            (stage / directory).mkdir()
            for path in sorted((PROJECT / directory).glob(pattern)):
                if path.is_file() and (directory != 'web' or path.suffix in WEB_SUFFIXES):
                    shutil.copy2(path, stage / directory / path.name)
        copy_libraries(stage / 'lib')
        (stage / 'dist').mkdir()
        (stage / 'dist/assessment-collector.zip').write_bytes(collector)
        (stage / 'agent-ca.pem').write_bytes(ca)
        return archive_stage(stage, output)


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
        for name in ('ops/prepare-beta-dependencies.py', 'ops/test-beta.sh',
                     'collector/public/ca.pem', 'CONTRACT.md', 'docs/validation/beta-1000-load.json'):
            if not (PROJECT / name).is_file():
                if name in ('ops/test-beta.sh', 'docs/validation/beta-1000-load.json'):
                    raise ValueError('Final Beta source evidence is missing: ' + name)
                raise ValueError('Source release input is missing: ' + name)
            target = stage / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(PROJECT / name, target)
        for source in sorted((PROJECT / 'docs').glob('*')):
            if source.is_file() and source.name.lower().startswith('beta') and source.suffix == '.md':
                target = stage / 'docs' / source.name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
        copy_libraries(stage / 'lib')
        (stage / 'dist').mkdir()
        (stage / 'dist/assessment-collector.zip').write_bytes(collector)
        return archive_stage(stage, Path(output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='Build an isolated package at this path')
    parser.add_argument('--with-source', action='store_true', help='Also build the credential-free Beta source ZIP')
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
