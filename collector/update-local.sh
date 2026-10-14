#!/bin/sh
# Fixed root-only service adapter. Never accepts remote paths or shell commands.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS ENV BASH_ENV
umask 077
ROOT=/opt/java-assessment-collector
HELPER=/opt/java-assessment-update
SYSTEMD=/etc/systemd/system
INIT=$(readlink -f /etc/init.d)
test "$(uname -s)" = Linux && test "$(id -u)" = 0 || exit 2
test "$#" = 1 || exit 2
fail() { echo AGENT_UPDATE_LOCAL_CHECK_FAILED >&2; exit 1; }
trusted() {
  path=$1
  while test "$path" != /; do
    test -d "$path" && test ! -L "$path" && test "$(stat -c %u "$path")" = 0 || fail
    mode_bits=$(stat -c %a "$path"); test "$((0$mode_bits & 022))" = 0 || fail
    path=$(dirname "$path")
  done
}
tree() {
  trusted "$1"
  bad=$(find "$1" -xdev \( -type l -o ! -uid 0 -o \( -type f -links +1 \) -o \( ! -type f ! -type d \) -o -perm -020 -o -perm -002 \) -print -quit)
  test -z "$bad" || fail
  awk -v r="$1/" 'index($2,r)==1 {bad=1} END {exit bad?1:0}' /proc/mounts || fail
}
protected_file() {
  test -f "$1" && test ! -L "$1" && test "$(stat -c %u "$1")" = 0 && test "$(stat -c %h "$1")" = 1 || fail
  file_mode=$(stat -c %a "$1"); test "$((0$file_mode & 022))" = 0 || fail
}
check() {
  tree "$HELPER"; tree "$ROOT"
  test ! -f "$ROOT/conf/server.properties" && test ! -d "$ROOT/web" || fail
  for file in conf/collector.properties conf/java.path conf/root-service-mode conf/update-capability; do
    test -f "$ROOT/$file" && test "$(stat -c %a "$ROOT/$file")" = 600 || fail
  done
  test "$(cat "$ROOT/conf/root-service-mode")" = system-root && test "$(cat "$ROOT/conf/update-capability")" = update-v2 || fail
  for file in update-local.sh update-service.sh root-trust.sh lifecycle-lock.sh update-public.der; do cmp -s "$ROOT/$file" "$HELPER/$file" || fail; done
  for file in "$ROOT"/lib/*.jar; do cmp -s "$file" "$HELPER/lib/$(basename "$file")" || fail; done
  cmp -s "$ROOT/conf/java.path" "$HELPER/java.path" || fail
  mode=$(cat "$HELPER/scheduler")
  case "$mode" in
    systemd)
      trusted "$SYSTEMD"
      for unit in service timer; do
        file=$SYSTEMD/java-assessment-collector.$unit
        protected_file "$file"; cmp -s "$file" "$HELPER/collector.$unit" || fail
        test "$(systemctl show java-assessment-collector.$unit -p DropInPaths)" = DropInPaths= || fail
      done
      test ! -e "$INIT/java-assessment-collector" || fail;;
    sysv)
      case "$INIT" in /etc/init.d|/etc/rc.d/init.d) ;; *) fail;; esac
      trusted "$INIT"
      protected_file "$INIT/java-assessment-collector"; cmp -s "$INIT/java-assessment-collector" "$HELPER/collector.init" || fail
      test ! -e "$SYSTEMD/java-assessment-collector.service" && test ! -e "$SYSTEMD/java-assessment-collector.timer" || fail;;
    *) fail;;
  esac
  for pending in /opt/java-assessment-uninstall/request.json /opt/java-assessment-uninstall/claim.json "$ROOT/collector-spool/uninstall-request.json"; do test ! -e "$pending" && test ! -L "$pending" || fail; done
}
case "$1" in
  check) check;;
  pause)
    check
    if test "$mode" = systemd; then systemctl stop java-assessment-collector.timer
    else /bin/sh "$ROOT/root-service.sh" stop; fi
    waited=0
    while test -d "$ROOT/collector-spool/.poll-lock"; do
      test ! -L "$ROOT/collector-spool/.poll-lock" || fail
      pid=$(cat "$ROOT/collector-spool/.poll-lock/pid" 2>/dev/null || true)
      case "$pid" in ''|*[!0-9]*) fail;; esac
      if ! kill -0 "$pid" 2>/dev/null; then rm -f "$ROOT/collector-spool/.poll-lock/pid"; rmdir "$ROOT/collector-spool/.poll-lock"; break; fi
      test "$waited" -lt 1500 || fail; sleep 5; waited=$((waited + 5))
    done
    if test "$mode" = systemd; then
      systemctl stop java-assessment-collector.service
      test "$(systemctl show java-assessment-collector.service -p MainPID)" = MainPID=0 || fail
      systemctl is-active --quiet java-assessment-collector.timer && fail
      systemctl is-active --quiet java-assessment-collector.service && fail
    fi
    # Never signal or stop a JVM outside this collector's known scheduler.
    for proc in /proc/[0-9]*/cmdline; do
      test -r "$proc" || continue
      if tr '\000' ' ' < "$proc" | grep -F "$ROOT/" | grep -E 'assessment\.(AssessmentCollector|AgentTool)( |$)|root-service.sh loop' >/dev/null; then fail; fi
    done;;
  start)
    check
    if test "$mode" = systemd; then
      systemctl start java-assessment-collector.timer; systemctl is-active --quiet java-assessment-collector.timer || fail
    else /bin/sh "$ROOT/root-service.sh" start; /bin/sh "$ROOT/root-service.sh" status >/dev/null || fail; fi;;
  sync) sync;;
  *) exit 2;;
esac
