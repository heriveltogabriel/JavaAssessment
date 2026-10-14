#!/usr/bin/env python3
"""User-run, single-host Linux canary. Never publishes or stores credentials.

Requires the separately published candidate, root-protected systemd installation,
Python 3 on the canary and passwordless sudo. Evidence is private in the ignored
output directory. Resume only with the original receipt after any lost reply.
"""
import argparse
import datetime as dt
import getpass
import hashlib
import http.cookiejar
import ipaddress
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
HOST_ID = re.compile(r"host-[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}\Z")
UUID = re.compile(r"[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}\Z")
SHA = re.compile(r"[a-f0-9]{64}\Z")
FAILURES = {"FAILED", "ROLLED_BACK", "CANCELLED", "EXPIRED"}
PRESERVED = ("CONFIG_SHA256", "INSTALLATION_ID_SHA256", "JAVA_PATH_SHA256",
             "CONFIGURED_CA_SHA256", "UPDATE_PUBLIC_KEY_SHA256")


class Stop(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise Stop(message)


def stamp():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def load_json(path, private=False):
    path = Path(path)
    require(not path.is_symlink() and path.is_file(), "Arquivo ausente ou simbólico: " + str(path))
    info = path.stat()
    if private:
        require(info.st_uid == os.getuid() and not info.st_mode & 0o077,
                "O recibo de retomada precisa pertencer ao usuário e ter permissão 600.")
        require(not path.parent.stat().st_mode & 0o077, "A pasta de evidências precisa ter permissão 700.")
    require(info.st_size < 2 * 1024 * 1024, "Arquivo de evidência maior que o limite.")
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), "Objeto JSON esperado.")
    return value


