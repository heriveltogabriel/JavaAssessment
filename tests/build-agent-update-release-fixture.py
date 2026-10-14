#!/usr/bin/env python3
"""Use two immutable real releases in the disposable E2E; never sign test code with product trust."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def release(directory):
    directory = Path(directory)
    manifest = json.loads((directory / 'packages/package-manifest.json').read_text())
    version = manifest['dashboardVersion']
    assert version == manifest['agentVersion'] and manifest['agentUpdateSigned']
    files = {}
    for prefix in ('assessment-collector-', 'assessment-dashboard-runtime-'):
        name = 'packages/' + prefix + version + '.zip'
        artifact = next(item for item in manifest['artifacts'] if item['file'] == name)
        data = (directory / name).read_bytes()
        assert digest(data) == artifact['sha256'], 'Immutable release hash mismatch'
        files[prefix] = data
    return version, files


def build(before, after, destination):
    old_version, old_files = release(before)
    version, files = release(after)
    destination = Path(destination)
    assert not destination.exists(), 'Use a new fixture destination'
    destination.mkdir(parents=True)
    (destination / 'old').mkdir()
    (destination / 'good').mkdir()
    with zipfile.ZipFile(io.BytesIO(old_files['assessment-collector-'])) as archive:
        old_entries = {name: archive.read(name) for name in archive.namelist()}
    (destination / 'old/collector.jar').write_bytes(old_entries['collector.jar'])
    (destination / 'good/assessment-collector.zip').write_bytes(files['assessment-collector-'])
    with zipfile.ZipFile(io.BytesIO(files['assessment-dashboard-runtime-'])) as runtime:
        expected = {'dist/agent-update-public.der', *{'dist/agent-updates/' + version + '/' + name for name in ('manifest.json', 'manifest.sig', 'collector.jar')}}
        for name in expected:
            output = destination / 'good' / name
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(runtime.read(name))
    catalog = json.loads((destination / 'good/dist/agent-updates' / version / 'manifest.json').read_text())
    stable = {name: digest(data) for name, data in old_entries.items() if re.fullmatch(r'[a-zA-Z0-9_-]+\.sh|lib/[a-zA-Z0-9_.-]+\.jar|update-public\.der', name)}
    assert stable == catalog['requiredFiles'], 'Initial installation needs different stable assets'
    assert old_entries['update-public.der'] == (destination / 'good/dist/agent-update-public.der').read_bytes(), 'Release public pins differ'
    (destination / 'fixture.json').write_text(json.dumps({'version': version, 'previousVersion': old_version, 'testOnly': True, 'productionPublicArtifactsUsed': True, 'privateKeyRead': False}) + '\n')
    print('Real immutable release pair ready: ' + old_version + ' -> ' + version + '; stable assets/pin match; no private signing key used')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before'); parser.add_argument('after'); parser.add_argument('destination')
    args = parser.parse_args()
    build(args.before, args.after, args.destination)
