#!/bin/sh
# Autonomous Linux bootstrap. Enrollment credentials never appear in arguments.
set -eu
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS CLASSPATH ENV BASH_ENV
if test "$(/usr/bin/id -u)" = 0; then PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH; fi
umask 077
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SYSTEM=false
SYSTEM_ROOT=false
MANAGED=false
ROOT_MANAGED=false
SERVER=''
CODE_FILE=''
DEST=''
JAVA_PATH=''
READ_GROUP=''
MIGRATE_FROM=''
SWITCH_DASHBOARD=false
NO_CRON=false
CONFIGURED_PACKAGE=false
PACKAGED_AUTHORIZATION=false
usage() {
  cat <<'EOF'
Usage: ./install.sh [--server https://dashboard] [options]
  --system             Create a dedicated service user (run installer with sudo).
  --system-root        Run as a root system service; select a trusted compatible Java automatically.
  --migrate-from PATH  Root service: retain an existing collector identity and spool.
  --switch-dashboard   Root service: register this host on a new dashboard using this package.
  --dir /absolute/path  User installation directory (default: $HOME/java-assessment).
  --code-file /path     Read enrollment code from a protected 0600/0400 file.
  --java /path/bin/java Select an existing compatible JVM.
  --read-group GROUP    System mode: join an existing application read-access group.
  --no-cron            Explicit manual operation; no automatic polling installed.
Environment packages configure the dashboard and registration automatically.
Generic packages prompt for a code when --code-file is omitted.
Re-running preserves an existing collector identity and does not require a code.
EOF
}
while test "$#" -gt 0; do
  case "$1" in
    --system) SYSTEM=true; shift;;
    --system-root) SYSTEM_ROOT=true; shift;;
    --managed) MANAGED=true; shift;;
    --managed-root) MANAGED=true; ROOT_MANAGED=true; shift;;
    --configured-origin) PACKAGED_AUTHORIZATION=true; shift;;
    --switch-dashboard) SWITCH_DASHBOARD=true; shift;;
    --no-cron) NO_CRON=true; shift;;
    --server|--dir|--code-file|--java|--read-group|--migrate-from)
      test "$#" -ge 2 || { usage; exit 2; }
      case "$1" in --server) SERVER=$2;; --dir) DEST=$2;; --code-file) CODE_FILE=$2;; --java) JAVA_PATH=$2;; --read-group) READ_GROUP=$2;; --migrate-from) MIGRATE_FROM=$2;; esac
      shift 2;;
    --help|-h) usage; exit 0;;
    *) echo 'Unknown installer option. Use --help.' >&2; exit 2;;
  esac
done
test "$(uname -s)" = Linux || { echo 'This installer currently supports Linux.' >&2; exit 2; }
if $SYSTEM_ROOT; then
  ! $SYSTEM && ! $MANAGED && test -z "$DEST$READ_GROUP" || { echo '--system-root cannot be combined with another installation mode, --dir or --read-group.' >&2; exit 2; }
  set --
  test -z "$SERVER" || set -- "$@" --server "$SERVER"
  test -z "$CODE_FILE" || set -- "$@" --code-file "$CODE_FILE"
  test -z "$JAVA_PATH" || set -- "$@" --java "$JAVA_PATH"
  test -z "$MIGRATE_FROM" || set -- "$@" --migrate-from "$MIGRATE_FROM"
  $SWITCH_DASHBOARD && set -- "$@" --switch-dashboard
  $NO_CRON && set -- "$@" --no-cron
  exec /bin/sh "$SOURCE/root-system-install.sh" "$@"
fi
test -z "$MIGRATE_FROM" || { echo '--migrate-from requires --system-root.' >&2; exit 2; }
$SWITCH_DASHBOARD && { echo '--switch-dashboard requires --system-root.' >&2; exit 2; }
if $SYSTEM; then
  test -z "$DEST" || { echo '--system uses a fixed dedicated directory; omit --dir.' >&2; exit 2; }
  set --
  test -z "$SERVER" || set -- --server "$SERVER"
  test -z "$CODE_FILE" || set -- "$@" --code-file "$CODE_FILE"
  test -z "$JAVA_PATH" || set -- "$@" --java "$JAVA_PATH"
  test -z "$READ_GROUP" || set -- "$@" --read-group "$READ_GROUP"
  $NO_CRON && set -- "$@" --no-cron
  exec /bin/sh "$SOURCE/system-install.sh" "$@"
