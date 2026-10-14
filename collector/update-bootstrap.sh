#!/bin/sh
# Independent recovery service. All paths/actions are fixed; remote data is never shell.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_HOME CLASSPATH ENV BASH_ENV
umask 077
ROOT=/opt/java-assessment-collector
HELPER=/opt/java-assessment-update
BOOT=$HELPER/bootstrap
STATE=$HELPER/state
SELF=$(readlink -f "$0")
INIT=$(readlink -f /etc/init.d)
test "$(uname -s)" = Linux && test "$(id -u)" = 0 || exit 2
fail() { echo AGENT_BUNDLE_LOCAL_CHECK_FAILED >&2; exit 1; }
trust() {
  if test -f "$BOOT/root-trust.sh"; then TRUST=$BOOT/root-trust.sh; else TRUST=$ROOT/root-trust.sh; fi
  path=$TRUST
  while :; do
    test ! -L "$path" && test "$(stat -c %u "$path")" = 0 || fail
    mode=$(stat -c %a "$path"); test "$((0$mode & 022))" = 0 || fail
    test "$path" != / || break; path=$(dirname "$path")
  done
  /bin/sh "$TRUST" package "$ROOT"
  /bin/sh "$TRUST" package "$HELPER"
  for tree in "$ROOT" "$HELPER"; do
    test -z "$(find "$tree" -xdev \( -type l -o \( -type f -links +1 \) \) -print -quit)" || fail
    awk -v prefix="$tree/" 'index($2,prefix)==1 {bad=1} END {exit bad?1:0}' /proc/mounts || fail
  done
}
java_path() { IFS= read -r JAVA < "$ROOT/conf/java.path"; JAVA=$(/bin/sh "$TRUST" java "$JAVA"); }
run_jar() {
  jar=$1; shift
  cp_path=$jar
  jar_dir=$(dirname "$jar")
  for library in "$jar_dir"/lib/*.jar; do test ! -f "$library" || cp_path="$cp_path:$library"; done
  managed_limit=90; test "${1:-}" != cycle || managed_limit=1800
  "$JAVA" -Xms16m -Xmx96m -Djava.security.egd=file:/dev/./urandom -Dhttp.keepAlive=false -cp "$cp_path" assessment.AgentMigrationBootstrap "$@" &
  managed_pid=$!; managed_elapsed=0
  trap 'kill -TERM "$managed_pid" 2>/dev/null || true; sleep 1; kill -KILL "$managed_pid" 2>/dev/null || true; wait "$managed_pid" 2>/dev/null || true; exit 1' HUP INT TERM
  while kill -0 "$managed_pid" 2>/dev/null; do
    if test "$managed_elapsed" -ge "$managed_limit"; then
      kill -TERM "$managed_pid" 2>/dev/null || true; sleep 1; kill -KILL "$managed_pid" 2>/dev/null || true; wait "$managed_pid" 2>/dev/null || true
      echo AGENT_MANAGER_TIMEOUT >&2; return 30
    fi
    sleep 1; managed_elapsed=$((managed_elapsed + 1))
  done
  managed_result=0; wait "$managed_pid" || managed_result=$?
  trap - HUP INT TERM
  return "$managed_result"
}
anchor() { run_jar "$BOOT/anchor.jar" "$@"; }
operation_arg() { case "$1" in ????????-????-????-????-????????????) case "$1" in *[!a-f0-9-]*) fail;; esac;; *) fail;; esac; }
scheduler() { IFS= read -r SCHEDULER < "$HELPER/scheduler"; case "$SCHEDULER" in systemd|sysv) ;; *) fail;; esac; }
recovery_alive() {
  test -f "$STATE/recovery.pid" && test ! -L "$STATE/recovery.pid" || return 1
  IFS= read -r recovery_pid < "$STATE/recovery.pid" || return 1
  case "$recovery_pid" in ''|*[!0-9]*) return 1;; esac
  test -r "/proc/$recovery_pid/status" && test -r "/proc/$recovery_pid/cmdline" || return 1
  test "$(awk '/^Uid:/ {print $2; exit}' "/proc/$recovery_pid/status")" = 0 || return 1
  tr '\000' ' ' < "/proc/$recovery_pid/cmdline" | grep -F "$BOOT/update-bootstrap.sh loop" >/dev/null || return 1
  kill -0 "$recovery_pid" 2>/dev/null
}
wait_collector() {
  waited=0
  while test -d "$ROOT/collector-spool/.poll-lock"; do
    test ! -L "$ROOT/collector-spool/.poll-lock" || fail
    pid=$(cat "$ROOT/collector-spool/.poll-lock/pid" 2>/dev/null || true)
    case "$pid" in ''|*[!0-9]*) fail;; esac
    if ! kill -0 "$pid" 2>/dev/null; then rm -f "$ROOT/collector-spool/.poll-lock/pid"; rmdir "$ROOT/collector-spool/.poll-lock"; break; fi
    test "$waited" -lt 1500 || fail; sleep 5; waited=$((waited + 5))
  done
}
bounded_probe() {
  cp_path=$1; shift
  "$JAVA" -Xms16m -Xmx96m -Djava.security.egd=file:/dev/./urandom -Dhttp.keepAlive=false -cp "$cp_path" assessment.AgentUpdateProbe "$@" &
  pid=$!; elapsed=0
  trap 'kill -TERM "$pid" 2>/dev/null || true; sleep 1; kill -KILL "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true' HUP INT TERM
  while kill -0 "$pid" 2>/dev/null; do
    if test "$elapsed" -ge 60; then
      kill -TERM "$pid" 2>/dev/null || true; sleep 1; kill -KILL "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
      test "$PROBE_MODE" != heartbeat || exit 10; exit 20
    fi
    sleep 1; elapsed=$((elapsed + 1))
  done
  result=0; wait "$pid" || result=$?; return "$result"
}
trust
case "${1:-}" in
  install-bootstrap)
    test "$#" = 2 || exit 2; operation_arg "$2"; operation=$2
    . "$ROOT/lifecycle-lock.sh"
    test ! -e "$HELPER/journal.json" && test ! -e "$ROOT/collector-spool/update-request.json" || fail
    case "$SELF" in "$HELPER/transactions/$operation"/prepared-[0-9]*/bootstrap/update-bootstrap.sh) ;; *) fail;; esac
    STAGED=$(dirname "$SELF")
    test -f "$STAGED/anchor.jar" && test -f "$STAGED/root-trust.sh" || fail
    java_path
    run_jar "$STAGED/anchor.jar" validate-prepared "$operation"
    # No installed recovery component is replaced by a later transaction.
    if test ! -d "$BOOT"; then
      BOOT_STAGE=$HELPER/bootstrap-prepared-$operation-$$
      mkdir "$BOOT_STAGE"; chmod 700 "$BOOT_STAGE"
      for file in anchor.jar root-trust.sh update-bootstrap.sh; do cp "$STAGED/$file" "$BOOT_STAGE/$file"; chmod 600 "$BOOT_STAGE/$file"; done
      mkdir "$BOOT_STAGE/lib"; chmod 700 "$BOOT_STAGE/lib"
      # These libraries are authenticated by the same initial bundle inventory.
      # Retain independent copies permanently; never borrow future ROOT/lib.
      for library in "$STAGED"/lib/*.jar; do
        test ! -f "$library" || { cp "$library" "$BOOT_STAGE/lib/"; chmod 600 "$BOOT_STAGE/lib/$(basename "$library")"; }
      done
      cp "$HELPER/transactions/$operation/manifest.json" "$BOOT_STAGE/bundle-manifest.json"
      cp "$HELPER/transactions/$operation/manifest.sig" "$BOOT_STAGE/bundle-manifest.sig"
      chmod 600 "$BOOT_STAGE/bundle-manifest.json" "$BOOT_STAGE/bundle-manifest.sig"
      sync
      test ! -e "$BOOT" || fail
      mv "$BOOT_STAGE" "$BOOT"; sync
    else
      test -f "$BOOT/anchor.jar" && test -f "$BOOT/root-trust.sh" && test -f "$BOOT/update-bootstrap.sh" || fail
    fi
    anchor seal-bootstrap "$operation"
    anchor capture-origin "$operation"
    scheduler
    if test "$SCHEDULER" = systemd; then
      test ! -L /etc/systemd/system/java-assessment-recovery.service || fail
      if test -f /etc/systemd/system/java-assessment-recovery.service; then
        cmp -s /etc/systemd/system/java-assessment-recovery.service "$BOOT/recovery.service" || fail
      else
        cat > "$BOOT/recovery.service" <<'UNIT'
# Java Assessment independent signed update recovery
[Unit]
Description=Java Assessment independent update recovery
After=network-online.target
Wants=network-online.target
Before=java-assessment-collector.service java-assessment-collector.timer
[Service]
Type=simple
User=root
Group=root
UMask=0077
ExecStart=/bin/sh /opt/java-assessment-update/bootstrap/update-bootstrap.sh loop
Restart=on-failure
RestartSec=15
TimeoutStopSec=1800
NoNewPrivileges=true
[Install]
WantedBy=multi-user.target
UNIT
        cp "$BOOT/recovery.service" /etc/systemd/system/java-assessment-recovery.service
        chmod 644 /etc/systemd/system/java-assessment-recovery.service
      fi
      systemctl disable --now java-assessment-update.service
      systemctl daemon-reload; systemctl enable java-assessment-recovery.service
      # The bridge is still inside the activating collector oneshot/cgroup.
      # Queue the independent service and exit; waiting for Before= ordering
      # here would wait for our own collector cycle to finish.
      systemctl start --no-block java-assessment-recovery.service
    else
      case "$INIT" in /etc/init.d|/etc/rc.d/init.d) ;; *) fail;; esac
      test ! -e "$INIT/java-assessment-recovery" || cmp -s "$INIT/java-assessment-recovery" "$BOOT/recovery.init" || fail
      /bin/sh "$HELPER/update-service.sh" stop
      chkconfig java-assessment-update off
      cat > "$BOOT/recovery.init" <<'INITFILE'
#!/bin/sh
# chkconfig: 345 94 06
# description: Java Assessment independent signed update recovery
exec /bin/sh /opt/java-assessment-update/bootstrap/update-bootstrap.sh "$@"
INITFILE
      cp "$BOOT/recovery.init" "$INIT/java-assessment-recovery"; chmod 755 "$INIT/java-assessment-recovery"
      chkconfig --add java-assessment-recovery; chkconfig java-assessment-recovery on
      /bin/sh "$BOOT/update-bootstrap.sh" start
    fi
    sync;;
  check) test "$#" = 2 || exit 2;;
  sync) sync;;
  snapshot-services)
    test "$#" = 2 || exit 2; operation_arg "$2"; scheduler
    saved=$HELPER/transactions/$2/previous-services; mkdir -p "$saved"; chmod 700 "$saved"
    if test "$SCHEDULER" = systemd; then
      for suffix in service timer; do
        test "$(systemctl show java-assessment-collector.$suffix -p DropInPaths)" = DropInPaths= || fail
        target=/etc/systemd/system/java-assessment-collector.$suffix
        cmp -s "$target" "$HELPER/collector.$suffix" || fail
        cp "$target" "$saved/collector.$suffix"; chmod 600 "$saved/collector.$suffix"
      done
    else
      cmp -s "$INIT/java-assessment-collector" "$HELPER/collector.init" || fail
      cp "$INIT/java-assessment-collector" "$saved/collector.init"; chmod 600 "$saved/collector.init"
    fi;;
  snapshot-uninstall|activate-uninstall|restore-uninstall)
    test "$#" = 2 || exit 2; operation_arg "$2"; java_path
    cp_path=$BOOT/anchor.jar
    for library in "$BOOT"/lib/*.jar; do test ! -f "$library" || cp_path="$cp_path:$library"; done
    "$JAVA" -Xms16m -Xmx96m -Djava.security.egd=file:/dev/./urandom -cp "$cp_path" assessment.AgentV3UninstallInventory "$1" "$2";;
  pause)
    test "$#" = 2 || exit 2; scheduler
    if test "$SCHEDULER" = systemd; then systemctl stop java-assessment-collector.timer; fi
    wait_collector
    if test "$SCHEDULER" = systemd; then
      systemctl stop java-assessment-collector.service
      test "$(systemctl show java-assessment-collector.service -p MainPID)" = MainPID=0 || fail
    else /bin/sh "$ROOT/root-service.sh" stop; fi
    for command in /proc/[0-9]*/cmdline; do
      test -r "$command" || continue
      if tr '\000' ' ' < "$command" | grep -F "$ROOT/" | grep -E 'assessment\.(AssessmentCollector|AgentTool)( |$)' >/dev/null; then fail; fi
    done;;
  activate-services)
    test "$#" = 2 || exit 2; operation_arg "$2"
    for entry in poll.sh run-once.sh agent-tool.sh; do
      cat > "$ROOT/$entry.next" <<'ENTRY'
