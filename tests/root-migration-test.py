"""Data-only migration tests: real helper, private synthetic data, no sudo/network."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / 'collector/root-migrate-input.sh'
TOKEN = 'fixture_private_token_01234567890123456789'
BASE = 'serverUrl=https\\://dashboard.example.invalid\nhostId=host-fixture\nagentToken=' + TOKEN + '\nspoolDirectory=/old/spool\nlogDirectory=/old/log\ntlsCaFile=/old/ca.pem\ntlsProvider=BCJSSE\ncachedIncludePaths=/opt,/home/opc/oracle\nsnapshotRoot=/home/opc/weblogic-observability/snapshots\n'
with tempfile.TemporaryDirectory(prefix='assessment-migration-', dir='/private/tmp' if Path('/private/tmp').exists() else '/tmp') as tmp:
    root = Path(tmp)
    for name in ('conf', 'collector-spool', 'logs'):
        (root / name).mkdir(mode=0o700)
    config = root / 'conf/collector.properties'
    def put(path, value):
        path.write_text(value)
        path.chmod(0o600)
    def run(mode, name=None, ok=True):
        cmd = ['/bin/sh', str(HELPER), mode, str(root)]
        if name is not None: cmd.append(name)
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        assert (p.returncode == 0) == ok, (mode, p.returncode, p.stderr)
        assert TOKEN not in p.stderr, 'Private token leaked to diagnostic output'
        return p.stdout
    put(config, BASE)
    put(root / 'conf/installation-id', '12345678-1234-1234-1234-123456789012\n')
    parsed = run('config')
    assert 'serverUrl=https://dashboard.example.invalid\n' in parsed
    assert 'installationId=12345678-1234-1234-1234-123456789012\n' in parsed
    assert 'snapshotRoot=/home/opc/weblogic-observability/snapshots\n' in parsed
    for field in ('spoolDirectory=', 'logDirectory=', 'tlsCaFile=', 'cachedIncludePaths='):
        assert field not in parsed
    put(root / 'collector-spool/pending-12345678-abcd.json', '{"fixture":true}\n')
    put(root / 'collector-spool/latest-snapshot.json', '{}\n')
    put(root / 'logs/install-events-20261009T203500-1234.json', '[]\n')
    put(root / 'logs/install-events-old.json.sent', '[]\n')
    state = run('list-state').splitlines()
    assert len(state) == 3 and 'logs/install-events-20261009T203500-1234.json' in state
    assert run('state', 'logs/install-events-20261009T203500-1234.json') == '[]\n'
    run('state', '../conf/collector.properties', ok=False)
    for bad in (BASE + 'arbitrary=x\n', BASE + 'hostId=duplicate\n', BASE + 'hostname=$(touch /tmp/assessment-injected)\n', BASE + '\x00', 'x' * 32769):
        put(config, bad); run('config', ok=False)
    put(config, BASE); config.chmod(0o644); run('config', ok=False); config.chmod(0o600)
    hard = root / 'conf/hardlink'; os.link(config, hard); run('config', ok=False); hard.unlink()
    regular = root / 'conf/real'; config.rename(regular); config.symlink_to(regular); run('config', ok=False); config.unlink(); regular.rename(config)
    pending = root / 'collector-spool/pending-12345678-abcd.json'
    pending.unlink(); pending.symlink_to(config); run('list-state', ok=False)
    print('Root migration input fixtures PASS (identity, bounds, permissions, links, pending snapshots/events).')
