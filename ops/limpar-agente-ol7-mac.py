#!/usr/bin/env python3
"""Operator-run removal of the ol07 lab agent using its installed uninstaller."""
import argparse
import ipaddress
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description='Remove somente o agente da VM ol07 e seus downloads reconhecidos; preserva o Java do sistema.')
    parser.add_argument('--host', help='IP da VM ol07; solicitado se omitido')
    args = parser.parse_args()
    host = args.host or input('IP da VM ol07: ').strip()
    try:
        host = str(ipaddress.ip_address(host))
    except ValueError:
        parser.error('Informe somente um endereco IP valido.')
    key = Path.home() / 'OneDrive/id_rsa'
    script = Path(__file__).with_name('limpar-agente-ol7.sh')
    if not key.is_file() or not script.is_file():
        raise RuntimeError('Chave SSH ou script de limpeza nao encontrado.')
    print('Removendo o agente de opc@' + host + '. O script exige hostname ol07 e recusa operacoes pendentes.', flush=True)
    print('O Java do sistema sera preservado. O cadastro antigo no dashboard nao sera excluido automaticamente.', flush=True)
    # Default host-key verification is retained. No credentials or script are uploaded to disk.
    result = subprocess.run(['ssh', '-i', str(key), '-o', 'IdentitiesOnly=yes',
                             '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=15',
                             'opc@' + host, 'sudo -n /bin/sh -s'], input=script.read_bytes())
    if result.returncode:
        raise RuntimeError('Limpeza nao confirmada. Confira a saida acima; nao force a exclusao dos diretorios.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, EOFError, KeyboardInterrupt) as exc:
        print('LIMPEZA_NAO_CONFIRMADA: ' + str(exc), file=sys.stderr)
        sys.exit(1)
