#!/usr/bin/env python3
"""User-run Mac preflight: inspect Linux canaries one at a time, without updates."""
import argparse
import datetime
import ipaddress
from pathlib import Path
import re
import subprocess
import sys
import tempfile

PROJECT = Path(__file__).resolve().parents[1]


def host(value):
    value = value.strip()
    try:
        return str(ipaddress.ip_address(value))
    except ValueError:
        if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?', value):
            raise argparse.ArgumentTypeError('IP ou hostname invalido')
        return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', action='append', type=host, default=[], help='IP da VM; repita para outra maquina')
    parser.add_argument('--user', default='opc', help='Usuario SSH (padrao: opc)')
    parser.add_argument('--identity', type=Path, default=Path.home() / 'OneDrive/id_rsa')
    parser.add_argument('--legacy', action='store_true', help='Compatibilidade SSH antiga para RHEL 5; execute separadamente')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.-]{0,63}', args.user):
        parser.error('Usuario SSH invalido')
    targets = list(args.host)
    if not targets:
        print('Pre-validacao dos agentes: versao, servicos, Java e hashes. Nenhuma instalacao ou reinicio.')
        first = input('IP da primeira VM com agente 1.5.12: ').strip()
        if not first:
            parser.error('Informe o IP de uma VM')
        try:
            targets.append(host(first))
            second = input('IP da segunda VM (Enter para validar somente a primeira): ').strip()
            if second:
                targets.append(host(second))
        except argparse.ArgumentTypeError as error:
            parser.error(str(error))
    targets = list(dict.fromkeys(targets))
    if len(targets) > 2:
        parser.error('Valide no maximo duas VMs por execucao')
    identity = args.identity.expanduser()
    if not identity.is_file():
        parser.error('Chave SSH nao encontrada: ' + str(identity) + '. Use --identity /caminho/da/chave')
    payload = (PROJECT / 'ops/agent-canary-preflight.sh').read_bytes()
    parent = PROJECT / 'output/agent-canaries'
    parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    output = Path(tempfile.mkdtemp(prefix=stamp + '-', dir=parent))
    failed = False
    for position, target in enumerate(targets, 1):
        print('\nConferindo ' + target + ' (' + str(position) + '/' + str(len(targets)) + ')...', flush=True)
        command = ['ssh', '-i', str(identity), '-o', 'ConnectTimeout=12', '-o', 'ServerAliveInterval=15',
                   '-o', 'ServerAliveCountMax=2', '-o', 'StrictHostKeyChecking=ask']
        if args.legacy:
            command += ['-o', 'KexAlgorithms=+diffie-hellman-group14-sha1', '-o', 'HostKeyAlgorithms=+ssh-rsa',
                        '-o', 'PubkeyAcceptedKeyTypes=+ssh-rsa']
        command += [args.user + '@' + target, 'sudo -n /bin/sh -s']
        report = output / ('maquina-' + str(position) + '.txt')
        with report.open('xb') as stream:
            report.chmod(0o600)
            try:
                result = subprocess.run(command, input=payload, stdout=stream, timeout=120)
            except (OSError, subprocess.TimeoutExpired) as error:
                print('Nao foi possivel concluir: ' + str(error), file=sys.stderr)
                failed = True
                continue
        text = report.read_text(encoding='utf-8', errors='replace')
        print(text, end='' if text.endswith('\n') else '\n')
        print('Relatorio privado: ' + str(report), flush=True)
        if result.returncode or 'PREFLIGHT_COMPLETE=READ_ONLY' not in text:
            failed = True
            print('Verificacao incompleta. Se sudo pedir senha, entre na VM e execute o diagnostico com sudo.', file=sys.stderr)
    print('\nEnvie a saida dos relatorios para conferir os perfis antes de preparar a publicacao.')
    print('Pasta dos relatorios: ' + str(output))
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
