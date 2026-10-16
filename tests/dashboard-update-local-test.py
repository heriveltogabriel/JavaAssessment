import importlib.util
import io
from pathlib import Path
import unittest
from unittest import mock
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('local_update',ROOT/'ops/dashboard-release/update-dashboard.py')
update=importlib.util.module_from_spec(spec);spec.loader.exec_module(update)

class LocalUpdateTest(unittest.TestCase):
 def test_loopback_keeps_hostname_verification_and_ignores_proxy(self):
  class Stream:
   def __enter__(self):return self
   def __exit__(self,*a):pass
   def sendall(self,b):self.request=b
   def makefile(self,*a):return io.BytesIO(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}')
  stream=Stream();context=mock.Mock();context.wrap_socket.return_value=stream
  with mock.patch.object(update,'LOCAL_MODE',True),mock.patch.object(update,'URL','https://client.example.test:8443'),mock.patch.object(update.ssl,'create_default_context',return_value=context),mock.patch.object(update.socket,'create_connection',return_value=stream) as connect,mock.patch.object(update.urllib.request,'urlopen') as external:
   self.assertEqual(update.get('/health'),b'{}')
   connect.assert_called_once_with(('127.0.0.1',8443),timeout=4)
   context.wrap_socket.assert_called_once_with(stream,server_hostname='client.example.test')
   external.assert_not_called();self.assertIn(b'Host: client.example.test:8443',stream.request)
 def test_failing_tls_is_not_downgraded(self):
  stream=mock.MagicMock();context=mock.Mock();context.wrap_socket.side_effect=update.ssl.SSLError('bad CA')
  with mock.patch.object(update,'LOCAL_MODE',True),mock.patch.object(update,'URL','https://client.example.test'),mock.patch.object(update.ssl,'create_default_context',return_value=context),mock.patch.object(update.socket,'create_connection',return_value=stream),self.assertRaises(update.ssl.SSLError):update.get('/health')
if __name__=='__main__':unittest.main()
