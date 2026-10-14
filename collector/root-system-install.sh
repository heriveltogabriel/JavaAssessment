#!/bin/sh
# Administrative root-service installation. Application permissions are untouched.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
# Environment hints are inert paths until the discovery helper validates them.
INPUT_JAVA_HOME=${JAVA_HOME:-}
INPUT_JDK_HOME=${JDK_HOME:-}
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_HOME JDK_HOME ASSESSMENT_JAVA ASSESSMENT_JAVA_HOME CLASSPATH ENV BASH_ENV
umask 077
test "$(id -u)" = 0 || { echo 'Use sudo ./install.sh --system-root.' >&2; exit 2; }
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEST=/opt/java-assessment-collector
. "$SOURCE/lifecycle-lock.sh"
# Finish an already-running independent uninstall invocation before inspecting
# its durable state. Its destructive stage also needs the lifecycle lock.
if test -d /opt/java-assessment-uninstall; then
  /bin/sh "$SOURCE/root-trust.sh" package /opt/java-assessment-uninstall
  if command -v systemctl >/dev/null 2>&1 && test -d /run/systemd/system; then systemctl stop java-assessment-uninstall.service
  else /bin/sh /opt/java-assessment-uninstall/uninstall-service.sh stop; fi
fi
for update_state in /opt/java-assessment-update/journal.json "$DEST/collector-spool/update-request.json"; do
  test ! -e "$update_state" && test ! -L "$update_state" || { echo 'Agent update is pending; installation refused.' >&2; exit 1; }
done
if test -d /opt/java-assessment-update; then
  /bin/sh "$SOURCE/root-trust.sh" package /opt/java-assessment-update
  if command -v systemctl >/dev/null 2>&1 && test -d /run/systemd/system; then systemctl stop java-assessment-update.service
  else /bin/sh /opt/java-assessment-update/update-service.sh stop; fi
fi
for uninstall_state in /opt/java-assessment-uninstall/request.json /opt/java-assessment-uninstall/claim.json "$DEST/collector-spool/uninstall-request.json"; do
  test ! -e "$uninstall_state" && test ! -L "$uninstall_state" || { echo "Agent uninstall is pending; installation refused." >&2; exit 1; }
done
SERVER=''; CODE_FILE=''; JAVA_PATH=''; SAVED_JAVA_PATH=''; MIGRATE_FROM=''; NO_SERVICE=false; SWITCH_DASHBOARD=false
while test "$#" -gt 0; do
  case "$1" in
    --no-cron) NO_SERVICE=true; shift;;
    --switch-dashboard) SWITCH_DASHBOARD=true; shift;;
    --server|--code-file|--java|--migrate-from)
      test "$#" -ge 2 || exit 2
      case "$1" in --server) SERVER=$2;; --code-file) CODE_FILE=$2;; --java) JAVA_PATH=$2;; --migrate-from) MIGRATE_FROM=$2;; esac
      shift 2;;
    *) echo 'Unknown root system installer option.' >&2; exit 2;;
  esac
done
if $SWITCH_DASHBOARD; then
  test -z "$SERVER$CODE_FILE$MIGRATE_FROM" || { echo '--switch-dashboard requires a configured environment package without --server, --code-file or --migrate-from.' >&2; exit 2; }
  test "$SOURCE" != "$DEST" || { echo 'Extract a fresh environment package in a separate private directory.' >&2; exit 2; }
  test -f "$DEST/conf/collector.properties" || { echo '--switch-dashboard requires an existing root collector identity.' >&2; exit 2; }
