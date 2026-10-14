#!/bin/sh
# Only disposable local fixtures; never a service installer or remote publisher.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17+ directory}"
suite=legacy
fixture=tmp/full-update/http-fixture-final3
fixture_set=false
profile_fixture=tmp/full-update/profile-fixture
while test "$#" -gt 0; do
  case "$1" in
    --suite) test "$#" -ge 2 || exit 2; suite=$2; shift 2;;
    --profile-fixture) test "$#" -ge 2 || exit 2; profile_fixture=$2; shift 2;;
    --fixture) test "$#" -ge 2 || exit 2; fixture=$2; fixture_set=true; shift 2;;
    *) printf '%s\n' 'Usage: test-agent-migration.sh --suite legacy|contracts|lifecycle|server|assessment|integration|all [--fixture PATH] [--profile-fixture PATH]' >&2; exit 2;;
  esac
done
case "$suite" in legacy|contracts|lifecycle|server|assessment|integration|all) ;; *) exit 2;; esac
if test "$suite" = legacy && test "$fixture_set" = false; then fixture=tmp/agent-migration/fixture; fi
mkdir -p tmp/agent-migration/classes tmp/agent-migration/evidence
export ASSESSMENT_MIGRATION_SUITE=$suite ASSESSMENT_MIGRATION_FIXTURE=$fixture ASSESSMENT_PROFILE_FIXTURE=$profile_fixture
python3 -B - <<'PY'
import os
from pathlib import Path
import subprocess

root = Path.cwd()
jdk = Path(os.environ['JAVA_HOME']) / 'bin'
output = root / 'tmp/agent-migration/classes'
evidence = root / 'tmp/agent-migration/evidence'
groups = {
    'legacy': ['LegacyAgentMigrationTest'],
    'contracts': ['AgentBundleManifestTest', 'AgentUpdateRoutesTest', 'AgentBootstrapHandoffTest'],
    'lifecycle': ['AgentBundleInstallerTest', 'AgentV3UninstallInventoryTest'],
    'server': ['AgentUpdateCoordinatorTest', 'AgentUpdateProfileFallbackTest', 'AgentUpdateMigrationHttpTest',
               'AgentUpdateHttpTest', 'WorkRequestsHttpTest', 'DashboardVersionHttpTest'],
    'assessment': ['PostUpdateAssessmentsTest', 'PostUpdateAssessmentHttpTest'],
    'integration': ['AgentFullUpdateEndToEndTest'],
}
selection = os.environ['ASSESSMENT_MIGRATION_SUITE']
suites = sum(groups.values(), []) if selection == 'all' else groups[selection]
missing = [name for name in suites if not (root / 'tests' / (name + '.java')).is_file()]
if missing:
    raise SystemExit('Required suite not implemented; nothing skipped: ' + ', '.join(missing))
sources = sorted((root / 'shared').glob('*.java'))
sources += sorted((root / 'server').glob('*.java'))
sources += sorted((root / 'collector').glob('*.java'))
sources += [root / 'tests/TestAccounts.java']
dependencies = set(suites)
if 'AgentFullUpdateEndToEndTest' in dependencies:
    dependencies.update(('AgentBundleInstallerTest', 'AgentUpdateMigrationHttpTest', 'AgentUpdateRoutesTest'))
if dependencies.intersection(('AgentUpdateCoordinatorTest', 'AgentUpdateMigrationHttpTest')):
    dependencies.add('AgentUpdateRoutesTest')
sources += [root / 'tests' / (name + '.java') for name in sorted(dependencies)]
cp = str(output) + os.pathsep + str(root / 'lib/*')
command = [str(jdk / 'javac'), '--release', '17', '-encoding', 'UTF-8', '-cp', cp,
           '-d', str(output), *map(str, sources)]
compiled = subprocess.run(command, capture_output=True, text=True, timeout=180)
(evidence / ('compile-' + selection + '.log')).write_text(compiled.stdout + compiled.stderr)
if compiled.returncode:
    raise SystemExit('Compilation failed; inspect tmp/agent-migration/evidence/compile-' + selection + '.log')
for suite in suites:
    heap = '-Xmx96m' if suite in ('LegacyAgentMigrationTest', 'AgentBundleManifestTest', 'AgentBundleInstallerTest', 'AgentV3UninstallInventoryTest') else '-Xmx384m'
    args = [str(jdk / 'java'), heap, '-cp', cp, 'assessment.' + suite]
    if suite == 'LegacyAgentMigrationTest':
        args.append('tmp/agent-migration/fixture' if selection == 'all' else os.environ['ASSESSMENT_MIGRATION_FIXTURE'])
    elif suite == 'AgentUpdateProfileFallbackTest':
        args.append(os.environ['ASSESSMENT_PROFILE_FIXTURE'])
    elif suite in ('AgentUpdateRoutesTest', 'AgentBundleInstallerTest', 'AgentUpdateCoordinatorTest', 'AgentUpdateMigrationHttpTest', 'AgentFullUpdateEndToEndTest', 'AgentV3UninstallInventoryTest'):
        args.append(os.environ['ASSESSMENT_MIGRATION_FIXTURE'])
    result = subprocess.run(args, capture_output=True, text=True, timeout=600 if suite == 'AgentV3UninstallInventoryTest' else 240)
    (evidence / (suite + '.log')).write_text(result.stdout + result.stderr)
    print(result.stdout.strip(), flush=True)
    if result.returncode:
        print(result.stderr.strip(), flush=True)
        raise SystemExit('Failed suite: ' + suite)
print('Migration suites passed locally; real Linux service/reboot validation is a separate gate.')
PY
