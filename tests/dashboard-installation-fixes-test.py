"""Runs on Python 3.6+, including actual OpenSSL/HTTPS checks."""
import io
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'dashboard-installer'))
import bootstrap
import install

class FixesTest(unittest.TestCase):
    def test_explicit_tls_ignores_ambient_openssl_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp); bad=directory/'ambient.cnf';bad.write_text('broken ambient configuration\n')
            with mock.patch.dict(os.environ, {'OPENSSL_CONF':str(bad)}):
                cert,key,ca,_=bootstrap.generate_tls(directory/'tls','dashboard.example.test')
                # Parsing and verification use the normal OpenSSL configuration.
            install.validate_certificate(cert,key,ca,'dashboard.example.test')
            with self.assertRaises(RuntimeError):install.validate_certificate(cert,key,ca,'wrong.example.test')

    def test_real_tls_health_requires_complete_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'VERSION').write_text('2.0.55\n')
            cert,key,ca,_=bootstrap.generate_tls(root/'tls','dashboard.example.test')
            ctx=ssl.SSLContext(ssl.PROTOCOL_TLS);ctx.load_cert_chain(str(cert),str(key))
            listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen(1)
            port=listener.getsockname()[1]
            errors=[]
            def serve():
                try:
                    plain,_=listener.accept()
                    with ctx.wrap_socket(plain,server_side=True) as stream:
                        stream.recv(4096)
                        body=b'{"status":"ok","storage":"sqlite","version":"2.0.55"}'
                        stream.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: '+str(len(body)).encode()+b'\r\nConnection: close\r\n\r\n')
                        for block in (body[:10],body[10:]):stream.sendall(block)
                except Exception as error:errors.append(type(error).__name__)
                finally:listener.close()
            thread=threading.Thread(target=serve);thread.daemon=True;thread.start()
            with mock.patch.object(install,'SOURCE',root):install.health_check('dashboard.example.test',port,ca)
            thread.join(5);self.assertFalse(errors);self.assertFalse(thread.is_alive())

    def test_wrong_health_never_confirms(self):
        class Stream:
            def __enter__(self):return self
            def __exit__(self,*a):pass
            def settimeout(self,*a):pass
            def sendall(self,*a):pass
            def makefile(self,*a):return io.BytesIO(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}')
        ctx=mock.Mock();ctx.wrap_socket.return_value=Stream()
        with mock.patch.object(install.ssl,'create_default_context',return_value=ctx),mock.patch.object(install.socket,'create_connection',return_value=Stream()),mock.patch.object(install.time,'sleep'),self.assertRaisesRegex(RuntimeError,'not ready'):
            install.health_check('dashboard.example.test',443,'unused')

if __name__=='__main__':unittest.main()
