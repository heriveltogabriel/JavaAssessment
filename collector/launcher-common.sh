#!/bin/sh
# Sourced by package launchers. The caller sets ASSESSMENT_ROOT and umask 077.
# Keep inherited JVM injection options out of this process and its logs.
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG CLASSPATH ENV BASH_ENV
ASSESSMENT_RUN_UID=$(/usr/bin/id -u)
if test "$ASSESSMENT_RUN_UID" = 0; then
  unset ASSESSMENT_JAVA ASSESSMENT_JAVA_HOME JAVA_HOME
  PATH=/usr/sbin:/usr/bin:/sbin:/bin
  export PATH
  # Validate the helper itself before executing it. Installed launchers are
  # protected by the root-only installation directory and administrative setup.
  ASSESSMENT_TRUST_PATH="$ASSESSMENT_ROOT/root-trust.sh"
  test -f "$ASSESSMENT_TRUST_PATH" || { echo 'Root trust helper is missing' >&2; exit 1; }
  while :; do
    test ! -L "$ASSESSMENT_TRUST_PATH" && test "$(stat -c %u "$ASSESSMENT_TRUST_PATH")" = 0 || { echo 'Root trust helper must have a root-owned path without symbolic links' >&2; exit 1; }
    ASSESSMENT_TRUST_MODE=$(stat -c %a "$ASSESSMENT_TRUST_PATH")
    test "$((0$ASSESSMENT_TRUST_MODE & 022))" = 0 || { echo 'Root trust helper path is writable by another account' >&2; exit 1; }
    test "$ASSESSMENT_TRUST_PATH" != / || break
    ASSESSMENT_TRUST_PATH=$(dirname "$ASSESSMENT_TRUST_PATH")
  done
  /bin/sh "$ASSESSMENT_ROOT/root-trust.sh" package "$ASSESSMENT_ROOT" || exit 1
fi
assessment_check_private_file() {
  test -f "$1" && test ! -L "$1" || { echo 'Protected configuration missing or symbolic link refused' >&2; return 1; }
  ASSESSMENT_MODE=$(stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1")
  case "$ASSESSMENT_MODE" in 600|400) ;; *) echo 'Credential file must have permissions 600 or 400' >&2; return 1;; esac
  ASSESSMENT_OWNER=$(stat -c %u "$1" 2>/dev/null || stat -f %u "$1")
  test "$ASSESSMENT_OWNER" = "$(id -u)" || { echo 'Credential file must belong to the collector user' >&2; return 1; }
  if test "$ASSESSMENT_RUN_UID" = 0; then
    /bin/sh "$ASSESSMENT_ROOT/root-trust.sh" private "$1" || return 1
  fi
}
assessment_select_java() {
ASSESSMENT_CP="$ASSESSMENT_ROOT/classes"
if test -f "$ASSESSMENT_ROOT/collector.jar"; then ASSESSMENT_CP="$ASSESSMENT_ROOT/collector.jar"; fi
for ASSESSMENT_LIBRARY in "$ASSESSMENT_ROOT"/lib/*.jar; do
  test -f "$ASSESSMENT_LIBRARY" && ASSESSMENT_CP="$ASSESSMENT_CP:$ASSESSMENT_LIBRARY"
done
ASSESSMENT_JAVA_BIN=''
ASSESSMENT_PROBED='|'
ASSESSMENT_PROBE_COUNT=0
if test -z "${ASSESSMENT_JAVA:-}" && test -f "$ASSESSMENT_ROOT/conf/java.path"; then
  assessment_check_private_file "$ASSESSMENT_ROOT/conf/java.path"
  IFS= read -r ASSESSMENT_SAVED_JAVA < "$ASSESSMENT_ROOT/conf/java.path" || true
  case "${ASSESSMENT_SAVED_JAVA:-}" in /*) ASSESSMENT_JAVA=$ASSESSMENT_SAVED_JAVA;; *) echo 'Saved Java path must be absolute' >&2; exit 1;; esac
fi
if test "$ASSESSMENT_RUN_UID" = 0; then
  test -n "${ASSESSMENT_JAVA:-}" || { echo 'Root collector requires an explicit trusted Java in conf/java.path' >&2; exit 1; }
  ASSESSMENT_JAVA=$(/bin/sh "$ASSESSMENT_ROOT/root-trust.sh" java "$ASSESSMENT_JAVA") || exit 1
fi
assessment_try_java() {
  test -n "$1" && test -x "$1" || return 1
  case "$ASSESSMENT_PROBED" in *"|$1|"*) return 1;; esac
  test "$ASSESSMENT_PROBE_COUNT" -lt 24 || return 1
  ASSESSMENT_PROBE_COUNT=$(( ASSESSMENT_PROBE_COUNT + 1 ))
  ASSESSMENT_PROBED="$ASSESSMENT_PROBED$1|"
  # Only an explicitly selected, PATH, already-running, or well-known Java is probed.
  "$1" -Xmx64m -Djava.security.egd=file:/dev/./urandom -cp "$ASSESSMENT_CP" assessment.JavaProbe >/dev/null 2>&1 &
  ASSESSMENT_PROBE_PID=$!
  ASSESSMENT_PROBE_SECONDS=0
  while kill -0 "$ASSESSMENT_PROBE_PID" 2>/dev/null; do
    if test "$ASSESSMENT_PROBE_SECONDS" -ge 5; then
      kill -TERM "$ASSESSMENT_PROBE_PID" 2>/dev/null || true
      sleep 1
      kill -KILL "$ASSESSMENT_PROBE_PID" 2>/dev/null || true
      wait "$ASSESSMENT_PROBE_PID" 2>/dev/null || true
      return 1
    fi
    sleep 1
    ASSESSMENT_PROBE_SECONDS=$(( ASSESSMENT_PROBE_SECONDS + 1 ))
  done
  if wait "$ASSESSMENT_PROBE_PID"; then ASSESSMENT_JAVA_BIN=$1; return 0; fi
  return 1
}
if test -n "${ASSESSMENT_JAVA:-}"; then
  assessment_try_java "$ASSESSMENT_JAVA" || { echo 'Explicit ASSESSMENT_JAVA is not usable' >&2; exit 1; }
fi
# Privileged execution never falls back to process discovery, PATH, or JAVA_HOME.
if test "$ASSESSMENT_RUN_UID" = 0; then return 0; fi
if test -z "$ASSESSMENT_JAVA_BIN" && test -n "${ASSESSMENT_JAVA_HOME:-}"; then
  assessment_try_java "$ASSESSMENT_JAVA_HOME/bin/java" || { echo 'Explicit ASSESSMENT_JAVA_HOME is not compatible with the collector' >&2; exit 1; }
fi
if test -z "$ASSESSMENT_JAVA_BIN" && test -n "${JAVA_HOME:-}"; then
  assessment_try_java "$JAVA_HOME/bin/java" || { echo 'Explicit JAVA_HOME is not compatible with the collector' >&2; exit 1; }
fi
if test -z "$ASSESSMENT_JAVA_BIN"; then
  for ASSESSMENT_EXE in /proc/[0-9]*/exe; do
    ASSESSMENT_CANDIDATE=$(readlink -f "$ASSESSMENT_EXE" 2>/dev/null || true)
    case "$ASSESSMENT_CANDIDATE" in */bin/java) assessment_try_java "$ASSESSMENT_CANDIDATE" && break || true;; esac
  done
