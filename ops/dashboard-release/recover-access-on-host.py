#!/usr/bin/env python3
"""Recuperação local interativa de acesso. Execute com sudo na VM do dashboard."""
import datetime
from contextlib import closing
import fcntl
import json
import os
from pathlib import Path
import pwd
import re
import secrets
import shlex
import shutil
import signal
import sqlite3
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request

BASE = Path('/opt/java-assessment-dashboard')
ROOT_PRIVATE = Path('/root')
UNIT = 'java-assessment-dashboard.service'
ACCOUNT = 'java-assessment-dashboard'
CGROUP_ROOT = Path('/sys/fs/cgroup')
LOCK = Path('/run/java-assessment-dashboard-access-recovery.lock')


class RecoveryError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise RecoveryError(message)


def protected(path, *, directory=False, owners=(0,), private=False):
    """Refuse symlinks in the entire selected path, and group/other writes."""
    path = Path(path)
    require(path.is_absolute(), 'Caminho absoluto obrigatório.')
    for part in [*reversed(path.parents), path]:
        info = part.lstat()
        require(not stat.S_ISLNK(info.st_mode), 'Link simbólico recusado: ' + str(part))
        require(not info.st_mode & 0o022, 'Caminho permite escrita por terceiros: ' + str(part))
    info = path.lstat()
    require(info.st_uid in owners, 'Proprietário inesperado: ' + str(path))
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode),
            'Tipo de arquivo inesperado: ' + str(path))
    if private:
        require(not info.st_mode & 0o077, 'Arquivo ou pasta precisa ser privado: ' + str(path))
    return path


def protected_executable(path):
    """Allow system-maintained Java alternatives and /sbin aliases, then use the target."""
    path = Path(path)
    require(path.is_absolute(), 'Executável precisa de caminho absoluto.')
    for part in [*reversed(path.parents), path]:
        info = part.lstat()
        require(info.st_uid == 0, 'Executável ou alias não pertence a root: ' + str(part))
        if not stat.S_ISLNK(info.st_mode):
            require(not info.st_mode & 0o022, 'Executável permite escrita por terceiros: ' + str(part))
    canonical = protected(path.resolve(strict=True))
    require(os.access(canonical, os.X_OK), 'Executável sem permissão de execução.')
    return canonical


