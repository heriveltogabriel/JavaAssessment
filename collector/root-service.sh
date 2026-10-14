#!/bin/sh
# Fixed root service entry point. The SysV supervisor is a shell; each JVM exits
# after its bounded poll/assessment cycle. No application JVM is signalled.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_HOME ASSESSMENT_JAVA ASSESSMENT_JAVA_HOME CLASSPATH ENV BASH_ENV
umask 077
ROOT=/opt/java-assessment-collector
test "$(id -u)" = 0 || { echo 'The system collector service requires root.' >&2; exit 2; }
/bin/sh "$ROOT/root-trust.sh" package "$ROOT"
mkdir -p "$ROOT/run" "$ROOT/logs"
chmod 700 "$ROOT/run" "$ROOT/logs"
PID_FILE=$ROOT/run/service.pid
STOP_FILE=$ROOT/run/service.stop
START_LOCK=$ROOT/run/service-start.lock
rotate_service_log() {
  service_log=$ROOT/logs/service.log
  if test -f "$service_log" && test "$(wc -c < "$service_log")" -gt 2097152; then
    test ! -f "$service_log.2" || mv "$service_log.2" "$service_log.3"
    test ! -f "$service_log.1" || mv "$service_log.1" "$service_log.2"
    mv "$service_log" "$service_log.1"
  fi
}
alive() {
  PID=''
  test -f "$PID_FILE" && test ! -L "$PID_FILE" || return 1
  IFS= read -r PID < "$PID_FILE" || return 1
  case "$PID" in ''|*[!0-9]*) return 1;; esac
  test -r "/proc/$PID/cmdline" && test -r "/proc/$PID/status" || return 1
  test "$(awk '/^Uid:/ {print $2; exit}' "/proc/$PID/status")" = 0 || return 1
  tr '\000' ' ' < "/proc/$PID/cmdline" | grep -F "$ROOT/root-service.sh loop" >/dev/null 2>&1 || return 1
  kill -0 "$PID" 2>/dev/null
}
case "${1:-}" in
  once) exec /bin/sh "$ROOT/poll.sh";;
  status)
    if alive; then
      if test -f "$STOP_FILE"; then echo 'Collector supervisor is stopping after its current cycle.'; else echo 'Collector supervisor is running.'; fi
      exit 0
    fi
    echo 'Collector supervisor is stopped.'; exit 3;;
  start)
    if alive; then echo 'Collector supervisor is already running.'; exit 0; fi
    mkdir "$START_LOCK" 2>/dev/null || { echo 'Collector service start is already in progress.' >&2; exit 1; }
    trap 'rmdir "$START_LOCK" 2>/dev/null || true' 0
    rm -f "$STOP_FILE" "$PID_FILE"
    rotate_service_log
    nohup /bin/sh "$ROOT/root-service.sh" loop 9>&- >> "$ROOT/logs/service.log" 2>&1 < /dev/null &
    SERVICE_PID=$!
    printf '%s\n' "$SERVICE_PID" > "$PID_FILE"
    sleep 1
    alive || { echo 'Collector supervisor could not start. Inspect the protected service log.' >&2; exit 1; }
    echo 'Collector supervisor started.';;
  stop)
    if ! alive; then rm -f "$STOP_FILE" "$PID_FILE"; echo 'Collector supervisor is stopped.'; exit 0; fi
    : > "$STOP_FILE"
    WAITED=0
    while alive; do
      test "$WAITED" -lt 1500 || { echo 'Collector is completing a bounded cycle; stop remains requested.' >&2; exit 1; }
      sleep 5; WAITED=$((WAITED + 5))
    done
    rm -f "$STOP_FILE" "$PID_FILE"
    echo 'Collector supervisor stopped.';;
  restart) /bin/sh "$ROOT/root-service.sh" stop; exec /bin/sh "$ROOT/root-service.sh" start;;
  loop)
    printf '%s\n' "$$" > "$PID_FILE"
    trap ': > "$STOP_FILE"' HUP INT TERM
    trap 'rm -f "$PID_FILE" "$STOP_FILE"' 0
    while test ! -f "$STOP_FILE"; do
      /bin/sh "$ROOT/root-service.sh" once || true
      PAUSES=0
      while test "$PAUSES" -lt 12 && test ! -f "$STOP_FILE"; do sleep 5; PAUSES=$((PAUSES + 1)); done
    done;;
  *) echo 'Usage: root-service.sh start|stop|restart|status|once' >&2; exit 2;;
esac
