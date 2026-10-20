# JDBC Long Classpath Implementation Plan

> **For agentic workers:** Use executing-plans to implement sequentially in the current branch.

**Goal:** Mostrar evidência JDBC disponível e remover truncamentos artificiais em classpaths longos.

**Architecture:** Limites explícitos e flags por fonte no parser; resolução mantém incertezas. Helpers de apresentação usam candidatos somente quando únicos e concordantes por PID, sem alterar campos persistidos.

**Tech Stack:** Java 6 source/target, Java 17 nos testes, JavaScript/Node.

- [x] Ajustar `collector/ProcessLaunch.java`: `MAX_PATH_ENTRIES=1024`, `PROCESS_TEXT_LIMIT=256*1024`, `paths()` retorna boolean de completude e detecta conteúdo inválido/excedente; rastrear limites por lista. Preservar classpath explícito completo apenas se concordante com prefixo do perfdata parcial.
- [x] Ajustar `collector/HsPerfData.java`: registrar `assessment.truncated.<campo>` junto ao indicador agregado existente; evitar apagar informação completa com fragmento. Em `AssessmentScan.java`, usar o novo limite de cmdline. Em `JdbcDriverResolver.java`, usar o novo limite de environ e MAX_PATHS=4096, propagar completude/razão real em vez de `cp.size()>=64`.
- [x] Acrescentar casos em `ProcessLaunchTest`, `HsPerfDataTest`, `WebLogicPrecedenceTest`: limites 64/1024/1025, prefixo e divergência, truncamento vmArgs, resolução de driver no fim de classpath longo e leitura cmdline maior que 64 KiB.
- [x] Em `web/app.js`, criar helper de candidato único coerente por PID; células de JAR/versão mostram Provável e alternativas quando conflitantes; testar imutabilidade e escape em `tests/jdbc-driver-ui.test.cjs`.
- [x] Executar `ops/test-source.sh` com JAVA_HOME JDK17 e NODE configurados; compilar fontes do coletor com ECJ `-source 1.6 -target 1.6` e conferir class major 50. Interromper em falhas para inspecionar, sem publicar.
- [x] Atualizar documentação JDBC/TODO com limites, tratamento de candidato e evidências; revisar diff, preservar pacotes publicados; incrementar somente após validação, conforme autorização posterior.

## Ampliação autorizada pelo usuário

- [x] Gerar release 2.1.14 com ponte 2.1.13 após regressão aprovada; pacotes web-update, por script e instalador em install/v2.1.14 com .sha256.
- [x] Validar pacote final contra a origem 2.1.12: manager original, verificador original do instalador e atualização/rollback Java/TLS/SQLite. Documentar evidências, atualizar README e entregar o ZIP para upload pelo cliente.