def save(path, value):
    path = Path(path)
    require(not path.is_symlink(), "Recibo simbólico recusado.")
    fd, temporary = tempfile.mkstemp(prefix=".receipt-", dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(str(path.parent), os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class API:
    def __init__(self, origin, ca):
        self.origin = origin
        self.csrf = None
        self.cookies = http.cookiejar.CookieJar()  # Memory only.
        context = ssl.create_default_context(cafile=str(ca))
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect(),
            urllib.request.HTTPSHandler(context=context),
            urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, path, body=None):
        require(path.startswith("/") and not path.startswith("//"), "Endpoint inválido.")
        headers = {"Accept": "application/json", "Origin": self.origin,
                   "X-Assessment-Background": "1"}
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
            if self.csrf:
                headers["X-CSRF-Token"] = self.csrf
        request = urllib.request.Request(self.origin + path, data=data, headers=headers)
        try:
            with self.opener.open(request, timeout=20) as response:
                raw = response.read(2 * 1024 * 1024 + 1)
                require(len(raw) <= 2 * 1024 * 1024, "Resposta HTTP excedeu o limite.")
                result = json.loads(raw)
                require(isinstance(result, dict), "Resposta HTTP inválida.")
                return result
        except urllib.error.HTTPError as error:
            raise Stop("Dashboard retornou HTTP %s em %s; nenhum detalhe sensível foi salvo." %
                       (error.code, path.split("?")[0])) from None
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise ConnectionError("Conexão HTTPS não confirmada; tente retomar com o mesmo recibo.") from error

    def login(self):
        email = input("Email do administrador do dashboard: ").strip()
        password = getpass.getpass("Senha (não será salva): ")
        try:
            result = self.request("/api/login", {"email": email, "password": password})
        finally:
            password = None
        require(result.get("authenticated") is True and
                result.get("user", {}).get("role") == "ADMIN" and
                result.get("mustChangePassword") is False,
                "Entre primeiro no dashboard com um administrador e conclua a troca de senha.")
        self.csrf = result.get("csrfToken")
        require(isinstance(self.csrf, str) and len(self.csrf) >= 16, "Sessão sem proteção CSRF.")


# Additional read-only Linux evidence. No complete process arguments or properties
# are emitted. Python is required on this RHEL 8 canary, never installed here.
EXTRA = r'''
printf 'HOST_ID='
sed -n 's/^[[:space:]]*hostId[[:space:]]*=[[:space:]]*\(host-[a-f0-9-]*\)[[:space:]]*$/\1/p' "$ROOT/conf/collector.properties"
python3 -I - <<'CANARY_PY'
import json, os, pathlib, re
root=pathlib.Path('/opt/java-assessment-collector')
print('BOOT_ID='+pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip())
apps=[]
for path in pathlib.Path('/proc').glob('[0-9]*'):
    try:
        args=(path/'cmdline').read_bytes().split(b'\0')
        if pathlib.Path(os.readlink(str(path/'exe'))).name != 'java': continue
        if any(b'/opt/java-assessment-collector' in a or b'/opt/java-assessment-update' in a
               or a.startswith(b'assessment.') for a in args): continue
        tail=(path/'stat').read_text().rpartition(')')[2].split()
        apps.append({'pid':int(path.name),'startTicks':int(tail[19])})
    except (OSError, ValueError, IndexError): pass
print('APPLICATION_PROCESSES='+json.dumps(sorted(apps,key=lambda x:x['pid']),separators=(',',':')))
pointer=root/'current.json'
if pointer.is_file() and not pointer.is_symlink():
    p=json.loads(pointer.read_text())
    for key,label in [('version','POINTER_VERSION'),('bundleSha256','BUNDLE_SHA256'),('managerSha256','MANAGER_SHA256')]:
        v=p.get(key,'')
        if isinstance(v,str) and re.fullmatch(r'[a-zA-Z0-9._-]{1,80}',v): print(label+'='+v)
CANARY_PY
'''


class SSH:
    def __init__(self, host, user, identity):
        try:
            ipaddress.ip_address(host)
        except ValueError:
            raise Stop("Informe um único IP literal em --host.") from None
        require(re.fullmatch(r"[a-z_][a-z0-9_-]{0,31}", user), "Usuário SSH inválido.")
        require(identity.is_file(), "Chave SSH não encontrada: " + str(identity))
        self.command = ["ssh", "-i", str(identity), "-o", "StrictHostKeyChecking=ask",
                        "-o", "ConnectTimeout=12", "-o", "ServerAliveInterval=15",
                        "-o", "ServerAliveCountMax=2", user + "@" + host]

    def execute(self, payload, timeout=120, reboot=False):
        try:
            result = subprocess.run(self.command + ["sudo -n /bin/sh -s"],
                                    input=payload.encode("utf-8"), stdout=subprocess.PIPE,
                                    timeout=timeout, check=False)
        except subprocess.TimeoutExpired:
            raise ConnectionError("SSH excedeu o tempo; não houve limpeza ou nova tentativa de instalação.") from None
        if reboot:
            # Disconnect is expected; changed boot_id is the only success proof.
            require(result.returncode in (0, 255), "Reinício não foi aceito pelo SSH/sudo.")
            return ""
        if result.returncode:
            raise ConnectionError("Diagnóstico SSH não concluiu (código %s). Confira acesso e sudo." % result.returncode)
        require(len(result.stdout) < 256 * 1024, "Diagnóstico remoto maior que o limite.")
        return result.stdout.decode("utf-8", errors="strict")

    def inspect(self):
        payload = (PROJECT / "ops/agent-canary-preflight.sh").read_text() + "\n" + EXTRA
        return parse_remote(self.execute(payload))

    def reboot(self):
        self.execute("set -eu\nPATH=/usr/sbin:/usr/bin:/sbin:/bin; export PATH\n"
                     "test \"$(id -u)\" = 0\ncommand -v systemctl >/dev/null\n"
                     "systemctl reboot\n", timeout=35, reboot=True)


def parse_remote(raw):
    evidence = {"services": {}, "pending": []}
    unit = None
    for line in raw.splitlines():
        key, separator, value = line.partition("=")
        if not separator:
            continue
        if key == "UNIT":
            unit = value
            evidence["services"][unit] = {}
        elif key in ("LoadState", "ActiveState", "SubState", "MainPID") and unit:
            evidence["services"][unit][key] = value
        elif key == "PENDING":
            evidence["pending"].append(value)
        elif key in set(PRESERVED) | {"HOST_ID", "BOOT_ID", "CAPABILITY", "COLLECTOR_JAR_VERSION",
                                    "LEGACY_HELPER_VERSION", "ROOT_SERVICE", "SCHEDULER", "ARCH",
                                    "POINTER_VERSION", "BUNDLE_SHA256", "MANAGER_SHA256",
                                    "PREFLIGHT_COMPLETE", "APPLICATION_PROCESSES"}:
            require(key not in evidence, "Diagnóstico duplicado: " + key)
            evidence[key] = value
    require(evidence.get("PREFLIGHT_COMPLETE") == "READ_ONLY", "Diagnóstico incompleto.")
    require(HOST_ID.fullmatch(evidence.get("HOST_ID", "")), "ID do agente ausente ou fora do formato permitido.")
    require(UUID.fullmatch(evidence.get("BOOT_ID", "")), "boot_id inválido.")
    for key in PRESERVED:
        require(SHA.fullmatch(evidence.get(key, "")), "Hash não confirmado: " + key)
    processes = json.loads(evidence.pop("APPLICATION_PROCESSES", "null"))
    require(isinstance(processes, list) and len(processes) <= 4096, "Processos não confirmados.")
    for item in processes:
        require(isinstance(item, dict) and set(item) == {"pid", "startTicks"} and
                all(type(item[k]) is int and item[k] > 0 for k in item), "Identidade de processo inválida.")
    evidence["applications"] = processes
    return evidence


def active(evidence, unit):
    info = evidence["services"].get(unit, {})
    return info.get("LoadState") == "loaded" and info.get("ActiveState") == "active"


def initial_remote(evidence):
    require(evidence.get("COLLECTOR_JAR_VERSION") == "1.5.12" and evidence.get("CAPABILITY") == "update-v1",
            "O canário inicial exige coletor 1.5.12 com update-v1.")
    require(re.fullmatch(r"1\.5\.(9|10|11|12|13)", evidence.get("LEGACY_HELPER_VERSION", "")),
            "Versão do auxiliar não reconhecida; confira o diagnóstico antes de continuar.")
    require(not evidence["pending"], "Há arquivos de operação pendente; não serão apagados pelo teste.")
    require(active(evidence, "java-assessment-collector.timer") and
            active(evidence, "java-assessment-update.service"), "Timer/atualizador original não estão ativos.")
    require(not active(evidence, "java-assessment-collector.service"), "Coletor executando; aguarde a coleta terminar.")


def final_remote(before, after, version, expected, same_processes=True):
    require(before["HOST_ID"] == after["HOST_ID"], "O cadastro do agente mudou.")
    for key in PRESERVED:
        require(before[key] == after[key], "Arquivo preservado mudou: " + key)
    require(after.get("CAPABILITY") == "update-v3" and after.get("POINTER_VERSION") == version,
            "Versão/pointer/capacidade final não confirmados.")
    require(after.get("BUNDLE_SHA256") == expected["bundleSha256"] and
            after.get("MANAGER_SHA256") == expected["managerSha256"],
            "Hashes ativos diferem do bundle/manager assinado da publicação.")
    require(active(after, "java-assessment-collector.timer") and
            active(after, "java-assessment-recovery.service"), "Timer ou recuperação não estão ativos.")
    if same_processes:
        require(before["BOOT_ID"] == after["BOOT_ID"], "A VM reiniciou durante a atualização.")
        require({(p["pid"], p["startTicks"]) for p in before["applications"]}.issubset(
                {(p["pid"], p["startTicks"]) for p in after["applications"]}),
                "Uma aplicação Java anterior terminou/reiniciou durante a atualização; interrompendo a prova.")


def host_state(api, host_id):
    host = api.request("/api/item?" + urllib.parse.urlencode({"kind": "hosts", "hostId": host_id, "id": host_id}))["item"]
    require(host.get("id") == host_id and host.get("credentialStatus") == "active", "Agente não está ativo no dashboard.")
    return host


def policy(host):
    settings = host.get("settings")
    require(isinstance(settings, dict), "Política efetiva não disponível.")
    return {"environmentId": host.get("environmentId", ""),
            "settingsSha256": hashlib.sha256(json.dumps(settings, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
            "limits": {k: settings[k] for k in ("maxDurationSeconds", "maxFiles", "batchSize", "pauseMillis", "inspectArchives") if k in settings}}


def validate_preview(api, host_id, version):
    preview = api.request("/api/agent-updates?" + urllib.parse.urlencode({"hostId": host_id}))
    release = preview.get("release", {})
    require(release.get("version") == version and release.get("available") is True and
            release.get("format") == "agent-bundle-v1", "Pacote completo da versão candidata não está disponível.")
    hosts = preview.get("hosts")
    require(isinstance(hosts, list) and len(hosts) == 1 and hosts[0].get("id") == host_id,
            "O preview não selecionou exatamente o único agente informado.")
    selected = hosts[0]
    require(selected.get("eligible") is True and selected.get("collectorVersion") == "1.5.12" and
            selected.get("updateMode") == "MIGRATION", "Migração não elegível: " + str(selected.get("reason", "desconhecido")))
    host = host_state(api, host_id)
    require(host.get("collectorVersion") == "1.5.12" and host.get("updateCapability") == "update-v1",
            "Origem informada pelo dashboard difere do diagnóstico.")
    require(host.get("uninstall") is None or host["uninstall"].get("status") not in {"WAITING", "UNINSTALLING", "UNCONFIRMED"},
            "Desinstalação pendente.")
    jobs = api.request("/api/work-requests?" + urllib.parse.urlencode(
        {"hostId": host_id, "type": "ASSESSMENT", "status": "ACTIVE", "pageSize": 1}))
    require(jobs.get("pagination", {}).get("total") == 0, "Assessment pendente/em execução; aguarde antes de testar.")
    return policy(host)


def stream_hash(stream):
    digest = hashlib.sha256()
    while True:
        block = stream.read(128 * 1024)
        if not block:
            return digest.hexdigest()
        digest.update(block)


def verify_signature(raw, signature, public_key):
    # Use macOS' existing OpenSSL, never install a dependency or implement RSA.
    with tempfile.TemporaryDirectory(prefix="assessment-public-signature-") as folder:
        folder = Path(folder)
        for name, value in (("manifest.json", raw), ("manifest.sig", signature), ("public.der", public_key)):
            (folder / name).write_bytes(value)
            (folder / name).chmod(0o600)
        try:
            converted = subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-in", str(folder / "public.der"),
                                        "-out", str(folder / "public.pem")], capture_output=True, timeout=15, check=False)
            require(converted.returncode == 0, "Não foi possível conferir a chave pública assinante do candidato.")
            verified = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(folder / "public.pem"),
                                       "-signature", str(folder / "manifest.sig"), str(folder / "manifest.json")],
                                      capture_output=True, timeout=15, check=False)
            require(verified.returncode == 0, "A assinatura do bundle candidato não confere.")
        except (FileNotFoundError, subprocess.TimeoutExpired):
            raise Stop("OpenSSL local não está disponível para validar a assinatura do candidato.") from None


