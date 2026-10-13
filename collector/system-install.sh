#!/bin/sh
# Privileged preparation only. Runtime files are written by the service account.
set -eu
umask 077
test "$(id -u)" = 0 || { echo 'Use sudo ./install.sh --system.' >&2; exit 2; }
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEST=/opt/java-assessment-collector
ACCOUNT=java-assessment
SERVER=''; CODE_FILE=''; JAVA_PATH=''; READ_GROUP=''; NO_CRON=false
CONFIGURED_PACKAGE=false
while test "$#" -gt 0; do
  case "$1" in
    --no-cron) NO_CRON=true; shift;;
    --server|--code-file|--java|--read-group)
      test "$#" -ge 2 || exit 2
      case "$1" in --server) SERVER=$2;; --code-file) CODE_FILE=$2;; --java) JAVA_PATH=$2;; --read-group) READ_GROUP=$2;; esac
      shift 2;;
    *) exit 2;;
  esac
done
. "$SOURCE/bootstrap-config.sh"
# A sudo installation reads user-controlled credential inputs as the original
# invoking user. It never uses root to open an arbitrary user-supplied pathname.
INPUT_ACCOUNT=''
if test -n "${SUDO_UID:-}" && test "$SUDO_UID" != 0; then
  case "$SUDO_UID" in *[!0-9]*) echo 'Invalid invoking account identity.' >&2; exit 2;; esac
  INPUT_ENTRY=$(getent passwd "$SUDO_UID") || { echo 'Cannot identify the invoking account.' >&2; exit 2; }
  INPUT_ACCOUNT=$(printf '%s' "$INPUT_ENTRY" | cut -d: -f1)
  case "$INPUT_ACCOUNT" in ''|*[!a-zA-Z0-9_-]*) echo 'Unsupported invoking account.' >&2; exit 2;; esac
fi
read_user_input() {
  INPUT_PATH=$(assessment_absolute_input "$2")
  if test -n "$INPUT_ACCOUNT"; then
    READ_RESULT=$(ASSESSMENT_INPUT_HELPER="$SOURCE/bootstrap-config.sh" ASSESSMENT_INPUT_MODE="$1" ASSESSMENT_INPUT_PATH="$INPUT_PATH" \
      /bin/su -s /bin/sh "$INPUT_ACCOUNT" -c 'exec /bin/sh "$ASSESSMENT_INPUT_HELPER" "$ASSESSMENT_INPUT_MODE" "$ASSESSMENT_INPUT_PATH"') || return 1
  else
    READ_RESULT=$(/bin/sh "$SOURCE/bootstrap-config.sh" "$1" "$INPUT_PATH") || return 1
  fi
}
case "$SERVER$JAVA_PATH$READ_GROUP" in *[!a-zA-Z0-9_./:-]*) echo 'Unsupported characters in installation options.' >&2; exit 2;; esac
for DIR in /opt "$DEST"; do
  test ! -L "$DIR" || { echo 'Managed directory cannot be a symlink.' >&2; exit 1; }
  if test -d "$DIR"; then test "$(stat -c %u "$DIR")" = 0 || { echo 'Managed directory must be root-owned.' >&2; exit 1; }; fi
