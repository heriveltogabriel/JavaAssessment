#!/bin/sh
# Real-Linux regression for JDBC driver resolution (needs Docker and JAVA_HOME of a JDK 17+; not part of test-source.sh).
# Expects: with a command line over 1 KB (clipped hsperfdata vmArgs) the driver is IDENTIFIED, and with
# JAVA_TOOL_OPTIONS in the environment it stays INCOMPLETE with an explicit reason.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17+ directory}"
rm -rf tmp/linux-e2e-classes && mkdir -p tmp/linux-e2e-classes
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -nowarn -cp 'lib/*' -d tmp/linux-e2e-classes $(git ls-files 'shared/*.java' 'server/*.java' 'collector/*.java' 'tests/*.java')
docker build -q -t java-assessment-linux-e2e tests/linux-e2e >/dev/null
run() { docker run --rm -v "$(pwd)/tests/linux-e2e:/work:ro" -v "$(pwd)/lib:/repo/lib:ro" -v "$(pwd)/tmp/linux-e2e-classes:/classes:ro" java-assessment-linux-e2e sh /work/run-container.sh /classes "$@"; }
OUT=$(run); echo "$OUT"
echo "$OUT" | grep -q "DS Orders -> IDENTIFIED 19.3.0.0.0" || { echo "FAIL: driver not identified with a clipped vmArgs"; exit 1; }
OUT=$(run JAVA_TOOL_OPTIONS=-Dx=1); echo "$OUT"
echo "$OUT" | grep -q "DS Orders -> INCOMPLETE null | Opções da JVM no ambiente (JAVA_TOOL_OPTIONS)" || { echo "FAIL: environment options must stay uncertain"; exit 1; }
echo "LINUX_REAL_E2E=PASS clipped-vmArgs identified; JAVA_TOOL_OPTIONS stays INCOMPLETE"
