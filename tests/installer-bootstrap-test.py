"""Black-box shell bootstrap fixtures; no Java, network, cron, sudo or host changes."""
import os
import pathlib
import shutil
import shlex
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOKEN = 'fixture_environment_code_01234567890123456789'
EXPLICIT = 'fixture_explicit_code_01234567890123456789'
SERVER = 'https://dashboard.example.invalid'


def check(value, message):
    if not value:
        raise AssertionError(message)


def executable(path, content):
    path.write_text(content)
    path.chmod(0o700)


with tempfile.TemporaryDirectory(prefix='assessment-bootstrap-', dir='/private/tmp' if pathlib.Path('/private/tmp').exists() else '/tmp') as td:
    top = pathlib.Path(td)
    bindir = top / 'bin'
    bindir.mkdir()
    executable(bindir / 'uname', '#!/bin/sh\nprintf "Linux\\n"\n')
    # Portable stat facade, so the Linux installer can be exercised on macOS.
    (bindir / 'stat.py').write_text('import os,sys,stat\ns=os.stat(sys.argv[-1]); k=sys.argv[2]\nprint({"%u":s.st_uid,"%a":format(stat.S_IMODE(s.st_mode),"o"),"%s":s.st_size,"%h":s.st_nlink}[k])\n')
    executable(bindir / 'stat', '#!/bin/sh\nexec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(bindir / 'stat.py')) + ' "$@"\n')
    executable(bindir / 'crontab', '#!/bin/sh\nif test "${1:-}" = -l; then echo "no crontab for fixture" >&2; exit 1; fi\ncp "$1" "$FIXTURE/cron.txt"\n')
    cases = 0

    def prepare(name):
        path = top / name
        package = path / 'package'
        for child in (package / 'conf', package / 'lib', path / 'home'):
            child.mkdir(parents=True)
        for filename in ('install.sh', 'system-install.sh', 'bootstrap-config.sh'):
            shutil.copy2(ROOT / 'collector' / filename, package / filename)
        (package / 'collector.jar').write_bytes(b'fixture')
        (package / 'lib/provider.jar').write_bytes(b'fixture')
        (package / 'conf/ca.pem').write_text('fixture public trust material')
        for filename in ('run-once.sh', 'poll.sh', 'launcher-common.sh'):
            executable(package / filename, '#!/bin/sh\necho ASSESSMENT_NO_JOB\n')
        executable(package / 'agent-tool.sh', r'''#!/bin/sh
set -eu
case "$1" in
 register)
  printf 'attempt\n' >> "$FIXTURE/registration-attempts"
  if test "${FIXTURE_AUTH_FAIL:-false}" = true; then echo 'ASSESSMENT_TOOL_FAILED=HTTP_403' >&2; exit 1; fi
  got_server=$(sed -n 's/^serverUrl=//p' "$2")
  got_code=$(sed -n 's/^enrollmentToken=//p' "$2")
  test "$got_server" = "$EXPECTED_SERVER" || exit 91
  test "$got_code" = "$EXPECTED_CODE" || exit 92
  printf 'hostId=fixture-persistent-host\nagentToken=fixture-operational-token\nserverUrl=%s\n' "$got_server" > "$3"
  chmod 600 "$3"
  echo ASSESSMENT_REGISTRATION=CONFIRMED
  ;;
 diagnose) test -f "$2"; echo ASSESSMENT_HEARTBEAT=CONFIRMED;;
 events) echo ASSESSMENT_INSTALL_EVENTS=CONFIRMED;;
 *) exit 94;;
esac
''')
        bootstrap(package)
        return path, package

    def bootstrap(package, server=SERVER, token=TOKEN, environment='env-fixture-one'):
        file = package / 'conf/bootstrap.properties'
        file.write_text(f'serverUrl={server}\nenrollmentToken={token}\nenvironmentId={environment}\n')
        file.chmod(0o644)  # Extraction can start permissive; parser must protect it.

    def run(path, package, args=(), success=True, expected_server=SERVER, expected_code=TOKEN, extra_env=None):
        env = dict(os.environ, PATH=str(bindir) + ':' + os.environ['PATH'], HOME=str(path / 'home'),
                   FIXTURE=str(path), EXPECTED_SERVER=expected_server, EXPECTED_CODE=expected_code)
        if extra_env:
            env.update(extra_env)
        process = subprocess.run(['/bin/sh', str(package / 'install.sh'), *args], env=env,
                                 stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, timeout=20)
        check((process.returncode == 0) == success, f'{path.name}: unexpected shell result {process.returncode}: {process.stdout}')
        for secret in (TOKEN, EXPLICIT):
            check(secret not in process.stdout, 'Credential leaked through stdout')
            logs = path / 'home/java-assessment/logs'
            if logs.exists():
                for log in logs.glob('*'):
                    if log.is_file():
                        check(secret not in log.read_text(errors='replace'), 'Credential leaked to installation log')
        return process.stdout

    path, package = prepare('unattended')
    run(path, package)  # zero arguments; no TTY, real scripts, fake network tool only.
    operational = path / 'home/java-assessment'
    check((package / 'conf/bootstrap.properties').stat().st_mode & 0o777 == 0o600, 'Bootstrap permissions must become 600')
    check(not (operational / 'conf/bootstrap.properties').exists(), 'Bootstrap copied to operational directory')
    check(not (operational / 'conf/enrollment.properties').exists(), 'Transient enrollment request was retained')
    check((path / 'cron.txt').exists(), 'Default installation did not install polling schedule')
    before = (operational / 'conf/collector.properties').read_bytes()
    bootstrap(package, server='https://other.example.invalid', token=EXPLICIT, environment='env-other')
    run(path, package)
    check(before == (operational / 'conf/collector.properties').read_bytes(), 'Reinstallation changed existing identity')
    check((path / 'registration-attempts').read_text().splitlines() == ['attempt'], 'Reinstall registered a second identity')
    cases += 2

    path, package = prepare('origin-mismatch')
    run(path, package, ['--server', 'https://other.example.invalid'], success=False)
    check(not (path / 'registration-attempts').exists(), 'Packaged credential was forwarded to a different origin')
    code = path / 'explicit-code'; code.write_text(EXPLICIT); code.chmod(0o600)
    run(path, package, ['--server', 'https://other.example.invalid', '--code-file', str(code)], expected_server='https://other.example.invalid', expected_code=EXPLICIT)
    cases += 2

    path, package = prepare('explicit-generic')
    (package / 'conf/bootstrap.properties').unlink()
    code = path / 'explicit-code'; code.write_text(EXPLICIT); code.chmod(0o600)
    run(path, package, ['--server', SERVER, '--code-file', str(code)], expected_code=EXPLICIT)
    cases += 1

    malformed = {
        'unknown': lambda p: p.write_text(p.read_text() + 'unexpected=value\n'),
        'duplicate': lambda p: p.write_text(p.read_text() + 'serverUrl=' + SERVER + '\n'),
        'oversized': lambda p: p.write_text('x' * 8193),
        'binary': lambda p: p.write_bytes(p.read_bytes() + b'\x00'),
        'escaped': lambda p: p.write_text(p.read_text().replace('https://', r'https\://')),
        'missing': lambda p: p.write_text('serverUrl=' + SERVER + '\n'),
        'injection': lambda p: p.write_text('serverUrl=' + SERVER + '\nenrollmentToken=$(touch ' + str(top / 'injected') + ')\nenvironmentId=env-test\n'),
    }
    for name, mutate in malformed.items():
        path, package = prepare('malformed-' + name)
        mutate(package / 'conf/bootstrap.properties')
        run(path, package, success=False)
        check(not (path / 'registration-attempts').exists(), 'Malformed package reached registration')
        cases += 1
    check(not (top / 'injected').exists(), 'Properties content was executed')

    for kind in ('symlink', 'hardlink'):
        path, package = prepare(kind)
        original = package / 'conf/bootstrap.properties'
        target = path / 'target'
        original.rename(target)
        if kind == 'symlink':
            original.symlink_to(target)
        else:
            os.link(target, original)
        run(path, package, success=False)
        check(not (path / 'registration-attempts').exists(), 'Linked bootstrap reached registration')
        cases += 1

    path, package = prepare('expired-package')
    output = run(path, package, success=False, extra_env={'FIXTURE_AUTH_FAIL': 'true'})
    check('download a new package from the environment' in output, 'Expired/revoked configured package lacks next step')
    check('Enrollment code:' not in output, 'Configured package fell back to an interactive prompt')
    cases += 1

    path, package = prepare('system-dispatch')
    # The dispatcher is real; the privileged preparation is replaced so the test
    # cannot create users or touch /opt. Root's RHEL staging E2E covers that part.
    executable(package / 'system-install.sh', '#!/bin/sh\nprintf "%s\\n" "$@" > "$FIXTURE/system-args"\n')
    run(path, package, ['--system'])
    forwarded = (path / 'system-args').read_text()
    check('--server' not in forwarded and TOKEN not in forwarded, 'System dispatch did not retain zero-argument bootstrap or exposed a code')
    run(path, package, ['--system', '--server', SERVER])
    check((path / 'system-args').read_text().splitlines() == ['--server', SERVER], 'Explicit system flags were not preserved')
    cases += 2

    path, package = prepare('java-build-path')
    java_path = '/opt/customer java/jdk-17.0.10+7/bin/java'
    run(path, package, ['--java', java_path])
    check((path / 'home/java-assessment/conf/java.path').read_text() == java_path + '\n', 'Java path with spaces/build suffix was changed')
    cases += 1
    for name, bad_java in (('java-relative', 'relative/bin/java'), ('java-linebreak', '/opt/java\n/bin/java')):
        path, package = prepare(name)
        run(path, package, ['--java', bad_java], success=False)
        check(not (path / 'registration-attempts').exists(), 'Invalid Java path reached registration')
        cases += 1

    print(f'INSTALLER_BOOTSTRAP_FIXTURES=PASS cases={cases} unattended reinstall precedence malformed links no-execution no-secret-logs system-dispatch')
