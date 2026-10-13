# Java Assessment

Inventário de instalações Java, aplicações, servidores Java e JDBC, organizado por ambiente e máquina. A versão **1.0 Beta** reúne um dashboard web com SQLite e um coletor para Linux, incluindo ambientes legados com Java 6.

## Componentes

| Componente | Função | Requisitos |
| --- | --- | --- |
| Dashboard | Recebe coletas, armazena o inventário, apresenta filtros e agenda assessments | Enterprise Linux 8+, systemd, JDK 17+ |
| Coletor | Descobre JVMs existentes e coleta evidências com limites configuráveis | Linux, Java compatível já instalado; modo de serviço com sudo/root |
| Banco local | Persistência transacional e consultas paginadas | SQLite embarcado; sem serviço de banco separado |

O agente comunica-se por HTTPS usando uma identidade individual. Cada pacote baixado de um ambiente recebe o endereço do dashboard, a CA e a credencial de registro. Heartbeat e consulta de tarefas são separados da varredura: uma comunicação sem tarefa não inicia um novo assessment.

## Gerar o instalador do dashboard

Com Python 3 e JDK 17+ disponíveis, execute na raiz do repositório:

```sh
python3 -B ops/prepare-beta-dependencies.py --check
python3 -B dashboard-installer/build.py --with-source
```

O instalador será gerado em `dist/assessment-dashboard-installer-1.0-beta.zip`, acompanhado de SHA-256. As bibliotecas fixadas e suas licenças já estão em `lib/`. O ZIP genérico do coletor está versionado como entrada do build; pacotes de agentes configurados para ambientes são privados e não devem ser adicionados ao Git.

## Instalar em uma nova VM

Copie o instalador gerado e seu arquivo `.sha256` para uma VM Enterprise Linux 8+:

```sh
sha256sum -c assessment-dashboard-installer-1.0-beta.zip.sha256
unzip assessment-dashboard-installer-1.0-beta.zip -d dashboard-installer
cd dashboard-installer
./install.sh
```

O instalador configura JDK quando necessário, HTTPS, senha inicial e serviço systemd. Confirme um IP ou DNS acessível pelos navegadores e pelos agentes. Libere TCP 443 na rede da nuvem. A CA gerada é privada; o navegador precisa confiar nela ou a instalação deve usar um certificado já confiável.

Após entrar no dashboard, crie um ambiente e use **Download agent** para obter o pacote e as instruções de instalação.

## Documentação

- [Instalação e configuração](dashboard-installer/GUIA_INSTALACAO.md)
- [Opções do instalador](dashboard-installer/README.md)
- [Instalação do coletor](collector/INSTALL.md)
- [Arquitetura e contrato HTTP](CONTRACT.md)
- [Migração e backup](dashboard-installer/BETA_MIGRATION.md)
- [Mudanças da Beta](dashboard-installer/BETA_RELEASE.md)
- [Testes e resultados com 1.000 máquinas](docs/BETA_VALIDATION.md)

## Desenvolvimento e testes

```sh
export JAVA_HOME=/caminho/do/jdk17
python3 -B tests/beta-installer-test.py
```

Para incluir inicialização HTTPS/SQLite real em diretório temporário:

```sh
BETA_INSTALLER_RUNTIME_TEST=1 python3 -B tests/beta-installer-test.py
```

A suíte principal inclui armazenamento, API, interface e carga. Requer Node.js, Playwright e Chrome disponíveis; use `NODE`, `NODE_PATH` ou `PLAYWRIGHT_MODULE` para instalações fora do caminho padrão:

```sh
/bin/sh ops/test-beta.sh
```

`BETA_LOAD=0` omite o teste de carga. A suíte usa fixtures locais, sem acessar dashboards instalados. Testes históricos individuais podem cobrir contratos anteriores; `ops/test-beta.sh` é a entrada de validação da Beta.

## Origem desta publicação

Esta base foi importada do pacote de fontes validado da versão 1.0 Beta, preservando o código do dashboard e do coletor. Foram adicionados documentação do repositório, regras de exclusão e fixtures sintéticas dos testes. Referências fixas ao laboratório e scripts de teste que operavam VMs reais foram retirados; o endereço público é configurado pelo instalador.

O relatório de carga registra uma execução local de um minuto com 1.000 máquinas simuladas. Ele não representa uma garantia de desempenho para qualquer VM nem um teste prolongado de estabilidade.

A desinstalação remota está em desenvolvimento separado e ainda não integra esta base. Nenhuma publicação neste repositório atualiza automaticamente uma VM.

## Licença

[MIT](LICENSE). As dependências mantêm suas próprias licenças, incluídas em [lib/licenses](lib/licenses), com versões e checksums em [dependencies-lock.json](lib/dependencies-lock.json).
