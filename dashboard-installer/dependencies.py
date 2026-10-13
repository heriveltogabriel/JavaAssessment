"""Verify the dashboard's pinned runtime libraries without network access."""
import hashlib
import json
from pathlib import Path


def verify_libraries(directory):
    directory = Path(directory)
    lock = json.loads((directory / 'dependencies-lock.json').read_text(encoding='utf-8'))
    if lock.get('schemaVersion') != 1:
        raise ValueError('Unsupported dependency manifest')
    expected = {entry['file'] for entry in lock['dependencies']}
    if len(expected) != len(lock['dependencies']) or expected != {p.name for p in directory.glob('*.jar')}:
        raise ValueError('Runtime JAR set differs from the pinned dependency manifest')
    files = []
    for entry in lock['dependencies']:
        files.append((entry['file'], entry['sha256']))
        files.append(('provenance/' + entry['file'][:-4] + '.pom', entry['pomSha256']))
    files.extend((entry['file'], entry['sha256']) for entry in lock['licenses'])
    for name, expected_digest in files:
        path = directory / name
        try:
            path.resolve().relative_to(directory.resolve())
        except ValueError:
            raise ValueError('Unsafe dependency path: ' + name)
        if path.is_symlink() or not path.is_file():
            raise ValueError('Missing or unsafe dependency file: ' + name)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected_digest:
            raise ValueError('Dependency checksum failed: ' + name)
    return lock
