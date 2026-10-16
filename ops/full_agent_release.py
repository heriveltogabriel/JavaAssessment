"""Prepare signed full-agent assets offline from independently pinned installers.

This module never publishes, changes a host, rotates a key or reserves versions.
The caller reserves an internal bridge number B and final number T, with every
legacy origin < B < T. Inputs and outputs are immutable release artifacts.
"""
import argparse
import io
import json
from pathlib import Path
import re
import sys
import tempfile
import zipfile

from agent_update_signing import (ensure_keypair, legacy_bridge_profile, package_entries,
                                 sha, sign_bridge_release, sign_bundle_release,
                                 sign_release, _signed_bytes, require, VERSION, bundle_entries, openssl,
                                 validate_helper_hashes)
from unified_agent import build_bridge


def numbers(value):
    require(isinstance(value, str) and VERSION.fullmatch(value), 'Complete stable version required')
    return tuple(map(int, value.split('.')))


def embed_full_update(agent, bundle_assets, version):
    """Only public signed assets are embedded; bundle does not contain itself."""
    entries = package_entries(agent)
    prefix = 'dist/agent-bundles/' + version + '/'
    for name in ('manifest.json', 'manifest.sig', 'bundle.zip'):
        entries['full-update/' + name] = bundle_assets[prefix + name]
    entries.pop('SHA256SUMS')
    entries['SHA256SUMS'] = ''.join(sha(data) + '  ' + name + '\n'
                                  for name, data in sorted(entries.items())).encode()
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            item = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            item.create_system = 3
            item.compress_type = zipfile.ZIP_DEFLATED
            item.external_attr = (0o100700 if name.endswith('.sh') else 0o100600) << 16
            archive.writestr(item, data)
    return stream.getvalue()


def inherited_bootstrap(entries, public):
    """Read the first V3 anchor from an independently pinned full installer."""
    trio = {'full-update/' + n for n in ('manifest.json', 'manifest.sig', 'bundle.zip')}
    present = {n for n in entries if n.startswith('full-update/')}
    if not present:
        return None
    require(present == trio, 'Incomplete prior full-update installer assets')
    raw, signature, payload = (entries['full-update/' + n] for n in ('manifest.json', 'manifest.sig', 'bundle.zip'))
    require(len(raw) <= 65536 and len(signature) == 384 and len(payload) <= 64 * 1024 * 1024, 'Prior full bundle exceeds bounds')
    metadata = json.loads(raw)
    require(set(metadata) == {'schema', 'type', 'format', 'version', 'minUpdater', 'minJava', 'platform', 'bytes', 'sha256', 'files'}
            and metadata['schema'] == 1 and metadata['minUpdater'] == 3
            and metadata['type'] == 'ASSESSMENT_AGENT_BUNDLE_V1' and metadata['format'] == 'agent-bundle-v1'
            and metadata['minJava'] == 6 and metadata['platform'] == 'linux'
            and metadata['bytes'] == len(payload) and metadata['sha256'] == sha(payload), 'Invalid prior full manifest')
    with tempfile.TemporaryDirectory(prefix='verify-inherited-anchor-') as temp:
        scratch = Path(temp)
        (scratch / 'public.der').write_bytes(public)
        (scratch / 'public.pem').write_bytes(openssl('pkey', '-pubin', '-inform', 'DER', '-in', scratch / 'public.der', '-outform', 'PEM'))
        (scratch / 'manifest.json').write_bytes(raw)
        (scratch / 'manifest.sig').write_bytes(signature)
        openssl('dgst', '-sha256', '-verify', scratch / 'public.pem', '-signature', scratch / 'manifest.sig', scratch / 'manifest.json')
        (scratch / 'bundle.zip').write_bytes(payload)
        content = bundle_entries(scratch / 'bundle.zip')
    require(set(content) == set(metadata['files']), 'Prior bundle inventory differs')
    for name, data in content.items():
        item = metadata['files'][name]
        require(item['bytes'] == len(data) and item['sha256'] == sha(data), 'Prior bundle member differs')
    bootstrap = {name[len('bootstrap/'):]: data for name, data in content.items() if name.startswith('bootstrap/')}
    require({'anchor.jar', 'root-trust.sh', 'update-bootstrap.sh'}.issubset(bootstrap), 'Prior full installer has no immutable bootstrap')
    return bootstrap


