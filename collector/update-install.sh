#!/bin/sh
# Root installer installs immutable recovery code independently of collector.jar.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
umask 077
ROOT=/opt/java-assessment-collector
HELPER=/opt/java-assessment-update
SYSTEMD=/etc/systemd/system
INIT=$(readlink -f /etc/init.d)
test "$(id -u)" = 0 && command -v flock >/dev/null 2>&1 || exit 2
test "$#" = 1 || exit 2
case "$1" in systemd|sysv) mode=$1;; *) exit 2;; esac
protected_parent() {
  path=$1
  while test "$path" != /; do
    test -d "$path" && test ! -L "$path" && test "$(stat -c %u "$path")" = 0 || exit 1
    bits=$(stat -c %a "$path"); test "$((0$bits & 022))" = 0 || exit 1
    path=$(dirname "$path")
  done
}
protected_target() {
  protected_parent "$(dirname "$1")"
  if test -e "$1" || test -L "$1"; then
    test -f "$1" && test ! -L "$1" && test "$(stat -c %u "$1")" = 0 && test "$(stat -c %h "$1")" = 1 || exit 1
    bits=$(stat -c %a "$1"); test "$((0$bits & 022))" = 0 || exit 1
  fi
}
protected_parent /opt
for file in "$HELPER/journal.json" "$ROOT/collector-spool/update-request.json"; do
  test ! -e "$file" && test ! -L "$file" || { echo 'Agent update pending; administrative overwrite refused.' >&2; exit 1; }
done
test ! -L "$HELPER" || exit 1
if test -d "$HELPER"; then
  /bin/sh "$ROOT/root-trust.sh" package "$HELPER"
  test -z "$(find "$HELPER" \( -type l -o \( -type f -links +1 \) \) -print -quit)" || exit 1
  if test "$mode" = systemd; then systemctl stop java-assessment-update.service; else /bin/sh "$HELPER/update-service.sh" stop; fi
fi
mkdir -p "$HELPER/lib"; chmod 700 "$HELPER" "$HELPER/lib"
for file in root-trust.sh lifecycle-lock.sh update-local.sh update-service.sh update-public.der; do
  test -f "$ROOT/$file" && test ! -L "$ROOT/$file" || exit 1
  cp "$ROOT/$file" "$HELPER/$file"; chmod 600 "$HELPER/$file"
done
cp "$ROOT/collector.jar" "$HELPER/helper.jar"; chmod 600 "$HELPER/helper.jar"
for lib in "$ROOT"/lib/*.jar; do cp "$lib" "$HELPER/lib/"; done
cp "$ROOT/conf/java.path" "$HELPER/java.path"; chmod 600 "$HELPER/java.path"
printf '%s\n' "$mode" > "$HELPER/scheduler"
rm -f "$HELPER/stop"
if test "$mode" = systemd; then
  for unit in service timer; do cp "$SYSTEMD/java-assessment-collector.$unit" "$HELPER/collector.$unit"; done
  target=$SYSTEMD/java-assessment-update.service
  protected_target "$target"
  if test -e "$target" || test -L "$target"; then test -f "$target" && test ! -L "$target" && grep -Fx 'ExecStart=/bin/sh /opt/java-assessment-update/update-service.sh loop' "$target" >/dev/null || exit 1; fi
  cat > "$HELPER/helper.service" <<'UNIT'
# Java Assessment signed agent update executor
[Unit]
Description=Java Assessment signed agent update executor
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
User=root
Group=root
UMask=0077
ExecStart=/bin/sh /opt/java-assessment-update/update-service.sh loop
Restart=on-failure
RestartSec=15
TimeoutStopSec=1800
NoNewPrivileges=true
[Install]
WantedBy=multi-user.target
UNIT
  cp "$HELPER/helper.service" "$target"; chmod 644 "$target"
  printf 'update-v2\n' > "$ROOT/conf/update-capability"
  systemctl daemon-reload; systemctl enable --now java-assessment-update.service
  systemctl is-active --quiet java-assessment-update.service
else
  case "$INIT" in /etc/init.d|/etc/rc.d/init.d) ;; *) exit 1;; esac
  cp "$INIT/java-assessment-collector" "$HELPER/collector.init"
  target=$INIT/java-assessment-update
  protected_target "$target"
  if test -e "$target" || test -L "$target"; then test -f "$target" && test ! -L "$target" && grep -Fx 'exec /bin/sh /opt/java-assessment-update/update-service.sh "$@"' "$target" >/dev/null || exit 1; fi
  cat > "$HELPER/helper.init" <<'INITFILE'
#!/bin/sh
# chkconfig: 345 96 04
# description: Java Assessment signed agent update executor
exec /bin/sh /opt/java-assessment-update/update-service.sh "$@"
INITFILE
  cp "$HELPER/helper.init" "$target"; chmod 755 "$target"
  printf 'update-v2\n' > "$ROOT/conf/update-capability"
  chkconfig --add java-assessment-update; chkconfig java-assessment-update on
  /bin/sh "$target" start
fi