def expected_bundle(release_dir, manifest, receipt, version):
    filename = "packages/assessment-dashboard-runtime-" + version + ".zip"
    artifacts = [entry for entry in manifest.get("artifacts", []) if entry.get("file") == filename]
    require(len(artifacts) == 1 and SHA.fullmatch(str(artifacts[0].get("sha256", ""))) and
            artifacts[0]["sha256"] == receipt.get("runtimeSha256"), "Checksum do runtime publicado não confere com o manifesto.")
    runtime = release_dir / filename
    require(runtime.is_file() and not runtime.is_symlink(), "Runtime local ausente ou simbólico.")
    with runtime.open("rb") as stream:
        require(stream_hash(stream) == artifacts[0]["sha256"], "Runtime candidato mudou após a publicação.")
    with zipfile.ZipFile(runtime) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "Entradas duplicadas no runtime.")
        prefix = "dist/agent-bundles/" + version + "/"
        metadata = archive.getinfo(prefix + "manifest.json")
        require(metadata.file_size <= 65536, "Manifesto do bundle excedeu o limite.")
        raw = archive.read(metadata)
        signature = archive.read(prefix + "manifest.sig")
        public_key = archive.read("dist/agent-update-public.der")
        require(len(signature) == 384 and len(public_key) < 4096 and
                hashlib.sha256(public_key).hexdigest() == manifest.get("agentUpdatePublicKeySha256"),
                "Pin da chave pública do candidato não confere.")
        verify_signature(raw, signature, public_key)
        bundle = json.loads(raw)
        require(bundle.get("version") == version and bundle.get("format") == "agent-bundle-v1" and
                bundle.get("minUpdater") == 3 and SHA.fullmatch(str(bundle.get("sha256", ""))),
                "Manifesto assinado do bundle é incompatível.")
        info = archive.getinfo(prefix + "bundle.zip")
        require(info.file_size == bundle.get("bytes") and info.file_size <= 64 * 1024 * 1024,
                "Tamanho do bundle assinado inválido.")
        with archive.open(info) as stream:
            require(stream_hash(stream) == bundle["sha256"], "Checksum do bundle não confere com a assinatura.")
        manager = bundle.get("files", {}).get("manager/helper.jar", {}).get("sha256")
        require(SHA.fullmatch(str(manager)), "Manager ausente do manifesto assinado.")
        return {"bundleSha256": bundle["sha256"], "managerSha256": manager}


