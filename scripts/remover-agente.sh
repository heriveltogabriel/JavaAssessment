#!/bin/sh
# Local administrative orchestration only. No remote paths/commands from dashboard.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
LC_ALL=C; export LC_ALL
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS ENV BASH_ENV CLASSPATH
umask 077
ROOT=/opt/java-assessment-collector
UPDATE=/opt/java-assessment-update
HELPER=/opt/java-assessment-uninstall
SYSTEMD=/etc/systemd/system
INIT=
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
systemd() { command -v systemctl >/dev/null 2>&1 && test -d /run/systemd/system; }
units() {
  printf '%s\n' java-assessment-collector.timer java-assessment-collector.service java-assessment-uninstall.service
  if present "$ROOT/current.json"; then printf '%s\n' java-assessment-recovery.service
  else printf '%s\n' java-assessment-update.service; fi
}
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

# Only known lock shapes are moved, never arbitrary contents or live owners.
check_poll_lock() {
  lock_path=$1
  check_tree "$lock_path"
  for member in "$lock_path"/* "$lock_path"/.[!.]* "$lock_path"/..?*; do
    present "$member" || continue
    test "$member" = "$lock_path/pid" && test -f "$member" || fail UNKNOWN_LOCK_MEMBER
  done
  lock_pid=$(cat "$lock_path/pid" 2>/dev/null || true)
  case "$lock_pid" in
    '') ;;
    *[!0-9]*) fail INVALID_LOCK_PID;;
    *) if kill -0 "$lock_pid" 2>/dev/null; then fail LOCK_OWNER_ALIVE; fi;;
  esac
}
preserve_locks() {
  for lock_path in "$ROOT/collector-spool/.poll-lock" "$ROOT"/collector-spool/.poll-lock-preservado-*; do
    present "$lock_path" || continue
    check_poll_lock "$lock_path"
    mv "$lock_path" "$WORK/$(basename "$lock_path")" || fail LOCK_PRESERVATION_FAILED
  done
}
usage() {
  cat <<'EOF'
Uso: sudo sh remover-agente.sh --check|--execute
--check    Verifica caminhos, servicos e operacoes pendentes (padrao).
--execute  Para os servicos e remove o agente com o desinstalador oficial.
Remove configuracao, identidade/token, coletas locais e executores do agente.
Preserva Java do sistema, aplicacoes e dashboard. Nao exclui o registro na console.
Instalacao sem desinstalador, arquivos desconhecidos ou transacao pendente: recusa.
EOF
}
# MAIN: tests exercise functions above in isolated fixtures, never host /opt.
case "${1:---check}" in
  --help|-h) usage; exit 0;;
  --check|--execute) MODE=${1:---check};;
  *) usage >&2; exit 2;;
esac
test "$#" -le 1 || { usage >&2; exit 2; }
test "$(id -u)" = 0 && test "$(uname -s)" = Linux || fail ROOT_LINUX_REQUIRED
INIT=$(readlink -f /etc/init.d)
case "$INIT" in /etc/init.d|/etc/rc.d/init.d) ;; *) fail INIT_DIRECTORY;; esac
# Run the downloaded script outside the agent directories.
cd / || fail WORKING_DIRECTORY
for dir in "$ROOT" "$HELPER" "$UPDATE"; do
  if present "$dir"; then check_tree "$dir"; fi
done
for file in uninstall-local.sh lifecycle-lock.sh root-trust.sh; do
  private_file "$HELPER/$file" || fail MISSING_OR_UNTRUSTED_UNINSTALLER
done
no_pending
verify_units
/bin/sh "$HELPER/root-trust.sh" package "$HELPER" || fail UNINSTALL_TRUST
printf '%s\n' "Agente: $ROOT" "Executores: $UPDATE e $HELPER"
if test "$MODE" = --check; then
  echo 'JA_CHECK=PASSED (previa; inventario completo sera validado pelo desinstalador)'
  echo 'Para remover: sudo sh remover-agente.sh --execute'
  exit 0
fi
# Share the Ansible orchestration lock; official uninstall acquires fd 9 itself.
trusted /opt || fail OPT_TRUST
lock=/opt/java-assessment-ansible.lock
if present "$lock"; then
  test -f "$lock" && trusted "$lock" && test "$(stat -c %h "$lock")" = 1 && test "$(stat -c %a "$lock")" = 600 || fail AUTOMATION_LOCK
fi
exec 8>> "$lock"
flock -n 8 || fail AUTOMATION_BUSY
WORK=$(mktemp -d /var/tmp/java-assessment-removal.XXXXXX)
echo "JA_DIAGNOSTIC=$WORK"
# Keep failure output private; services remain stopped on a failed removal.
trap 'rc=$?; if test "$rc" != 0; then echo "Remocao incompleta. Confira $WORK/operation.log; servicos podem estar parados." >&2; fi' 0
(
  . "$HELPER/lifecycle-lock.sh"
  no_pending
  stop_services
  preserve_locks
) >> "$WORK/operation.log" 2>&1 || fail PREPARATION_FAILED
/bin/sh "$HELPER/uninstall-local.sh" remove 8>&- >> "$WORK/operation.log" 2>&1 || fail UNINSTALL_REFUSED
/bin/sh "$HELPER/uninstall-local.sh" cleanup 8>&- >> "$WORK/operation.log" 2>&1 || fail UNINSTALL_CLEANUP
for dir in "$ROOT" "$UPDATE" "$HELPER"; do present "$dir" && fail UNINSTALL_INCOMPLETE; done
no_processes
echo 'JA_REMOVAL=COMPLETE'
echo 'Java e aplicacoes preservados. O registro da maquina permanece no dashboard.'
echo "Diagnostico privado e travas preservadas: $WORK"
echo 'Arquivos de coordenacao /opt/java-assessment-*.lock permanecem para evitar corridas.'
