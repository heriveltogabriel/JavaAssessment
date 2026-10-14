#!/bin/sh
# Independent root service. Never launched as a descendant of collector.service.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_HOME ASSESSMENT_JAVA ASSESSMENT_JAVA_HOME CLASSPATH ENV BASH_ENV
umask 077
HOME_DIR=/opt/java-assessment-uninstall
test "$(id -u)" = 0 || exit 2
case "${1:-}" in
  start)
    if test -f "$HOME_DIR/supervisor.pid"; then
      pid=$(cat "$HOME_DIR/supervisor.pid");case "$pid" in ''|*[!0-9]*) exit 1;; esac
      if kill -0 "$pid" 2>/dev/null; then exit 0; fi
    fi
    nohup /bin/sh "$HOME_DIR/uninstall-service.sh" loop 9>&- >/dev/null 2>&1 < /dev/null &
    printf '%s\n' "$!" > "$HOME_DIR/supervisor.pid";;
  stop)
    # Used only by administrative reinstall, finish current invocation first.
    : > "$HOME_DIR/stop"
    elapsed=0
    while test -f "$HOME_DIR/supervisor.pid"; do
      pid=$(cat "$HOME_DIR/supervisor.pid");case "$pid" in ''|*[!0-9]*) exit 1;; esac
      kill -0 "$pid" 2>/dev/null || break
      test "$elapsed" -lt 1800 || exit 1;sleep 5;elapsed=$((elapsed+5))
    done;;
  loop)
    printf '%s\n' "$$" > "$HOME_DIR/supervisor.pid"
    trap 'test ! -d "$HOME_DIR" || rm -f "$HOME_DIR/supervisor.pid"' 0
    while test -d "$HOME_DIR" && test ! -f "$HOME_DIR/stop"; do
      /bin/sh "$HOME_DIR/root-trust.sh" package "$HOME_DIR"
      IFS= read -r java < "$HOME_DIR/java.path"
      java=$(/bin/sh "$HOME_DIR/root-trust.sh" java "$java")
      cp_path=$HOME_DIR/collector.jar
      for lib in "$HOME_DIR"/lib/*.jar; do cp_path="$cp_path:$lib";done
      # No operational token exists here until a request arrives. Output is a
      # bounded one-line diagnostic; no growing log or private response body.
      "$java" -Xms16m -Xmx64m -Dhttp.keepAlive=false -cp "$cp_path" assessment.UninstallWorker > "$HOME_DIR/diagnostic.tmp" 2>&1 || true
      if test -d "$HOME_DIR"; then
        head -c 1024 "$HOME_DIR/diagnostic.tmp" > "$HOME_DIR/diagnostic.txt";rm -f "$HOME_DIR/diagnostic.tmp"
        sleep 15
      fi
    done;;
  *) exit 2;;
esac
