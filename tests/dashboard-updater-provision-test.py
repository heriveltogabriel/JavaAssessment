"""Root-only file/permission checks in temporary paths; systemctl is mocked."""
import importlib.util
import json
import os
from pathlib import Path
import pwd
import tempfile
import unittest
from unittest import mock

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('provision',str(ROOT/'maintenance/install.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

@unittest.skipUnless(os.geteuid()==0,'Run isolated provisioning tests as root on Linux')
class ProvisionTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
  self.account=pwd.getpwnam('opc');self.base=self.root/'installation';(self.base/'maintenance').mkdir(parents=True)
  (self.base/'maintenance/dashboard_update.py').write_text('# fixture; never executed\n')
  self.state=self.root/'state';self.state.mkdir();(self.state/'status.json').write_text(json.dumps({'phase':'IDLE'}))
  self.unit=self.root/'worker.service';self.access=self.root/'dashboard.service.d/updater.conf'
  for name,value in [('BASE',self.base),('STATE',self.state),('UNIT',self.unit),('ACCESS_DROPIN',self.access)]:
   p=mock.patch.object(m,name,value);p.start();self.addCleanup(p.stop)
  for target,name,value in [(m.pwd,'getpwnam',self.account),(m.subprocess,'check_call',0),(m.subprocess,'call',0),(m.time,'sleep',None)]:
   p=mock.patch.object(target,name,return_value=value);p.start();self.addCleanup(p.stop)
 def test_scoped_permissions_and_idempotent_install(self):
  m.main();m.main()
  self.assertEqual(self.state.stat().st_mode&0o777,0o750)
  self.assertEqual((self.state/'inbox').stat().st_mode&0o777,0o770)
  self.assertEqual((self.state/'inbox').stat().st_uid,0)
  self.assertEqual((self.state/'inbox').stat().st_gid,self.account.pw_gid)
  self.assertEqual(self.access.read_text(),m.ACCESS_MARKER+'[Service]\nReadWritePaths='+str(self.state/'inbox')+'\n')
  self.assertEqual(self.access.stat().st_mode&0o777,0o644)
 def test_custom_dropin_is_preserved(self):
  self.access.parent.mkdir();self.access.write_text('administrator customization\n')
  with self.assertRaises(RuntimeError):m.main()
  self.assertEqual(self.access.read_text(),'administrator customization\n');m.subprocess.check_call.assert_not_called()
 def test_linked_inbox_is_refused(self):
  (self.state/'inbox').symlink_to(self.root,target_is_directory=True)
  with self.assertRaises(RuntimeError):m.main()
  m.subprocess.check_call.assert_not_called()
 def test_custom_executor_unit_is_preserved(self):
  self.unit.write_text('administrator customization\n')
  with self.assertRaises(RuntimeError):m.main()
  self.assertEqual(self.unit.read_text(),'administrator customization\n');m.subprocess.check_call.assert_not_called()
 def test_readiness_failure_is_reported(self):
  with mock.patch.object(m.subprocess,'call',return_value=1),self.assertRaises(RuntimeError):m.main()
 def test_non_root_is_refused_before_changes(self):
  with mock.patch.object(m.os,'geteuid',return_value=1234),self.assertRaises(RuntimeError):m.main()
  self.assertFalse(self.unit.exists());m.subprocess.check_call.assert_not_called()

if __name__=='__main__':unittest.main()
