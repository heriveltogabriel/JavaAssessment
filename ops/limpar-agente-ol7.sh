#!/bin/sh
# Operator-run cleanup of the ol07 test installation only.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS ENV BASH_ENV
fail() { printf '%s\n' "LIMPEZA_INTERROMPIDA: $1" >&2; exit 1; }
test "$(id -u)" = 0 || fail 'Execute com sudo.'
test "$(uname -s)" = Linux && test "$(hostname -s)" = ol07 || fail 'Este script foi preparado somente para a VM ol07.'
test "$#" = 0 || fail 'Este script nao recebe argumentos.'
ROOT=/opt/java-assessment-collector
UPDATE=/opt/java-assessment-update
HELPER=/opt/java-assessment-uninstall
ACCOUNT_HOME=/home/opc
# Require the V3 installation observed in this incident. No arbitrary rm of /opt.
for path in "$ROOT" "$UPDATE" "$HELPER"; do
  test -d "$path" && test ! -L "$path" || fail 'Instalacao esperada ausente ou vinculada.'
done
test -f "$HELPER/v3-inventory.json" || fail 'Inventario de propriedade V3 ausente.'
for path in "$UPDATE/state/transaction.json" "$UPDATE/journal.json" "$ROOT/collector-spool/update-request.json" "$ROOT/collector-spool/update-v3-request.json" "$ROOT/collector-spool/uninstall-request.json" "$HELPER/request.json" "$HELPER/claim.json"; do
  test ! -e "$path" && test ! -L "$path" || fail 'Ha uma operacao pendente. Aguarde sua conclusao.'
done
/bin/sh "$HELPER/root-trust.sh" package "$HELPER"
# Stop the idle uninstall executor before calling its own verified operations.
systemctl stop java-assessment-uninstall.service
state=$(systemctl show --all java-assessment-uninstall.service -p LoadState -p ActiveState -p MainPID -p ControlPID -p Job)
printf '%s\n' "$state" | awk -F= '
  $1=="LoadState" {loaded=($2=="loaded"); l++}
  $1=="ActiveState" {stopped=($2=="inactive"||$2=="failed"); a++}
  $1=="MainPID" {main=($2=="0"); m++}
  $1=="ControlPID" {control=($2=="0"); c++}
  $1=="Job" {job=($2==""||$2=="0"); j++}
  END {exit !(l==1&&a==1&&m==1&&c==1&&j==1&&loaded&&stopped&&main&&control&&job)}' || fail 'Parada do desinstalador nao confirmada.'
# Older signed uninstall helpers omit two files created by AgentTool itself.
# Preserve exactly these operational files outside the installation; never
# change signed code/receipts or delete an unrecognized inventory member.
/bin/sh "$HELPER/root-trust.sh" package "$ROOT"
(
  . "$ROOT/lifecycle-lock.sh"
  backup=
  for relative in conf/installation-id.lock logs/permission-check.json; do
    file=$ROOT/$relative
    test -e "$file" || continue
    test -f "$file" && test ! -L "$file" && test "$(stat -c %u "$file")" = 0 && test "$(stat -c %h "$file")" = 1 || fail 'Arquivo operacional inesperado.'
    test "$relative" != conf/installation-id.lock || test ! -s "$file" || fail 'Lock de cadastro nao esta vazio.'
    if test -z "$backup"; then
      backup=$(mktemp -d /var/tmp/java-assessment-cleanup.XXXXXX)
      chmod 700 "$backup"
      printf 'ARQUIVOS_OPERACIONAIS_PRESERVADOS=%s\n' "$backup"
    fi
    mv -- "$file" "$backup/$(basename "$file")"
  done
)
# Exact completed local-bootstrap residue diagnosed on this ol07 installation.
# Keep it intact outside the product before invoking the old signed helper.
LEGACY_OP=f09e66e6-2eae-4bcc-bc5b-30787b8c0a2f
record=$UPDATE/state/cleanup/$LEGACY_OP.json
tx=$UPDATE/transactions/$LEGACY_OP
if test -f "$record"; then
  /bin/sh "$HELPER/root-trust.sh" package "$UPDATE"
  test "$(sha256sum "$record" | awk '{print $1}')" = edf18b8acdda12075c11ca1b80b8f6d56c4e31589455076fc7078f95811719c5 || fail 'Fila de limpeza diferente da diagnosticada.'
  test -d "$tx" && test ! -L "$tx" || fail 'Transacao concluida inesperada.'
  test "$(find "$tx" -mindepth 1 -maxdepth 1 | wc -l)" -eq 1 || fail 'Arquivos adicionais na transacao; preservados.'
  cmp -s "$tx/uninstall-owner.json" "$HELPER/v3-inventory.json" || fail 'Recibo diferente do inventario verificado.'
  cmp -s "$UPDATE/update-public.der" "$ROOT/update-public.der" && cmp -s "$UPDATE/update-public.der" "$HELPER/v3-public.der" || fail 'Chave publica divergente.'
  systemctl stop java-assessment-recovery.service
  recovery=$(systemctl show --all java-assessment-recovery.service -p MainPID -p ControlPID -p ActiveState -p Job)
  printf '%s\n' "$recovery" | awk -F= '
    $1=="MainPID" {m=($2=="0");mc++} $1=="ControlPID" {c=($2=="0");cc++}
    $1=="ActiveState" {a=($2=="inactive"||$2=="failed");ac++}
    $1=="Job" {j=($2==""||$2=="0");jc++}
    END {exit !(m&&c&&a&&j&&mc==1&&cc==1&&ac==1&&jc==1)}' || fail 'Parada do recuperador nao confirmada.'
  (
    . "$ROOT/lifecycle-lock.sh"
    test ! -e "$UPDATE/state/transaction.json" || fail 'Nova operacao ativa.'
    backup=$(mktemp -d /var/tmp/java-assessment-cleanup.XXXXXX)
    chmod 700 "$backup"
    printf 'RESIDUOS_CONCLUIDOS_PRESERVADOS=%s\n' "$backup"
    mv -- "$tx" "$backup/transaction"
    mv -- "$record" "$backup/cleanup-record.json"
    mv -- "$UPDATE/update-public.der" "$backup/update-public.der"
  )
