#!/bin/sh
# Local administrative orchestration only. No remote paths/commands from dashboard.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
LC_ALL=C; export LC_ALL
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS IBM_JAVA_OPTIONS OPENJ9_JAVA_OPTIONS ENV BASH_ENV CLASSPATH
umask 077
ROOT=/opt/java-assessment-collector
UPDATE=/opt/java-assessment-update
HELPER=/opt/java-assessment-uninstall
SYSTEMD=/etc/systemd/system
INIT=$(readlink -f /etc/init.d)
WORK=''
fail() { printf 'JA_ERROR=%s\n' "$1" >&2; exit 1; }
present() { test -e "$1" || test -L "$1"; }
trusted() (
  p=$1
  while :; do
    test ! -L "$p" && test "$(stat -c %u "$p")" = 0 || exit 1
    mode=$(stat -c %a "$p"); test "$((0$mode & 022))" = 0 || exit 1
    test "$p" != / || break
    p=$(dirname "$p")
  done
)
private_file() { test -s "$1" && test -f "$1" && trusted "$1" && test "$(stat -c %h "$1")" = 1; }
check_tree() {
  test -d "$1" && trusted "$1" || fail UNTRUSTED_TREE
  bad=$(find "$1" -xdev \( -type l -o ! -uid 0 -o \( -type f -links +1 \) -o \( ! -type f ! -type d \) -o -perm -020 -o -perm -002 \) -print -quit)
  test -z "$bad" || fail UNSAFE_TREE_MEMBER
  awk -v r="$1" '$2==r || index($2,r"/")==1 {bad=1} END {exit bad?1:0}' /proc/mounts || fail MOUNTED_TREE
}
no_pending() {
  for p in "$ROOT/.root-install-lock" "$ROOT/collector-spool/.install-lock" \
    "$UPDATE/state/transaction.json" "$UPDATE/journal.json" "$UPDATE/request.json" \
    "$ROOT/collector-spool/update-request.json" "$ROOT/collector-spool/update-v3-request.json" \
    "$ROOT/collector-spool/uninstall-request.json" "$HELPER/request.json" "$HELPER/claim.json" \
    "$HELPER/removal-started"; do
    present "$p" && fail PENDING_OPERATION
  done
  return 0
}
identity() {
  for identity_file in "$ROOT/conf/installation-id" "$ROOT/conf/collector.properties"; do
    private_file "$identity_file" || return 1
    case "$(stat -c %a "$identity_file")" in 600|400) ;; *) return 1;; esac
  done
  private_file "$ROOT/conf/installation-id" && private_file "$ROOT/conf/collector.properties" &&
  grep -Eq '^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$' "$ROOT/conf/installation-id" &&
  awk -F= '/^serverUrl=./ {s++} /^hostId=./ {h++} /^agentToken=./ {t++} END {exit !(s==1 && h==1 && t==1)}' "$ROOT/conf/collector.properties"
}
systemd() { command -v systemctl >/dev/null 2>&1 && test -d /run/systemd/system; }
units() {
  printf '%s\n' java-assessment-collector.timer java-assessment-collector.service java-assessment-uninstall.service
  if present "$ROOT/current.json"; then printf '%s\n' java-assessment-recovery.service
  else printf '%s\n' java-assessment-update.service; fi
}
# A stopped oneshot collector is normal; inspect the timer and executors instead.
healthy() {
  identity || return 1
  for p in "$ROOT/root-service.sh" "$ROOT/poll.sh" "$ROOT/conf/java.path" "$HELPER/uninstall-service.sh"; do private_file "$p" || return 1; done
  if present "$ROOT/current.json"; then
    private_file "$ROOT/current.json" && private_file "$UPDATE/bootstrap/anchor.jar" || return 1
  else private_file "$ROOT/collector.jar" && private_file "$UPDATE/update-service.sh" || return 1; fi
  if systemd; then
    for unit in $(units); do
      test "$unit" != java-assessment-collector.service || continue
      systemctl is-active --quiet "$unit" && systemctl is-enabled --quiet "$unit" || return 1
    done
    test "$(systemctl show java-assessment-collector.service -p ActiveState)" != ActiveState=failed || return 1
  else
    /bin/sh "$ROOT/root-service.sh" status >/dev/null 2>&1 || return 1
    # Helpers have no portable status interface. Verify the saved PID belongs
    # to the expected root supervisor, not merely to some live process.
    for dir in "$HELPER" "$UPDATE"; do
      if test "$dir" = "$UPDATE" && present "$ROOT/current.json"; then
        pidfile=$UPDATE/state/recovery.pid; script=$UPDATE/bootstrap/update-bootstrap.sh
      elif test "$dir" = "$UPDATE"; then pidfile=$dir/supervisor.pid; script=$dir/update-service.sh
      else pidfile=$dir/supervisor.pid; script=$dir/uninstall-service.sh; fi
      private_file "$pidfile" || return 1
      pid=$(cat "$pidfile"); case "$pid" in ''|*[!0-9]*) return 1;; esac
      test -r "/proc/$pid/status" && test "$(awk '/^Uid:/ {print $2}' "/proc/$pid/status")" = 0 || return 1
      tr '\000' ' ' < "/proc/$pid/cmdline" | grep -F "$script loop" >/dev/null || return 1
    done
  fi
}
state() {
  no_pending
  any=false
  for dir in "$ROOT" "$HELPER" "$UPDATE"; do
    if present "$dir"; then check_tree "$dir"; any=true; fi
  done
  if { present "$ROOT/releases" || present "$UPDATE/bootstrap"; } && ! present "$ROOT/current.json"; then
    fail V3_POINTER_MISSING
  fi
  if ! $any; then echo ABSENT
  elif healthy; then echo HEALTHY
  elif identity; then echo REPAIR
  else echo RESIDUE; fi
}
wait_healthy() {
  attempts=0
  while ! healthy; do
    test "$attempts" -lt 6 || return 1
    sleep 10
    attempts=$((attempts + 1))
  done
}
# Compare services with the installer's own retained definitions, including
# the one official recovery drop-in. Custom overrides require manual review.
verify_units() {
  if systemd; then
    for unit in $(units) java-assessment-update.service; do
      case "$unit" in
        java-assessment-collector.service) copy=$HELPER/collector.service;;
        java-assessment-collector.timer) copy=$HELPER/collector.timer;;
        java-assessment-uninstall.service) copy=$HELPER/helper.service;;
        java-assessment-recovery.service) copy=$UPDATE/bootstrap/recovery.service;;
        java-assessment-update.service) copy=$UPDATE/helper.service;;
      esac
      load=$(systemctl show "$unit" -p LoadState) || fail SERVICE_INSPECTION
      if test "$load" = LoadState=not-found; then
        present "$SYSTEMD/$unit" && fail UNKNOWN_SERVICE
        continue
      fi
      test "$load" = LoadState=loaded || fail UNKNOWN_SERVICE
      private_file "$SYSTEMD/$unit" && private_file "$copy" && cmp -s "$SYSTEMD/$unit" "$copy" || fail SERVICE_DEFINITION_CHANGED
      test "$(systemctl show "$unit" -p FragmentPath)" = "FragmentPath=$SYSTEMD/$unit" || fail SERVICE_LOCATION
      drop=$(systemctl show "$unit" -p DropInPaths)
      if test "$drop" != DropInPaths=; then
        ext=$SYSTEMD/java-assessment-recovery.service.d/10-java-assessment-start.conf
        test "$unit" = java-assessment-recovery.service && test "$drop" = "DropInPaths=$ext" && private_file "$ext" || fail SERVICE_OVERRIDE
        test "$(sha256sum "$ext" | cut -d ' ' -f 1)" = 838b1dd41f8f2f6acdab3fcb64a0e951f5603659f8d61633a74dae312d219917 || fail SERVICE_OVERRIDE
      fi
      test "$(systemctl show "$unit" -p NeedDaemonReload)" = NeedDaemonReload=no || fail SERVICE_RELOAD_NEEDED
    done
  else
    for pair in "collector:$HELPER/collector.init" "uninstall:$HELPER/helper.init" "update:$UPDATE/helper.init" "recovery:$UPDATE/bootstrap/recovery.init"; do
      name=${pair%%:*}; copy=${pair#*:}; unit=$INIT/java-assessment-$name
      present "$unit" || continue
      private_file "$unit" && private_file "$copy" && cmp -s "$unit" "$copy" || fail SERVICE_DEFINITION_CHANGED
    done
  fi
}
backup() {
  # All trees were checked before copying. Keep credentials private and report
  # only the path; never stream configuration or installer output to Ansible.
  mkdir "$WORK/backup" || fail BACKUP_FAILED
  for dir in "$ROOT" "$HELPER" "$UPDATE"; do
    if present "$dir"; then check_tree "$dir"; cp -pR "$dir" "$WORK/backup/" || fail BACKUP_FAILED; fi
  done
  printf '%s\n' 'Backup privado: pode conter tokens. Nao enviar ao Git. Restauracao exige diagnostico; nao iniciar duas identidades.' > "$WORK/backup/README.txt"
  echo "JA_BACKUP=$WORK/backup"
}
no_processes() {
  for proc in /proc/[0-9]*/cmdline; do
    if test ! -r "$proc"; then present "$proc" && fail PROCESS_INSPECTION; continue; fi
    for dir in "$ROOT" "$UPDATE" "$HELPER"; do
      if tr '\000' ' ' < "$proc" | grep -F "$dir/" >/dev/null; then fail AGENT_PROCESS_PRESENT; fi
    done
    proc_dir=${proc%/cmdline}
    for link in "$proc_dir/cwd" "$proc_dir/exe" "$proc_dir"/fd/*; do
      target=$(readlink "$link" 2>/dev/null || true)
      case "$target" in "$ROOT"|"$ROOT"/*|"$UPDATE"|"$UPDATE"/*|"$HELPER"|"$HELPER"/*) fail AGENT_PROCESS_PRESENT;; esac
    done
  done
}
residue_only() {
  # Only a pre-registration collector tree, without helper/scheduler/process.
  # A lone identity/token is ambiguous: never silently issue a second identity.
  for p in "$HELPER" "$UPDATE" "$ROOT/current.json" "$ROOT/conf/installation-id" "$ROOT/conf/collector.properties"; do
    present "$p" && fail RESIDUE_REQUIRES_DIAGNOSIS
  done
  for name in collector uninstall update recovery; do
    for p in "$SYSTEMD/java-assessment-$name.service" "$SYSTEMD/java-assessment-$name.timer" "$INIT/java-assessment-$name"; do
      present "$p" && fail RESIDUE_HAS_SERVICE
    done
    if systemd; then
      for suffix in service timer; do
        test "$(systemctl show "java-assessment-$name.$suffix" -p LoadState)" = LoadState=not-found || fail RESIDUE_HAS_SERVICE
      done
    fi
  done
  no_processes
  # Recognize only shipped files and installer state directories.
  if present "$ROOT"; then
    for p in "$ROOT"/* "$ROOT"/.[!.]* "$ROOT"/..?*; do
      present "$p" || continue
      name=$(basename "$p")
      case "$name" in conf|logs|run|collector-spool|backups|trust|lib|full-update) ;;
        *) test -f "$p" && test -f "$PKG/$name" || fail UNKNOWN_RESIDUE;;
      esac
    done
    present "$ROOT/web" && fail UNKNOWN_RESIDUE
  fi
  # Refuse any cron-based installation; do not edit application crontabs.
  getent passwd > "$WORK/accounts" || fail ACCOUNT_INSPECTION
  test "$(wc -l < "$WORK/accounts")" -le 4096 || fail ACCOUNT_INSPECTION
  while IFS=: read -r account rest; do
    case "$account" in ''|*[!a-zA-Z0-9_.-]*) fail ACCOUNT_INSPECTION;; esac
    if crontab -u "$account" -l > "$WORK/cron" 2> "$WORK/cron-error"; then
      grep -E 'java-assessment|assessment-collector' "$WORK/cron" >/dev/null && fail RESIDUE_HAS_CRON
    else grep -qi 'no crontab' "$WORK/cron-error" || fail CRON_INSPECTION; fi
  done < "$WORK/accounts"
  for p in /etc/crontab /etc/cron.d/* /etc/cron.hourly/* /etc/cron.daily/* /etc/cron.weekly/* /etc/cron.monthly/*; do
    test -f "$p" || continue
    grep -E 'java-assessment|assessment-collector' "$p" >/dev/null && fail RESIDUE_HAS_CRON
  done
  return 0
}
stop_services() {
  verify_units
  if systemd; then
    for unit in $(units) java-assessment-update.service; do
      test "$(systemctl show "$unit" -p LoadState)" != LoadState=not-found || continue
      systemctl stop "$unit" || fail SERVICE_STOP_FAILED
      for prop in MainPID ControlPID; do test "$(systemctl show "$unit" -p "$prop")" = "$prop=0" || fail SERVICE_NOT_STOPPED; done
      case "$(systemctl show "$unit" -p ActiveState)" in ActiveState=inactive|ActiveState=failed) ;; *) fail SERVICE_NOT_STOPPED;; esac
    done
  else
    for name in collector uninstall update recovery; do
      test ! -f "$INIT/java-assessment-$name" || /bin/sh "$INIT/java-assessment-$name" stop || fail SERVICE_STOP_FAILED
    done
  fi
  no_pending
  no_processes
}
start_services() {
  verify_units
  if systemd; then
    for unit in $(units); do
      test "$unit" != java-assessment-collector.service || continue
      systemctl enable "$unit" || fail SERVICE_ENABLE_FAILED
      systemctl start "$unit" || fail SERVICE_START_FAILED
    done
    systemctl reset-failed java-assessment-collector.service || fail SERVICE_RESET_FAILED
  else
    for name in uninstall recovery collector; do
      private_file "$INIT/java-assessment-$name" || fail MISSING_SERVICE
      chkconfig java-assessment-$name on || fail SERVICE_ENABLE_FAILED
      /bin/sh "$INIT/java-assessment-$name" start 8>&- 9>&- || fail SERVICE_START_FAILED
    done
  fi
}
# Ansible-only selection policy. Shipped collector code/configuration is unchanged.
# Probe real VM properties after the package's root-trust checks, never directory names.
policy_try_java() {
  policy_candidate=$1
  test -f "$policy_candidate" && test -x "$policy_candidate" || return 1
  case "$policy_candidate" in "$ROOT"/*|"$UPDATE"/*|"$HELPER"/*|"$WORK"/*) return 1;; esac
  case "$policy_seen" in *"|$policy_candidate|"*) return 1;; esac
  policy_seen="$policy_seen$policy_candidate|"
  test "$policy_count" -lt 24 || return 1
  policy_count=$((policy_count + 1))
  policy_resolved=$(/bin/sh "$PKG/root-trust.sh" java "$policy_candidate") || return 1
  test -n "$policy_resolved" || return 1
  case "$policy_resolved" in "$ROOT"/*|"$UPDATE"/*|"$HELPER"/*|"$WORK"/*) return 1;; esac
  case "$policy_probed" in *"|$policy_resolved|"*) return 1;; esac
  policy_probed="$policy_probed$policy_resolved|"
  # timeout and a file-size ceiling bound each invocation and its private output.
  (ulimit -f 128; timeout -k 1 5 "$policy_resolved" -Xmx64m \
    -Djava.security.egd=file:/dev/./urandom -XshowSettings:properties -version) \
    > "$WORK/java-policy-probe.log" 2>&1 || return 1
  policy_meta=$(awk '
    /^[ \t]*java.specification.version[ \t]*=/ {v++; sub(/^[^=]*=[ \t]*/, ""); sub(/[ \t\r]+$/, ""); major=$0}
    /^[ \t]*java.vendor[ \t]*=/ {n++; sub(/^[^=]*=[ \t]*/, ""); sub(/[ \t\r]+$/, ""); vendor=$0}
    /^[ \t]*java.vm.vendor[ \t]*=/ {m++; sub(/^[^=]*=[ \t]*/, ""); sub(/[ \t\r]+$/, ""); vmvendor=$0}
    END {
      sub(/^1\./,"",major)
      if(v!=1 || n!=1 || m!=1 || major !~ /^[0-9]+$/ || length(major)>3 ||
         vendor=="" || vmvendor=="" || length(vendor)>200 || length(vmvendor)>200 ||
         vendor ~ /[[:cntrl:]]/ || vmvendor ~ /[[:cntrl:]]/) exit 1
      print major; print vendor; print vmvendor
    }' "$WORK/java-policy-probe.log") || return 1
  policy_major=$(printf '%s\n' "$policy_meta" | sed -n '1p')
  policy_vendor=$(printf '%s\n' "$policy_meta" | sed -n '2p')
  policy_vm_vendor=$(printf '%s\n' "$policy_meta" | sed -n '3p')
  if test "$policy_major" -lt "$MIN_JAVA"; then
    printf 'Java policy: skipped Java %s at %s (minimum %s).\n' "$policy_major" "$policy_resolved" "$MIN_JAVA"
    return 1
  fi
  if test "$EXCLUDE_IBM" = true; then
    policy_vendor_lower=$(printf '%s %s' "$policy_vendor" "$policy_vm_vendor" | tr '[:upper:]' '[:lower:]')
    case "$policy_vendor_lower" in *ibm*|*'international business machines'*)
      printf 'Java policy: skipped IBM vendor at %s.\n' "$policy_resolved"; return 1;; esac
  fi
  (ulimit -f 128; timeout -k 1 5 "$policy_resolved" -Xmx64m \
    -Djava.security.egd=file:/dev/./urandom -cp "$PKG/collector.jar:$PKG/lib/*" assessment.JavaProbe) \
    > "$WORK/java-policy-probe.log" 2>&1 || return 1
  JAVA=$policy_resolved
  printf 'Java policy: selected Java %s; vendor=%s; path=%s.\n' "$policy_major" "$policy_vendor" "$JAVA"
}
policy_select_java() {
  policy_seen='|'; policy_probed='|'; policy_count=0
  if test -n "$JAVA"; then policy_try_java "$JAVA"; return $?; fi
  for policy_known in /usr/bin/java /usr/lib/jvm/*/bin/java /usr/lib/jvm/*/jre/bin/java \
      /usr/java/bin/java /usr/java/*/bin/java /usr/java/*/jre/bin/java \
      /opt/java/*/bin/java /opt/java-lab/*/bin/java; do
    if policy_try_java "$policy_known"; then return 0; fi
    test "$policy_count" -lt 24 || return 1
  done
  # Process executable links reveal nonstandard application JVM locations.
  timeout -k 1 5 readlink -f /proc/[0-9]*/exe > "$WORK/java-process-candidates" 2>/dev/null || true
  while IFS= read -r policy_process; do
    case "$policy_process" in */bin/java) ;; *) continue;; esac
    if policy_try_java "$policy_process"; then return 0; fi
    test "$policy_count" -lt 24 || return 1
  done < "$WORK/java-process-candidates"
  set --
  for policy_root in /usr/java /usr/lib/jvm /opt /usr/local /u01 /u02 /app /apps /oracle /data; do
    test ! -d "$policy_root" || test -L "$policy_root" || set -- "$@" "$policy_root"
  done
  test "$#" -gt 0 || return 1
  printf 'Java policy: bounded directory discovery (depth 8, 10000 entries, 10 seconds).\n'
  timeout -k 1 10 find "$@" -xdev -maxdepth 8 -printf '%y %p\n' \
    \( -type d \( ! -uid 0 -o -perm -020 -o -perm -002 \) -prune \) 2>/dev/null |
    awk 'NR>10000 {exit} ($1=="f" || $1=="l") && /\/bin\/java$/ {print substr($0,3)}' \
    > "$WORK/java-directory-candidates"
  while IFS= read -r policy_found; do
    if policy_try_java "$policy_found"; then return 0; fi
    test "$policy_count" -lt 24 || return 1
  done < "$WORK/java-directory-candidates"
  return 1
}

