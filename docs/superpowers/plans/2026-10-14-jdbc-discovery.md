# JDBC Datasource Recovery Implementation Plan

> **For agentic workers:** Delegate collector diagnosis and fix to a scoped subagent; root validates ingestion/dashboard and integrates packaging with executing-plans.

**Goal:** Explicar e corrigir o desaparecimento de JDBC datasource do Tomcat e JBoss.
**Architecture:** Parser de metadados isolado no collector, integrado à descoberta por processo e/ou paths; API e overview devem contar registros enviados sem fabricar dados.
**Tech Stack:** Coletor compilado para Java6 (bytecode 50), servidor Java17/SQLite, fixtures de navegador Node.

### Task 1: Descoberta (agente JDBC)
Files: collector/AssessmentScan.java, collector/ProcessLaunch.java se necessário, collector/ServerDataSources.java e collector/ServerDataSourcesTest.java novos, docs/JDBC_DATASOURCES.md novo. Não editar AgentTransport.java ou builder.
- [x] Comparar código/artefatos antigos e caminho atual; registrar evidência da causa e reproduzir com fixtures Tomcat/JBoss.
- [x] Implementar parser XML limitado com DTD/entidades externas desabilitadas, sem URL/usuário/senha no inventário; reutilizar budgets e excludes existentes.
- [x] Integrar descoberta e associar origem/servidor/processo; deduplicar e distinguir configuração/runtime.
- [x] Testar configurações válidas, namespace/XA aplicável, limites/excludes, XXE, malformados, credenciais-canário e regressões Spring/RuntimeInventory.
- [x] Documentar suporte exato e necessidade de novo agente e coleta; reportar comandos e checks ao root.

### Task 2: Ingestão e overview (root)
Files: tests/JdbcOverviewHttpTest.java novo e testes de dashboard existentes quando necessários.
- [x] Enviar snapshot gerado pela fixture com autenticação; verificar counts/datasourceSummary em global/ambiente/máquina e navegação de cartão JDBC para registros.
- [x] Verificar que atualização não preserva inventário antigo como se fosse atual; evitar alterações de protocolo sem evidência.
- [x] Incluir o parser no build e testes no release builder; conferir classe no collector.jar e compatibilidade de atualização remota por JAR.
- [x] Consolidar causa confirmada localmente, evidências e limitações de acesso ao estado real das VMs no recibo final.

Fechamento: versão 1.5.12 preparada localmente, sem publicação. Evidência final em output/dashboard-releases/1.5.12/evidence/delivery-validation.json. Os arquivos de distribuição permanecem imutáveis; este fechamento do plano foi registrado após conferir seus hashes.
