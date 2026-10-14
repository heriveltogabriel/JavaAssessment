#!/bin/sh
set -eu
umask 077
ASSESSMENT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ASSESSMENT_CODE=$ASSESSMENT_ROOT
case "$ASSESSMENT_CODE" in /opt/java-assessment-collector/releases/*) ASSESSMENT_ROOT=/opt/java-assessment-collector;; esac
. "$ASSESSMENT_CODE/launcher-common.sh"
case "${1:-}" in
  version) test "$#" = 1 || exit 2;;
  register)
    test "$#" -eq 3 || { echo 'Usage: agent-tool.sh register BOOTSTRAP_PROPERTIES FINAL_CONFIG' >&2; exit 1; }
    assessment_check_private_file "$2"
    if test -e "$3" || test -L "$3"; then assessment_check_private_file "$3"; fi
    ;;
  diagnose)
    test "$#" -eq 2 || { echo 'Usage: agent-tool.sh diagnose FINAL_CONFIG' >&2; exit 1; }
    assessment_check_private_file "$2"
    ;;
  events)
    test "$#" -eq 3 || { echo 'Usage: agent-tool.sh events FINAL_CONFIG EVENTS_JSON' >&2; exit 1; }
    assessment_check_private_file "$2"
    ;;
  *) echo 'Usage: agent-tool.sh version|register|diagnose|events ...' >&2; exit 1;;
esac
assessment_select_java
assessment_execute 120 assessment.AgentTool "$@"
