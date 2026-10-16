#!/usr/bin/env python3
"""Install a fresh Java Assessment dashboard on a systemd Linux host."""
import argparse
import hashlib
import http.client
import ipaddress
import json
import os
import pwd
import re
import secrets
import shlex
import shutil
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import time
import urllib.parse
import warnings
import zipfile
from pathlib import Path

# Imported helpers must not create __pycache__ files before manifest validation.
sys.dont_write_bytecode = True
from bootstrap import choose_public_url, el_major, generate_tls, open_firewall
from dependencies import verify_libraries
from agent_updates import distribution_assets

SOURCE = Path(__file__).resolve().parent
DEST = Path('/opt/java-assessment-dashboard')
UNIT = Path('/etc/systemd/system/java-assessment-dashboard.service')
ACCOUNT = 'java-assessment-dashboard'
PASSWORD_RECORD = Path('/root/java-assessment-dashboard-admin.txt')
CA_KEY_RECORD = Path('/root/java-assessment-dashboard-ca.key')


def fail(message):
    raise RuntimeError(message)


def command(args, *, input_data=None):
    result = subprocess.run(args, input=input_data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False,
                            timeout=60 if Path(args[0]).name == "openssl" else None)
    if result.returncode:
        if Path(args[0]).name == 'openssl':
            detail = result.stderr.decode('utf-8', 'replace').lower()
            reasons = [('unable to get local issuer certificate', 'CA or intermediate certificate missing'),
                       ('certificate has expired', 'certificate expired'),
                       ('unable to load', 'certificate or key file could not be read'),
                       ('key values mismatch', 'certificate and private key do not match')]
            reason = next((safe for needle, safe in reasons if needle in detail), 'verify certificate, key and CA files')
            fail('OpenSSL ' + args[1] + ' failed: ' + reason)
        fail(f"Command failed: {Path(args[0]).name} ({result.returncode})")
    return result.stdout


def parse_url(value):
    url = urllib.parse.urlsplit(value)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password
            or url.path not in ('', '/') or url.query or url.fragment or value.endswith('/')):
        fail('Use a public HTTPS origin such as https://dashboard.example.com:8445')
    try:
        port = url.port or 443
    except ValueError:
        fail('Invalid HTTPS port')
    if not 1 <= port <= 65535 or port == 8085:
        fail('HTTPS port must be 1–65535 and different from the local HTTP port 8085')
    host = url.hostname
    if ':' in host:
        fail('Use an IPv4 address or DNS name for this installer')
    if host.lower() in ('localhost',) or host.startswith('127.'):
        fail('The public URL must be reachable from collector machines')
    try:
        if ipaddress.ip_address(host).is_loopback:
            fail('The public URL must not use a loopback address')
    except ValueError:
        if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?', host):
            fail('Invalid dashboard hostname')
    return host, port


def regular_file(path, label, allow_symlink=False):
    path = Path(path)
    if not allow_symlink and path.is_symlink():
        fail(f'{label} must not be a symbolic link')
    try:
        path = path.resolve(strict=True)
    except (OSError, RuntimeError):
        fail(f'{label} must be an existing regular file')
    if not path.is_file():
        fail(f'{label} must be an existing regular file')
    return path


def verify_manifest(root=None):
    root = SOURCE if root is None else Path(root)
    manifest = regular_file(root / 'SHA256SUMS', 'Package manifest')
    expected = {}
    for line in manifest.read_text(encoding='utf-8').splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9_./-]+)', line)
        if not match or match.group(2) in expected:
            fail('Package manifest is invalid')
        expected[match.group(2)] = match.group(1)
    actual = {str(p.relative_to(root)) for p in root.rglob('*')
              if p.is_file() and p.resolve() != manifest}
    if actual != set(expected):
        fail('Package contents do not match the manifest')
    for name, digest in expected.items():
        file = root / name
        if file.is_symlink() or '..' in Path(name).parts:
            fail('Package contains an unsafe path')
        if hashlib.sha256(file.read_bytes()).hexdigest() != digest:
            fail(f'Package checksum failed: {name}')


def validate_java(home):
    home = Path(home).resolve()
    if not home.is_absolute() or not (home / 'bin/java').is_file() or not (home / 'bin/javac').is_file():
        fail('Provide --java-home for an existing JDK 17 or later with java and javac')
    version = command([str(home / 'bin/javac'), '-version']).decode('utf-8', 'replace')
    match = re.search(r'\b(?:javac\s+)?(?:1\.)?(\d+)(?:\.\d+)*', version)
    if not match or int(match.group(1)) < 17:
        fail('The dashboard installer requires JDK 17 or later')
    command([str(home / 'bin/java'), '-version'])
    return home


