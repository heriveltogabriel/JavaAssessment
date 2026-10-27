#!/bin/sh
# Local fixtures only. No remote hosts, release signing or heavy stress tests.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17+ directory}"
mkdir -p tmp/source-classes tmp/source-evidence tmp/java-tmp
# Fixtures must not live under /tmp: the collector excludes /tmp by default.
JTMP="$(pwd)/tmp/java-tmp"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp 'lib/*' \
  -d tmp/source-classes shared/*.java server/*.java collector/*.java tests/*.java
CP='tmp/source-classes:lib/*'
for suite in VisitedPathsTest LifecycleTest ProductHomesTest JdbcDriverResolverTest WebLogicPrecedenceTest TomcatDiscoveryTest JbossDiscoveryTest NativeTomcatTest JdbcEndpointTest ArchiveMetadataTest WebLogicVersionTest SpringDataSourceTest ApplicationConfigFilesTest AgentConfigAcceptanceTest ApplicationJdbcTest JndiLinksTest SpringExtendedTest SpringConfigurationTest SpringIntegrationTest RuntimeInventoryTest \
  ProcessLaunchTest HsPerfDataTest JdkInventoryTest BoundedDirectoryTest CollectorSweepTest \
  CollectorTest AgentToolTest LegacyDiscoveryTest AgentBundleManifestTest AgentBootstrapHandoffTest AgentRecoveryServiceTest; do
  "$JAVA_HOME/bin/java" -Djava.io.tmpdir="$JTMP" -Xmx96m -cp "$CP" "assessment.$suite"
done
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$JTMP" -Xmx96m -cp "$CP" assessment.ServerDataSourcesTest \
  --export tmp/source-evidence/jdbc-fixtures.json
for suite in JdbcEndpointReportsTest DatabaseGroupsHttpTest EnrollmentCapacityTest EnvironmentPresenceTest ArchiveDefaultsHttpTest SettingsPathsHttpTest PortugueseTest ScheduleSpreadTest InventoryStoreTest UserAuthTest UserAccessHttpTest DashboardMaintenanceHttpTest EnvironmentInstallAccessTest EnvironmentInstallHttpTest InstallCommandTest \
  MachineActionsHttpTest MachineCleanupHttpTest AgentUpdateWorkerTest AgentUpdateStartTimeoutTest AgentUpdateCampaignsTest AgentUpdateHttpTest \
  WorkRequestsHttpTest RuntimeInventoryHttpTest CsvExportTest InventoryArchiveTest InventoryArchiveHttpTest \
  VersionedCsvHttpTest DashboardVersionHttpTest CompactOverviewTest LifecycleHttpTest PostUpdateAssessmentsTest PostUpdateAssessmentHttpTest; do
  "$JAVA_HOME/bin/java" -Djava.io.tmpdir="$JTMP" -Xmx384m -cp "$CP" "assessment.$suite"
done
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$JTMP" -Xmx384m -cp "$CP" assessment.JdbcOverviewHttpTest \
  tmp/source-evidence/jdbc-fixtures.json tmp/source-evidence/jdbc-http
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$JTMP" -Xmx384m -cp "$CP" assessment.InventoryArchiveTest --export tmp/source-evidence/inventory-export
python3 tests/inventory-archive-csv.py tmp/source-evidence/inventory-export
for suite in enrollment-capacity-ui.test.cjs inventory-export-ui.test.cjs jdbc-driver-ui.test.cjs jdbc-endpoint-ui.test.cjs datasources-ui.test.cjs environment-presence.test.cjs locale.test.cjs dashboard.test.cjs interface-refresh.test.cjs version-js.test.cjs agent-release-notice.test.cjs lifecycle-ui.test.cjs; do
  "${NODE:-node}" "tests/$suite"
done
"${NODE:-node}" tests/jdbc-overview.test.cjs tmp/source-evidence/jdbc-http/overview-fixtures.json
printf '%s\n' 'Source regression checks passed; heavy stress and external hosts were not exercised.'
