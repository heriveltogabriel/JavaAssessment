"""OS boundaries for running the shipped POSIX orchestrator locally, without root.
Linux stat/find/systemctl are simulated; actual shell control flow, copies,
identity validation, archive hash checks and state decisions are executed.
"""
import hashlib
import json
import os
import re
from pathlib import Path
import shlex
import sys

REPO = Path(__file__).resolve().parents[1]


class RecoveryFixture:
    def __init__(self, base):
        self.base = Path(base)
        self.opt = self.base / 'opt'
        self.root = self.opt / 'java-assessment-collector'
        self.update = self.opt / 'java-assessment-update'
        self.helper = self.opt / 'java-assessment-uninstall'
        self.units = self.base / 'etc/systemd/system'
        self.init = self.base / 'etc/init.d'
        self.bin = self.base / 'bin'
        self.source = self.base / 'tmp/java-assessment-ansible.fixture/package'
        for p in (self.opt, self.units, self.init, self.bin, self.source, self.base / 'proc', self.base / 'run/systemd/system'):
            p.mkdir(parents=True, exist_ok=True)
        (self.base / 'proc/mounts').write_text('')
        self.services = self.base / 'services.json'
        self.services.write_text('{}')
        self.write_tools()
        source = (REPO / 'install/ansible/files/manage-agent.sh').read_text()
        # Replace only OS paths/privilege boundaries, not decision branches.
        source = re.sub(r'/run/systemd/system|/opt|/etc|/proc|/tmp', lambda m: str(self.base / m[0].lstrip('/')), source)
        source = source.replace('PATH=/usr/sbin:/usr/bin:/sbin:/bin;', 'PATH=' + shlex.quote(str(self.bin)) + ':/usr/bin:/bin;')
        self.script = self.base / 'manage-agent.sh'
        source = source.replace("set -eu\n", "set -eu\nSUDO_UID=1000; export SUDO_UID\n", 1)
        self.script.write_text(source)
        self.env = dict(os.environ, JA_TEST_ROOT=str(self.base), PATH=str(self.bin) + ':/usr/bin:/bin')
        self.make_package()

    def executable(self, name, source):
        p = self.bin / name
        p.write_text(source)
        p.chmod(0o700)

    def write_tools(self):
        self.executable('java', '#!/bin/sh\ntest ! -f "$JA_TEST_ROOT/v3-code-bad" || exit 1\nprintf "%s\\n" "$JA_TEST_ROOT/opt/java-assessment-collector/releases/fixture"\n')
        self.executable('uname' , '#!/bin/sh\necho Linux\n')
        self.executable('id', '#!/bin/sh\necho 0\n')
        preamble = f'#!{sys.executable}\nimport os,sys,json,stat,hashlib,fcntl\nfrom pathlib import Path\nb=Path(os.environ["JA_TEST_ROOT"])\na=sys.argv[1:]\n'
        self.executable('stat', preamble + '''
p=Path(a[-1]);s=p.lstat();mode=stat.S_IMODE(s.st_mode)
if not str(p).startswith(str(b)): mode=0o755
values={'%u':'0' if not (b/'bad-owner').exists() or p.name!='collector.properties' else '99','%h':str(s.st_nlink),'%a':format(mode,'o'),'%s':str(s.st_size)}
print(values[a[1]])
''')
        self.executable('readlink', preamble + 'print(Path(a[-1]).resolve())\n')
        self.executable('flock', preamble + '''
if (b/'busy').exists(): sys.exit(1)
try: fcntl.flock(int(a[-1]),fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError: sys.exit(1)
''')
        self.executable('find', preamble + '''
root=Path(a[0]); members=[root]
for parent,dirs,files in os.walk(root,followlinks=False):
 members += [Path(parent)/n for n in dirs+files]
if '-exec' in a:
 typ=a[a.index('-type')+1];mode=int(a[a.index('chmod')+1],8)
 for p in members:
  if (typ=='d' and p.is_dir()) or (typ=='f' and p.is_file()): p.chmod(mode)
else:
 for p in members:
  s=p.lstat()
  bad=p.is_symlink() or (stat.S_ISREG(s.st_mode) and s.st_nlink>1) or not (stat.S_ISDIR(s.st_mode) or stat.S_ISREG(s.st_mode))
  if '-perm' in a: bad=bad or bool(s.st_mode & 0o022)
  if bad: print(p);break
''')
        self.executable('sha256sum', preamble + '''
if a[0]=='-c':
 for line in Path(a[1]).read_text().splitlines():
  digest,name=line.split()
  if hashlib.sha256(Path(name).read_bytes()).hexdigest()!=digest: sys.exit(1)
else: print(hashlib.sha256(Path(a[0]).read_bytes()).hexdigest()+'  '+a[0])
''')
        self.executable('chkconfig', '#!/bin/sh\nexit 0\n')
        self.executable('getent', '#!/bin/sh\necho root:x:0:0:root:/root:/bin/sh\n')
        self.executable('crontab', '#!/bin/sh\necho "no crontab for root" >&2\nexit 1\n')
        self.executable('systemctl', preamble + '''
p=b/'services.json';states=json.loads(p.read_text());unit=next((x for x in a if x.endswith(('.service','.timer'))),'')
s=states.get(unit);cmd=a[0]
if cmd=='show':
 prop=a[a.index('-p')+1]
 defaults={'LoadState':'loaded' if s else 'not-found','ActiveState':s.get('active','inactive') if s else 'inactive','FragmentPath':str(b/'etc/systemd/system'/unit),'DropInPaths':'','NeedDaemonReload':'no','MainPID':'0','ControlPID':'0'}
 print(prop+'='+ (s or {}).get(prop,defaults.get(prop,'')))
elif cmd in ('is-active','is-enabled'):
 sys.exit(0 if s and s.get('active' if cmd=='is-active' else 'enabled')==('active' if cmd=='is-active' else True) else 3)
elif cmd in ('start','enable','stop','reset-failed'):
 if not s: sys.exit(4)
 with (b/'actions').open('a') as f:f.write(cmd+' '+unit+'\\n')
 if cmd in ('start','stop'): s['active']='active' if cmd=='start' else 'inactive'
 if cmd=='enable': s['enabled']=True
 if cmd=='reset-failed' and s.get('active')=='failed': s['active']='inactive'
 p.write_text(json.dumps(states))
else: sys.exit(7)
''')

    def seal(self):
        manifest = self.source / 'SHA256SUMS'
        manifest.write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+str(p.relative_to(self.source))+'\n'
                                  for p in sorted(self.source.rglob('*')) if p.is_file() and p != manifest))

    def make_package(self):
        (self.source / 'conf').mkdir(exist_ok=True)
        (self.source / 'conf/bootstrap.properties').write_text('private-new-registration')
        (self.source / 'collector.jar').write_text('jar')
        (self.source / 'root-trust.sh').write_text('#!/bin/sh\nexit 0\n')
        (self.source / 'lifecycle-lock.sh').write_text('exec 9>> "'+str(self.opt / 'java-assessment-lifecycle.lock')+'"\nflock -n 9 || exit 1\n')
        (self.source / 'root-java-discovery.sh').write_text('#!/bin/sh\ntest ! -f "$JA_TEST_ROOT/java-bad" || exit 1\nprintf \'%s\\n\' \"$JA_TEST_ROOT/bin/java\"\n')
        migration = (REPO / 'collector/root-migrate-input.sh').read_text().replace('PATH=/usr/sbin:/usr/bin:/sbin:/bin;', 'PATH='+shlex.quote(str(self.bin))+':/usr/bin:/bin;')
        (self.source / 'root-migrate-input.sh').write_text(migration)
        (self.source / 'install.sh').write_text('''#!/bin/sh
set -eu
test -z "${SUDO_UID:-}"
printf installed > "$JA_TEST_ROOT/installed"
test ! -f "$JA_TEST_ROOT/install-bad" || exit 7
''' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(REPO / 'tests/ansible_recovery_fixture.py')) + ' install "$JA_TEST_ROOT"\n')
        self.seal()

    def installation(self, v3=False, active=True):
        (self.root / 'conf').mkdir(parents=True, exist_ok=True)
        (self.root / 'collector-spool').mkdir(exist_ok=True)
        cfg=self.root/'conf/collector.properties'
        if not cfg.exists():
            cfg.write_text('serverUrl=https://old.example.invalid\nhostId=host-existing\nagentToken=existing-private-token-0123456789\ntlsCaFile='+str(self.root/'trust/ca.pem')+'\n')
            (self.root/'conf/installation-id').write_text('11111111-1111-1111-1111-111111111111\n')
        for p in self.root.glob('conf/*'): p.chmod(0o600)
        (self.root/'conf/java.path').write_text('/usr/java/bin/java\n')
        for name in ('root-service.sh','poll.sh','collector.jar'): (self.root/name).write_text('fixture')
        self.helper.mkdir(exist_ok=True); self.update.mkdir(exist_ok=True)
        (self.helper/'uninstall-service.sh').write_text('fixture')
        (self.update/'update-service.sh').write_text('fixture')
        (self.helper/'uninstall-local.sh').write_text('#!/bin/sh\n'+shlex.quote(sys.executable)+' '+shlex.quote(str(REPO/'tests/ansible_recovery_fixture.py'))+' "$1" "$JA_TEST_ROOT"\n')
        if v3:
            (self.root/'releases/fixture').mkdir(parents=True,exist_ok=True)
            (self.root/'releases/fixture/collector.jar').write_text('signed-fixture-jar')
            (self.root/'current.json').write_text('{"fixture":true}')
            (self.update/'bootstrap').mkdir(exist_ok=True)
            (self.update/'bootstrap/anchor.jar').write_text('anchor')
        copies={'collector.timer':self.helper/'collector.timer', 'collector.service':self.helper/'collector.service',
                'uninstall.service':self.helper/'helper.service',
                ('recovery.service' if v3 else 'update.service'):(self.update/'bootstrap/recovery.service' if v3 else self.update/'helper.service')}
        states={}
        for name,copy in copies.items():
            unit='java-assessment-'+name
            copy.write_text('fixture-unit-'+name)
            (self.units/unit).write_text(copy.read_text())
            states[unit]={'active':'active' if active and name!='collector.service' else 'inactive','enabled':active}
        self.services.write_text(json.dumps(states))

    def sysv(self, v3=True, active=False):
        import shutil
        shutil.rmtree(self.base/'run/systemd/system')
        for p in self.units.iterdir(): p.unlink()
        self.services.write_text('{}')
        self.root.joinpath('root-service.sh').write_text('#!/bin/sh\ntest -f "$JA_TEST_ROOT/collector-active"\n')
        for name,copy in [('collector',self.helper/'collector.init'),('uninstall',self.helper/'helper.init'),
                          ('recovery' if v3 else 'update',self.update/'bootstrap/recovery.init' if v3 else self.update/'helper.init')]:
            body='#!/bin/sh\n'+shlex.quote(sys.executable)+' '+shlex.quote(str(REPO/'tests/ansible_recovery_fixture.py'))+' "sysv-'+name+'-$1" "$JA_TEST_ROOT"\n'
            copy.write_text(body)
            (self.init/('java-assessment-'+name)).write_text(body)
            if active: self.sysv_action(name,'start')

    def sysv_action(self, name, action):
        import shutil
        if name=='collector':
            marker=self.base/'collector-active'
            if action=='start': marker.touch()
            elif marker.exists(): marker.unlink()
            return
        pid={'uninstall':101,'update':102,'recovery':103}[name]
        pidfile={'uninstall':self.helper/'supervisor.pid','update':self.update/'supervisor.pid','recovery':self.update/'state/recovery.pid'}[name]
        script={'uninstall':self.helper/'uninstall-service.sh','update':self.update/'update-service.sh','recovery':self.update/'bootstrap/update-bootstrap.sh'}[name]
        proc=self.base/'proc'/str(pid)
        if action=='start':
            pidfile.parent.mkdir(exist_ok=True)
            pidfile.write_text(str(pid))
            proc.mkdir(exist_ok=True)
            (proc/'cmdline').write_text('/bin/sh '+str(script)+' loop')
            (proc/'status').write_text('Uid: 0 0 0 0\n')
        else:
            if pidfile.exists(): pidfile.unlink()
            if proc.exists(): shutil.rmtree(proc)


if __name__=='__main__':
    import shutil
    action,base=sys.argv[1:]
    # Installer boundary: do not overwrite the orchestrator, source or tool stubs.
    f=object.__new__(RecoveryFixture);f.base=Path(base);f.opt=f.base/'opt'
    f.root=f.opt/'java-assessment-collector';f.update=f.opt/'java-assessment-update';f.helper=f.opt/'java-assessment-uninstall'
    f.units=f.base/'etc/systemd/system';f.services=f.base/'services.json'
    f.init=f.base/'etc/init.d'
    if action.startswith('sysv-'):
        _,name,verb=action.split('-'); f.sysv_action(name,verb)
    elif action=='install':
        f.installation(active=True)
        if not (f.base/'run/systemd/system').exists():
            (f.base/'run/systemd/system').mkdir()
            f.sysv(v3=False,active=True)
    elif action=='remove':
        if (f.base/'uninstall-refuse').exists(): sys.exit(17)
        shutil.rmtree(f.root);shutil.rmtree(f.update)
    elif action=='cleanup':
        shutil.rmtree(f.helper)
        for p in f.units.iterdir():p.unlink()
        f.services.write_text('{}')
