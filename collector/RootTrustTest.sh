#!/bin/sh
# Run as root on Linux; creates/removes only its own directory under /root.
# No installed application, service, account, or configuration is changed.
set -eu
umask 077
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH
test "$(id -u)" = 0 || { echo 'RootTrustTest requires Linux root for ownership fixtures.' >&2; exit 2; }
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORK=$(mktemp -d /root/assessment-root-trust-test.XXXXXX)
cleanup() { rm -rf "$WORK"; }
trap cleanup 0
trap 'exit 130' HUP INT TERM
fail() { echo "FAIL: $1" >&2; exit 1; }
expect_rejected() {
  if /bin/sh "$SOURCE/root-trust.sh" "$@" > /dev/null 2>&1; then fail "accepted unsafe $1 fixture"; fi
}
mkdir -p "$WORK/runtime/bin" "$WORK/runtime/lib" "$WORK/package/conf" "$WORK/package/lib" "$WORK/external"
MARKER="$WORK/java-executed"
cat > "$WORK/runtime/bin/java" <<EOF
#!/bin/sh
printf 'executed\n' >> '$MARKER'
exit 0
EOF
chmod 700 "$WORK/runtime/bin/java"
printf 'trusted library\n' > "$WORK/runtime/lib/runtime.jar"
/bin/sh "$SOURCE/root-trust.sh" java "$WORK/runtime/bin/java" > /dev/null
test ! -e "$MARKER" || fail 'validation executed Java'
chmod 660 "$WORK/runtime/lib/runtime.jar"
expect_rejected java "$WORK/runtime/bin/java"
chmod 600 "$WORK/runtime/lib/runtime.jar"
chown 65534 "$WORK/runtime/lib/runtime.jar"
expect_rejected java "$WORK/runtime/bin/java"
chown 0 "$WORK/runtime/lib/runtime.jar"

# A secure leaf does not make an unsafe parent trustworthy.
chmod 770 "$WORK/runtime"
expect_rejected java "$WORK/runtime/bin/java"
chmod 700 "$WORK/runtime"
ln -s runtime.jar "$WORK/runtime/lib/internal.jar"
/bin/sh "$SOURCE/root-trust.sh" java "$WORK/runtime/bin/java" > /dev/null
mkdir -p "$WORK/runtime/man/en"
printf 'manual\n' > "$WORK/runtime/man/en/java.1"
ln -s en "$WORK/runtime/man/ja"
/bin/sh "$SOURCE/root-trust.sh" java "$WORK/runtime/bin/java" > /dev/null
printf 'trusted CA\n' > "$WORK/external/cacerts"
ln -s "$WORK/external/cacerts" "$WORK/runtime/lib/cacerts"
/bin/sh "$SOURCE/root-trust.sh" java "$WORK/runtime/bin/java" > /dev/null
chmod 666 "$WORK/external/cacerts"
expect_rejected java "$WORK/runtime/bin/java"
chmod 600 "$WORK/external/cacerts"
ln -s "$WORK/external" "$WORK/runtime/lib/external-dir"
expect_rejected java "$WORK/runtime/bin/java"
rm "$WORK/runtime/lib/external-dir"

# readlink -f would conceal the unsafe intermediate symlink's parent.
mkdir "$WORK/unsafe"
chmod 777 "$WORK/unsafe"
ln -s "$WORK/runtime" "$WORK/unsafe/intermediate"
ln -s "$WORK/unsafe/intermediate" "$WORK/java-alias"
expect_rejected java "$WORK/java-alias/bin/java"
mkfifo "$WORK/runtime/lib/pipe"
expect_rejected java "$WORK/runtime/bin/java"
rm "$WORK/runtime/lib/pipe"

cp "$SOURCE/launcher-common.sh" "$SOURCE/root-trust.sh" "$WORK/package/"
printf 'fixture\n' > "$WORK/package/collector.jar"
printf 'fixture\n' > "$WORK/package/lib/library.jar"
printf 'fixture\n' > "$WORK/package/conf/collector.properties"
/bin/sh "$SOURCE/root-trust.sh" package "$WORK/package"
chmod 644 "$WORK/package/conf/collector.properties"
expect_rejected package "$WORK/package"
chmod 600 "$WORK/package/conf/collector.properties"

# An inherited JVM selection cannot bypass the root-only java.path requirement.
if ASSESSMENT_ROOT="$WORK/package" ASSESSMENT_JAVA="$WORK/runtime/bin/java" JAVA_HOME="$WORK/runtime" \
  /bin/sh -c '. "$ASSESSMENT_ROOT/launcher-common.sh"; assessment_select_java' > /dev/null 2>&1; then
  fail 'root accepted an inherited JVM without java.path'
fi
test ! -e "$MARKER" || fail 'unconfigured Java was executed'
printf '%s\n' "$WORK/runtime/bin/java" > "$WORK/package/conf/java.path"
ASSESSMENT_ROOT="$WORK/package" /bin/sh -c '. "$ASSESSMENT_ROOT/launcher-common.sh"; assessment_select_java'
test -s "$MARKER" || fail 'trusted configured JVM was not probed'
rm "$MARKER"
chmod 666 "$WORK/package/lib/library.jar"
if ASSESSMENT_ROOT="$WORK/package" /bin/sh -c '. "$ASSESSMENT_ROOT/launcher-common.sh"; assessment_select_java' > /dev/null 2>&1; then
  fail 'root accepted writable collector code'
fi
test ! -e "$MARKER" || fail 'Java was probed before classpath validation'
echo 'RootTrustTest PASS'