def package(release_dir):
    manifest = load_json(release_dir / "packages/package-manifest.json")
    receipt = load_json(release_dir / "deployment/deployment-result.json")
    version = manifest.get("dashboardVersion")
    require(isinstance(version, str) and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version), "Versão candidata inválida.")
    require(manifest.get("candidateOnly") is True, "Este runner exige package-manifest com candidateOnly=true.")
    require(manifest.get("agentVersion") == version and receipt.get("status") == "deployed" and
            receipt.get("dashboardVersion") == version and receipt.get("collectorVersion") == version and
            receipt.get("externalHttpsVerified") is True and receipt.get("externalVersion") == version and
            receipt.get("health", {}).get("version") == version,
            "Publique e confirme o dashboard candidato primeiro; o recibo não confirma esta versão/HTTPS.")
    origin = receipt.get("target", "")
    parsed = urllib.parse.urlsplit(origin)
    require(parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password and
            parsed.path in ("", "/") and not parsed.query and not parsed.fragment,
            "Origem HTTPS do recibo inválida.")
    ca = release_dir / "deployment/dashboard-public-ca.pem"
    require(ca.is_file() and not ca.is_symlink(), "CA pública da publicação não encontrada.")
    return version, origin.rstrip("/"), ca, expected_bundle(release_dir, manifest, receipt, version)