fi
/bin/sh "$HELPER/uninstall-local.sh" remove
/bin/sh "$HELPER/uninstall-local.sh" cleanup
for path in "$ROOT" "$UPDATE" "$HELPER"; do
  test ! -e "$path" && test ! -L "$path" || fail 'Remocao local incompleta.'
done
# Remove only recognized staging directories created by this downloader.
# Reject links, nested mounts and unexpected top-level entries.
for stage in /tmp/java-assessment-install.??????; do
  test -e "$stage" || continue
  test -d "$stage" && test ! -L "$stage" || fail 'Staging inesperado.'
  test -f "$stage/agent.zip" && test -f "$stage/package/collector.jar" && test -f "$stage/package/SHA256SUMS" || fail 'Staging nao reconhecido; preservado.'
  test -z "$(find "$stage" -xdev -type l -print -quit)" || fail 'Link no staging; preservado.'
  awk -v prefix="$stage/" -v exact="$stage" '$2==exact||index($2,prefix)==1 {bad=1} END {exit bad?1:0}' /proc/mounts || fail 'Montagem no staging; preservada.'
  for entry in "$stage"/* "$stage"/.[!.]* "$stage"/..?*; do
    test -e "$entry" || continue
    case "$entry" in "$stage/agent.zip"|"$stage/dashboard-ca.pem"|"$stage/download.conf"|"$stage/package") ;; *) fail 'Arquivo desconhecido no staging; preservado.';; esac
  done
  rm -rf --one-file-system -- "$stage"
done
# install.sh is the downloaded wrapper from this incident, not an arbitrary installer.
if test -e "$ACCOUNT_HOME/install.sh" || test -L "$ACCOUNT_HOME/install.sh"; then
  test -f "$ACCOUNT_HOME/install.sh" && test ! -L "$ACCOUNT_HOME/install.sh" || fail 'Script no home inesperado.'
  grep -F '/tmp/java-assessment-install.' "$ACCOUNT_HOME/install.sh" >/dev/null && grep -F '/api/install/package' "$ACCOUNT_HOME/install.sh" >/dev/null || fail 'install.sh nao corresponde ao download; preservado.'
  rm -f -- "$ACCOUNT_HOME/install.sh"
fi
printf '%s\n' 'LIMPEZA_LOCAL_OK: agente, servicos, downloads reconhecidos e install.sh removidos.'
printf '%s\n' 'Java do sistema preservado. O registro antigo no dashboard deve ser removido pela interface.'
systemctl list-units --all --no-pager 'java-assessment-*'
