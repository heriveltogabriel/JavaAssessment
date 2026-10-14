#!/bin/sh
# Called only by administrative root-system-install, after scheduler installation.
set -eu
umask 077
PATH=/usr/sbin:/usr/bin:/sbin:/bin;export PATH
ROOT=/opt/java-assessment-collector
HELPER=/opt/java-assessment-uninstall
SYSTEMD=/etc/systemd/system
INIT=$(readlink -f /etc/init.d)
test "$(id -u)" = 0 || exit 2
case "${1:-}" in systemd|sysv) mode=$1;; *) exit 2;; esac
test "$#" = 1 || exit 2
for file in "$HELPER/request.json" "$HELPER/claim.json" "$ROOT/collector-spool/uninstall-request.json";do test ! -e "$file" && test ! -L "$file" || { echo 'Uninstall is pending; refuse administrative overwrite.' >&2;exit 1; };done
if test -d "$HELPER";then
  /bin/sh "$ROOT/root-trust.sh" package "$HELPER"
  if test "$mode" = systemd;then systemctl stop java-assessment-uninstall.service;else /bin/sh "$HELPER/uninstall-service.sh" stop;fi
fi
# The existing root trust helper validates all parents, including /opt.
test ! -L "$HELPER" || exit 1
mkdir -p "$HELPER/lib"
chmod 700 "$HELPER" "$HELPER/lib"
for file in collector.jar root-trust.sh lifecycle-lock.sh uninstall-service.sh uninstall-local.sh;do
  test -f "$ROOT/$file" && test ! -L "$ROOT/$file" || exit 1
  cp "$ROOT/$file" "$HELPER/$file";chmod 600 "$HELPER/$file"
done
for lib in "$ROOT"/lib/*.jar;do cp "$lib" "$HELPER/lib/";done
cp "$ROOT/conf/java.path" "$HELPER/java.path";chmod 600 "$HELPER/java.path"
printf '%s\n' "$mode" > "$HELPER/scheduler"
# Explicit package-owned files. No traversal or dashboard-driven manifest.
cat > "$HELPER/owned-files" <<'FILES'
install.sh
system-install.sh
root-system-install.sh
root-migrate-input.sh
root-service.sh
root-trust.sh
root-java-discovery.sh
launcher-common.sh
bootstrap-config.sh
run-once.sh
agent-tool.sh
poll.sh
collector.jar
uninstall-install.sh
uninstall-service.sh
uninstall-local.sh
lifecycle-lock.sh
update-install.sh
update-local.sh
update-service.sh
update-public.der
conf/collector.properties
conf/java.path
conf/installation-id
conf/root-service-mode
conf/uninstall-capability
conf/update-capability
conf/ca.pem
trust/ca.pem
logs/first-cycle.tmp
FILES
for lib in "$ROOT"/lib/*.jar;do printf 'lib/%s\n' "$(basename "$lib")" >> "$HELPER/owned-files";done
rm -f "$HELPER/stop"
if test "$mode" = systemd;then
  for unit in service timer;do cp "$SYSTEMD/java-assessment-collector.$unit" "$HELPER/collector.$unit";done
  target=$SYSTEMD/java-assessment-uninstall.service
  if test -e "$target" || test -L "$target";then test -f "$target" && test ! -L "$target" && grep -Fx 'ExecStart=/bin/sh /opt/java-assessment-uninstall/uninstall-service.sh loop' "$target" >/dev/null || exit 1;fi
  cat > "$HELPER/helper.service" <<'UNIT'
# Java Assessment restricted uninstall executor
[Unit]
Description=Java Assessment restricted uninstall executor
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
User=root
Group=root
UMask=0077
ExecStart=/bin/sh /opt/java-assessment-uninstall/uninstall-service.sh loop
Restart=on-failure
RestartSec=15
TimeoutStopSec=1800
NoNewPrivileges=true
[Install]
WantedBy=multi-user.target
UNIT
  cp "$HELPER/helper.service" "$target";chmod 644 "$target"
  systemctl daemon-reload;systemctl enable --now java-assessment-uninstall.service
  systemctl is-active --quiet java-assessment-uninstall.service
else
  case "$INIT" in /etc/init.d|/etc/rc.d/init.d) ;; *) exit 1;; esac
  cp "$INIT/java-assessment-collector" "$HELPER/collector.init"
  target=$INIT/java-assessment-uninstall
  if test -e "$target" || test -L "$target";then test -f "$target" && test ! -L "$target" && grep -Fx 'exec /bin/sh /opt/java-assessment-uninstall/uninstall-service.sh "$@"' "$target" >/dev/null || exit 1;fi
  cat > "$HELPER/helper.init" <<'INITFILE'
#!/bin/sh
# chkconfig: 345 97 03
# description: Java Assessment restricted uninstall executor
exec /bin/sh /opt/java-assessment-uninstall/uninstall-service.sh "$@"
INITFILE
  cp "$HELPER/helper.init" "$target";chmod 755 "$target"
  chkconfig --add java-assessment-uninstall;chkconfig java-assessment-uninstall on
  /bin/sh "$target" start
fi
printf 'uninstall-v1\n' > "$ROOT/conf/uninstall-capability"
