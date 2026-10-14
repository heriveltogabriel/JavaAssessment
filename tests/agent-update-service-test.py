#!/usr/bin/env python3
"""Probe child supervision in a disposable copy; no real services or privileged paths."""
import json
import os
import shlex
from pathlib import Path
import subprocess
import sys
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='agent-update-probe-') as temporary:
    base = Path(temporary).resolve(); helper = base / 'helper'; root = base / 'collector'; bins = base / 'bin'
    for p in (helper / 'lib', root, bins): p.mkdir(parents=True, mode=0o700)

    def put(p, text, mode=0o600): p.write_text(text); p.chmod(mode)

    put(bins / 'id', '#!/bin/sh\necho 0\n', 0o700)
    java = bins / 'fixture-java'
    put(java, f'''#!{sys.executable}
import json,os,signal,sys,time
from pathlib import Path
b=Path({str(base)!r});(b/'arguments.json').write_text(json.dumps(sys.argv[1:]));(b/'child.pid').write_text(str(os.getpid()))
mode=(b/'mode').read_text()
if mode=='hang':
 signal.signal(signal.SIGTERM,signal.SIG_IGN)
 while True:time.sleep(1)
sys.exit(20 if mode=='bad' else 0)
''', 0o700)
    put(helper / 'java.path', str(java) + '\n')
    put(helper / 'lib/tls.jar', 'protected library')
    source = (PROJECT / 'collector/update-service.sh').read_text()
    replacements = {
        'PATH=/usr/sbin:/usr/bin:/sbin:/bin': f'PATH={bins}:/usr/bin:/bin',
        '/opt/java-assessment-update': str(helper),
        '/opt/java-assessment-collector': str(root),
        '"$waited" -ge 60': '"$waited" -ge 1',
    }
    for old, new in replacements.items():
        assert old in source, old; source = source.replace(old, new)
    script = helper / 'update-service.sh'; put(script, source, 0o700)

    def run(location='candidate', version='1.2.3', operation='probe'):
        return subprocess.run(['/bin/sh', str(script), 'probe', location, version, operation], capture_output=True, text=True, timeout=6)

    put(base / 'mode', 'ok'); assert run().returncode == 0
    arguments = json.loads((base / 'arguments.json').read_text())
    assert '-Djava.security.egd=file:/dev/./urandom' in arguments
    assert arguments[-3:] == ['assessment.AgentUpdateProbe', 'probe', '1.2.3']
    assert any(value.startswith(str(helper / 'candidate.jar') + ':') for value in arguments)
    assert run('installed', operation='heartbeat').returncode == 0
    arguments = json.loads((base / 'arguments.json').read_text())
    assert arguments[-4:] == ['assessment.AgentUpdateProbe', 'heartbeat', '1.2.3', str(root / 'conf/collector.properties')]
    put(base / 'mode', 'bad'); assert run().returncode == 20
    put(base / 'mode', 'hang'); assert run().returncode == 20
    pid = int((base / 'child.pid').read_text())
    try: os.kill(pid, 0)
    except ProcessLookupError: pass
    else: raise AssertionError('Timed-out JVM remains alive')
    assert run('installed', operation='heartbeat').returncode == 10, 'Heartbeat timeout is inconclusive'
    heartbeat_pid = int((base / 'child.pid').read_text())
    try: os.kill(heartbeat_pid, 0)
    except ProcessLookupError: pass
    else: raise AssertionError('Timed-out heartbeat JVM remains alive')
    (base / 'arguments.json').unlink()
    for location, version, operation in (('../../etc/passwd', '1.2.3', 'probe'), ('candidate', '1.2.3;touch', 'probe'), ('candidate', '1.2.3', 'shell'), ('candidate', '1.2.3', 'heartbeat')):
        assert run(location, version, operation).returncode == 2
        assert not (base / 'arguments.json').exists()

    # No request/journal means no updater JVM. A durable journal must still resume.
    put(helper / 'root-trust.sh', '#!/bin/sh\nif test "$1" = java; then printf "%s\\n" "$2"; fi\n', 0o700)
    put(helper / 'lifecycle-lock.sh', ':\n', 0o700)
    put(helper / 'update-local.sh', '#!/bin/sh\nexit 0\n', 0o700)
    put(base / 'mode', 'ok')
    assert subprocess.run(['/bin/sh', str(script), 'once'], capture_output=True, timeout=5).returncode == 0
    assert not (base / 'arguments.json').exists(), 'idle updater spawned a JVM'
    put(helper / 'journal.json', '{}')
    assert subprocess.run(['/bin/sh', str(script), 'once'], capture_output=True, timeout=5).returncode == 0
    args = json.loads((base / 'arguments.json').read_text())
    assert args[-1] == 'assessment.AgentUpdateWorker' and '-Djava.security.egd=file:/dev/./urandom' in args
    (helper / 'journal.json').unlink()

    # Real SysV loop retains a trailing result after verbose warnings, and keeps it on idle cycles.
    put(java, f"""#!{sys.executable}
from pathlib import Path
h=Path({str(helper)!r})
print('warning '*1000)
print('AGENT_UPDATE_RETRY=NETWORK_UNCONFIRMED')
(h/'journal.json').unlink()
""", 0o700)
    put(helper / 'journal.json', '{}')
    put(bins / 'sleep', f'#!/bin/sh\n: > "{helper}/stop"\n', 0o700)
    assert subprocess.run(['/bin/sh', str(script), 'loop'], capture_output=True, timeout=5).returncode == 0
    saved = (helper / 'diagnostic.txt').read_bytes()
    assert len(saved) <= 4096 and saved.endswith(b'AGENT_UPDATE_RETRY=NETWORK_UNCONFIRMED\n')
    (helper / 'stop').unlink()
    assert subprocess.run(['/bin/sh', str(script), 'loop'], capture_output=True, timeout=5).returncode == 0
    assert (helper / 'diagnostic.txt').read_bytes() == saved, 'idle loop erased final diagnosis'
    (helper / 'stop').unlink()
    (bins / 'sleep').unlink()

    # SysV supervisor must not retain the installer's inherited lifecycle fd9.
    fd_probe = f'import os,pathlib; p=pathlib.Path({str(base / "child-lock-fd")!r}); p.write_text(str(os.path.exists("/dev/fd/9")))'
    put(helper / 'update-local.sh', f'''#!/bin/sh
{shlex.quote(sys.executable)} -c {shlex.quote(fd_probe)}
exit 1
''', 0o700)
    start = subprocess.run(['/bin/sh', '-c', 'exec 9>"$1"; exec /bin/sh "$2" start', 'fixture', str(base / 'lock'), str(script)], capture_output=True, text=True, timeout=5)
    assert start.returncode == 0, start.stderr
    import time
    for _ in range(100):
        if (base / 'child-lock-fd').exists(): break
        time.sleep(0.02)
    assert (base / 'child-lock-fd').read_text() == 'False'

print('agent-update-service-test PASS: fixed candidate/live paths, exact probe/heartbeat arguments, failure exit, TERM-resistant child KILL/reap, invalid input rejected, SysV inherited lock closed')
