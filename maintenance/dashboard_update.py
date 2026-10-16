#!/usr/bin/env python3
"""Independent dashboard updater. Python 3.6+, fixed production paths, signed data only."""
import contextlib
import datetime
import fcntl
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import socket
import sqlite3
import ssl
import stat
import subprocess
import sys
import tempfile
import time
import urllib.parse
import uuid
import zipfile

BASE = Path('/opt/java-assessment-dashboard')
STATE = Path('/var/lib/java-assessment-dashboard-updater')
UNIT = 'java-assessment-dashboard.service'
MAX_UPLOAD = 256 * 1024 * 1024
MAX_EXPANDED = 768 * 1024 * 1024
COMPONENTS = ('classes', 'server', 'shared', 'web', 'dist', 'lib', 'maintenance', 'VERSION')
TERMINAL = ('SUCCEEDED', 'ROLLED_BACK', 'CANCELLED', 'FAILED')
ID = re.compile(r'^[a-f0-9]{32}$')
VERSION = re.compile(r'^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')

class Rejected(Exception):
    pass

def require(condition, message):
    if not condition:
        raise Rejected(message)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def version(value):
    require(isinstance(value, str) and VERSION.fullmatch(value), 'Versão inválida.')
    return tuple(map(int, value.split('.')))

def now():
    return datetime.datetime.utcnow().isoformat() + 'Z'

def sync_dir(path):
    fd = os.open(str(path), os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)

def atomic(path, data, gid=None):
    path = Path(path)
    fd, name = tempfile.mkstemp(prefix='.next-', dir=str(path.parent))
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
            if gid is not None:
                os.fchown(stream.fileno(), 0, gid); os.fchmod(stream.fileno(), 0o640)
        os.replace(name, str(path)); sync_dir(path.parent)
    finally:
        if os.path.exists(name): os.unlink(name)

def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True) + '\n').encode('utf-8')

