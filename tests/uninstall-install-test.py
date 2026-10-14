#!/usr/bin/env python3
"""Independent helper installation fixtures; all target paths are transplanted."""
import subprocess,tempfile,shutil
from pathlib import Path
R=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='uninstall-installer-') as td:
 b=Path(td);root=b/'collector';helper=b/'helper';units=b/'units';init=b/'init';bins=b/'bin'
 def put(p,s,mode=0o600):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s);p.chmod(mode)
 for d in (root,units,init,bins):d.mkdir(mode=0o700)
 put(bins/'id','#!/bin/sh\necho 0\n',0o700);put(bins/'systemctl','#!/bin/sh\nexit 0\n',0o700);put(bins/'chkconfig','#!/bin/sh\nexit 0\n',0o700)
 source=(R/'collector/uninstall-install.sh').read_text().replace('PATH=/usr/sbin:/usr/bin:/sbin:/bin',f'PATH={bins}:/usr/bin:/bin').replace('ROOT=/opt/java-assessment-collector',f'ROOT={root}').replace('/opt/java-assessment-uninstall',str(helper)).replace('SYSTEMD=/etc/systemd/system',f'SYSTEMD={units}').replace('INIT=$(readlink -f /etc/init.d)',f'INIT={init}').replace('case "$INIT" in /etc/init.d|/etc/rc.d/init.d)',f'case "$INIT" in {init})')
 script=b/'install.sh';put(script,source,0o700)
 for name in ('collector.jar','uninstall-local.sh','lifecycle-lock.sh'):put(root/name,'fixture code\n')
 put(root/'root-trust.sh','#!/bin/sh\nexit 0\n');put(root/'uninstall-service.sh','#!/bin/sh\nexit 0\n');put(root/'conf/java.path','/already-installed/jvm/bin/java\n');put(root/'lib/bctls-fixture.jar','fixture library\n');put(root/'conf/collector.properties','hostId=host-fixture\nagentToken=fixture-secret\n')
 service='owned-service\nProtectSystem=full\n';put(units/'java-assessment-collector.service',service);put(units/'java-assessment-collector.timer','owned-timer\n');put(init/'java-assessment-collector','owned-sysv\n')
 def run(mode,ok=True):
  p=subprocess.run(['/bin/sh',str(script),mode],capture_output=True,text=True,timeout=10);assert (p.returncode==0)==ok,(p.returncode,p.stdout,p.stderr);assert 'fixture-secret' not in p.stdout+p.stderr
 run('systemd');assert (root/'conf/uninstall-capability').read_text()=='uninstall-v1\n';assert (units/'java-assessment-collector.service').read_text()==service;assert 'NoNewPrivileges=true' in (units/'java-assessment-uninstall.service').read_text();assert (helper/'java.path').read_bytes()==(root/'conf/java.path').read_bytes();assert (helper/'collector.service').read_text()==service;assert not (helper/'identity.properties').exists();assert 'uninstall-local.sh' in (helper/'owned-files').read_text();assert (helper/'collector.jar').stat().st_mode&0o777==0o600
 put(helper/'request.json','{}');run('systemd',False);assert (helper/'request.json').exists();(helper/'request.json').unlink();run('systemd')
 shutil.rmtree(helper);(units/'java-assessment-uninstall.service').unlink();run('sysv');assert (init/'java-assessment-uninstall').exists();assert (helper/'collector.init').read_bytes()==(init/'java-assessment-collector').read_bytes();assert (root/'conf/collector.properties').read_text()=='hostId=host-fixture\nagentToken=fixture-secret\n'
 print('uninstall-install-test PASS: independent systemd/SysV service, capability, protected files, same validated JVM, no idle operational token, preserves collector isolation/identity, pending request refuses overwrite, reinstallation.')
