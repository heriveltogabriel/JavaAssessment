#!/usr/bin/env python3
"""Restricted remover fixtures. NEVER executes the original or uses real /opt/etc."""
import os,shutil,subprocess,tempfile,json
from pathlib import Path
PROJECT=Path(__file__).resolve().parents[1]
SCRIPT=(PROJECT/'collector/uninstall-local.sh').read_text()
checks=[]
with tempfile.TemporaryDirectory(prefix='uninstall-filesystem-') as td:
 base=Path(td); bins=base/'bin';bins.mkdir()
 def put(p,text,mode=0o600):
  p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);p.chmod(mode)
 put(bins/'id','#!/bin/sh\necho 0\n',0o700)
 put(bins/'uname','#!/bin/sh\necho Linux\n',0o700)
 put(bins/'stat','''#!/usr/bin/env python3
import subprocess,sys
fmt={'%u':'%u','%a':'%Lp','%h':'%l'}[sys.argv[2]]
if sys.argv[2]=='%u':print(0)
else: print(subprocess.check_output(['/usr/bin/stat','-f',fmt,sys.argv[3]],text=True).strip())
''',0o700)
 put(bins/'getent','#!/bin/sh\nprintf "root:x:0:0:root:/root:/bin/sh\\napp:x:501:501:app:/app:/bin/sh\\n"\n',0o700)
 put(bins/'crontab',f'''#!/bin/sh
if test -f '{base}/cron-present';then echo '* * * * * /opt/java-assessment-collector/poll.sh';exit 0;fi
echo 'no crontab for account' >&2;exit 1
''',0o700)
 put(bins/'systemctl',f'''#!/usr/bin/env python3
import sys
from pathlib import Path
b=Path({str(base)!r});a=sys.argv[1:]
if a[0]=='show':
 if a[-1]=='DropInPaths':print('DropInPaths='+('/evil' if (b/'dropin').exists() else ''))
 else:print('LoadState='+('loaded' if (b/'systemd'/a[1]).exists() else 'not-found'))
elif a[0]=='is-active':sys.exit(1)
elif a[0]=='daemon-reload' and (b/'reload-fail').exists():sys.exit(1)
''',0o700)
 put(bins/'chkconfig','#!/bin/sh\nexit 0\n',0o700)
 put(base/'mounts','')
 source=SCRIPT.replace('PATH=/usr/sbin:/usr/bin:/sbin:/bin',f'PATH={bins}:/usr/bin:/bin').replace('ROOT=/opt/java-assessment-collector',f'ROOT={base}/collector').replace('HELPER=/opt/java-assessment-uninstall',f'HELPER={base}/helper').replace('SYSTEMD=/etc/systemd/system',f'SYSTEMD={base}/systemd').replace('INIT=$(readlink -f /etc/init.d)',f'INIT={base}/init').replace('CRON=/etc/cron.d',f'CRON={base}/cron').replace('case "$INIT" in /etc/init.d|/etc/rc.d/init.d)',f'case "$INIT" in {base}/init)').replace('while test "$path" != /;',f'while test "$path" != {base};').replace('! -uid 0',f'! -uid {os.getuid()}').replace('/proc/mounts',str(base/'mounts')).replace('/var/log/.java-assessment-uninstall.XXXXXX',str(base/'.diagnostic.XXXXXX')).replace('/var/log/java-assessment-uninstall-last.txt',str(base/'last.txt')).replace('/etc/rc[0-6].d/',str(base)+'/rc[0-6].d/').replace('/etc/rc.d/rc[0-6].d/',str(base)+'/rc.d/rc[0-6].d/')
 source=source.replace('/opt/java-assessment-update',str(base/'updater'))
 script=base/'executor.sh';put(script,source,0o700)
 root=base/'collector';helper=base/'helper';systemd=base/'systemd';init=base/'init';cron=base/'cron'
 for p in (systemd,init,cron):p.mkdir(mode=0o700)
 put(base/'apps/order-app.java','preserve applications',0o640);put(base/'jvm/bin/java','preserve installed JVM',0o750);put(base/'dashboard/server.jar','preserve dashboard',0o600)
 before={str(p.relative_to(base)):(p.read_bytes(),p.stat().st_mode) for folder in ('apps','jvm','dashboard') for p in (base/folder).rglob('*') if p.is_file()}
 app=subprocess.Popen(['/bin/sleep','120'])
 def setup(mode='systemd'):
  for p in (root,helper):
   if p.exists():shutil.rmtree(p)
  for p in systemd.iterdir():p.unlink()
  for p in init.iterdir():p.unlink()
  for flag in ('cron-present','dropin','reload-fail'): (base/flag).unlink(missing_ok=True)
  for p in (root,helper):p.mkdir(mode=0o700)
  put(root/'conf/collector.properties','hostId=host-fixture\nagentToken=fixture-private-secret\n');put(root/'collector.jar','collector');put(root/'logs/collector.log','own logs');put(helper/'identity.properties','hostId=host-fixture\nagentToken=fixture-private-secret\n');put(helper/'scheduler',mode+'\n');put(helper/'owned-files','collector.jar\nconf/collector.properties\nroot-service.sh\n')
  # Advisory locking is exercised with a real disposable inode in
  # agent-update-local-test.py; this remover fixture isolates that boundary.
  put(helper/'lifecycle-lock.sh','#!/bin/sh\n:\n')
  if mode=='systemd':
   for unit in ('service','timer'):put(systemd/f'java-assessment-collector.{unit}','exact-owned-unit\n');put(helper/f'collector.{unit}','exact-owned-unit\n')
  else:
   put(root/'root-service.sh','#!/bin/sh\nexit 0\n');put(init/'java-assessment-collector','owned-sysv\n');put(helper/'collector.init','owned-sysv\n')
 def run(ok=True,action='remove'):
  p=subprocess.run(['/bin/sh',str(script),action],capture_output=True,text=True,timeout=10)
  assert (p.returncode==0)==ok,(p.returncode,p.stdout,p.stderr)
  assert 'fixture-private-secret' not in p.stdout+p.stderr
  return p
 try:
  for mode in ('systemd','sysv'):
   setup(mode);run();assert not root.exists();assert not (init/'java-assessment-collector').exists();assert not (systemd/'java-assessment-collector.service').exists();assert not (systemd/'java-assessment-collector.timer').exists();run();checks.append(mode+' removal/repeat')
  setup();put(base/'reload-fail','1');run(False);assert not root.exists();(base/'reload-fail').unlink();run();checks.append('resume after files/units removed before reload')
  setup();put(base/'updater/helper.service','owned update executor');put(systemd/'java-assessment-update.service','owned update executor');put(base/'updater/helper.jar','independent recovery code');run();assert not (base/'updater').exists();assert not (systemd/'java-assessment-update.service').exists();checks.append('idle updater removed with collector')
  setup();put(base/'updater/journal.json','{}');run(False);assert root.exists();shutil.rmtree(base/'updater');checks.append('active update blocks uninstall')
  for kind in ('unknown','dashboard','symlink','hardlink','special','cron','override','unit-changed','host-changed','backup','mount'):
   setup()
   if kind=='unknown':put(root/'application.jar','application')
   elif kind=='dashboard':put(root/'conf/server.properties','dashboard')
   elif kind=='symlink':(root/'logs/collector.log').unlink();(root/'logs/collector.log').symlink_to(base/'apps/order-app.java')
   elif kind=='hardlink':os.link(base/'apps/order-app.java',root/'logs/linked.log')
   elif kind=='special':os.mkfifo(root/'logs/collector.log.pipe')
   elif kind=='cron':put(base/'cron-present','1')
   elif kind=='override':put(base/'dropin','1')
   elif kind=='unit-changed':put(systemd/'java-assessment-collector.service','different-unit')
   elif kind=='host-changed':put(root/'conf/collector.properties','hostId=host-other\n')
   elif kind=='backup':put(root/'backups/unknown-data','preserve backup for inspection')
   elif kind=='mount':put(base/'mounts',f'dev {root}/logs ext4 rw 0 0\n')
   run(False);assert (root/'collector.jar').exists();checks.append(kind+' refused')
   if kind=='mount':put(base/'mounts','')
  # V3 Java inventory is tested against real signed ZIPs separately. This
  # transplanted shell fixture verifies only fixed service/removal ordering.
  for mode in ('systemd','sysv'):
   setup(mode);updater=base/'updater'
   if updater.exists():shutil.rmtree(updater)
   put(helper/'v3-inventory.json','{"schema":1}\n')
   fake_java=helper/'fixture-java'
   put(helper/'java.path',str(fake_java)+'\n')
   put(helper/'root-trust.sh','#!/bin/sh\nif test "$1" = java;then printf "%s\\n" "$2";fi\nexit 0\n',0o700)
   put(fake_java,f'''#!/bin/sh
for last do :;done
case "$last" in
 check) test ! -f '{base}/v3-check-fail';;
 remove) rm -rf '{root}' '{updater}';;
 check-helper) exit 0;;
 *) exit 2;;
esac
''',0o700)
   if mode=='systemd':
    put(updater/'bootstrap/recovery.service','owned V3 recovery\n');put(systemd/'java-assessment-recovery.service','owned V3 recovery\n')
    put(updater/'helper.service','owned legacy update\n');put(systemd/'java-assessment-update.service','owned legacy update\n')
   else:
    put(updater/'bootstrap/recovery.init','owned V3 recovery init\n');put(init/'java-assessment-recovery','owned V3 recovery init\n')
    put(updater/'bootstrap/update-bootstrap.sh','#!/bin/sh\nexit 0\n',0o700)
    put(updater/'helper.init','owned legacy update init\n');put(init/'java-assessment-update','owned legacy update init\n')
    put(updater/'update-service.sh','#!/bin/sh\nexit 0\n',0o700)
   put(base/'v3-check-fail','1');run(False);assert root.exists() and updater.exists();(base/'v3-check-fail').unlink()
   run();assert not root.exists() and not updater.exists();assert not (systemd/'java-assessment-recovery.service').exists() and not (init/'java-assessment-recovery').exists();run()
   checks.append(mode+' V3 signed-inventory boundary, recovery/legacy updater services and repeat')
  assert app.poll() is None
  after={str(p.relative_to(base)):(p.read_bytes(),p.stat().st_mode) for folder in ('apps','jvm','dashboard') for p in (base/folder).rglob('*') if p.is_file()}
  assert before==after
  print('uninstall-local-test PASS: '+', '.join(checks)+', application process/files/modes and JVM/dashboard preserved.')
 finally:app.terminate();app.wait()
