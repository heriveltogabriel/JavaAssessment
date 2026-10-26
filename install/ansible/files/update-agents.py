#!/usr/bin/env python3
"""Orchestrate existing signed agent updates; credentials arrive only on stdin."""
import datetime as dt
import fcntl
import http.cookiejar
import json
import os
from pathlib import Path
import re
import ssl
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BUSY = {'WAITING', 'DOWNLOADING', 'INSTALLING', 'VERIFYING', 'UNCONFIRMED'}
TERMINAL = {'SUCCEEDED', 'FAILED', 'ROLLED_BACK', 'CANCELLED', 'EXPIRED'}


class Stop(Exception):
    pass


class ApiError(Stop):
    def __init__(self, status):
        self.status = status
        super().__init__('API HTTP %s; execução interrompida. Verifique acesso e dashboard.' % status)


def validate(c):
    url = urllib.parse.urlsplit(c.get('url', ''))
    if (url.scheme != 'https' or not url.hostname or url.username or url.password
            or url.query or url.fragment or url.path not in ('', '/')):
        raise Stop('Informe somente a origem HTTPS do dashboard, sem credenciais na URL.')
    c['url'] = c['url'].rstrip('/')
    if not re.fullmatch(r'\d+\.\d+\.\d+', c.get('version', '')):
        raise Stop('Informe a versão alvo explícita, por exemplo 2.1.24.')
    if not all(isinstance(c.get(k), str) and c[k] for k in ('email', 'password', 'ca_file', 'report_dir')):
        raise Stop('Preencha administrador, senha no Vault, CA e diretório privado de relatório.')
    for key, default, lo, hi in [('batch_size', 5, 1, 5), ('poll_seconds', 15, 5, 120),
                                ('batch_timeout', 1800, 300, 7200), ('contact_seconds', 300, 60, 900),
                                ('pause_seconds', 15, 0, 300)]:
        value = c.setdefault(key, default)
        if type(value) is not int or not lo <= value <= hi:
            raise Stop('Parâmetro fora do limite: ' + key)
    if type(c.setdefault('apply', False)) is not bool:
        raise Stop('apply deve ser booleano.')
    if not isinstance(c.get('targets'), list) or not c['targets']:
        raise Stop('Selecione máquinas no inventário e com --limit.')
    for target in c['targets']:
        if not isinstance(target, dict) or not isinstance(target.get('name'), str) or not target['name']:
            raise Stop('Cada alvo precisa de name (hostname exato no dashboard).')
        if target.get('url', c['url']).rstrip('/') != c['url']:
            raise Stop('Selecione somente hosts do mesmo dashboard.')
        if not isinstance(target.get('id', ''), str):
            raise Stop('ID de máquina inválido.')
    return c


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise Stop('Redirecionamento recusado; use a URL HTTPS final do dashboard.')


class Api:
    def __init__(self, config):
        self.c = config
        context = ssl.create_default_context(cafile=config['ca_file'])
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect(),
            urllib.request.HTTPSHandler(context=context),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = ''
        self.login()

    def login(self):
        result = self.call('login', {'email': self.c['email'], 'password': self.c['password']}, renew=False)
        if (not result.get('authenticated') or result.get('mustChangePassword')
                or result.get('user', {}).get('role') != 'ADMIN' or not result.get('csrfToken')):
            raise Stop('Use um administrador ativo, com a primeira troca de senha concluída.')
        self.csrf = result['csrfToken']

    def call(self, path, body=None, renew=True):
        headers = {'Accept': 'application/json', 'Origin': self.c['url']}
        data = None
        if body is not None:
            headers.update({'Content-Type': 'application/json', 'X-CSRF-Token': self.csrf})
            data = json.dumps(body).encode('utf-8')
        request = urllib.request.Request(self.c['url'] + '/api/' + path, data=data, headers=headers)
        # One request at a time; no rapid retries against a busy dashboard.
        time.sleep(0.2)
        try:
            with self.opener.open(request, timeout=30) as response:
                raw = response.read(32 * 1024 * 1024 + 1)
                if len(raw) > 32 * 1024 * 1024:
                    raise Stop('Resposta excedeu o limite de leitura.')
                result = json.loads(raw)
                if not isinstance(result, dict):
                    raise Stop('Resposta inesperada da API.')
                return result
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status == 401 and renew and path != 'login':
                self.login()
                return self.call(path, body, renew=False)
            raise ApiError(status) from None
        except (OSError, ValueError, urllib.error.URLError):
            raise Stop('Falha de conexão/TLS/resposta. Não avance manualmente; retome com o mesmo diretório.') from None


