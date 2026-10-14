#!/usr/bin/env python3
"""Offline release assembly; fixture has an exclusive test key, no host access."""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ops'))
from agent_update_signing import package_entries, sha
from full_agent_release import build_assets, write_once, inherited_bootstrap
from dashboard_release import reserve_update_pair

checks = 0

def check(ok, why):
    global checks
    if not ok:
        raise AssertionError(why)
    checks += 1


def reject(call, why):
    try:
        call()
    except (RuntimeError, ValueError, OSError):
        check(True, why)
        return
    raise AssertionError('Accepted: ' + why)


def main(args):
    fixture = Path(args.fixture).resolve()
    metadata = json.loads((fixture / 'fixture.json').read_text())
    check(metadata['testOnly'] and not metadata['productionPrivateKeyRead'], 'exclusive test fixture required')
    source_agent = fixture / metadata['genericInstaller']
    original = fixture / metadata['originalPackage']
    before = source_agent.read_bytes()
    origin = {'path': str(original), 'sha256': sha(original.read_bytes()), 'minUpdater': 1}
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    with tempfile.TemporaryDirectory(prefix='release-assembly-') as temp:
        scratch = Path(temp)
        agent = scratch / 'unembedded-agent.zip'
        raw = {name: data for name, data in package_entries(source_agent).items()
               if name != 'SHA256SUMS' and not name.startswith('full-update/')}
        raw['SHA256SUMS'] = ''.join(sha(data) + '  ' + name + '\n' for name, data in sorted(raw.items())).encode()
        with zipfile.ZipFile(agent, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(raw.items()):
                archive.writestr(name, data)
        assets, installer = build_assets(ROOT, fixture / 'test-signing-root', scratch,
                                        agent, metadata['targetVersion'], metadata['bridgeVersion'], [origin], java,
                                        [{'path': str(original), 'sha256': sha(original.read_bytes())}])
        check(source_agent.read_bytes() == before, 'immutable input installer')
        reject(lambda: build_assets(ROOT, fixture / 'test-signing-root', scratch / 'bad-helper', agent,
                                    metadata['targetVersion'], metadata['bridgeVersion'], [origin], java,
                                    [{'path': str(original), 'sha256': '0' * 64}]), 'unreviewed helper baseline')
        check(all('private' not in name for name in assets), 'only public update files returned')
        target = scratch / 'installer.zip'
        target.write_bytes(installer)
        entries = package_entries(target)
        input_entries = package_entries(agent)
        check(all(entries[name] == value for name, value in input_entries.items() if name != 'SHA256SUMS'), 'signed support and original CA unchanged')
        check(set(entries) - set(input_entries) == {'full-update/' + name for name in ('manifest.json', 'manifest.sig', 'bundle.zip')}, 'fresh installer embeds exact public signed bundle trio')
        bootstrap = inherited_bootstrap(entries, entries['update-public.der'])
        check(bootstrap['anchor.jar'] == input_entries['collector.jar'] and all(bootstrap.get(name) == data for name, data in input_entries.items() if name.startswith('lib/')), 'future release inherits authenticated anchor and its own libraries')
        changed = dict(entries)
        changed['full-update/manifest.sig'] = bytes(384)
        reject(lambda: inherited_bootstrap(changed, entries['update-public.der']), 'changed inherited bootstrap signature')
        index = json.loads(assets['dist/agent-update-routes/' + metadata['targetVersion'] + '/routes.json'])
        check(len(index['routes']) == 1 and index['minimumAgentVersion'] == '1.5.12', 'known legacy route catalog assembled')
        output = scratch / 'saved'
        write_once(output, assets)
        reject(lambda: write_once(output, assets), 'overwrite immutable signed output')
        reject(lambda: build_assets(ROOT, fixture / 'test-signing-root', scratch / 'bad', agent,
                                    metadata['targetVersion'], metadata['targetVersion'], [origin], java), 'non-monotonic bridge')
        reject(lambda: build_assets(ROOT, fixture / 'test-signing-root', scratch / 'bad-hash', agent,
                                    metadata['targetVersion'], metadata['bridgeVersion'], [{**origin, 'sha256': '0' * 64}], java), 'unreviewed source package')
        ledger = scratch / 'ledger'
        (ledger / 'release').mkdir(parents=True)
        state = {'version': '2.0.5', 'releases': [{'version': '2.0.5', 'status': 'prepared'}]}
        (ledger / 'release/dashboard.json').write_text(json.dumps(state))
        release, out = reserve_update_pair(ledger)
        check(release['bridgeVersion'] == '2.0.6' and release['version'] == '2.0.7', 'bridge and target receive different increasing numbers')
        state = json.loads((ledger / 'release/dashboard.json').read_text())
        check(state['releases'][-2]['kind'] == 'agent-bridge' and state['releases'][-2]['status'] == 'bridge-reserved', 'bridge never masquerades as a prepared dashboard')
        reject(lambda: reserve_update_pair(ledger, requested_version='2.0.8'), 'no space for reserved bridge')
        check(out.is_dir(), 'target reserved once')
    print('Full agent release assembly PASS ' + str(checks) + ' checks; only disposable signing and ledger, no production version reserved.')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fixture', default='tmp/full-update/http-fixture-v2')
    main(p.parse_args())