def build_assets(product_root, signing_root, output, agent, target, bridge, origins, java, helper_baselines=None):
    """Return runtime assets and a fresh-install ZIP without touching input ZIPs.

    origins is a reviewed list of {path, sha256, minUpdater}; a checksum supplied
    independently from release receipts is mandatory. No host paths are read.
    """
    product_root, signing_root, output, agent = map(Path, (product_root, signing_root, output, agent))
    require(numbers(bridge) < numbers(target), 'Internal bridge must precede final target')
    require(isinstance(origins, list) and len(origins) <= 32, 'At most 32 explicit origin profiles')
    key, public = ensure_keypair(signing_root)
    generic = package_entries(agent)
    require(generic['update-public.der'] == public, 'Generic installer must use existing signing pin')
    require(json.loads(generic['DEPENDENCIES.json'])['version'] == target, 'Generic version differs from target')
    require(not any(name.startswith('full-update/') for name in generic), 'Use the unembedded generic build as bundle input')
    helper_profiles = []
    require(isinstance(helper_baselines or [], list) and len(helper_baselines or []) <= 32, 'At most 32 reviewed helper baselines')
    for row in helper_baselines or []:
        require(isinstance(row, dict) and set(row) == {'path', 'sha256'}, 'Helper baseline requires path and independent ZIP hash')
        entries, profile = legacy_bridge_profile(Path(row['path']), row['sha256'])
        require(entries['update-public.der'] == public, 'Known helper trust pin differs')
        helper_profiles.append(profile)
    reviewed = []
    inherited = None
    for row in origins:
        require(isinstance(row, dict) and set(row) == {'path', 'sha256', 'minUpdater'}, 'Origin requires exact path, independent sha256 and minUpdater')
        require(type(row['minUpdater']) is int and row['minUpdater'] in (1, 2), 'Unsupported legacy updater')
        entries, profile = legacy_bridge_profile(Path(row['path']), row['sha256'])
        require(numbers('1.5.12') <= numbers(profile['fromVersion']) < numbers(bridge), 'Origin must be >=1.5.12 and below bridge')
        require(entries['update-public.der'] == public, 'Origin trust pin cannot be replaced')
        prior_bootstrap = inherited_bootstrap(entries, public)
        if prior_bootstrap is not None:
            require(inherited is None or inherited == prior_bootstrap, 'Conflicting prior immutable bootstraps require explicit migration')
            inherited = prior_bootstrap
        reviewed.append((row, profile))
    require(len({(p['fromVersion'], p['fromCollectorSha256'], p['supportProfileId']) for _, p in reviewed}) == len(reviewed), 'Duplicate origin support profile')
    bootstrap = {'anchor.jar': generic['collector.jar'], 'root-trust.sh': generic['root-trust.sh'],
                 'update-bootstrap.sh': generic['update-bootstrap.sh'],
                 **{name: data for name, data in generic.items() if name.startswith('lib/')}}
    if inherited is not None:
        bootstrap = inherited
    assets = sign_bundle_release(signing_root, agent, target, bootstrap)
    # Preserve the published classic route for older installer/test consumers.
    # The dashboard prefers V3 once the signed route index exists.
    assets.update(sign_release(signing_root, agent, target))
    routes = []
    for row, profile in reviewed:
        known_helpers = sorted({p['fromCollectorSha256'] for p in ([p for _, p in reviewed] + helper_profiles)
                                if numbers(p['fromVersion']) <= numbers(profile['fromVersion'])})
        validate_helper_hashes(known_helpers, profile['fromCollectorSha256'])
        staged = output / 'bridge-builds' / sha(json.dumps(profile, sort_keys=True).encode())
        payload = build_bridge(product_root, staged, Path(row['path']), bridge, target,
                               Path(java), row['sha256'], known_helpers)
        signed = sign_bridge_release(signing_root, Path(row['path']), payload, bridge,
                                     target, row['sha256'], row['minUpdater'])
        manifest = next(name for name in signed if name.endswith('/manifest.json'))
        identity = sha(signed[manifest])
        routes.append({'id': identity, 'fromVersion': profile['fromVersion'],
                       'fromCollectorSha256': profile['fromCollectorSha256'],
                       'supportProfileId': profile['supportProfileId'],
                       'minUpdater': row['minUpdater'], 'bridgeVersion': bridge,
                       'bridgeManifestSha256': identity})
        for name, data in signed.items():
            require(name not in assets or assets[name] == data, 'Conflicting immutable update asset')
            assets[name] = data
    index = {'schema': 1, 'type': 'ASSESSMENT_AGENT_ROUTES_V1', 'targetVersion': target,
             'minimumAgentVersion': '1.5.12',
             'bundleManifestSha256': sha(assets['dist/agent-bundles/' + target + '/manifest.json']),
             'routes': routes}
    raw = (json.dumps(index, sort_keys=True, separators=(',', ':')) + '\n').encode()
    require(len(raw) <= 65536, 'Route index exceeds agent bound')
    prefix = 'dist/agent-update-routes/' + target + '/'
    assets.update({prefix + 'routes.json': raw, prefix + 'routes.sig': _signed_bytes(key, public, raw)})
    return assets, embed_full_update(agent, assets, target)


def write_once(output, assets):
    output = Path(output)
    for name, data in assets.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('xb') as out:
            out.write(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', type=Path, required=True)
    parser.add_argument('--target-version', required=True)
    parser.add_argument('--bridge-version', required=True)
    parser.add_argument('--origins', type=Path, required=True,
                        help='JSON list with independently pinned ZIP paths, sha256 and minUpdater')
    parser.add_argument('--helper-baselines', type=Path, help='Optional independently pinned older installers whose known helper can remain behind a newer collector')
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--signing-root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    require(not args.output.exists(), 'Use a fresh immutable output directory')
    rows = json.loads(args.origins.read_text())
    for row in rows:
        path = Path(row['path'])
        if not path.is_absolute():
            row['path'] = str(args.origins.parent / path)
    helper_rows = json.loads(args.helper_baselines.read_text()) if args.helper_baselines else []
    for row in helper_rows:
        path = Path(row['path'])
        if not path.is_absolute():
            row['path'] = str(args.helper_baselines.parent / path)
    assets, installer = build_assets(Path(__file__).resolve().parents[1], args.signing_root,
                                    args.output, args.agent, args.target_version,
                                    args.bridge_version, rows, args.java, helper_rows)
    write_once(args.output, {**assets, 'assessment-collector-' + args.target_version + '.zip': installer})
    print('Prepared full agent assets ' + args.target_version + ' locally, ' + str(len(rows)) + ' reviewed legacy profiles; nothing published.')


if __name__ == '__main__':
    main()
