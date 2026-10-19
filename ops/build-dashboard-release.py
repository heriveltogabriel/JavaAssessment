#!/usr/bin/env python3
"""Reserve, compile, validate and package a new dashboard version. Local only."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from dashboard_release import reserve, finish, java_source, agent_version, agent_source, stable_version, reserve_update_pair, version_numbers, write_state
from unified_agent import build as build_agent
from agent_update_signing import ensure_keypair
from full_agent_release import build_assets

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get('ASSESSMENT_JAVA_HOME', '/private/tmp/assessment-jdk17/jdk-17.0.20.1+1/Contents/Home'))
LEGACY_RUNTIME = ROOT / 'output/runtime-without-compiler'
CSV = ROOT / 'output/versioned-download-csv'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def mark_lab_candidate(release):
    """Keep laboratory scope in the immutable reservation and package receipts."""
    state_path = ROOT / 'release/dashboard.json'
    with (state_path.parent / '.dashboard-release.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        state = json.loads(state_path.read_text())
        row = next(item for item in state['releases'] if item['version'] == release['version'])
        require(row['status'] == 'reserved', 'Only an unpublished reservation can be a laboratory candidate')
        row['candidateOnly'] = True
        release['candidateOnly'] = True
        write_state(state_path, state)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def support_module():
    spec = importlib.util.spec_from_file_location('release_support', ROOT / 'ops/build-versioned-download-csv.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def baseline(support, runtime_path, source_path, runtime_sha, source_sha, label, version):
    require(digest(runtime_path.read_bytes()) == runtime_sha, 'Baseline runtime changed: ' + label)
    require(digest(source_path.read_bytes()) == source_sha, 'Baseline sources changed: ' + label)
    runtime = support.read_zip(runtime_path)
    sources = support.read_zip(source_path)
    entries = {name: data for name, data in {**runtime, **sources}.items()
               if name.startswith(('server/', 'shared/', 'web/', 'lib/'))}
    return {'label': label, 'version': version, 'hashes': {name: digest(data) for name, data in entries.items()}}, runtime


def known_artifacts(support):
    candidates = []
    receipt = json.loads((CSV / 'deployment/deployment-result.json').read_text())
    require(receipt.get('status') == 'deployed' and receipt.get('collectorCaPreserved') is True,
            'CSV deployment receipt not confirmed')
    item, _ = baseline(support, CSV / 'packages/assessment-dashboard-runtime-1.0-beta-csv.zip',
                       CSV / 'deployment/dashboard-update-sources.zip', receipt['runtimeSha256'], receipt['sourcesSha256'],
                       'confirmed CSV delivery', '1.0-beta')
    candidates.append(item)
    manifest = json.loads((LEGACY_RUNTIME / 'packages/package-manifest.json').read_text())
    artifacts = {item['file']: item['sha256'] for item in manifest['artifacts']}
    item, runtime = baseline(support, LEGACY_RUNTIME / 'packages/assessment-dashboard-runtime-1.0-beta-runtime.zip',
                            LEGACY_RUNTIME / 'deployment/dashboard-update-sources.zip',
                            artifacts['packages/assessment-dashboard-runtime-1.0-beta-runtime.zip'],
                            artifacts['deployment/dashboard-update-sources.zip'], 'prepared runtime delivery 1.5.1', '1.0-beta')
    candidates.append(item)
    state = json.loads((ROOT / 'release/dashboard.json').read_text())
    for release in state['releases']:
        if release['status'] != 'prepared':
            continue
        out = ROOT / 'output/dashboard-releases' / release['version']
        prepared = json.loads((out / 'packages/package-manifest.json').read_text())
        hashes = {item['file']: item['sha256'] for item in prepared['artifacts']}
        item, _ = baseline(support, out / ('packages/assessment-dashboard-runtime-' + release['version'] + '.zip'),
                           out / 'deployment/dashboard-update-sources.zip',
                           hashes['packages/assessment-dashboard-runtime-' + release['version'] + '.zip'],
                           hashes['deployment/dashboard-update-sources.zip'], 'prepared dashboard ' + release['version'], release['version'])
        candidates.append(item)
    hotfix = ROOT / "release/dashboard-baseline-2.0.53-hotfix.json"
    if hotfix.is_file():
        candidates.append(json.loads(hotfix.read_text()))
    agent = LEGACY_RUNTIME / 'packages/assessment-collector-1.5.1.zip'
    require(digest(agent.read_bytes()) == artifacts['packages/assessment-collector-1.5.1.zip'], 'Approved agent 1.5.1 changed')
    require(json.loads(support.read_zip(agent)['DEPENDENCIES.json'])['version'] == '1.5.1', 'Agent version mismatch')
    return candidates, runtime, agent


def checked_run(argv, log):
    result = subprocess.run(list(map(str, argv)), cwd=ROOT, capture_output=True, text=True, timeout=180)
    log.write_text(result.stdout + result.stderr)
    require(result.returncode == 0, 'Local check failed; see ' + str(log))


def reviewed_agent_origins(support, helper_only=False):
    """Known immutable release receipts supply independent package hashes."""
    state = json.loads((ROOT / 'release/dashboard.json').read_text())
    origins = []
    for release in state['releases']:
        version = release['version']
        if release['status'] != 'prepared' or version_numbers(version) < ((1, 5, 8) if helper_only else (1, 5, 12)):
            continue
        packages = ROOT / 'output/dashboard-releases' / version / 'packages'
        receipt = json.loads((packages / 'package-manifest.json').read_text())
        name = 'assessment-collector-' + version + '.zip'
        entry = next(item for item in receipt['artifacts'] if item['file'] == 'packages/' + name)
        path = packages / name
        require(path.is_file() and not path.is_symlink() and digest(path.read_bytes()) == entry['sha256'],
                'Known agent origin changed: ' + version)
        item = {'path': str(path), 'sha256': entry['sha256']}
        if not helper_only:
            item['minUpdater'] = 1 if version_numbers(version) < (2, 0, 1) else 2
        origins.append(item)
    require(any(item['path'].endswith('assessment-collector-1.5.12.zip') for item in origins),
            'Original 1.5.12 migration baseline is required')
    return origins


def package(release, out, support, previous, baselines, agent):
    version = release['version']
    support.OUT = out
    builder = support.load_builder()
    secrets = builder.private_literals()
    packages, deployment = out / 'packages', out / 'deployment'
    packages.mkdir(); deployment.mkdir()
    sources = support.product_sources()
    require(sources['server/DashboardVersion.java'].decode() == java_source(release), 'Source version changed during release preparation')
    require((ROOT / 'dashboard-installer/VERSION').read_text() == version + '\n', 'Installer version differs from release')
    require(agent_version((ROOT / 'collector/AgentTransport.java').read_text()) == version, 'Agent source differs from shared release')
    require(json.loads(support.read_zip(agent)['DEPENDENCIES.json'])['version'] == version, 'Agent ZIP differs from shared release')
    runtime = support.verified_classes(sources, JDK / 'bin/javac')
    sources.update({'maintenance/'+p.name:p.read_bytes() for p in (ROOT/'maintenance').glob('*.py')})
    web = {'web/' + path.name: path.read_bytes() for path in sorted((ROOT / 'web').iterdir())
           if path.is_file() and path.suffix in builder.WEB_SUFFIXES}
    libraries = {name: data for name, data in previous.items() if name.startswith('lib/')}
    runtime.update(web); runtime.update(libraries)
    require(release.get('bridgeVersion'), 'Full update requires a separately reserved internal bridge')
    update_assets, complete_installer = build_assets(ROOT, ROOT, out / 'build/full-agent', agent,
                                                    version, release['bridgeVersion'],
                                                    reviewed_agent_origins(support), JDK / 'bin/java',
                                                    reviewed_agent_origins(support, helper_only=True))
    complete_path = out / 'build' / ('assessment-collector-complete-' + version + '.zip')
    with complete_path.open('xb') as stream:
        stream.write(complete_installer)
    agent = complete_path
    # Validate the actual deliverable, after full-update assets have been embedded.
    final_cp = str(out / 'build/server') + ':' + str(ROOT / 'lib/*')
    for suite in ('InstallerPackageVersionTest', 'EnvironmentInstallHttpTest'):
        checked_run([JDK / 'bin/java', '-Xmx384m', '-cp', final_cp, 'assessment.' + suite, complete_path],
                    out / 'evidence' / ('final-package-' + suite + '.log'))
    runtime['dist/assessment-collector.zip'] = complete_installer
    runtime.update(update_assets)
    runtime['VERSION'] = (version + '\n').encode()
    runtime['CONTRACT.md'] = (ROOT / 'CONTRACT.md').read_bytes()
    runtime['README.txt'] = ('Dashboard and agent ' + version + '. Use the manual publisher; preserve database/configuration/CA/identities. Update installed VM agents separately.\n').encode()
    runtime_path = support.archive(packages / ('assessment-dashboard-runtime-' + version + '.zip'), runtime, secrets)
    update_sources = dict(sources)
    update_sources['expected-baselines.json'] = (json.dumps(baselines, indent=2, sort_keys=True) + '\n').encode()
    source_path = support.archive(deployment / 'dashboard-update-sources.zip', update_sources, secrets)
    replacements = {'@DASHBOARD_VERSION@': version, '@RUNTIME_SHA@': digest(runtime_path.read_bytes()), '@SOURCE_SHA@': digest(source_path.read_bytes()),
                    '@RECOVERY_SHA@': digest((ROOT / 'ops/dashboard-release/recover-access-on-host.py').read_bytes())}
    scripts = {}
    for name in ('update-dashboard.py', 'publish-dashboard.py', 'Atualizar Dashboard.command', 'test-update-dashboard.py',
                 'recover-access-on-host.py', 'atualizar-e-recuperar-acesso.py', 'atualizar-dashboard.sh'):
        text = (ROOT / 'ops/dashboard-release' / name).read_text()
        for key, value in replacements.items():
            text = text.replace(key, value)
        require(not any(key in text for key in replacements), 'Unresolved delivery token')
        scripts[name] = text.encode()
        support.scan(name, scripts[name], secrets)
        (deployment / name).write_bytes(scripts[name]); (deployment / name).chmod(0o755)
    candidate_note = '**Candidato exclusivo de laboratório.** Migração real, recuperação, Java 6/SysV e segundo canário ainda não validados para produção. Não entregar ao cliente nesta etapa.\n\n' if release.get('candidateOnly') else ''
    scripts['README.md'] = ('# Dashboard ' + version + '\n\n' + candidate_note + 'Nesta pasta, execute `python3 publish-dashboard.py` no seu Terminal.\n\n').encode() + b'\n\n'.join((ROOT / name).read_bytes() for name in ('docs/DASHBOARD_VERSION.md', 'docs/USER_ACCESS.md', 'docs/MACHINE_ACTIONS.md', 'docs/AGENT_REMOTE_UPDATE.md', 'docs/WORK_REQUESTS.md', 'docs/JDBC_DATASOURCES.md', 'docs/AGENT_REMOTE_UPDATE_TEST.md'))
    scripts['README.md'] += b'\n\n' + (ROOT / 'docs/AGENT_UPDATE_V3.md').read_bytes()
    scripts['README.md'] += b'\n\n' + (ROOT / 'docs/runbooks/update-from-2.0.53.md').read_bytes()
    scripts['README.md'] += ('\n\n**Atenção: atualize também os agentes para ' + version + '.** Publicar o dashboard não atualiza as máquinas automaticamente. Migrações conhecidas a partir de 1.5.12 exigem os perfis assinados; acompanhe atualização e assessment separadamente em Work requests.\n').encode()
    (deployment / 'README.md').write_bytes(scripts['README.md'])
    review = {**sources, **web, **libraries, **update_assets, 'dist/assessment-collector.zip': agent.read_bytes(), 'VERSION': runtime['VERSION']}
    review['collector/public/agent-update-public.der'] = update_assets['dist/agent-update-public.der']
    for name in builder.INSTALLER_FILES + ('build.py',):
        review['dashboard-installer/' + name] = (ROOT / 'dashboard-installer' / name).read_bytes()
    for directory, extensions in [('collector', {'.java', '.sh', '.md', '.example'}), ('tests', {'.java', '.cjs', '.py'}), ('ops/dashboard-release', {'.py', '.command'})]:
        for path in sorted((ROOT / directory).rglob('*')):
            if path.is_file() and path.suffix in extensions and '__pycache__' not in path.parts:
                review[str(path.relative_to(ROOT))] = path.read_bytes()
    for name in ('docs/validation/agent-v3-uninstall.md', 'docs/validation/agent-profile-fallback.md', 'docs/AGENT_UPDATE_V3.md', 'docs/validation/agent-migration-baselines.json', 'docs/validation/full-agent-update.md', 'docs/runbooks/agent-update-1512-canary.md', 'docs/superpowers/specs/2026-10-14-full-agent-update-design.md', 'docs/superpowers/plans/2026-10-14-full-agent-update.md', 'CONTRACT.md', 'docs/DASHBOARD_VERSION.md', 'docs/RUNTIME_WITHOUT_COMPILER.md', 'docs/USER_ACCESS.md', 'docs/MACHINE_ACTIONS.md',
                 'docs/superpowers/specs/2026-10-13-machine-actions-design.md', 'docs/superpowers/plans/2026-10-13-machine-actions.md',
                 'docs/superpowers/specs/2026-10-13-user-access-proposal.md', 'docs/superpowers/plans/2026-10-13-user-access.md',
                 'docs/superpowers/specs/2026-10-13-password-eight-recovery-design.md', 'docs/superpowers/plans/2026-10-13-password-eight-recovery.md',
                 'docs/superpowers/specs/2026-10-13-dashboard-version-design.md', 'docs/superpowers/plans/2026-10-13-dashboard-version.md',
                 'docs/superpowers/specs/2026-10-13-dashboard-uninstall-history-design.md', 'docs/superpowers/plans/2026-10-13-dashboard-uninstall-history.md',
                 'docs/superpowers/specs/2026-10-13-unified-release-version-design.md', 'docs/superpowers/plans/2026-10-13-unified-release-version.md',
                 'docs/superpowers/specs/2026-10-13-agent-update-design.md', 'docs/superpowers/plans/2026-10-13-agent-update.md',
                 'docs/AGENT_REMOTE_UPDATE.md', 'docs/AGENT_REMOTE_UPDATE_TEST.md', 'docs/WORK_REQUESTS.md',
                 'docs/superpowers/specs/2026-10-13-work-requests-progress-design.md',
                 'docs/superpowers/plans/2026-10-13-work-requests-progress.md',
                 'docs/JDBC_DATASOURCES.md', 'docs/agent-hardening-2.0.2.md', 'docs/root-service-runbook.md',
                 'docs/superpowers/specs/2026-10-14-agent-hardening-design.md',
                 'docs/superpowers/plans/2026-10-14-agent-hardening.md',
                 'docs/superpowers/specs/2026-10-14-work-requests-navigation-design.md',
                 'docs/superpowers/plans/2026-10-14-work-requests-navigation.md',
                 'docs/superpowers/specs/2026-10-14-jdbc-discovery-design.md',
                 'docs/superpowers/plans/2026-10-14-jdbc-discovery.md',
                 'docs/superpowers/specs/2026-10-14-interface-2.0.0-design.md',
                 'docs/superpowers/plans/2026-10-14-interface-2.0.0.md',
                 'release/dashboard-baseline-2.0.53-hotfix.json', 'ops/full_agent_release.py', 'ops/agent_update_signing.py', 'ops/remover-agente-antigo.py', 'ops/build-dashboard-release.py', 'ops/dashboard_release.py', 'ops/unified_agent.py', 'ops/build-installer.py', 'ops/build-versioned-download-csv.py'):
        review[name] = (ROOT / name).read_bytes()
    for folder in ('integration/spring',):
        for path in sorted((ROOT / folder).rglob('*')):
            if path.is_file() and path.suffix in ('.java', '.example', '.md'):
                review[str(path.relative_to(ROOT))] = path.read_bytes()
    for name in ('docs/validation/jdbc-discovery-2026-10-16.md', 'docs/runbooks/jdbc-discovery-lab.md', 'docs/SPRING_DISCOVERY.md', 'docs/SPRING_INTEGRATION.md', 'docs/ENVIRONMENT_INSTALL_ACCESS.md', 'docs/validation/recovery-installer-2026-10-16.md', 'docs/DASHBOARD_SELF_UPDATE.md', 'docs/validation/dashboard-self-update-2026-10-16.md', 'docs/runbooks/update-from-2.0.53.md', 'docs/RELEASE_' + version + '.md'):
        review[name] = (ROOT / name).read_bytes()
    review['ops/build-dashboard-web-update.py'] = (ROOT / 'ops/build-dashboard-web-update.py').read_bytes()
    review['release/dashboard.json'] = (json.dumps({'version': version, 'releases': [{**release, 'status': 'prepared'}]}, indent=2) + '\n').encode()
    review['SOURCE_RECONSTRUCTION.md'] = b'Product Java17 sources/public libraries/web/agent included. Compile shared/*.java and server/*.java with javac --release17 -cp lib/*. Compile tests with product classes. Operational rebuild also needs the previous immutable runtime/source archives and confirmed CSV receipt in the original workspace; they are baseline proofs. No credentials are included.\n'
    review_path = support.archive(packages / ('assessment-dashboard-source-' + version + '.zip'), review, secrets)
    manual_path = support.archive(packages / ('assessment-dashboard-update-' + version + '.zip'),
                                  {**scripts, runtime_path.name: runtime_path.read_bytes(), source_path.name: source_path.read_bytes()}, secrets)
    agent_package = packages / ('assessment-collector-' + version + '.zip')
    agent_package.write_bytes(agent.read_bytes())
    artifacts = [{'file': str(path.relative_to(out)), 'sha256': digest(path.read_bytes()), 'bytes': path.stat().st_size,
                  'entries': len(support.read_zip(path))} for path in (agent_package, runtime_path, review_path, manual_path, source_path)]
    manifest = {'status': 'prepared-local-build', 'published': False, 'dashboardVersion': version, 'builtAt': release['builtAt'],
                'candidateOnly': bool(release.get('candidateOnly')),
                'agentVersion': version, 'agentSha256': digest(agent.read_bytes()), 'baselineVersions': [item['label'] for item in baselines],
                'serverClassesVerified': True, 'agentUpdateSigned': True, 'agentUpdateFormat': 'agent-bundle-v1',
                'agentUpdateMinimumVersion': '1.5.12', 'agentBridgeVersion': release['bridgeVersion'],
                'agentUpdatePublicKeySha256': digest(update_assets['dist/agent-update-public.der']), 'artifacts': artifacts}
    (packages / 'package-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (packages / 'SHA256SUMS').write_text(''.join(item['sha256'] + '  ' + Path(item['file']).name + '\n' for item in artifacts if item['file'].startswith('packages/')))
    (deployment / 'SHA256SUMS').write_text(''.join(digest(data) + '  ' + name + '\n' for name, data in sorted(scripts.items())) + digest(source_path.read_bytes()) + '  dashboard-update-sources.zip\n')
    (out / 'README.md').write_bytes(scripts['README.md'])
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab-canary', action='store_true', help='Mark this unpublished B/T reservation as a laboratory-only candidate, not production validated')
    parser.add_argument('--prepare-only', action='store_true', help='Reserve version and compile without packaging; finish with --package-version')
    version_options = parser.add_mutually_exclusive_group()
    version_options.add_argument('--version', type=stable_version, help='Reserve this newer stable release (for example 2.0.0); reserves an internal bridge then the next final patch')
    version_options.add_argument('--package-version', help='Package an already reserved version after local verification')
    args = parser.parse_args()
    if args.package_version and args.prepare_only:
        parser.error('--prepare-only cannot be combined with --package-version')
    ensure_keypair(ROOT)
    support = support_module()
    baselines, previous, agent_baseline = known_artifacts(support)
    if args.package_version:
        state = json.loads((ROOT / 'release/dashboard.json').read_text())
        release = next(item for item in state['releases'] if item['version'] == args.package_version)
        require(release['status'] == 'reserved', 'Release already packaged; prepare a new version')
        out = ROOT / 'output/dashboard-releases' / release['version']
        require((ROOT / 'server/DashboardVersion.java').read_text() == java_source(release), 'Current source version differs from reserved release')
    else:
        transport = ROOT / 'collector/AgentTransport.java'
        release, out = reserve_update_pair(ROOT, minimum_version=agent_version(transport.read_text()), requested_version=args.version)
        (ROOT / 'server/DashboardVersion.java').write_text(java_source(release))
        (ROOT / 'dashboard-installer/VERSION').write_text(release['version'] + '\n')
        transport.write_text(agent_source(transport.read_text(), release['version']))
    if args.lab_canary:
        mark_lab_candidate(release)
    try:
        classes, evidence = out / 'build/server', out / 'evidence'
        classes.mkdir(parents=True, exist_ok=True); evidence.mkdir(exist_ok=True)
        sources = [*sorted((ROOT / 'shared').glob('*.java')), *sorted((ROOT / 'server').glob('*.java'))]
        checked_run([JDK / 'bin/javac', '--release', '17', '-cp', ROOT / 'lib/*', '-d', classes, *sources], evidence / 'compile.log')
        support.OUT = out
        agent = build_agent(ROOT, out, release, agent_baseline, support, JDK / 'bin/java')
        worker_tests = out / 'build/collector-tests'
        worker_tests.mkdir(parents=True, exist_ok=True)
        worker_cp = str(out / 'build/collector') + ':' + str(ROOT / 'lib/*')
        checked_run([JDK / 'bin/javac', '--release', '17', '-cp', worker_cp, '-d', worker_tests,
                     ROOT / 'tests/AgentUpdateWorkerTest.java'], evidence / 'compile-update-worker-test.log')
        checked_run([JDK / 'bin/java', '-Xmx96m', '-cp', str(worker_tests) + ':' + worker_cp,
                     'assessment.AgentUpdateWorkerTest'], evidence / 'agent-update-worker.log')
        checked_run([JDK / 'bin/javac', '--release', '17', '-cp', worker_cp, '-d', worker_tests,
                     *sorted((ROOT / 'collector').glob('*Test.java'))],
                    evidence / 'compile-discovery-tests.log')
        jdbc_fixture = evidence / 'jdbc-collector-fixtures.json'
        checked_run([JDK / 'bin/java', '-Xmx96m', '-cp', str(worker_tests) + ':' + worker_cp,
                     'assessment.ServerDataSourcesTest', '--export', jdbc_fixture], evidence / 'jdbc-collector.log')
        for suite in ('ProductHomesTest', 'HsPerfDataTest', 'JdbcDriverResolverTest', 'JdbcEndpointTest', 'SpringConfigurationTest', 'SpringIntegrationTest', 'SpringDataSourceTest', 'RuntimeInventoryTest', 'ProcessLaunchTest', 'JdkInventoryTest', 'ArchiveMetadataTest', 'WebLogicVersionTest', 'BoundedDirectoryTest', 'CollectorSweepTest', 'CollectorTest', 'AgentToolTest'):
            checked_run([JDK / 'bin/java', '-Xmx96m', '-cp', str(worker_tests) + ':' + worker_cp,
                         'assessment.' + suite], evidence / (suite + '.log'))
        checked_run([JDK / 'bin/java', '-Xmx96m', '-cp', str(worker_tests) + ':' + worker_cp,
                     'assessment.LegacyDiscoveryTest', '--export', evidence / 'weblogic-collector-fixture.json'], evidence / 'LegacyDiscoveryTest.log')
        checked_run([JDK / 'bin/javac', '--release', '17', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), '-d', classes,
                     ROOT / 'tests/TestAccounts.java', ROOT / 'tests/DashboardVersionHttpTest.java',
                     ROOT / 'tests/UserAuthTest.java', ROOT / 'tests/UserAccessHttpTest.java',
                     ROOT / 'tests/MachineActionsHttpTest.java', ROOT / 'tests/MachineActionsUiFixture.java',
                     ROOT / 'tests/UserAccessUiFixture.java', ROOT / 'tests/AgentUpdateHttpTest.java', ROOT / 'tests/AgentUpdateStartTimeoutTest.java',
                     ROOT / 'tests/WorkRequestsHttpTest.java', ROOT / 'tests/WorkRequestsUiFixture.java',
                     ROOT / 'tests/BetaServerTest.java', ROOT / 'tests/JdbcOverviewHttpTest.java',
                     ROOT / 'tests/EnvironmentInstallAccessTest.java', ROOT / 'tests/EnvironmentInstallHttpTest.java',
                     ROOT / 'tests/InstallCommandTest.java', ROOT / 'tests/InstallCommandTlsTest.java'], evidence / 'compile-version-test.log')
        for suite in ('EnvironmentInstallAccessTest', 'EnvironmentInstallHttpTest', 'InstallCommandTest', 'InstallCommandTlsTest'):
            checked_run([JDK / 'bin/java', '-Xmx384m', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), 'assessment.' + suite], evidence / (suite + '.log'))
        checked_run([JDK / 'bin/java', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), 'assessment.UserAuthTest'], evidence / 'user-auth.log')
        checked_run([JDK / 'bin/java', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), 'assessment.UserAccessHttpTest'], evidence / 'user-access-http.log')
        checked_run([JDK / 'bin/java', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), 'assessment.MachineActionsHttpTest'], evidence / 'machine-actions-http.log')
        checked_run([JDK / 'bin/java', '-Xmx384m', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), 'assessment.AgentUpdateHttpTest'], evidence / 'agent-update-http.log')
        checked_run([JDK / 'bin/java', '-Xmx384m', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), 'assessment.AgentUpdateStartTimeoutTest'], evidence / 'agent-update-start-timeout.log')
        checked_run([JDK / 'bin/java', '-Xmx384m', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), 'assessment.WorkRequestsHttpTest'], evidence / 'work-requests-http.log')
        checked_run([JDK / 'bin/java', '-Xmx384m', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'),
                     'assessment.JdbcOverviewHttpTest', jdbc_fixture, evidence / 'jdbc-overview'], evidence / 'jdbc-overview-http.log')
        legacy_fixture = json.loads(jdbc_fixture.read_text())
        legacy_fixture['tomcat'] = json.loads((evidence / 'weblogic-collector-fixture.json').read_text())
        legacy_http_fixture = evidence / 'weblogic-http-fixtures.json'
        legacy_http_fixture.write_text(json.dumps(legacy_fixture))
        checked_run([JDK / 'bin/java', '-Xmx384m', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'),
                     'assessment.JdbcOverviewHttpTest', legacy_http_fixture, evidence / 'weblogic-overview'], evidence / 'weblogic-overview-http.log')
        checked_run([JDK / 'bin/java', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), 'assessment.DashboardVersionHttpTest', release['version']], evidence / 'version-http.log')
        checked_run([JDK / 'bin/javac', '--release', '17', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), '-d', classes,
                     ROOT / 'tests/UnifiedReleaseHttpTest.java', ROOT / 'tests/InstallerPackageVersionTest.java'], evidence / 'compile-unified-test.log')
        checked_run([JDK / 'bin/java', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), 'assessment.UnifiedReleaseHttpTest', agent, release['version']], evidence / 'unified-version-http.log')
        checked_run([JDK / 'bin/java', '-cp', str(classes) + ':' + str(ROOT / 'lib/*'), 'assessment.InstallerPackageVersionTest', agent], evidence / 'installer-version.log')
        checked_run([sys.executable, ROOT / 'tests/dashboard-release-test.py'], evidence / 'release-counter.log')
        checked_run([sys.executable, '-B', ROOT / 'tests/agent-update-signing-test.py'], evidence / 'agent-update-signing.log')
        checked_run([sys.executable, '-B', ROOT / 'tests/helper-baseline-limit-test.py'], evidence / 'helper-baseline-limit.log')
        checked_run([sys.executable, '-B', ROOT / 'tests/agent-update-installer-test.py'], evidence / 'agent-update-installer.log')
        for test in ('agent-update-local-test', 'agent-update-install-test', 'agent-update-service-test'):
            checked_run([sys.executable, '-B', ROOT / ('tests/' + test + '.py')], evidence / (test + '.log'))
        checked_run([sys.executable, ROOT / 'ops/dashboard-release/test-update-dashboard.py'], evidence / 'update-fixtures.log')
        checked_run([sys.executable, ROOT / 'ops/dashboard-release/test-recover-access-on-host.py'], evidence / 'recovery-fixtures.log')
        checked_run([sys.executable, ROOT / 'tests/access_recovery_wrapper_test.py'], evidence / 'recovery-wrapper.log')
        checked_run([sys.executable, '-B', ROOT / 'tests/remove-root-agent-local-test.py'], evidence / 'local-removal.log')
        if args.prepare_only:
            print('Reserved and compiled dashboard ' + release['version'] + '; package with --package-version ' + release['version'])
            return
        manifest = package(release, out, support, previous, baselines, agent)
        finish(ROOT, release['bridgeVersion'], 'bridge-prepared')
        finish(ROOT, release['version'], 'prepared')
        print('Prepared dashboard and complete agent v' + release['version'] + ' locally. No VM accessed.')
        print('Run manually: python3 "' + str(out / 'deployment/publish-dashboard.py') + '"')
    except Exception:
        finish(ROOT, release['version'], 'failed')
        raise


if __name__ == '__main__':
    main()
