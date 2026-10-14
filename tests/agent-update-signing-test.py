#!/usr/bin/env python3
"""Offline signing regression: temporary test keys only, no VM or network access."""
import hashlib
import io
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC=importlib.util.spec_from_file_location('signing',Path(__file__).resolve().parents[1]/'ops/agent_update_signing.py')
signing=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(signing)


class SigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary=tempfile.TemporaryDirectory(prefix='agent-signing-tests-')
        cls.root=Path(cls.temporary.name)
        cls.key,cls.public=signing.ensure_keypair(cls.root,create=True)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def package(self,version='1.5.8',binary_version=None,changed=None):
        jar=io.BytesIO()
        with zipfile.ZipFile(jar,'w') as z:
            z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nImplementation-Version: '+(binary_version or version)+'\n\n')
            for name in ['AssessmentCollector','AgentUpdateProbe']:
                z.writestr('assessment/'+name+'.class',b'\xca\xfe\xba\xbe\x00\x00\x00\x32')
        entries={'collector.jar':jar.getvalue(),'DEPENDENCIES.json':json.dumps({'version':version}).encode(),'update-public.der':self.public}
        for name in ['root-service.sh','root-trust.sh','update-install.sh','update-service.sh','update-local.sh']:
            entries[name]=b'#!/bin/sh\nexit 0\n'
        if changed: entries.update(changed)
        entries['SHA256SUMS']=''.join(signing.sha(data)+'  '+name+'\n' for name,data in sorted(entries.items())).encode()
        path=self.root/'test-agent.zip'
        with zipfile.ZipFile(path,'w') as z:
            for name,data in entries.items(): z.writestr(name,data)
        return path

    def test_signed_release_is_public_and_openssl_verifies_exact_manifest(self):
        result=signing.sign_release(self.root,self.package(),'1.5.8')
        prefix='dist/agent-updates/1.5.8/'
        self.assertEqual(set(result),{'dist/agent-update-public.der',prefix+'manifest.json',prefix+'manifest.sig',prefix+'collector.jar'})
        manifest=json.loads(result[prefix+'manifest.json']);self.assertEqual(manifest['sha256'],signing.sha(result[prefix+'collector.jar']))
        self.assertEqual(manifest['minUpdater'],2);self.assertEqual(manifest['version'],'1.5.8');self.assertEqual(manifest['requiredFiles']['update-public.der'],signing.sha(self.public))
        self.assertNotIn(self.key.read_bytes(),list(result.values()))
        raw=self.root/'verify.json';raw.write_bytes(result[prefix+'manifest.json'])
        sig=self.root/'verify.sig';sig.write_bytes(result[prefix+'manifest.sig'])
        pub=self.root/'public.pem';pub.write_bytes(signing.openssl('pkey','-in',self.key,'-pubout'))
        signing.openssl('dgst','-sha256','-verify',pub,'-signature',sig,raw)
        raw.write_bytes(raw.read_bytes()+b' ')
        with self.assertRaises(RuntimeError): signing.openssl('dgst','-sha256','-verify',pub,'-signature',sig,raw)

    def test_reuses_key_and_refuses_insecure_permissions(self):
        before=self.key.read_bytes();self.assertEqual(signing.ensure_keypair(self.root)[1],self.public)
        self.key.chmod(0o644)
        try:
            with self.assertRaisesRegex(RuntimeError,'private'):signing.ensure_keypair(self.root)
        finally:self.key.chmod(0o600)
        self.assertEqual(self.key.read_bytes(),before)

    def test_missing_private_key_cannot_silently_rotate_existing_public_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'collector/public').mkdir(parents=True)
            (root/'collector/public/agent-update-public.der').write_bytes(self.public)
            with self.assertRaises(RuntimeError):signing.ensure_keypair(root,create=True)

    def test_wrong_binary_version_is_not_signed(self):
        with self.assertRaisesRegex(RuntimeError,'binary'):signing.sign_release(self.root,self.package(binary_version='1.5.7'),'1.5.8')

    def test_wrong_public_pin_is_not_signed(self):
        with self.assertRaisesRegex(RuntimeError,'pinned'):signing.sign_release(self.root,self.package(changed={'update-public.der':b'otherkey'}),'1.5.8')

    def test_version_and_package_integrity_are_enforced(self):
        with self.assertRaisesRegex(RuntimeError,'Stable full'):signing.sign_release(self.root,self.package(),'1.5.8-beta')
        path=self.package()
        with zipfile.ZipFile(path,'a') as z:z.writestr('unexpected.sh',b'changed')
        with self.assertRaisesRegex(RuntimeError,'checksum inventory'):signing.sign_release(self.root,path,'1.5.8')


if __name__=='__main__':unittest.main(verbosity=2)