fi
. "$SOURCE/bootstrap-config.sh"
if $MANAGED; then
  DEST=/opt/java-assessment-collector
  if $ROOT_MANAGED; then
    test "$(id -u)" = 0 && test "$SOURCE" = "$DEST" || { echo 'Root managed mode requires the prepared system package.' >&2; exit 2; }
    test "$(readlink /proc/$$/fd/9)" = /opt/java-assessment-lifecycle.lock && flock -n 9 || { echo 'Root managed mode requires the administrative lifecycle lock.' >&2; exit 2; }
    for update_state in /opt/java-assessment-update/journal.json "$DEST/collector-spool/update-request.json"; do
      test ! -e "$update_state" && test ! -L "$update_state" || { echo 'Agent update is pending; installation refused.' >&2; exit 1; }
    done
    /bin/sh "$DEST/root-trust.sh" package "$DEST"
    $NO_CRON || { echo 'Root polling is configured by the service installer.' >&2; exit 2; }
  else
    test "$(id -un)" = java-assessment && test "$(id -u)" != 0 || { echo 'Managed mode must run as the dedicated service account.' >&2; exit 2; }
  fi
  test "$(stat -c %u "$DEST")" = 0 || { echo 'Managed installation directory must be owned by root.' >&2; exit 2; }
else
  test "$(id -u)" != 0 || { echo 'Use --system-root or --system when running as root, or run as the application user.' >&2; exit 2; }
  DEST=${DEST:-"$HOME/java-assessment"}