def run(args, *, timeout=45, label='Comando local'):
    # Do not include stdout/stderr or argument values in exception messages.
    try:
        result = subprocess.run(list(map(str, args)), text=True, capture_output=True,
                                timeout=timeout, check=False,
                                env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8'})
    except subprocess.TimeoutExpired:
        raise RecoveryError(label + ' excedeu o tempo permitido.') from None
    require(result.returncode == 0, label + ' falhou (código ' + str(result.returncode) + ').')
    return result.stdout


def service_state():
    names = ('LoadState', 'ActiveState', 'MainPID', 'ControlPID', 'ControlGroup', 'User')
    output = run(['/usr/bin/systemctl', 'show', UNIT, '--no-pager', '--property=' + ','.join(names)],
                 label='Consulta do serviço')
    result = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    require(all(name in result for name in names), 'Estado incompleto do serviço.')
    require(result['LoadState'] == 'loaded' and result['User'] == ACCOUNT,
            'Serviço ou usuário do dashboard inesperado.')
    return result


def empty_cgroup(group):
    require(group.startswith('/') and '..' not in Path(group).parts and Path(group).name == UNIT,
            'Grupo de processos inesperado; recuperação recusada.')
    if (CGROUP_ROOT / 'cgroup.controllers').is_file():
        directory = CGROUP_ROOT / group.lstrip('/')
        if not directory.exists():
            return True
        values = dict(line.split() for line in (directory / 'cgroup.events').read_text().splitlines())
        require(values.get('populated') in ('0', '1'), 'Não foi possível verificar os processos do serviço.')
        return values['populated'] == '0'
    hierarchy = CGROUP_ROOT / 'systemd'
    require((hierarchy / 'cgroup.procs').is_file(), 'Hierarquia de processos não suportada.')
    directory = hierarchy / group.lstrip('/')
    if not directory.exists():
        return True
    for current, _, _ in os.walk(directory):
        if (Path(current) / 'cgroup.procs').read_text().strip():
            return False
    return True


def stop_and_verify(before):
    run(['/usr/bin/systemctl', 'stop', UNIT], timeout=45, label='Parada do dashboard')
    deadline = time.monotonic() + 15
    while True:
        state = service_state()
        group = state['ControlGroup'] or before['ControlGroup']
        require(not state['ControlGroup'] or not before['ControlGroup'] or state['ControlGroup'] == before['ControlGroup'],
                'Grupo de processos mudou durante a parada.')
        if state['ActiveState'] in ('inactive', 'failed') and state['MainPID'] == '0' and state['ControlPID'] == '0' and empty_cgroup(group):
            return
        require(time.monotonic() < deadline, 'Parada não confirmada; nenhuma credencial será alterada.')
        time.sleep(0.25)


def restart_and_verify():
    run(['/usr/bin/systemctl', 'start', UNIT], timeout=45, label='Inicialização do dashboard')
    deadline = time.monotonic() + 20
    while True:
        state = service_state()
        if state['ActiveState'] == 'active' and state['MainPID'].isdigit() and int(state['MainPID']) > 0:
            break
        require(time.monotonic() < deadline, 'Dashboard ainda não está ativo; consulte journalctl -u ' + UNIT)
        time.sleep(0.5)
    return check_local_health()


def check_local_health():
    config = protected(BASE / 'conf/server.properties').read_text()
    ports = re.findall(r'^\s*httpPort\s*[=:]\s*(\d+)\s*$', config, re.MULTILINE)
    port = int(ports[-1]) if ports else 8085
    require(1 <= port <= 65535, 'Porta HTTP local inválida.')
    version = protected(BASE / 'VERSION').read_text().strip()
    require(re.fullmatch(r'\d+\.\d+\.\d+', version) is not None, 'Versão instalada não reconhecida.')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 20
    while True:
        try:
            with opener.open('http://127.0.0.1:' + str(port) + '/health', timeout=2) as response:
                value = json.loads(response.read(4096))
                if response.status == 200 and value.get('status') == 'ok' and value.get('storage') == 'sqlite' and value.get('version') == version:
                    return value
        except (OSError, ValueError):
            pass
        require(time.monotonic() < deadline, 'Serviço iniciado, mas /health local não confirmou a versão e o banco.')
        time.sleep(0.5)


def selected_java():
    launcher = protected(BASE / 'run-server.sh')
    candidates = []
    for line in launcher.read_text().replace('\\\n', '').splitlines():
        tokens = shlex.split(line, comments=True)
        if tokens and tokens[0] == 'exec':
            require(len(tokens) > 2 and 'assessment.AssessmentServer' in tokens,
                    'Comando Java do dashboard não reconhecido.')
            candidates.append(tokens[1])
    require(len(candidates) == 1, 'Não foi possível identificar um único Java no run-server.sh.')
    java = protected_executable(Path(candidates[0]))
    require(java.name == 'java' and os.access(java, os.X_OK), 'Java do dashboard não é executável.')
    return java


def inspect_installation():
    require(sys.platform.startswith('linux') and os.geteuid() == 0, 'Execute este script com sudo na VM Linux do dashboard.')
    require(sys.stdin.isatty() and sys.stdout.isatty(), 'Use um Terminal interativo; senhas não são emitidas em logs ou pipes.')
    account = pwd.getpwnam(ACCOUNT)
    # The parent may be0750/0755; only the newly created backup must be private.
    protected(ROOT_PRIVATE, directory=True)
    protected(BASE, directory=True)
    for name in ('conf', 'classes', 'classes/assessment', 'lib'):
        protected(BASE / name, directory=True)
    protected(BASE / 'conf/server.properties')
    protected(BASE / 'classes/assessment/UserAdminTool.class')
    for path in (BASE / 'classes').rglob('*'):
        protected(path, directory=path.is_dir())
    for path in (BASE / 'lib').glob('*.jar'):
        protected(path)
    protected(BASE / 'data', directory=True, owners=(account.pw_uid,), private=True)
    protected(BASE / 'data/native', directory=True, owners=(account.pw_uid,), private=True)
    protected(BASE / 'data/inventory.sqlite', owners=(account.pw_uid,), private=True)
    for name in ('inventory.sqlite-wal', 'inventory.sqlite-shm', '.human-auth.lock'):
        path = BASE / 'data' / name
        if path.exists() or path.is_symlink():
            protected(path, owners=(account.pw_uid,), private=True)
    runner = shutil.which('runuser', path='/usr/sbin:/usr/bin:/sbin:/bin')
    require(runner is not None, 'runuser não encontrado.')
    runner = str(protected_executable(Path(runner)))
    return account, selected_java(), runner


def database_state():
    database = BASE / 'data/inventory.sqlite'
    with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True, timeout=10)) as connection:
        connection.execute('BEGIN')
        require(connection.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'Integridade do banco não confirmada.')
        tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        marker = None
        if 'metadata' in tables:
            row = connection.execute('SELECT payload FROM metadata WHERE key=?', ('humanAuthActivated',)).fetchone()
            marker = json.loads(row[0]) if row else None
            require(marker is None or isinstance(marker, bool), 'Estado de autenticação desconhecido.')
        count = connection.execute('SELECT count(*) FROM human_users').fetchone()[0] if 'human_users' in tables else 0
        admins = [] if 'human_users' not in tables else [dict(zip(('name', 'email'), row)) for row in
                  connection.execute("SELECT name,email FROM human_users WHERE role='ADMIN' ORDER BY email")]
        return {'bootstrap': count == 0 and marker is not True, 'admins': admins}


