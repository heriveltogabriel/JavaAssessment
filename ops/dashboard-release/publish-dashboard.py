#!/usr/bin/env python3
"""Run manually in the user's Terminal after Codex remote-shell review denial."""
import datetime
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

HERE = Path(__file__).resolve().parent
HOST = 'opc@167.126.4.37'
OPTIONS = ['-i', str(Path.home() / 'OneDrive/id_rsa'), '-o', 'IdentitiesOnly=yes',
           '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10']
DASHBOARD_VERSION = '@DASHBOARD_VERSION@'
RUNTIME = 'assessment-dashboard-runtime-@DASHBOARD_VERSION@.zip'

def run(argv, timeout=120):
    result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        if result.stdout:
            print(result.stdout, end='')
        if result.stderr:
            print(result.stderr, end='', file=sys.stderr)
        raise RuntimeError('Command failed with exit code ' + str(result.returncode))
    return result.stdout

def main():
    runtime = HERE / RUNTIME
    if not runtime.is_file():
        runtime = HERE.parent / 'packages' / RUNTIME
    sources = HERE / 'dashboard-update-sources.zip'
    for path, digest in [(runtime, '@RUNTIME_SHA@'),
                         (sources, '@SOURCE_SHA@')]:
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Package checksum mismatch: ' + path.name)
    print('Atualizando somente o dashboard https://167.126.4.37; agentes não serão alterados.', flush=True)
    stage = run(['ssh', *OPTIONS, HOST, 'umask 077; mktemp -d /home/opc/dashboard-update-XXXXXXXX']).strip()
    if not re.fullmatch(r'/home/opc/dashboard-update-[A-Za-z0-9]{8}', stage):
        raise RuntimeError('Unexpected remote staging directory')
    print('Transferindo arquivos verificados para ' + stage, flush=True)
    run(['scp', *OPTIONS, str(runtime), str(sources), str(HERE / 'update-dashboard.py'), HOST + ':' + stage + '/'], timeout=180)
    command = 'sudo -n python3 ' + ' '.join(shlex.quote(stage + '/' + name)
                                         for name in ['update-dashboard.py', RUNTIME, sources.name])
    print('Criando backup privado, publicando e verificando o serviço...', flush=True)
    attempt = subprocess.run(['ssh', *OPTIONS, HOST, command], capture_output=True, text=True, timeout=420)
    try:
        report = json.loads(attempt.stdout)
    except ValueError:
        report = {'status': 'unconfirmed', 'sshReturnCode': attempt.returncode}
    (HERE / 'last-attempt.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if attempt.returncode or report.get('status') != 'deployed' or report.get('dashboardVersion') != DASHBOARD_VERSION or report.get('collectorVersion') != DASHBOARD_VERSION or report.get('health', {}).get('version') != DASHBOARD_VERSION:
        if attempt.stdout:
            print(attempt.stdout, end='')
        if attempt.stderr:
            print(attempt.stderr, end='', file=sys.stderr)
        raise RuntimeError('Deployment was not confirmed; see ' + str(HERE / 'last-attempt.json'))
    # Keep a confirmed deployment receipt even if the independent external check fails.
    (HERE / 'deployment-result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    # Only the public trust certificate is brought to this computer, never private keys/configuration.
    try:
        ca = run(['ssh', *OPTIONS, HOST, 'sudo -n cat /opt/java-assessment-dashboard/conf/agent-ca.pem'])
        (HERE / 'dashboard-public-ca.pem').write_text(ca)
        external = subprocess.run(['curl', '--silent', '--show-error', '--fail', '--connect-timeout', '10',
                                   '--max-time', '15', '--cacert', str(HERE / 'dashboard-public-ca.pem'),
                                   'https://167.126.4.37/health'], capture_output=True, text=True, timeout=20)
        external_health = json.loads(external.stdout) if external.returncode == 0 else {}
        report['externalHttpsVerified'] = external.returncode == 0 and external_health.get('status') == 'ok' and external_health.get('version') == DASHBOARD_VERSION
        report['externalVersion'] = external_health.get('version')
    except Exception as exc:
        report['externalHttpsVerified'] = False
        report['externalHttpsError'] = str(exc)
    report['recordedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (HERE / 'deployment-result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Dashboard e pacote do agente atualizados para v' + DASHBOARD_VERSION + '. Backup preservado em ' + report['backup'])
    print('HTTPS externo: ' + ('validado' if report['externalHttpsVerified'] else 'não confirmado; serviço interno validado'))
    print('Relatório salvo em ' + str(HERE / 'deployment-result.json'))
    print('Abra https://167.126.4.37 e entre novamente. Nenhum agente foi atualizado ou removido.')

if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('Atualização não confirmada: ' + str(exc), file=sys.stderr)
        sys.exit(1)
