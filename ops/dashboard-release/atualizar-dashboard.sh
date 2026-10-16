#!/bin/sh
# Execute na VM que já hospeda o dashboard. Não reinstala nem opera agentes.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
test "$(id -u)" = 0 || { echo 'Execute: sudo /bin/sh atualizar-dashboard.sh' >&2; exit 1; }
if command -v python3 >/dev/null 2>&1; then PYTHON=$(command -v python3)
elif test -x /usr/libexec/platform-python; then PYTHON=/usr/libexec/platform-python
else echo 'Python 3.6 ou posterior é necessário.' >&2; exit 1; fi
exec "$PYTHON" -B "$HERE/update-dashboard.py" --local \
  "$HERE/assessment-dashboard-runtime-@DASHBOARD_VERSION@.zip" "$HERE/dashboard-update-sources.zip"