def find_java(explicit):
    candidates = []
    if explicit:
        candidates.append(explicit)
    else:
        if os.environ.get('JAVA_HOME'):
            candidates.append(os.environ['JAVA_HOME'])
        javac = shutil.which('javac')
        if javac:
            candidates.append(str(Path(javac).resolve().parent.parent))
        for base in (Path('/usr/lib/jvm'), Path('/usr/java'), Path('/opt/java'), Path('/opt/jdk')):
            if base.is_dir():
                candidates.extend(str(p) for p in sorted(base.iterdir()) if p.is_dir())
    for candidate in candidates:
        try:
            return validate_java(candidate)
        except (RuntimeError, OSError):
            if explicit:
                raise
    fail('JDK 17+ not found. Install one or pass --java-home /path/to/jdk')


def validate_certificate(cert, key, ca, host):
    cert = regular_file(cert, 'TLS certificate chain', allow_symlink=True)
    key = regular_file(key, 'TLS private key', allow_symlink=True)
    ca = regular_file(ca, 'Collector trust CA', allow_symlink=True)
    if '-----BEGIN PRIVATE KEY-----' not in key.read_text(encoding='ascii'):
        fail('TLS private key must be unencrypted RSA PKCS#8 PEM')
    command(['openssl', 'rsa', '-in', str(key), '-passin', 'pass:', '-check', '-noout'])
    leaf_key = command(['openssl', 'x509', '-in', str(cert), '-pubkey', '-noout'])
    private_public = command(['openssl', 'pkey', '-in', str(key), '-passin', 'pass:', '-pubout'])
    if leaf_key != private_public:
        fail('TLS certificate and private key do not match')
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', DeprecationWarning)
        try:
            ssl.match_hostname(ssl._ssl._test_decode_cert(str(cert)), host)
        except (ssl.CertificateError, ValueError) as error:
            fail(f'TLS certificate does not match dashboard address: {error}')
    command(['openssl', 'verify', '-CAfile', str(ca), '-untrusted', str(cert), str(cert)])
    if b'CA:TRUE' not in command(['openssl', 'x509', '-in', str(ca), '-text', '-noout']):
        fail('Collector trust file must contain a CA certificate')
    if cert.stat().st_size > 512 * 1024 or key.stat().st_size > 64 * 1024 or ca.stat().st_size > 512 * 1024:
        fail('TLS file exceeds the server or collector size limit')
    return cert, key, ca


def read_password(path):
    if path is None:
        return secrets.token_urlsafe(24)
    file = regular_file(path, 'Administrator password file')
    if stat.S_IMODE(file.stat().st_mode) not in (0o600, 0o400):
        fail('Administrator password file must be mode 600 or 400')
    if file.stat().st_uid not in (0, os.geteuid()):
        fail('Administrator password file must belong to root or the installing user')
    password = file.read_text(encoding='utf-8').rstrip('\r\n')
    if len(password) < 8 or len(password) > 128 or '\n' in password or '\r' in password:
        fail('Administrator password must contain 8–128 characters on one line')
    return password


def admin_identity(name, email, *, input_fn=input, interactive=None):
    interactive = sys.stdin.isatty() if interactive is None else interactive
    if not name or not email:
        if not interactive:
            fail('Provide --admin-name and --admin-email for the first administrator')
        name = name or input_fn('First administrator name: ')
        email = email or input_fn('First administrator email: ')
    name, email = name.strip(), email.strip().lower()
    if not 1 <= len(name) <= 120 or any(ord(c) < 32 or ord(c) == 127 for c in name):
        fail('Administrator name must contain 1–120 printable characters')
    if len(email) > 254 or not re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}", email):
        fail('Provide a valid administrator email')
    return name, email


def provision_admin(stage, java_home, name, email, password):
    """Run the same protected account tool used for recovery, before service startup."""
    # Keep the temporary input out of the dashboard tree and remove it even on failure.
    with tempfile.TemporaryDirectory(prefix='.assessment-first-admin-', dir=stage.parent) as folder:
        secret = Path(folder) / 'password'
        write_private(secret, password + '\n')
        command([str(java_home / 'bin/java'), '-Xmx128m',
                 '-Dorg.sqlite.tmpdir=' + str(stage / 'data/native'),
                 '-cp', str(stage / 'classes') + ':' + str(stage / 'lib/*'),
                 'assessment.UserAdminTool', '--root', str(stage), '--mode', 'provision',
                 '--name', name, '--email', email, '--password-file', str(secret)])