test "$(id -u)" = 0 && test "$(uname -s)" = Linux || fail ROOT_LINUX_REQUIRED
case "$INIT" in /etc/init.d|/etc/rc.d/init.d) ;; *) fail INIT_DIRECTORY;; esac
case "${1:-}" in
  probe)
    test "$#" = 1 || fail ARGUMENTS
    result=$(state) || exit 1
    echo "JA_STATE=$result";;
  apply)
    { test "$#" = 4 || test "$#" = 6; } || fail ARGUMENTS
    MODE=$2; SOURCE=$3; JAVA=$4; MIN_JAVA=${5:-6}; EXCLUDE_IBM=${6:-false}
    case "$MIN_JAVA" in ''|*[!0-9]*) fail JAVA_POLICY_ARGUMENTS;; esac
    test "${#MIN_JAVA}" -le 2 && test "$MIN_JAVA" -ge 6 || fail JAVA_POLICY_ARGUMENTS
    case "$EXCLUDE_IBM" in true|false) ;; *) fail JAVA_POLICY_ARGUMENTS;; esac
    POLICY=false
    if test "$MIN_JAVA" -gt 6 || test "$EXCLUDE_IBM" = true; then POLICY=true; fi
    case "$MODE" in auto|reinstall) ;; *) fail MODE;; esac
    case "$SOURCE" in /tmp/java-assessment-ansible.*/package) ;; *) fail PACKAGE_PATH;; esac
    case "$SOURCE" in *..*|*[!a-zA-Z0-9_./-]*) fail PACKAGE_PATH;; esac
    # An independent non-inherited orchestration lock covers gaps between the
    # official operations, which acquire their own shared lifecycle lock.
    trusted /opt || fail OPT_TRUST
    lock=/opt/java-assessment-ansible.lock
    if present "$lock"; then
      test -f "$lock" && trusted "$lock" && test "$(stat -c %h "$lock")" = 1 && test "$(stat -c %a "$lock")" = 600 || fail AUTOMATION_LOCK;
    fi
    exec 8>> "$lock"; flock -n 8 || fail AUTOMATION_BUSY
    BEFORE=$(state)
    if test "$MODE:$BEFORE" = auto:HEALTHY; then echo JA_RESULT=PRESERVED; exit 0; fi
    WORK=$(mktemp -d /opt/java-assessment-ansible.XXXXXX)
    echo "JA_LOG=$WORK/operation.log"
    # Copy user staging without following links, then verify the private copy.
    test -d "$SOURCE" && test ! -L "$SOURCE" && test ! -L "$(dirname "$SOURCE")" || fail PACKAGE_PATH
    test -z "$(find "$SOURCE" \( -type l -o \( ! -type f ! -type d \) -o \( -type f -links +1 \) \) -print -quit)" || fail PACKAGE_MEMBER
    cp -R "$SOURCE" "$WORK/package"
    PKG=$WORK/package
    find "$PKG" -type d -exec chmod 700 '{}' +
    find "$PKG" -type f -exec chmod 600 '{}' +
    check_tree "$PKG"
    (
      cd "$PKG" || exit 1
      test -s SHA256SUMS || exit 1
      awk 'length($1)!=64 || $1 ~ /[^a-fA-F0-9]/ || NF!=2 || $2 ~ /^\// || $2 ~ /\.\./ || $2 ~ /[^a-zA-Z0-9_.\/-]/ {bad=1} END {exit bad?1:0}' SHA256SUMS || exit 1
      sha256sum -c SHA256SUMS || exit 1
      for tool in flock crontab getent stat mktemp cp mv awk sed tar; do command -v "$tool" >/dev/null || exit 1; done
      if ! systemd; then command -v chkconfig >/dev/null || exit 1; fi
    ) > "$WORK/operation.log" 2>&1 || fail PREFLIGHT_PACKAGE
    if $POLICY; then
      command -v timeout >/dev/null 2>&1 || fail JAVA_POLICY_TIMEOUT_REQUIRED
      # V3 service recovery keeps its persisted JVM; do not validate one VM
      # and then reactivate a service configured to run a different one.
      if test "$MODE" = auto && test "$BEFORE" = REPAIR && present "$ROOT/current.json"; then
        private_file "$ROOT/conf/java.path" || fail EXISTING_JAVA_REQUIRES_REVIEW
        saved_policy_java=$(cat "$ROOT/conf/java.path")
        test -z "$JAVA" || test "$JAVA" = "$saved_policy_java" || fail EXISTING_JAVA_REQUIRES_REVIEW
        JAVA=$saved_policy_java
      fi
      policy_select_java >> "$WORK/operation.log" 2>&1 || fail JAVA_POLICY_NO_MATCH
    else
      if test -n "$JAVA"; then
        JAVA=$(/bin/sh "$PKG/root-trust.sh" java "$JAVA" 2>> "$WORK/operation.log") || fail PREFLIGHT_JAVA
        test -n "$JAVA" || fail PREFLIGHT_JAVA
      fi
      requested_java=$JAVA
      JAVA=$(/bin/sh "$PKG/root-java-discovery.sh" "$PKG" "$JAVA" 2>> "$WORK/operation.log") || fail PREFLIGHT_JAVA
      test -z "$requested_java" || test "$JAVA" = "$requested_java" || fail EXPLICIT_JAVA_REJECTED
    fi
    case "$JAVA" in "$ROOT"/*|"$UPDATE"/*|"$HELPER"/*|"$WORK"/*) fail JAVA_INSIDE_AGENT;; esac
    if test "$MODE" = auto && test "$BEFORE" = REPAIR; then
      /bin/sh "$PKG/root-migrate-input.sh" config "$ROOT" > "$WORK/identity-before" || fail IDENTITY_INVALID
    fi
    if test "$MODE:$BEFORE" = auto:REPAIR && present "$ROOT/current.json"; then
      private_file "$UPDATE/bootstrap/anchor.jar" || fail V3_CODE_INVALID
      code=$("$JAVA" -Xms16m -Xmx96m -Djava.security.egd=file:/dev/./urandom -cp "$UPDATE/bootstrap/anchor.jar" assessment.AgentMigrationBootstrap code-path 2>> "$WORK/operation.log") || fail V3_CODE_INVALID
      case "$code" in "$ROOT"/releases/*) ;; *) fail V3_CODE_INVALID;; esac
      private_file "$code/collector.jar" || fail V3_CODE_INVALID
    fi
    private_file "$PKG/lifecycle-lock.sh" || fail PACKAGE_INCOMPLETE
    # Validate and back up under the same lock used by update/uninstall.
    (
      . "$PKG/lifecycle-lock.sh"
      no_pending
      test "$(state)" = "$BEFORE" || fail STATE_CHANGED_RETRY
      case "$BEFORE" in
        ABSENT|RESIDUE) residue_only;;
        *) verify_units;;
      esac
      if test "$MODE" = reinstall && test "$BEFORE" != ABSENT && test "$BEFORE" != RESIDUE; then stop_services >> "$WORK/operation.log" 2>&1; fi
      backup
      if test "$BEFORE" = RESIDUE; then
        residue_only
        test ! -d "$ROOT" || mv "$ROOT" "$WORK/residue" || fail RESIDUE_MOVE_FAILED
      fi
    ) || fail PREPARATION_FAILED
    if test "$MODE" = reinstall && test "$BEFORE" != ABSENT && test "$BEFORE" != RESIDUE; then
      /bin/sh "$PKG/root-trust.sh" package "$HELPER" >> "$WORK/operation.log" 2>&1 || fail UNINSTALL_TRUST
      /bin/sh "$HELPER/uninstall-local.sh" remove 8>&- >> "$WORK/operation.log" 2>&1 || fail UNINSTALL_REFUSED
      /bin/sh "$HELPER/uninstall-local.sh" cleanup 8>&- >> "$WORK/operation.log" 2>&1 || fail UNINSTALL_CLEANUP
      for dir in "$ROOT" "$UPDATE" "$HELPER"; do present "$dir" && fail UNINSTALL_INCOMPLETE; done
    elif test "$BEFORE" = REPAIR && present "$ROOT/current.json"; then
      # No replacement of signed V3 slots, receipts or journals.
      ( . "$PKG/lifecycle-lock.sh"; no_pending; start_services ) >> "$WORK/operation.log" 2>&1 || fail V3_SERVICE_RECOVERY
      wait_healthy || fail V3_NEEDS_DIAGNOSIS
      /bin/sh "$PKG/root-migrate-input.sh" config "$ROOT" > "$WORK/identity-after" || fail IDENTITY_INVALID
      cmp -s "$WORK/identity-before" "$WORK/identity-after" || fail IDENTITY_CHANGED
      rm -f "$PKG/conf/bootstrap.properties" "$WORK/identity-before" "$WORK/identity-after"
      echo JA_RESULT=SERVICES_RECOVERED
      exit 0
    fi
    # The package is now a checked, root-owned private copy, not the SSH user's
    # staging. Let bootstrap-config validate it as root instead of trying to
    # read this protected copy as the original sudo caller.
    unset SUDO_UID SUDO_GID SUDO_USER
    /bin/sh "$PKG/install.sh" --system-root --java "$JAVA" 8>&- </dev/null >> "$WORK/operation.log" 2>&1 || fail INSTALL_FAILED
    if test -f "$WORK/identity-before"; then
      /bin/sh "$PKG/root-migrate-input.sh" config "$ROOT" > "$WORK/identity-after" || fail IDENTITY_INVALID
      cmp -s "$WORK/identity-before" "$WORK/identity-after" || fail IDENTITY_CHANGED
    fi
    wait_healthy || fail LOCAL_VERIFICATION
    rm -f "$PKG/conf/bootstrap.properties" "$WORK/identity-before" "$WORK/identity-after"
    echo JA_RESULT=INSTALLED;;
  *) fail ARGUMENTS;;
esac
