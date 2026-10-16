#!/usr/bin/env python3
"""Disposable systemd/HTTPS trial. Never references production dashboard service or data."""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('fixture',str(ROOT/'tests/dashboard-self-update-test.py'));fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
m=fixture.m
SERVER='''import json,ssl,sys\nfrom pathlib import Path\nfrom http.server import HTTPServer,BaseHTTPRequestHandler\nbase=Path(sys.argv[1])\nclass Handler(BaseHTTPRequestHandler):\n def log_message(self,*args):pass\n def do_GET(self):\n  version=(base/'VERSION').read_text().strip()\n  fail=(base/'fail-new').exists() and version=='2.1.0'\n  body=json.dumps({'status':'ok','storage':'sqlite','version':version}).encode()\n  self.send_response(503 if fail else 200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)\nserver=HTTPServer(('127.0.0.1',int(sys.argv[2])),Handler)\ncontext=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(str(base/'cert.pem'),str(base/'key.pem'));server.socket=context.wrap_socket(server.socket,server_side=True);server.serve_forever()\n'''
class Native(m.System):
 def __init__(self,base,unit):super().__init__(base);self.unit=unit
 def command(self,*args):return super().command(*(self.unit if arg==m.UNIT else arg for arg in args))
 def backup(self,source,target):
  # Fixture DB has DELETE journaling, no writers; production fallback is database.sh.
  fixture.shutil.copy2(str(source),str(target))
 def label(self):pass

def main():
 if os.geteuid()!=0:raise RuntimeError('Run fixture as root')
 fixture.UpdateTests.setUpClass()
 unit='java-assessment-dashboard-self-test-'+str(os.getpid())+'.service'
 unit_path=Path('/run/systemd/system')/unit
 test=fixture.UpdateTests();test.setUp()
 try:
  base=test.base
  with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
  (base/'server.py').write_text(SERVER)
  (base/'openssl.cnf').write_text('[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,keyEncipherment,keyCertSign\n')
  subprocess.check_call(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(base/'key.pem'),'-out',str(base/'cert.pem'),'-days','1','-config',str(base/'openssl.cnf')],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  (base/'conf/agent-ca.pem').write_bytes((base/'cert.pem').read_bytes());(base/'conf/server.properties').write_text('publicUrl=https://localhost:'+str(port)+'\n')
  unit_path.write_text('[Unit]\nDescription=Disposable dashboard updater HTTPS fixture\n[Service]\nType=simple\nExecStart='+sys.executable+' '+str(base/'server.py')+' '+str(base)+' '+str(port)+'\nTimeoutStopSec=10\n')
  subprocess.check_call(['systemctl','daemon-reload']);system=Native(base,unit);test.engine=m.Engine(base,test.state,system)
  system.start();system.health('2.0.57');test.prepare();test.engine.confirm(test.ident)
  assert test.engine.record['phase']=='SUCCEEDED'
  test.engine.rollback();assert test.engine.record['phase']=='ROLLED_BACK';system.health('2.0.57')
  (base/'fail-new').write_text('fixture');test.ident='2'*32;test.prepare();failed=False
  try:test.engine.confirm(test.ident)
  except m.Rejected:failed=True
  assert failed and test.engine.record['phase']=='ROLLED_BACK';system.health('2.0.57')
  print('NATIVE_DASHBOARD_UPDATE=PASS signature + real systemd stop/start + verified TLS/SNI + explicit rollback + failed health automatic rollback; isolated fixture')
 finally:
  subprocess.call(['systemctl','stop',unit],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  if unit_path.exists():unit_path.unlink()
  subprocess.call(['systemctl','daemon-reload']);test.doCleanups();fixture.UpdateTests.tearDownClass()
if __name__=='__main__':main()
