#!/usr/bin/env python3
"""Disposable rewritten paths/adapters; never touches installed units or /opt.

Exercises the real shell's child supervision, V3 fixed wrappers and idle path.
This is deliberately not evidence of Linux service-manager/reboot behaviour.
"""
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
checks = 0


def check(condition, message):
    global checks
    assert condition, message
    checks += 1


with tempfile.TemporaryDirectory(prefix='agent-bundle-shell-') as temporary:
    base = Path(temporary).resolve()
    root, home, bins = base / 'collector', base / 'helper', base / 'bin'
    code = root / 'releases/2.0.5-123456789abc'
    boot, state = home / 'bootstrap', home / 'state'
    for directory in (root / 'conf', root / 'lib', root / 'collector-spool', code / 'lib', boot / 'lib', state, bins):
        directory.mkdir(parents=True, mode=0o700)

    def put(path, value, mode=0o600):
        path.write_text(value)
        path.chmod(mode)

    put(bins / 'id', '#!/bin/sh\necho 0\n', 0o700)
    put(bins / 'uname', '#!/bin/sh\necho Linux\n', 0o700)
    put(bins / 'stat', f'''#!{sys.executable}
import os,sys
if sys.argv[2]=='%u': print('0')
elif sys.argv[2]=='%a': print(oct(os.stat(sys.argv[3]).st_mode & 0o777)[2:])
else: raise SystemExit(2)
''', 0o700)
    java = bins / 'fixture-java'
    put(java, f'''#!{sys.executable}
import json,signal,sys,time
from pathlib import Path
b=Path({str(base)!r})
(b/'arguments.json').write_text(json.dumps(sys.argv[1:]))
(b/'child.pid').write_text(str(__import__('os').getpid()))
mode=(b/'mode').read_text()
if mode=='hang':
 signal.signal(signal.SIGTERM,signal.SIG_IGN)
 while True: time.sleep(1)
if sys.argv[-1]=='code-path':
 print({str(code)!r}); raise SystemExit(0)
raise SystemExit(20 if mode=='bad' else 10 if mode=='network' else 0)
''', 0o700)
    trust = '#!/bin/sh\nif test "$1" = java; then printf "%s\\n" "$2"; fi\n'
    put(boot / 'root-trust.sh', trust, 0o700)
    put(root / 'root-trust.sh', trust, 0o700)
    put(root / 'conf/java.path', str(java) + '\n')
    put(home / 'scheduler', 'sysv\n')
    put(code / 'collector.jar', 'signed collector fixture')
    put(code / 'lib/current-tls.jar', 'current signed library')
    put(boot / 'anchor.jar', 'independent anchor fixture')
    put(boot / 'lib/original-tls.jar', 'immutable anchor dependency')
    put(base / 'mounts', '')
    put(base / 'mode', 'ok')
    source = (PROJECT / 'collector/update-bootstrap.sh').read_text()
    replacements = {
        'PATH=/usr/sbin:/usr/bin:/sbin:/bin': f'PATH={bins}:/usr/bin:/bin',
        '/opt/java-assessment-collector': str(root),
        '/opt/java-assessment-update': str(home),
        '/proc/mounts': str(base / 'mounts'),
        'INIT=$(readlink -f /etc/init.d)': 'INIT=' + str(base / 'init.d'),
        '"$elapsed" -ge 60': '"$elapsed" -ge 1',
    }
    for old, new in replacements.items():
        check(old in source, 'known disposable rewrite: ' + old)
        source = source.replace(old, new)
    script = boot / 'update-bootstrap.sh'
    put(script, source, 0o700)

    def run(*arguments, timeout=8):
        return subprocess.run(['/bin/sh', str(script), *arguments], capture_output=True, text=True, timeout=timeout)

    result = run('probe', code.name, '2.0.5', 'probe')
    check(result.returncode == 0, 'candidate probe succeeds: ' + result.stderr)
    arguments = json.loads((base / 'arguments.json').read_text())
    check('-Xmx96m' in arguments and '-Djava.security.egd=file:/dev/./urandom' in arguments, 'bounded Java6-safe runtime arguments')
    classpath = arguments[arguments.index('-cp') + 1]
    check(str(code / 'lib/current-tls.jar') in classpath and str(boot / 'lib/original-tls.jar') not in classpath, 'collector uses its own signed libraries')
    check(arguments[-3:] == ['assessment.AgentUpdateProbe', 'probe', '2.0.5'], 'exact probe entry point')

    check(run('probe', code.name, '2.0.5', 'heartbeat').returncode == 0, 'installed heartbeat succeeds')
    arguments = json.loads((base / 'arguments.json').read_text())
    check(arguments[-4:] == ['assessment.AgentUpdateProbe', 'heartbeat', '2.0.5', str(root / 'conf/collector.properties')], 'heartbeat reads only fixed protected configuration')
    put(base / 'mode', 'network')
    check(run('probe', code.name, '2.0.5', 'heartbeat').returncode == 10, 'inconclusive network code preserved')
    put(base / 'mode', 'bad')
    check(run('probe', code.name, '2.0.5', 'probe').returncode == 20, 'real probe failure preserved')
    put(base / 'mode', 'hang')
    check(run('probe', code.name, '2.0.5', 'heartbeat').returncode == 10, 'stuck heartbeat is inconclusive')
    pid = int((base / 'child.pid').read_text())
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        checks += 1
    else:
        raise AssertionError('Term-resistant probe was not killed/reaped')

    (base / 'arguments.json').unlink()
    put(base / 'mode', 'ok')
    for arguments in [('probe', '../../external', '2.0.5', 'probe'), ('probe', code.name, '2.0.5;touch', 'probe'), ('probe', code.name, '2.0.5', 'shell')]:
        check(run(*arguments).returncode != 0 and not (base / 'arguments.json').exists(), 'unsafe probe input rejected before JVM')
    check(run('once').returncode == 0 and not (base / 'arguments.json').exists(), 'idle recovery starts no JVM')

    operation = '00000000-0000-0000-0000-000000000003'
    result = run('activate-services', operation)
    check(result.returncode == 0, 'fixed wrappers installed: ' + result.stderr)
    check((root / 'conf/update-capability').read_text().strip() == 'update-v3', 'capability written only in activation action')
    for entry in ('poll.sh', 'run-once.sh', 'agent-tool.sh'):
        text = (root / entry).read_text()
        check('code-path' in text and 'exec /bin/sh "$CODE/' + entry + '" "$@"' in text, 'wrapper resolves validated active release: ' + entry)
    put(code / 'poll.sh', '#!/bin/sh\nprintf "executed\\n" > ' + shlex.quote(str(base / 'slot-called')) + '\n', 0o700)
    result = subprocess.run(['/bin/sh', str(root / 'poll.sh')], capture_output=True, text=True, timeout=8)
    check(result.returncode == 0 and (base / 'slot-called').read_text() == 'executed\n', 'fixed poll dispatches actual active slot')

    installer_source = base / 'installer-source'
    installer_source.mkdir()
    put(installer_source / 'lifecycle-lock.sh', 'printf "MUTATED\\n" > ' + shlex.quote(str(base / 'installer-mutation')) + '\n')
    installer = (PROJECT / 'collector/root-system-install.sh').read_text()
    installer = installer.replace('/opt/java-assessment-collector', str(root)).replace('/opt/java-assessment-update', str(home))
    installer = installer.replace('test "$(id -u)" = 0', 'test 0 = 0')
    installer_path = installer_source / 'root-system-install.sh'
    put(installer_path, installer, 0o700)
    for guarded in (root / 'current.json', state / 'transaction.json'):
        put(guarded, '{}\n')
        result = subprocess.run(['/bin/sh', str(installer_path)], capture_output=True, text=True, timeout=8)
        check(result.returncode != 0 and not (base / 'installer-mutation').exists(), 'V3 administrative installer refuses before locks or service mutation: ' + guarded.name)
        check(not (root / '.root-install-lock').exists(), 'V3 guard never creates administrative installation lock')
        guarded.unlink()

print(f'agent-bundle-service-test PASS {checks} checks: fixed probes/wrappers, timeout child reaping, no idle JVM, Java6 runtime flags')
print('VALIDATION_LIMIT=rewritten disposable paths and OS/Java adapters; Linux service/reboot/flock gates remain open')