def create_operation(api, report, report_path):
    # Persist the request key BEFORE POST. A lost response is resumed with this
    # exact body/key, including runAssessmentAfterUpdate; never a new operation.
    report["phase"] = "REQUEST_PERSISTED"
    save(report_path, report)
    result = api.request("/api/agent-updates", report["request"])
    require(UUID.fullmatch(str(result.get("batchId", ""))), "Resposta sem batchId válido; retome pelo recibo.")
    requests = result.get("requests")
    require(result.get("skipped") == [] and isinstance(requests, list) and len(requests) == 1,
            "A API não criou uma única atualização; confira Work requests.")
    operation = requests[0]
    require(operation.get("hostId") == report["hostId"] and operation.get("targetVersion") == report["version"] and
            operation.get("runAssessmentAfterUpdate") is True and operation.get("updateMode") == "MIGRATION" and
            UUID.fullmatch(str(operation.get("id", ""))), "Resposta de atualização fora do contrato do canário.")
    report.update(batchId=result["batchId"], operationId=operation["id"], phase="FOLLOWING")
    save(report_path, report)


def wait_operation(api, report, report_path, deadline, sleep=None, monotonic=None):
    sleep = sleep or time.sleep
    monotonic = monotonic or time.monotonic
    last = None
    while monotonic() < deadline:
        try:
            detail = api.request("/api/work-requests/detail?" + urllib.parse.urlencode(
                {"id": report["batchId"], "type": "AGENT_UPDATE", "hostId": report["hostId"]}))
        except ConnectionError:
            if last != "OFFLINE":
                print("Conexão temporariamente indisponível; acompanhando o mesmo pedido.", flush=True)
            last = "OFFLINE"
            sleep(5)
            continue
        machines = detail.get("machines")
        require(isinstance(machines, list) and len(machines) == 1 and not detail.get("skipped"), "Work request deixou de representar um único canário.")
        machine = machines[0]
        require(machine.get("id") == report["operationId"] and machine.get("hostId") == report["hostId"] and
                machine.get("targetVersion") == report["version"] and machine.get("updateMode") == "MIGRATION",
                "Identidade do Work request não corresponde ao recibo.")
        assessment = machine.get("assessment") or {}
        state = (machine.get("status"), machine.get("migrationStage"), assessment.get("status"))
        if state != last:
            print("Atualização: %s · etapa: %s · assessment: %s" % (state[0], state[1] or "—", state[2] or "aguardando"), flush=True)
            report["progress"] = {"updateStatus": state[0], "migrationStage": state[1], "assessmentStatus": state[2], "at": stamp()}
            save(report_path, report)
            last = state
        require(machine.get("status") not in FAILURES, "Atualização terminou em %s (%s); reinício cancelado." %
                (machine.get("status"), machine.get("diagnostic", "")))
        require(assessment.get("status") not in FAILURES | {"SUPERSEDED"}, "Assessment não concluiu com sucesso; reinício cancelado.")
        if machine.get("status") == "SUCCEEDED" and assessment.get("status") in {"SUCCEEDED", "PARTIAL"}:
            require(assessment.get("hostId") == report["hostId"] and assessment.get("operationId") == report["operationId"] and
                    UUID.fullmatch(str(assessment.get("jobId", ""))), "Assessment não está vinculado à operação.")
            report["assessment"] = {k: assessment[k] for k in ("jobId", "status", "reused", "startedAt", "finishedAt") if k in assessment}
            report["phase"] = "UPDATED"
            save(report_path, report)
            return
        sleep(5)
    raise Stop("Tempo de acompanhamento esgotado. O pedido continua no dashboard; retome com --resume-file.")


