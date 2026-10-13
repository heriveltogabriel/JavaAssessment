#!/bin/sh
# Read migration data as its existing owner, never evaluate it. stdout is private
# machine-to-machine input and must be redirected by the privileged installer.
set -eu
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS CLASSPATH ENV BASH_ENV
PATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH
umask 077
fail() { echo 'Existing collector migration input is invalid or inaccessible.' >&2; exit 1; }
migration_stat() {
  stat -c "$1" "$2" 2>/dev/null && return 0
  case "$1" in %u) field=%u;; %h) field=%l;; %a) field=%Lp;; %s) field=%z;; *) return 1;; esac
  stat -f "$field" "$2"
}
check_path() {
  case "$1" in /*) ;; *) fail;; esac
  case "$1" in *[!a-zA-Z0-9_./-]*|*..*|/) fail;; esac
  check=$1
  while test "$check" != /; do test ! -L "$check" || fail; check=$(dirname "$check"); done
}
check_file() {
  check_path "$1"
  test -f "$1" && test "$(migration_stat %u "$1")" = "$(id -u)" && test "$(migration_stat %h "$1")" = 1 || fail
  case "$(migration_stat %a "$1")" in 600|400) ;; *) fail;; esac
  size=$(migration_stat %s "$1"); test "$size" -ge 1 && test "$size" -le "$2" || fail
}
source_dir=${2:-}
check_path "$source_dir"
test -d "$source_dir" || fail
directory_owner=$(migration_stat %u "$source_dir")
test "$directory_owner" = "$(id -u)" || test "$directory_owner" = 0 || fail
case "${1:-}" in
  config)
    check_file "$source_dir/conf/collector.properties" 32768
    test "$(LC_ALL=C tr -d '\012\040-\176' < "$source_dir/conf/collector.properties" | wc -c | tr -d ' ')" = 0 || fail
    seen='|'; found_server=false; found_host=false; found_token=false
    while IFS= read -r line || test -n "$line"; do
      case "$line" in ''|'#'*|'!'*) continue;; *=*) ;; *) fail;; esac
      key=${line%%=*}; value=${line#*=}
      case "$key" in
        serverUrl|hostId|agentToken|hostname|snapshotRoot)
          case "$seen" in *"|$key|"*) fail;; esac
          seen="$seen$key|"
          # java.util.Properties escapes delimiters when it writes these keys.
          value=$(printf '%s' "$value" | sed 's/\\:/:/g; s/\\=/=/g; s/\\!/!/g; s/\\#/#/g')
          case "$value" in *'\'*) fail;; esac
          case "$key" in
            serverUrl)
              case "$value" in https://*) authority=${value#https://};; http://127.0.0.1:*|http://localhost:*) authority=${value#http://};; *) fail;; esac
              case "$authority" in ''|*[!a-zA-Z0-9.:-]*|*..*) fail;; esac
              test "${#value}" -le 512 || fail; found_server=true;;
            hostId) case "$value" in ''|*[!a-zA-Z0-9_.:-]*) fail;; esac; test "${#value}" -le 256 || fail; found_host=true;;
            agentToken) case "$value" in ''|*[!a-zA-Z0-9_.~+/=-]*) fail;; esac; test "${#value}" -ge 16 && test "${#value}" -le 1024 || fail; found_token=true;;
            hostname) case "$value" in ''|*[!a-zA-Z0-9_.:-]*) fail;; esac; test "${#value}" -le 200 || fail;;
            snapshotRoot) case "$value" in /*) ;; *) fail;; esac; case "$value" in *[!a-zA-Z0-9_./-]*|*..*|/) fail;; esac;;
          esac
          printf '%s=%s\n' "$key" "$value";;
        spoolDirectory|logDirectory|tlsCaFile|tlsProvider|cachedIncludePaths) ;; # replaced with fixed protected paths
        *) fail;;
      esac
    done < "$source_dir/conf/collector.properties"
    $found_server && $found_host && $found_token || fail
    if test -e "$source_dir/conf/installation-id" || test -L "$source_dir/conf/installation-id"; then
      check_file "$source_dir/conf/installation-id" 128
      identity=$(cat "$source_dir/conf/installation-id")
      printf '%s\n' "$identity" | LC_ALL=C grep -Eq '^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$' || fail
      printf 'installationId=%s\n' "$identity"
    fi;;
  list-state)
    test ! -L "$source_dir/collector-spool" || fail
    count=0
    for file in "$source_dir"/collector-spool/pending-*.json "$source_dir/collector-spool/latest-snapshot.json" "$source_dir/collector-spool/last-ack.json" "$source_dir"/logs/install-events-*.json; do
      test -e "$file" || continue
      name=$(basename "$file")
      case "$name" in latest-snapshot.json|last-ack.json) ;; pending-*.json) printf '%s\n' "$name" | LC_ALL=C grep -Eq '^pending-[a-fA-F0-9-]{8,80}\.json$' || fail;; install-events-*.json) printf '%s\n' "$name" | LC_ALL=C grep -Eq '^install-events-[a-zA-Z0-9-]{1,100}\.json$' || fail;; *) fail;; esac
      check_file "$file" 2097152
      count=$((count + 1)); test "$count" -le 256 || fail
      printf '%s\n' "${file#"$source_dir"/}"
    done;;
  state)
    name=${3:-}
    case "$name" in collector-spool/latest-snapshot.json|collector-spool/last-ack.json) ;; collector-spool/pending-*.json) printf '%s\n' "$name" | LC_ALL=C grep -Eq '^collector-spool/pending-[a-fA-F0-9-]{8,80}\.json$' || fail;; logs/install-events-*.json) printf '%s\n' "$name" | LC_ALL=C grep -Eq '^logs/install-events-[a-zA-Z0-9-]{1,100}\.json$' || fail;; *) fail;; esac
    check_file "$source_dir/$name" 2097152
    cat "$source_dir/$name";;
  *) fail;;
esac
