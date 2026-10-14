# Fontes da versão 2.0.2

O pacote inclui servidor, interface, coletor, testes locais, instalador, bibliotecas fixadas, licenças, agente genérico e catálogo público de atualização assinado. Configurações privadas, chaves de assinatura, identidades, inventário, logs e ferramentas específicas do laboratório não fazem parte da entrega.

Na raiz extraída, com Python 3 e OpenSSL:

```sh
python3 -B ops/prepare-beta-dependencies.py --check
python3 -B dashboard-installer/build.py --with-source
```

O empacotador verifica assinatura, versão, integridade e arquivos estáveis do agente antes de incluir o catálogo. A cópia do Git usa os arquivos públicos de `release/`; o ZIP de fontes usa o catálogo em `dist/`. Nenhum dos dois precisa dos arquivos de uma publicação anterior ou da chave privada.

O build mantém os bytes executáveis do agente publicado. Para mudar o agente distribuído, prepare uma nova versão e seu catálogo assinado pelo processo de release autorizado. O código de assinatura recusa criar outra chave quando já existe uma chave pública fixada e a privada correspondente está ausente.

Para conferir o instalador em isolamento, com um JDK 17+ local:

```sh
export JAVA_HOME=/caminho/do/jdk17
python3 -B tests/agent-update-installer-test.py
python3 -B tests/dashboard_installer_test.py
BETA_INSTALLER_RUNTIME_TEST=1 python3 -B tests/beta-installer-test.py
```

O último comando compila o servidor, gera certificado, abre HTTPS somente em `127.0.0.1`, cria SQLite e verifica instalação, autenticação e migração em diretórios temporários. Não executa systemctl, SSH nem publicação remota. O ambiente de teste deve permitir loopback.

Fixtures JavaScript incluídas são código sintético de teste, não resultados de máquinas. Para testes de navegador, instale Playwright e Chrome ou configure `PLAYWRIGHT_MODULE` e `BROWSER_CHANNEL`.

`--output /caminho/pacote.zip` e `--source-output /caminho/fontes.zip` permitem destinos isolados. Os artefatos recebem arquivo SHA-256. A preparação de dependências sem `--check` baixa apenas os arquivos fixados do Maven Central e exige os checksums registrados.

A compatibilidade legada e os limites desta entrega estão em `docs/RELEASE_2.0.2.md`. Compilação Java 6 não substitui homologação em JVM e Linux legados reais.
