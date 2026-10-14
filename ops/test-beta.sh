#!/bin/sh
# Disposable local fixtures only. Does not connect to any installed dashboard.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ]; then
  printf '%s\n' 'Set JAVA_HOME to a JDK 17+ directory.' >&2
  exit 1
fi
export BETA_JAVA="$JAVA_HOME/bin/java"
mkdir -p tmp/beta-classes
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp 'lib/*' \
  -d tmp/beta-classes shared/*.java server/*.java \
  tests/TestAccounts.java tests/UserAuthTest.java tests/UserAccessHttpTest.java \
  tests/InventoryStoreTest.java tests/BetaServerTest.java \
  tests/BetaLoadTest.java tests/BetaUiFixture.java
"$BETA_JAVA" -Xmx256m -cp 'tmp/beta-classes:lib/*' assessment.InventoryStoreTest
"$BETA_JAVA" -Xmx256m -cp 'tmp/beta-classes:lib/*' assessment.BetaServerTest
"${NODE:-node}" tests/beta-ui.test.cjs
"${NODE:-node}" tests/beta-ui-browser.cjs
"${NODE:-node}" tests/beta-ui-real-browser.cjs
if [ "${BETA_LOAD:-1}" = 1 ]; then
  mkdir -p tmp/beta-reports
  "$BETA_JAVA" -Xmx256m -cp 'tmp/beta-classes:lib/*' \
    assessment.BetaLoadTest tmp/beta-reports/beta-1000-load.json
fi
printf '%s\n' 'Beta local functional, browser and requested load checks passed.'