def select_identity(state, input_fn=input):
    if state['bootstrap']:
        print('A configuração inicial ainda não foi concluída. Vamos criar o primeiro administrador.')
        name = input_fn('Nome completo: ').strip()
        email = input_fn('Email para entrar: ').strip().lower()
        require(1 <= len(name) <= 120 and not any(ord(c) < 32 or ord(c) == 127 for c in name), 'Nome inválido.')
        mode = 'provision'
    else:
        require(state['admins'], 'Não há ADMIN existente. Recuperação recusada; um usuário Leitura não será promovido.')
        print('Administradores existentes:')
        for admin in state['admins']:
            # Avoid terminal control sequences, even if a database was manually edited.
            print('  ' + json.dumps(admin['email'], ensure_ascii=True))
        email = input_fn('Email do administrador para redefinir: ').strip().lower()
        matches = [admin for admin in state['admins'] if admin['email'] == email]
        require(len(matches) == 1, 'Escolha o email de um administrador existente.')
        name, mode = matches[0]['name'], 'recover'
    require(len(email) <= 254 and re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}", email), 'Email inválido.')
    return {'mode': mode, 'name': name, 'email': email}


def backup_installation():
    folder = Path(tempfile.mkdtemp(prefix='java-assessment-access-', dir=ROOT_PRIVATE))
    folder.chmod(0o700)
    protected(folder, directory=True, private=True)
    for path in (BASE / 'conf').rglob('*'):
        protected(path, directory=path.is_dir())
    shutil.copytree(BASE / 'conf', folder / 'conf', symlinks=False)
    with closing(sqlite3.connect((BASE / 'data/inventory.sqlite').as_uri() + '?mode=ro', uri=True, timeout=10)) as source:
        with closing(sqlite3.connect(folder / 'inventory.sqlite')) as destination:
            source.backup(destination)
            require(destination.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'Backup SQLite não passou na verificação.')
    (folder / 'inventory.sqlite').chmod(0o600)
    return folder


def fix_sidecars(account):
    for name in ('inventory.sqlite-wal', 'inventory.sqlite-shm'):
        path = BASE / 'data' / name
        if path.exists() or path.is_symlink():
            protected(path, owners=(0, account.pw_uid), private=True)
            os.chown(path, account.pw_uid, account.pw_gid)
            path.chmod(0o600)


def private_record(path, identity, password, status):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
    with os.fdopen(os.open(path, flags, 0o600), 'w') as output:
        output.write('Java Assessment — acesso temporário\nEmail: ' + identity['email'] +
                     '\nSenha temporária: ' + password + '\nEstado: ' + status +
                     '\nValidade: 24 horas após aplicação; troca obrigatória no primeiro acesso.\n')
        output.flush(); os.fsync(output.fileno())


