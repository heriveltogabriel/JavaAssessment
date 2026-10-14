# Java Assessment

Descoberta e inventário do ambiente Java na infraestrutura do cliente. A versão **2.0.3** reúne dashboard, agente Linux e banco SQLite local, com organização por ambiente e máquina.

## Recursos

- Instalações Java, incluindo runtimes sem compilador quando há evidência de uso por aplicação.
- Aplicações, processos, servidores Java, bibliotecas e datasources JDBC por configuração suportada.
- Contas individuais com email e senha, perfis Administrador e Leitura e troca de senha no primeiro acesso.
- Exportação de um CSV único por ambiente, máquina ou todos os ambientes.
- Ações de máquina, revogação e desinstalação controlada do agente.
- Atualização de agentes compatíveis por HTTPS, individualmente ou por ambiente, com assinatura e verificação.
- Histórico de coletas e atualizações em **Work requests**, incluindo progresso e resultado por máquina.

O agente inicia a comunicação HTTPS com o dashboard. Contato e consulta de tarefas são separados da varredura: sem tarefa, não há novo assessment. Os dados permanecem na instalação do cliente.

## Componentes e requisitos

| Componente | Função | Requisitos |
| --- | --- | --- |
| Dashboard | Inventário, ambientes, coletas, usuários e administração | Enterprise Linux 8+ compatível, 64 bits, systemd, JDK 17+ |
| Agente | Descoberta com escopo, exclusões e limites configuráveis | Linux e JVM existente compatível; serviço instalado com sudo/root |
| SQLite | Inventário, configurações e histórico | Embarcado; sem serviço de banco separado |

Uma instalação nova do dashboard configura `Xms=Xmx=1536m` e limite do serviço de 2 GiB. O coletor usa `Xms16m` e `Xmx96m`; memória nativa e subprocessos são adicionais. Dimensionamento e cobertura dependem do ambiente real.

## Gerar o instalador a partir do Git

Na raiz deste repositório, com Python 3 e OpenSSL disponíveis:

```sh
python3 -B ops/prepare-beta-dependencies.py --check
python3 -B dashboard-installer/build.py --with-source
```

Os resultados ficam em `dist/assessment-dashboard-installer-2.0.3.zip` e `dist/assessment-dashboard-source-2.0.3.zip`, com arquivos SHA-256. O build usa as bibliotecas fixadas, o agente genérico e o catálogo público assinado incluídos no repositório. Não exige arquivos da máquina de desenvolvimento nem a chave privada de assinatura.

O pacote do agente versionado em `dist/assessment-collector.zip` é genérico e não contém identidade cadastrada. O catálogo em `release/agent-updates/2.0.3` é verificado contra a assinatura, a chave pública e os bytes desse pacote. Alterações nos arquivos executáveis exigem uma nova entrega assinada; o build não assina nem substitui a confiança instalada.

## Instalar no cliente

O [diretório install](install/README.md) reúne o manual ilustrado, o guia de instalação em PDF e o instalador pronto da versão 2.0.3, com seu SHA-256.

Na VM destinada ao dashboard, após transferir o instalador e seu SHA-256:

```sh
sha256sum -c assessment-dashboard-installer-2.0.3.zip.sha256
unzip assessment-dashboard-installer-2.0.3.zip -d dashboard-installer-2.0.3
cd dashboard-installer-2.0.3
./install.sh \
  --public-url https://dashboard.exemplo.com \
  --admin-name "Administrador" \
  --admin-email admin@exemplo.com
```

Substitua o endereço e a identidade pelos valores do cliente. O instalador fornece uma senha temporária e exige sua troca no primeiro acesso. A equipe deve configurar a confiança no certificado HTTPS e permitir acesso à porta 443. O endereço pode ser privado.

Crie os ambientes e baixe o agente pelo dashboard do cliente. Na máquina monitorada, extraia o ZIP em uma pasta nova e execute:

```sh
sudo /bin/sh ./install.sh --system-root
```

O destino operacional é `/opt/java-assessment-collector`, independentemente da pasta de extração ou do nome do usuário. A passagem de agentes antigos para 2.0.3 exige o instalador completo uma vez, pois scripts e executor mudaram. Depois, atualizações compatíveis podem ser solicitadas pelo painel.

## Desenvolvimento e validação local

Com JDK 17+ e Node.js no ambiente:

```sh
export JAVA_HOME=/caminho/do/jdk17
/bin/sh ops/test-source.sh
python3 -B tests/agent-update-installer-test.py
python3 -B tests/dashboard_installer_test.py
BETA_INSTALLER_RUNTIME_TEST=1 python3 -B tests/beta-installer-test.py
```

As verificações HTTP/HTTPS usam loopback e diretórios temporários. Não atualizam VMs. `ops/test-source.sh` não executa os testes pesados de carga ou de diretórios.

Para compilar o agente com alvo Java 6, configure `ASSESSMENT_ECJ_JAR` com um Eclipse Compiler compatível, como ECJ 3.33.0. Os testes executados em JDK 17 não comprovam execução em Java 6 ou Linux de 32 bits.

Os testes de navegador usam Playwright e Chrome. Configure `NODE`, `NODE_PATH`, `PLAYWRIGHT_MODULE` ou `BROWSER_CHANNEL` conforme o ambiente. `BETA_LOAD=0 /bin/sh ops/test-beta.sh` executa a suíte histórica de interface sem o teste de carga.

## Documentação

- [Visão funcional, operação e limites](docs/SOLUTION.md)
- [Mudanças e validação da 2.0.3](docs/RELEASE_2.0.3.md)
- [Instalação e configuração](dashboard-installer/GUIA_INSTALACAO.md)
- [Opções do instalador](dashboard-installer/README.md)
- [Instalação do coletor](collector/INSTALL.md)
- [Arquitetura e contrato HTTP](CONTRACT.md)
- [Reconstrução do pacote de fontes](dashboard-installer/README-SOURCE.md)
- [Migração e backup](dashboard-installer/BETA_MIGRATION.md)

Os nomes de alguns scripts e documentos mantêm `beta` por compatibilidade com a estrutura anterior. A versão atual do produto é 2.0.3. Relatórios históricos descrevem somente o cenário e a versão em que foram produzidos.

Configurações privadas, chaves, inventário, logs, pacotes de ambientes e ferramentas específicas do laboratório ficam fora do Git. Publicar código neste repositório não instala nem atualiza servidores ou agentes.

## Licença

[MIT](LICENSE). As dependências mantêm suas próprias licenças em [lib/licenses](lib/licenses), com versões e checksums em [dependencies-lock.json](lib/dependencies-lock.json).
