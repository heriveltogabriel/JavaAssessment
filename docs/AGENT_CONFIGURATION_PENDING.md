# Configurações de aplicações e JDBC — registro de desenvolvimento

Implementação iniciada sobre **2.1.8** e incorporada à entrega [2.1.10](RELEASE_2.1.10.md). Este documento preserva os testes da etapa Spring; o fechamento conjunto está nas [evidências da entrega](validation/console-release-2.1.10.md).
Não houve incremento de versão, geração de instalador, assinatura, push ou deploy.
O fechamento aguarda as demais implementações solicitadas pelo usuário.

## Alterações

1. **Launcher:** opções entre `-jar` e o arquivo deixam de ser interpretadas como nome do JAR. Perfdata pode confirmar o artefato. Opções JVM após o JAR geram uma observação sem seus valores.
2. **Configuração externa:** `appConfigPaths`, com padrão `/etc/config`, está disponível nas configurações gerais, de ambiente e de máquina. Listas vazias desativam a descoberta adicional. O nome do arquivo/aplicação e locais explícitos orientam o vínculo; configuração genérica ambígua permanece na máquina, sem aplicação atribuída.
3. **Spring:** prefixos de datasource automáticos com binding relaxado, pistas de JPA/dialeto, imports locais limitados e perfis. A URL prevalece sobre um dialeto divergente. Os nomes dos prefixos personalizados distinguem os datasources.
4. **JNDI:** correlação com datasources de servidores observados na mesma máquina, independentemente da ordem dos processos. Somente um candidato permite herdar destino e driver; ausência, ambiguidade e conflito ficam explícitos.
5. **Outros formatos:** JPA, Hibernate, beans Spring XML, declarações JNDI e busca textual restrita às aplicações. Arquivos JAR/WAR e módulos de EAR são inspecionados dentro dos limites. Evidências de origem aparecem nos detalhes do inventário.

**Mudança de padrão:** `/opt` sai das inclusões padrão novas. Configurações salvas não são migradas. Na consolidação 2.1.10, `/opt` também entra nas exclusões novas: os processos continuam visíveis, mas seus arquivos não são lidos enquanto a exclusão permanecer.

O inventário conserva somente os campos permitidos do destino JDBC. Não são emitidos comandos brutos, URLs JDBC completas, usuários, senhas ou tokens. A descoberta é passiva: não carrega classes nem conecta ao banco. Versão do driver continua diferente de versão real do banco.

## Validação local

As cinco etapas foram acompanhadas de testes e da suíte `ops/test-source.sh`.
O teste `AgentConfigAcceptanceTest` reproduz o comando do cliente com `FORMACAO_PERIODOS_SE.jar`, configuração externa Oracle e prefixo `cache-consultas-jndi` com dialeto PostgreSQL.

Execução da regressão no Mac:

```sh
JAVA_HOME="$PWD/tools/jdk17/jdk-17.0.20.1+1/Contents/Home" \
NODE="/Users/heriveltosantos/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node" \
sh ops/test-source.sh
```

Testes novos: `ApplicationConfigFilesTest`, `SpringExtendedTest`, `JndiLinksTest`, `ApplicationJdbcTest` e `AgentConfigAcceptanceTest`. Casos existentes ampliados: `ProcessLaunchTest`, `SpringConfigurationTest` e `ArchiveDefaultsHttpTest`.

Compilação do coletor com ECJ `-source 1.6 -target 1.6`: classes com major version 50. Isso valida o alvo de compilação; não substitui execução em uma JVM Java 6.

Saída final (20/10/2026, comandos concluídos com código 0):

```text
APPLICATION_CONFIG_FILES=PASS unique ambiguous explicit named defaults privacy symlink
AGENT_CONFIG_ACCEPTANCE=PASS real-command named-config Oracle JNDI dialect observation privacy
APPLICATION_JDBC=PASS persistence Hibernate Spring-XML generic dedup scopes archives limits privacy XXE
JNDI_LINKS=PASS JBoss-order WebLogic prefixes missing ambiguous drivers
SPRING_EXTENDED=PASS auto-prefix relaxed JPA imports profiles privacy cycles depth count size symlink
ARCHIVE_DEFAULTS_HTTP=PASS fresh migration inheritance exceptions restart reset no-extra-jobs
Source regression checks passed; heavy stress and external hosts were not exercised.
ECJ_JAVA6=PASS source=1.6 target=1.6 class-major=50 classes=91
```

Log completo local: `tmp/agent-config-final.log` (ignorado pelo Git).

## Limites e verificações restantes

- Testes executados no macOS/JDK 17, com fixtures locais. Linux, Java 6 real e a aplicação do cliente ainda precisam de validação.
- Não reproduz o contexto Spring efetivo, configuração programática, SpEL, imports remotos ou YAML complexo. Grupos/includes e documentos condicionais permanecem observações.
- Imports: profundidade 3, até 20 arquivos e 1 MiB por arquivo externo. Recursos Spring empacotados mantêm limites menores: 64 KiB por entrada, cache de 32 entradas/256 KiB por arquivo Java.
- Busca adicional de configurações: até 512 caminhos, 128 textos e três níveis. Busca JDBC genérica: textos até 512 KiB; XML até 256 KiB; módulos de EAR até 4 MiB descompactados. Todos respeitam exclusões, links e orçamento global.
- Configurações sem vínculo inequívoco não provam qual aplicação usa o datasource. JNDI de outro host não é resolvido.
- Os scripts pessoais `gerar-2.1.8.sh`/`.command` não foram encontrados neste checkout; a generalização permanece no TODO. Nenhum script substituto de publicação foi executado.
- A entrega consolidada exige atualizar também os agentes e solicitar nova coleta; atualizar só o dashboard não recalcula inventários antigos.

## Arquivos alterados

- `README.md`
- `collector/AgentConfigAcceptanceTest.java`
- `collector/ApplicationConfigFiles.java`
- `collector/ApplicationConfigFilesTest.java`
- `collector/ApplicationJdbc.java`
- `collector/ApplicationJdbcTest.java`
- `collector/AssessmentScan.java`
- `collector/JdbcEndpoint.java`
- `collector/JndiLinks.java`
- `collector/JndiLinksTest.java`
- `collector/ProcessLaunch.java`
- `collector/ProcessLaunchTest.java`
- `collector/README.md`
- `collector/SpringConfiguration.java`
- `collector/SpringConfigurationTest.java`
- `collector/SpringExtendedTest.java`
- `docs/AGENT_CONFIGURATION_PENDING.md`
- `docs/SPRING_DISCOVERY.md`
- `docs/TODO.md`
- `docs/superpowers/plans/2026-10-20-agent-configuration.md`
- `ops/build-dashboard-release.py`
- `ops/test-source.sh`
- `server/AssessmentServer.java`
- `tests/ArchiveDefaultsHttpTest.java`
- `web/app.js`
- `web/environments.js`
