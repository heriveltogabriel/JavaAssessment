#!/bin/bash
# Reinstalação limpa do Java Assessment: remover o dashboard instalado (ex.: 2.0.53) com backup completo,
# permitir uma instalação limpa da 2.1.8 e, se quiser, trazer de volta os dados e a CA antigos
# ou desfazer tudo. Execute no servidor do dashboard, como root.
#
#   sudo bash reinstalar-dashboard.sh inspecionar             # só lê; não altera nada
#   sudo bash reinstalar-dashboard.sh remover                 # backup completo + remoção (pede confirmação)
#   sudo bash reinstalar-dashboard.sh restaurar-dados <backup> # após instalar a 2.1.8: volta banco, config e CA
#   sudo bash reinstalar-dashboard.sh voltar-original <backup> # desfaz tudo: reinstala exatamente o que foi removido
#
# Não altera agentes nas máquinas. PREFIX existe só para testes em diretório isolado.
set -euo pipefail
umask 077

PREFIX="${PREFIX:-}"
DEST="$PREFIX/opt/java-assessment-dashboard"
UNIT="$PREFIX/etc/systemd/system/java-assessment-dashboard.service"
DROPIN="$PREFIX/etc/systemd/system/java-assessment-dashboard.service.d"
UPD_UNIT="$PREFIX/etc/systemd/system/java-assessment-dashboard-updater.service"
UPD_STATE="$PREFIX/var/lib/java-assessment-dashboard-updater"
ADMIN_REC="$PREFIX/root/java-assessment-dashboard-admin.txt"
CA_REC="$PREFIX/root/java-assessment-dashboard-ca.key"
ACCOUNT=java-assessment-dashboard
BACKUP_ROOT="$PREFIX/root"
ITEMS=("$DEST" "$UNIT" "$DROPIN" "$UPD_UNIT" "$UPD_STATE" "$ADMIN_REC" "$CA_REC")

fail() { echo "ERRO: $*" >&2; exit 1; }
say() { echo "== $*"; }
need_root() { [ -n "$PREFIX" ] || [ "$(id -u)" = 0 ] || fail "execute com sudo"; }
svc() { [ -n "$PREFIX" ] && { echo "  (teste) systemctl $*"; return 0; }; systemctl "$@"; }
exists() { [ -e "$1" ] || [ -L "$1" ]; }
prop() { sed -n "s/^$1=//p" "$2" 2>/dev/null | tail -1; }
health() {
  local port; port="$(prop httpsPort "$DEST/conf/server.properties")"; port="${port:-8445}"
  # -k só nesta checagem local em loopback; a confiança real dos agentes usa a CA instalada.
  curl -sk --max-time 5 "https://127.0.0.1:$port/health" || echo "(sem resposta em https://127.0.0.1:$port/health)"
}

inspecionar() {
  say "Instalação atual"
  for p in "${ITEMS[@]}"; do
    if exists "$p"; then printf '  existe   %s (%s)\n' "$p" "$(du -sh "$p" 2>/dev/null | cut -f1)"; else printf '  ausente  %s\n' "$p"; fi
  done
  if id "$ACCOUNT" >/dev/null 2>&1; then echo "  conta    $(id "$ACCOUNT")"; else echo "  conta    $ACCOUNT ausente"; fi
  [ -f "$DEST/VERSION" ] && echo "  versão   $(cat "$DEST/VERSION")"
  if [ -f "$DEST/conf/server.properties" ]; then
    echo "  URL      $(prop publicUrl "$DEST/conf/server.properties")"
    echo "  porta    $(prop httpsPort "$DEST/conf/server.properties")"
  fi
  for f in conf/tls/fullchain.pem conf/tls/privkey.pem conf/agent-ca.pem; do
    exists "$DEST/$f" && echo "  TLS      $f presente" || echo "  TLS      $f AUSENTE"
  done
  [ -d "$DEST/data" ] && { echo "  dados:"; ls -la "$DEST/data" | sed 's/^/    /'; }
  if [ -z "$PREFIX" ]; then
    say "Serviços"
    systemctl is-active java-assessment-dashboard 2>/dev/null | sed 's/^/  dashboard: /' || true
    systemctl is-active java-assessment-dashboard-updater 2>/dev/null | sed 's/^/  atualizador: /' || true
    say "Saúde"; health; echo
  fi
  say "Espaço livre para o backup"; df -h "$BACKUP_ROOT" | tail -1 | sed 's/^/  /'
}

