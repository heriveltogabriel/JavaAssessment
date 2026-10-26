#!/usr/bin/env python3
"""Offline orchestration tests plus real HTTPS/Ansible contract tests."""
import copy
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('agent_updates', ROOT / 'install/ansible/files/update-agents.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class Clock:
    def __init__(self): self.now = 1793020000
    def __call__(self): return self.now
    def sleep(self, seconds): self.now += seconds
    def iso(self): return dt.datetime.fromtimestamp(self.now, dt.timezone.utc).isoformat()


class Fake:
    def __init__(self, count=8):
        self.clock = Clock()
        self.hosts = {str(i): {'id': str(i), 'hostname': 'host-%s' % i, 'fqdn': 'host-%s.lab' % i,
                              'credentialStatus': 'active', 'collectorVersion': '2.1.20',
                              'lastSeen': self.clock.iso()} for i in range(count)}
        self.requests, self.batches, self.calls, self.cancelled = {}, {}, [], []
        self.timeline = ['SUCCEEDED']
        self.external, self.lost, self.no_version, self.blocked_host = False, False, False, None
        self.secret = 'PASSWORD_NOT_IN_REPORT_918273'

    def call(self, path, body=None, **kw):
        self.calls.append((path, copy.deepcopy(body)))
        endpoint, _, query = path.partition('?')
        args = {k: v[0] for k, v in urllib.parse.parse_qs(query).items()}
        if endpoint == 'login':
            if body != {'email': 'admin@test.invalid', 'password': self.secret}: raise m.ApiError(401)
            return {'authenticated': True, 'csrfToken': 'SECRET_CSRF', 'user': {'role': 'ADMIN'}}
        if endpoint == 'state':
            hosts = list(self.hosts.values())
            if 'hostId' in args: hosts = [h for h in hosts if h['id'] == args['hostId']]
            page = int(args.get('page', 1))
            return {'version': '2.1.24', 'hosts': copy.deepcopy(hosts[(page-1)*100:page*100]),
                    'serverTime': self.clock.iso(), 'pagination': {'page': page, 'pages': max(1, (len(hosts)+99)//100)}}
        if endpoint == 'work-requests':
            items = [{'id': b['id']} for b in self.batches.values() if any(r['status'] in m.BUSY for r in b['requests'])]
            if self.external: items.append({'id': 'other-campaign'})
            return {'items': items, 'pagination': {'page': 1, 'pages': 1}}
        if endpoint == 'agent-updates' and body is None:
            h = self.hosts[args['hostId']]
            return {'release': {'version': '2.1.24', 'available': True},
                    'hosts': [{'id': h['id'], 'eligible': h['id'] != self.blocked_host,
                               'reason': 'UPDATE_BUSY' if h['id'] == self.blocked_host else ''}]}
        if endpoint == 'agent-updates':
            key = body['requestKey']
            if key not in self.batches:
                assert not any(r['status'] in m.BUSY for b in self.batches.values() for r in b['requests']), 'Overlapping batches'
                rows = [{'id': 'r-'+i, 'hostId': i, 'status': 'WAITING'} for i in body['hostIds']]
                self.batches[key] = {'id': key, 'requests': rows, 'step': 0}
            b = self.batches[key]
            if self.lost:
                self.lost = False
                raise m.Stop('POST response lost')
            return {'batchId': b['id'], 'requests': copy.deepcopy(b['requests']), 'skipped': []}
        if endpoint == 'work-requests/detail':
            b = self.batches[args['id']]
            value = self.timeline[min(b['step'], len(self.timeline)-1)]
            b['step'] += 1
            for idx, row in enumerate(b['requests']):
                if row['status'] in {'CANCELLED', 'EXPIRED'}: continue
                row['status'] = value[idx] if isinstance(value, list) else value
                if row['status'] == 'SUCCEEDED' and not self.no_version:
                    self.hosts[row['hostId']]['collectorVersion'] = '2.1.24'
            return {'machines': copy.deepcopy(b['requests']), 'request': {'id': b['id']}}
        if endpoint == 'agent-updates/cancel':
            self.cancelled.append(body['hostId'])
            for b in self.batches.values():
                for row in b['requests']:
                    if row['id'] == body['requestId']:
                        if row['status'] != 'WAITING': raise m.ApiError(409)
                        row['status'] = 'CANCELLED'
                        return copy.deepcopy(row)
            raise m.ApiError(404)
        raise AssertionError(path)


class CampaignTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.files = m.Files(self.tmp.name + '/run')
        self.api = Fake()
        self.c = dict(url='https://dashboard.invalid', email='admin@test.invalid', password=self.api.secret,
                      ca_file='/CA.pem', report_dir=str(self.files.path), version='2.1.24', apply=True,
                      batch_size=5, poll_seconds=15, batch_timeout=300, contact_seconds=300, pause_seconds=0,
                      targets=[{'name': 'host-'+str(i)} for i in range(8)])
    def tearDown(self):
        self.files.close()
        self.tmp.cleanup()
    def run_campaign(self):
        return m.Campaign(m.validate(self.c), self.api, self.files, self.api.clock.sleep, self.api.clock).run()
    def created(self): return [b for p,b in self.api.calls if p == 'agent-updates' and b is not None]
    def test_batches_wait_confirm_and_disable_assessment(self):
        self.api.timeline = ['DOWNLOADING', 'SUCCEEDED']
        result = self.run_campaign()
        self.assertEqual([len(b['hostIds']) for b in self.created()], [5,3])
        self.assertTrue(all(b['runAssessmentAfterUpdate'] is False for b in self.created()))
        self.assertEqual(result['counts'], {'SUCCEEDED': 8})
        self.assertTrue(all(r['confirmedVersion']=='2.1.24' for r in self.files.read('report.json')['machines']))
    def test_preview_never_mutates(self):
        self.c['apply'] = False
        self.run_campaign()
        self.assertFalse(any(body is not None for path,body in self.api.calls))
        self.assertIsNone(self.files.read('journal.json'))
    def test_offline_current_revoked_and_unknown_skipped(self):
        self.api.hosts['0']['lastSeen'] = '2000-01-01T00:00:00Z'
        self.api.hosts['1']['collectorVersion'] = '2.1.24'
        self.api.hosts['2']['credentialStatus'] = 'revoked'
        self.api.hosts['3']['collectorVersion'] = 'unknown'
        self.run_campaign()
        self.assertEqual(self.created()[0]['hostIds'], ['4','5','6','7'])
        self.assertEqual(self.files.read('report.json')['counts']['NO_RECENT_CONTACT'], 1)
    def test_ambiguous_hostname_blocks_every_post(self):
        self.api.hosts['1']['hostname'] = 'host-0'
        with self.assertRaisesRegex(m.Stop, 'ambíguo'): self.run_campaign()
        self.assertFalse(self.created())
    def test_explicit_id_disambiguates(self):
        self.api.hosts['1']['hostname'] = 'host-0'
        self.c['targets'] = [{'name':'alias', 'id':'1'}]
        self.run_campaign()
        self.assertEqual(self.created()[0]['hostIds'], ['1'])
    def test_exact_fqdn(self):
        self.c['targets'] = [{'name':'HOST-0.LAB.'}]
        self.run_campaign()
        self.assertEqual(self.created()[0]['hostIds'], ['0'])
    def test_duplicate_inventory_identity_fails(self):
        self.c['targets'] = [{'name':'host-0'}, {'name':'host-0.lab'}]
        with self.assertRaisesRegex(m.Stop, 'mesmo cadastro'): self.run_campaign()
    def test_unknown_hostname_does_not_match_by_prefix(self):
        self.c['targets'] = [{'name':'host'}]
        with self.assertRaisesRegex(m.Stop, 'ausente'): self.run_campaign()
    def test_global_active_campaign_blocks(self):
        self.api.external = True
        with self.assertRaisesRegex(m.Stop, 'outros lotes'): self.run_campaign()
        self.assertFalse(self.created())
    def test_concurrent_host_update_blocks(self):
        self.api.blocked_host = '0'
        with self.assertRaisesRegex(m.Stop, 'concorrente'): self.run_campaign()
        self.assertFalse(self.created())
    def test_expired_hosts_allow_next_batch(self):
        self.api.timeline = ['EXPIRED']
        result = self.run_campaign()
        self.assertEqual(len(self.created()), 2)
        self.assertEqual(result['counts'], {'EXPIRED': 8})
    def test_unconfirmed_stops_and_cancels_only_own_waiting_reversed(self):
        self.api.timeline = [['UNCONFIRMED','WAITING','WAITING','WAITING','WAITING']]
        with self.assertRaisesRegex(m.Stop, 'ATTENTION'): self.run_campaign()
        self.assertEqual(len(self.created()), 1)
        self.assertEqual(self.api.cancelled, ['4','3','2','1'])
    def test_failed_stops_before_next_batch(self):
        self.api.timeline = ['FAILED']
        with self.assertRaisesRegex(m.Stop, 'ATTENTION'): self.run_campaign()
        self.assertEqual(len(self.created()), 1)
    def test_rolled_back_blocks(self):
        self.api.timeline = ['ROLLED_BACK']
        with self.assertRaisesRegex(m.Stop, 'ATTENTION'): self.run_campaign()
    def test_timeout_stops_and_does_not_cancel_downloading(self):
        self.api.timeline = [['DOWNLOADING','WAITING','WAITING','WAITING','WAITING']]
        with self.assertRaisesRegex(m.Stop, 'MONITOR_TIMEOUT'): self.run_campaign()
        self.assertEqual(self.api.cancelled, ['4','3','2','1'])
        self.assertEqual(len(self.created()), 1)
    def test_missing_version_confirmation_stops(self):
        self.api.no_version = True
        with self.assertRaisesRegex(m.Stop, 'VERSION_NOT_CONFIRMED'): self.run_campaign()
        self.assertEqual(len(self.created()), 1)
    def test_resume_after_lost_post_reuses_same_key(self):
        self.api.lost = True
        with self.assertRaisesRegex(m.Stop, 'POST response lost'): self.run_campaign()
        key = self.files.read('journal.json')['active']['key']
        result = self.run_campaign()
        self.assertEqual(self.created()[0]['requestKey'], key)
        self.assertEqual(self.created()[1]['requestKey'], key)
        self.assertEqual(len(self.api.batches), 2)
        self.assertEqual(result['counts'], {'SUCCEEDED':8})
    def test_completed_run_is_idempotent(self):
        self.run_campaign()
        before = len(self.created())
        self.run_campaign()
        self.assertEqual(len(self.created()), before)
    def test_blocked_campaign_cannot_resume_silently(self):
        self.api.timeline = ['UNCONFIRMED']
        with self.assertRaises(m.Stop): self.run_campaign()
        before = len(self.created())
        with self.assertRaisesRegex(m.Stop, 'bloqueada'): self.run_campaign()
        self.assertEqual(len(self.created()), before)
    def test_changed_selection_cannot_reuse_journal(self):
        self.run_campaign()
        self.c['targets'] = [{'name':'host-0'}]
        with self.assertRaisesRegex(m.Stop, 'outra seleção'): self.run_campaign()
    def test_pagination_above_one_thousand(self):
        self.api = Fake(1501)
        self.c['targets'] = [{'name':'host-1500'}]
        self.run_campaign()
        self.assertTrue(any(p.endswith('page=16') for p,_ in self.api.calls))
        self.assertEqual(self.created()[0]['hostIds'], ['1500'])
        self.assertFalse(any(p == 'agent-updates' and b is None for p,b in self.api.calls))
    def test_no_credentials_in_reports(self):
        self.run_campaign()
        text = ''.join(p.read_text() for p in self.files.path.glob('*.json'))
        for secret in (self.api.secret, 'SECRET_CSRF', self.c['email']): self.assertNotIn(secret, text)
    def test_local_lock_and_permissions(self):
        with self.assertRaisesRegex(m.Stop, 'Outra execução'): m.Files(self.files.path)
        self.run_campaign()
        self.assertEqual(self.files.path.stat().st_mode & 0o777, 0o700)
        self.assertTrue(all(p.stat().st_mode & 0o077 == 0 for p in self.files.path.iterdir()))
    def test_mixed_dashboards_refused_before_authentication(self):
        self.c['targets'][0]['url'] = 'https://another.invalid'
        with self.assertRaisesRegex(m.Stop, 'mesmo dashboard'): m.validate(self.c)
    def test_https_and_batch_limits(self):
        for key,value in [('url','http://dashboard.invalid'),('url','https://user:pass@example.com'),
                          ('batch_size',6),('batch_size',0),('apply','true')]:
            c = dict(self.c, **{key:value})
            with self.assertRaises(m.Stop): m.validate(c)


class HttpsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name)
        cls.cert, cls.key = cls.path/'ca.pem', cls.path/'key.pem'
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1',
                        '-keyout',str(cls.key),'-out',str(cls.cert),'-subj','/CN=localhost',
                        '-addext','subjectAltName=DNS:localhost'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cls.fake = Fake(2)
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_GET(self): self.handle_api()
            def do_POST(self): self.handle_api()
            def handle_api(self):
                path = self.path.removeprefix('/api/')
                body = json.loads(self.rfile.read(int(self.headers['Content-Length']))) if self.command=='POST' else None
                try:
                    if path == 'redirect':
                        self.send_response(302); self.send_header('Location', 'https://example.invalid/private'); self.end_headers(); return
                    if path == 'forbidden': raise m.ApiError(403)
                    if path != 'login':
                        if self.headers.get('Cookie') != 'assessment_session=SECRET_COOKIE': raise m.ApiError(401)
                        if body is not None and self.headers.get('X-CSRF-Token') != 'SECRET_CSRF': raise m.ApiError(403)
                    if self.command == 'POST' and self.headers.get('Origin') != cls.url: raise m.ApiError(403)
                    result = cls.fake.call(path, body)
                    self.send_response(200)
                    self.send_header('Content-Type','application/json')
                    if path=='login': self.send_header('Set-Cookie','assessment_session=SECRET_COOKIE; Secure; HttpOnly; Path=/')
                    self.end_headers()
                    self.wfile.write(json.dumps(result).encode())
                except m.ApiError as e:
                    self.send_response(e.status); self.end_headers(); self.wfile.write(b'{"error":"private details"}')
        cls.server = ThreadingHTTPServer(('127.0.0.1',0), Handler)
        cls.url = 'https://localhost:%d' % cls.server.server_port
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(cls.cert), str(cls.key))
        cls.server.socket = context.wrap_socket(cls.server.socket, server_side=True)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join(); cls.tmp.cleanup()
    def config(self, directory):
        return m.validate(dict(url=self.url, email='admin@test.invalid', password=self.fake.secret,
                      ca_file=str(self.cert), report_dir=str(directory), version='2.1.24', apply=True,
                      targets=[{'name':'host-0'}, {'name':'host-1'}]))
    def test_real_https_cookies_csrf_and_renewal(self):
        api = m.Api(self.config(self.path/'unused'))
        self.assertEqual(api.call('state?view=hosts')['version'], '2.1.24')
        # Clear session cookies to exercise a 401 and one safe reauthentication.
        for handler in api.opener.handlers:
            if hasattr(handler, 'cookiejar'): handler.cookiejar.clear()
        self.assertEqual(api.call('state?view=hosts')['version'], '2.1.24')
    def test_ansible_preview_then_apply(self):
        ansible = os.environ.get('ANSIBLE_PLAYBOOK','/private/tmp/ja-ansible-env/bin/ansible-playbook')
        if not Path(ansible).exists(): self.skipTest('Set ANSIBLE_PLAYBOOK to run integration')
        directory = self.path/'ansible-run'
        inventory = self.path/'inventory.yml'
        inventory.write_text('all:\n  children:\n    assessment_agents:\n      hosts:\n        host-0:\n        host-1:\n')
        variables = self.path/'vars.json'
        variables.write_text(json.dumps({'assessment_dashboard_url':self.url, 'assessment_ca_file':str(self.cert),
                     'assessment_update_version':'2.1.24','assessment_update_email':'admin@test.invalid',
                     'assessment_update_password':self.fake.secret,'assessment_update_report_dir':str(directory)}))
        os.chmod(variables,0o600)
        command = [ansible,'-i',str(inventory),str(ROOT/'install/ansible/update-agents.yml'),'-e','@'+str(variables)]
        for apply in (False, True):
            run = subprocess.run(command + ['-e',json.dumps({'assessment_update_apply':apply})], text=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
            self.assertEqual(run.returncode,0,run.stdout)
            for secret in (self.fake.secret,'SECRET_CSRF','SECRET_COOKIE'): self.assertNotIn(secret,run.stdout)
            if not apply:
                self.assertFalse(self.fake.batches)
                self.assertEqual(len(json.loads((directory/'preview.json').read_text())['machines']),2)
        result = json.loads((directory/'outcome.json').read_text())
        self.assertEqual(result['counts'], {'SUCCEEDED':2})
        self.assertTrue(result['changed'])
        self.assertEqual(len(self.fake.batches),1)  # run_once did not run once per inventory host
    def test_tls_hostname_validation(self):
        c = self.config(self.path/'unused')
        c['url'] = self.url.replace('localhost', '127.0.0.1')
        with self.assertRaisesRegex(m.Stop, 'TLS'): m.Api(c)
    def test_redirect_never_forwards_session(self):
        api = m.Api(self.config(self.path/'unused'))
        with self.assertRaisesRegex(m.Stop, 'Redirecionamento'): api.call('redirect')
    def test_error_does_not_expose_response_body(self):
        api = m.Api(self.config(self.path/'unused'))
        with self.assertRaises(m.ApiError) as caught: api.call('forbidden')
        self.assertEqual(caught.exception.status, 403)
        self.assertNotIn('private details', str(caught.exception))
    def test_untrusted_ca_rejected(self):
        c = self.config(self.path/'unused')
        c['ca_file'] = '/missing-ca-file.pem'
        with self.assertRaises(OSError): m.Api(c)


if __name__ == '__main__': unittest.main(verbosity=2)
