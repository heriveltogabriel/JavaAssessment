#!/bin/sh
# Local fixtures only. No remote hosts, release signing or heavy stress tests.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17+ directory}"
mkdir -p tmp/source-classes tmp/source-evidence
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp 'lib/*' \
  -d tmp/source-classes shared/*.java server/*.java collector/*.java tests/*.java
CP='tmp/source-classes:lib/*'
for suite in ArchiveMetadataTest WebLogicVersionTest SpringDataSourceTest SpringConfigurationTest SpringIntegrationTest RuntimeInventoryTest \
  ProcessLaunchTest JdkInventoryTest BoundedDirectoryTest CollectorSweepTest \
  CollectorTest AgentToolTest LegacyDiscoveryTest AgentBundleManifestTest AgentBootstrapHandoffTest; do
  "$JAVA_HOME/bin/java" -Xmx96m -cp "$CP" "assessment.$suite"
done
"$JAVA_HOME/bin/java" -Xmx96m -cp "$CP" assessment.ServerDataSourcesTest \
  --export tmp/source-evidence/jdbc-fixtures.json
for suite in EnvironmentPresenceTest ArchiveDefaultsHttpTest PortugueseTest InventoryStoreTest UserAuthTest UserAccessHttpTest EnvironmentInstallAccessTest EnvironmentInstallHttpTest InstallCommandTest \
  MachineActionsHttpTest MachineCleanupHttpTest AgentUpdateWorkerTest AgentUpdateHttpTest \
  WorkRequestsHttpTest RuntimeInventoryHttpTest CsvExportTest \
  VersionedCsvHttpTest DashboardVersionHttpTest CompactOverviewTest PostUpdateAssessmentsTest PostUpdateAssessmentHttpTest; do
  "$JAVA_HOME/bin/java" -Xmx384m -cp "$CP" "assessment.$suite"
done
"$JAVA_HOME/bin/java" -Xmx384m -cp "$CP" assessment.JdbcOverviewHttpTest \
  tmp/source-evidence/jdbc-fixtures.json tmp/source-evidence/jdbc-http
for suite in environment-presence.test.cjs locale.test.cjs dashboard.test.cjs interface-refresh.test.cjs version-js.test.cjs agent-release-notice.test.cjs; do
  "${NODE:-node}" "tests/$suite"
done
"${NODE:-node}" tests/jdbc-overview.test.cjs tmp/source-evidence/jdbc-http/overview-fixtures.json
printf '%s\n' 'Source regression checks passed; heavy stress and external hosts were not exercised.'
