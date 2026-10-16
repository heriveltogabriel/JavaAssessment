#!/bin/sh
# Restricted executor: fixed paths, no remote command/path parameters.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS ENV BASH_ENV
umask 077
ROOT=/opt/java-assessment-collector
HELPER=/opt/java-assessment-uninstall
SYSTEMD=/etc/systemd/system
INIT=$(readlink -f /etc/init.d)
CRON=/etc/cron.d
test "$(uname -s)" = Linux && test "$(id -u)" = 0 || exit 2
case "$INIT" in /etc/init.d|/etc/rc.d/init.d) ;; *) exit 2;; esac
fail() { echo 'UNINSTALL_LOCAL_VERIFICATION_FAILED' >&2; exit 1; }
v3() { test -f "$HELPER/v3-inventory.json"; }
v3_inventory() {
  IFS= read -r java < "$HELPER/java.path"
  java=$(/bin/sh "$HELPER/root-trust.sh" java "$java")
  cp_path=$HELPER/collector.jar
  for library in "$HELPER"/lib/*.jar; do cp_path="$cp_path:$library"; done
  "$java" -Xms16m -Xmx96m -Djava.security.egd=file:/dev/./urandom -cp "$cp_path" assessment.AgentV3UninstallInventory "$1"
}
# Trust every parent and tree; no symbolic links, hardlinks, mounted nested trees
# or special files are accepted. GNU find on RHEL 5 supports these predicates.
trusted() {
  path=$1
  while test "$path" != /; do
    test -d "$path" && test ! -L "$path" && test "$(stat -c %u "$path")" = 0 || fail
    directory_mode=$(stat -c %a "$path");test "$((0$directory_mode & 022))" = 0 || fail
    path=$(dirname "$path")
  done
}
check_tree() {
  test ! -L "$1" || fail
  trusted "$1"
  bad=$(find "$1" -xdev \( -type l -o ! -uid 0 -o \( -type f -links +1 \) -o \( ! -type f ! -type d \) -o -perm -020 -o -perm -002 \) -print -quit)
  test -z "$bad" || fail
  # A nested mount would otherwise leave external files behind or remove them.
  awk -v r="$1/" 'index($2,r)==1 {found=1} END {exit found?1:0}' /proc/mounts || fail
}
check_scheduler() {
  trusted "$SYSTEMD";trusted "$INIT";trusted "$CRON"
  for file in "$CRON/java-assessment-collector" "$CRON/java-assessment"; do test ! -e "$file" && test ! -L "$file" || fail;done
  # Inspect every local account, not just a guessed root/opc pair. Refuse cron
  # installations; never rewrite application crontabs.
  getent passwd > "$HELPER/accounts.tmp"
  test "$(wc -l < "$HELPER/accounts.tmp")" -le 4096 || fail
  while IFS=: read -r account rest; do
    case "$account" in ''|*[!a-zA-Z0-9_.-]*) fail;; esac
    if LC_ALL=C crontab -u "$account" -l > "$HELPER/cron.tmp" 2> "$HELPER/cron.err"; then
      if grep -E 'java-assessment|assessment-collector' "$HELPER/cron.tmp" >/dev/null; then fail;fi
    else grep -qi 'no crontab' "$HELPER/cron.err" || fail;fi
  done < "$HELPER/accounts.tmp"
  rm -f "$HELPER/accounts.tmp" "$HELPER/cron.tmp" "$HELPER/cron.err"
  mode=$(cat "$HELPER/scheduler")
  case "$mode" in
    systemd)
      for unit in service timer; do
        target=$SYSTEMD/java-assessment-collector.$unit
        if test -e "$target" || test -L "$target"; then
          test -f "$target" && test ! -L "$target" && test "$(stat -c %h "$target")" = 1 || fail
          cmp -s "$target" "$HELPER/collector.$unit" || fail
        else test -f "$HELPER/removal-started" || fail;fi
        test "$(systemctl show java-assessment-collector.$unit -p DropInPaths)" = DropInPaths= || fail
      done
      test ! -e "$INIT/java-assessment-collector" && test ! -L "$INIT/java-assessment-collector" || fail;;
    sysv)
      for unit in service timer; do test ! -e "$SYSTEMD/java-assessment-collector.$unit" && test ! -L "$SYSTEMD/java-assessment-collector.$unit" || fail;done
      if test -e "$INIT/java-assessment-collector" || test -L "$INIT/java-assessment-collector"; then
        test ! -L "$INIT/java-assessment-collector" && test "$(stat -c %h "$INIT/java-assessment-collector")" = 1 && cmp -s "$INIT/java-assessment-collector" "$HELPER/collector.init" || fail
      else test -f "$HELPER/removal-started" || fail;fi;;
    *) fail;;
  esac
}
check_files() {
  if v3; then
    test ! -d "$ROOT" || check_tree "$ROOT"
    v3_inventory check; return
  fi
  test ! -L "$ROOT" || fail
  test -d "$ROOT" || { test -f "$HELPER/removal-started" || fail;return; }
  check_tree "$ROOT"
  test ! -f "$ROOT/conf/server.properties" && test ! -d "$ROOT/web" || fail
  if test -f "$ROOT/conf/collector.properties"; then
    original=$(sed -n 's/^hostId=//p' "$ROOT/conf/collector.properties" | tr -d '\r')
    expected=$(sed -n 's/^hostId=//p' "$HELPER/identity.properties" | tr -d '\r')
    test -n "$expected" && test "$original" = "$expected" || fail
  else test -f "$HELPER/removal-started" || fail;fi
  find "$ROOT" -xdev -mindepth 1 -print > "$HELPER/files.tmp"
  while IFS= read -r file; do
    relative=${file#"$ROOT/"}
    case "$relative" in ''|*[!a-zA-Z0-9_./-]*|*..*) fail;; esac
    if test -d "$file"; then
      case "$relative" in conf|lib|trust|logs|run|collector-spool|collector-spool/.poll-lock|backups) ;; *) fail;; esac
    elif grep -Fx "$relative" "$HELPER/owned-files" >/dev/null; then :
    else
      case "$relative" in
        logs/collector.log|logs/collector.log.[123]|logs/service.log|logs/service.log.[123]|logs/install.log|logs/install-events-*.json|logs/install-events-*.json.sent|collector-spool/pending-*.json|collector-spool/latest-snapshot.json|collector-spool/last-ack.json|collector-spool/.collector.lock|collector-spool/uninstall-request.json|run/service.pid|run/service.stop|collector-spool/.poll-lock/pid) ;;
        *) fail;;
      esac
    fi
  done < "$HELPER/files.tmp"
}
case "${1:-}" in
  remove)
    test "$#" = 1 || fail
    . "$HELPER/lifecycle-lock.sh"
    check_tree "$HELPER";check_scheduler;check_files
    # The updater is independent of the collector. Stop and remove it only
    # with no transaction or pending action, under the shared lifecycle lock.
    UPDATE=/opt/java-assessment-update
    test ! -e "$UPDATE/journal.json" && test ! -L "$UPDATE/journal.json" && test ! -e "$ROOT/collector-spool/update-request.json" || fail
    if test -d "$UPDATE"; then
      check_tree "$UPDATE"
      if v3; then
        test ! -e "$UPDATE/state/transaction.json" && test ! -e "$ROOT/collector-spool/update-v3-request.json" || fail
        if test -f "$SYSTEMD/java-assessment-recovery.service"; then
          test ! -L "$SYSTEMD/java-assessment-recovery.service" && cmp -s "$SYSTEMD/java-assessment-recovery.service" "$UPDATE/bootstrap/recovery.service" || fail
          systemctl disable --now java-assessment-recovery.service
          systemctl is-active --quiet java-assessment-recovery.service && fail
          startup=$SYSTEMD/java-assessment-recovery.service.d
          if test -e "$startup" || test -L "$startup"; then
            trusted "$startup"
            test "$(find "$startup" -mindepth 1 -maxdepth 1 | wc -l)" -eq 1 || fail
            extension=$startup/10-java-assessment-start.conf
            test -f "$extension" && test ! -L "$extension" && test "$(stat -c %u:%h "$extension")" = 0:1 || fail
            test "$(sha256sum "$extension" | cut -d ' ' -f 1)" = 838b1dd41f8f2f6acdab3fcb64a0e951f5603659f8d61633a74dae312d219917 || fail
            rm -- "$extension"; rmdir -- "$startup"
          fi
          rm -f "$SYSTEMD/java-assessment-recovery.service"; systemctl daemon-reload
        elif test -f "$INIT/java-assessment-recovery"; then
          test ! -L "$INIT/java-assessment-recovery" && cmp -s "$INIT/java-assessment-recovery" "$UPDATE/bootstrap/recovery.init" || fail
          /bin/sh "$UPDATE/bootstrap/update-bootstrap.sh" stop
          chkconfig java-assessment-recovery off; chkconfig --del java-assessment-recovery; rm -f "$INIT/java-assessment-recovery"
        else fail; fi
      fi
      if test -f "$SYSTEMD/java-assessment-update.service"; then
        test ! -L "$SYSTEMD/java-assessment-update.service" && cmp -s "$SYSTEMD/java-assessment-update.service" "$UPDATE/helper.service" || fail
        systemctl disable --now java-assessment-update.service
        systemctl is-active --quiet java-assessment-update.service && fail
        rm -f "$SYSTEMD/java-assessment-update.service"; systemctl daemon-reload
      elif test -f "$INIT/java-assessment-update"; then
        test ! -L "$INIT/java-assessment-update" && cmp -s "$INIT/java-assessment-update" "$UPDATE/helper.init" || fail
        /bin/sh "$UPDATE/update-service.sh" stop
        chkconfig java-assessment-update off; chkconfig --del java-assessment-update; rm -f "$INIT/java-assessment-update"
      elif ! v3; then fail; fi
      if ! v3; then rm -rf "$UPDATE"; fi
    fi
    : > "$HELPER/removal-started"
    # Persist claim/identity and the directory renames before removing anything.
    sync
    if test "$mode" = systemd; then
      if test -f "$SYSTEMD/java-assessment-collector.timer"; then systemctl disable --now java-assessment-collector.timer;fi
    else
      test ! -f "$INIT/java-assessment-collector" || chkconfig java-assessment-collector off
    fi
    elapsed=0
    while test -d "$ROOT/collector-spool/.poll-lock"; do
      test ! -L "$ROOT/collector-spool/.poll-lock" || fail
      pid=$(cat "$ROOT/collector-spool/.poll-lock/pid" 2>/dev/null || true)
      case "$pid" in ''|*[!0-9]*) fail;; esac
      if ! kill -0 "$pid" 2>/dev/null; then rm -f "$ROOT/collector-spool/.poll-lock/pid";rmdir "$ROOT/collector-spool/.poll-lock";break;fi
      test "$elapsed" -lt 1500 || fail;sleep 5;elapsed=$((elapsed+5))
    done
    if test "$mode" = systemd; then
      if test -f "$SYSTEMD/java-assessment-collector.service"; then systemctl stop java-assessment-collector.service;fi
      if systemctl is-active --quiet java-assessment-collector.timer || systemctl is-active --quiet java-assessment-collector.service; then fail;fi
    elif test -f "$ROOT/root-service.sh"; then /bin/sh "$ROOT/root-service.sh" stop;fi
    # A collector outside its scheduler means ownership cannot be verified.
    for proc in /proc/[0-9]*/cmdline; do
      test -r "$proc" || continue
      if tr '\000' ' ' < "$proc" | grep -F "$ROOT/" | grep -E 'assessment\.(AssessmentCollector|AgentTool)( |$)|root-service.sh loop' >/dev/null; then fail;fi
    done
    check_files
    if v3; then v3_inventory remove; fi
    if test -d "$ROOT"; then
      # Unlink only inspected regular files. rmdir refuses any unexpected/new
      # member; rm -rf is deliberately never used on the collector tree.
      while IFS= read -r file; do test ! -f "$file" || rm -f "$file";done < "$HELPER/files.tmp"
      find "$ROOT" -xdev -depth -type d -exec rmdir '{}' \;
      test ! -e "$ROOT" || fail
    fi
    if test "$mode" = systemd; then
      rm -f "$SYSTEMD/java-assessment-collector.service" "$SYSTEMD/java-assessment-collector.timer"
      systemctl daemon-reload
      for unit in service timer; do test "$(systemctl show java-assessment-collector.$unit -p LoadState)" = LoadState=not-found || fail;done
    else
      test ! -f "$INIT/java-assessment-collector" || chkconfig --del java-assessment-collector
      rm -f "$INIT/java-assessment-collector"
      for file in /etc/rc[0-6].d/*java-assessment-collector /etc/rc.d/rc[0-6].d/*java-assessment-collector; do test ! -e "$file" && test ! -L "$file" || fail;done
    fi
    test ! -e "$ROOT" && test ! -L "$ROOT" || fail
    sync
    echo UNINSTALL_LOCAL_VERIFIED;;
  cleanup)
    test "$#" = 1 || fail
    . "$HELPER/lifecycle-lock.sh"
    check_tree "$HELPER";trusted "$SYSTEMD";trusted "$INIT"
    if v3; then v3_inventory check-helper; fi
    # Helper belongs exclusively to this protocol, independently of dashboard.
    if test -f "$SYSTEMD/java-assessment-uninstall.service"; then
      test ! -L "$SYSTEMD/java-assessment-uninstall.service" && cmp -s "$SYSTEMD/java-assessment-uninstall.service" "$HELPER/helper.service" || fail
      systemctl disable java-assessment-uninstall.service
      rm -f "$SYSTEMD/java-assessment-uninstall.service";systemctl daemon-reload
    elif test -f "$INIT/java-assessment-uninstall"; then
      test ! -L "$INIT/java-assessment-uninstall" && cmp -s "$INIT/java-assessment-uninstall" "$HELPER/helper.init" || fail
      chkconfig java-assessment-uninstall off;chkconfig --del java-assessment-uninstall;rm -f "$INIT/java-assessment-uninstall"
    fi
    # Preserve one bounded, credential-free diagnosis. No inventory/backups.
    state=CONFIRMATION_UNCONFIRMED
    test ! -f "$HELPER/ack.json" || state=RESULT_CONFIRMED_BY_DASHBOARD
    diagnostic=$(mktemp /var/log/.java-assessment-uninstall.XXXXXX)
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$state" > "$diagnostic"
    chmod 600 "$diagnostic";mv -f "$diagnostic" /var/log/java-assessment-uninstall-last.txt
    rm -rf "$HELPER";;
  *) exit 2;;
esac
