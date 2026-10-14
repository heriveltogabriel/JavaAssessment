#!/bin/sh
set -eu
umask 077
ASSESSMENT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ASSESSMENT_CODE=$ASSESSMENT_ROOT
case "$ASSESSMENT_CODE" in /opt/java-assessment-collector/releases/*) ASSESSMENT_ROOT=/opt/java-assessment-collector;; esac
ASSESSMENT_CONFIG=${1:-"$ASSESSMENT_ROOT/conf/collector.properties"}
. "$ASSESSMENT_CODE/launcher-common.sh"
assessment_check_private_file "$ASSESSMENT_CONFIG"
assessment_select_java
assessment_execute 1050 assessment.AssessmentCollector "$ASSESSMENT_CONFIG"
