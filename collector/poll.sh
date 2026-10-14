#!/bin/sh
set -u
umask 077
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG CLASSPATH ENV BASH_ENV
if test "$(/usr/bin/id -u)" = 0; then PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH; fi
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if test "$(/usr/bin/id -u)" = 0; then
  # Reuse the root-only helper/path/package checks before touching locks or logs.
  ASSESSMENT_ROOT=$ROOT
  . "$ROOT/launcher-common.sh"
fi
# Shell lock covers event retry and log rotation; Java also locks the scan itself.
# The independent updater sends its own version heartbeat while this durable
# journal blocks collection. A lost network ACK must not dispatch a new scan.
if test "$ROOT" = /opt/java-assessment-collector && test -f /opt/java-assessment-update/journal.json; then exit 0; fi
INSTALL_LOCK=$ROOT/collector-spool/.install-lock
if test -d "$INSTALL_LOCK"; then
  INSTALL_PID=''
  test ! -f "$INSTALL_LOCK/pid" || read -r INSTALL_PID < "$INSTALL_LOCK/pid"
  case "$INSTALL_PID" in
    ''|*[!0-9]*) test -n "$(find "$INSTALL_LOCK" -prune -mmin +2 2>/dev/null)" || exit 0;;
    *) kill -0 "$INSTALL_PID" 2>/dev/null && exit 0;;
  esac
  rm -f "$INSTALL_LOCK/pid"
  rmdir "$INSTALL_LOCK" 2>/dev/null || exit 0
fi
LOCK=$ROOT/collector-spool/.poll-lock
if ! mkdir "$LOCK" 2>/dev/null; then
  if test -f "$LOCK/pid"; then
    read -r OLD_PID < "$LOCK/pid"
    case "$OLD_PID" in ''|*[!0-9]*) exit 0;; esac
    if ! kill -0 "$OLD_PID" 2>/dev/null; then
      rm -f "$LOCK/pid"
      rmdir "$LOCK" 2>/dev/null || exit 0
      mkdir "$LOCK" 2>/dev/null || exit 0
    else exit 0; fi
  else
    # A crash between mkdir and pid publication leaves an empty lock. Remove only
    # a directory older than two minutes; rmdir refuses a live/nonempty lock.
    if test -n "$(find "$LOCK" -prune -mmin +2 2>/dev/null)"; then
      rmdir "$LOCK" 2>/dev/null || exit 0
      mkdir "$LOCK" 2>/dev/null || exit 0
    else exit 0; fi
  fi
fi
echo "$$" > "$LOCK/pid"
trap 'rm -f "$LOCK/pid"; rmdir "$LOCK" 2>/dev/null || true' 0
LOG=$ROOT/logs/collector.log
if test -f "$LOG" && test "$(wc -c < "$LOG")" -gt 2097152; then
  test ! -f "$LOG.2" || mv "$LOG.2" "$LOG.3"
  test ! -f "$LOG.1" || mv "$LOG.1" "$LOG.2"
  mv "$LOG" "$LOG.1"
fi
COUNT=0
for EVENTS in "$ROOT"/logs/install-events-*.json; do
  test -f "$EVENTS" || continue
  COUNT=$((COUNT + 1))
  test "$COUNT" -le 3 || break
  # An active installer may still append; it uploads that run itself.
  test ! -d "$ROOT/collector-spool/.install-lock" || break
  if "$ROOT/agent-tool.sh" events "$ROOT/conf/collector.properties" "$EVENTS" >> "$LOG" 2>&1; then mv "$EVENTS" "$EVENTS.sent"; fi
done
printf '%s collector poll\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$LOG"
"$ROOT/run-once.sh" "$ROOT/conf/collector.properties" >> "$LOG" 2>&1
