#!/usr/bin/env python3
"""Retire the fixed root collector locally on Linux/systemd, retaining a backup.

Run from the administrator's home: sudo python3 remover-agente-antigo.py
No network requests, remote shell, credential output or application Java signals.
"""
import os
from contextlib import contextmanager
import fcntl
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile

COLLECTOR = Path('/opt/java-assessment-collector')
HELPER = Path('/opt/java-assessment-uninstall')
UPDATE_HELPER = Path('/opt/java-assessment-update')
LIFECYCLE_LOCK = Path('/opt/java-assessment-lifecycle.lock')
UNIT_DIR = Path('/etc/systemd/system')
BACKUP_PARENT = Path('/root')
PROC = Path('/proc')
CGROUP = Path('/sys/fs/cgroup')
SYSTEMD_RUN = Path('/run/systemd/system')
CRON_DIRS = tuple(Path('/etc') / name for name in ('cron.d', 'cron.hourly', 'cron.daily',
                                                 'cron.weekly', 'cron.monthly'))
CRONTAB = Path('/etc/crontab')
UNITS = ('java-assessment-collector.timer', 'java-assessment-uninstall.service',
         'java-assessment-collector.service')
PROPERTIES = ('LoadState', 'ActiveState', 'MainPID', 'ControlPID', 'ControlGroup',
              'FragmentPath', 'DropInPaths', 'NeedDaemonReload')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def present(path):
    return path.exists() or path.is_symlink()


def run(*args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            universal_newlines=True, timeout=180, close_fds=True)
    require(result.returncode == 0, 'Falha no comando: ' + ' '.join(args[:3]))
    return result.stdout


def trusted(path):
    for member in (path, *path.parents):
        st = member.lstat()
        require(not stat.S_ISLNK(st.st_mode) and st.st_uid == 0 and not st.st_mode & 0o022,
                'Caminho sem protecao esperada: ' + str(member))


def validate_lock(st):
    require(stat.S_ISREG(st.st_mode) and st.st_uid == 0 and st.st_nlink == 1 and
            stat.S_IMODE(st.st_mode) == 0o600,
            'Bloqueio de operacao sem protecao esperada: ' + str(LIFECYCLE_LOCK))


@contextmanager
def lifecycle_lock():
    trusted(LIFECYCLE_LOCK.parent)
    if present(LIFECYCLE_LOCK):
        validate_lock(LIFECYCLE_LOCK.lstat())
    flags = os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK
    fd = os.open(str(LIFECYCLE_LOCK), flags, 0o600)
    try:
        os.set_inheritable(fd, False)
        opened = os.fstat(fd)
        validate_lock(opened)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('Outra operacao do agente esta em andamento; tente novamente depois.') from None
        current = LIFECYCLE_LOCK.lstat()
        validate_lock(current)
        require((opened.st_dev, opened.st_ino) == (current.st_dev, current.st_ino),
                'Bloqueio de operacao mudou; arquivos mantidos.')
        yield
    finally:
        # Keep the lock inode in place; closing the private descriptor releases flock.
        os.close(fd)


def no_update_helper():
    for path in (UPDATE_HELPER, UPDATE_HELPER / 'journal.json', UPDATE_HELPER / 'request.json',
                 COLLECTOR / 'collector-spool/update-request.json'):
        require(not present(path),
                'Atualizador de agente encontrado; use a desinstalacao pelo dashboard. Arquivos mantidos: ' + str(path))


def state(unit):
    result = subprocess.run(['systemctl', 'show', unit, '--property=' + ','.join(PROPERTIES)],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            universal_newlines=True, timeout=30, close_fds=True)
    fields = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
    require(result.returncode == 0 or fields.get('LoadState') == 'not-found',
            'Nao foi possivel consultar o servico: ' + unit)
    require(fields.get('LoadState') in ('loaded', 'not-found'),
            'Unidade alterada ou indisponivel: ' + unit)
    return fields


