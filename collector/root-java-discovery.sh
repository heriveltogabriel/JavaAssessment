#!/bin/sh
# Installation-time selection only. Every Java executable and the installed
# probe classpath are validated before a candidate is executed as root.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_HOME JDK_HOME ASSESSMENT_JAVA ASSESSMENT_JAVA_HOME CLASSPATH ENV BASH_ENV
umask 077

assessment_root_java_candidates() {
  if test -n "${1:-}"; then printf '%s\n' "$1"; fi
  for root_hint in "${2:-}" "${3:-}"; do
    test -z "$root_hint" || printf '%s/bin/java\n' "$root_hint"
  done
  # PATH was reset above; user environment variables do not select privileged code.
  command -v java 2>/dev/null || true
  for candidate in /usr/bin/java /usr/lib/jvm/*/bin/java \
      /usr/java/latest/bin/java /usr/java/*/bin/java \
      /opt/java-lab/jdk*/bin/java /opt/java/*/bin/java; do
    printf '%s\n' "$candidate"
  done
}

assessment_root_search_roots() {
  for root_search_path in /opt /usr/local /u01 /u02 /app /apps /oracle /data; do
    if test -d "$root_search_path" && test ! -L "$root_search_path"; then printf '%s\n' "$root_search_path"; fi
  done
}

assessment_root_search_java() (
  # This final fallback is installation-only. Its scratch directory is private,
  # inside the already validated collector package, and removed on every exit.
  set --
  while IFS= read -r search_root; do
    test -z "$search_root" || set -- "$@" "$search_root"
  done <<SEARCH_ROOTS
$(assessment_root_search_roots)
SEARCH_ROOTS
  test "$#" -gt 0 || return 0
  search_stage=$(mktemp -d "$ROOT_JAVA_PACKAGE/.java-discovery.XXXXXX") || return 1
  search_find_pid=''; search_read_pid=''; search_watch_pid=''
  search_cleanup() {
    for search_pid in "$search_find_pid" "$search_read_pid" "$search_watch_pid"; do
      test -z "$search_pid" || kill -TERM "$search_pid" 2>/dev/null || true
    done
    for search_pid in "$search_find_pid" "$search_read_pid" "$search_watch_pid"; do
      test -z "$search_pid" || wait "$search_pid" 2>/dev/null || true
    done
    rm -f "$search_stage/entries" "$search_stage/candidates" "$search_stage/entry-limit" "$search_stage/time-limit"
    rmdir "$search_stage" 2>/dev/null || true
  }
  trap search_cleanup 0
  trap 'exit 130' HUP INT TERM
  mkfifo "$search_stage/entries"
  printf 'Searching protected Java locations (depth 8, 10000 entries, 10 seconds maximum).\n' >&2
  # Print every visited entry, including a pruned directory, so the reader's
  # entry budget also includes directories. Untrusted trees cannot be eligible.
  find "$@" -xdev -maxdepth 8 -printf '%y %p\n' \
    \( -type d \( ! -uid 0 -o -perm -020 -o -perm -002 \) -prune \) \
    > "$search_stage/entries" 2>/dev/null &
  search_find_pid=$!
  awk -v limit_file="$search_stage/entry-limit" '
    NR > 10000 {print "limit" > limit_file; exit}
    ($1 == "f" || $1 == "l") && /\/bin\/java$/ {print substr($0, 3)}
  ' < "$search_stage/entries" > "$search_stage/candidates" &
  search_read_pid=$!
  (
    watch_sleep_pid=''
    stop_watch() {
      if test -n "$watch_sleep_pid"; then
        kill -TERM "$watch_sleep_pid" 2>/dev/null || true
        wait "$watch_sleep_pid" 2>/dev/null || true
      fi
      exit 0
    }
    trap stop_watch HUP INT TERM
    sleep 10 & watch_sleep_pid=$!
    wait "$watch_sleep_pid" || exit 0
    watch_sleep_pid=''
    printf 'limit\n' > "$search_stage/time-limit"
    kill -TERM "$search_find_pid" "$search_read_pid" 2>/dev/null || true
    sleep 1 & watch_sleep_pid=$!
    wait "$watch_sleep_pid" || exit 0
    watch_sleep_pid=''
    kill -KILL "$search_find_pid" "$search_read_pid" 2>/dev/null || true
  ) >/dev/null 2>&1 &
  search_watch_pid=$!
  wait "$search_read_pid" || true
  search_read_pid=''
  # If the reader hit its entry limit, stop its producer immediately.
  kill -TERM "$search_find_pid" 2>/dev/null || true
  wait "$search_find_pid" || true
  search_find_pid=''
  kill -TERM "$search_watch_pid" 2>/dev/null || true
  wait "$search_watch_pid" 2>/dev/null || true
  search_watch_pid=''
  test ! -f "$search_stage/entry-limit" || echo 'Java search reached its entry limit; use --java for an installation outside the examined scope.' >&2
  test ! -f "$search_stage/time-limit" || echo 'Java search reached its time limit; use --java for an installation outside the examined scope.' >&2
  cat "$search_stage/candidates"
)

assessment_root_java_probe() (
  probe_java=$1
  "$probe_java" -Xmx64m -Djava.security.egd=file:/dev/./urandom \
    -cp "$ROOT_JAVA_CP" assessment.JavaProbe >/dev/null 2>&1 &
  probe_pid=$!
  trap 'kill -TERM "$probe_pid" 2>/dev/null || true; wait "$probe_pid" 2>/dev/null || true' 0
  trap 'exit 130' HUP INT TERM
  probe_seconds=0
  while kill -0 "$probe_pid" 2>/dev/null; do
    if test "$probe_seconds" -ge 5; then
      kill -TERM "$probe_pid" 2>/dev/null || true
      sleep 1
      kill -KILL "$probe_pid" 2>/dev/null || true
      wait "$probe_pid" 2>/dev/null || true
      trap - 0
      return 1
    fi
    sleep 1
    probe_seconds=$((probe_seconds + 1))
  done
  probe_result=0
  wait "$probe_pid" || probe_result=$?
  trap - 0
  return "$probe_result"
)

assessment_try_root_java() {
  root_candidate=$1
  test -f "$root_candidate" && test -x "$root_candidate" || return 1
  case "$ROOT_JAVA_SEEN" in *"|$root_candidate|"*) return 1;; esac
  ROOT_JAVA_SEEN="$ROOT_JAVA_SEEN$root_candidate|"
  test "$ROOT_JAVA_COUNT" -lt 24 || return 1
  ROOT_JAVA_COUNT=$((ROOT_JAVA_COUNT + 1))
  printf 'Checking JVM candidate: %s\n' "$root_candidate" >&2
  # This command only examines files, ownership, permissions and links.
  root_resolved=$(/bin/sh "$ROOT_JAVA_PACKAGE/root-trust.sh" java "$root_candidate") || {
    echo 'Skipping candidate that did not pass root trust validation.' >&2
    return 1
  }
  case "$ROOT_JAVA_PROBED" in *"|$root_resolved|"*) return 1;; esac
  ROOT_JAVA_PROBED="$ROOT_JAVA_PROBED$root_resolved|"
  if assessment_root_java_probe "$root_resolved"; then
    ROOT_JAVA_SELECTED=$root_resolved
    printf 'Selected compatible JVM: %s\n' "$root_resolved" >&2
    return 0
  fi
  echo 'Candidate could not execute the collector compatibility probe; trying another.' >&2
  return 1
}

assessment_discover_root_java() {
  ROOT_JAVA_PACKAGE=$1
  test "$(id -u)" = 0 || { echo 'Root JVM selection requires root.' >&2; return 1; }
  case "$ROOT_JAVA_PACKAGE" in /*) ;; *) echo 'Use an absolute installed package path.' >&2; return 1;; esac
  # The caller copies into /opt first. Never execute a probe from the ZIP's
  # user-owned extraction directory, and never change its permissions.
  /bin/sh "$ROOT_JAVA_PACKAGE/root-trust.sh" package "$ROOT_JAVA_PACKAGE" || return 1
  test -f "$ROOT_JAVA_PACKAGE/collector.jar" || { echo 'Installed collector.jar is missing.' >&2; return 1; }
  ROOT_JAVA_CP=$ROOT_JAVA_PACKAGE/collector.jar
  for root_library in "$ROOT_JAVA_PACKAGE"/lib/*.jar; do
    test ! -f "$root_library" || ROOT_JAVA_CP="$ROOT_JAVA_CP:$root_library"
  done
  ROOT_JAVA_COUNT=0
  ROOT_JAVA_SEEN='|'
  ROOT_JAVA_PROBED='|'
  ROOT_JAVA_SELECTED=''
  # Finish the small known-path phase before looking at processes. A successful
  # known candidate returns immediately, without a background /proc traversal.
  while IFS= read -r root_candidate; do
    if assessment_try_root_java "$root_candidate"; then
      printf '%s\n' "$ROOT_JAVA_SELECTED"
      return 0
    fi
    test "$ROOT_JAVA_COUNT" -lt 24 || break
  done <<CANDIDATES
$(assessment_root_java_candidates "${2:-}" "${3:-}" "${4:-}")
CANDIDATES
  # A running application may reveal a nonstandard location. It supplies only
  # a path, and must pass exactly the same trust and compatibility checks.
  for root_process_exe in /proc/[0-9]*/exe; do
    test "$ROOT_JAVA_COUNT" -lt 24 || break
    root_candidate=$(readlink -f "$root_process_exe" 2>/dev/null || true)
    case "$root_candidate" in */bin/java) ;; *) continue;; esac
    if assessment_try_root_java "$root_candidate"; then
      printf '%s\n' "$ROOT_JAVA_SELECTED"
      return 0
    fi
  done
  if test "$ROOT_JAVA_COUNT" -lt 24; then
    while IFS= read -r root_candidate; do
      if assessment_try_root_java "$root_candidate"; then
        printf '%s\n' "$ROOT_JAVA_SELECTED"
        return 0
      fi
      test "$ROOT_JAVA_COUNT" -lt 24 || break
    done <<SEARCH_CANDIDATES
$(assessment_root_search_java)
SEARCH_CANDIDATES
  fi
  echo 'No compatible root-protected JVM was found among the checked candidates.' >&2
  echo 'Use --java /absolute/path/bin/java to select an existing protected JVM explicitly.' >&2
  return 1
}

assessment_root_java_main() {
  test "$#" -ge 1 && test "$#" -le 4 || { echo 'Expected installed package path, optional preferred Java path and Java home hints.' >&2; return 2; }
  assessment_discover_root_java "$@"
}

assessment_root_java_main "$@"
