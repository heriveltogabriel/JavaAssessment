#!/usr/bin/env python3
"""Prepare local review/deployment artifacts. Never connects to or changes a server."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/versioned-download-csv'
PREVIOUS = ROOT / 'output/remote-uninstall-review'
RUNTIME_NAME = 'assessment-dashboard-runtime-1.0-beta-csv.zip'
SOURCE_NAME = 'assessment-dashboard-source-1.0-beta-csv-review.zip'
MANUAL_NAME = 'assessment-dashboard-update-1.0-beta-csv.zip'
COLLECTOR_NAME = 'assessment-collector-1.5.0-uninstall-review.zip'
JDK = Path('/private/tmp/assessment-jdk17/jdk-17.0.20.1+1/Contents/Home')
STAMP = (2026, 10, 13, 0, 0, 0)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load_builder():
    sys.path.insert(0, str(ROOT / 'dashboard-installer'))
    spec = importlib.util.spec_from_file_location('csv_public_builder', ROOT / 'dashboard-installer/build.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_zip(path, complete=True):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate ZIP entry in ' + path.name)
        result = {name: archive.read(name) for name in names}
    require('SHA256SUMS' in result, 'Missing internal manifest: ' + path.name)
    listed = set()
    for line in result['SHA256SUMS'].decode().splitlines():
        expected, name = line.split('  ', 1)
        require(name not in listed and name in result and digest(result[name]) == expected,
                'Internal manifest mismatch: ' + path.name + ' / ' + name)
        listed.add(name)
    if complete:
        require(listed == set(result) - {'SHA256SUMS'}, 'Manifest does not cover all entries: ' + path.name)
    return result


def scan(name, content, secrets):
    parts = Path(name).parts
    require(not name.startswith('/') and '..' not in parts and '\\' not in name, 'Unsafe archive path: ' + name)
    require(parts[0] not in ('conf', 'data', 'logs') and not name.endswith(('.sqlite', '.jks', '.key', '.p12', '.log')), 'Private runtime entry: ' + name)
    require(not re.search(br'(?m)^-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----\r?$', content), 'Private key in public entry: ' + name)
    require(not any(secret in content for secret in secrets), 'Known local credential in public entry: ' + name)


def archive(path, entries, secrets):
    require('SHA256SUMS' not in entries, 'Input must not include an obsolete manifest')
    for name, content in entries.items():
        scan(name, content, secrets)
    entries = dict(entries)
    entries['SHA256SUMS'] = ''.join(digest(content) + '  ' + name + '\n' for name, content in sorted(entries.items())).encode()
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w') as target:
        for name, content in sorted(entries.items()):
            info = zipfile.ZipInfo(name, STAMP)
            mode = 0o755 if name.endswith(('.sh', '.command', '.py')) else 0o644
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(info, content)
    read_zip(path)
    return path


def previous_artifacts():
    report = json.loads((PREVIOUS / 'deployment/deployment-result.json').read_text())
    require(report.get('status') == 'deployed' and report.get('configurationPreserved') is True and report.get('collectorCaPreserved') is True,
            'Previous deployment receipt is not confirmed')
    previous_runtime = PREVIOUS / 'packages/assessment-dashboard-runtime-1.0-beta-uninstall-review.zip'
    previous_sources = PREVIOUS / 'deployment/dashboard-update-sources.zip'
    require(digest(previous_runtime.read_bytes()) == report['runtimeSha256'], 'Previous runtime differs from deployment receipt')
    require(digest(previous_sources.read_bytes()) == report['sourcesSha256'], 'Previous sources differ from deployment receipt')
    runtime = read_zip(previous_runtime)
    with zipfile.ZipFile(previous_sources) as original:
        sources = {name: original.read(name) for name in original.namelist()}
    baseline = json.loads((OUT / 'baseline/manifest.json').read_text())
    require(all(name.startswith(('server/', 'shared/', 'web/')) for name in baseline), 'Unexpected current baseline entry')
    deployed = {name: content for name, content in {**runtime, **sources}.items() if name.startswith(('server/', 'shared/', 'web/'))}
    require({name: digest(content) for name, content in deployed.items()} == baseline, 'Current baseline differs from actually deployed source/web artifacts')
    for name, expected in baseline.items():
        require(digest((OUT / 'baseline' / name).read_bytes()) == expected, 'Baseline snapshot content differs: ' + name)
    beta = read_zip(ROOT / 'dist/assessment-dashboard-installer-1.0-beta.zip')
    libraries = {name: content for name, content in runtime.items() if name.startswith('lib/')}
    actual_libraries = {name: content for name, content in beta.items() if name.startswith('lib/')}
    require(libraries == actual_libraries, 'Runtime libraries differ from all installed Beta library entries')
    baseline.update({name: digest(content) for name, content in actual_libraries.items()})
    return report, runtime, baseline


def product_sources():
    return {str(path.relative_to(ROOT)): path.read_bytes() for directory in ('server', 'shared')
            for path in sorted((ROOT / directory).glob('*.java'))}


def verified_classes(sources, javac):
    allowed = {Path(name).stem for name in sources}
    require(not any('Test' in stem or 'Fixture' in stem for stem in allowed), 'Test classes in server product sources')
    directory = OUT / 'build/server/assessment'
    classes = {'classes/assessment/' + path.name: path.read_bytes() for path in sorted(directory.glob('*.class'))
               if path.stem.split('$')[0] in allowed}
    require(classes and all(content[:4] == b'\xca\xfe\xba\xbe' and int.from_bytes(content[6:8], 'big') == 61 for content in classes.values()), 'Java 17 product classes required')
    # Compile current source snapshots independently, then compare bytes to the final tested build.
    with tempfile.TemporaryDirectory(prefix='assessment-csv-rebuild-') as temporary:
        stage = Path(temporary)
        for name, content in sources.items():
            path = stage / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        (stage / 'classes').mkdir()
        result = subprocess.run([str(javac), '--release', '17', '-cp', str(ROOT / 'lib/*'), '-d', str(stage / 'classes'),
                                 *[str(stage / name) for name in sorted(sources)]], capture_output=True, text=True)
        require(result.returncode == 0, 'Independent product compilation failed:\n' + result.stderr[-4000:])
        rebuilt = {'classes/assessment/' + path.name: path.read_bytes() for path in (stage / 'classes/assessment').glob('*.class')}
        require(rebuilt == classes, 'Final build classes differ from fresh source compilation; recompile before packaging')
    require(product_sources() == sources, 'Product sources changed during packaging')
    return classes


def deployment_readme():
    return '''# Atualização preparada: downloads versionados e CSV

Este pacote foi preparado localmente e ainda não foi publicado. Destino único: https://167.126.4.37. Agente disponível para download: 1.5.0. Nenhum agente instalado é atualizado ou removido por este procedimento.

No Terminal do Mac, dentro desta pasta extraída, execute:

```sh
python3 publish-dashboard.py
```

Ou abra `Atualizar Dashboard.command`. O publicador utiliza a chave existente `~/OneDrive/id_rsa`, o usuário `opc`, verificação estrita da host key e `sudo -n`; não solicita a senha do dashboard.

O script valida os hashes, verifica a instalação anterior com desinstalação remota publicada em 13/10/2026 e interrompe antes da parada caso fontes, web ou qualquer biblioteca tenham divergido. A baseline inclui todos os arquivos de licenças/proveniência das bibliotecas. Bibliotecas não são substituídas.

Ele confirma a parada pelo estado/PIDs/cgroup do serviço, guarda configuração e código em backup privado `/root/java-assessment-dashboard-update-*`, faz cópia consistente do SQLite, troca apenas classes, web, fontes e o ZIP genérico para download, preserva a CA existente e verifica o serviço, HTTPS, banco e hashes. Falhas após a parada acionam recuperação do código anterior mantendo o banco. O backup permanece disponível. Solicitações de desinstalação existentes fazem a atualização ser recusada.

Os scripts de parada, backup, permissões, CA e recuperação preservam a revisão já validada; a adaptação de embalagem apenas descarta o manifesto interno de fontes após verificá-lo. Não copie configurações ou credenciais para esta pasta.

`last-attempt.json` registra a tentativa. `deployment-result.json` só é gravado após confirmação de publicação; a CA pública pode ser salva para conferir HTTPS externo. O reinício encerra a sessão web: entre novamente depois. Não execute novamente sobre uma instalação já atualizada; a baseline exige a versão anterior exata.

Mudanças: Actions → Exportar CSV oferece Todos os ambientes, Sem ambiente, ambiente específico e Esta máquina quando aplicável; o CSV inclui o inventário completo do escopo. Nomes de ZIP usam a versão real do pacote, por exemplo `assessment-producao-1.5.0.zip`.
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--javac', type=Path, default=JDK / 'bin/javac')
    parser.add_argument('--preliminary', action='store_true', help='Mark build as preliminary until final validation is confirmed')
    args = parser.parse_args()
    builder = load_builder()
    secrets = builder.private_literals()
    packages, deployment = OUT / 'packages', OUT / 'deployment'
    packages.mkdir(parents=True, exist_ok=True)
    deployment.mkdir(parents=True, exist_ok=True)
    report, old_runtime, baseline = previous_artifacts()
    sources = product_sources()
    runtime = verified_classes(sources, args.javac)
    web = {'web/' + path.name: path.read_bytes() for path in sorted((ROOT / 'web').iterdir()) if path.is_file() and path.suffix in builder.WEB_SUFFIXES}
    runtime.update(web)
    runtime.update({name: content for name, content in old_runtime.items() if name.startswith('lib/')})
    collector = PREVIOUS / 'packages' / COLLECTOR_NAME
    require(digest(collector.read_bytes()) == '4792c942c21f4510ac10b6ebb43e572eb978b946e37178b80a7138fcbae333df', 'Approved collector archive changed')
    collector_files = read_zip(collector)
    require(json.loads(collector_files['DEPENDENCIES.json'])['version'] == '1.5.0', 'Collector version must remain 1.5.0')
    runtime['dist/assessment-collector.zip'] = collector.read_bytes()
    runtime['CONTRACT.md'] = (ROOT / 'CONTRACT.md').read_bytes()
    runtime['README.txt'] = b'Prepared dashboard runtime 1.0-beta-csv. Preserve conf/data/CA/identities. Use the supplied manual publisher; do not run the fresh-install installer. Collector remains approved 1.5.0; no agent is installed or removed.\n'
    runtime_path = archive(packages / RUNTIME_NAME, runtime, secrets)
    update_sources = dict(sources)
    update_sources['expected-baseline.json'] = (json.dumps(baseline, indent=2, sort_keys=True) + '\n').encode()
    sources_path = archive(deployment / 'dashboard-update-sources.zip', update_sources, secrets)
    runtime_sha, sources_sha = digest(runtime_path.read_bytes()), digest(sources_path.read_bytes())

    generated = {}
    for name in ('update-dashboard.py', 'publish-dashboard.py', 'Atualizar Dashboard.command', 'test-update-dashboard.py'):
        text = (PREVIOUS / 'deployment' / name).read_text()
        text = text.replace(report['runtimeSha256'], runtime_sha).replace(report['sourcesSha256'], sources_sha)
        text = text.replace('assessment-dashboard-runtime-1.0-beta-uninstall-review.zip', RUNTIME_NAME)
        text = text.replace('Installed files differ from the approved Beta baseline', 'Installed files differ from the previously deployed remote-uninstall baseline')
        if name == 'update-dashboard.py':
            original = "    expected = json.loads(sources.pop('expected-baseline.json'))\n"
            require(text.count(original) == 1, 'Updater manifest adaptation location changed')
            text = text.replace(original, original + "    sources.pop('SHA256SUMS', None)  # Already verified; not a deployed source file.\n")
        content = text.encode()
        scan(name, content, secrets)
        (deployment / name).write_bytes(content)
        (deployment / name).chmod(0o755)
        generated[name] = content
    generated['README.md'] = deployment_readme().encode()
    (deployment / 'README.md').write_bytes(generated['README.md'])

    review = dict(sources)
    review.update(web)
    review.update({name: content for name, content in runtime.items() if name.startswith('lib/')})
    review['dist/assessment-collector.zip'] = collector.read_bytes()
    for directory, suffixes in [('collector', {'.java', '.sh', '.md', '.example'}), ('tests', {'.java', '.cjs', '.py'})]:
        for path in sorted((ROOT / directory).rglob('*')):
            if path.is_file() and path.suffix in suffixes and '__pycache__' not in path.parts:
                review[str(path.relative_to(ROOT))] = path.read_bytes()
    for name in builder.INSTALLER_FILES + ('build.py',):
        review['dashboard-installer/' + name] = (ROOT / 'dashboard-installer' / name).read_bytes()
    for path in sorted((ROOT / 'docs/superpowers').rglob('*versioned-download-csv*.md')):
        review[str(path.relative_to(ROOT))] = path.read_bytes()
    for name in ('CONTRACT.md', 'docs/REMOTE_UNINSTALL_REVIEW.md', 'docs/VERSIONED_DOWNLOAD_CSV.md', 'ops/build-versioned-download-csv.py'):
        path = ROOT / name
        if path.is_file():
            review[name] = path.read_bytes()
    for name in json.loads((OUT / 'baseline/manifest.json').read_text()):
        review['output/versioned-download-csv/baseline/' + name] = (OUT / 'baseline' / name).read_bytes()
    review['output/versioned-download-csv/baseline/manifest.json'] = (OUT / 'baseline/manifest.json').read_bytes()
    review['output/versioned-download-csv/baseline/production-lib-hashes.json'] = (json.dumps({name: value for name, value in baseline.items() if name.startswith('lib/')}, indent=2) + '\n').encode()
    review['SOURCE_RECONSTRUCTION.md'] = b'''# Local reconstruction

Sources, public dependencies, web, tests and the unchanged approved collector are included. With Java 17, compile product code using `javac --release 17 -cp 'lib/*' -d build/server shared/Json.java server/*.java`. Compile tests with product classes and run the named Java tests; browser fixtures need Node, Playwright and Chrome. Fixture tests do not require a live VM.

The full deployment builder intentionally also requires the original prior runtime ZIP, prior update-sources ZIP, prior successful deployment receipt and approved Beta installer ZIP from the original workspace. These prove the installed baseline and are not regenerated or inferred by the source archive. The source archive independently supports recompilation and review; preserve original proof artifacts to recreate the delivery bundle.
'''
    source_review = archive(packages / SOURCE_NAME, review, secrets)
    manual = dict(generated)
    manual[RUNTIME_NAME] = runtime_path.read_bytes()
    manual['dashboard-update-sources.zip'] = sources_path.read_bytes()
    manual_path = archive(packages / MANUAL_NAME, manual, secrets)

    entries = []
    for path in (runtime_path, source_review, manual_path, sources_path):
        files = read_zip(path)
        entries.append({'file': str(path.relative_to(OUT)), 'sha256': digest(path.read_bytes()), 'bytes': path.stat().st_size, 'entries': len(files)})
    manifest = {'status': 'preliminary-local-build' if args.preliminary else 'prepared-local-build', 'featureRelease': '1.0-beta-csv', 'agentVersion': '1.5.0',
                'published': False, 'sourceClassesVerified': True, 'baselineVerifiedAgainstDeploymentReceipt': True,
                'previousDeploymentCompletedAt': report['completedAt'], 'previousRuntimeSha256': report['runtimeSha256'],
                'previousSourcesSha256': report['sourcesSha256'], 'baselineEntries': len(baseline), 'artifacts': entries}
    (packages / 'package-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (packages / 'SHA256SUMS').write_text(''.join(entry['sha256'] + '  ' + Path(entry['file']).name + '\n' for entry in entries if entry['file'].startswith('packages/')))
    deployment_hashes = {name: digest(content) for name, content in generated.items()}
    deployment_hashes['dashboard-update-sources.zip'] = sources_sha
    (deployment / 'SHA256SUMS').write_text(''.join(value + '  ' + name + '\n' for name, value in sorted(deployment_hashes.items())))
    (OUT / 'README.md').write_text('''# Downloads versionados e exportação CSV

Preparado localmente; publicação ainda não executada. Dashboard: pacote `1.0-beta-csv`; coletor aprovado permanece `1.5.0`.

- Downloads genéricos e por ambiente trazem a versão real do coletor no nome e nas instruções.
- Actions → Exportar CSV abre a seleção de Todos os ambientes, Sem ambiente, ambiente específico e, dentro de uma máquina, Esta máquina. A exportação inclui todo o inventário do escopo, sem filtros ocultos da tabela.
- Pacotes preservam configurações, CA, banco, identidades e bibliotecas. Nenhum agente instalado é alterado.

Pacotes e checksums estão em `packages/package-manifest.json`. `assessment-dashboard-update-1.0-beta-csv.zip` contém runtime, fontes de atualização, scripts e instruções para extrair e executar manualmente. A publicação pelo workspace usa:

```sh
python3 output/versioned-download-csv/deployment/publish-dashboard.py
```

A baseline foi comparada com os fontes/runtime da publicação anterior confirmada em 13/10/2026, incluindo todos os arquivos de bibliotecas e licenças do instalador Beta. Os hashes das classes foram comparados com uma compilação independente das fontes atuais. Todos os arquivos dos ZIPs possuem hashes internos; foram verificados os literais de credenciais locais e cabeçalhos de chaves privadas, sem exibi-los. A rotina de publicação inclui backup consistente e recuperação do código anterior.

Para reconstruir os artefatos neste workspace, execute `python3 ops/build-versioned-download-csv.py` depois de gerar as classes finais em `output/versioned-download-csv/build/server`. O ZIP de fontes permite recompilar e revisar o produto; `SOURCE_RECONSTRUCTION.md` lista dependências adicionais para reproduzir o pacote operacional completo. Resultados dos testes ficam em `evidence/`.
''')
    print(json.dumps(manifest, indent=2))
    print('Internal manifests, deployed baseline, unchanged public libraries/collector, final classes and private-literal scan: PASS. No remote operation performed.')


if __name__ == '__main__':
    main()