def verify_unit(unit):
    fields = state(unit)
    path = UNIT_DIR / unit
    require(not fields.get('DropInPaths'), 'Override de servico encontrado: ' + unit)
    if fields['LoadState'] == 'not-found':
        require(not present(path), 'Definicao nao carregada: ' + str(path))
        return False
    require(fields.get('FragmentPath') == str(path), 'Local de unidade inesperado: ' + unit)
    require(fields.get('NeedDaemonReload') == 'no', 'Systemd precisa recarregar definicoes; confira a unidade: ' + unit)
    require(path.is_file(), 'Definicao ausente: ' + unit)
    trusted(path)
    content = path.read_text()
    commands = re.findall(r'^\s*(Exec\w+)\s*=([^\n]*)', content, re.M)
    if unit.endswith('.timer'):
        targets = re.findall(r'^\s*Unit\s*=([^\n]*)', content, re.M)
        require(not commands and targets == ['java-assessment-collector.service'],
                'Timer nao reconhecido: ' + unit)
    else:
        expected = '/bin/sh ' + (str(HELPER) + '/uninstall-service.sh loop'
                                 if unit == 'java-assessment-uninstall.service'
                                 else str(COLLECTOR) + '/root-service.sh once')
        require(commands == [('ExecStart', expected)], 'Comandos de servico nao reconhecidos: ' + unit)
        require(re.search(r'^KillMode=(?!control-group\s*$)', content, re.M) is None,
                'KillMode de servico alterado: ' + unit)
    return True


def no_pending_operation():
    no_update_helper()
    for path in (COLLECTOR / '.root-install-lock', COLLECTOR / 'collector-spool/.install-lock',
                 HELPER / 'request.json', HELPER / 'claim.json',
                 COLLECTOR / 'collector-spool/uninstall-request.json'):
        require(not present(path), 'Operacao pendente; arquivos mantidos: ' + str(path))


def no_cron():
    # This utility supports the fixed systemd installation, not user/cron installs.
    for account in run('getent', 'passwd').splitlines():
        name = account.split(':', 1)[0]
        require(re.fullmatch(r'[A-Za-z0-9_.-]+', name) is not None, 'Conta local nao reconhecida.')
        result = subprocess.run(['crontab', '-u', name, '-l'], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, universal_newlines=True, timeout=15,
                                env=dict(os.environ, LC_ALL='C'), close_fds=True)
        require(result.returncode == 0 or (result.returncode == 1 and 'no crontab' in result.stderr.lower()),
                'Nao foi possivel verificar crontab de ' + name)
        require(not re.search(r'java-assessment|assessment-collector', result.stdout),
                'Instalacao cron encontrada; este script atende somente systemd.')
    for directory in CRON_DIRS:
        for path in directory.glob('*'):
            if path.is_file():
                require(not re.search(rb'java-assessment|assessment-collector', path.read_bytes()),
                        'Agendamento alternativo encontrado: ' + str(path))
    if CRONTAB.is_file():
        require(not re.search(rb'java-assessment|assessment-collector', CRONTAB.read_bytes()),
                'Agendamento alternativo em /etc/crontab.')


def stopped(unit):
    fields = state(unit)
    require(fields.get('ActiveState') in ('inactive', 'failed') and
            fields.get('MainPID', '0') == '0' and fields.get('ControlPID', '0') == '0',
            'Servico ainda ativo; arquivos mantidos: ' + unit)
    group = fields.get('ControlGroup', '')
    if group:
        require(group.startswith('/') and '..' not in Path(group).parts, 'Cgroup inesperado.')
        bases = [CGROUP / group.lstrip('/'), CGROUP / 'systemd' / group.lstrip('/')]
        for base in bases:
            if base.is_dir():
                for path in base.rglob('cgroup.procs'):
                    require(not path.read_text().strip(), 'Processo residual no cgroup: ' + unit)


def no_processes():
    prefixes = (str(COLLECTOR) + '/', str(HELPER) + '/')
    for entry in PROC.iterdir():
        if not entry.name.isdigit() or int(entry.name) == os.getpid():
            continue
        try:
            args = (entry / 'cmdline').read_bytes().replace(b'\0', b' ').decode(errors='replace')
        except FileNotFoundError:
            require(not entry.exists(), 'Nao foi possivel verificar processo PID ' + entry.name)
            continue
        require(not any(prefix in args for prefix in prefixes),
            'Processo ainda usa o agente (PID ' + entry.name + '); arquivos mantidos.')
        links = [entry / 'cwd', entry / 'exe']
        try:
            links.extend((entry / 'fd').iterdir())
        except FileNotFoundError:
            require(not entry.exists(), 'Nao foi possivel verificar processo PID ' + entry.name)
            continue
        for link in links:
            try:
                value = os.readlink(link)
            except FileNotFoundError:
                continue  # A single descriptor closed; keep inspecting this PID.
            require(not any(value == prefix[:-1] or value.startswith(prefix) for prefix in prefixes),
                    'Processo ainda usa o agente (PID ' + entry.name + '); arquivos mantidos.')


