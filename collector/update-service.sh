#!/bin/sh
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_HOME CLASSPATH ENV BASH_ENV
umask 077
HELPER=/opt/java-assessment-update
test "$(id -u)" = 0 || exit 2
case "${1:-}" in
  probe)
    test "$#" = 4 || exit 2
    case "$2" in candidate) jar=$HELPER/candidate.jar;; installed) jar=/opt/java-assessment-collector/collector.jar;; *) exit 2;; esac
    case "$3" in ''|*[!0-9.]*) exit 2;; esac
    case "$4" in probe|heartbeat) operation=$4;; *) exit 2;; esac
    test "$operation" != heartbeat || test "$2" = installed || exit 2
    IFS= read -r java < "$HELPER/java.path"
    cp_path=$jar
    for lib in "$HELPER"/lib/*.jar; do cp_path="$cp_path:$lib"; done
    set -- "$java" -Xms16m -Xmx64m -Dhttp.keepAlive=false -Djava.security.egd=file:/dev/./urandom -cp "$cp_path" assessment.AgentUpdateProbe "$operation" "$3"
    test "$operation" != heartbeat || set -- "$@" /opt/java-assessment-collector/conf/collector.properties
    "$@" &
    probe_pid=$!
    trap 'kill -TERM "$probe_pid" 2>/dev/null || true; sleep 1; kill -KILL "$probe_pid" 2>/dev/null || true; wait "$probe_pid" 2>/dev/null || true' HUP INT TERM
    waited=0
    while kill -0 "$probe_pid" 2>/dev/null; do
      if test "$waited" -ge 60; then
        kill -TERM "$probe_pid" 2>/dev/null || true; sleep 1
        kill -KILL "$probe_pid" 2>/dev/null || true; wait "$probe_pid" 2>/dev/null || true
        test "$operation" != heartbeat || exit 10
        exit 20
      fi
      sleep 1; waited=$((waited + 1))
    done
    result=0; wait "$probe_pid" || result=$?; exit "$result";;
  once)
    /bin/sh "$HELPER/root-trust.sh" package "$HELPER"
    . "$HELPER/lifecycle-lock.sh"
    /bin/sh "$HELPER/update-local.sh" check
    # A full JVM is unnecessary when no durable update is pending.
    if test ! -f "$HELPER/journal.json" && test ! -f /opt/java-assessment-collector/collector-spool/update-request.json; then exit 0; fi
    IFS= read -r java < "$HELPER/java.path"
    java=$(/bin/sh "$HELPER/root-trust.sh" java "$java")
    cp_path=$HELPER/helper.jar
    for lib in "$HELPER"/lib/*.jar; do cp_path="$cp_path:$lib"; done
    exec "$java" -Xms16m -Xmx96m -Dhttp.keepAlive=false -Djava.security.egd=file:/dev/./urandom -cp "$cp_path" assessment.AgentUpdateWorker;;
  start)
    if test -f "$HELPER/supervisor.pid"; then
      pid=$(cat "$HELPER/supervisor.pid"); case "$pid" in ''|*[!0-9]*) exit 1;; esac
      if kill -0 "$pid" 2>/dev/null; then exit 0; fi
    fi
    rm -f "$HELPER/stop"
    nohup /bin/sh "$HELPER/update-service.sh" loop 9>&- >/dev/null 2>&1 < /dev/null &
    printf '%s\n' "$!" > "$HELPER/supervisor.pid";;
  stop)
    : > "$HELPER/stop"; waited=0
    while test -f "$HELPER/supervisor.pid"; do
      pid=$(cat "$HELPER/supervisor.pid"); case "$pid" in ''|*[!0-9]*) exit 1;; esac
      kill -0 "$pid" 2>/dev/null || break
      test "$waited" -lt 1800 || exit 1; sleep 5; waited=$((waited + 5))
    done;;
  loop)
    exec 9>&-
    printf '%s\n' "$$" > "$HELPER/supervisor.pid"
    trap 'test ! -d "$HELPER" || rm -f "$HELPER/supervisor.pid"' 0
    trap ': > "$HELPER/stop"' HUP INT TERM
    while test -d "$HELPER" && test ! -f "$HELPER/stop"; do
      /bin/sh "$HELPER/update-local.sh" check >/dev/null 2>&1 || exit 1
      /bin/sh "$HELPER/update-service.sh" once > "$HELPER/diagnostic.tmp" 2>&1 || true
      if test -d "$HELPER"; then
        if test -s "$HELPER/diagnostic.tmp"; then tail -c 4096 "$HELPER/diagnostic.tmp" > "$HELPER/diagnostic.txt"; fi
        rm -f "$HELPER/diagnostic.tmp"; sleep 15
      fi
    done;;
  *) exit 2;;
esac
