#!/bin/sh
# Runs inside the container (see ../../ops/test-linux-real.sh). A real JVM 8 with a WebLogic-sized command line
# must resolve its JDBC driver even though HotSpot clips java.rt.vmArgs at 1024 bytes.
set -e
CLS=$1; shift
rm -rf /u01; mkdir -p /u01/stub /u01/lib
/opt/jdk8/bin/javac -d /u01/stub /work/stub/weblogic/Server.java
/opt/java/openjdk/bin/javac --release 17 -cp "$CLS:/repo/lib/*" -d /tmp/h /work/src/assessment/*.java
/opt/java/openjdk/bin/java -cp "/tmp/h:$CLS:/repo/lib/*" assessment.MakeFixture
PAD=""; i=0; while [ $i -lt 40 ]; do PAD="$PAD:/u01/lib/missing/library-$i.jar"; i=$((i+1)); done
BIG=$(head -c 1300 /dev/zero | tr '\0' 'x')
env "$@" /opt/jdk8/bin/java -Dweblogic.RootDirectory=/u01/domain -Dweblogic.Name=AdminServer -Dpadding=$BIG -cp "/u01/stub$PAD:/u01/lib/ojdbc8.jar" weblogic.Server &
PID=$!
sleep 3
echo "cmdline bytes=$(tr '\0' ' ' < /proc/$PID/cmdline | wc -c)"
/opt/java/openjdk/bin/java -cp "/tmp/h:$CLS:/repo/lib/*" assessment.RealProcCheck
kill $PID 2>/dev/null || true