fi
# An explicit choice never silently falls back to a different runtime.
if test -n "$JAVA_PATH"; then JAVA_PATH=$(/bin/sh "$SOURCE/root-trust.sh" java "$JAVA_PATH"); fi
. "$SOURCE/bootstrap-config.sh"
for program in stat getent mktemp cp mv awk sed crontab; do command -v "$program" >/dev/null 2>&1 || { echo "Required administrative command unavailable: $program" >&2; exit 2; }; done
safe_path() { case "$1" in /*) ;; *) return 1;; esac; case "$1" in ''|*[!a-zA-Z0-9_./-]*|*..*|/|/home|/opt|/usr|/var|/tmp) return 1;; esac; }
safe_parent() {
  check=$1
  while test "$check" != /; do
    test ! -L "$check" && test "$(stat -c %u "$check")" = 0 || return 1
    mode=$(stat -c %a "$check"); test "$((0$mode & 022))" = 0 || return 1
    check=$(dirname "$check")
  done
}
safe_parent /opt || { echo 'System package parent must be protected and root-owned.' >&2; exit 1; }
test ! -L "$DEST" || exit 1
if test -d "$DEST"; then safe_parent "$DEST" || { echo 'Existing system package directory is not trusted.' >&2; exit 1; }; else mkdir "$DEST"; chmod 700 "$DEST"; fi
LOCK=$DEST/.root-install-lock
mkdir "$LOCK" 2>/dev/null || { echo 'Another root installation may be active; inspect .root-install-lock before retrying.' >&2; exit 1; }
printf '%s\n' "$$" > "$LOCK/pid"
STAGE=$(mktemp -d "$DEST/.root-stage.XXXXXX")
DONE=false
ORIGIN_LOCK=''
DESTINATION_LOCK=''
cleanup() {
  result=$?
  if ! $DONE; then echo 'Root service installation did not complete; the protected identity is retained for retry.' >&2; fi
  for held_lock in "$ORIGIN_LOCK" "$DESTINATION_LOCK"; do
    test -n "$held_lock" || continue
    # rm/rmdir do not follow a replaced leaf symlink. Never recurse into origin.
    if test -d "$held_lock" && test ! -L "$held_lock" && test "$(stat -c %u "$held_lock")" = 0; then
      rm -f "$held_lock/pid"; rmdir "$held_lock" 2>/dev/null || true
    fi
  done
  rm -rf "$STAGE"
  rm -f "$LOCK/pid"; rmdir "$LOCK" 2>/dev/null || true
  trap - 0; exit "$result"
}
trap cleanup 0
trap 'exit 130' HUP INT TERM
RUN_ID=$(date -u +%Y%m%dT%H%M%S)-$$
INPUT_ACCOUNT=''
if test -n "${SUDO_UID:-}" && test "$SUDO_UID" != 0; then
  case "$SUDO_UID" in *[!0-9]*) exit 2;; esac
  INPUT_ACCOUNT=$(getent passwd "$SUDO_UID" | cut -d: -f1)
  case "$INPUT_ACCOUNT" in ''|*[!a-zA-Z0-9_-]*) exit 2;; esac
fi
read_input() {
  input_path=$(assessment_absolute_input "$2")
  if test -n "$INPUT_ACCOUNT"; then
    ASSESSMENT_READ_MODE=$1 ASSESSMENT_READ_PATH=$input_path /bin/su -s /bin/sh "$INPUT_ACCOUNT" -c 'exec /bin/sh -s -- "$ASSESSMENT_READ_MODE" "$ASSESSMENT_READ_PATH"' < "$SOURCE/bootstrap-config.sh"
  else /bin/sh "$SOURCE/bootstrap-config.sh" "$1" "$input_path"; fi
}
if $SWITCH_DASHBOARD; then
  test -f "$SOURCE/conf/bootstrap.properties" && test ! -L "$SOURCE/conf/bootstrap.properties" || { echo 'The new environment package has no embedded registration authorization.' >&2; exit 2; }
  read_input read-bootstrap "$SOURCE/conf/bootstrap.properties" > "$STAGE/bootstrap"
  assessment_parse_bootstrap < "$STAGE/bootstrap"
  unset BOOTSTRAP_TOKEN
fi
migration_owner() {
  safe_path "$1" || { echo 'Use an explicit dedicated migration directory.' >&2; return 1; }
  test -f "$1/conf/collector.properties" && test ! -L "$1/conf/collector.properties" || return 1
  owner=$(stat -c %u "$1/conf/collector.properties")
  if test "$1" != "$DEST" && test -n "${SUDO_UID:-}" && test "$SUDO_UID" != 0; then
    test "$owner" = "$SUDO_UID" || { echo 'Migration input must belong to the invoking user.' >&2; return 1; }
  fi
  getent passwd "$owner" | cut -d: -f1
}
read_migration() {
  account=$1; shift
  case "$account" in ''|*[!a-zA-Z0-9_-]*) return 1;; esac
  if test "$account" = root; then /bin/sh "$SOURCE/root-migrate-input.sh" "$@"
  else
    ASSESSMENT_MIGRATE_MODE=$1 ASSESSMENT_MIGRATE_SOURCE=$2 ASSESSMENT_MIGRATE_NAME=${3:-} /bin/su -s /bin/sh "$account" -c 'exec /bin/sh -s -- "$ASSESSMENT_MIGRATE_MODE" "$ASSESSMENT_MIGRATE_SOURCE" "$ASSESSMENT_MIGRATE_NAME"' < "$SOURCE/root-migrate-input.sh"
  fi
}
# Pause this service before capturing queues, including a root reinstallation.
if test -f "$DEST/conf/root-service-mode"; then
  /bin/sh "$SOURCE/root-trust.sh" package "$DEST"
  if test -z "$JAVA_PATH" && test -f "$DEST/conf/java.path"; then
    IFS= read -r SAVED_JAVA_PATH < "$DEST/conf/java.path" || true
  fi
  if command -v systemctl >/dev/null 2>&1 && test -d /run/systemd/system; then
    # A previous manual or incomplete installation may have no systemd units.
    # Only the explicit not-found state is skipped; inspection failures abort.
    for old_unit in java-assessment-collector.timer java-assessment-collector.service; do
      old_state=$(systemctl show --property=LoadState "$old_unit" 2>&1) || true
      case "$old_state" in
        LoadState=not-found) ;;
        LoadState=?*) systemctl stop "$old_unit";;
        *) echo "Cannot inspect the previous collector unit $old_unit: $old_state" >&2; exit 1;;
      esac
    done
  elif test -f "$DEST/root-service.sh"; then /bin/sh "$DEST/root-service.sh" stop; fi
fi
freeze_source() {
  freeze_base=$1
  safe_path "$freeze_base" || return 1
  freeze_check=$freeze_base/collector-spool
  while test "$freeze_check" != /; do
    test ! -L "$freeze_check" || { echo 'Migration state path cannot contain symlinks.' >&2; return 1; }
    freeze_check=$(dirname "$freeze_check")
  done
  test -d "$freeze_base/collector-spool" || { echo 'Existing collector spool is missing.' >&2; return 1; }
  freeze_lock=$freeze_base/collector-spool/.install-lock
  # Never steal an existing lock, even if its PID appears stale.
  mkdir "$freeze_lock" 2>/dev/null || { echo 'Existing collector installation lock must be resolved before migration.' >&2; return 1; }
  if test "$freeze_base" = "$DEST"; then DESTINATION_LOCK=$freeze_lock; else ORIGIN_LOCK=$freeze_lock; fi
  test ! -L "$freeze_lock" && test "$(stat -c %u "$freeze_lock")" = 0 || return 1
  # A local private tempfile also works when /home and /opt use different disks.
  freeze_pid_file=$(mktemp "$freeze_lock/.pid.XXXXXX")
  printf '%s\n' "$$" > "$freeze_pid_file"
  ln "$freeze_pid_file" "$freeze_lock/pid"
  rm -f "$freeze_pid_file"
  freeze_wait=0
  while test -d "$freeze_base/collector-spool/.poll-lock"; do
    poll_lock=$freeze_base/collector-spool/.poll-lock
    test ! -L "$poll_lock" || return 1
    if test -f "$poll_lock/pid"; then
      test ! -L "$poll_lock/pid" && test "$(stat -c %s "$poll_lock/pid")" -le 32 || return 1
      poll_pid=$(cat "$poll_lock/pid")
      case "$poll_pid" in ''|*[!0-9]*) echo 'Existing poll lock has an invalid PID.' >&2; return 1;; esac
      if ! kill -0 "$poll_pid" 2>/dev/null; then break; fi
    fi
    test "$freeze_wait" -lt 1500 || { echo 'Existing collector has not completed its bounded cycle; migration was paused.' >&2; return 1; }
    sleep 5; freeze_wait=$((freeze_wait + 5))
  done
}
IDENTITY_SOURCE=''; MIGRATION_ACCOUNT=''; ORIGIN_ACCOUNT=''
if test -f "$DEST/conf/collector.properties"; then
  IDENTITY_SOURCE=$DEST; MIGRATION_ACCOUNT=$(migration_owner "$DEST")
  freeze_source "$DEST"
  read_migration "$MIGRATION_ACCOUNT" config "$DEST" > "$STAGE/identity"
fi
if test -n "$MIGRATE_FROM"; then
  test "$MIGRATE_FROM" != "$DEST" || { echo "Omit --migrate-from when reinstalling the fixed system directory." >&2; exit 2; }
  ORIGIN_ACCOUNT=$(migration_owner "$MIGRATE_FROM")
  freeze_source "$MIGRATE_FROM"
  read_migration "$ORIGIN_ACCOUNT" config "$MIGRATE_FROM" > "$STAGE/origin-identity"
  if test -n "$IDENTITY_SOURCE"; then
    LC_ALL=C awk '/^(serverUrl|hostId|agentToken)=/' "$STAGE/identity" | LC_ALL=C sort > "$STAGE/current-key"
    LC_ALL=C awk '/^(serverUrl|hostId|agentToken)=/' "$STAGE/origin-identity" | LC_ALL=C sort > "$STAGE/origin-key"
    cmp -s "$STAGE/current-key" "$STAGE/origin-key" || { echo 'Existing destination identity differs; no collector was rebound.' >&2; exit 1; }
  else cp "$STAGE/origin-identity" "$STAGE/identity"; IDENTITY_SOURCE=$MIGRATE_FROM; MIGRATION_ACCOUNT=$ORIGIN_ACCOUNT; fi
fi
if $SWITCH_DASHBOARD; then
  # The old credential, logs and pending results are retained in BACKUP below.
  # They must never be sent to another dashboard as this host's new identity.
  rm -f "$STAGE/identity"
  IDENTITY_SOURCE=''
fi
mkdir "$STAGE/state"
if test -n "$IDENTITY_SOURCE"; then
  read_migration "$MIGRATION_ACCOUNT" list-state "$IDENTITY_SOURCE" > "$STAGE/state-list"
  while IFS= read -r relative; do
    mkdir -p "$STAGE/state/$(dirname "$relative")"
    read_migration "$MIGRATION_ACCOUNT" state "$IDENTITY_SOURCE" "$relative" > "$STAGE/state/$relative"
  done < "$STAGE/state-list"
fi
# Capture embedded authorization before an in-place conf/ rotation.
if test ! -f "$STAGE/identity" && { test -e "$SOURCE/conf/bootstrap.properties" || test -L "$SOURCE/conf/bootstrap.properties"; }; then
  read_input read-bootstrap "$SOURCE/conf/bootstrap.properties" > "$STAGE/bootstrap"
fi
BACKUP=/opt/java-assessment-collector-backup-$RUN_ID
mkdir "$BACKUP"; chmod 700 "$BACKUP"
if $SWITCH_DASHBOARD && test -f "$DEST/trust/ca.pem"; then
  cp "$DEST/trust/ca.pem" "$BACKUP/old-dashboard-ca.pem"
  chmod 600 "$BACKUP/old-dashboard-ca.pem"
fi
for name in conf logs collector-spool; do
  test ! -L "$DEST/$name" || { echo 'Existing collector state directory is a symlink.' >&2; exit 1; }
  if test -e "$DEST/$name"; then
    mv "$DEST/$name" "$BACKUP/$name"
    if test "$name" = collector-spool && test -n "$DESTINATION_LOCK"; then DESTINATION_LOCK=$BACKUP/collector-spool/.install-lock; fi
  fi
  mkdir "$DEST/$name"; chmod 700 "$DEST/$name"
done
chmod 700 "$DEST"
mkdir -p "$DEST/lib" "$DEST/trust" "$DEST/run" "$DEST/backups"
for name in lib trust run backups; do safe_parent "$DEST/$name" || exit 1; chmod 700 "$DEST/$name"; done
copy_artifact() {
  test -f "$1" && test ! -L "$1" || { echo 'Missing or symbolic-link package artifact.' >&2; return 1; }
  test ! -L "$2" || return 1
  temporary=$(mktemp "$DEST/.artifact.XXXXXX")
  cp "$1" "$temporary"; chown root:root "$temporary"; chmod "$3" "$temporary"; mv -f "$temporary" "$2"
}
for name in install.sh system-install.sh root-system-install.sh root-migrate-input.sh root-service.sh root-trust.sh root-java-discovery.sh launcher-common.sh bootstrap-config.sh run-once.sh agent-tool.sh poll.sh collector.jar uninstall-install.sh uninstall-service.sh uninstall-local.sh lifecycle-lock.sh update-install.sh update-service.sh update-local.sh update-public.der; do
  mode=700; test "$name" != collector.jar || mode=600
  test "$name" != update-public.der || mode=600
  if test "$SOURCE" != "$DEST"; then copy_artifact "$SOURCE/$name" "$DEST/$name" "$mode"; else test -f "$DEST/$name" && test ! -L "$DEST/$name" || exit 1; chmod "$mode" "$DEST/$name"; fi
done
for file in "$SOURCE"/lib/*.jar; do
  name=$(basename "$file"); case "$name" in *[!a-zA-Z0-9_.-]*) exit 1;; esac
  if test "$SOURCE" != "$DEST"; then copy_artifact "$file" "$DEST/lib/$name" 600; fi
done
CA_SOURCE=$SOURCE/conf/ca.pem
if test "$SOURCE" = "$DEST"; then CA_SOURCE=$DEST/trust/ca.pem; fi
copy_artifact "$CA_SOURCE" "$DEST/trust/ca.pem" 600
if test -f "$STAGE/identity"; then
  while IFS= read -r line; do
    case "$line" in installationId=*) printf '%s\n' "${line#installationId=}" > "$DEST/conf/installation-id";; *) printf '%s\n' "$line" >> "$DEST/conf/collector.properties";; esac
  done < "$STAGE/identity"
  printf 'spoolDirectory=%s/collector-spool\nlogDirectory=%s/logs\ntlsProvider=BCJSSE\ntlsCaFile=%s/trust/ca.pem\n' "$DEST" "$DEST" "$DEST" >> "$DEST/conf/collector.properties"
  if test -n "$SERVER"; then
    assessment_validate_server "$SERVER" || exit 1
    test "$(sed -n 's/^serverUrl=//p' "$DEST/conf/collector.properties")" = "$ASSESSMENT_SERVER_VALUE" || { echo 'The existing identity belongs to another dashboard.' >&2; exit 1; }
  fi
  if test -f "$STAGE/state-list"; then while IFS= read -r relative; do cp "$STAGE/state/$relative" "$DEST/$relative"; chmod 600 "$DEST/$relative"; done < "$STAGE/state-list"; fi
fi
CONFIGURED=false; TEMP_CODE=''
if test ! -f "$DEST/conf/collector.properties"; then
  if test -f "$STAGE/bootstrap"; then
    assessment_parse_bootstrap < "$STAGE/bootstrap"; CONFIGURED=true
    test -n "$SERVER" || SERVER=$BOOTSTRAP_SERVER
  fi
  test -n "$SERVER" || { echo 'New root registrations require a configured package or --server.' >&2; exit 1; }
  assessment_validate_server "$SERVER"; SERVER=$ASSESSMENT_SERVER_VALUE
  if test -n "$CODE_FILE"; then read_input read-code "$CODE_FILE" > "$STAGE/code"
  elif $CONFIGURED; then
    test "$SERVER" = "$BOOTSTRAP_SERVER" || { echo 'The packaged authorization belongs to another dashboard.' >&2; exit 1; }
    printf '%s\n' "$BOOTSTRAP_TOKEN" > "$STAGE/code"; unset BOOTSTRAP_TOKEN
  else
    echo 'Use a configured environment package or a protected --code-file for a new root installation.' >&2; exit 1
  fi
  TEMP_CODE=$STAGE/code; chmod 600 "$TEMP_CODE"
fi
/bin/sh "$DEST/root-trust.sh" package "$DEST"
if test -z "$JAVA_PATH"; then
  echo 'Selecting an existing, protected JVM for the root collector.'
  JAVA_PATH=$(/bin/sh "$DEST/root-java-discovery.sh" "$DEST" "$SAVED_JAVA_PATH" "$INPUT_JAVA_HOME" "$INPUT_JDK_HOME") || {
    printf '%s [ERROR] java: Automatic JVM selection failed. Review the terminal diagnostics or use --java with an existing protected JVM.\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$DEST/logs/install.log"
    exit 1
  }
fi
printf '%s\n' "$JAVA_PATH" > "$DEST/conf/java.path"
printf '%s [OK] java: Selected root-protected Java: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$JAVA_PATH" >> "$DEST/logs/install.log"
set -- --managed-root --no-cron --java "$JAVA_PATH"
test -z "$SERVER" || set -- "$@" --server "$SERVER"
test -z "$TEMP_CODE" || set -- "$@" --code-file "$TEMP_CODE"
$CONFIGURED && set -- "$@" --configured-origin
/bin/sh "$DEST/install.sh" "$@"
printf 'system-root\n' > "$DEST/conf/root-service-mode"

disable_origin_cron() {
  account=$1; base=$2; marker="java-assessment-$(printf '%s' "$base" | cksum | awk '{print $1}')"
  before=$DEST/backups/crontab-$account-$RUN_ID
  if LC_ALL=C crontab -u "$account" -l > "$before" 2> "$STAGE/cron-error"; then :
  elif test ! -s "$before" && LC_ALL=C grep -qi 'no crontab' "$STAGE/cron-error"; then return 0
  else echo 'Could not inspect the old collector crontab; service was not enabled.' >&2; return 1; fi
  awk -v path="$base/poll.sh" -v marker="$marker" '!(NF==8 && $1=="*" && $2=="*" && $3=="*" && $4=="*" && $5=="*" && $6==path && $7=="#" && $8==marker)' "$before" > "$STAGE/cron-next"
  if ! cmp -s "$before" "$STAGE/cron-next"; then crontab -u "$account" "$STAGE/cron-next"; fi
}
if test -n "$MIGRATE_FROM"; then disable_origin_cron "$ORIGIN_ACCOUNT" "$MIGRATE_FROM"; fi
if test -n "$IDENTITY_SOURCE" && test "$IDENTITY_SOURCE" = "$DEST" && test "$MIGRATION_ACCOUNT" != root; then disable_origin_cron "$MIGRATION_ACCOUNT" "$DEST"; fi
OLD_CRON=/etc/cron.d/java-assessment-collector
if test -e "$OLD_CRON" || test -L "$OLD_CRON"; then
  test -f "$OLD_CRON" && test ! -L "$OLD_CRON" && test "$(stat -c %u "$OLD_CRON")" = 0 || exit 1
  cp "$OLD_CRON" "$DEST/backups/cron-d-$RUN_ID"; chmod 600 "$DEST/backups/cron-d-$RUN_ID"
  awk -v path="$DEST/poll.sh" '!(NF==7 && $1=="*" && $2=="*" && $3=="*" && $4=="*" && $5=="*" && ($6=="root" || $6=="java-assessment") && $7==path)' "$OLD_CRON" > "$STAGE/cron-d-next"
  if ! cmp -s "$OLD_CRON" "$STAGE/cron-d-next"; then
    temporary=$(mktemp /etc/cron.d/.java-assessment.XXXXXX); cp "$STAGE/cron-d-next" "$temporary"; chmod 644 "$temporary"; mv "$temporary" "$OLD_CRON"
  fi
fi
rm -f "$DEST/conf/uninstall-capability" "$DEST/conf/update-capability"
if ! $NO_SERVICE; then
  if command -v systemctl >/dev/null 2>&1 && test -d /run/systemd/system; then
    safe_parent /etc/systemd/system || exit 1
    for unit in service timer; do
      target=/etc/systemd/system/java-assessment-collector.$unit
      if test -e "$target" || test -L "$target"; then test -f "$target" && test ! -L "$target" && grep -F 'Java Assessment collector' "$target" >/dev/null || { echo 'Existing service name belongs to another configuration.' >&2; exit 1; }; fi
    done
    cat > "$STAGE/service" <<'SERVICE'
# Java Assessment collector root service
[Unit]
Description=Java Assessment collector bounded cycle
After=network-online.target
Wants=network-online.target
[Service]
Type=oneshot
User=root
Group=root
UMask=0077
ExecStart=/bin/sh /opt/java-assessment-collector/root-service.sh once
TimeoutStartSec=1500
NoNewPrivileges=true
ProtectSystem=full
SERVICE
    cat > "$STAGE/timer" <<'TIMER'
# Java Assessment collector root timer
[Unit]
Description=Java Assessment collector polling timer
[Timer]
OnBootSec=15s
OnUnitInactiveSec=60s
AccuracySec=1s
Unit=java-assessment-collector.service
[Install]
WantedBy=timers.target
TIMER
    for unit in service timer; do temporary=$(mktemp /etc/systemd/system/.java-assessment.XXXXXX); cp "$STAGE/$unit" "$temporary"; chmod 644 "$temporary"; mv "$temporary" "/etc/systemd/system/java-assessment-collector.$unit"; done
    systemctl daemon-reload
    systemctl enable --now java-assessment-collector.timer
    systemctl is-active --quiet java-assessment-collector.timer
    /bin/sh "$DEST/uninstall-install.sh" systemd
    /bin/sh "$DEST/update-install.sh" systemd
  else
    INIT_DIRECTORY=$(readlink -f /etc/init.d)
    case "$INIT_DIRECTORY" in /etc/init.d|/etc/rc.d/init.d) ;; *) echo 'Unsupported SysV init directory.' >&2; exit 1;; esac
    safe_parent "$INIT_DIRECTORY" || exit 1
    target=$INIT_DIRECTORY/java-assessment-collector
    if test -e "$target" || test -L "$target"; then test -f "$target" && test ! -L "$target" && grep -F "$DEST/root-service.sh" "$target" >/dev/null || exit 1; fi
    temporary=$(mktemp "$INIT_DIRECTORY/.java-assessment.XXXXXX")
    cat > "$temporary" <<'INIT'
#!/bin/sh
# chkconfig: 345 98 02
# description: Java Assessment collector shell supervisor
# processname: java-assessment-collector
exec /bin/sh /opt/java-assessment-collector/root-service.sh "$@"
INIT
    chmod 755 "$temporary"; mv "$temporary" "$target"
    command -v chkconfig >/dev/null 2>&1 || { echo 'chkconfig is required for the SysV service.' >&2; exit 1; }
    chkconfig --add java-assessment-collector
    chkconfig java-assessment-collector on
    /bin/sh "$target" start
    /bin/sh "$DEST/uninstall-install.sh" sysv
    /bin/sh "$DEST/update-install.sh" sysv
  fi
fi
WHEN=$(date -u +%Y-%m-%dT%H:%M:%SZ)
message='Root system service polling installed. Application permissions were preserved.'
status=OK
if $NO_SERVICE; then message='Root installation validated in explicit manual mode; automatic polling is disabled.'; status=WARNING; fi
printf '%s [%s] scheduler: %s\n' "$WHEN" "$status" "$message" | tee -a "$DEST/logs/install.log"
events=$DEST/logs/install-events-root-$RUN_ID.json
printf '[{"eventId":"root-service-%s","time":"%s","stage":"scheduler","status":"%s","message":"%s"}]\n' "$RUN_ID" "$WHEN" "$status" "$message" > "$events"
if "$DEST/agent-tool.sh" events "$DEST/conf/collector.properties" "$events" >/dev/null 2>&1; then mv "$events" "$events.sent"; fi
DONE=true
if $SWITCH_DASHBOARD; then
  echo "Root collector registered on the new dashboard. Old identity and pending data: $BACKUP"
else
  echo 'Root collector installation validated; its existing identity and protected pending data were retained.'
fi
echo "Service: java-assessment-collector; log: $DEST/logs/install.log"