remover() {
  need_root
  exists "$DEST" || exists "$UNIT" || fail "não há dashboard instalado em $DEST"
  inspecionar
  echo
  echo "Isto vai PARAR o dashboard, fazer backup completo e REMOVER a instalação acima."
  echo "Os agentes param de se comunicar até o dashboard voltar."
  if [ "${CONFIRMAR:-}" != "REMOVER" ]; then
    read -r -p "Digite REMOVER para continuar: " answer
    [ "$answer" = "REMOVER" ] || fail "cancelado; nada foi alterado"
  fi
  local stamp backup; stamp="$(date +%Y%m%d-%H%M%S)"; backup="$BACKUP_ROOT/java-assessment-backup-$stamp"
  mkdir -m 700 "$backup"
  say "Parando serviços"
  svc stop java-assessment-dashboard-updater.service 2>/dev/null || true
  svc stop java-assessment-dashboard.service 2>/dev/null || true
  say "Backup em $backup"
  local present=()
  for p in "${ITEMS[@]}"; do exists "$p" && present+=("${p#/}"); done
  tar -C / -cpzf "$backup/instalacao.tar.gz" --numeric-owner "${present[@]}"
  if id "$ACCOUNT" >/dev/null 2>&1; then echo "$(id -u "$ACCOUNT") $(id -g "$ACCOUNT")" > "$backup/conta-uid-gid"; fi
  # Cópias avulsas para reinstalar com o mesmo certificado/CA (opções --tls-cert/--tls-key/--agent-ca).
  mkdir -m 700 "$backup/tls"
  for f in conf/tls/fullchain.pem conf/tls/privkey.pem conf/agent-ca.pem conf/server.properties; do
    exists "$DEST/$f" && cp -p "$DEST/$f" "$backup/tls/$(basename "$f")"
  done
  [ -f "$DEST/VERSION" ] && cp "$DEST/VERSION" "$backup/VERSION"
  tar -tzf "$backup/instalacao.tar.gz" > "$backup/conteudo.txt"
  (cd "$backup" && sha256sum instalacao.tar.gz > instalacao.tar.gz.sha256)
  tar -tzf "$backup/instalacao.tar.gz" >/dev/null || fail "backup ilegível; nada foi removido"
  say "Removendo"
  svc disable java-assessment-dashboard.service java-assessment-dashboard-updater.service 2>/dev/null || true
  for p in "${ITEMS[@]}"; do exists "$p" && rm -rf -- "$p" && echo "  removido $p"; done
  if [ -z "$PREFIX" ] && id "$ACCOUNT" >/dev/null 2>&1; then userdel "$ACCOUNT" && echo "  removida conta $ACCOUNT"; fi
  svc daemon-reload
  cat <<EOF

Removido. Backup: $backup  (guarde-o; contém a chave do certificado)

Próximo passo — instalação limpa da 2.1.8 (escolha):
 a) Mesmo endereço e mesmo certificado, para os agentes continuarem confiando no servidor:
    sudo ./install.sh --public-url <URL> \\
      --tls-cert $backup/tls/fullchain.pem --tls-key $backup/tls/privkey.pem --agent-ca $backup/tls/agent-ca.pem
    Depois, opcional: sudo bash $0 restaurar-dados $backup   (volta usuários, ambientes, inventário e cadastro dos agentes)
 b) Totalmente nova: sudo ./install.sh  (nova CA e novo banco; reinstale os agentes com o pacote do novo dashboard)

Para desfazer tudo e voltar à versão anterior: sudo bash $0 voltar-original $backup
EOF
}

