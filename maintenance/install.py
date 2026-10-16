#!/usr/bin/env python3
"""Provision the fixed independent service from a verified installation, never from an upload."""
import os
from pathlib import Path
import pwd
import subprocess
import sys
import time
import json

BASE=Path('/opt/java-assessment-dashboard')
STATE=Path('/var/lib/java-assessment-dashboard-updater')
UNIT=Path('/etc/systemd/system/java-assessment-dashboard-updater.service')
MARKER='# Java Assessment independent dashboard updater v1\n'
ACCESS_DROPIN=Path('/etc/systemd/system/java-assessment-dashboard.service.d/dashboard-updater.conf')
ACCESS_MARKER='# Java Assessment dashboard update inbox v1\n'

def access_dropin():
    return ACCESS_MARKER+'[Service]\nReadWritePaths='+str(STATE/'inbox')+'\n'

def main():
    if os.geteuid()!=0:raise RuntimeError('Root required')
    account=pwd.getpwnam('java-assessment-dashboard')
    script=BASE/'maintenance/dashboard_update.py'
    if not script.is_file() or script.is_symlink() or script.stat().st_uid!=0:raise RuntimeError('Untrusted updater source')
    for path,mode in [(STATE,0o750),(STATE/'inbox',0o770)]:
        if path.is_symlink():raise RuntimeError('Linked updater directory')
        path.mkdir(exist_ok=True);os.chown(str(path),0,account.pw_gid);path.chmod(mode)
    # Existing dashboards have ProtectSystem=strict. Grant only the request
    # inbox write access; status, journal, backups and installed code stay protected.
    if ACCESS_DROPIN.parent.is_symlink():raise RuntimeError('Linked dashboard drop-in directory')
    ACCESS_DROPIN.parent.mkdir(mode=0o755,exist_ok=True)
    if ACCESS_DROPIN.exists() and (ACCESS_DROPIN.is_symlink() or ACCESS_DROPIN.read_text()!=access_dropin()):raise RuntimeError('Customized dashboard updater access; refusing overwrite')
    if not ACCESS_DROPIN.exists():
        with ACCESS_DROPIN.open('x') as stream:stream.write(access_dropin())
        ACCESS_DROPIN.chmod(0o644)
    text=MARKER+'''[Unit]
Description=Java Assessment independent dashboard updater
After=local-fs.target network.target

[Service]
Type=simple
User=root
Group=root
UMask=0077
ExecStart=/usr/bin/python3 -B /opt/java-assessment-dashboard/maintenance/dashboard_update.py
Restart=on-failure
RestartSec=5
TimeoutStopSec=180
PrivateTmp=true
ProtectSystem=full
ProtectHome=true
NoNewPrivileges=true
MemoryMax=1G

[Install]
WantedBy=multi-user.target
'''
    if UNIT.exists() and (UNIT.is_symlink() or UNIT.read_text()!=text):raise RuntimeError('Customized updater unit; refusing overwrite')
    if not UNIT.exists():
        with UNIT.open('x') as stream:stream.write(text)
        UNIT.chmod(0o644)
    subprocess.check_call(['systemctl','daemon-reload'])
    subprocess.check_call(['systemctl','enable','--now',UNIT.name])
    for attempt in range(20):
        status=STATE/'status.json'
        active=subprocess.call(['systemctl','is-active','--quiet',UNIT.name])==0
        if active and status.is_file() and not status.is_symlink():
            data=json.loads(status.read_text())
            if data.get('phase') in ('IDLE','SUCCEEDED','ROLLED_BACK','CANCELLED','FAILED','PREPARED'):
                return
        time.sleep(0.5)
    raise RuntimeError('Independent updater readiness not confirmed')

if __name__=='__main__':main()
