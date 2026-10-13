#!/usr/bin/env python3
"""Fetch the pinned Beta runtime JARs/POMs, or verify them offline (--check)."""
import argparse
import hashlib
import json
import os
import sys
import tempfile
import urllib.request
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT / 'dashboard-installer'))
from dependencies import verify_libraries


def fetch(url, target, expected):
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return
    if not url.startswith('https://repo.maven.apache.org/maven2/'):
        raise ValueError('Dependency URL must use Maven Central')
    with urllib.request.urlopen(url, timeout=60) as response:
        content = response.read(32 * 1024 * 1024 + 1)
    if len(content) > 32 * 1024 * 1024 or hashlib.sha256(content).hexdigest() != expected:
        raise ValueError('Download checksum mismatch: ' + target.name)
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as staged:
        staged.write(content)
        temporary = Path(staged.name)
    try:
        os.chmod(temporary, 0o644)
        os.replace(temporary, target)
    finally:
        if temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify without downloading')
    args = parser.parse_args()
    directory = PROJECT / 'lib'
    lock = json.loads((directory / 'dependencies-lock.json').read_text())
    if not args.check:
        for entry in lock['dependencies']:
            fetch(entry['url'], directory / entry['file'], entry['sha256'])
            fetch(entry['url'][:-4] + '.pom',
                  directory / 'provenance' / (entry['file'][:-4] + '.pom'), entry['pomSha256'])
    verify_libraries(directory)
    print('BETA_DEPENDENCIES_OK: %d pinned runtime JARs and licenses' % len(lock['dependencies']))


if __name__ == '__main__':
    main()