restaurar_dados() {
  need_root
  local backup="${1:?informe a pasta do backup}"
  [ -f "$backup/instalacao.tar.gz" ] || fail "backup não encontrado em $backup"
  (cd "$backup" && sha256sum -c instalacao.tar.gz.sha256 >/dev/null) || fail "backup alterado (sha256)"
  [ -d "$DEST" ] || fail "instale a 2.1.8 primeiro"
  local stage; stage="$(mktemp -d "$BACKUP_ROOT/.restaurar-XXXXXX")"
  tar -C "$stage" -xpzf "$backup/instalacao.tar.gz" --numeric-owner "${DEST#/}/data" "${DEST#/}/conf"
  local old="$stage/${DEST#/}"
  [ -d "$old/data" ] && [ -d "$old/conf" ] || fail "backup sem data/ e conf/"
  say "Parando o dashboard novo"
  svc stop java-assessment-dashboard.service
  local keep; keep="$BACKUP_ROOT/java-assessment-instalacao-limpa-$(date +%Y%m%d-%H%M%S)"
  mkdir -m 700 "$keep" && cp -rp "$DEST/data" "$DEST/conf" "$keep/"
  echo "  dados e config da instalação limpa guardados em $keep"
  rm -rf -- "$DEST/data" "$DEST/conf"
  cp -rp "$old/data" "$old/conf" "$DEST/"
  rm -rf -- "$stage"
  if [ -z "$PREFIX" ]; then
    chown -R "$ACCOUNT:$ACCOUNT" "$DEST/data"; find "$DEST/data" -type d -exec chmod 700 {} +; find "$DEST/data" -type f -exec chmod 600 {} +
    chown -R "root:$ACCOUNT" "$DEST/conf"; find "$DEST/conf" -type d -exec chmod 750 {} +; find "$DEST/conf" -type f -exec chmod 640 {} +
  fi
  say "Iniciando com os dados anteriores"
  svc start java-assessment-dashboard.service
  [ -n "$PREFIX" ] || { sleep 15; health; echo; }
  echo "Confira login, máquinas e versão. Para voltar à instalação limpa: pare o serviço e copie de volta $keep/data e $keep/conf."
}

voltar_original() {
  need_root
  local backup="${1:?informe a pasta do backup}"
  [ -f "$backup/instalacao.tar.gz" ] || fail "backup não encontrado em $backup"
  (cd "$backup" && sha256sum -c instalacao.tar.gz.sha256 >/dev/null) || fail "backup alterado (sha256)"
  if exists "$DEST" || exists "$UNIT"; then
    echo "Há uma instalação atual; ela será removida (com backup próprio) antes de restaurar a original."
    CONFIRMAR="${CONFIRMAR:-}" remover
  fi
  if [ -z "$PREFIX" ] && [ -f "$backup/conta-uid-gid" ] && ! id "$ACCOUNT" >/dev/null 2>&1; then
    read -r uid gid < "$backup/conta-uid-gid"
    getent group "$ACCOUNT" >/dev/null || groupadd --system -g "$gid" "$ACCOUNT"
    useradd --system -u "$uid" -g "$gid" --home-dir /opt/java-assessment-dashboard --shell /sbin/nologin "$ACCOUNT"
  fi
  say "Restaurando a instalação original"
  tar -C / -xpzf "$backup/instalacao.tar.gz" --numeric-owner
  svc daemon-reload
  svc enable --now java-assessment-dashboard.service
  [ -f "$UPD_UNIT" ] && svc enable --now java-assessment-dashboard-updater.service || true
  [ -n "$PREFIX" ] || { sleep 15; health; echo; }
  echo "Versão original restaurada a partir de $backup."
}

case "${1:-inspecionar}" in
  inspecionar) inspecionar ;;
  remover) remover ;;
  restaurar-dados) restaurar_dados "${2:-}" ;;
  voltar-original) voltar_original "${2:-}" ;;
  *) fail "uso: $0 inspecionar|remover|restaurar-dados <backup>|voltar-original <backup>" ;;
esac
