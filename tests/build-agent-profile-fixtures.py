#!/usr/bin/env python3
"""Derive a two-profile migration catalog from an existing disposable full fixture.

The original 1.5.12 collector bytes are unchanged. One known support profile has
a different poll.sh; both bridges and the ordered index use only the existing
fixture's test pin. No product source, release artifact or production key is read.
"""
import argparse
import io
import json
import os
from pathlib import Path
import shutil
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ops'))
from agent_update_signing import (ensure_keypair, legacy_bridge_profile, package_entries,
                                 sha, sign_bridge_release, _signed_bytes)


def require(value, message):
    if not value:
        raise RuntimeError(message)


def package(path, entries):
    entries = {name: data for name, data in entries.items() if name != 'SHA256SUMS'}
    entries['SHA256SUMS'] = ''.join(sha(data) + '  ' + name + '\n' for name, data in sorted(entries.items())).encode()
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            archive.writestr(name, data)


def save(base, entries):
    for name, data in entries.items():
        path = base / name
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        path.write_bytes(data)
        path.chmod(0o600)


def build(args):
    os.umask(0o077)
    source = Path(args.fixture).resolve()
    output = Path(args.output).absolute()
    require(source.is_relative_to((ROOT / 'tmp').resolve()) and not source.is_symlink(), 'Only an existing local test fixture is supported')
    require(not output.exists() and not output.is_symlink(), 'Use a new profile fixture output')
    metadata = json.loads((source / 'fixture.json').read_text())
    require(metadata.get('testOnly') is True and metadata.get('productionPrivateKeyRead') is False
            and metadata['fromVersion'] == '1.5.12', 'An explicit original 1.5.12 test fixture is required')
    key, public = ensure_keypair(source / 'test-signing-root')
    require(sha(public) == metadata['testPublicKeySha256'], 'Fixture signing pin differs')
    original_zip = source / metadata['originalPackage']
    original = package_entries(original_zip)
    require(sha(original['collector.jar']) == metadata['fromCollectorSha256']
            and original['update-public.der'] == public, 'Original collector or pin differs')
    original_payload = (source / metadata['bridgeJar']).read_bytes()
    require(len(original_payload) < 32 * 1024 * 1024, 'Bridge exceeds legacy bound')
    output.mkdir(parents=True, mode=0o700)
    shutil.copyfile(original_zip, output / original_zip.name)
    shutil.copytree(source / 'dist', output / 'dist')
    for name in ('home', 'legacy-root'):
        shutil.copytree(source / name, output / name)
    shutil.copytree(source / 'legacy-root', output / 'alternate-root')
    (output / 'baseline').mkdir()
    (output / 'baseline/collector.jar').write_bytes(original['collector.jar'])
    alternate = dict(original)
    alternate['poll.sh'] += b'\n# Isolated, independently known alternate support profile.\n'
    alternate_zip = output / 'assessment-original-1.5.12-alternate.zip'
    package(alternate_zip, alternate)
    alternate, alternate_profile = legacy_bridge_profile(alternate_zip, sha(alternate_zip.read_bytes()))
    (output / 'alternate-root/poll.sh').write_bytes(alternate['poll.sh'])
    (output / 'alternate-root/SHA256SUMS').write_bytes(alternate['SHA256SUMS'])
    with zipfile.ZipFile(io.BytesIO(original_payload)) as archive:
        members = {name: archive.read(name) for name in archive.namelist()}
    bridge_metadata = json.loads(members['migration/bridge.json'])
    bridge_metadata.update({key: value for key, value in alternate_profile.items()})
    members['migration/bridge.json'] = (json.dumps(bridge_metadata, sort_keys=True, separators=(',', ':')) + '\n').encode()
    alternate_bridge = output / 'alternate-bridge.jar'
    with zipfile.ZipFile(alternate_bridge, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(members.items()):
            archive.writestr(name, data)
    original_bridge = output / 'original-bridge.jar'
    original_bridge.write_bytes(original_payload)
    routes = []
    profiles = []
    for label, origin, bridge in (('alternate', alternate_zip, alternate_bridge), ('original', original_zip, original_bridge)):
        entries, profile = legacy_bridge_profile(origin, sha(origin.read_bytes()))
        artifacts = sign_bridge_release(source / 'test-signing-root', origin, bridge,
                                        metadata['bridgeVersion'], metadata['targetVersion'], sha(origin.read_bytes()), 1)
        save(output, artifacts)
        manifest_path = next(name for name in artifacts if name.endswith('/manifest.json'))
        identity = sha(artifacts[manifest_path])
        routes.append({'id': identity, 'fromVersion': profile['fromVersion'], 'fromCollectorSha256': profile['fromCollectorSha256'],
                       'supportProfileId': profile['supportProfileId'], 'minUpdater': 1, 'bridgeVersion': metadata['bridgeVersion'],
                       'bridgeManifestSha256': identity})
        profiles.append({'label': label, 'id': identity, 'supportProfileId': profile['supportProfileId'], 'pollSha256': sha(entries['poll.sh'])})
    require(profiles[0]['supportProfileId'] != profiles[1]['supportProfileId'], 'Support profiles must differ')
    prefix = 'dist/agent-update-routes/' + metadata['targetVersion'] + '/'
    index = json.loads((output / (prefix + 'routes.json')).read_text())
    index['routes'] = routes
    raw = (json.dumps(index, sort_keys=True, separators=(',', ':')) + '\n').encode()
    save(output, {prefix + 'routes.json': raw, prefix + 'routes.sig': _signed_bytes(key, public, raw)})
    summary = {**metadata, 'profiles': profiles, 'fixtureSource': str(source.relative_to(ROOT)), 'root': 'legacy-root',
               'baselineJar': 'baseline/collector.jar', 'alternateRoot': 'alternate-root',
               'profileFallbackOnly': True, 'originalCollectorUnchanged': True,
               'serviceProbeAdapters': True, 'productionPrivateKeyRead': False}
    save(output, {'fixture.json': (json.dumps(summary, indent=2) + '\n').encode()})
    print('Two-profile test catalog ready: ' + str(output))
    print('Original collector bytes unchanged; alternate support first, original second; isolated test signatures only.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', default='tmp/full-update/http-fixture-final3')
    parser.add_argument('--output', default='tmp/full-update/profile-fixture')
    build(parser.parse_args())