def regular(path, limit):
    fd = os.open(str(path), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        st = os.fstat(stream.fileno())
        require(stat.S_ISREG(st.st_mode) and st.st_nlink == 1 and st.st_size <= limit, 'Arquivo inválido ou excede o limite.')
        data = stream.read(limit + 1)
        require(len(data) <= limit, 'Arquivo excede o limite.')
        return data

def tree_hashes(path):
    require(path.is_dir() and not path.is_symlink(), 'Diretório de instalação inválido.')
    result = {}
    for p in sorted(path.rglob('*')):
        require(not p.is_symlink(), 'Link não permitido na instalação.')
        if p.is_file(): result[str(p.relative_to(path))] = sha(p.read_bytes())
    return result

def source_hashes(base):
    return {folder + '/' + name: digest for folder in ('server','shared','web','lib')
            for name, digest in tree_hashes(base / folder).items()}

def checked_package(path, public_key, current_version, baseline):
    """Verify signature before trusting paths, metadata or allocating expanded contents."""
    require(path.stat().st_size <= MAX_UPLOAD, 'Pacote excede 256 MiB.')
    with zipfile.ZipFile(str(path)) as archive:
        infos = archive.infolist(); names = [i.filename for i in infos]
        require(len(names) <= 6000 and len(names) == len(set(names)), 'Entradas duplicadas ou em excesso.')
        require('manifest.json' in names and 'manifest.sig' in names, 'Use o pacote assinado de atualização pelo painel.')
        require(archive.getinfo('manifest.json').file_size <= 512*1024 and archive.getinfo('manifest.sig').file_size <= 1024, 'Manifesto inválido.')
        raw = archive.read('manifest.json'); signature = archive.read('manifest.sig')
        with tempfile.TemporaryDirectory(prefix='dashboard-signature-') as temp:
            m=Path(temp)/'manifest'; s=Path(temp)/'signature';m.write_bytes(raw);s.write_bytes(signature)
            result=subprocess.run(['openssl','dgst','-sha256','-verify',str(public_key),'-keyform','DER','-signature',str(s),str(m)], stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
        require(result.returncode == 0, 'Assinatura do pacote inválida.')
        manifest=json.loads(raw.decode('utf-8'))
        require(set(manifest)=={'format','version','fromVersions','rollback','notes','files','baselines'}, 'Manifesto não suportado.')
        require(manifest['format']=='java-assessment-dashboard-update-v1' and manifest['rollback']=='preserve-database-v1', 'Protocolo de atualização incompatível.')
        require(version(manifest['version']) > version(current_version), 'O pacote deve ser mais recente que a versão instalada.')
        require(current_version in manifest['fromVersions'], 'A versão instalada não é uma origem compatível.')
        require(baseline in manifest['baselines'], 'Arquivos instalados diferem das origens assinadas.')
        require(isinstance(manifest['notes'],str) and len(manifest['notes']) <= 4000, 'Notas do pacote inválidas.')
        files=manifest['files'];require(isinstance(files,dict) and 0<len(files)<=5998,'Lista de arquivos inválida.')
        require(set(names)=={'manifest.json','manifest.sig'}|{'payload/'+n for n in files}, 'Conteúdo não declarado no manifesto.')
        require(sum(i.file_size for i in infos)<=MAX_EXPANDED, 'Pacote expandido excede o limite.')
        for name,item in files.items():
            require(isinstance(name,str) and len(name)<=240 and re.fullmatch(r'[A-Za-z0-9_.$/+ -]+',name)
                    and not name.startswith('/') and all(p not in ('','..','.') for p in name.split('/'))
                    and (name=='VERSION' or name.split('/')[0] in COMPONENTS[:-1]), 'Caminho de pacote inválido.')
            info=archive.getinfo('payload/'+name)
            require(not info.is_dir() and stat.S_IFMT(info.external_attr>>16) in (0,stat.S_IFREG)
                    and not info.flag_bits & 1, 'Links, diretórios ou arquivos criptografados não são aceitos.')
            require(isinstance(item,dict) and set(item)=={'sha256','size'} and type(item['size']) is int
                    and 0<=item['size']<=128*1024*1024 and info.file_size==item['size']
                    and re.fullmatch('[a-f0-9]{64}',str(item['sha256'])), 'Metadados de arquivo inválidos.')
            h=hashlib.sha256()
            with archive.open(info) as stream:
                for chunk in iter(lambda:stream.read(65536),b''): h.update(chunk)
            require(h.hexdigest()==item['sha256'], 'Integridade do arquivo não confere.')
        require(archive.read('payload/VERSION').decode().strip()==manifest['version'], 'Versão do conteúdo não confere.')
        for component in COMPONENTS[:-1]:require(any(n.startswith(component+'/') for n in files), 'Componente obrigatório ausente.')
        require('dist/agent-update-public.der' in files and archive.read('payload/dist/agent-update-public.der')==public_key.read_bytes(), 'Chave de confiança não pode ser substituída.')
        require('server/UserAuth.java' in files and 'server/DashboardMaintenance.java' in files and 'maintenance/dashboard_update.py' in files, 'Pacote sem autenticação ou manutenção compatível.')
        return manifest

class System:
    def __init__(self, base=BASE): self.base=base
    def command(self,*args):
        return subprocess.check_output(args,stderr=subprocess.STDOUT,universal_newlines=True,timeout=120).strip()
    def stop(self):
        self.command('systemctl','stop',UNIT)
        result=self.command('systemctl','show',UNIT,'--all','-p','ActiveState','-p','MainPID','-p','ControlPID','-p','ControlGroup')
        values=dict(line.split('=',1) for line in result.splitlines() if '=' in line)
        require(values.get('ActiveState') in ('inactive','failed') and values.get('MainPID')=='0' and values.get('ControlPID')=='0' and values.get('ControlGroup')=='','Não foi possível confirmar a parada do dashboard.')
    def backup(self,source,target):
        require('BACKUP_OK' in self.command(str(self.base/'database.sh'),'backup',str(source),str(target)), 'Backup do banco inválido.')
    def start(self): self.command('systemctl','start',UNIT)
    def label(self):
        if shutil.which('restorecon'):self.command('restorecon','-RF',str(self.base))
    def health(self,expected):
        props=dict(line.split('=',1) for line in (self.base/'conf/server.properties').read_text().splitlines() if '=' in line and not line.startswith(('#','!')))
        parsed=urllib.parse.urlsplit(props.get('publicUrl',''))
        require(parsed.scheme=='https' and parsed.hostname and not parsed.username and not parsed.password, 'Endereço HTTPS inválido.')
        context=ssl.create_default_context(cafile=str(self.base/'conf/agent-ca.pem'))
        for attempt in range(30):
            try:
                with socket.create_connection(('127.0.0.1',parsed.port or 443),timeout=2) as plain:
                    with context.wrap_socket(plain,server_hostname=parsed.hostname) as secure:
                        secure.sendall(('GET /health HTTP/1.1\r\nHost: '+parsed.netloc+'\r\nConnection: close\r\n\r\n').encode('ascii'))
                        response=http.client.HTTPResponse(secure);response.begin();data=json.loads(response.read(4097))
                        if response.status==200 and data.get('status')=='ok' and data.get('storage')=='sqlite' and data.get('version')==expected:return
            except (OSError,ValueError,http.client.HTTPException):pass
            time.sleep(1)
        raise Rejected('A nova versão não respondeu corretamente ao HTTPS.')

class Engine:
    def __init__(self, base=BASE, state=STATE, system=None, gid=None):
        self.base=Path(base);self.state=Path(state);self.system=system or System(base);self.gid=gid
        self.work=self.base/'.dashboard-updates';self.work.mkdir(mode=0o700,exist_ok=True)
        self.journal=self.state/'journal.json';self.public=self.state/'status.json';self.gate=self.state/'maintenance'
        self.record=json.loads(regular(self.journal,2*1024*1024)) if self.journal.exists() else {'phase':'IDLE','events':[],'history':[]}
        self.save()
    def save(self):
        atomic(self.journal,json_bytes(self.record))
        public={k:self.record[k] for k in ('id','phase','version','previousVersion','notes','events','history','updatedAt','actor') if k in self.record}
        public['installedVersion']=(self.base/'VERSION').read_text().strip() if (self.base/'VERSION').is_file() else self.record.get('previousVersion','')
        public['canRollback']=self.record.get('phase')=='SUCCEEDED' and public['installedVersion']==self.record.get('version')
        atomic(self.public,json_bytes(public),self.gid)
    def event(self,phase,message):
        self.record.update(phase=phase,updatedAt=now())
        self.record['events']=(self.record.get('events',[])+[{'at':now(),'message':message}])[-100:];self.save()
    def terminal(self,phase,message):
        self.record['history']=(self.record.get('history',[])+[{'id':self.record.get('id'),'version':self.record.get('version'),'phase':phase,'at':now()}])[-25:]
        self.event(phase,message)
    def op(self):
        require(ID.fullmatch(self.record.get('id','')),'Operação inválida.')
        return self.work/self.record['id']
    def database(self,backup=None):
        db=self.base/'data/inventory.sqlite'
        require(db.is_file() and not db.is_symlink(),'Banco de inventário inválido.')
        # SQLite may need to recover/recreate WAL sidecars after the JVM stops.
        # mode=ro can fail even for a clean JDBC database with no sidecars left.
        # rw never creates a missing inventory; query_only prohibits SQL mutations.
        owner=db.stat()
        con=sqlite3.connect('file:'+str(db)+'?mode=rw',uri=True,timeout=10)
        try:
            con.execute('PRAGMA query_only=ON')
            require(con.execute('PRAGMA quick_check').fetchall()==[('ok',)],'Banco precisa de diagnóstico.')
            tables={r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            for table in ('agent_update_requests','agent_update_operations','uninstall_requests'):
                if table in tables:
                    statuses={r[0] for r in con.execute('SELECT DISTINCT status FROM '+table)}
                    require(not statuses-{'SUCCEEDED','FAILED','CANCELLED','EXPIRED','ROLLED_BACK'},'Há atualização ou desinstalação de agente pendente. Aguarde sua conclusão.')
            if backup:
                if hasattr(con,'backup'):
                    out=sqlite3.connect(str(backup))
                    try:con.backup(out);require(out.execute('PRAGMA integrity_check').fetchall()==[('ok',)],'Backup do banco inválido.')
                    finally:out.close()
                else:self.system.backup(db,backup)
            return {table:con.execute('SELECT count(*) FROM '+table).fetchone()[0] for table in ('agent_update_requests','agent_update_operations','uninstall_requests') if table in tables}
        finally:
            con.close()
            # A root diagnostic must not leave root-owned sidecars that prevent
            # the unprivileged dashboard from reopening its own database.
            if os.geteuid()==0:
                for suffix in ('-wal','-shm'):
                    try:fd=os.open(str(db)+suffix,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
                    except FileNotFoundError:continue
                    try:
                        info=os.fstat(fd)
                        require(stat.S_ISREG(info.st_mode) and info.st_nlink==1,'Arquivo auxiliar do banco inválido.')
                        os.fchown(fd,owner.st_uid,owner.st_gid);os.fchmod(fd,0o600)
                    finally:os.close(fd)
    def prepare(self,ident,upload,actor):
        require(self.record['phase'] in ('IDLE',)+TERMINAL,'Já existe uma operação em andamento.')
        require(ID.fullmatch(ident),'Identificador inválido.')
        require(not (self.work/ident).exists(),'Identificador de operação já utilizado.')
        current=(self.base/'VERSION').read_text().strip()
        self.record={'id':ident,'phase':'VERIFYING','previousVersion':current,'events':[],'history':self.record.get('history',[]),'actor':actor}
        self.event('VERIFYING','Validando assinatura, compatibilidade e arquivos do pacote.')
        directory=self.op();directory.mkdir(mode=0o700);sync_dir(self.work)
        try:
            # Copy from the untrusted inbox through a no-follow descriptor, never reuse its path.
            fd=os.open(str(upload),os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
            with os.fdopen(fd,'rb') as source, (directory/'package.zip').open('xb') as target:
                info=os.fstat(source.fileno())
                require(stat.S_ISREG(info.st_mode) and info.st_nlink==1 and 0<info.st_size<=MAX_UPLOAD,'Upload inválido.')
                total=0
                while True:
                    chunk=source.read(65536)
                    if not chunk:break
                    total+=len(chunk);require(total<=MAX_UPLOAD,'Upload excede o limite.');target.write(chunk)
                target.flush();os.fsync(target.fileno())
                require(total==info.st_size,'Upload alterado durante a leitura.')
            manifest=checked_package(directory/'package.zip',self.base/'dist/agent-update-public.der',current,source_hashes(self.base))
            self.database()
            require(shutil.disk_usage(str(self.base)).free > MAX_EXPANDED*3 + (self.base/'data/inventory.sqlite').stat().st_size*2,'Espaço insuficiente para atualização e backup.')
            self.record.update(version=manifest['version'],notes=manifest['notes'],manifest=manifest,packageSha=sha((directory/'package.zip').read_bytes()))
            self.event('PREPARED','Pacote pronto. Confirme Atualizar e reiniciar para continuar.')
        except Exception as error:
            self.terminal('FAILED',str(error) if isinstance(error,Rejected) else 'Preparação recusada: pacote inválido ou incompleto.');raise
    def confirm(self,ident):
        require(ident==self.record.get('id') and self.record['phase']=='PREPARED','A preparação mudou. Consulte novamente.')
        directory=self.op();current=(self.base/'VERSION').read_text().strip()
        require(current==self.record['previousVersion'],'A versão instalada mudou.')
        checked_package(directory/'package.zip',self.base/'dist/agent-update-public.der',current,source_hashes(self.base));self.database()
        atomic(self.gate,b'maintenance\n',self.gid)
        try:
            self.event('BACKUP','Pausando o dashboard e criando backup privado consistente.')
            self.system.stop();self.record['operationCounts']=self.database(directory/'inventory.sqlite')
            shutil.copytree(str(self.base/'conf'),str(directory/'conf'))
            key=self.base/'data/.environment-install.key'
            if key.exists():atomic(directory/'environment-install.key',regular(key,32))
            (directory/'old').mkdir();new=directory/'new';new.mkdir()
            shutil.copytree(str(self.base/'dist'),str(new/'dist'))
            manifest=self.record['manifest']
            with zipfile.ZipFile(str(directory/'package.zip')) as archive:
                for name in manifest['files']:
                    target=new/name;target.parent.mkdir(parents=True,exist_ok=True)
                    with target.open('wb') as stream:
                        stream.write(archive.read('payload/'+name));stream.flush();os.fsync(stream.fileno())
            self.configure_ca(new/'dist/assessment-collector.zip')
            for p in new.rglob('*'):
                if self.gid is not None:os.chown(str(p),0,self.gid)
                p.chmod(0o750 if p.is_dir() else 0o640)
            for folder in sorted((p for p in new.rglob('*') if p.is_dir()),key=lambda p:len(p.parts),reverse=True):sync_dir(folder)
            sync_dir(new);sync_dir(directory)
            self.record['confHashes']=tree_hashes(self.base/'conf')
            self.record['oldHashes']={name:(tree_hashes(self.base/name) if (self.base/name).is_dir() else sha((self.base/name).read_bytes())) for name in COMPONENTS}
            self.record['swapStarted']=True
            self.event('APPLYING','Backup concluído. Aplicando os componentes da nova versão.')
            for component in COMPONENTS:
                os.replace(str(self.base/component),str(directory/'old'/component));sync_dir(self.base);sync_dir(directory/'old')
                os.replace(str(new/component),str(self.base/component));sync_dir(self.base)
            self.system.label();self.event('STARTING','Iniciando e verificando a nova versão por HTTPS.')
            self.system.start();self.system.health(self.record['version']);self.database()
            require(tree_hashes(self.base/'conf')==self.record['confHashes'],'Configuração alterada durante a atualização.')
            for name,item in manifest['files'].items():
                if name!='dist/assessment-collector.zip':require(sha((self.base/name).read_bytes())==item['sha256'],'Arquivo instalado não confere.')
            self.terminal('SUCCEEDED','Atualização concluída. Nova versão e HTTPS confirmados.');self.ungate()
        except Exception:
            self.rollback(automatic=True)
            raise
    def configure_ca(self,path):
        # Only the public CA of this installation is substituted in the generic agent.
        ca=(self.base/'conf/agent-ca.pem').read_bytes();out=io.BytesIO()
        with zipfile.ZipFile(str(path)) as source,zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as target:
            lines=[]
            for name in source.namelist():
                if name=='SHA256SUMS':continue
                data=source.read(name)
                if name=='conf/ca.pem':data=ca
                if name=='DEPENDENCIES.json':
                    value=json.loads(data);value['ca']={'source':'existing dashboard CA','sha256':sha(ca)};data=json_bytes(value)
                target.writestr(name,data);lines.append(sha(data)+'  '+name)
            target.writestr('SHA256SUMS','\n'.join(lines)+'\n')
        with path.open('wb') as stream:
            stream.write(out.getvalue());stream.flush();os.fsync(stream.fileno())
    def ungate(self):
        if self.gate.exists():self.gate.unlink();sync_dir(self.state)
    def cancel(self,ident):
        require(ident==self.record.get('id') and self.record['phase']=='PREPARED','Só é possível cancelar antes da aplicação.')
        self.terminal('CANCELLED','Preparação cancelada. A instalação foi preservada.')
        (self.op()/'package.zip').unlink()
    def rollback(self,automatic=False):
        if not automatic:
            require(self.record['phase']=='SUCCEEDED' and (self.base/'VERSION').read_text().strip()==self.record['version'],'Restauração não disponível.')
            require(self.database()==self.record.get('operationCounts'),'Novas operações de agentes impedem a restauração desta versão.')
            for name,item in self.record['manifest']['files'].items():
                if name!='dist/assessment-collector.zip':require(sha((self.base/name).read_bytes())==item['sha256'],'Arquivos instalados foram alterados; restauração recusada.')
        atomic(self.gate,b'maintenance\n',self.gid)
        try:
            self.event('ROLLING_BACK','Restaurando a versão anterior e preservando os dados atuais.')
            self.system.stop()
            if self.record.get('swapStarted'):self.database()
            directory=self.op();old=directory/'old';failed=directory/'failed';failed.mkdir(exist_ok=True)
            if self.record.get('swapStarted'):
                for component in COMPONENTS:
                    saved=old/component
                    if saved.exists():
                        digest=tree_hashes(saved) if saved.is_dir() else sha(saved.read_bytes())
                        require(digest==self.record['oldHashes'][component],'Backup de componentes não confere. Restauração interrompida.')
                for component in COMPONENTS:
                    saved=old/component;target=self.base/component
                    if saved.exists():
                        digest=tree_hashes(saved) if saved.is_dir() else sha(saved.read_bytes())
                        require(digest==self.record['oldHashes'][component],'Backup de componentes não confere. Restauração interrompida.')
                        if target.exists():
                            destination=failed/component
                            if destination.exists():
                                if destination.is_dir():shutil.rmtree(str(destination))
                                else:destination.unlink()
                            os.replace(str(target),str(destination))
                        os.replace(str(saved),str(target));sync_dir(self.base);sync_dir(old)
                require((self.base/'VERSION').read_text().strip()==self.record['previousVersion'],'Versão anterior não restaurada.')
            self.system.label();self.system.start();self.system.health(self.record['previousVersion'])
            self.terminal('ROLLED_BACK','Versão anterior restaurada e HTTPS confirmado. Banco atual preservado.');self.ungate()
        except Exception:
            self.event('RECOVERY_REQUIRED','Recuperação não confirmada. Backup preservado; diagnóstico administrativo necessário.')
            raise
    def recover(self):
        phase=self.record['phase']
        if phase in ('BACKUP','APPLYING','STARTING','ROLLING_BACK'):self.rollback(automatic=True)
        elif phase=='VERIFYING':self.terminal('FAILED','Preparação interrompida. Envie o pacote novamente.')
        elif phase in TERMINAL:self.ungate()


def serve():
    require(os.geteuid()==0,'O serviço de atualização deve executar como root.')
    os.umask(0o077);account=pwd.getpwnam('java-assessment-dashboard')
    require(STATE.is_dir() and not STATE.is_symlink() and STATE.stat().st_uid==0,'Diretório de estado inválido.')
    fd=os.open(str(STATE/'worker.lock'),os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
    engine=Engine(gid=account.pw_gid)
    try:engine.recover()
    except Exception:pass
    inbox=STATE/'inbox'
    loaded_code=sha(Path(__file__).read_bytes())
    while True:
        for request in sorted(inbox.glob('*.json'))[:8]:
            ident=request.stem
            try:
                require(ID.fullmatch(ident),'Solicitação inválida.')
                data=json.loads(regular(request,8192));request.unlink()
                require(set(data)<= {'action','operationId','actor'} and data.get('action') in ('prepare','confirm','cancel','rollback'),'Ação inválida.')
                actor=data.get('actor','');require(isinstance(actor,str) and len(actor)<=120,'Administrador inválido.')
                action=data['action'];operation=data.get('operationId','')
                if action=='prepare':engine.prepare(ident,inbox/(ident+'.zip'),actor)
                else:
                    require(operation==engine.record.get('id'),'Operação desatualizada.')
                    if action=='confirm':engine.confirm(operation)
                    elif action=='cancel':engine.cancel(operation)
                    else:engine.rollback()
            except Exception:
                # Never publish raw subprocess stderr, configuration or secrets.
                if engine.record['phase'] in ('PREPARED',)+TERMINAL:engine.event(engine.record['phase'],'Solicitação recusada ou falhou; confira o estado atual antes de tentar novamente.')
            finally:
                if request.exists():request.unlink()
                upload=inbox/(ident+'.zip')
                if ID.fullmatch(ident) and upload.is_file():upload.unlink()
        if engine.record['phase'] in TERMINAL and sha(Path(__file__).read_bytes())!=loaded_code:
            os.execv(sys.executable,[sys.executable,'-B',str(Path(__file__).resolve())])
        time.sleep(1)

if __name__=='__main__':serve()