def wait_contacts(api, report, report_path, deadline, after=None, sleep=None, monotonic=None):
    sleep = sleep or time.sleep
    monotonic = monotonic or time.monotonic
    # lastSeen is advanced by authentic agent traffic (heartbeat/results), not
    # by these GETs. Two distinct newer timestamps prove CONTACTS, not leases.
    previous = after or host_state(api, report["hostId"]).get("lastSeen")
    require(isinstance(previous, str) and previous, "Dashboard não fornece timestamp de contato.")
    observed = []
    while monotonic() < deadline:
        try:
            host = host_state(api, report["hostId"])
        except ConnectionError:
            sleep(5)
            continue
        require(host.get("collectorVersion") == report["version"] and host.get("updateCapability") == "update-v3",
                "Dashboard não confirma a versão/capacidade final.")
        require(host.get("bundleSha256") == report["expected"]["bundleSha256"] and
                host.get("managerSha256") == report["expected"]["managerSha256"],
                "Dashboard não confirma os hashes assinados do bundle/manager esperado.")
        require(host.get("environmentId", "") == report["policy"]["environmentId"], "O ambiente do agente mudou.")
        require(policy(host)["settingsSha256"] == report["policy"]["settingsSha256"], "A política efetiva mudou durante a prova.")
        at = host.get("lastSeen")
        if isinstance(at, str) and at > previous:
            observed.append(at)
            previous = at
            print("Contato real do agente confirmado: %s/2" % len(observed), flush=True)
            if len(observed) == 2:
                return observed
        sleep(5)
    raise Stop("Faltaram dois contatos reais posteriores do agente. Retome o recibo; não force nova atualização.")


