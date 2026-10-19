#!/usr/bin/env python3
"""Real released Java/SQLite/TLS processes in disposable loopback directories.

The service adapter starts child JVMs instead of touching systemd. The 2.0.57
fixture gets an empty maintenance directory to model the bootstrap prerequisite;
this test does not claim that unmodified 2.0.57 supports the browser workflow.
"""
import hashlib
import importlib.util
import json
import os
import pwd
from pathlib import Path
import socket
import shutil
import shlex
import sqlite3
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('updater', str(ROOT/'maintenance/dashboard_update.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

def artifact(version, name):
    root = ROOT/'output/dashboard-releases'/version
    manifest = json.loads((root/'packages/package-manifest.json').read_text())
    entry = next(e for e in manifest['artifacts'] if e['file'] == name)
    path = root/name
    assert hashlib.sha256(path.read_bytes()).hexdigest() == entry['sha256']
    return path

def unpack(path, base, prefixes):
    with zipfile.ZipFile(str(path)) as z:
        for name in z.namelist():
            if name == 'VERSION' or any(name.startswith(p+'/') for p in prefixes):
                assert not name.startswith('/') and '..' not in Path(name).parts
                target = base/name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(z.read(name))

def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

class JavaSystem(m.System):
    def __init__(self, base):
        super().__init__(base)
        self.process = None
        self.log = (base/'process.log').open('ab')
        self.unit = ('java-assessment-final-fixture-'+str(os.getpid())+'.service') if os.environ.get('TEST_DASHBOARD_USER') else None
        self.starts = 0
    def start(self):
        assert self.process is None
        command = [os.environ['TEST_JAVA'], '-Xmx384m', '-cp',
            str(self.base/'classes')+os.pathsep+str(self.base/'lib/*'),
            'assessment.AssessmentServer', str(self.base)]
        if self.unit:
            self.starts += 1
            self.unit = 'java-assessment-final-fixture-'+str(os.getpid())+'-'+str(self.starts)+'.service'
            subprocess.check_call(['systemd-run', '--unit='+self.unit, '--uid='+os.environ['TEST_DASHBOARD_USER'], '--property=TimeoutStopSec=20', '--property=ProtectSystem=strict', '--property=PrivateTmp=true', '--property=ReadWritePaths='+str(self.base/'data')]+command, stdout=self.log, stderr=self.log)
        else:
            self.process = subprocess.Popen(command, stdout=self.log, stderr=self.log)
    def stop(self):
        if self.unit:
            subprocess.check_call(['systemctl', 'stop', self.unit], stdout=self.log, stderr=self.log)
            subprocess.call(['systemctl', 'reset-failed', self.unit], stdout=self.log, stderr=self.log)
            return
        if self.process is not None:
            self.process.terminate()
            self.process.wait(timeout=20)
            self.process = None
            if os.environ.get('TEST_PRESERVE_STOPPED_DB'):
                target = Path(os.environ['TEST_PRESERVE_STOPPED_DB'])
                if not target.exists():
                    shutil.copytree(str(self.base/'data'), str(target))
    def label(self):
        pass
    def backup(self, source, target):
        output = subprocess.check_output([os.environ['TEST_JAVA'], '-cp', str(self.base/'lib/*'),
            str(ROOT/'dashboard-installer/DatabaseTool.java'), 'backup', str(source), str(target)])
        assert b'BACKUP_OK' in output

def main():
    import argparse
    parser=argparse.ArgumentParser()
    parser.add_argument("--from-version", default="2.0.57")
    parser.add_argument("--to-version", default="2.1.0")
    args=parser.parse_args()
    origin,target=args.from_version,args.to_version
    with tempfile.TemporaryDirectory(prefix='dashboard-final-java-',dir='/opt' if os.environ.get('TEST_DASHBOARD_USER') else None) as temp:
        base = Path(temp).resolve()/'installation'
        base.mkdir()
        unpack(artifact(origin, 'packages/assessment-dashboard-runtime-'+origin+'.zip'), base,
               ('classes', 'web', 'dist', 'lib'))
        unpack(artifact(origin, 'deployment/dashboard-update-sources.zip'), base,
               ('server', 'shared', 'web'))
        (base/'maintenance').mkdir(exist_ok=True)
        state = Path(temp).resolve()/'state'
        state.mkdir()
        (base/'conf/tls').mkdir(parents=True)
        (base/'openssl.cnf').write_text('[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,keyEncipherment,keyCertSign\n')
        subprocess.check_call(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(base/'conf/tls/privkey.pem')], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.check_call(['openssl', 'req', '-x509', '-new', '-key', str(base/'conf/tls/privkey.pem'), '-out', str(base/'conf/tls/fullchain.pem'), '-days', '1', '-config', str(base/'openssl.cnf')], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        (base/'conf/agent-ca.pem').write_bytes((base/'conf/tls/fullchain.pem').read_bytes())
        port_value=port()
        (base/'conf/server.properties').write_text('agentToken=disposable-fixture\nhostId=fixture-only\nseedLegacyHost=false\nadminSalt=fixture-salt\nadminPasswordSha256='+hashlib.sha256(b'fixture-saltfixture-password').hexdigest()+'\nhttpPort='+str(port())+'\nhttpsPort='+str(port_value)+'\npublicUrl=https://localhost:'+str(port_value)+'\n')
        account = pwd.getpwnam(os.environ['TEST_DASHBOARD_USER']) if os.environ.get('TEST_DASHBOARD_USER') else None
        if account:
            assert os.geteuid()==0
            Path(temp).chmod(0o755)
            for p in [base]+list(base.rglob('*')):
                os.chown(str(p), account.pw_uid, account.pw_gid)
            (base/'data').mkdir()
            os.chown(str(base/'data'),account.pw_uid,account.pw_gid)
            (state/'inbox').mkdir(mode=0o770)
            os.chown(str(state/'inbox'),0,account.pw_gid)
            (state/'inbox').chmod(0o770)
            status=state/'status.json';status.write_text('{}');status.chmod(0o640);os.chown(str(status),0,account.pw_gid)
            install_spec=importlib.util.spec_from_file_location('install',str(ROOT/'maintenance/install.py'))
            install=importlib.util.module_from_spec(install_spec);install_spec.loader.exec_module(install);install.STATE=state
            access=next(line for line in install.access_dropin().splitlines() if line.startswith('ReadWritePaths='))
            probe='test -r '+shlex.quote(str(status))+' && test ! -w '+shlex.quote(str(status))+' && touch '+shlex.quote(str(state/'inbox/probe'))
            subprocess.check_call(['systemd-run','--wait','--pipe','--uid='+os.environ['TEST_DASHBOARD_USER'],'--property=ProtectSystem=strict','--property='+access,'/bin/sh','-c',probe],stdout=subprocess.DEVNULL)
            assert (state/'inbox/probe').is_file()
        system = JavaSystem(base)
        try:
            system.start()
            system.health(origin)
            (base/'data/.environment-install.key').write_bytes(os.urandom(32))
            if account:
                os.chown(str(base/'data/.environment-install.key'),account.pw_uid,account.pw_gid)
                (base/'data/.environment-install.key').chmod(0o600)
            identity = (base/'data/.environment-install.key').read_bytes()
            package = ROOT/'output/dashboard-releases'/target/'packages'/('assessment-dashboard-web-update-'+target+'.zip')
            engine = m.Engine(base, state, system, account.pw_gid if account else None)
            engine.prepare('a'*32, package, 'fixture@example.test')
            engine.confirm('a'*32)
            assert engine.record['phase'] == 'SUCCEEDED'
            system.health(target)
            assert identity == (base/'data/.environment-install.key').read_bytes()
            if account:
                for p in (base/'data').glob('inventory.sqlite*'):
                    assert p.stat().st_uid == account.pw_uid, 'database remains readable by the dashboard account'
            con = sqlite3.connect(str(base/'data/inventory.sqlite'))
            con.execute('CREATE TABLE fixture_after_update (value TEXT)')
            con.execute("INSERT INTO fixture_after_update VALUES ('preserved')")
            con.commit()
            con.close()
            engine.rollback()
            system.health(origin)
            con = sqlite3.connect(str(base/'data/inventory.sqlite'))
            assert con.execute('SELECT value FROM fixture_after_update').fetchone()[0] == 'preserved'
            assert con.execute('PRAGMA integrity_check').fetchall() == [('ok',)]
            con.close()
            assert identity == (base/'data/.environment-install.key').read_bytes()
            print('FINAL_JAVA_PACKAGE=PASS '+origin+' -> '+target+' -> '+origin+'; real released Java classes, signed ZIP, TLS/SNI, SQLite and installation identity; '+('temporary systemd unit, unprivileged user, strict sandbox and inbox-only write access' if account else 'child-process service adapter'))
        except Exception:
            system.log.flush()
            print((base/'process.log').read_text(errors='replace')[-4000:])
            raise
        finally:
            system.stop()
            system.log.close()

if __name__ == '__main__':
    main()
