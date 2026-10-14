#!/usr/bin/env python3
"""User-run read-only diagnosis of an interrupted systemd migration."""
import argparse
import datetime
import ipaddress
from pathlib import Path
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
EXTRA = r'''
echo MIGRATION_DIAG_BEGIN
for unit in java-assessment-collector.service java-assessment-update.service java-assessment-recovery.service; do
  printf 'SANDBOX_UNIT=%s\n' "$unit"
  systemctl show "$unit" -p ProtectSystem -p ReadOnlyPaths -p ReadWritePaths -p Result -p ExecMainCode -p ExecMainStatus -p MainPID 2>/dev/null || true
done
python3 - <<'DIAG_PY'
import json, pathlib, re, hashlib
root=pathlib.Path('/opt/java-assessment-collector')
home=pathlib.Path('/opt/java-assessment-update')
def read(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size>2*1024*1024: return None
    try:
        value=json.loads(path.read_text())
        return value if isinstance(value,dict) else None
    except (ValueError,OSError): return None
for name,relative in [('LEGACY','journal.json'),('V3','state/transaction.json')]:
    data=read(home/relative)
    if data is None:
        print(name+'_JOURNAL=ABSENT_OR_UNREADABLE'); continue
    for key in ('phase','operationId','requestId','targetVersion','previousVersion','preparedDirectory','diagnostic'):
        value=data.get(key)
        if isinstance(value,str) and re.fullmatch(r'[a-zA-Z0-9_.-]{1,128}',value): print(name+'_'+key+'='+value)
    if name=='V3':
        operation=data.get('operationId','');prepared=data.get('preparedDirectory','')
        if re.fullmatch(r'[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}',operation) and re.fullmatch(r'prepared-[0-9]{1,3}',prepared):
            script=home/'transactions'/operation/prepared/'bootstrap/update-bootstrap.sh'
            if script.is_file() and not script.is_symlink(): print('PREPARED_BOOTSTRAP_SHA256='+hashlib.sha256(script.read_bytes()).hexdigest())
for label,path in [('BOOTSTRAP',home/'bootstrap/update-bootstrap.sh'),('BOOTSTRAP_SEAL',home/'bootstrap/bootstrap-owned.json'),
                   ('ORIGIN_RECORD',home/'state/origin/origin.json'),('RECOVERY_TEMPLATE',home/'bootstrap/recovery.service'),
                   ('RECOVERY_UNIT_IN_ETC',pathlib.Path('/etc/systemd/system/java-assessment-recovery.service')),
                   ('CURRENT_POINTER',root/'current.json')]:
    print(label+'='+('PRESENT' if path.is_file() and not path.is_symlink() else 'ABSENT_OR_LINKED'))
    if label in ('BOOTSTRAP','RECOVERY_TEMPLATE') and path.is_file() and not path.is_symlink():
        print(label+'_SHA256='+hashlib.sha256(path.read_bytes()).hexdigest())
for relative in ('logs/collector.log',):
    path=root/relative
    if path.is_file() and not path.is_symlink():
        with path.open('rb') as stream:
            stream.seek(max(0,path.stat().st_size-65536));tail=stream.read(65536).decode('utf-8','replace')
        events=re.findall(r'AGENT_(?:BUNDLE_RETRY|UPDATE_RETRY)=([A-Z0-9_]{1,80})',tail)
        for code in events[-8:]: print('RECENT_DIAGNOSTIC='+code)
print('DIAG_COMPLETE=READ_ONLY')
DIAG_PY
echo MIGRATION_DIAG_END
'''


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host',required=True,type=ipaddress.ip_address)
    parser.add_argument('--identity',type=Path,default=Path.home()/'OneDrive/id_rsa')
    args=parser.parse_args()
    identity=args.identity.expanduser()
    if not identity.is_file(): parser.error('Chave SSH ausente; use --identity /caminho/da/chave')
    payload=(PROJECT/'ops/agent-canary-preflight.sh').read_text()+'\n'+EXTRA
    parent=PROJECT/'output/agent-canaries';parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    folder=Path(tempfile.mkdtemp(prefix='migration-diag-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'-',dir=parent))
    report=folder/'diagnostico.txt'
    command=['ssh','-i',str(identity),'-o','StrictHostKeyChecking=ask','-o','ConnectTimeout=12',
             '-o','ServerAliveInterval=15','-o','ServerAliveCountMax=2','opc@'+str(args.host),'sudo -n /bin/sh -s']
    print('Lendo estado da migracao em '+str(args.host)+'. Nenhum servico ou arquivo do agente sera alterado.',flush=True)
    with report.open('xb') as stream:
        report.chmod(0o600)
        try: result=subprocess.run(command,input=payload.encode(),stdout=stream,timeout=120)
        except (OSError,subprocess.TimeoutExpired) as failure:
            print('Diagnostico interrompido: '+str(failure));print('Relatorio privado: '+str(report));return 1
    output=report.read_text(errors='replace');print(output,end='' if output.endswith('\n') else '\n')
    print('Relatorio privado: '+str(report))
    return 0 if result.returncode==0 and 'DIAG_COMPLETE=READ_ONLY' in output else 1


if __name__=='__main__':
    raise SystemExit(main())