def main():
    require(sys.platform.startswith('linux') and os.geteuid() == 0,
            'Execute na VM Linux: sudo python3 remover-agente-antigo.py')
    os.umask(0o077)
    os.environ['PATH'] = '/usr/sbin:/usr/bin:/sbin:/bin'
    os.environ['LC_ALL'] = 'C'
    require(SYSTEMD_RUN.is_dir(), 'Este script atende instalacao --system-root com systemd.')
    with lifecycle_lock():
        remove_legacy_installation()


def remove_legacy_installation():
    # Refuse updater-enabled installations before inspecting or stopping any unit.
    no_update_helper()
    # All paths and service names are fixed; callers cannot choose deletion targets.
    trusted(BACKUP_PARENT)
    trusted(UNIT_DIR)
    require(UNIT_DIR.stat().st_dev == BACKUP_PARENT.stat().st_dev,
            'Unidades e backup estao em filesystems diferentes; requer copia verificada.')
    no_pending_operation()
    for root in (COLLECTOR, HELPER):
        if present(root):
            trusted(root)
            require(root.is_dir(), 'Diretorio esperado: ' + str(root))
            require(root.stat().st_dev == BACKUP_PARENT.stat().st_dev,
                    'Backup e agente estao em filesystems diferentes; requer copia verificada.')
            require(not (root / 'web').exists() and not (root / 'conf/server.properties').exists(),
                    'Possivel dashboard no diretorio; nenhuma remocao permitida.')
            for name in ('collector.jar', 'root-service.sh') if root == COLLECTOR else ('collector.jar', 'uninstall-service.sh'):
                require((root / name).is_file(), 'Instalacao nao reconhecida: ' + str(root))
                trusted(root / name)
            for line in (PROC / 'self/mountinfo').read_text().splitlines():
                mount = line.split()[4]
                require(mount != str(root) and not mount.startswith(str(root) + '/'),
                        'Montagem dentro do agente; arquivos mantidos.')
    active = [unit for unit in UNITS if verify_unit(unit)]
    if not active and not present(COLLECTOR) and not present(HELPER):
        print('AGENTE_JA_AUSENTE')
        return
    no_cron()
    backup = Path(tempfile.mkdtemp(prefix='java-assessment-removido-', dir=str(BACKUP_PARENT)))
    print('BACKUP=' + str(backup), flush=True)
    (backup / 'README.txt').write_text('Backup da remocao local. Pode conter credenciais. Manter acesso exclusivo de root.\n')
    # Timer first, then uninstall executor, then collector. Never signal application JVMs.
    for unit in active:
        run('systemctl', 'stop', unit)
        stopped(unit)
    no_pending_operation()
    no_processes()
    for unit in active:
        run('systemctl', 'disable', unit)
    for unit in active:
        stopped(unit)
        require(verify_unit(unit), 'Unidade mudou durante a remocao: ' + unit)
    for path in [*(UNIT_DIR / unit for unit in active), COLLECTOR, HELPER]:
        if present(path):
            os.rename(path, backup / path.name)
    run('systemctl', 'daemon-reload')
    for unit in active:
        require(state(unit)['LoadState'] == 'not-found', 'Unidade ainda presente: ' + unit)
    require(not present(COLLECTOR) and not present(HELPER), 'Diretorio ainda presente.')
    print('AGENTE_REMOVIDO: servicos e instalacao retirados; backup preservado.')
    print('O historico e a credencial no dashboard nao foram alterados. Revogue o cadastro antigo no dashboard.')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('REMOCAO_NAO_CONCLUIDA: ' + str(exc), file=sys.stderr)
        sys.exit(1)
