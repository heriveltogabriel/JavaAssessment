#!/usr/bin/env bash
set -Eeuo pipefail

SOURCE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
if (( EUID != 0 )); then
  if ! command -v sudo >/dev/null 2>&1; then
    echo 'ERROR: sudo is required; run this script as root.' >&2
    exit 1
  fi
  exec sudo "$SOURCE_DIR/install.sh" "$@"
fi

umask 077
LOG=/var/log/java-assessment-dashboard-install.log
touch "$LOG"
chmod 600 "$LOG"
exec > >(tee -a "$LOG") 2>&1
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) Starting Java Assessment dashboard installation"

if [[ ! -f /etc/os-release ]]; then
  echo 'ERROR: /etc/os-release is missing.' >&2
  exit 1
fi
if [[ ! -x /usr/bin/dnf ]] && ! command -v dnf >/dev/null 2>&1; then
  echo 'ERROR: Enterprise Linux 8+ with dnf is required.' >&2
  exit 1
fi

if command -v python3 >/dev/null 2>&1; then
  PYTHON=$(command -v python3)
elif [[ -x /usr/libexec/platform-python ]]; then
  PYTHON=/usr/libexec/platform-python
else
  echo 'Installing Python 3 with dnf'
  dnf -y install python3
  PYTHON=$(command -v python3)
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo 'Installing OpenSSL with dnf'
  dnf -y install openssl
fi

exec "$PYTHON" -B "$SOURCE_DIR/install.py" "$@"