def stamp(value):
    try:
        return dt.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()
    except (TypeError, ValueError, AttributeError):
        return None


def version(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d+\.\d+\.\d+', value):
        return None
    return tuple(map(int, value.split('.')))


class Files:
    def __init__(self, directory):
        self.path = Path(directory).absolute()
        if self.path.is_symlink():
            raise Stop('O diretório de relatório não pode ser um link.')
        self.path.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = self.path.stat()
        if info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise Stop('O diretório de relatório deve pertencer ao usuário atual e ter permissão 0700.')
        self.lock = os.open(str(self.path / '.lock'), os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            os.close(self.lock)
            raise Stop('Outra execução já usa esse diretório de relatório.') from None

    def close(self):
        os.close(self.lock)

    def read(self, name):
        path = self.path / name
        if path.is_symlink():
            raise Stop('Arquivo de controle não pode ser um link.')
        return json.loads(path.read_text()) if path.exists() else None

    def write(self, name, value):
        fd, temporary = tempfile.mkstemp(dir=str(self.path), prefix='.write-')
        try:
            with os.fdopen(fd, 'w') as stream:
                json.dump(value, stream, ensure_ascii=False, indent=2)
                stream.write('\n')
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path / name)
            directory = os.open(str(self.path), os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


class Campaign:
    def __init__(self, config, api, files, sleep=time.sleep, clock=time.time):
        self.c, self.api, self.files, self.sleep, self.clock = config, api, files, sleep, clock
        self.j = None
        self.changed = False

    def save(self):
        self.files.write('journal.json', self.j)
        counts = {}
        for item in self.j['results'].values():
            status = item['status']
            counts[status] = counts.get(status, 0) + 1
        self.files.write('report.json', {
            'version': self.c['version'], 'updatedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
            'counts': counts, 'active': self.j.get('active'), 'blocked': self.j.get('blocked', ''),
            'machines': list(self.j['results'].values())})

    def list_hosts(self):
        hosts, page = {}, 1
        while True:
            data = self.api.call('state?view=hosts&pageSize=100&page=%d' % page)
            if data.get('version') != self.c['version']:
                raise Stop('A versão alvo deve ser a versão publicada neste dashboard.')
            for host in data['hosts']:
                hosts[host['id']] = host
            if page >= data['pagination']['pages']:
                return hosts
            if data['pagination']['page'] != page:
                raise Stop('Inventário mudou durante a paginação. Execute novamente.')
            page += 1

    def resolve(self, hosts):
        selected = []
        for target in self.c['targets']:
            if target.get('id'):
                matches = [h for h in hosts.values() if h['id'] == target['id']]
            else:
                name = target['name'].lower().rstrip('.')
                matches = [h for h in hosts.values() if name in {
                    str(h.get('hostname', '')).lower().rstrip('.'),
                    str(h.get('fqdn', '')).lower().rstrip('.')}]
            if len(matches) != 1:
                raise Stop('Hostname ausente/ambíguo: %s. Defina assessment_update_host_id.' % target['name'])
            if matches[0]['id'] in selected:
                raise Stop('Dois itens do inventário apontam para o mesmo cadastro; remova o duplicado.')
            selected.append(matches[0]['id'])
        return selected

    def host(self, host_id):
        data = self.api.call('state?view=hosts&hostId=' + urllib.parse.quote(host_id, safe=''))
        if data.get('version') != self.c['version']:
            raise Stop('A versão do dashboard mudou durante a campanha.')
        matches = [h for h in data['hosts'] if h['id'] == host_id]
        if len(matches) != 1:
            raise Stop('Cadastro não encontrado; campanha interrompida.')
        return matches[0], stamp(data.get('serverTime'))

    def basic(self, host, now):
        if host.get('credentialStatus') != 'active' or host.get('agentLifecycle') == 'uninstalled':
            return 'REVOKED_OR_UNINSTALLED'
        current = version(host.get('collectorVersion'))
        if current is None:
            return 'VERSION_UNKNOWN'
        if current >= version(self.c['version']):
            return 'ALREADY_CURRENT' if current == version(self.c['version']) else 'NEWER_VERSION'
        last = stamp(host.get('lastSeen'))
        if now is None or last is None or last > now + 60 or now - last > self.c['contact_seconds']:
            return 'NO_RECENT_CONTACT'
        return 'READY'

    def external_busy(self, own=''):
        page = 1
        while True:
            data = self.api.call('work-requests?type=AGENT_UPDATE&status=ACTIVE&pageSize=100&page=%d' % page)
            if any(item['id'] != own for item in data['items']):
                raise Stop('Existem outros lotes ativos. Aguarde ou cancele os pedidos antigos antes de aplicar.')
            if page >= data['pagination']['pages']:
                return
            page += 1

    def detail(self, batch_id):
        return self.api.call('work-requests/detail?type=AGENT_UPDATE&id=' + urllib.parse.quote(batch_id, safe=''))

    def result(self, host, status, **extra):
        self.j['results'][host['id']] = dict(hostId=host['id'], hostname=host.get('hostname', ''),
                                             fromVersion=host.get('collectorVersion', ''), status=status, **extra)

    def cancel_waiting(self, machines):
        # Reverse ordinal avoids successively releasing the next machine ourselves.
        for machine in reversed(machines):
            if machine['status'] == 'WAITING':
                try:
                    self.api.call('agent-updates/cancel', {'hostId': machine['hostId'], 'requestId': machine['id']})
                    self.changed = True
                except ApiError as error:
                    if error.status != 409:
                        raise

    def stop_batch(self, reason, machines):
        self.j['blocked'] = reason
        self.save()
        self.cancel_waiting(machines)
        latest = self.detail(self.j['active']['batchId'])
        for machine in latest['machines']:
            item = self.j['results'][machine['hostId']]
            item['status'] = machine['status']
            item['diagnostic'] = machine.get('diagnostic', '')
        self.save()
        raise Stop(reason + '; somente pendentes do próprio lote foram cancelados. Confira Solicitações.')

    def submit(self):
        active = self.j['active']
        response = self.api.call('agent-updates', {
            'hostIds': active['ids'], 'targetVersion': self.c['version'], 'requestKey': active['key'],
            'expiresInMinutes': 60, 'runAssessmentAfterUpdate': False})
        self.changed = True
        active['batchId'] = response['batchId']
        active['requestIds'] = [r['id'] for r in response['requests']]
        for item in response.get('skipped', []):
            host_id = item['hostId']
            if host_id not in active['ids']:
                raise Stop('Resposta de criação contém host fora da seleção.')
            self.j['results'][host_id] = dict(self.j['results'][host_id], status='SKIPPED', reason=item['reason'])
        self.save()

    def monitor(self):
        active = self.j['active']
        if not active.get('batchId'):
            # Replaying exactly the saved key recovers a POST whose response was lost.
            self.submit()
        while True:
            detail = self.detail(active['batchId'])
            machines = detail['machines']
            if (set(m['id'] for m in machines) != set(active['requestIds'])
                    or any(m['hostId'] not in active['ids'] for m in machines)):
                raise Stop('Detalhe do lote não corresponde aos pedidos persistidos.')
            for m in machines:
                before = self.j['results'][m['hostId']]
                self.j['results'][m['hostId']] = dict(before, status=m['status'], requestId=m['id'],
                                                       diagnostic=m.get('diagnostic', ''))
            self.save()
            states = {m['status'] for m in machines}
            if states - BUSY - TERMINAL:
                self.stop_batch('UNKNOWN_STATUS', machines)
            if states & {'UNCONFIRMED', 'FAILED', 'ROLLED_BACK'}:
                self.stop_batch('UPDATE_REQUIRES_ATTENTION', machines)
            if not states & BUSY:
                for m in machines:
                    if m['status'] == 'SUCCEEDED':
                        host, _ = self.host(m['hostId'])
                        if host.get('collectorVersion') != self.c['version']:
                            self.stop_batch('VERSION_NOT_CONFIRMED', machines)
                        self.j['results'][m['hostId']]['confirmedVersion'] = host['collectorVersion']
                self.j['active'] = None
                self.save()
                return
            if self.clock() >= active['deadline']:
                self.stop_batch('MONITOR_TIMEOUT', machines)
            self.sleep(self.c['poll_seconds'])

    def run(self):
        hosts = self.list_hosts()
        selected = self.resolve(hosts)
        identity = {'url': self.c['url'], 'version': self.c['version'], 'selected': sorted(selected)}
        if not self.c['apply']:
            rows = []
            # Recheck presence and eligibility per host; never use the global preview.
            for host_id in selected:
                host, now = self.host(host_id)
                status = self.basic(host, now)
                if status == 'READY':
                    p = self.api.call('agent-updates?hostId=' + urllib.parse.quote(host_id, safe=''))
                    status = 'READY' if p['hosts'][0]['eligible'] else p['hosts'][0]['reason']
                rows.append({'hostId': host_id, 'hostname': host.get('hostname', ''),
                             'version': host.get('collectorVersion', ''), 'status': status})
            self.files.write('preview.json', {'version': self.c['version'], 'machines': rows})
            return {'mode': 'preview', 'selected': len(rows), 'ready': sum(r['status'] == 'READY' for r in rows)}
        self.j = self.files.read('journal.json')
        if self.j is None:
            self.external_busy()
            self.j = {'identity': identity, 'results': {}, 'active': None, 'blocked': ''}
            self.save()
        if self.j['identity'] != identity:
            raise Stop('Diretório pertence a outra seleção/versão/dashboard; use outro diretório privado.')
        if self.j.get('blocked'):
            raise Stop('Campanha bloqueada: ' + self.j['blocked'] + '. Confira o lote antes de iniciar nova campanha.')
        if self.j.get('active'):
            self.monitor()
        pending = [i for i in selected if i not in self.j['results']]
        while pending:
            self.external_busy()
            ids = []
            while pending and len(ids) < self.c['batch_size']:
                host_id = pending.pop(0)
                host, now = self.host(host_id)
                status = self.basic(host, now)
                if status == 'READY':
                    p = self.api.call('agent-updates?hostId=' + urllib.parse.quote(host_id, safe=''))
                    if p.get('release', {}).get('version') != self.c['version'] or not p['release'].get('available'):
                        raise Stop('Release não está disponível na API.')
                    entries = [h for h in p['hosts'] if h['id'] == host_id]
                    if len(entries) != 1:
                        raise Stop('Prévia do host inconsistente.')
                    status = 'READY' if entries[0]['eligible'] else entries[0]['reason']
                    if status in {'UPDATE_BUSY', 'UNINSTALL_BUSY'}:
                        raise Stop('Operação concorrente encontrada; aguarde sua conclusão.')
                if status == 'READY':
                    ids.append(host_id)
                    # Persist these only together with the idempotency key below.
                    self.result(host, 'SUBMITTING')
                else:
                    self.result(host, status)
            if not ids:
                self.save()
                continue
            self.external_busy()
            self.j['active'] = {'key': uuid.uuid4().hex, 'ids': ids, 'batchId': '',
                                'deadline': self.clock() + self.c['batch_timeout']}
            self.save()  # Durable before the first mutating request.
            self.submit()
            self.monitor()
            if pending:
                self.sleep(self.c['pause_seconds'])
        return {'mode': 'apply', 'selected': len(selected), 'finished': True,
                'counts': json.loads((self.files.path / 'report.json').read_text())['counts']}


def main():
    files = None
    campaign = None
    try:
        os.umask(0o077)
        config = validate(json.load(sys.stdin))
        files = Files(config['report_dir'])
        campaign = Campaign(config, Api(config), files)
        result = campaign.run()
        result['changed'] = campaign.changed
        result['ok'] = True
        files.write('outcome.json', {'ok': True, **result})
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except Stop as error:
        outcome = {'ok': False, 'changed': bool(campaign and campaign.changed), 'error': str(error)}
        if files:
            files.write('outcome.json', outcome)
        print(json.dumps(outcome, ensure_ascii=False))
        return 1
    except (Exception, KeyboardInterrupt):
        # Do not print exceptions that might include credentials or response bodies.
        outcome = {'ok': False, 'error': 'Interrompido; retome com a mesma seleção e diretório.'}
        if files:
            files.write('outcome.json', outcome)
        print(json.dumps(outcome, ensure_ascii=False))
        return 1
    finally:
        if files:
            files.close()


if __name__ == '__main__':
    sys.exit(main())