#!/bin/sh
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_HOME CLASSPATH ENV BASH_ENV
test "$(id -u)" = 0 || exit 2
BOOT=/opt/java-assessment-update/bootstrap
/bin/sh "$BOOT/root-trust.sh" package /opt/java-assessment-collector
/bin/sh "$BOOT/root-trust.sh" package /opt/java-assessment-update
IFS= read -r JAVA < /opt/java-assessment-collector/conf/java.path
JAVA=$(/bin/sh "$BOOT/root-trust.sh" java "$JAVA")
CODE=$("$JAVA" -Xms16m -Xmx96m -Djava.security.egd=file:/dev/./urandom -cp "$BOOT/anchor.jar" assessment.AgentMigrationBootstrap code-path)
case "$CODE" in /opt/java-assessment-collector/releases/*) ;; *) exit 1;; esac
ENTRY
      printf 'exec /bin/sh "$CODE/%s" "$@"\n' "$entry" >> "$ROOT/$entry.next"
      chmod 700 "$ROOT/$entry.next"; mv "$ROOT/$entry.next" "$ROOT/$entry"
    done
    printf 'update-v3\n' > "$ROOT/conf/update-capability"; chmod 600 "$ROOT/conf/update-capability"
    sync;;
  restore-services)
    # Collector definitions retain their owned fixed entry paths. Recovery
    # restored their scripts and capability before reaching this action.
    test "$#" = 2 || exit 2; scheduler
    if test "$(cat "$ROOT/conf/update-capability")" != update-v3; then
      if test "$SCHEDULER" = systemd; then systemctl enable --now java-assessment-update.service
      else chkconfig java-assessment-update on; /bin/sh "$HELPER/update-service.sh" start; fi
    fi;;
  start)
    if test "$#" = 1; then
      recovery_alive && exit 0
      rm -f "$STATE/recovery.pid"
      rm -f "$STATE/recovery.stop"
      nohup /bin/sh "$BOOT/update-bootstrap.sh" loop 9>&- >/dev/null 2>&1 < /dev/null &
      printf '%s\n' "$!" > "$STATE/recovery.pid"; exit 0
    fi
    test "$#" = 2 || exit 2; scheduler
    if test "$SCHEDULER" = systemd; then systemctl start java-assessment-collector.timer; systemctl is-active --quiet java-assessment-collector.timer || fail
    else /bin/sh "$ROOT/root-service.sh" start; fi;;
  probe)
    test "$#" = 4 || exit 2
    case "$2" in ''|*[!A-Za-z0-9_.-]*|*..*) fail;; esac
    case "$3" in ''|*[!0-9.]*) fail;; esac
    case "$4" in probe|heartbeat) PROBE_MODE=$4;; *) fail;; esac
    java_path; code=$ROOT/releases/$2; test -f "$code/collector.jar" || fail
    cp_path=$code/collector.jar; for library in "$code"/lib/*.jar; do test ! -f "$library" || cp_path="$cp_path:$library"; done
    if test "$PROBE_MODE" = heartbeat; then bounded_probe "$cp_path" heartbeat "$3" "$ROOT/conf/collector.properties"
    else bounded_probe "$cp_path" probe "$3"; fi;;
  probe-manager|evidence)
    java_path; manager=$(anchor manager-path); expected=$(anchor manager-hash)
    test "$1" != evidence || { test "$#" = 2 && test "$2" = "$expected"; } || fail
    run_jar "$manager" manager-probe "$expected"
    scheduler
    if test "$SCHEDULER" = systemd; then systemctl is-active --quiet java-assessment-recovery.service || fail
    else recovery_alive || fail; fi;;
  once)
    test "$#" = 1 || exit 2
    if test ! -f "$STATE/transaction.json" && test ! -f "$ROOT/collector-spool/update-v3-request.json"; then
      test -f "$STATE/cleanup.next" || exit 0
      IFS= read -r cleanup_next < "$STATE/cleanup.next"; case "$cleanup_next" in ''|*[!0-9]*) fail;; esac
      cleanup_now=$(date +%s); test "$cleanup_now" -ge "$cleanup_next" || exit 0
      . "$ROOT/lifecycle-lock.sh"
      # Rate-limit even corrupt queue metadata; idle must not launch a JVM in
      # every 15-second supervisor cycle after a housekeeping error.
      printf '%s\n' "$((cleanup_now + 86400))" > "$STATE/cleanup.next.tmp"; mv "$STATE/cleanup.next.tmp" "$STATE/cleanup.next"
      java_path; anchor maintenance; exit 0
    fi
    . "$ROOT/lifecycle-lock.sh"
    java_path
    if ! manager=$(anchor manager-path); then anchor recover-manager; exit 0; fi
    if test "$manager" != "$BOOT/anchor.jar"; then
      expected=$(anchor manager-hash)
      if ! run_jar "$manager" manager-probe "$expected"; then anchor recover-manager; exit 0; fi
    fi
    run_jar "$manager" cycle;;
  loop)
    test "$#" = 1 || exit 2; exec 9>&-
    printf '%s\n' "$$" > "$STATE/recovery.pid"
    trap 'rm -f "$STATE/recovery.pid"' 0
    trap ': > "$STATE/recovery.stop"' HUP INT TERM
    while test ! -f "$STATE/recovery.stop"; do
      /bin/sh "$BOOT/update-bootstrap.sh" once > "$STATE/diagnostic.tmp" 2>&1 || true
      if test -s "$STATE/diagnostic.tmp"; then tail -c 4096 "$STATE/diagnostic.tmp" > "$STATE/diagnostic.txt"; fi
      rm -f "$STATE/diagnostic.tmp"
      if test ! -f "$STATE/transaction.json" && test ! -f "$STATE/cleanup.next" && test "$(cat "$ROOT/conf/update-capability")" != update-v3; then exit 0; fi
      sleep 15
    done;;
  stop)
    : > "$STATE/recovery.stop"; elapsed=0
    while recovery_alive; do
      test "$elapsed" -lt 1800 || fail; sleep 5; elapsed=$((elapsed + 5))
    done;;
  status)
    recovery_alive || exit 3; echo 'Independent update recovery is running.';;
  *) exit 2;;
esac