fi
ACCOUNT=$(id -un)
test -z "$READ_GROUP" || { echo '--read-group requires --system.' >&2; exit 2; }
# These paths are used in cron and a fixed su command, so keep a narrow alphabet.
case "$DEST" in /*) ;; *) echo 'Installation directory must be absolute.' >&2; exit 2;; esac
case "$DEST" in *[!a-zA-Z0-9_./-]*|*..*|/|/home|/opt|/usr|/var|/tmp) echo 'Use a dedicated installation directory without spaces or shell characters.' >&2; exit 2;; esac
# Java is saved as data in java.path and always passed as one quoted argument.
# Real JDK directory names can contain spaces and build suffixes such as +7.
case "$JAVA_PATH" in ''|/*) ;; *) echo 'Java path must be absolute.' >&2; exit 2;; esac
JAVA_CR=$(printf '\r')
case "$JAVA_PATH" in *'
'*|*"$JAVA_CR"*|*'|'*) echo 'Java path cannot contain line breaks or a pipe character.' >&2; exit 2;; esac
case "$READ_GROUP" in *[!a-zA-Z0-9_-]*) echo 'Invalid read group.' >&2; exit 2;; esac
case "$SERVER" in https://*) ;; http://127.0.0.1:*|http://localhost:*) ;; '') ;; *) echo 'Remote dashboard requires HTTPS.' >&2; exit 2;; esac
for program in stat cksum date sed awk; do
  command -v "$program" >/dev/null 2>&1 || { echo "Required command unavailable: $program" >&2; exit 2; }
done
if ! $NO_CRON; then command -v crontab >/dev/null 2>&1 || { echo 'crontab is unavailable; install cron or explicitly choose --no-cron.' >&2; exit 2; }; fi
if test -n "$CODE_FILE"; then
  test -f "$CODE_FILE" && test ! -L "$CODE_FILE" || { echo 'Code file must be a regular file.' >&2; exit 2; }
  case "$(stat -c %a "$CODE_FILE")" in 600|400) ;; *) echo 'Code file requires mode 600 or 400.' >&2; exit 2;; esac
fi
# Refuse symlinks in installation path and all writable/artifact destinations.
CHECK=$DEST
while test "$CHECK" != /; do
  test ! -L "$CHECK" || { echo 'Installation path cannot contain symlinks.' >&2; exit 2; }
  CHECK=$(dirname "$CHECK")
done
if test -d "$DEST" && ! $MANAGED; then
  test "$(stat -c %u "$DEST")" = "$(id -u)" || { echo 'Installation directory belongs to a different user.' >&2; exit 2; }
fi
mkdir -p "$DEST"
if ! $MANAGED; then chmod 700 "$DEST"; fi
for item in conf logs collector-spool lib collector.jar run-once.sh agent-tool.sh launcher-common.sh bootstrap-config.sh poll.sh conf/collector.properties conf/enrollment.properties conf/installation-id conf/ca.pem conf/java.path logs/install.log; do
  test ! -L "$DEST/$item" || { echo 'Refusing symlink in managed installation files.' >&2; exit 2; }
done
mkdir -p "$DEST/conf" "$DEST/logs" "$DEST/collector-spool" "$DEST/lib"
INSTALL_LOCK=$DEST/collector-spool/.install-lock
if ! mkdir "$INSTALL_LOCK" 2>/dev/null; then
  LOCK_PID=''
  if test -f "$INSTALL_LOCK/pid"; then read -r LOCK_PID < "$INSTALL_LOCK/pid" || true; fi
  case "$LOCK_PID" in
    ''|*[!0-9]*)
      test -n "$(find "$INSTALL_LOCK" -prune -mmin +2 2>/dev/null)" || { echo 'Another installation may be starting.' >&2; exit 1; };;
    *) kill -0 "$LOCK_PID" 2>/dev/null && { echo 'Another installation is active.' >&2; exit 1; };;
  esac
  rm -f "$INSTALL_LOCK/pid"
  rmdir "$INSTALL_LOCK" && mkdir "$INSTALL_LOCK" || exit 1
fi
printf '%s\n' "$$" > "$INSTALL_LOCK/pid"
LOG=$DEST/logs/install.log
if test -f "$LOG" && test "$(wc -c < "$LOG")" -gt 2097152; then
  test ! -f "$LOG.2" || mv "$LOG.2" "$LOG.3"
  test ! -f "$LOG.1" || mv "$LOG.1" "$LOG.2"
  mv "$LOG" "$LOG.1"
fi
RUN_ID=$(date -u +%Y%m%dT%H%M%S)-$$
EVENTS=$DEST/logs/install-events-$RUN_ID.json
printf '[\n]\n' > "$EVENTS"
SUCCESS=false
REGISTERED=false
ECHO_SAVED=''
COUNTER=0
emit() {
  # All event messages are fixed strings; never interpolate credentials or server bodies.
  COUNTER=$(( COUNTER + 1 ))
  WHEN=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  printf '%s [%s] %s: %s\n' "$WHEN" "$2" "$1" "$3" | tee -a "$LOG"
  sed '$d' "$EVENTS" > "$EVENTS.tmp"
  if test "$COUNTER" -gt 1; then printf ',\n' >> "$EVENTS.tmp"; fi
  printf '{"eventId":"%s-%s","time":"%s","stage":"%s","status":"%s","message":"%s"}\n]\n' "$RUN_ID" "$COUNTER" "$WHEN" "$1" "$2" "$3" >> "$EVENTS.tmp"
  mv "$EVENTS.tmp" "$EVENTS"
  if $SYSTEM && id "$ACCOUNT" >/dev/null 2>&1; then chown "$ACCOUNT" "$EVENTS" "$LOG"; fi
}
as_agent() {
  if $SYSTEM; then
    COMMAND=''
    for ARG in "$@"; do
      case "$ARG" in *[!a-zA-Z0-9_./:-]*) echo 'Invalid internal command argument.' >&2; return 2;; esac
      COMMAND="$COMMAND '$ARG'"
    done
    /bin/su -s /bin/sh "$ACCOUNT" -c "$COMMAND"
  else "$@"; fi
}
tool() {
  if as_agent "$DEST/agent-tool.sh" "$@" > "$DEST/logs/setup-output.tmp" 2>&1; then
    cat "$DEST/logs/setup-output.tmp" | tee -a "$LOG"
    rm -f "$DEST/logs/setup-output.tmp"
  else
    RESULT=$?
    cat "$DEST/logs/setup-output.tmp" | tee -a "$LOG"
    rm -f "$DEST/logs/setup-output.tmp"
    return "$RESULT"
  fi
}
flush_events() {
  if $REGISTERED; then
    for BUFFER in "$DEST"/logs/install-events-*.json; do
      test -f "$BUFFER" || continue
      if as_agent "$DEST/agent-tool.sh" events "$DEST/conf/collector.properties" "$BUFFER" >/dev/null 2>&1; then
        # Current file remains for later event append; acknowledged old runs need no resend.
        if test "$BUFFER" != "$EVENTS"; then mv "$BUFFER" "$BUFFER.sent"; fi
      else return 1; fi
    done
  fi
}
cleanup() {
  RESULT=$?
  if test -n "$ECHO_SAVED"; then stty "$ECHO_SAVED" < /dev/tty 2>/dev/null || true; fi
  if ! $SUCCESS; then
    emit failure ERROR 'Installation did not complete. Review the preceding diagnostic and rerun the installer.' || true
    flush_events || true
    echo "Installation log: $LOG" >&2
  fi
  rm -f "$DEST/conf/enrollment.properties"
  rm -f "$INSTALL_LOCK/pid"
  rmdir "$INSTALL_LOCK" 2>/dev/null || true
  trap - 0
  exit "$RESULT"
}
trap cleanup 0
trap 'exit 130' HUP INT TERM
emit system INFO 'Starting installation checks.'
if $SYSTEM; then
  if ! id "$ACCOUNT" >/dev/null 2>&1; then
    /usr/sbin/useradd -r -M -d "$DEST" -s /sbin/nologin "$ACCOUNT"
  else
    test "$(id -u "$ACCOUNT")" != 0 || { emit permissions ERROR 'Service account must not have UID zero.'; exit 1; }
    test "$(getent passwd "$ACCOUNT" | cut -d: -f6)" = "$DEST" || { emit permissions ERROR 'Existing service account has a different home; refusing to change it.'; exit 1; }
  fi
  if test -n "$READ_GROUP"; then
    GROUP_LINE=$(getent group "$READ_GROUP") || { emit permissions ERROR 'Requested read-access group does not exist.'; exit 1; }
    test "$(printf '%s' "$GROUP_LINE" | cut -d: -f3)" != 0 || { emit permissions ERROR 'Group zero is not a read-access group.'; exit 1; }
    /usr/sbin/usermod -a -G "$READ_GROUP" "$ACCOUNT"
  fi
  chown "$ACCOUNT" "$DEST/conf" "$DEST/logs" "$DEST/collector-spool" "$EVENTS" "$LOG"
fi
chmod 700 "$DEST/conf" "$DEST/logs" "$DEST/collector-spool"
if ! $MANAGED; then
for ARTIFACT in collector.jar run-once.sh agent-tool.sh launcher-common.sh bootstrap-config.sh poll.sh; do
  test -f "$SOURCE/$ARTIFACT" || { emit system ERROR 'The installer package is incomplete. Download and extract the complete ZIP.'; exit 1; }
  if test "$SOURCE" != "$DEST"; then cp "$SOURCE/$ARTIFACT" "$DEST/$ARTIFACT.new"; mv "$DEST/$ARTIFACT.new" "$DEST/$ARTIFACT"; fi
done
for ARTIFACT in "$SOURCE"/lib/*.jar; do
  test -f "$ARTIFACT" || { emit tls ERROR 'TLS provider libraries are missing from the package.'; exit 1; }
  test ! -L "$DEST/lib/$(basename "$ARTIFACT")" || exit 1
  if test "$SOURCE" != "$DEST"; then cp "$ARTIFACT" "$DEST/lib/$(basename "$ARTIFACT").new"; mv "$DEST/lib/$(basename "$ARTIFACT").new" "$DEST/lib/$(basename "$ARTIFACT")"; fi
done
chmod 755 "$DEST"/*.sh "$DEST/lib"
chmod 644 "$DEST/collector.jar" "$DEST"/lib/*.jar
if test "$SOURCE" != "$DEST"; then cp "$SOURCE/conf/ca.pem" "$DEST/conf/ca.pem"; fi
chmod 600 "$DEST/conf/ca.pem"
fi
CA_FILE=$DEST/conf/ca.pem
if $MANAGED; then CA_FILE=$DEST/trust/ca.pem; fi
if test -n "$JAVA_PATH"; then printf '%s\n' "$JAVA_PATH" > "$DEST/conf/java.path"; fi
if $SYSTEM; then
  chown root:root "$DEST"/*.sh "$DEST/collector.jar" "$DEST/lib" "$DEST"/lib/*.jar
  chown "$ACCOUNT" "$DEST/conf/ca.pem"
  if test -f "$DEST/conf/java.path"; then chown "$ACCOUNT" "$DEST/conf/java.path"; fi
fi
emit permissions OK 'Collector files installed with protected configuration and logs. Application directory permissions were preserved.'
if test -f "$DEST/conf/collector.properties"; then
  emit registration INFO 'Existing collector identity found; validating it without creating another registration.'
else
  if test -e "$SOURCE/conf/bootstrap.properties" || test -L "$SOURCE/conf/bootstrap.properties"; then
    assessment_read_bootstrap "$SOURCE/conf/bootstrap.properties" || { emit registration ERROR 'Configured package registration data is invalid. Download the package again.'; exit 1; }
    CONFIGURED_PACKAGE=true
    PACKAGED_AUTHORIZATION=true
    if test -z "$SERVER"; then SERVER=$BOOTSTRAP_SERVER; fi
  fi
  test -n "$SERVER" || { emit registration ERROR 'A dashboard URL is required for a new registration.'; exit 1; }
  assessment_validate_server "$SERVER" || exit 1
  SERVER=$ASSESSMENT_SERVER_VALUE
  if test -n "$CODE_FILE"; then
    assessment_read_code "$CODE_FILE" || exit 1
  elif $CONFIGURED_PACKAGE; then
    test "$SERVER" = "$BOOTSTRAP_SERVER" || { emit registration ERROR 'The packaged authorization belongs to another dashboard. Use its original URL or provide an explicit code file.'; exit 1; }
    CODE=$BOOTSTRAP_TOKEN
  else
    test -r /dev/tty || { emit registration ERROR 'No terminal available. Use a protected code file.'; exit 1; }
    printf 'Enrollment code: ' > /dev/tty
    ECHO_SAVED=$(stty -g < /dev/tty)
    stty -echo < /dev/tty
    IFS= read -r CODE < /dev/tty || CODE=''
    stty "$ECHO_SAVED" < /dev/tty
    ECHO_SAVED=''
    printf '\n' > /dev/tty
  fi
  assessment_validate_code "$CODE" || { emit registration ERROR 'Enrollment code has an invalid format.'; exit 1; }
  unset BOOTSTRAP_TOKEN
  # URL syntax is fully verified in the Java setup utility before any request.
  case "$SERVER" in *[!a-zA-Z0-9_./:-]*) emit tls ERROR 'Use a dashboard URL without credentials, query string or shell characters.'; exit 1;; esac
  {
    printf 'serverUrl=%s\nenrollmentToken=%s\n' "$SERVER" "$CODE"
    printf 'installationIdPath=%s/conf/installation-id\nspoolDirectory=%s/collector-spool\n' "$DEST" "$DEST"
    printf 'tlsProvider=BCJSSE\ntlsCaFile=%s\n' "$CA_FILE"
  } > "$DEST/conf/enrollment.properties"
  unset CODE
  # If the package was extracted directly into the operational directory, remove
  # that initial authorization after it has been read into the protected request.
  if $CONFIGURED_PACKAGE && test "$SOURCE" = "$DEST"; then rm -f "$SOURCE/conf/bootstrap.properties"; fi
  chmod 600 "$DEST/conf/enrollment.properties"
  if $SYSTEM; then chown "$ACCOUNT" "$DEST/conf/enrollment.properties"; fi
  emit java INFO 'Searching for an existing compatible JVM and testing the registration connection.'
  if ! tool register "$DEST/conf/enrollment.properties" "$DEST/conf/collector.properties"; then
    if $PACKAGED_AUTHORIZATION; then
      emit registration ERROR 'Registration did not complete. Check the Java, TLS and connection diagnostics above. If the dashboard reports expired or revoked authorization, download a new package from the environment.'
    fi
    exit 1
  fi
  rm -f "$DEST/conf/enrollment.properties"
fi
REGISTERED=true
tool diagnose "$DEST/conf/collector.properties"
emit registration OK 'Dashboard confirmed the collector registration; identity saved locally.'
emit tls OK 'Authenticated dashboard connection validated.'
emit heartbeat OK 'First operational contact confirmed by the dashboard.'
flush_events || emit diagnostic WARNING 'Installation events are buffered locally and will be retried.'
if $NO_CRON; then
  if $ROOT_MANAGED; then emit scheduler INFO 'Root system service polling is configured by the administrative installer.'
  else emit scheduler WARNING 'Manual mode selected. Automatic polling has not been installed.'; fi
else
  if $SYSTEM; then
    test ! -L /etc/cron.d/java-assessment-collector || { emit scheduler ERROR 'Cron destination is a symlink.'; exit 1; }
    {
      printf 'SHELL=/bin/sh\nPATH=/usr/bin:/bin\n'
      printf '* * * * * %s %s/poll.sh\n' "$ACCOUNT" "$DEST"
    } > /etc/cron.d/java-assessment-collector
    chown root:root /etc/cron.d/java-assessment-collector
    chmod 644 /etc/cron.d/java-assessment-collector
  else
    MARKER="java-assessment-$(printf '%s' "$DEST" | cksum | awk '{print $1}')"
    if LC_ALL=C crontab -l > "$DEST/logs/crontab-before.tmp" 2> "$DEST/logs/crontab-error.tmp"; then :
    elif test -s "$DEST/logs/crontab-before.tmp" || ! LC_ALL=C grep -qi 'no crontab' "$DEST/logs/crontab-error.tmp"; then
      emit scheduler ERROR 'Cannot read the existing user crontab; no entries were changed.'; exit 1
    fi
    awk -v marker="$MARKER" 'index($0,marker)==0' "$DEST/logs/crontab-before.tmp" > "$DEST/logs/crontab-next.tmp"
    printf '* * * * * %s/poll.sh # %s\n' "$DEST" "$MARKER" >> "$DEST/logs/crontab-next.tmp"
    crontab "$DEST/logs/crontab-next.tmp"
    rm -f "$DEST/logs/crontab-before.tmp" "$DEST/logs/crontab-next.tmp" "$DEST/logs/crontab-error.tmp"
  fi
  emit scheduler OK 'One-minute task polling installed. Scans follow the dashboard schedule.'
fi
flush_events || true
emit diagnostic INFO 'Executing the first collector cycle; an assessment runs only when assigned by the dashboard.'
if as_agent "$DEST/run-once.sh" "$DEST/conf/collector.properties" > "$DEST/logs/first-cycle.tmp" 2>&1; then
  cat "$DEST/logs/first-cycle.tmp" | tee -a "$LOG"
  rm -f "$DEST/logs/first-cycle.tmp"
else
  RESULT=$?
  cat "$DEST/logs/first-cycle.tmp" | tee -a "$LOG"
  rm -f "$DEST/logs/first-cycle.tmp"
  emit diagnostic ERROR 'First collector cycle failed. Identity is retained; inspect the diagnostic and rerun.'
  exit "$RESULT"
fi
emit complete OK 'Installation completed. Check the dashboard for the separate first-assessment receipt.'
if flush_events; then mv "$EVENTS" "$EVENTS.sent"; else emit diagnostic WARNING 'Dashboard event upload is pending; automatic polling will retry.'; fi
SUCCESS=true
# Completed event batches are diagnostic history; keep recent bounded history.
find "$DEST/logs" -name 'install-events-*.json.sent' -type f -mtime +14 -exec rm -f '{}' \;
echo "Installation log: $LOG"
echo "Diagnostics: $DEST/agent-tool.sh diagnose $DEST/conf/collector.properties"
echo "Dashboard: ${SERVER:-configured in conf/collector.properties}"