done
mkdir -p "$DEST"
chmod 755 "$DEST"
# Serialize privileged preparation before touching any shared runtime artifacts.
# The parent is root-owned, so the service user cannot replace this lock or its PID.
BOOTSTRAP_LOCK=$DEST/.bootstrap-lock
BOOTSTRAP_LOCK_HELD=false
TEMP_CODE=''
ATOMIC_TMP=''
ECHO_SAVED=''
cleanup() {
  RESULT=$?
  if test -n "$ECHO_SAVED"; then stty "$ECHO_SAVED" < /dev/tty 2>/dev/null || true; fi
  test -z "$TEMP_CODE" || rm -f "$TEMP_CODE"
  test -z "$ATOMIC_TMP" || rm -f "$ATOMIC_TMP"
  if $BOOTSTRAP_LOCK_HELD; then rm -f "$BOOTSTRAP_LOCK/pid"; rmdir "$BOOTSTRAP_LOCK" 2>/dev/null || true; fi
  trap - 0
  exit "$RESULT"
}
trap cleanup 0
trap 'exit 130' HUP INT TERM
if ! mkdir "$BOOTSTRAP_LOCK" 2>/dev/null; then
  test -d "$BOOTSTRAP_LOCK" && test ! -L "$BOOTSTRAP_LOCK" && test "$(stat -c %u "$BOOTSTRAP_LOCK")" = 0 || { echo 'Invalid administrative installation lock.' >&2; exit 1; }
  if test -f "$BOOTSTRAP_LOCK/pid" && test ! -L "$BOOTSTRAP_LOCK/pid"; then
    read -r OLD_PID < "$BOOTSTRAP_LOCK/pid" || OLD_PID=''
    case "$OLD_PID" in ''|*[!0-9]*) echo 'Invalid administrative lock PID; inspect .bootstrap-lock.' >&2; exit 1;; esac
    if kill -0 "$OLD_PID" 2>/dev/null; then echo 'Another administrative installation is active.' >&2; exit 1; fi
    rm -f "$BOOTSTRAP_LOCK/pid"
    rmdir "$BOOTSTRAP_LOCK" || exit 1
  elif test -n "$(find "$BOOTSTRAP_LOCK" -prune -mmin +2 2>/dev/null)"; then
    rmdir "$BOOTSTRAP_LOCK" || exit 1
  else
    echo 'Administrative preparation is active; retry in two minutes after an interruption.' >&2; exit 1
  fi
  mkdir "$BOOTSTRAP_LOCK" || exit 1
fi
BOOTSTRAP_LOCK_HELD=true
printf '%s\n' "$$" > "$BOOTSTRAP_LOCK/pid"
atomic_copy() {
  test -f "$1" || { echo 'The installer package is incomplete.' >&2; return 1; }
  if test -e "$2"; then test -f "$2" && test ! -L "$2" || { echo 'Artifact destination is not a regular file.' >&2; return 1; }; fi
  # A new inode lets a JVM already running keep reading its complete previous JAR.
  ATOMIC_TMP=$(mktemp "$DEST/.artifact.XXXXXX")
  cp "$1" "$ATOMIC_TMP"
  chown root:root "$ATOMIC_TMP"
  chmod "$3" "$ATOMIC_TMP"
  mv -f "$ATOMIC_TMP" "$2"
  ATOMIC_TMP=''
}
for NAME in lib trust conf logs collector-spool bootstrap.log install.sh system-install.sh collector.jar run-once.sh agent-tool.sh launcher-common.sh bootstrap-config.sh poll.sh; do
  test ! -L "$DEST/$NAME" || { echo 'Refusing symlink in managed package.' >&2; exit 1; }
done
printf '%s [INFO] Administrative preparation started\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee -a "$DEST/bootstrap.log"
if ! id "$ACCOUNT" >/dev/null 2>&1; then
  /usr/sbin/useradd -r -M -d "$DEST" -s /sbin/nologin "$ACCOUNT"
else
  test "$(id -u "$ACCOUNT")" != 0 || exit 1
  ENTRY=$(getent passwd "$ACCOUNT")
  test "$(printf '%s' "$ENTRY" | cut -d: -f6)" = "$DEST" || { echo 'Existing service account has another home.' >&2; exit 1; }
  case "$(printf '%s' "$ENTRY" | cut -d: -f7)" in /sbin/nologin|/usr/sbin/nologin|/bin/false) ;; *) echo 'Existing account permits interactive login; refusing to change it.' >&2; exit 1;; esac
fi
if test -n "$READ_GROUP"; then
  ENTRY=$(getent group "$READ_GROUP") || { echo 'Read-access group does not exist.' >&2; exit 1; }
  test "$(printf '%s' "$ENTRY" | cut -d: -f3)" != 0 || { echo 'Cannot grant group zero.' >&2; exit 1; }
  /usr/sbin/usermod -a -G "$READ_GROUP" "$ACCOUNT"
fi
mkdir -p "$DEST/lib" "$DEST/trust" "$DEST/conf" "$DEST/logs" "$DEST/collector-spool"
for NAME in lib trust; do
  test "$(stat -c %u "$DEST/$NAME")" = 0 || { echo 'Package directory must be root-owned.' >&2; exit 1; }
  chmod 755 "$DEST/$NAME"
done
for NAME in install.sh system-install.sh run-once.sh agent-tool.sh launcher-common.sh bootstrap-config.sh poll.sh collector.jar; do
  if test "$SOURCE" != "$DEST"; then
    MODE=755; test "$NAME" != collector.jar || MODE=644
    atomic_copy "$SOURCE/$NAME" "$DEST/$NAME" "$MODE"
  fi
  chown root:root "$DEST/$NAME"
