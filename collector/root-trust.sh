#!/bin/sh
# Linux/RHEL 5 compatible, read-only validation. Never execute the Java candidate.
set -eu
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS CLASSPATH ENV BASH_ENV
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH
TRUST_VERIFIED='|'

fail() { echo "Root collector trust validation failed: $1" >&2; exit 1; }
safe_chain() {
  # Check both the named path and its resolved target. A root-owned leaf under
  # a writable parent is not trusted. Symlink permissions themselves are 777.
  # RHEL /bin/sh supports local. Keep the successful ancestor cache in this
  # invocation only; every poll obtains fresh filesystem evidence.
  local TRUST_CHAIN TRUST_DEPTH TRUST_MODE TRUST_TARGET TRUST_PENDING
  TRUST_CHAIN=$1; TRUST_DEPTH=${2:-0}; TRUST_PENDING=''
  test "$TRUST_DEPTH" -le 24 || return 1
  case "$TRUST_CHAIN" in /*) ;; *) return 1;; esac
  case "$TRUST_CHAIN" in *'
'*|*'|'*) return 1;; esac
  while :; do
    case "$TRUST_VERIFIED" in *"|$TRUST_CHAIN|"*) break;; esac
    test -e "$TRUST_CHAIN" || return 1
    test "$(stat -c %u "$TRUST_CHAIN")" = 0 || return 1
    if test -L "$TRUST_CHAIN"; then
      TRUST_TARGET=$(readlink "$TRUST_CHAIN") || return 1
      case "$TRUST_TARGET" in /*) ;; *) TRUST_TARGET="$(dirname "$TRUST_CHAIN")/$TRUST_TARGET";; esac
      # Validate each link hop; readlink -f alone could hide an intermediate
      # symlink controlled by an unprivileged account.
      safe_chain "$TRUST_TARGET" "$((TRUST_DEPTH + 1))" || return 1
    else
      TRUST_MODE=$(stat -c %a "$TRUST_CHAIN")
      test "$((0$TRUST_MODE & 022))" = 0 || return 1
    fi
    TRUST_PENDING="$TRUST_PENDING$TRUST_CHAIN|"
    test "$TRUST_CHAIN" != / || break
    TRUST_CHAIN=$(dirname "$TRUST_CHAIN")
  done
  TRUST_VERIFIED="$TRUST_VERIFIED$TRUST_PENDING"
}
safe_path() {
  safe_chain "$1" || return 1
  TRUST_RESOLVED=$(readlink -f "$1") || return 1
  # safe_chain already checked every named parent and every individual link
  # hop; avoid repeating the same stat subprocesses for the canonical path.
  test -n "$TRUST_RESOLVED"
}
safe_simple_internal_link() {
  # The enclosing physical tree has already passed the batched owner/mode
  # checks. Resolve simple relative links without spawning stat for every legal
  # document. Any further symlink or complex traversal uses the full validator.
  local SIMPLE_TREE SIMPLE_LINK SIMPLE_TARGET SIMPLE_BASE SIMPLE_REST SIMPLE_PART SIMPLE_CURRENT
  SIMPLE_TREE=$1; SIMPLE_LINK=$2
  SIMPLE_TARGET=$(readlink "$SIMPLE_LINK") || return 1
  case "$SIMPLE_TARGET" in /*|'') return 1;; esac
  SIMPLE_BASE=${SIMPLE_LINK%/*}
  while :; do
    case "$SIMPLE_TARGET" in
      ../*) SIMPLE_BASE=${SIMPLE_BASE%/*}; SIMPLE_TARGET=${SIMPLE_TARGET#../};;
      ./*) SIMPLE_TARGET=${SIMPLE_TARGET#./};;
      *) break;;
    esac
  done
  case "$SIMPLE_TARGET" in ''|..|.|*/../*|*/..|*/./*|*/.) return 1;; esac
  SIMPLE_CURRENT=$SIMPLE_BASE
  case "$SIMPLE_CURRENT" in "$SIMPLE_TREE"|"$SIMPLE_TREE"/*) ;; *) return 1;; esac
  SIMPLE_REST=$SIMPLE_TARGET
  while :; do
    SIMPLE_PART=${SIMPLE_REST%%/*}
    test -n "$SIMPLE_PART" || return 1
    SIMPLE_CURRENT="$SIMPLE_CURRENT/$SIMPLE_PART"
    test ! -L "$SIMPLE_CURRENT" || return 1
    case "$SIMPLE_REST" in */*) SIMPLE_REST=${SIMPLE_REST#*/};; *) break;; esac
  done
  test -f "$SIMPLE_CURRENT" || test -d "$SIMPLE_CURRENT"
}
safe_link() {
  local TRUST_TREE TRUST_LINK TRUST_RESOLVED TRUST_TREE_DEPTH
  TRUST_TREE=$1; TRUST_TREE_DEPTH=$2; TRUST_LINK=$3
  safe_simple_internal_link "$TRUST_TREE" "$TRUST_LINK" && return 0
  safe_path "$TRUST_LINK" || return 1
  # Internal directory links are covered by the non-following tree traversal.
  # External regular files (e.g. RPM cacerts) are allowed only with safe parents.
  # RPM OpenJDK places conf and lib/security in /etc/java: validate those
  # complete trees too, with a bounded recursion depth and the same link rules.
  case "$TRUST_RESOLVED" in "$TRUST_TREE"|"$TRUST_TREE"/*) ;;
    /etc/java/*)
      if test -d "$TRUST_RESOLVED"; then safe_tree "$TRUST_RESOLVED" "$TRUST_TREE_DEPTH" || return 1
      else test -f "$TRUST_RESOLVED" || return 1; fi;;
    *) test -f "$TRUST_RESOLVED" || return 1;;
  esac
}
safe_tree() {
  local TRUST_TREE TRUST_BAD TRUST_TREE_DEPTH
  TRUST_TREE_DEPTH=${2:-0}
  test "$TRUST_TREE_DEPTH" -le 4 || return 1
  safe_path "$1" || return 1
  TRUST_TREE=$(readlink -f "$1") || return 1
  test -d "$TRUST_TREE" || return 1
  # Do not follow symlinks here, nor special devices, FIFOs, or sockets. GNU find
  # exits at the first failure; no file contents or credentials are printed.
  TRUST_BAD=$(find "$TRUST_TREE" \( ! -uid 0 -o \( ! -type l \( -perm -020 -o -perm -002 \) \) -o \( ! -type f ! -type d ! -type l \) \) -print -quit) || return 1
  test -z "$TRUST_BAD" || return 1
  # One shell per batch, not per link. This shares successful parent checks
  # across the hundreds of legal-document symlinks in modern OpenJDK packages.
  find "$TRUST_TREE" -type l -exec /bin/sh "$TRUST_SELF" links "$TRUST_TREE" "$((TRUST_TREE_DEPTH + 1))" '{}' + || return 1
}

TRUST_SELF=$(readlink -f "$0")
case "${1:-}" in
  java)
    test "$#" -eq 2 || fail 'expected java /absolute/path/bin/java'
    case "$2" in /*) ;; *) fail 'Java executable path must be absolute';; esac
    test -e "$2" || fail 'Java executable path does not exist or contains a broken symbolic link; check --java'
    safe_path "$2" || fail 'Java path or its parents are writable by another account'
    TRUST_JAVA=$(readlink -f "$2")
    case "$TRUST_JAVA" in */bin/java) ;; *) fail 'Java must resolve to a runtime bin/java';; esac
    test -f "$TRUST_JAVA" && test -x "$TRUST_JAVA" || fail 'Java is not an executable regular file'
    TRUST_RUNTIME=$(dirname "$(dirname "$TRUST_JAVA")")
    # A JDK's jre/bin/java may load code/config from the enclosing JDK as well.
    if test "$(basename "$TRUST_RUNTIME")" = jre && test -f "$TRUST_RUNTIME/../bin/javac"; then
      TRUST_RUNTIME=$(readlink -f "$TRUST_RUNTIME/..")
    fi
    safe_tree "$TRUST_RUNTIME" || fail 'runtime contains an untrusted owner, mode, link, or special file'
    # Oracle/OpenJDK 6-8 may search this system extension directory before the
    # application's classpath, even though it lives outside JAVA_HOME.
    if test -e /usr/java/packages/lib/ext || test -L /usr/java/packages/lib/ext; then
      safe_tree /usr/java/packages/lib/ext || fail 'system Java extension directory is not trusted'
    fi
    printf '%s\n' "$TRUST_JAVA"
    ;;
  package)
    test "$#" -eq 2 || fail 'expected package /absolute/collector/path'
    safe_tree "$2" || fail 'collector package contains an untrusted owner, mode, link, or special file'
    test ! -L "$2/conf" || fail 'configuration directory must not be a symbolic link'
    if test -d "$2/conf"; then
      TRUST_BAD=$(find "$2/conf" -type l -print -quit)
      test -z "$TRUST_BAD" || fail 'configuration files cannot be symbolic links'
      TRUST_BAD=$(find "$2/conf" -type f ! -perm 0600 ! -perm 0400 -print -quit)
      test -z "$TRUST_BAD" || fail 'configuration files must have mode 600 or 400'
    fi
    ;;
  private)
    test "$#" -eq 2 || fail 'expected private /absolute/configuration/path'
    safe_path "$2" && test -f "$2" && test ! -L "$2" || fail 'configuration path is not trusted'
    case "$(stat -c %a "$2")" in 600|400) ;; *) fail 'configuration files must have mode 600 or 400';; esac
    ;;
  ca)
    test "$#" -eq 2 || fail 'expected ca /absolute/CA/path'
    safe_path "$2" && test -f "$TRUST_RESOLVED" || fail 'CA path is not trusted'
    test "$(wc -c < "$TRUST_RESOLVED")" -le 524288 || fail 'CA bundle exceeds bound'
    ;;
  links)
    test "$#" -ge 4 || exit 1
    TRUST_BATCH_TREE=$2; TRUST_BATCH_DEPTH=$3; shift 3
    for TRUST_BATCH_LINK do safe_link "$TRUST_BATCH_TREE" "$TRUST_BATCH_DEPTH" "$TRUST_BATCH_LINK" || exit 1; done
    ;;
  *) fail 'expected java or package validation';;
esac