def repackage_collector(source, destination, ca_bytes):
    if not ca_bytes.startswith(b'-----BEGIN CERTIFICATE-----') or len(ca_bytes) > 512 * 1024:
        fail('Collector CA must be a PEM certificate bundle up to 512 KiB')
    replacements = {'conf/ca.pem': ca_bytes}
    with zipfile.ZipFile(source) as original:
        names = original.namelist()
        if len(names) != len(set(names)) or 'conf/ca.pem' not in names or 'SHA256SUMS' not in names:
            fail('Generic collector ZIP is incomplete or contains duplicate entries')
        if any(name.startswith('/') or '..' in Path(name).parts for name in names):
            fail('Generic collector ZIP contains an unsafe path')
        if 'DEPENDENCIES.json' in names:
            metadata = json.loads(original.read('DEPENDENCIES.json'))
            metadata['ca'] = {'source': 'dashboard installer input',
                              'sha256': hashlib.sha256(ca_bytes).hexdigest()}
            replacements['DEPENDENCIES.json'] = json.dumps(metadata, indent=2).encode('utf-8')
        hashes = []
        with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as configured:
            for name in names:
                if name == 'SHA256SUMS':
                    continue
                data = replacements[name] if name in replacements else original.read(name)
                configured.writestr(name, data)
                hashes.append(f'{hashlib.sha256(data).hexdigest()}  {name}')
            configured.writestr('SHA256SUMS', '\n'.join(hashes) + '\n')


def write_private(path, content, mode=0o600):
    with open(path, 'w', encoding='utf-8') as output:
        output.write(content)
    os.chmod(path, mode)


def legacy_files(directory):
    """Read the two legacy inputs only; reject incomplete identity migrations."""
    if directory is None:
        return {}
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        fail('--legacy-data must be a regular directory containing both legacy JSON files')
    content = {}
    documents = {}
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail('Legacy JSON contains a duplicate property')
            result[key] = value
        return result
    def reject_constant(value):
        fail('Legacy JSON contains a non-finite number')
    for name, limit in (('state.json', 32 * 1024 * 1024), ('auth-registry.json', 8 * 1024 * 1024)):
        path = regular_file(directory / name, 'Legacy ' + name)
        if path.stat().st_mode & 0o077:
            fail('Legacy ' + name + ' must be mode 600 or 400')
        if path.stat().st_size > limit:
            fail('Legacy ' + name + ' exceeds the supported migration limit')
        with path.open('rb') as source:
            content[name] = source.read(limit + 1)
        if len(content[name]) > limit:
            fail('Legacy ' + name + ' exceeds the supported migration limit')
        documents[name] = json.loads(content[name].decode('utf-8'), object_pairs_hook=unique_object,
                                     parse_constant=reject_constant)
        if not isinstance(documents[name], dict):
            fail('Legacy JSON must contain an object: ' + name)
    hosts = documents['state.json'].get('hosts')
    identities = documents['auth-registry.json'].get('hosts')
    if not isinstance(hosts, list) or not isinstance(identities, dict):
        fail('Legacy inventory and identity registry must both contain hosts')
    host_ids = []
    for host in hosts:
        if not isinstance(host, dict) or not isinstance(host.get('id'), str) or not host['id']:
            fail('Legacy inventory contains an invalid host identity')
        host_ids.append(host['id'])
    if len(host_ids) != len(set(host_ids)) or set(host_ids) != set(identities):
        fail('Legacy inventory hosts and identity registry do not match; use a consistent full backup')
    for host_id, identity in identities.items():
        if (not isinstance(identity, dict) or identity.get('hostId') != host_id or
                not isinstance(identity.get('agentToken'), str) or not identity['agentToken']):
            fail('Legacy identity registry contains an invalid host credential')
    return content


