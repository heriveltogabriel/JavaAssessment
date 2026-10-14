#!/usr/bin/env python3
"""Disposable copies with fixed paths/service commands rewritten ONLY in this test."""
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
checks = []

with tempfile.TemporaryDirectory(prefix='agent-update-local-') as temporary:
    base = Path(temporary).resolve()
    bins, root, helper = (base / name for name in ('bin', 'collector', 'helper'))
    systemd, init = base / 'systemd', base / 'init'
    for path in (bins, systemd, init):
        path.mkdir(mode=0o700)

    def put(path, text, mode=0o600):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        path.chmod(mode)

    put(bins / 'id', '#!/bin/sh\necho 0\n', 0o700)
    put(bins / 'uname', '#!/bin/sh\necho Linux\n', 0o700)
    put(bins / 'stat', '''#!/usr/bin/env python3
import subprocess,sys
fmt={'%u':'%u','%a':'%Lp','%h':'%l'}[sys.argv[2]]
if sys.argv[2]=='%u':print(0)
else: print(subprocess.check_output(['/usr/bin/stat','-f',fmt,sys.argv[3]],text=True).strip())
''', 0o700)
    put(bins / 'sync', '#!/bin/sh\nexit 0\n', 0o700)
    put(bins / 'systemctl', f'''#!/usr/bin/env python3
import json,sys
from pathlib import Path
b=Path({str(base)!r});a=sys.argv[1:]
state=json.loads((b/'state.json').read_text())
with (b/'service-events').open('a') as out:out.write(' '.join(a)+'\\n')
if a[0]=='show':
 if a[-1]=='DropInPaths':print('DropInPaths='+('/evil.conf' if (b/'dropin').exists() else ''))
 elif a[-1]=='MainPID':print('MainPID='+('99' if (b/'pid-remains').exists() else '0'))
elif a[0] in ('stop','start'):
 if (b/'service-failure').exists():sys.exit(1)
 state[a[1]]=a[0]=='start';(b/'state.json').write_text(json.dumps(state))
elif a[0]=='is-active':sys.exit(0 if state.get(a[-1]) else 3)
''', 0o700)
    put(base / 'mounts', '')
    source = (PROJECT / 'collector/update-local.sh').read_text()
    replacements = {
        'PATH=/usr/sbin:/usr/bin:/sbin:/bin': f'PATH={bins}:/usr/bin:/bin',
        'ROOT=/opt/java-assessment-collector': f'ROOT={root}',
        'HELPER=/opt/java-assessment-update': f'HELPER={helper}',
        'SYSTEMD=/etc/systemd/system': f'SYSTEMD={systemd}',
        'INIT=$(readlink -f /etc/init.d)': f'INIT={init}',
        'while test "$path" != /;': f'while test "$path" != {base};',
        '! -uid 0': f'! -uid {os.getuid()}',
        '/proc/mounts': str(base / 'mounts'),
        'case "$INIT" in /etc/init.d|/etc/rc.d/init.d)': f'case "$INIT" in {init})',
        '/opt/java-assessment-uninstall': str(base / 'uninstall'),
    }
    for old, new in replacements.items():
        assert old in source, old
        source = source.replace(old, new)
    script = base / 'update-local-fixture.sh'
    put(script, source, 0o700)
    put(base / 'apps/customer.jar', 'application unchanged', 0o640)
    before = (base / 'apps/customer.jar').read_bytes()

    def setup(mode='systemd'):
        for path in (root, helper, base / 'uninstall'):
            if path.exists(): shutil.rmtree(path)
        for folder in (systemd, init):
            for path in folder.iterdir(): path.unlink()
        for flag in ('dropin', 'pid-remains', 'service-failure'):
            (base / flag).unlink(missing_ok=True)
        put(base / 'mounts', '')
        root.mkdir(mode=0o700); helper.mkdir(mode=0o700)
        put(root / 'conf/collector.properties', 'hostId=fixture\nagentToken=private-test-token\n')
        put(root / 'conf/java.path', '/protected/jvm/bin/java\n')
        put(root / 'conf/root-service-mode', 'system-root\n')
        put(root / 'conf/update-capability', 'update-v2\n')
        put(root / 'collector.jar', 'unchanged by local service adapter')
        (root / 'collector-spool').mkdir(mode=0o700)
        put(helper / 'java.path', '/protected/jvm/bin/java\n')
        put(helper / 'scheduler', mode + '\n')
        for name in ('update-local.sh', 'update-service.sh', 'root-trust.sh', 'lifecycle-lock.sh', 'update-public.der', 'lib/tls.jar'):
            put(root / name, 'stable ' + name); put(helper / name, 'stable ' + name)
        if mode == 'systemd':
            for unit in ('service', 'timer'):
                put(systemd / ('java-assessment-collector.' + unit), 'owned ' + unit)
                put(helper / ('collector.' + unit), 'owned ' + unit)
        else:
            put(init / 'java-assessment-collector', 'owned sysv')
            put(helper / 'collector.init', 'owned sysv')
            put(root / 'root-service.sh', f'''#!/bin/sh
printf 'sysv %s\\n' "$1" >> '{base}/service-events'
exit 0
''', 0o700)
        put(base / 'state.json', json.dumps({'java-assessment-collector.timer': True, 'java-assessment-collector.service': False}))
        put(base / 'service-events', '')

    def run(action='check', ok=True, selected=None):
        result = subprocess.run(['/bin/sh', str(selected or script), action], capture_output=True, text=True, timeout=10)
        assert (result.returncode == 0) == ok, (action, result.returncode, result.stdout, result.stderr)
        assert 'private-test-token' not in result.stdout + result.stderr
        assert (base / 'apps/customer.jar').read_bytes() == before
        assert (root / 'collector.jar').read_text() == 'unchanged by local service adapter'
        return result

    for mode in ('systemd', 'sysv'):
        setup(mode); run(); run('pause'); run('start')
        events = (base / 'service-events').read_text()
        assert ('stop java-assessment-collector.timer' if mode == 'systemd' else 'sysv stop') in events
        assert ('start java-assessment-collector.timer' if mode == 'systemd' else 'sysv start') in events
        checks.append(mode + ' protected scheduler pause/resume')
    for kind in ('symlink', 'hardlink', 'writable', 'mount', 'override', 'unit-changed', 'helper-changed', 'key-changed', 'private-mode', 'wrong-mode', 'uninstall', 'special', 'java-changed'):
        setup()
        if kind == 'symlink': (root / 'alias').symlink_to(base / 'apps')
        elif kind == 'hardlink': os.link(base / 'apps/customer.jar', root / 'application.jar')
        elif kind == 'writable': (root / 'lib').chmod(0o777)
        elif kind == 'mount': put(base / 'mounts', f'dev {root}/lib ext4 rw 0 0\n')
        elif kind == 'override': put(base / 'dropin', 'yes')
        elif kind == 'unit-changed': put(systemd / 'java-assessment-collector.service', 'unrelated unit')
        elif kind == 'helper-changed': put(helper / 'update-local.sh', 'changed')
        elif kind == 'key-changed': put(helper / 'update-public.der', 'different pin')
        elif kind == 'private-mode': (root / 'conf/collector.properties').chmod(0o644)
        elif kind == 'wrong-mode': put(root / 'conf/root-service-mode', 'manual\n')
        elif kind == 'uninstall': put(base / 'uninstall/claim.json', '{}')
        elif kind == 'special': os.mkfifo(root / 'pipe')
        elif kind == 'java-changed': put(helper / 'java.path', '/other/java\n')
        run(ok=False); checks.append(kind + ' rejected')
    setup(); put(base / 'pid-remains', '1'); run('pause', False); checks.append('remaining collector PID rejected')
    setup(); put(base / 'service-failure', '1'); run('pause', False); run('start', False); checks.append('failed service operations rejected')
    setup(); put(root / 'collector-spool/.poll-lock/pid', '999999999\n'); run('pause'); assert not (root / 'collector-spool/.poll-lock').exists(); checks.append('stale poll lock recovered')
    setup(); put(root / 'collector-spool/.poll-lock/pid', str(os.getpid()) + '\n')
    bounded = base / 'bounded-fixture.sh'; put(bounded, source.replace('"$waited" -lt 1500', '"$waited" -lt 0'), 0o700)
    run('pause', False, bounded); checks.append('active collector cycle not killed')

    # Test the real advisory-lock semantics on disposable inode/descriptor9.
    put(bins / 'flock', '''#!/usr/bin/env python3
import fcntl,sys
try: fcntl.flock(int(sys.argv[-1]),fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError:sys.exit(1)
''', 0o700)
    lock_source = (PROJECT / 'collector/lifecycle-lock.sh').read_text().replace('/opt/java-assessment-lifecycle.lock', str(base / 'lifecycle.lock')).replace('/opt', str(base))
    lock_script = base / 'lock-fixture.sh'
    put(lock_script, '#!/bin/sh\nPATH=' + str(bins) + ':/usr/bin:/bin;export PATH\n' + lock_source + '\necho LOCKED\n', 0o700)
    result = subprocess.run(['/bin/sh', str(lock_script)], capture_output=True, text=True)
    assert result.returncode == 0 and 'LOCKED' in result.stdout, result.stderr
    with (base / 'lifecycle.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        result = subprocess.run(['/bin/sh', str(lock_script)], capture_output=True, text=True)
        assert result.returncode != 0 and 'LOCKED' not in result.stdout
    checks.append('concurrent lifecycle operation refused')
    (base / 'lifecycle.lock').unlink(); (base / 'lifecycle.lock').symlink_to(base / 'apps/customer.jar')
    result = subprocess.run(['/bin/sh', str(lock_script)], capture_output=True, text=True)
    assert result.returncode != 0 and (base / 'apps/customer.jar').read_bytes() == before
    checks.append('lifecycle lock symlink refused')

print('agent-update-local-test PASS: ' + str(len(checks)) + ' scenarios; ' + ', '.join(checks))
