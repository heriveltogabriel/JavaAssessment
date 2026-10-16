#!/usr/bin/env python3
"""Run an isolated probe on the two approved lab hosts; do not replace or enroll agents."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOSTS = {'157.151.0.69': 'ol07', '157.151.15.169': 'instance-rh5'}
PAYLOAD = r'''PS1= PS2=
export PS1 PS2
set -eu
umask 077
test "$(id -u)" = 0
case "$(hostname)" in "$3"|"$3".*) ;; *) echo WRONG_LAB_HOST; exit 1;; esac
test -f "$1" && test ! -L "$1"
dest=$(mktemp -d /root/java-assessment-jdbc-probe.XXXXXX)
cp "$1" "$dest/probe.jar"
chmod 600 "$dest/probe.jar"
test "$(sha256sum "$dest/probe.jar" | awk '{print $1}')" = "$2"
java_bin=
for candidate in /opt/java-assessment-runtime/jdk1.6.0_45/bin/java /usr/bin/java /opt/java-lab/jdk11/bin/java; do
  if test -x "$candidate"; then java_bin=$candidate; break; fi
done
test -n "$java_bin" || { echo JAVA_NOT_FOUND; exit 1; }
"$java_bin" -Xms16m -Xmx96m -Djava.security.egd=file:/dev/urandom -cp "$dest/probe.jar" assessment.JdbcEndpointTest
"$java_bin" -Xms16m -Xmx96m -Djava.security.egd=file:/dev/urandom -cp "$dest/probe.jar" assessment.JdbcDiscoveryProbe "$dest/result.json"
echo "REMOTE_RESULT=$dest/result.json"
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True, choices=sorted(HOSTS))
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--identity', type=Path, default=Path.home() / 'OneDrive/id_rsa')
    args = parser.parse_args()
    data = args.jar.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    parent = ROOT / 'output/jdbc-discovery'
    parent.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='lab-' + HOSTS[args.host] + '-', dir=str(parent)))
    out.chmod(0o700)
    ssh = ['ssh', '-i', str(args.identity), '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'StrictHostKeyChecking=yes', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=2']
    if args.host == '157.151.15.169':
        ssh += ['-o', 'KexAlgorithms=+diffie-hellman-group14-sha1', '-o', 'HostKeyAlgorithms=+ssh-rsa', '-o', 'PubkeyAcceptedKeyTypes=+ssh-rsa']
    ssh += ['opc@' + args.host]
    def call(command, payload=None, timeout=60):
        invocation = ssh
        if args.host == '157.151.15.169' and command.startswith('sudo '):
            invocation = ssh[:-1] + ['-tt', ssh[-1]]
            command = 'stty -echo; ' + command
            if payload is not None:
                payload += b'\nexit\n'
        result = subprocess.run(invocation + [command], input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        with (out / 'ssh.log').open('ab') as log:
            log.write(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError('SSH/probe failed (exit %s): %s' % (result.returncode, result.stderr.decode(errors='replace')[-1500:]))
        return result.stdout.decode('utf-8').replace('\r\n', '\n')
    print('Probe isolado em %s; agente instalado e aplicações serão preservados.' % args.host, flush=True)
    try:
        stage = call('umask 077; d=$(mktemp -d /home/opc/jdbc-probe.XXXXXX) || exit; cat > "$d/probe.jar" || exit; printf "%s\\n" "$d"', data).strip()
        if not re.fullmatch('/home/opc/jdbc-probe\.[A-Za-z0-9]+', stage):
            raise RuntimeError('Unexpected upload directory')
        output = call('sudo -n /bin/sh -s -- ' + ' '.join(shlex.quote(v) for v in [stage + '/probe.jar', digest, HOSTS[args.host]]), PAYLOAD.encode(), 270)
        print(output)
        match = re.search(r'REMOTE_RESULT=(/root/java-assessment-jdbc-probe\.[A-Za-z0-9]+/result\.json)$', output, re.M)
        if not match:
            raise RuntimeError('Missing completion/result marker')
        result = call('sudo -n cat ' + shlex.quote(match.group(1)))
        json.loads(result)
        (out / 'result.json').write_text(result)
        (out / 'probe-sha256.txt').write_text(digest + '\n')
        print('Evidência privada: ' + str(out))
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        (out / 'failure.txt').write_text(str(error) + '\n')
        print('PROBE_NOT_CONFIRMED: ' + str(error))
        print('Evidência privada: ' + str(out))
        return 1
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
