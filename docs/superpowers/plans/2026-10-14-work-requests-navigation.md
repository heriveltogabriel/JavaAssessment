# Work Requests Navigation Implementation Plan

> **For agentic workers:** Use bounded parallel subagent tasks for UI, result evidence and JDBC investigation; review each result before packaging. Execute locally with executing-plans. Shared file ownership is explicit.

**Goal:** Implementar a navegação e filtros aprovados e tornar o motivo de resultados parciais claro e persistente.
**Architecture:** app.js separa a página jobs dos contextos de inventário; work-requests.js mantém consulta e filtros próprios; AssessmentServer persiste avisos no job e WorkRequests publica DTOs mínimos opcionais.
**Tech Stack:** Java17/SQLite, JavaScript/CSS, Playwright, Python release builder.

### Task 1: UI independente (agente frontend)
Files: web/app.js, web/index.html, web/work-requests.js/css, web/styles.css se necessário; tests/work-requests-browser.cjs e tests/work-requests-real-browser.cjs.
- [x] Criar testes de navegação independente, menu global e atalhos com escopo; remover jobs apenas da renderização de abas, preservando isView e rotas antigas.
- [x] Renderizar cabeçalhos próprios em jobs e separar Administração no menu; validar reader sem separador órfão.
- [x] Separar busca de filtros avançados, usar painel hidden e botão aria-expanded/aria-controls; manter resumo e Limpar visíveis, preservar critérios/rascunho/expansão durante polling.
- [x] Tornar labels PARTIAL dependentes do tipo e adicionar Ver motivo e resumo por contagem. Consumir observations/observationsAvailable/observationSource e fallback legado sem inventar.
- [x] Rodar navegador simulado e real com fixtures atualizadas, verificar desktop/mobile e screenshots; conservar teste original de atualização.

### Task 2: Motivos históricos (agente backend)
Files: server/AssessmentServer.java, server/WorkRequests.java, tests/WorkRequestsHttpTest.java, tests/WorkRequestsUiFixture.java.
- [x] Reproduzir perda de avisos por coleta posterior em teste HTTP/SQLite.
- [x] Persistir cobertura/avisos próprios no job recebido, limitar saída, expor observations e origem nos detalhes; listar resumo quando houver.
- [x] Para legado, usar cobertura atual apenas se o horário corresponde ao job; caso contrário explicar indisponibilidade.
- [x] Rodar WorkRequestsHttpTest e AgentUpdateHttpTest em localhost, validar reader e nenhum token/configuração nas respostas.

### Task 3: Integração/release (root)
Files: docs/WORK_REQUESTS.md, ops/build-dashboard-release.py, docs/specs/plans e release gerada.
- [x] Revisar alterações e atualizar documentação; coordenar campos de API entre agentes.
- [x] Rodar regressões relevantes e integração real na versão reservada; conferir visualmente screenshots.
- [x] Reservar próxima versão compartilhada com python3 ops/build-dashboard-release.py --prepare-only, finalizar --package-version após aprovação técnica dos testes.
- [x] Conferir hashes, versões e compatibilidade com release anterior; gerar recibo e comando manual de publicação. Não criar commit no Git pai que ignora este projeto.

O usuário autorizou execução autônoma ao informar que vai dormir. Skills auxiliares subagent-driven-development/finishing-a-development-branch não estão instaladas; aplicar o fluxo disponível de execução e revisão sem interromper trabalho autorizado.

Fechamento: versão 1.5.12 preparada localmente, sem publicação. Evidência final em output/dashboard-releases/1.5.12/evidence/delivery-validation.json. Os arquivos de distribuição permanecem imutáveis; este fechamento do plano foi registrado após conferir seus hashes.
