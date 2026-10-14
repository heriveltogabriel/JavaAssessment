#!/usr/bin/env python3
"""Independent updater installation against disposable service/filesystem adapters."""
import shutil
import subprocess
import tempfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='agent-update-installer-') as temporary:
    base = Path(temporary)
    root, helper, units, init, bins = [base / n for n in ('collector', 'helper', 'units', 'init', 'bin')]
    for directory in (root, units, init, bins): directory.mkdir(mode=0o700)

    def put(path, text, mode=0o600):
        path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text); path.chmod(mode)

    for name in ('id', 'systemctl', 'chkconfig', 'flock'):
        put(bins / name, '#!/bin/sh\n' + ('echo 0\n' if name == 'id' else 'exit 0\n'), 0o700)
    put(bins / 'stat', '''#!/usr/bin/env python3
import os,sys
s=os.lstat(sys.argv[3]);print({'%u':'0','%a':format(s.st_mode&0o7777,'o'),'%h':str(s.st_nlink)}[sys.argv[2]])
''', 0o700)
    source = (PROJECT / 'collector/update-install.sh').read_text()
    for old, new in {
        'PATH=/usr/sbin:/usr/bin:/sbin:/bin': f'PATH={bins}:/usr/bin:/bin',
        'ROOT=/opt/java-assessment-collector': f'ROOT={root}',
        '/opt/java-assessment-update': str(helper),
        'SYSTEMD=/etc/systemd/system': f'SYSTEMD={units}',
        'INIT=$(readlink -f /etc/init.d)': f'INIT={init}',
        'case "$INIT" in /etc/init.d|/etc/rc.d/init.d)': f'case "$INIT" in {init})',
        'protected_parent /opt': f'protected_parent {base}',
        'while test "$path" != /;': f'while test "$path" != {base};',
    }.items():
        assert old in source, old
        source = source.replace(old, new)
    script = base / 'install-fixture.sh'; put(script, source, 0o700)
    for name in ('collector.jar', 'update-local.sh', 'update-public.der', 'lifecycle-lock.sh'):
        put(root / name, 'fixture ' + name)
    put(root / 'root-trust.sh', '#!/bin/sh\nexit 0\n')
    put(root / 'update-service.sh', '#!/bin/sh\nexit 0\n')
    put(root / 'conf/java.path', '/protected/installed/java/bin/java\n')
    put(root / 'lib/tls.jar', 'fixture protected TLS library')
    put(root / 'conf/collector.properties', 'hostId=fixture-host\nagentToken=private-fixture-token\n')
    for unit in ('service', 'timer'):
        put(units / ('java-assessment-collector.' + unit), 'owned ' + unit)
    put(init / 'java-assessment-collector', 'owned sysv')

    def run(mode, ok=True):
        result = subprocess.run(['/bin/sh', str(script), mode], capture_output=True, text=True, timeout=10)
        assert (result.returncode == 0) == ok, (result.returncode, result.stdout, result.stderr)
        assert 'private-fixture-token' not in result.stdout + result.stderr

    for mode in ('systemd', 'sysv'):
        if helper.exists(): shutil.rmtree(helper)
        run(mode)
        assert (helper / 'helper.jar').read_bytes() == (root / 'collector.jar').read_bytes()
        assert (helper / 'helper.jar').stat().st_mode & 0o777 == 0o600
        assert (helper / 'update-public.der').read_bytes() == (root / 'update-public.der').read_bytes()
        assert (helper / 'java.path').read_bytes() == (root / 'conf/java.path').read_bytes()
        assert not (helper / 'identity.properties').exists()
        assert (root / 'conf/update-capability').read_text() == 'update-v2\n'
        assert (helper / 'scheduler').read_text() == mode + '\n'
        expected = units / 'java-assessment-update.service' if mode == 'systemd' else init / 'java-assessment-update'
        assert expected.exists()
        put(helper / 'journal.json', '{"phase":"CONFIRMING"}')
        run(mode, False)
        assert (helper / 'journal.json').exists()
        (helper / 'journal.json').unlink(); run(mode)
        put(root / 'collector-spool/update-request.json', '{}'); run(mode, False)
        (root / 'collector-spool/update-request.json').unlink()
    assert 'private-fixture-token' in (root / 'conf/collector.properties').read_text()

print('agent-update-install-test PASS: systemd/SysV, independent helper, pin, protected JVM/code, capability, no credential copy, pending/retry guards, identity preserved')
