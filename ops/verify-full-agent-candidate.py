#!/usr/bin/env python3
"""Verify final public laboratory artifacts without a signing key or host access."""
import argparse
import datetime
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile
import zipfile

from agent_update_signing import (bundle_entries, bundle_role, legacy_bridge_profile,
                                 openssl, package_entries, require, sha, validate_jar)

ROOT = Path(__file__).resolve().parents[1]


def parse(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON member')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def version(value):
    require(isinstance(value, str) and re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', value), 'Invalid stable version')
    return tuple(map(int, value.split('.')))


def archive(raw, limit=512 * 1024 * 1024):
    with zipfile.ZipFile(io.BytesIO(raw)) as source:
        entries = source.infolist()
        require(0 < len(entries) <= 20000 and len({item.filename for item in entries}) == len(entries), 'Duplicate or excessive ZIP inventory')
        require(sum(item.file_size for item in entries) <= limit, 'Expanded ZIP exceeds bound')
        for item in entries:
            name = item.filename
            require(name and not name.startswith('/') and '\\' not in name and '..' not in PurePosixPath(name).parts
                    and not item.is_dir() and stat.S_IFMT(item.external_attr >> 16) in (0, stat.S_IFREG), 'Unsafe ZIP entry')
        content = {item.filename: source.read(item) for item in entries}
    if 'SHA256SUMS' in content:
        checked = set()
        for line in content['SHA256SUMS'].decode('utf-8').splitlines():
            digest, name = line.split('  ', 1)
            require(re.fullmatch(r'[0-9a-f]{64}', digest) and name in content
                    and name != 'SHA256SUMS' and name not in checked, 'Invalid internal checksum entry')
            require(sha(content[name]) == digest, 'Internal checksum mismatch: ' + name)
            checked.add(name)
    return content


def check_signature(public, raw, signature, scratch):
    require(0 < len(raw) <= 65536 and len(signature) == 384, 'Signature input exceeds bounds')
    (scratch / 'document').write_bytes(raw)
    (scratch / 'signature').write_bytes(signature)
    openssl('dgst', '-sha256', '-verify', public, '-signature', scratch / 'signature', scratch / 'document')
    return parse(raw)


def worker_classes(jar):
    content = archive(jar, 64 * 1024 * 1024)
    result = {name: raw for name, raw in content.items()
              if re.fullmatch(r'assessment/AgentUpdateWorker(?:\$[^/]+)?\.class', name)}
    require(len(result) == 7, 'Unexpected original updater class inventory')
    return result


def checked_bundle(public, raw, signature, payload, target, scratch):
    bundle = check_signature(public, raw, signature, scratch)
    require(set(bundle) == {'schema', 'type', 'format', 'version', 'minUpdater', 'minJava', 'platform', 'bytes', 'sha256', 'files'}
            and bundle['schema'] == 1 and bundle['type'] == 'ASSESSMENT_AGENT_BUNDLE_V1' and bundle['format'] == 'agent-bundle-v1'
            and bundle['version'] == target and bundle['minUpdater'] == 3 and bundle['minJava'] == 6 and bundle['platform'] == 'linux'
            and bundle['bytes'] == len(payload) and bundle['sha256'] == sha(payload), 'Unexpected signed bundle header')
    (scratch / 'bundle.zip').write_bytes(payload)
    members = bundle_entries(scratch / 'bundle.zip')
    require(set(members) == set(bundle['files']), 'Bundle manifest inventory differs')
    for name, data in members.items():
        item = bundle['files'][name]
        require(item == {'bytes': len(data), 'sha256': sha(data), 'mode': '700' if name.endswith('.sh') else '600', 'role': bundle_role(name)}, 'Bundle member metadata differs')
    return bundle, members


def inherited_bootstrap(pins, known, baselines_root, public_bytes, public_pem, scratch, target):
    """Authenticate immutable bootstrap solely through reviewed public artifacts."""
    pin = pins['immutableBootstrapPin']
    require(set(pin) == {'version', 'packageManifestSha256', 'packageSha256', 'packageBytes', 'publicKeySha256',
                         'manifestSha256', 'signatureSha256', 'bundleSha256', 'files'}, 'Unexpected immutable bootstrap pin fields')
    origin = pin['version']
    require(origin == '2.0.9' and version(origin) <= version(target) and origin in known,
            'Immutable bootstrap must come from the reviewed first V3 2.0.9 package')
    entries, _, package_pin = known[origin]
    require(package_pin['sha256'] == pin['packageSha256'] and package_pin['bytes'] == pin['packageBytes']
            and pin['publicKeySha256'] == sha(public_bytes) and entries['update-public.der'] == public_bytes,
            'Immutable bootstrap package/key differs from reviewed pin')
    manifest_path = baselines_root / origin / 'packages/package-manifest.json'
    require(manifest_path.is_file() and not manifest_path.is_symlink(), 'Immutable bootstrap public package receipt is missing')
    manifest_raw = manifest_path.read_bytes()
    require(sha(manifest_raw) == pin['packageManifestSha256'], 'Immutable bootstrap package receipt changed')
    receipt = parse(manifest_raw)
    artifact_name = 'packages/assessment-collector-' + origin + '.zip'
    artifacts = [row for row in receipt['artifacts'] if row['file'] == artifact_name]
    require(receipt['dashboardVersion'] == receipt['agentVersion'] == origin
            and receipt['agentUpdatePublicKeySha256'] == sha(public_bytes) and len(artifacts) == 1
            and artifacts[0]['sha256'] == pin['packageSha256'] and artifacts[0]['bytes'] == pin['packageBytes'],
            'Immutable bootstrap package receipt does not authenticate the reviewed agent')
    raw, signature, payload = (entries['full-update/' + name] for name in ('manifest.json', 'manifest.sig', 'bundle.zip'))
    require(sha(raw) == pin['manifestSha256'] and sha(signature) == pin['signatureSha256']
            and sha(payload) == pin['bundleSha256'], 'Immutable bootstrap signed source bytes differ')
    bundle, members = checked_bundle(public_pem, raw, signature, payload, origin, scratch)
    bootstrap = {name: data for name, data in members.items() if name.startswith('bootstrap/')}
    require(set(bootstrap) == set(pin['files']) and set(bootstrap) == {name for name in bundle['files'] if name.startswith('bootstrap/')},
            'Immutable bootstrap inventory differs')
    require({'bootstrap/anchor.jar', 'bootstrap/root-trust.sh', 'bootstrap/update-bootstrap.sh'}.issubset(bootstrap)
            and all(name in {'bootstrap/anchor.jar', 'bootstrap/root-trust.sh', 'bootstrap/update-bootstrap.sh'}
                    or re.fullmatch(r'bootstrap/lib/[A-Za-z0-9_.-]+[.]jar', name) for name in bootstrap),
            'Immutable bootstrap contains an unexpected member')
    for name, data in bootstrap.items():
        require(pin['files'][name] == bundle['files'][name]
                and pin['files'][name]['bytes'] == len(data) and pin['files'][name]['sha256'] == sha(data),
                'Immutable bootstrap member differs from reviewed signed bytes')
    validate_jar(bootstrap['bootstrap/anchor.jar'], origin)
    return origin, bootstrap


def verify(release, baselines_root, pins_path, public_path, prepared_release=False):
    release, baselines_root = Path(release), Path(baselines_root)
    require(release.is_dir() and not release.is_symlink(), 'Release directory is missing or linked')
    manifest_path = release / 'packages/package-manifest.json'
    require(manifest_path.is_file() and not manifest_path.is_symlink(), 'Final package receipt is missing')
    manifest_raw = manifest_path.read_bytes()
    manifest = parse(manifest_raw)
    target, bridge = manifest['dashboardVersion'], manifest['agentBridgeVersion']
    require(version('1.5.12') < version(bridge) < version(target), 'Invalid bridge/target ordering')
    require((manifest.get('candidateOnly') is True or prepared_release and manifest.get('candidateOnly') is False) and manifest.get('published') is False
            and manifest.get('status') == 'prepared-local-build' and manifest.get('agentVersion') == target
            and manifest.get('agentUpdateFormat') == 'agent-bundle-v1'
            and manifest.get('agentUpdateMinimumVersion') == '1.5.12'
            and manifest.get('serverClassesVerified') is True and manifest.get('agentUpdateSigned') is True,
            'Receipt does not describe an unpublished laboratory full-update candidate')
    require(public_path.is_file() and not public_path.is_symlink(), 'Pinned public key is missing')
    public = public_path.read_bytes()
    require(manifest['agentUpdatePublicKeySha256'] == sha(public), 'Candidate public key differs from repository pin')
    expected = {
        'packages/assessment-collector-' + target + '.zip',
        'packages/assessment-dashboard-runtime-' + target + '.zip',
        'packages/assessment-dashboard-source-' + target + '.zip',
        'packages/assessment-dashboard-update-' + target + '.zip',
        'deployment/dashboard-update-sources.zip',
    }
    rows = manifest['artifacts']
    require(isinstance(rows, list) and len(rows) == len(expected)
            and {row['file'] for row in rows} == expected, 'Unexpected final artifact inventory')
    files = {}
    for row in rows:
        require(set(row) == {'file', 'sha256', 'bytes', 'entries'}, 'Unexpected artifact receipt fields')
        path = release / row['file']
        require(path.is_file() and not path.is_symlink(), 'Final artifact is missing or linked')
        raw = path.read_bytes()
        require(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'Final artifact bytes/hash differ')
        content = archive(raw)
        require(len(content) == row['entries'], 'Final artifact ZIP inventory differs')
        files[row['file']] = (raw, content)
    agent_name = 'packages/assessment-collector-' + target + '.zip'
    agent_path = release / agent_name
    generic = package_entries(agent_path)
    require(manifest['agentSha256'] == sha(files[agent_name][0]), 'Agent top-level hash differs')
    require(generic['update-public.der'] == public and parse(generic['DEPENDENCIES.json'])['version'] == target, 'Generic agent version/pin differs')
    require(all(name in ('conf/ca.pem', 'conf/collector.properties.example') for name in generic if name.startswith('conf/')), 'Installed configuration in generic agent')
    validate_jar(generic['collector.jar'], target)
    runtime = files['packages/assessment-dashboard-runtime-' + target + '.zip'][1]
    require(runtime['VERSION'] == (target + '\n').encode() and runtime['dist/assessment-collector.zip'] == files[agent_name][0]
            and runtime['dist/agent-update-public.der'] == public, 'Runtime version/agent/pin differs')
    manual = files['packages/assessment-dashboard-update-' + target + '.zip'][1]
    require(manual['assessment-dashboard-runtime-' + target + '.zip'] == files['packages/assessment-dashboard-runtime-' + target + '.zip'][0]
            and manual['dashboard-update-sources.zip'] == files['deployment/dashboard-update-sources.zip'][0], 'Manual publisher embeds different artifacts')
    source = files['packages/assessment-dashboard-source-' + target + '.zip'][1]
    ledger = parse(source['release/dashboard.json'])
    require(ledger['version'] == target and len(ledger['releases']) == 1
            and ledger['releases'][0]['version'] == target and ledger['releases'][0]['bridgeVersion'] == bridge
            and bool(ledger['releases'][0].get('candidateOnly')) == manifest.get('candidateOnly'), 'Public source receipt loses candidate scope')

    pins = parse(Path(pins_path).read_bytes())
    known = {}
    for row in pins['reviewedPackagePins']:
        origin = baselines_root / row['version'] / 'packages' / ('assessment-collector-' + row['version'] + '.zip')
        require(origin.is_file() and not origin.is_symlink() and origin.stat().st_size == row['bytes'], 'Pinned baseline is missing')
        entries, profile = legacy_bridge_profile(origin, row['sha256'])
        require(profile['fromVersion'] == row['version'] and entries['update-public.der'] == public, 'Baseline version/pin differs')
        known[row['version']] = (entries, profile, row)
    baseline = pins['baselines']['1.5.12']
    original = known['1.5.12'][0]['collector.jar']
    require(sha(original) == baseline['collectorJarSha256'] == 'f9aba1161d5c997be2beeac149bb78caa56d2eec7172ced98800fd18ae8d95b2'
            and len(original) == baseline['collectorJarBytes'] and sha(public) == baseline['publicKeySha256'], 'Original 1.5.12 provenance differs')
    original_workers = worker_classes(original)
    require(sha(original_workers['assessment/AgentUpdateWorker.class']) == baseline['workerClassSha256'], 'Original worker pin differs')
    require(worker_classes(known['1.5.9'][0]['collector.jar']) == original_workers, 'Known 1.5.9 worker classes differ from original 1.5.12')
    latest = known[max(known, key=version)][0]
    require({name: data for name, data in generic.items() if name.startswith('lib/')} ==
            {name: data for name, data in latest.items() if name.startswith('lib/')} and
            generic['conf/ca.pem'] == latest['conf/ca.pem'], 'Generic public CA/libraries differ from reviewed latest installer')
    with tempfile.TemporaryDirectory(prefix='verify-public-candidate-') as temp:
        scratch = Path(temp)
        (scratch / 'public.der').write_bytes(public)
        pem = scratch / 'public.pem'
        pem.write_bytes(openssl('pkey', '-pubin', '-inform', 'DER', '-in', scratch / 'public.der', '-outform', 'PEM'))
        prefix = 'dist/agent-bundles/' + target + '/'
        raw, signature, payload = (runtime[prefix + name] for name in ('manifest.json', 'manifest.sig', 'bundle.zip'))
        require({name for name in generic if name.startswith('full-update/')} == {'full-update/' + name for name in ('manifest.json', 'manifest.sig', 'bundle.zip')}, 'Unexpected generic full-update assets')
        for name in ('manifest.json', 'manifest.sig', 'bundle.zip'):
            require(generic['full-update/' + name] == runtime[prefix + name], 'Generic and runtime full-update bytes differ')
        bundle, members = checked_bundle(pem, raw, signature, payload, target, scratch)
        bootstrap_origin, bootstrap = inherited_bootstrap(pins, known, baselines_root, public, pem, scratch, target)
        expected_members = {'code/collector.jar': generic['collector.jar'], 'manager/helper.jar': generic['collector.jar'], **bootstrap}
        for name, data in generic.items():
            if re.fullmatch(r'[A-Za-z0-9_-]+[.]sh|lib/[A-Za-z0-9_.-]+[.]jar', name):
                expected_members['code/' + name] = data
                if name.startswith('lib/'):
                    expected_members['manager/' + name] = data
            elif name.startswith('conf/') and name.endswith('.example'):
                expected_members['defaults/' + Path(name).name] = data
        require(members == expected_members, 'Candidate bundle differs from complete generic code/manager or immutable inherited bootstrap')
        for name in ('code/collector.jar', 'manager/helper.jar'):
            validate_jar(members[name], target)
        validate_jar(members['bootstrap/anchor.jar'], bootstrap_origin)

        route_prefix = 'dist/agent-update-routes/' + target + '/'
        routes = check_signature(pem, runtime[route_prefix + 'routes.json'], runtime[route_prefix + 'routes.sig'], scratch)
        require(set(routes) == {'schema', 'type', 'targetVersion', 'minimumAgentVersion', 'bundleManifestSha256', 'routes'}
                and routes['schema'] == 1 and routes['type'] == 'ASSESSMENT_AGENT_ROUTES_V1'
                and routes['targetVersion'] == target and routes['minimumAgentVersion'] == '1.5.12'
                and routes['bundleManifestSha256'] == sha(raw), 'Route catalog differs from final bundle')
        expected_origins = {v for v, (_, _, pin) in known.items() if pin['migrationOrigin'] and version(v) < version(target)}
        require(len(routes['routes']) == len(expected_origins)
                and {row['fromVersion'] for row in routes['routes']} == expected_origins, 'Route origin inventory differs from reviewed pins')
        for route in routes['routes']:
            require(set(route) == {'id', 'fromVersion', 'fromCollectorSha256', 'supportProfileId', 'minUpdater', 'bridgeVersion', 'bridgeManifestSha256'}, 'Unexpected route fields')
            from_version = route['fromVersion']; entries, profile, _ = known[from_version]
            bridge_prefix = 'dist/agent-bridges/' + bridge + '/' + route['id'] + '/'
            bridge_raw = runtime[bridge_prefix + 'manifest.json']
            header = check_signature(pem, bridge_raw, runtime[bridge_prefix + 'manifest.sig'], scratch)
            candidate = runtime[bridge_prefix + 'collector.jar']
            expected_updater = 1 if version(from_version) < version('2.0.1') else 2
            require(route['id'] == route['bridgeManifestSha256'] == sha(bridge_raw)
                    and route['bridgeVersion'] == bridge and route['fromCollectorSha256'] == profile['fromCollectorSha256']
                    and route['supportProfileId'] == profile['supportProfileId'] and route['minUpdater'] == expected_updater, 'Route original support/protocol differs')
            require(header == {'schema': 1, 'type': 'ASSESSMENT_AGENT_UPDATE_V1', 'version': bridge,
                               'format': 'collector-jar-v1', 'minUpdater': expected_updater, 'sha256': sha(candidate),
                               'bytes': len(candidate), 'requiredFiles': profile['supportFiles']}, 'Signed bridge manifest differs from original support inventory')
            validate_jar(candidate, bridge)
            content = archive(candidate, 64 * 1024 * 1024)
            metadata = parse(content['migration/bridge.json'])
            helpers = sorted({p['fromCollectorSha256'] for _, p, _ in known.values() if version(p['fromVersion']) <= version(from_version)})
            require(metadata == {'schema': 1, **profile, 'bridgeVersion': bridge, 'targetVersion': target,
                                 'recoveryResource': 'migration/baseline/collector.jar', 'allowedHelperHashes': helpers}, 'Bridge provenance/helper allowlist differs from reviewed original pins')
            require(content['migration/baseline/collector.jar'] == entries['collector.jar'], 'Bridge original recovery resource differs')
            if from_version == '1.5.12':
                require(known['1.5.9'][1]['fromCollectorSha256'] in helpers
                        and known['1.5.12'][1]['fromCollectorSha256'] in helpers
                        and worker_classes(content['migration/baseline/collector.jar']) == original_workers, 'Original 1.5.12 recovery/helper provenance differs')
        classic = 'dist/agent-updates/' + target + '/'
        header = check_signature(pem, runtime[classic + 'manifest.json'], runtime[classic + 'manifest.sig'], scratch)
        support_files = {name: sha(data) for name, data in generic.items()
                         if re.fullmatch(r'[A-Za-z0-9_-]+[.]sh|lib/[A-Za-z0-9_.-]+[.]jar|update-public[.]der', name)}
        require(header == {'schema': 1, 'type': 'ASSESSMENT_AGENT_UPDATE_V1', 'version': target,
                           'format': 'collector-jar-v1', 'minUpdater': 2, 'sha256': sha(generic['collector.jar']),
                           'bytes': len(generic['collector.jar']), 'requiredFiles': support_files}
                and runtime[classic + 'collector.jar'] == generic['collector.jar'], 'Classic route differs from final agent')
    return {'schema': 1, 'result': 'PUBLIC_CANDIDATE_VERIFIED' if manifest.get('candidateOnly') else 'PUBLIC_RELEASE_ARTIFACTS_VERIFIED', 'candidateOnly': manifest.get('candidateOnly'),
            'productionCompatibilityValidated': False, 'hostAccess': False, 'privateSigningMaterialRead': False,
            'verifiedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'targetVersion': target, 'bridgeVersion': bridge, 'publicKeySha256': sha(public),
            'packageManifestSha256': sha(manifest_raw), 'bundleManifestSha256': sha(raw),
            'bundleSha256': bundle['sha256'], 'legacyProfiles': len(routes['routes']),
            'bootstrapOriginVersion': bootstrap_origin, 'immutableBootstrapVerified': True,
            'original1512Verified': True, 'helper1512And159Verified': True,
            'artifacts': [{'file': row['file'], 'sha256': row['sha256'], 'bytes': row['bytes']} for row in rows]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--prepared-release', action='store_true', help='Verify an unpublished consolidated release with the same provenance checks; does not certify runtime compatibility')
    parser.add_argument('--baselines-root', type=Path, help='Directory containing immutable previous version directories')
    parser.add_argument('--pins', type=Path, default=ROOT / 'docs/validation/agent-migration-baselines.json')
    parser.add_argument('--report', type=Path, required=True, help='Fresh private JSON receipt; existing files are never replaced')
    args = parser.parse_args()
    result = verify(args.release, args.baselines_root or args.release.parent, args.pins,
                    ROOT / 'collector/public/agent-update-public.der', args.prepared_release)
    args.report.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(not args.report.parent.is_symlink(), 'Linked report directory refused')
    fd = os.open(args.report, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(json.dumps(result, indent=2) + '\n')
    print(result['result'] + ': B=' + result['bridgeVersion'] + ' T=' + result['targetVersion']
          + '; ' + str(result['legacyProfiles']) + ' reviewed legacy profiles; original 1.5.12 and helpers 1.5.12/1.5.9 verified.')
    print('Artifact integrity verified; native runtime/upgrade evidence must be evaluated separately.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, KeyError, zipfile.BadZipFile) as failure:
        print('CANDIDATE_VERIFICATION_FAILED: ' + str(failure), file=sys.stderr)
        sys.exit(1)
