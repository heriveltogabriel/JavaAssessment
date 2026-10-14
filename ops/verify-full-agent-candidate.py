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
        return {item.filename: source.read(item) for item in entries}


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


def verify(release, baselines_root, pins_path, public_path):
    release, baselines_root = Path(release), Path(baselines_root)
    require(release.is_dir() and not release.is_symlink(), 'Release directory is missing or linked')
    manifest_path = release / 'packages/package-manifest.json'
    require(manifest_path.is_file() and not manifest_path.is_symlink(), 'Final package receipt is missing')
    manifest_raw = manifest_path.read_bytes()
    manifest = parse(manifest_raw)
    target, bridge = manifest['dashboardVersion'], manifest['agentBridgeVersion']
    require(version('1.5.12') < version(bridge) < version(target), 'Invalid bridge/target ordering')
    require(manifest.get('candidateOnly') is True and manifest.get('published') is False
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
            and ledger['releases'][0].get('candidateOnly') is True, 'Public source receipt loses candidate scope')

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
        bundle = check_signature(pem, raw, signature, scratch)
        require(set(bundle) == {'schema', 'type', 'format', 'version', 'minUpdater', 'minJava', 'platform', 'bytes', 'sha256', 'files'}
                and bundle['schema'] == 1 and bundle['type'] == 'ASSESSMENT_AGENT_BUNDLE_V1' and bundle['format'] == 'agent-bundle-v1'
                and bundle['version'] == target and bundle['minUpdater'] == 3 and bundle['minJava'] == 6 and bundle['platform'] == 'linux'
                and bundle['bytes'] == len(payload) and bundle['sha256'] == sha(payload), 'Unexpected final bundle header')
        (scratch / 'bundle.zip').write_bytes(payload)
        members = bundle_entries(scratch / 'bundle.zip')
        require(set(members) == set(bundle['files']), 'Bundle manifest inventory differs')
        for name, data in members.items():
            item = bundle['files'][name]
            require(item == {'bytes': len(data), 'sha256': sha(data), 'mode': '700' if name.endswith('.sh') else '600', 'role': bundle_role(name)}, 'Bundle member metadata differs')
        expected_members = {'code/collector.jar': generic['collector.jar'], 'manager/helper.jar': generic['collector.jar'],
                            'bootstrap/anchor.jar': generic['collector.jar'], 'bootstrap/root-trust.sh': generic['root-trust.sh'],
                            'bootstrap/update-bootstrap.sh': generic['update-bootstrap.sh']}
        for name, data in generic.items():
            if re.fullmatch(r'[A-Za-z0-9_-]+[.]sh|lib/[A-Za-z0-9_.-]+[.]jar', name):
                expected_members['code/' + name] = data
                if name.startswith('lib/'):
                    expected_members['manager/' + name] = data
                    expected_members['bootstrap/' + name] = data
            elif name.startswith('conf/') and name.endswith('.example'):
                expected_members['defaults/' + Path(name).name] = data
        require(members == expected_members, 'First V3 candidate bundle differs from complete generic code/bootstrap/libraries')
        for name in ('code/collector.jar', 'manager/helper.jar', 'bootstrap/anchor.jar'):
            validate_jar(members[name], target)

        route_prefix = 'dist/agent-update-routes/' + target + '/'
        routes = check_signature(pem, runtime[route_prefix + 'routes.json'], runtime[route_prefix + 'routes.sig'], scratch)
        require(set(routes) == {'schema', 'type', 'targetVersion', 'minimumAgentVersion', 'bundleManifestSha256', 'routes'}
                and routes['schema'] == 1 and routes['type'] == 'ASSESSMENT_AGENT_ROUTES_V1'
                and routes['targetVersion'] == target and routes['minimumAgentVersion'] == '1.5.12'
                and routes['bundleManifestSha256'] == sha(raw), 'Route catalog differs from final bundle')
        expected_origins = {v for v, (_, _, pin) in known.items() if pin['migrationOrigin']}
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
    return {'schema': 1, 'result': 'PUBLIC_CANDIDATE_VERIFIED', 'candidateOnly': True,
            'productionCompatibilityValidated': False, 'hostAccess': False, 'privateSigningMaterialRead': False,
            'verifiedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'targetVersion': target, 'bridgeVersion': bridge, 'publicKeySha256': sha(public),
            'packageManifestSha256': sha(manifest_raw), 'bundleManifestSha256': sha(raw),
            'bundleSha256': bundle['sha256'], 'legacyProfiles': len(routes['routes']),
            'original1512Verified': True, 'helper1512And159Verified': True,
            'artifacts': [{'file': row['file'], 'sha256': row['sha256'], 'bytes': row['bytes']} for row in rows]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--baselines-root', type=Path, help='Directory containing immutable previous version directories')
    parser.add_argument('--pins', type=Path, default=ROOT / 'docs/validation/agent-migration-baselines.json')
    parser.add_argument('--report', type=Path, required=True, help='Fresh private JSON receipt; existing files are never replaced')
    args = parser.parse_args()
    result = verify(args.release, args.baselines_root or args.release.parent, args.pins,
                    ROOT / 'collector/public/agent-update-public.der')
    args.report.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(not args.report.parent.is_symlink(), 'Linked report directory refused')
    fd = os.open(args.report, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(json.dumps(result, indent=2) + '\n')
    print('PUBLIC_CANDIDATE_VERIFIED: B=' + result['bridgeVersion'] + ' T=' + result['targetVersion']
          + '; ' + str(result['legacyProfiles']) + ' reviewed legacy profiles; original 1.5.12 and helpers 1.5.12/1.5.9 verified.')
    print('Laboratory artifacts only; Linux migration, recovery and production compatibility remain pending.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, KeyError, zipfile.BadZipFile) as failure:
        print('CANDIDATE_VERIFICATION_FAILED: ' + str(failure), file=sys.stderr)
        sys.exit(1)
