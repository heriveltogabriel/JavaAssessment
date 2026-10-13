#!/bin/sh
# Credential data parser. Never source or evaluate a downloaded properties file.
# Functions are sourced from this trusted package script; data is read with read.
assessment_input_error() { echo "$1" >&2; return 1; }
assessment_stat() {
  case "$1" in owner) assessment_gnu=%u; assessment_bsd=%u;; size) assessment_gnu=%s; assessment_bsd=%z;; links) assessment_gnu=%h; assessment_bsd=%l;; mode) assessment_gnu=%a; assessment_bsd=%Lp;; esac
  stat -c "$assessment_gnu" "$2" 2>/dev/null || stat -f "$assessment_bsd" "$2"
}
assessment_absolute_input() {
  case "$1" in /*) printf '%s\n' "$1";; *) printf '%s/%s\n' "$PWD" "$1";; esac
}
assessment_protect_input() {
  ASSESSMENT_INPUT=$(assessment_absolute_input "$1")
  test -f "$ASSESSMENT_INPUT" && test ! -L "$ASSESSMENT_INPUT" || { assessment_input_error 'Registration input must be a regular non-symlink file.'; return 1; }
  assessment_parent=$ASSESSMENT_INPUT
  while test "$assessment_parent" != /; do
    test ! -L "$assessment_parent" || { assessment_input_error 'Registration input path cannot contain symlinks.'; return 1; }
    assessment_parent=$(dirname "$assessment_parent")
  done
  test "$(assessment_stat links "$ASSESSMENT_INPUT")" = 1 || { assessment_input_error 'Registration input cannot have hard links.'; return 1; }
  test "$(assessment_stat owner "$ASSESSMENT_INPUT")" = "$(id -u)" || { assessment_input_error 'Registration input must belong to the invoking user.'; return 1; }
  assessment_size=$(assessment_stat size "$ASSESSMENT_INPUT")
  test "$assessment_size" -gt 0 && test "$assessment_size" -le 8192 || { assessment_input_error 'Registration input must contain 1 to 8192 bytes.'; return 1; }
  chmod 600 "$ASSESSMENT_INPUT" || return 1
  test "$(LC_ALL=C tr -d '\012\040-\176' < "$ASSESSMENT_INPUT" | wc -c | tr -d ' ')" = 0 || { assessment_input_error 'Registration input contains unsupported control or binary characters.'; return 1; }
}
assessment_validate_server() {
  assessment_url=$1
  while test "${assessment_url%/}" != "$assessment_url"; do assessment_url=${assessment_url%/}; done
  case "$assessment_url" in https://*) assessment_authority=${assessment_url#https://};; http://127.0.0.1:*) assessment_authority=${assessment_url#http://};; http://localhost:*) assessment_authority=${assessment_url#http://};; *) assessment_input_error 'Registration requires an HTTPS dashboard origin.'; return 1;; esac
  case "$assessment_authority" in ''|*[!a-zA-Z0-9.:-]*|*..*) assessment_input_error 'Dashboard origin must contain only a hostname or IP and optional port.'; return 1;; esac
  test "${#assessment_url}" -le 512 || { assessment_input_error 'Dashboard origin is too long.'; return 1; }
  ASSESSMENT_SERVER_VALUE=$assessment_url
}
assessment_validate_code() {
  case "$1" in ''|*[!a-zA-Z0-9_-]*) assessment_input_error 'Registration authorization has an invalid format.'; return 1;; esac
  test "${#1}" -ge 16 && test "${#1}" -le 1024 || { assessment_input_error 'Registration authorization has an invalid length.'; return 1; }
}
assessment_parse_bootstrap() {
  BOOTSTRAP_SERVER=''; BOOTSTRAP_TOKEN=''; BOOTSTRAP_ENVIRONMENT=''
  assessment_seen_server=false; assessment_seen_token=false; assessment_seen_environment=false
  while IFS= read -r assessment_line || test -n "$assessment_line"; do
    case "$assessment_line" in
      serverUrl=*)
        $assessment_seen_server && { assessment_input_error 'Duplicate bootstrap property.'; return 1; }
        BOOTSTRAP_SERVER=${assessment_line#serverUrl=}; assessment_seen_server=true;;
      enrollmentToken=*)
        $assessment_seen_token && { assessment_input_error 'Duplicate bootstrap property.'; return 1; }
        BOOTSTRAP_TOKEN=${assessment_line#enrollmentToken=}; assessment_seen_token=true;;
      environmentId=*)
        $assessment_seen_environment && { assessment_input_error 'Duplicate bootstrap property.'; return 1; }
        BOOTSTRAP_ENVIRONMENT=${assessment_line#environmentId=}; assessment_seen_environment=true;;
      ''|'#'*) ;;
      *) assessment_input_error 'Unknown or malformed bootstrap property.'; return 1;;
    esac
  done
  $assessment_seen_server && $assessment_seen_token && $assessment_seen_environment || { assessment_input_error 'Configured package is missing required registration properties.'; return 1; }
  assessment_validate_server "$BOOTSTRAP_SERVER" || return 1
  BOOTSTRAP_SERVER=$ASSESSMENT_SERVER_VALUE
  assessment_validate_code "$BOOTSTRAP_TOKEN" || return 1
  case "$BOOTSTRAP_ENVIRONMENT" in ''|*[!a-zA-Z0-9_-]*) assessment_input_error 'Configured environment identifier is invalid.'; return 1;; esac
  test "${#BOOTSTRAP_ENVIRONMENT}" -le 128 || { assessment_input_error 'Configured environment identifier is too long.'; return 1; }
}
assessment_read_bootstrap() {
  assessment_protect_input "$1" || return 1
  assessment_parse_bootstrap < "$ASSESSMENT_INPUT"
}
assessment_read_code() {
  assessment_protect_input "$1" || return 1
  CODE=$(cat "$ASSESSMENT_INPUT")
  assessment_validate_code "$CODE"
}
# Executable interface for privilege-separated reads. Only the original sudo
# user reads user-owned inputs; root validates these data again before delegation.
case "${1:-}" in
  read-bootstrap)
    test "$#" -eq 2 || exit 2
    assessment_read_bootstrap "$2" || exit 1
    printf 'serverUrl=%s\nenrollmentToken=%s\nenvironmentId=%s\n' "$BOOTSTRAP_SERVER" "$BOOTSTRAP_TOKEN" "$BOOTSTRAP_ENVIRONMENT"
    ;;
  read-code)
    test "$#" -eq 2 || exit 2
    assessment_read_code "$2" || exit 1
    printf '%s\n' "$CODE"
    ;;
esac
