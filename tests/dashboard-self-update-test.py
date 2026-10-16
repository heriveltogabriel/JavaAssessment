import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ops'))
def load(name,path):
 spec=importlib.util.spec_from_file_location(name,str(path));mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod
m=load('dashboard_update',ROOT/'maintenance/dashboard_update.py')
builder=load('web_builder',ROOT/'ops/build-dashboard-web-update.py')
class FakeSystem:
 def __init__(self):self.calls=[];self.fail_new=False
 def stop(self):self.calls.append('stop')
 def start(self):self.calls.append('start')
 def label(self):pass
 def backup(self,source,target):shutil.copy2(str(source),str(target))
 def health(self,v):
  self.calls.append('health:'+v)
  if self.fail_new and v=='2.1.0':raise m.Rejected('failed')
class PowerLoss(BaseException):pass
class UpdateTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.keys=tempfile.TemporaryDirectory();cls.key=Path(cls.keys.name)/'private.pem';cls.pub=Path(cls.keys.name)/'public.der'
  subprocess.check_call(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(cls.key)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  subprocess.check_call(['openssl','pkey','-in',str(cls.key),'-pubout','-outform','DER','-out',str(cls.pub)],stderr=subprocess.DEVNULL)
 @classmethod
 def tearDownClass(cls):cls.keys.cleanup()
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.home=Path(self.temp.name);self.base=self.home/'base';self.base.mkdir();self.state=self.home/'state';self.state.mkdir();(self.state/'inbox').mkdir()
  for d in ['classes','server','shared','web','dist','conf','data','lib','maintenance']:(self.base/d).mkdir()
  for d in ['classes','server','shared','web','lib','maintenance']:(self.base/d/'original').write_text('old')
  (self.base/'VERSION').write_text('2.0.57\n');(self.base/'dist/agent-update-public.der').write_bytes(self.pub.read_bytes());(self.base/'conf/agent-ca.pem').write_bytes(b'PRIVATE-INSTALLATION-PUBLIC-CA')
  (self.base/'conf/server.properties').write_text('secret=do-not-expose\n');(self.base/'data/.environment-install.key').write_bytes(b'X'*32)
  con=sqlite3.connect(str(self.base/'data/inventory.sqlite'));con.execute('CREATE TABLE data(value TEXT)');con.execute("INSERT INTO data VALUES('preserved')");con.commit();con.close()
  agent=io.BytesIO()
  with zipfile.ZipFile(agent,'w') as z:z.writestr('conf/ca.pem','generic');z.writestr('DEPENDENCIES.json','{"version":"2.1.0"}');z.writestr('collector.jar','fixture')
  runtime={'VERSION':b'2.1.0\n','classes/new':b'new','classes/Example$Nested.class':b'inner class fixture','lib/dependency.jar':b'new library fixture','web/new':b'new','dist/agent-update-public.der':self.pub.read_bytes(),'dist/assessment-collector.zip':agent.getvalue()}
  sources={'server/UserAuth.java':b'fixture','server/DashboardMaintenance.java':b'fixture','shared/new':b'new','maintenance/dashboard_update.py':b'fixture updater','expected-baselines.json':json.dumps([{'version':'2.0.57','hashes':m.source_hashes(self.base)}]).encode()}
  for name,entries in [('runtime',runtime),('sources',sources)]:
   with zipfile.ZipFile(str(self.home/(name+'.zip')),'w') as z:
    for n,v in entries.items():z.writestr(n,v)
  self.upload=self.home/'upload.zip';builder.build(self.home/'runtime.zip',self.home/'sources.zip',self.upload,self.key,'Fixture notes')
  self.system=FakeSystem();self.engine=m.Engine(self.base,self.state,self.system);self.ident='1'*32
 def prepare(self):self.engine.prepare(self.ident,self.upload,'admin@example.test')
 def manifest(self):return m.checked_package(self.upload,self.pub,'2.0.57',m.source_hashes(self.base))
 def rewrite(self,transform,sign=False):
  with zipfile.ZipFile(str(self.upload)) as z:entries={n:z.read(n) for n in z.namelist()}
  transform(entries)
  if sign:
   raw=self.home/'raw';sig=self.home/'sig';raw.write_bytes(entries['manifest.json']);subprocess.check_call(['openssl','dgst','-sha256','-sign',str(self.key),'-out',str(sig),str(raw)]);entries['manifest.sig']=sig.read_bytes()
  with zipfile.ZipFile(str(self.upload),'w') as z:
   for n,v in entries.items():z.writestr(n,v)
 def test_prepare_leaves_service_untouched(self):
  self.prepare();self.assertEqual(self.system.calls,[]);self.assertEqual(self.engine.record['phase'],'PREPARED');self.assertFalse(self.engine.gate.exists())
 def test_success_and_manual_rollback_keep_data_and_ca(self):
  self.prepare();self.engine.confirm(self.ident);self.assertEqual(self.engine.record['phase'],'SUCCEEDED');self.assertFalse(self.engine.gate.exists())
  self.assertEqual((self.base/'classes/Example$Nested.class').read_bytes(),b'inner class fixture')
  self.assertEqual((self.base/'lib/dependency.jar').read_bytes(),b'new library fixture')
  with zipfile.ZipFile(str(self.base/'dist/assessment-collector.zip')) as z:self.assertEqual(z.read('conf/ca.pem'),b'PRIVATE-INSTALLATION-PUBLIC-CA')
  con=sqlite3.connect(str(self.base/'data/inventory.sqlite'));con.execute("INSERT INTO data VALUES('after-update')");con.commit();con.close()
  self.engine.rollback();self.assertEqual((self.base/'VERSION').read_text().strip(),'2.0.57');self.assertEqual(self.engine.record['phase'],'ROLLED_BACK')
  self.assertEqual((self.base/'lib/original').read_text(),'old');self.assertFalse((self.base/'lib/dependency.jar').exists())
  con=sqlite3.connect(str(self.base/'data/inventory.sqlite'));self.assertEqual(con.execute('SELECT count(*) FROM data').fetchone()[0],2);con.close()
  self.assertNotIn('do-not-expose',(self.state/'status.json').read_text())
 def test_start_failure_automatically_rolls_back(self):
  self.prepare();self.system.fail_new=True
  with self.assertRaises(m.Rejected):self.engine.confirm(self.ident)
  self.assertEqual(self.engine.record['phase'],'ROLLED_BACK');self.assertFalse(self.engine.gate.exists())
 def test_cancel_only_before_application(self):
  self.prepare();self.engine.cancel(self.ident);self.assertEqual(self.engine.record['phase'],'CANCELLED');self.assertEqual(self.system.calls,[])
  with self.assertRaises(m.Rejected):self.engine.confirm(self.ident)
 def test_stale_id_refused(self):
  self.prepare()
  with self.assertRaises(m.Rejected):self.engine.confirm('2'*32)
 def test_second_preparation_refused(self):
  self.prepare()
  with self.assertRaises(m.Rejected):self.engine.prepare('2'*32,self.upload,'other')
 def test_unsigned_zip_refused(self):
  self.rewrite(lambda e:e.pop('manifest.sig'))
  with self.assertRaises(m.Rejected):self.manifest()
 def test_modified_signature_refused(self):
  self.rewrite(lambda e:e.update({'manifest.sig':b'wrong'}))
  with self.assertRaises(m.Rejected):self.manifest()
 def test_modified_file_refused(self):
  self.rewrite(lambda e:e.update({'payload/web/new':b'bad'}))
  with self.assertRaises(m.Rejected):self.manifest()
 def test_unlisted_entry_refused(self):
  self.rewrite(lambda e:e.update({'payload/../../escape':b'bad'}))
  with self.assertRaises(m.Rejected):self.manifest()
 def test_equal_or_older_version_refused(self):
  for v in ('2.1.0','2.2.0'):
   with self.assertRaises(m.Rejected):m.checked_package(self.upload,self.pub,v,m.source_hashes(self.base))
 def test_baseline_changed_refused(self):
  (self.base/'web/original').write_text('customized')
  with self.assertRaises(m.Rejected):self.prepare()
 def test_change_after_prepare_refused_before_stop(self):
  self.prepare();(self.base/'server/original').write_text('customized')
  with self.assertRaises(m.Rejected):self.engine.confirm(self.ident)
  self.assertEqual(self.system.calls,[])
 def test_pending_agent_update_refused(self):
  con=sqlite3.connect(str(self.base/'data/inventory.sqlite'));con.execute('CREATE TABLE agent_update_operations(status TEXT)');con.execute("INSERT INTO agent_update_operations VALUES('WAITING')");con.commit();con.close()
  with self.assertRaises(m.Rejected):self.prepare()
 def test_bad_link_refused(self):
  self.upload.unlink();self.upload.symlink_to(self.home/'runtime.zip')
  with self.assertRaises(OSError):self.prepare()
 def test_insufficient_space_refused(self):
  with mock.patch.object(m.shutil,'disk_usage',return_value=type('D',(),{'free':0})()):
   with self.assertRaises(m.Rejected):self.prepare()
 def test_power_loss_before_or_after_each_component_rename(self):
  # Each subtest gets an independent original installation and signed package.
  for index in range(len(m.COMPONENTS)*2):
   if index:self.setUp()
   self.prepare();real=m.os.replace;counter=[0]
   def replace(source,target):
    src=Path(source);dst=Path(target)
    mutation=src.parent==self.base or src.parent==self.engine.op()/'new'
    if mutation:
     current=counter[0];counter[0]+=1
     if current==index:raise PowerLoss()
    return real(source,target)
   with mock.patch.object(m.os,'replace',side_effect=replace),self.assertRaises(PowerLoss):self.engine.confirm(self.ident)
   restarted=m.Engine(self.base,self.state,self.system);restarted.recover()
   self.assertEqual(restarted.record['phase'],'ROLLED_BACK',str(index));self.assertEqual((self.base/'VERSION').read_text().strip(),'2.0.57');self.assertFalse(restarted.gate.exists())
 def test_recovery_failure_keeps_maintenance_and_backup(self):
  self.prepare();self.system.health=lambda v:(_ for _ in ()).throw(m.Rejected('no health'))
  with self.assertRaises(m.Rejected):self.engine.confirm(self.ident)
  self.assertEqual(self.engine.record['phase'],'RECOVERY_REQUIRED');self.assertTrue(self.engine.gate.exists());self.assertTrue((self.engine.op()/'inventory.sqlite').exists())
 def test_corrupted_backup_refuses_rollback(self):
  self.prepare();self.engine.confirm(self.ident);(self.engine.op()/'old/web/original').write_text('tampered')
  with self.assertRaises(m.Rejected):self.engine.rollback()
  self.assertEqual(self.engine.record['phase'],'RECOVERY_REQUIRED');self.assertTrue(self.engine.gate.exists())
 def test_new_agent_operation_blocks_manual_rollback(self):
  self.prepare();self.engine.confirm(self.ident)
  con=sqlite3.connect(str(self.base/'data/inventory.sqlite'));con.execute('CREATE TABLE agent_update_operations(status TEXT)');con.execute("INSERT INTO agent_update_operations VALUES('SUCCEEDED')");con.commit();con.close()
  with self.assertRaises(m.Rejected):self.engine.rollback()
  self.assertEqual(self.engine.record['phase'],'SUCCEEDED');self.assertFalse(self.engine.gate.exists())
 def test_manual_rollback_rejects_changed_installed_file(self):
  self.prepare();self.engine.confirm(self.ident);(self.base/'web/new').write_text('customized')
  with self.assertRaises(m.Rejected):self.engine.rollback()
  self.assertEqual(self.engine.record['phase'],'SUCCEEDED')
 def test_signed_traversal_refused(self):
  def modify(entries):
   manifest=json.loads(entries['manifest.json']);manifest['files']['../outside']={'size':1,'sha256':m.sha(b'x')};entries['manifest.json']=json.dumps(manifest).encode();entries['payload/../outside']=b'x'
  self.rewrite(modify,sign=True)
  with self.assertRaises(m.Rejected):self.manifest()
 def test_interrupted_preparation_recovers_without_stop(self):
  self.engine.record.update(id=self.ident,phase='VERIFYING');self.engine.save()
  restarted=m.Engine(self.base,self.state,self.system);restarted.recover();self.assertEqual(restarted.record['phase'],'FAILED');self.assertEqual(self.system.calls,[])
 def test_duplicate_entries_refused(self):
  with zipfile.ZipFile(str(self.upload),'a') as z:z.writestr('manifest.sig',b'duplicate')
  with self.assertRaises(m.Rejected):self.manifest()
 def test_signed_symlink_refused(self):
  with zipfile.ZipFile(str(self.upload)) as z:entries={n:z.read(n) for n in z.namelist()}
  with zipfile.ZipFile(str(self.upload),'w') as z:
   for n,v in entries.items():
    info=zipfile.ZipInfo(n)
    if n=='payload/web/new':info.external_attr=(0o120777<<16)
    z.writestr(info,v)
  with self.assertRaises(m.Rejected):self.manifest()
if __name__=='__main__':unittest.main()
