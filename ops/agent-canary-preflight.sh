#!/bin/sh
# Read-only Linux preflight. No installation, service restart or configuration writes.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
unset ENV BASH_ENV LD_PRELOAD LD_LIBRARY_PATH JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS
LC_ALL=C; export LC_ALL
ROOT=/opt/java-assessment-collector
HELPER=/opt/java-assessment-update
if test "$(uname -s)" != Linux; then echo 'ERROR=Execute este diagnostico na VM Linux.'; exit 2; fi
if test "$(id -u)" != 0; then echo 'ERROR=O diagnostico precisa de sudo para ler os arquivos protegidos.'; exit 2; fi
if ! command -v sha256sum >/dev/null 2>&1; then echo 'ERROR=sha256sum nao encontrado.'; exit 2; fi
printf '%s\n' 'JAVA_ASSESSMENT_PREFLIGHT=1' "UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)" "ARCH=$(uname -m)"
if test -r /etc/redhat-release; then printf 'OS='; tr '\n\r' '  ' < /etc/redhat-release; printf '\n'; fi
printf 'KERNEL=%s\n' "$(uname -r)"
if test ! -d "$ROOT" || test -L "$ROOT"; then echo 'ERROR=Diretorio protegido do coletor ausente ou simbolico.'; exit 3; fi
version() {
  label=$1; jar=$2
  if test -f "$jar" && test ! -L "$jar"; then
    if command -v unzip >/dev/null 2>&1; then
      v=$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | sed -n 's/^Implementation-Version: *//p' | tr -d '\r' | head -n 1)
      case "$v" in ''|*[!0-9A-Za-z.+_-]*) v=UNAVAILABLE;; esac
      printf '%s=%s\n' "$label" "$v"
    else printf '%s=UNZIP_UNAVAILABLE\n' "$label"; fi
  else printf '%s=ABSENT\n' "$label"; fi
}
hash() {
  label=$1; file=$2
  if test -f "$file" && test ! -L "$file"; then
    value=$(sha256sum "$file"); value=${value%% *}; printf '%s=%s\n' "$label" "$value"
  else printf '%s=ABSENT_OR_LINKED\n' "$label"; fi
}
# Only bounded marker values, never configuration contents or tokens.
marker() {
  label=$1; file=$2
  if test -f "$file" && test ! -L "$file" && test "$(wc -c < "$file")" -le 64; then
    value=$(tr -d '\r\n' < "$file")
    case "$value" in update-v1|update-v2|update-v3|systemd|sysv|system-root) printf '%s=%s\n' "$label" "$value";; *) printf '%s=UNRECOGNIZED\n' "$label";; esac
  else printf '%s=ABSENT_OR_INVALID\n' "$label"; fi
}
version COLLECTOR_JAR_VERSION "$ROOT/collector.jar"
version LEGACY_HELPER_VERSION "$HELPER/helper.jar"
hash COLLECTOR_SHA256 "$ROOT/collector.jar"
hash LEGACY_HELPER_SHA256 "$HELPER/helper.jar"
hash CONFIG_SHA256 "$ROOT/conf/collector.properties"
hash INSTALLATION_ID_SHA256 "$ROOT/conf/installation-id"
hash JAVA_PATH_SHA256 "$ROOT/conf/java.path"
marker ROOT_SERVICE "$ROOT/conf/root-service-mode"
marker CAPABILITY "$ROOT/conf/update-capability"
marker SCHEDULER "$HELPER/scheduler"
# V3 active code can differ from the retained JAR in the fixed root.
if test -f "$ROOT/current.json" && test ! -L "$ROOT/current.json"; then
  value=$(sed -n 's/.*"version":"\([0-9][0-9.]*\)".*/\1/p' "$ROOT/current.json" | head -n 1)
  printf 'ACTIVE_POINTER_VERSION=%s\n' "${value:-UNAVAILABLE}"
