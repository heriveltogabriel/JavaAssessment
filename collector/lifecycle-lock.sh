#!/bin/sh
# Sourced only by root administrative operations. All use the same Linux flock.
ASSESSMENT_LIFECYCLE_LOCK=/opt/java-assessment-lifecycle.lock
test "$(id -u)" = 0 && command -v flock >/dev/null 2>&1 || { echo 'Protected updates require root and util-linux flock.' >&2; exit 1; }
test ! -L /opt && test "$(stat -c %u /opt)" = 0 || exit 1
assessment_lock_mode=$(stat -c %a /opt); test "$((0$assessment_lock_mode & 022))" = 0 || exit 1
if test -e "$ASSESSMENT_LIFECYCLE_LOCK" || test -L "$ASSESSMENT_LIFECYCLE_LOCK"; then
  test -f "$ASSESSMENT_LIFECYCLE_LOCK" && test ! -L "$ASSESSMENT_LIFECYCLE_LOCK" && test "$(stat -c %u "$ASSESSMENT_LIFECYCLE_LOCK")" = 0 && test "$(stat -c %h "$ASSESSMENT_LIFECYCLE_LOCK")" = 1 || exit 1
  test "$(stat -c %a "$ASSESSMENT_LIFECYCLE_LOCK")" = 600 || exit 1
fi
umask 077
exec 9>> "$ASSESSMENT_LIFECYCLE_LOCK"
flock -n 9 || { echo 'Another agent lifecycle operation is active.' >&2; exit 1; }
