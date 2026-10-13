"""Small, dependency-free helpers for a fresh Enterprise Linux dashboard install."""
import ipaddress
import json
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path


def el_major(release_file=Path('/etc/os-release')):
    values = {}
    for line in release_file.read_text(encoding='utf-8').splitlines():
        if '=' in line and not line.startswith('#'):
            key, value = line.split('=', 1)
            values[key] = value.strip().strip('"\'')
    identity = (values.get('ID', '') + ' ' + values.get('ID_LIKE', '')).lower().split()
    if not set(identity).intersection(('ol', 'rhel', 'centos', 'rocky', 'almalinux')):
        raise RuntimeError('This installer supports Oracle Linux/RHEL-compatible systems version 8 or later')
    try:
        major = int(values.get('VERSION_ID', '').split('.')[0])
    except ValueError:
        raise RuntimeError('Cannot determine Enterprise Linux version')
    if major < 8:
        raise RuntimeError('The dashboard requires Enterprise Linux 8 or later')
    return major


def public_ipv4(value):
    try:
        address = ipaddress.ip_address(str(value).strip())
    except ValueError:
        return None
    return str(address) if address.version == 4 and address.is_global else None


def detect_public_ip():
    """Suggest an address; the operator must confirm it before installation."""
    request = urllib.request.Request(
        'http://169.254.169.254/opc/v2/vnics/',
        headers={'Authorization': 'Bearer Oracle'})
    try:
        direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with direct.open(request, timeout=2) as response:
            vnics = json.load(response)
        for vnic in vnics if isinstance(vnics, list) else []:
            found = public_ipv4(vnic.get('publicIp')) if isinstance(vnic, dict) else None
            if found:
                return found, 'OCI instance metadata'
    except (OSError, ValueError, TypeError):
        pass
    parts = os.environ.get('SSH_CONNECTION', '').split()
    if len(parts) == 4:
        found = public_ipv4(parts[2])
        if found:
            return found, 'SSH connection'
    try:
        with urllib.request.urlopen('https://api.ipify.org', timeout=4) as response:
            found = public_ipv4(response.read(128).decode('ascii'))
        if found:
            return found, 'outbound IP lookup (confirm if NAT is used)'
    except (OSError, ValueError, UnicodeError):
        pass
    return None, 'not detected'


def choose_public_url(explicit, input_fn=input, interactive=None):
    if explicit:
        return explicit if explicit.startswith('https://') else 'https://' + explicit
    if interactive is None:
        interactive = sys.stdin.isatty()
    if not interactive:
        raise RuntimeError('Pass --public-url https://IP-or-DNS for unattended installation')
    candidate, source = detect_public_ip()
    default = 'https://' + candidate if candidate else ''
    print('Dashboard address must be reachable by browsers and all collectors.')
    print('Detected:', default or 'none', '(' + source + ')')
    try:
        answer = input_fn('Public HTTPS address [' + (default or 'required') + ']: ').strip()
    except EOFError:
        raise RuntimeError('Public address was not confirmed; use --public-url for unattended installation')
    value = answer or default
    if not value:
        raise RuntimeError('Public address is required')
    return value if value.startswith('https://') else 'https://' + value


def openssl(args):
    completed = subprocess.run(['openssl'] + args, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, check=False)
    if completed.returncode:
        raise RuntimeError('TLS certificate generation failed (' + args[0] + '): ' +
                           completed.stderr.decode('utf-8', 'replace')[-400:].strip())


def generate_tls(directory, host):
    """Create a root CA and an RSA server certificate with a matching SAN."""
    directory = Path(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    ca_key, ca_cert = directory / 'ca.key', directory / 'ca.pem'
    server_key, server_cert = directory / 'server.key', directory / 'server.pem'
    csr, config = directory / 'server.csr', directory / 'server.cnf'
    is_ip = False
    try:
        ipaddress.ip_address(host)
        is_ip = True
    except ValueError:
        pass
    san = ('IP:' if is_ip else 'DNS:') + host
    config.write_text('[v3_server]\nsubjectAltName=' + san +
                      '\nbasicConstraints=critical,CA:FALSE\n'
                      'keyUsage=critical,digitalSignature,keyEncipherment\n'
                      'extendedKeyUsage=serverAuth\n', encoding='ascii')
    openssl(['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072', '-out', str(ca_key)])
    openssl(['req', '-new', '-x509', '-sha256', '-days', '3650', '-key', str(ca_key),
             '-out', str(ca_cert), '-subj', '/CN=Java Assessment Local CA',
             '-addext', 'basicConstraints=critical,CA:TRUE',
             '-addext', 'keyUsage=critical,keyCertSign,cRLSign'])
    openssl(['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(server_key)])
    openssl(['req', '-new', '-sha256', '-key', str(server_key), '-out', str(csr),
             '-subj', '/CN=' + host[:60]])
    openssl(['x509', '-req', '-sha256', '-days', '397', '-in', str(csr), '-CA', str(ca_cert),
             '-CAkey', str(ca_key), '-CAcreateserial', '-out', str(server_cert),
             '-extfile', str(config), '-extensions', 'v3_server'])
    fullchain = directory / 'fullchain.pem'
    fullchain.write_bytes(server_cert.read_bytes() + ca_cert.read_bytes())
    for key in (ca_key, server_key):
        os.chmod(key, 0o600)
    return fullchain, server_key, ca_cert, ca_key


def open_firewall(port):
    """Open the local host firewall only when firewalld is active."""
    if subprocess.run(['systemctl', 'is-active', '--quiet', 'firewalld'],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        return 'firewalld is inactive; no local firewall change'
    if not shutil.which('firewall-cmd'):
        raise RuntimeError('firewalld is active but firewall-cmd is unavailable')
    subprocess.run(['firewall-cmd', '--permanent', '--add-port=' + str(port) + '/tcp'], check=True)
    subprocess.run(['firewall-cmd', '--reload'], check=True)
    return 'local firewalld permits TCP ' + str(port)
