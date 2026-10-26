# Agent Update Campaigns Implementation Plan

**Goal:** Executar campanhas grandes sem carregar a frota inteira no navegador e sem expirar a fila antes da vez de cada agente.
**Architecture:** Novo serviço AgentUpdateCampaigns integrado ao registry/coordinator e às APIs administrativas. Persistência em batches/requests e snapshots de seleção. Interfaces de revisão e acompanhamento paginadas.
**Tech Stack:** Java 17, SQLite JSON1, JavaScript existente, testes Java/Node/browser.

- [x] Implementar snapshots/páginas/contadores com filtros validados e SQL parametrizado; separar catálogo e elegibilidade reutilizáveis do registry.
- [x] Criar campanha idempotente por snapshot, alvos por SQL, controle de estado e auditoria administrativa. Persistir release/padrões de concorrência.
- [x] Integrar admissões limitadas, grupos, prazos só ao admitir, bloqueio de claims, ponte no slot do pai, pausa automática e cancelamento em blocos. Regressão do protocolo antigo.
- [x] Paginar Solicitações e carregar eventos individuais; controles de campanha e repetir resultados problemáticos com seleção revisável.
- [x] Substituir revisão completa por páginas de 50, filtros, seleção explícita e snapshot de todos do filtro. Confirmação de resumo e assessment desativado por padrão.
- [x] Testes Java com 5000 hosts, reinício e corridas; testes de interface; ops/test-source.sh. Corrigir falhas antes de publicar qualquer build.
- [x] Documentar operação, limites e evidências; preservar versão durante desenvolvimento e alterações locais preexistentes.