done
for FILE in "$SOURCE"/lib/*.jar; do
  test -f "$FILE" || { echo 'Missing TLS libraries.' >&2; exit 1; }
  NAME=$(basename "$FILE")
  test ! -L "$DEST/lib/$NAME" || exit 1
  if test "$SOURCE" != "$DEST"; then atomic_copy "$FILE" "$DEST/lib/$NAME" 644; fi
  chown root:root "$DEST/lib/$NAME"
done
test ! -L "$DEST/trust/ca.pem" || exit 1
if test "$SOURCE" != "$DEST"; then atomic_copy "$SOURCE/conf/ca.pem" "$DEST/trust/ca.pem" 644; fi
chown root:root "$DEST/trust/ca.pem"
chmod 644 "$DEST/collector.jar" "$DEST"/lib/*.jar "$DEST/trust/ca.pem"
chmod 755 "$DEST"/*.sh
chown "$ACCOUNT" "$DEST/conf" "$DEST/logs" "$DEST/collector-spool"
chmod 700 "$DEST/conf" "$DEST/logs" "$DEST/collector-spool"
# Resolve packaged credentials only for a new identity. Existing operational
# credentials and environment membership are never replaced by a new ZIP.
if test ! -f "$DEST/conf/collector.properties"; then
  if test -e "$SOURCE/conf/bootstrap.properties" || test -L "$SOURCE/conf/bootstrap.properties"; then
    read_user_input read-bootstrap "$SOURCE/conf/bootstrap.properties" || { echo 'Configured package registration data is invalid. Download the package again.' >&2; exit 1; }
    assessment_parse_bootstrap <<ASSESSMENT_BOOTSTRAP_DATA
$READ_RESULT
ASSESSMENT_BOOTSTRAP_DATA
    unset READ_RESULT
    CONFIGURED_PACKAGE=true
    test -n "$SERVER" || SERVER=$BOOTSTRAP_SERVER
  fi
  test -n "$SERVER" || { echo 'A dashboard URL is required for a new registration.' >&2; exit 1; }
  assessment_validate_server "$SERVER" || exit 1
  SERVER=$ASSESSMENT_SERVER_VALUE
  if test -n "$CODE_FILE"; then
    read_user_input read-code "$CODE_FILE" || exit 1
    CODE=$READ_RESULT
    unset READ_RESULT
  elif $CONFIGURED_PACKAGE; then
    test "$SERVER" = "$BOOTSTRAP_SERVER" || { echo 'The packaged authorization belongs to another dashboard. Use its original URL or provide an explicit code file.' >&2; exit 1; }
    CODE=$BOOTSTRAP_TOKEN
  else
    printf 'Enrollment code: ' > /dev/tty
    ECHO_SAVED=$(stty -g < /dev/tty); stty -echo < /dev/tty
    IFS= read -r CODE < /dev/tty || CODE=''
    stty "$ECHO_SAVED" < /dev/tty; ECHO_SAVED=''
    printf '\n' > /dev/tty
  fi
  assessment_validate_code "$CODE" || exit 1
  unset BOOTSTRAP_TOKEN
  TEMP_CODE=$(mktemp "$DEST/.enrollment.XXXXXX")
  printf '%s\n' "$CODE" > "$TEMP_CODE"; unset CODE
  chmod 400 "$TEMP_CODE"; chown "$ACCOUNT" "$TEMP_CODE"
fi
COMMAND="cd '$DEST' && /bin/sh '$DEST/install.sh' --managed"
test -z "$SERVER" || COMMAND="$COMMAND --server '$SERVER'"
test -z "$JAVA_PATH" || COMMAND="$COMMAND --java '$JAVA_PATH'"
test -z "$TEMP_CODE" || COMMAND="$COMMAND --code-file '$TEMP_CODE'"
$CONFIGURED_PACKAGE && COMMAND="$COMMAND --configured-origin"
$NO_CRON && COMMAND="$COMMAND --no-cron"
printf '%s [OK] Package prepared; switching to service account\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee -a "$DEST/bootstrap.log"
# stdout shows progress directly. Do not tee privileged output into service-owned logs.
/bin/su -s /bin/sh "$ACCOUNT" -c "$COMMAND"
