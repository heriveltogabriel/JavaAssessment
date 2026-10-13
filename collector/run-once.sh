#!/bin/sh
set -eu
umask 077
ASSESSMENT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ASSESSMENT_CONFIG=${1:-"$ASSESSMENT_ROOT/conf/collector.properties"}
. "$ASSESSMENT_ROOT/launcher-common.sh"
assessment_check_private_file "$ASSESSMENT_CONFIG"
assessment_select_java
assessment_execute 1050 assessment.AssessmentCollector "$ASSESSMENT_CONFIG"
