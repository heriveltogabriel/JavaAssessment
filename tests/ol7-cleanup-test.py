"""Exercise operator cleanup with isolated paths and fake services, never /opt."""
from pathlib import Path
import tempfile,subprocess,shutil
source=Path('ops/limpar-agente-ol7.sh').read_text()
checks=0
for scenario in ('ok','pending','wrong-host','control-running','remove-failed','foreign-stage'):
 with tempfile.TemporaryDirectory(prefix='ol7-cleanup-test-') as directory:
  base=Path(directory);root=base/'collector';update=base/'update';helper=base/'uninstall';home=base/'home';bin=base/'bin';stage=base/'java-assessment-install.ABC123'
  for path in (root,update,helper,home,bin,stage/'package'):path.mkdir(parents=True)
  (helper/'v3-inventory.json').write_text('{}')
  (root/'lifecycle-lock.sh').write_text(': # isolated lock fixture\n')
  (root/'conf').mkdir();(root/'conf/installation-id.lock').write_text('')
  (root/'logs').mkdir();(root/'logs/permission-check.json').write_text('{}')
  (helper/'root-trust.sh').write_text('#!/bin/sh\nexit 0\n')
  (helper/'uninstall-local.sh').write_text('''#!/bin/sh
set -eu
printf '%s\\n' "$1" >> 'EVENTS'
case "$1" in
remove) FAIL rm -rf 'ROOT' 'UPDATE';;
cleanup) rm -rf 'HELPER';;
*) exit 1;;
esac
'''.replace('EVENTS',str(base/'events')).replace('FAIL','exit 1; ' if scenario=='remove-failed' else '').replace('ROOT',str(root)).replace('UPDATE',str(update)).replace('HELPER',str(helper)))
  (stage/'agent.zip').write_text('fixture');(stage/'package/collector.jar').write_text('fixture');(stage/'package/SHA256SUMS').write_text('fixture')
  (home/'install.sh').write_text('# /tmp/java-assessment-install. /api/install/package\n')
  if scenario=='pending':(update/'journal.json').write_text('{}')
  if scenario=='foreign-stage':(stage/'keep.txt').write_text('preserve')
  for name,script in {'id':"printf '0\\n'",'uname':"printf 'Linux\\n'",'hostname':"printf '%s\\n' "+('other' if scenario=='wrong-host' else 'ol07'),'stat':"case \"$2\" in %u) printf '0\\n';; %h) printf '1\\n';; *) exit 1;; esac",'systemctl':'''case "$1" in show) printf 'LoadState=loaded\\nActiveState=failed\\nMainPID=0\\nControlPID=CONTROL\\nJob=\\n';; esac'''.replace('CONTROL','12' if scenario=='control-running' else '0'),'awk':'''# Use the real awk for service-state input; no simulated mounts.
case "$*" in */proc/mounts*) exit 0;; esac
exec /usr/bin/awk "$@"''','rm':'''# macOS lacks GNU --one-file-system; only isolated test paths reach here.
if test "$1" = -rf && test "$2" = --one-file-system; then shift 2; fi
exec /bin/rm -rf "$@"'''} .items():
   p=bin/name;p.write_text('#!/bin/sh\n'+script+'\n');p.chmod(0o700)
  text=source.replace('PATH=/usr/sbin:/usr/bin:/sbin:/bin','PATH='+str(bin)+':/usr/bin:/bin').replace('/opt/java-assessment-collector',str(root)).replace('/opt/java-assessment-update',str(update)).replace('/opt/java-assessment-uninstall',str(helper)).replace('/home/opc',str(home)).replace('/var/tmp/java-assessment-cleanup.XXXXXX',str(base/'backup.XXXXXX')).replace('/tmp/java-assessment-install.??????',str(base/'java-assessment-install.??????'))
  script=base/'cleanup.sh';script.write_text(text)
  result=subprocess.run(['/bin/sh',str(script)],capture_output=True,text=True)
  events=(base/'events').read_text() if (base/'events').exists() else ''
  if scenario=='ok':
   assert result.returncode==0,result.stderr
   assert events=='remove\ncleanup\n' and not any(p.exists() for p in (root,update,helper,stage,home/'install.sh'))
   assert 'LIMPEZA_LOCAL_OK' in result.stdout
   backup=next(base.glob('backup.*'));assert (backup/'installation-id.lock').is_file() and (backup/'permission-check.json').read_text()=='{}'
  else:
   assert result.returncode!=0,scenario
   if scenario in ('pending','wrong-host','control-running'):assert events=='' and root.exists() and stage.exists()
   if scenario=='remove-failed':assert events=='remove\n' and root.exists() and helper.exists()
   if scenario=='foreign-stage':assert (stage/'keep.txt').exists() and (home/'install.sh').exists()
  checks+=1
print('OL7_CLEANUP_FIXTURES=PASS',checks,'isolated success/refusal scenarios; no real agent removed')