fi
# File metadata only: never execute a JVM just to identify its version.
if test -f "$ROOT/conf/java.path" && test ! -L "$ROOT/conf/java.path"; then
  IFS= read -r java_path < "$ROOT/conf/java.path" || true
  case "${java_path:-}" in
    /*/bin/java)
      java_home=${java_path%/bin/java}
      printf 'JAVA_PATH=%s\n' "$java_path"
      if test -f "$java_home/release"; then
        sed -n '/^JAVA_VERSION=/p; /^IMPLEMENTOR=/p; /^OS_ARCH=/p' "$java_home/release" | head -n 4
      else
        runtime_jar=$java_home/jre/lib/rt.jar
        test -f "$runtime_jar" || runtime_jar=$java_home/lib/rt.jar
        if test -f "$runtime_jar" && command -v unzip >/dev/null 2>&1; then
          unzip -p "$runtime_jar" META-INF/MANIFEST.MF 2>/dev/null | sed -n '/^Implementation-Version:/p; /^Implementation-Vendor:/p' | head -n 2
        fi
      fi;;
    *) echo 'JAVA_PATH=REQUIRES_REVIEW';;
  esac
fi
# Read only the CA path; refuse properties escaping/continuation rather than guessing.
ca_path=''
if test -f "$ROOT/conf/collector.properties" && test ! -L "$ROOT/conf/collector.properties"; then
  ca_path=$(sed -n 's/^[[:space:]]*tlsCaFile[[:space:]]*=[[:space:]]*//p' "$ROOT/conf/collector.properties" | tail -n 1 | tr -d '\r')
fi
case "$ca_path" in
  *'\'*|*'..'*|'') echo 'CONFIGURED_CA_SHA256=REQUIRES_REVIEW';;
  /*) hash CONFIGURED_CA_SHA256 "$ca_path";;
  *) echo 'CONFIGURED_CA_SHA256=REQUIRES_REVIEW';;
esac
hash UPDATE_PUBLIC_KEY_SHA256 "$ROOT/update-public.der"
echo 'SUPPORT_FILES_BEGIN'
for file in "$ROOT"/*.sh "$ROOT"/lib/*.jar "$ROOT"/update-public.der; do
  test -f "$file" && test ! -L "$file" || continue
  name=${file#"$ROOT"/}
  case "$name" in *[!A-Za-z0-9_./-]*|*'..'*) echo 'SUPPORT_FILE_REQUIRES_REVIEW'; continue;; esac
  sum=$(sha256sum "$file"); sum=${sum%% *}
  printf '%s  %s\n' "$sum" "$name"
done
echo 'SUPPORT_FILES_END'
echo 'PENDING_OPERATIONS_BEGIN'
for file in "$HELPER/journal.json" "$HELPER/state/transaction.json" "$ROOT/collector-spool/update-request.json" "$ROOT/collector-spool/update-v3-request.json" /opt/java-assessment-uninstall/request.json /opt/java-assessment-uninstall/claim.json "$ROOT/collector-spool/uninstall-request.json"; do
  if test -e "$file" || test -L "$file"; then printf 'PENDING=%s\n' "$file"; fi
done
echo 'PENDING_OPERATIONS_END'
echo 'DISK_BEGIN'
df -Pk "$ROOT" "$HELPER" 2>/dev/null || df -Pk /opt
echo 'DISK_END'
echo 'SERVICES_BEGIN'
if command -v systemctl >/dev/null 2>&1 && test -d /run/systemd/system; then
  for unit in java-assessment-collector.timer java-assessment-collector.service java-assessment-update.service java-assessment-recovery.service; do
    printf 'UNIT=%s\n' "$unit"
    systemctl show "$unit" -p LoadState -p ActiveState -p SubState -p MainPID 2>/dev/null || true
  done
else
  # Process evidence only. Calling an old service "status" may mutate PID files.
  for proc in /proc/[0-9]*/cmdline; do
    test -r "$proc" || continue
    kind=$(tr '\000' '\n' < "$proc" 2>/dev/null | awk '/^assessment\.(AssessmentCollector|AgentUpdateWorker|AgentMigrationBootstrap|UninstallWorker)$/ {print; exit}')
    test -n "$kind" || continue
    pid=${proc#/proc/}; pid=${pid%/cmdline}
    printf 'PID=%s MAIN_CLASS=%s\n' "$pid" "$kind"
    sed -n '/^State:/p; /^VmRSS:/p' "/proc/$pid/status" 2>/dev/null || true
  done
  for file in "$ROOT/run/supervisor.pid" "$HELPER/supervisor.pid" "$HELPER/state/recovery.pid"; do
    if test -f "$file" && test ! -L "$file" && test "$(wc -c < "$file")" -le 32; then
      pid=$(tr -d '\r\n' < "$file")
      case "$pid" in ''|*[!0-9]*) continue;; esac
      state=NOT_RUNNING; kill -0 "$pid" 2>/dev/null && state=PID_PRESENT
      printf 'SUPERVISOR=%s STATE=%s\n' "$file" "$state"
    fi
  done
fi
echo 'SERVICES_END'
echo 'PREFLIGHT_COMPLETE=READ_ONLY'