def parser():
    result = argparse.ArgumentParser(description="Canário real de migração 1.5.12, um único host. Não publica o dashboard.")
    result.add_argument("--release-dir", required=True, type=Path)
    result.add_argument("--host", required=True, help="Um único IP literal da VM de teste")
    result.add_argument("--user", default="opc")
    result.add_argument("--identity", type=Path, default=Path.home() / "OneDrive/id_rsa")
    result.add_argument("--reiniciar", action="store_true", help="Autoriza reiniciar somente o canário após atualização/assessment confirmados")
    result.add_argument("--resume-file", type=Path, help="Recibo privado da execução interrompida; reutiliza a mesma chave de pedido")
    result.add_argument("--timeout-minutes", type=int, default=30, choices=range(20, 61), metavar="20..60")
    return result


def run(args):
    version, origin, ca, expected = package(args.release_dir.resolve())
    ssh = SSH(args.host, args.user, args.identity.expanduser())
    if args.resume_file:
        require(not args.resume_file.is_symlink(), "Recibo de retomada simbólico recusado.")
        report_path = args.resume_file.resolve()
        report = load_json(report_path, private=True)
        require(report.get("format") == "agent-canary-v1" and report.get("hostIp") == args.host and
                report.get("version") == version and report.get("origin") == origin and
                report.get("rebootRequested") is args.reiniciar and report.get("expected") == expected,
                "Retomada não corresponde ao host, versão, pacote assinado, origem ou opção de reinício.")
        require(report.get("phase") in {"PREFLIGHT", "REQUEST_PERSISTED", "FOLLOWING", "UPDATED", "VERIFIED", "REBOOT_REQUESTED", "COMPLETE"},
                "Fase de retomada desconhecida.")
        if report["phase"] != "PREFLIGHT":
            require(HOST_ID.fullmatch(str(report.get("hostId", ""))) and UUID.fullmatch(str(report.get("request", {}).get("requestKey", ""))) and
                    report["request"] == {"hostIds": [report["hostId"]], "targetVersion": version,
                    "requestKey": report["request"]["requestKey"], "expiresInMinutes": 60, "runAssessmentAfterUpdate": True},
                    "Pedido original do recibo inválido.")
        if report.get("phase") == "COMPLETE":
            print("Este canário já concluiu. Evidências: " + str(report_path))
            return 0
    else:
        evidence_root = PROJECT / "output/agent-canaries"
        require(not evidence_root.is_symlink(), "Pasta de evidências simbólica recusada.")
        evidence_root.mkdir(parents=True, mode=0o700, exist_ok=True)
        evidence_root.chmod(0o700)
        folder = Path(tempfile.mkdtemp(prefix=dt.datetime.now().strftime("%Y%m%d-%H%M%S-") + "canary-", dir=str(evidence_root)))
        folder.chmod(0o700)
        report_path = folder / "canary.json"
        report = {"format": "agent-canary-v1", "hostIp": args.host, "version": version, "origin": origin,
                  "rebootRequested": args.reiniciar, "expected": expected, "startedAt": stamp(), "phase": "PREFLIGHT"}
        save(report_path, report)
    print("Candidato %s · somente %s · evidências: %s" % (version, args.host, report_path), flush=True)
    print("A atualização preserva as aplicações; o reinício autorizado interromperá esta VM de teste." if args.reiniciar else
          "Sem reinício da VM nesta execução.", flush=True)
    api = API(origin, ca)
    health = api.request("/health")
    require(health.get("status") == "ok" and health.get("version") == version, "Saúde HTTPS/versão candidata não confirmadas.")
    api.login()
    deadline = time.monotonic() + args.timeout_minutes * 60
    try:
        if report["phase"] == "PREFLIGHT":
            baseline = ssh.inspect()
            # A normal short polling cycle may be active during inspection.
            for attempt in range(7):
                if not active(baseline, "java-assessment-collector.service"):
                    break
                require(attempt < 6, "Coletor ainda executando após 30 segundos; aguarde e repita o teste.")
                time.sleep(5)
                baseline = ssh.inspect()
            initial_remote(baseline)
            host_id = baseline["HOST_ID"]
            effective = validate_preview(api, host_id, version)
            print("Agente %s · ambiente %s · política %s" % (host_id, effective["environmentId"] or "sem ambiente", effective["settingsSha256"]), flush=True)
            print("Limites atuais: " + json.dumps(effective["limits"], ensure_ascii=False), flush=True)
            report.update(before=baseline, hostId=host_id, policy=effective,
                          request={"hostIds": [host_id], "targetVersion": version, "requestKey": str(uuid.uuid4()),
                                   "expiresInMinutes": 60, "runAssessmentAfterUpdate": True})
            create_operation(api, report, report_path)
        elif report["phase"] == "REQUEST_PERSISTED":
            create_operation(api, report, report_path)
        require(UUID.fullmatch(str(report.get("batchId", ""))) and UUID.fullmatch(str(report.get("operationId", ""))), "Recibo sem operação válida.")
        print("Work request: " + origin + "/#jobs?" + urllib.parse.urlencode(
            {"env": "all", "host": report["hostId"], "request": report["batchId"], "requestType": "AGENT_UPDATE"}), flush=True)
        if report["phase"] == "FOLLOWING":
            wait_operation(api, report, report_path, deadline)
        if report["phase"] == "UPDATED":
            after = ssh.inspect()
            final_remote(report["before"], after, version, expected)
            report["afterUpdate"] = after
            report["contactsAfterUpdate"] = wait_contacts(api, report, report_path, deadline)
            report["phase"] = "VERIFIED"
            save(report_path, report)
        if args.reiniciar and report["phase"] == "VERIFIED":
            current = ssh.inspect()
            final_remote(report["before"], current, version, expected)
            # Recheck no active assessment immediately before reboot.
            jobs = api.request("/api/work-requests?" + urllib.parse.urlencode(
                {"hostId": report["hostId"], "type": "ASSESSMENT", "status": "ACTIVE", "pageSize": 1}))
            require(jobs.get("pagination", {}).get("total") == 0, "Nova coleta ativa; reinício adiado.")
            report.update(phase="REBOOT_REQUESTED", bootBeforeReboot=current["BOOT_ID"])
            save(report_path, report)
            print("Reiniciando somente " + args.host + "...", flush=True)
            ssh.reboot()
        if report["phase"] == "REBOOT_REQUESTED":
            booted = None
            while time.monotonic() < deadline:
                try:
                    current = ssh.inspect()
                    if current["BOOT_ID"] != report["bootBeforeReboot"] and active(current, "java-assessment-collector.timer") and active(current, "java-assessment-recovery.service"):
                        booted = current
                        break
                except ConnectionError:
                    pass
                time.sleep(10)
            require(booted is not None, "Reinício não confirmado; retome o recibo. Nenhum segundo reboot será enviado.")
            final_remote(report["before"], booted, version, expected, same_processes=False)
            report["afterReboot"] = booted
            # Starting from the API observation AFTER changed boot_id avoids
            # counting a last pre-reboot heartbeat as post-boot contact.
            report["contactsAfterReboot"] = wait_contacts(api, report, report_path, deadline)
        report.update(phase="COMPLETE", finishedAt=stamp())
        save(report_path, report)
        print("CANARIO_OK: versão, identidade, configuração, Java, CA, recuperação, assessment e contatos confirmados.")
        if args.reiniciar:
            print("Reinício confirmado por novo boot_id e dois contatos posteriores.")
        if report.get("assessment", {}).get("status") == "PARTIAL":
            print("O assessment concluiu com observações; revise a cobertura no Work request.")
        print("Isso valida este canário; não certifica Java 6/SysV ou Linux 32 bits.")
        print("Evidências: " + str(report_path))
        return 0
    except (Stop, ConnectionError, KeyboardInterrupt):
        print("Acompanhamento interrompido. Nenhum journal, pacote ou serviço foi limpo.", file=sys.stderr)
        print("Retome com --resume-file \"%s\" e os mesmos argumentos." % report_path, file=sys.stderr)
        raise


def main():
    try:
        return run(parser().parse_args())
    except (Stop, ConnectionError, ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        print("ERRO: " + str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Interrompido pelo usuário.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