def build_stage(stage, java_home, url, host, port, cert, key, ca, password, legacy=None,
                *, admin_name, admin_email):
    verify_libraries(SOURCE / 'lib')
    for name in ('shared', 'server', 'web', 'lib', 'maintenance'):
        shutil.copytree(SOURCE / name, stage / name)
    for name in ('VERSION', 'BETA_RELEASE.md', 'BETA_MIGRATION.md', 'DatabaseTool.java'):
        shutil.copyfile(SOURCE / name, stage / name)
    for name in ('classes', 'conf/tls', 'data/native', 'dist', 'logs'):
        (stage / name).mkdir(parents=True, exist_ok=True)
    for name, content in (legacy or {}).items():
        if name not in ('state.json', 'auth-registry.json'):
            fail('Unsupported legacy input')
        (stage / 'data' / name).write_bytes(content)
        os.chmod(stage / 'data' / name, 0o600)
    shutil.copyfile(cert, stage / 'conf/tls/fullchain.pem')
    shutil.copyfile(key, stage / 'conf/tls/privkey.pem')
    shutil.copyfile(ca, stage / 'conf/agent-ca.pem')
    repackage_collector(SOURCE / 'dist/assessment-collector.zip',
                        stage / 'dist/assessment-collector.zip', ca.read_bytes())
    updates = distribution_assets(SOURCE, (SOURCE / 'VERSION').read_text().strip())
    for name, content in updates.items():
        target = stage / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    sources = [str(f) for directory in ('shared', 'server') for f in sorted((stage / directory).glob('*.java'))]
    command([str(java_home / 'bin/javac'), '--release', '17', '-encoding', 'UTF-8',
             '--add-modules', 'jdk.httpserver', '-cp', str(stage / 'lib/*'),
             '-d', str(stage / 'classes')] + sources)
    host_id = 'bootstrap-' + secrets.token_hex(12)
    props = {'agentToken': secrets.token_hex(32), 'hostId': host_id,
             'seedLegacyHost': 'false', 'fleetName': 'Java Assessment',
             'jobHistoryPerHost': '100', 'receiptHistoryPerHost': '200',
             'httpBind': '127.0.0.1',
             'httpsBind': '0.0.0.0', 'httpPort': '8085', 'httpsPort': str(port),
             'requireHttps': 'true', 'publicHost': host + (f':{port}' if port != 443 else ''),
             'publicUrl': url}
    write_private(stage / 'conf/server.properties',
                  ''.join(f'{name}={value}\n' for name, value in props.items()))
    write_private(stage / 'run-server.sh',
                  '#!/bin/sh\nset -eu\numask 077\n'
                  f'cd {shlex.quote(str(DEST))}\n'
                  f'exec {shlex.quote(str(java_home / "bin/java"))} -Xms1536m -Xmx1536m '
                  '-XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom '
                  f'-Dorg.sqlite.tmpdir={shlex.quote(str(DEST / "data/native"))} '
                  '-XX:ErrorFile=logs/hs_err_pid%p.log '
                  '-Xlog:gc:file=logs/gc.log:time,uptime,level:filecount=3,filesize=5m '
                  '--add-modules jdk.httpserver '
                  f'-cp "classes:lib/*" assessment.AssessmentServer {shlex.quote(str(DEST))}\n', 0o750)
    write_private(stage / 'database.sh',
                  '#!/bin/sh\nset -eu\numask 077\n'
                  f'cd {shlex.quote(str(DEST))}\n'
                  f'exec {shlex.quote(str(java_home / "bin/java"))} -Xmx128m '
                  f'-Dorg.sqlite.tmpdir={shlex.quote(str(DEST / "data/native"))} '
                  '-cp "lib/*" DatabaseTool.java "$@"\n', 0o750)
    for directory in (stage / 'conf', stage / 'conf/tls', stage / 'data',
                      stage / 'data/native', stage / 'logs'):
        os.chmod(directory, 0o700)
    for path in (stage / 'conf/tls/fullchain.pem', stage / 'conf/tls/privkey.pem',
                 stage / 'conf/agent-ca.pem'):
        os.chmod(path, 0o600)
    provision_admin(stage, java_home, admin_name, admin_email, password)


def set_stage_ownership(stage, account):
    """Keep code root-owned; make private runtime files writable before first boot."""
    for current, directories, files in os.walk(stage):
        current = Path(current)
        relative = current.relative_to(stage)
        writable = relative.parts and relative.parts[0] in ('data', 'logs')
        uid = account.pw_uid if writable else 0
        os.chown(current, uid, account.pw_gid)
        os.chmod(current, 0o700 if writable else 0o750)
        for name in files:
            path = current / name
            if path.is_symlink():
                fail('Staged installation contains a symbolic link')
            os.chown(path, uid, account.pw_gid)
            os.chmod(path, 0o600 if writable else (0o750 if name in ('run-server.sh', 'database.sh') else 0o640))


