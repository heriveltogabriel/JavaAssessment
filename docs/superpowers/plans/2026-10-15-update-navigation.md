# Atualização e navegação — Implementation Plan

**Goal:** Entregar um candidato novo com handoff corrigido, abertura automática do Work request e atalho para todas as máquinas.

**Architecture:** Reutilizar o POST idempotente, o histórico de pedidos e a rota environment/hosts; manter a correção revisada em AgentMigrationBootstrap e os artefatos anteriores imutáveis.

**Tech Stack:** Java 6/17, JavaScript, SQLite, Python e shell; execução nesta sessão sobre a branch existente autorizada.

## 1. Interface

- [x] Em `web/agent-updates.js`, capturar batchId após a resposta e, havendo pedidos e callback, liberar busy, fechar e abrir o pedido antes de atualizar o inventário. Respostas sem batch permanecem no modal.
- [x] Em `web/app.js`, o callback de atualização abre o histórico global; o novo item lateral chama `openAllMachines()`, define ambiente vazio e contexto environment, limpa estado transitório e chama finishNavigation.
- [x] Em `web/index.html`, acrescentar link Java Assessment com href `#environment/hosts?env=all` e data-tab hosts. Ajustar destaque no render e manter navegação por teclado.
- [x] Atualizar `tests/post-update-assessment-browser.cjs` e `tests/work-requests-browser.cjs` para esperar o detalhe automaticamente. Verificar atalho de contexto filtrado e leitor, erro/ACK perdido e nenhum POST extra.

## 2. Agente e evidência

- [x] Conferir regressões do handoff corrigido com `AgentBootstrapHandoffTest` e compilação Java 6 pelo runner de contratos `ops/test-agent-migration.sh`.
- [x] Atualizar `docs/TODO.md` e evidências com confirmação do operador: RHEL 5/Java 6, 2.0.5 → 2.0.11 e assessment recebido, sem inventar horário ou contagem.

## 3. Release

- [x] Sincronizar fontes públicas pertinentes com o mirror operacional; comparar arquivos antes de montar.
- [x] Executar o builder operacional local com `--lab-canary` e reserva de novo par B/T; não reusar 2.0.11. Verificar as opções reais do builder antes da chamada.
- [x] Verificar candidato com `ops/verify-full-agent-candidate.py`, registrar checksums e atualizar notas com alerta de atualização dos agentes e limites conhecidos.
- [x] Copiar entrega para output/dashboard-releases da pasta pública, manter segredos fora do Git e fornecer o comando publish-dashboard.py exato. Publicação real pelo operador.

## Aceite

- POST confirmado abre o pedido correto, incluindo lote global iniciado em tela filtrada; erro mantém revisão; nenhum pedido duplicado.
- Link lateral abre Machines e All environments em desktop/mobile e leitor sem ampliar privilégios.
- Candidato incorpora o código de parada coordenada do helper; testes locais não são apresentados como prova nativa de falha/reboot.

Resultado: candidato local 2.0.13 (bridge 2.0.12) preparado e verificado. Publicação remota não executada. Evidências e limites em docs/RELEASE_2.0.13.md.
