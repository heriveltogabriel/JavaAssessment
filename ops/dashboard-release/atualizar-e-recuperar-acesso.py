#!/usr/bin/env python3
"""Manual Terminal workflow: verified release publication and interactive access recovery."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

HERE = Path(__file__).resolve().parent
VERSION = '@DASHBOARD_VERSION@'
HELPER_SHA256 = '@RECOVERY_SHA@'


def publisher():
    spec = importlib.util.spec_from_file_location('manual_dashboard_publisher', HERE / 'publish-dashboard.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def confirmed_current(module):
    """A local confirmed receipt plus current server version allows recovery retry."""
    try:
        receipt = json.loads((HERE / 'deployment-result.json').read_text())
        if (receipt.get('status') != 'deployed' or receipt.get('dashboardVersion') != VERSION
                or receipt.get('health', {}).get('version') != VERSION):
            return False
    except (OSError, ValueError):
        return False
    actual = module.run(['ssh', *module.OPTIONS, module.HOST,
                         'sudo -n cat /opt/java-assessment-dashboard/VERSION']).strip()
    return actual == VERSION


def main(*, recovery_only=False):
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        raise RuntimeError('Execute este comando diretamente no seu Terminal interativo.')
    helper = HERE / 'recover-access-on-host.py'
    if hashlib.sha256(helper.read_bytes()).hexdigest() != HELPER_SHA256:
        raise RuntimeError('O arquivo de recuperação difere do pacote verificado.')
    module = publisher()
    if recovery_only:
        print('Recuperação de acesso: verificando o dashboard já instalado.', flush=True)
    elif confirmed_current(module):
        print('Dashboard v' + VERSION + ' já publicado; continuando a recuperação.', flush=True)
    else:
        module.main()
    actual = module.run(['ssh', *module.OPTIONS, module.HOST,
                         'sudo -n cat /opt/java-assessment-dashboard/VERSION']).strip()
    if actual != VERSION:
        raise RuntimeError('Versão esperada não confirmada; nenhuma senha foi alterada por este comando.')
    stage = module.run(['ssh', *module.OPTIONS, module.HOST,
                        'umask 077; mktemp -d /home/opc/dashboard-access-XXXXXXXX']).strip()
    if not re.fullmatch(r'/home/opc/dashboard-access-[A-Za-z0-9]{8}', stage):
        raise RuntimeError('Diretório remoto de recuperação inesperado.')
    module.run(['scp', *module.OPTIONS, str(helper), module.HOST + ':' + stage + '/'])
    remote = stage + '/' + helper.name
    # Read once under sudo, verify those exact bytes, then execute in memory.
    code = ('import hashlib; from pathlib import Path\n'
            'p=Path(' + repr(remote) + '); source=p.read_bytes()\n'
            'if hashlib.sha256(source).hexdigest()!=' + repr(HELPER_SHA256) + ': raise RuntimeError("Recovery checksum mismatch")\n'
            'exec(compile(source,str(p),"exec"),{"__name__":"__main__","__file__":str(p)})')
    print('Recuperando acesso. Informe nome/email quando solicitado; a senha temporária aparecerá neste Terminal.', flush=True)
    # Inherit the user's terminal. Never capture credentials into local reports/logs.
    result = subprocess.run(['ssh', '-tt', *module.OPTIONS, module.HOST,
                             'sudo -n python3 -c ' + shlex.quote(code)])
    if result.returncode:
        raise RuntimeError('Recuperação não confirmada. Confira a mensagem acima antes de repetir.')
    print('Abra https://167.126.4.37 e entre com o email e a senha temporária exibidos. Escolha sua senha pessoal com pelo menos 8 caracteres.')


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        print('Operação interrompida: ' + str(error), file=sys.stderr)
        sys.exit(1)