def service_unit(port):
    capability = ('AmbientCapabilities=CAP_NET_BIND_SERVICE\n'
                  'CapabilityBoundingSet=CAP_NET_BIND_SERVICE\n') if port < 1024 else ''
    return ('[Unit]\nDescription=Java Assessment dashboard\nAfter=network-online.target\n'
            'Wants=network-online.target\nStartLimitIntervalSec=60\nStartLimitBurst=5\n'
            '\n[Service]\nType=simple\n'
            f'User={ACCOUNT}\nGroup={ACCOUNT}\nWorkingDirectory={DEST}\n'
            f'ExecStart=/bin/sh {DEST}/run-server.sh\nRestart=on-failure\nRestartSec=3\n'
            'UMask=0077\nTimeoutStopSec=30\nStandardOutput=journal\nStandardError=journal\n'
            'MemoryAccounting=true\nMemoryMax=2G\n'
            'NoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\n'
            f'ReadWritePaths={DEST}/data {DEST}/logs\n' + capability +
            '\n[Install]\nWantedBy=multi-user.target\n')


def health_check(host, port, ca):
    context = ssl.create_default_context(cafile=str(ca))
    expected = (SOURCE / 'VERSION').read_text().strip()
    last = 'no response'
    for _ in range(20):
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=2) as plain:
                with context.wrap_socket(plain, server_hostname=host) as secure:
                    secure.settimeout(2)
                    secure.sendall(('GET /health HTTP/1.1\r\nHost: ' + host + '\r\nConnection: close\r\n\r\n').encode('ascii'))
                    response = http.client.HTTPResponse(secure)
                    response.begin()
                    body = response.read(4097)
                    if response.status != 200 or len(body) > 4096:
                        raise ValueError('unexpected HTTP status or body size')
                    payload = json.loads(body.decode('utf-8'))
                    if (isinstance(payload, dict) and payload.get('status') == 'ok'
                            and payload.get('storage') == 'sqlite' and payload.get('version') == expected):
                        return
                    last = 'unexpected application version or storage'
        except ssl.CertificateError:
            fail('Dashboard TLS certificate verification failed; verify CA, chain and DNS/IP SAN')
        except (OSError, ssl.SSLError, ValueError, http.client.HTTPException) as error:
            last = type(error).__name__
        time.sleep(1)
    fail('Dashboard process is not ready: TLS /health failed (' + last + '). Inspect journalctl -u java-assessment-dashboard')


def ensure_java(explicit, allow_install):
    try:
        return find_java(explicit)
    except RuntimeError:
        if explicit or not allow_install:
            raise
    if not shutil.which('dnf'):
        fail('JDK 17+ not found and dnf is unavailable; install a JDK or use --java-home')
    for package in ('java-17-openjdk-devel', 'jdk-17-headful'):
        print('Installing JDK package:', package, flush=True)
        result = subprocess.run(['dnf', '-y', 'install', package], check=False)
        if result.returncode == 0:
            try:
                return find_java(None)
            except RuntimeError:
                pass
    fail('Unable to install JDK 17. Check dnf repositories or use --java-home')


def save_root_secret(path, value):
    if path.exists() or path.is_symlink():
        fail('Refusing to overwrite root-only credential file: ' + str(path))
    with open(path, 'x', encoding='utf-8') as output:
        output.write(value)
    os.chmod(path, 0o600)