def apply_access(account, java, runner, identity, password):
    folder = Path(tempfile.mkdtemp(prefix='.access-recovery-', dir=BASE / 'data'))
    secret = folder / 'password'
    try:
        with os.fdopen(os.open(secret, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'w') as output:
            output.write(password + '\n')
        os.chown(secret, account.pw_uid, account.pw_gid)
        os.chown(folder, account.pw_uid, account.pw_gid)
        output = run([runner, '-u', ACCOUNT, '--', str(java), '-Xmx128m',
                      '-Dorg.sqlite.tmpdir=' + str(BASE / 'data/native'), '-cp',
                      str(BASE / 'classes') + ':' + str(BASE / 'lib/*'), 'assessment.UserAdminTool',
                      '--root', str(BASE), '--mode', identity['mode'], '--name', identity['name'],
                      '--email', identity['email'], '--password-file', str(secret)],
                     timeout=90, label='Recuperação protegida de acesso')
        try:
            result = json.loads(output)
            user = result['user']
            require(result['mode'] == identity['mode'] and user['email'] == identity['email'] and
                    user['role'] == 'ADMIN' and user['mustChangePassword'] is True and
                    user['status'] == 'FIRST_ACCESS', 'Ferramenta não confirmou o administrador esperado.')
            require(re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z', result['expiresAt']) is not None,
                    'Validade da credencial não reconhecida.')
            datetime.datetime.strptime(result['expiresAt'][:19], '%Y-%m-%dT%H:%M:%S')
            return result
        except (ValueError, KeyError, TypeError):
            raise RecoveryError('Resposta da ferramenta de contas não reconhecida.') from None
    finally:
        shutil.rmtree(folder)


def recover(input_fn=input):
    account, java, runner = inspect_installation()
    initial = service_state()
    require(initial['ActiveState'] == 'active', 'O dashboard precisa estar ativo antes da recuperação.')
    identity = select_identity(database_state(), input_fn)
    before = service_state()
    password = secrets.token_urlsafe(24)
    backup = None
    report = {'status': 'not-applied', 'mode': identity['mode'], 'email': identity['email']}
    stop_attempted = False
    failure = None
    try:
        print('Parando o dashboard por alguns segundos e preparando backup privado...')
        stop_attempted = True
        stop_and_verify(before)
        current = database_state()
        require(current['bootstrap'] if identity['mode'] == 'provision' else any(a['email'] == identity['email'] for a in current['admins']),
                'As contas mudaram durante a seleção; execute novamente.')
        backup = backup_installation()
        private_record(backup / 'acesso-temporario.txt', identity, password, 'preparado; consulte resultado.json para confirmação')
        fix_sidecars(account)
        result = apply_access(account, java, runner, identity, password)
        report.update({'status': 'applied', 'expiresAt': result['expiresAt']})
    except (Exception, KeyboardInterrupt) as error:
        failure = error
        report['status'] = 'not-confirmed'
    finally:
        if stop_attempted:
            try:
                fix_sidecars(account)
            except (Exception, KeyboardInterrupt):
                report['sidecarsVerified'] = False
                failure = RecoveryError('Permissões dos arquivos SQLite não confirmadas. Consulte o registro privado.')
            try:
                report['health'] = restart_and_verify()
                report['serviceRestarted'] = True
            except (Exception, KeyboardInterrupt):
                report['serviceRestarted'] = False
                failure = RecoveryError('Reinício não confirmado. Consulte sudo journalctl -u ' + UNIT + ' -n 80 --no-pager')
        if backup:
            (backup / 'resultado.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            (backup / 'resultado.json').chmod(0o600)
            print('Backup e registro de acesso (root): ' + str(backup))
    if failure:
        # Operational output is deliberately generic; subprocess output may contain secrets.
        if isinstance(failure, RecoveryError):
            raise failure
        raise RecoveryError('Recuperação não confirmada. O reinício do serviço foi tentado; consulte o registro privado.') from None
    print('\nAcesso recuperado. Dashboard ativo.')
    print('Email: ' + identity['email'])
    print('Senha temporária: ' + password)
    print('Válida por 24 horas. Entre com email e senha e escolha sua senha pessoal.')
    print('Depois de entrar, remova somente o registro de senha temporária do backup.')
    return report


def main():
    os.umask(0o077)
    descriptor = None
    try:
        require(sys.platform.startswith('linux') and os.geteuid() == 0, 'Execute com sudo na VM Linux do dashboard.')
        descriptor = os.open(LOCK, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        require(stat.S_ISREG(os.fstat(descriptor).st_mode) and os.fstat(descriptor).st_uid == 0,
                'Arquivo de exclusão operacional inseguro.')
        os.fchmod(descriptor, 0o600)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RecoveryError('Outra recuperação já está em andamento.') from None
        # Turn common terminal interruption into a finally path that restarts service.
        for sig in (signal.SIGTERM, signal.SIGHUP):
            signal.signal(sig, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
        recover()
        return 0
    except (Exception, KeyboardInterrupt) as error:
        print('ERRO: ' + (str(error) if isinstance(error, RecoveryError) else 'Recuperação interrompida ou instalação não validada.'), file=sys.stderr)
        return 1
    finally:
        if descriptor is not None:
            os.close(descriptor)


if __name__ == '__main__':
    sys.exit(main())
