# JDBC driver resolution implementation plan

> Execute inline com executing-plans; implementação e publicação já autorizadas pelo usuário.

**Goal:** Identificar versões de drivers associados aos datasources WebLogic e publicar a melhoria com evidências.
**Architecture:** Resolver passivo separado por coleta, integração após leitura dos descritores do processo; campos aditivos no inventário e nos relatórios.
**Tech Stack:** Java 6 no coletor, Java 17/SQLite no dashboard, JavaScript e Python no empacotamento.

- [x] Criar `collector/JdbcDriverResolverTest.java`: fixtures ZIP com classe exata e manifestos; testar candidato único, dois candidatos, JAR renomeado, Class-Path indireto, exclusões, dois processos e orçamento. Executar com JVM 17 e alvo de compilação Java 6.
- [x] Criar `collector/JdbcDriverResolver.java`: cache limitado e leitura via orçamento de ArchiveMetadata; percorrer fila local com profundidade 8; armazenar classe/versão/origem de cada candidato; resolver somente quando há um candidato e inspeção completa. Integrar em `AssessmentScan.discoverProcesses` com `jdbcDrivers.discover(context, directory)` após descoberta vinculada.
- [x] Acrescentar `driverArtifact`, `driverResolution`, `driverCandidates` e evidência aos detalhes em `web/app.js`, seleção e resumo em `server/Reports.java` e CSV em `server/CsvExport.java`. Testar fixtures nos relatórios e exportação.
- [x] Registrar o teste no `ops/test-source.sh` e no builder. Executar `/bin/sh ops/test-source.sh` e testes HTTP/CSV pertinentes; corrigir falhas sem mudar versão.
- [x] Reservar entrega pelo `ops/build-dashboard-release.py`, conferir pacote final assinado e construir pacote web via `ops/build-dashboard-web-update.py --release VERSAO_RESERVADA`. Validar origem 2.1.0 e preservar os arquivos publicados.
- [x] Publicar no laboratório pela fila do executor independente; acompanhar estado, HTTPS, banco e versão. Atualizar README, install, notas e evidências. Não criar pedidos de atualização de agentes automaticamente.