def install(args):
    verify_manifest()
    verify_libraries(SOURCE / 'lib')
    distribution_assets(SOURCE, (SOURCE / 'VERSION').read_text().strip())
    if sys.platform != 'linux' and not args.check:
        fail('Run this installer on Enterprise Linux 8 or later')
    if not args.check:
        if os.geteuid() != 0:
            fail('Run the installation as root, or execute ./install.sh to use sudo')
        el_major()
        if Path('/proc/1/comm').read_text().strip() != 'systemd':
            fail('This dashboard installer requires systemd as PID 1')
        if DEST.exists() or DEST.is_symlink() or UNIT.exists() or UNIT.is_symlink():
            fail('A dashboard installation already exists; this ZIP installs fresh servers only')
        if (PASSWORD_RECORD.exists() or PASSWORD_RECORD.is_symlink() or
                CA_KEY_RECORD.exists() or CA_KEY_RECORD.is_symlink()):
            fail('Existing dashboard credentials found in /root; inspect them before reinstalling')
        try:
            pwd.getpwnam(ACCOUNT)
        except KeyError:
            pass
        else:
            fail('Dedicated service account already exists; inspect it before installation')
    url = choose_public_url(args.public_url)
    host, port = parse_url(url)
    admin_name, admin_email = admin_identity(getattr(args, 'admin_name', None),
                                           getattr(args, 'admin_email', None))
    java_home = ensure_java(args.java_home, not args.check and not args.no_package_install)
    legacy = legacy_files(getattr(args, 'legacy_data', None))
    if bool(args.tls_cert) != bool(args.tls_key):
        fail('Provide both --tls-cert and --tls-key, or neither for automatic TLS')
    if args.agent_ca and not args.tls_cert:
        fail('--agent-ca applies to a supplied TLS certificate; automatic TLS creates its own CA')
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='.assessment-tls-', dir='/root' if not args.check else None) as tls_temp:
        ca_key = None
        if args.tls_cert:
            cert, key, ca = validate_certificate(args.tls_cert, args.tls_key,
                                                 args.agent_ca or SOURCE / 'agent-ca.pem', host)
        else:
            cert, key, ca, ca_key = generate_tls(tls_temp, host)
            cert, key, ca = validate_certificate(cert, key, ca, host)
        password = read_password(args.admin_password_file)
        if args.check:
            print('PRECHECK OK: package, JDK, dashboard URL and TLS identity')
            return
        stage = Path(tempfile.mkdtemp(prefix='.java-assessment-dashboard-', dir='/opt'))
        try:
            build_stage(stage, java_home, url, host, port, cert, key, ca, password, legacy,
                        admin_name=admin_name, admin_email=admin_email)
            command(['useradd', '--system', '--user-group', '--home-dir', str(DEST),
                     '--shell', '/sbin/nologin', ACCOUNT])
            account = pwd.getpwnam(ACCOUNT)
            set_stage_ownership(stage, account)
            os.replace(stage, DEST)
            write_private(UNIT, '# Java Assessment dashboard managed by install.py\n' + service_unit(port), 0o644)
            save_root_secret(PASSWORD_RECORD, 'URL: ' + url + '\nName: ' + admin_name +
                             '\nEmail: ' + admin_email + '\nTemporary password: ' + password +
                             '\nExpires: 24 hours after provisioning; change required at first login.\n')
            if ca_key:
                save_root_secret(CA_KEY_RECORD, ca_key.read_text(encoding='ascii'))
            command([sys.executable, str(DEST / 'maintenance/install.py')])
            command(['systemctl', 'daemon-reload'])
            command(['systemctl', 'enable', '--now', UNIT.name])
            health_check(host, port, DEST / 'conf/agent-ca.pem')
        finally:
            if stage.exists():
                shutil.rmtree(stage)
    try:
        print('Firewall:', open_firewall(port))
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print('WARNING: local firewall could not be configured:', error, file=sys.stderr)
    print(f'INSTALLED: {url}')
    print('First administrator: ' + admin_email)
    print('Temporary access (24h): sudo cat ' + str(PASSWORD_RECORD))
    if ca_key:
        print('Browser trust CA: ' + str(DEST / 'conf/agent-ca.pem'))
        print('The generated certificate is private to this installation; browsers may ask you to trust its CA.')
    print('OCI network: allow inbound TCP ' + str(port) + ' in the instance NSG or Security List.')
    print('Service: systemctl status java-assessment-dashboard --no-pager')
    print('Next: sign in with email and temporary password, choose a new password, then create an environment')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--public-url', help='HTTPS origin reachable by all collectors; prompted when omitted')
    parser.add_argument('--java-home', help='Optional existing JDK 17+ home; otherwise discovered')
    parser.add_argument('--tls-cert', help='Optional full PEM server certificate chain')
    parser.add_argument('--tls-key', help='Optional unencrypted RSA PKCS#8 PEM key')
    parser.add_argument('--agent-ca', help='Optional PEM CA for the TLS certificate; defaults to bundled CA')
    parser.add_argument('--admin-name', help='First administrator full name; prompted when omitted')
    parser.add_argument('--admin-email', help='First administrator email; prompted when omitted')
    parser.add_argument('--admin-password-file', help='Optional mode-600/400 temporary password file (8–128 characters, valid 24h)')
    parser.add_argument('--no-package-install', action='store_true', help='Do not install a missing JDK with dnf')
    parser.add_argument('--legacy-data', help='Private directory with a consistent state.json and auth-registry.json backup; imported before first boot')
    parser.add_argument('--check', action='store_true', help='Validate inputs without installing')
    args = parser.parse_args()
    try:
        install(args)
    except (RuntimeError, OSError, ValueError, UnicodeError, zipfile.BadZipFile) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
