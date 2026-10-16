# Work Requests Progress Implementation Plan

> **For agentic workers:** Execute task-by-task with executing-plans; use bounded parallel backend/frontend work with the shared API contract in the approved spec.

**Goal:** Dar acompanhamento persistente por lote e máquina às atualizações solicitadas pelo dashboard.

**Architecture:** WorkRequests.java consulta lotes e coletas, AgentUpdateRegistry persiste eventos/snapshots, work-requests.js apresenta histórico e banner. Nenhuma mudança no protocolo do agente.

**Tech Stack:** Java17/SQLite, JavaScript/CSS nativos, Python builder, testes Java HTTP e Playwright.

---

### Task 1: Backend e persistência
Files: server/WorkRequests.java (novo), server/AgentUpdateRegistry.java, server/AssessmentServer.java, server/MachineActions.java, tests/WorkRequestsHttpTest.java (novo).
- [x] Implementar contratos públicos e filtros documentados na spec, usando SQL parametrizado e paginação antes de buscar detalhes.
- [x] Persistir `events:[{at,status,diagnostic}]` apenas em transições reais; manter criação/estado atual do legado com `historyPartial:true`.
- [x] Congelar nomes/ambiente/ator nos novos pedidos; preservar pedidos terminais ao remover máquina. Nenhum token/configuração em resposta.
- [x] Cobrir por teste: reader GET permitido/POST negado, counts corretos, FAILED/ROLLED_BACK/UNCONFIRMED nunca viram sucesso, escopo e paginação, restart, remoção e legado. Compilar com javac --release 17 e executar assessment.WorkRequestsHttpTest contra fixture local.

### Task 2: Interface
Files: web/work-requests.js/css (novos), web/app.js, web/index.html, web/agent-updates.js, tests/work-requests-browser.cjs (novo).
- [x] Integrar `AssessmentWorkRequests` ao contexto/sessão e renderizar Work requests com filtros próprios e consulta à API.
- [x] Oferecer acesso ao detalhe persistido após confirmação, preservando o popup de progresso; rotas reabrem com `request=<id>&requestType=AGENT_UPDATE`.
- [x] Exibir banner e contagens reais, tabela de máquinas e timeline. Cancelar apenas WAITING e somente admin, preservando guardas do servidor.
- [x] Usar polling 5s com header de background, revision/AbortController para impedir respostas de escopo/sessão antigos. Preservar estado ao falhar consulta e apresentar aviso.
- [x] Verificar desktop/mobile, reload de detalhe, fechamento sem perda, escopo, acesso leitor, erros e confirmações incompletas.

### Task 3: Integração e entrega
Files: docs/AGENT_REMOTE_UPDATE.md, ops/build-dashboard-release.py, release gerada em output/dashboard-releases.
- [x] Revisar API/UI, executar testes existentes agent-updates-browser.cjs e AgentUpdateHttpTest, corrigir regressões concretas.
- [x] Executar teste de navegador com backend real e revisar screenshot desktop/mobile. Salvar evidência.
- [x] Usar `python3 ops/build-dashboard-release.py --prepare-only` para reservar/compilar a versão compartilhada 1.5.11 e validar integração.
- [x] Finalizar com `python3 ops/build-dashboard-release.py --package-version 1.5.11`.
- [x] Conferir hashes do manifesto, versão completa e compatibilidade dos assets para agentes instalados. Entregar arquivo/publisher manual, sem SSH/publicação remota.

Aprovação: usuário aprovou a proposta e pediu implementar; não há nova aprovação necessária para edição/testes/empacotamento local. Não criar commit no repositório pai que ignora este projeto.

Validação local: 126 verificações WorkRequestsHttpTest, 132 AgentUpdateHttpTest, 18 verificações do novo navegador e 22 de regressão do navegador passaram. Na 1.5.11, ambos os testes completos de Chrome com servidor/SQLite reais passaram (8 grupos cada). Evidências em output/work-requests e output/agent-update/real-browser. Screenshots desktop/mobile revisadas. Nenhuma operação de produção executada.

Entrega: versão 1.5.11 preparada; hashes dos cinco arquivos e dos SHA256SUMS de packages/deployment conferidos. Runtime contém frontend atual, WorkRequests.class e coletor 1.5.11. Compatibilidade dos arquivos de suporte e da chave pública confirmada para atualização de 1.5.9 e 1.5.10. Recibo final em output/dashboard-releases/1.5.11/evidence/work-requests-validation.json. As marcações finais deste plano foram concluídas após o empacotamento; o arquivo-fonte imutável conserva o estado registrado durante a criação do pacote.