fi
if test -z "$ASSESSMENT_JAVA_BIN"; then assessment_try_java "$(command -v java 2>/dev/null || true)" || true; fi
if test -z "$ASSESSMENT_JAVA_BIN"; then
  for ASSESSMENT_CANDIDATE in /usr/java/latest/bin/java /usr/java/*/bin/java /usr/lib/jvm/*/bin/java /opt/java/*/bin/java /opt/java-lab/jdk*/bin/java /home/opc/oracle/jdk*/bin/java /opc/oracle/jdk*/bin/java; do
    assessment_try_java "$ASSESSMENT_CANDIDATE" && break || true
  done
fi
test -n "$ASSESSMENT_JAVA_BIN" || { echo 'Java not found; set ASSESSMENT_JAVA or JAVA_HOME' >&2; exit 1; }

}
assessment_execute() {
  ASSESSMENT_LIMIT=$1
  shift
  nice -n 10 "$ASSESSMENT_JAVA_BIN" -Xms16m -Xmx96m -Djava.awt.headless=true -Djava.security.egd=file:/dev/./urandom -Dhttp.keepAlive=false \
    -cp "$ASSESSMENT_CP" "$@" &
  ASSESSMENT_PID=$!
  (
    ASSESSMENT_ELAPSED=0
    while test "$ASSESSMENT_ELAPSED" -lt "$ASSESSMENT_LIMIT"; do
      sleep 5
      ASSESSMENT_ELAPSED=$(( ASSESSMENT_ELAPSED + 5 ))
    done
    echo "ASSESSMENT_WATCHDOG_TIMEOUT_SECONDS=$ASSESSMENT_LIMIT" >&2
    kill -TERM "$ASSESSMENT_PID" 2>/dev/null || true
    sleep 5
    kill -KILL "$ASSESSMENT_PID" 2>/dev/null || true
  ) &
  ASSESSMENT_WATCHDOG_PID=$!
  assessment_cleanup() {
    kill -TERM "$ASSESSMENT_WATCHDOG_PID" 2>/dev/null || true
    wait "$ASSESSMENT_WATCHDOG_PID" 2>/dev/null || true
  }
  trap 'kill -TERM "$ASSESSMENT_PID" 2>/dev/null || true; assessment_cleanup; exit 1' HUP INT TERM
  ASSESSMENT_EXIT=0
  wait "$ASSESSMENT_PID" || ASSESSMENT_EXIT=$?
  assessment_cleanup
  return "$ASSESSMENT_EXIT"
}
