# Dashboard version Implementation Plan

> **For agentic workers:** Use executing-plans inline to implement each task and verify it. The user has authorized the requested version behavior.

**Goal:** Exibir uma versão completa coerente e incrementar automaticamente em cada nova entrega.

**Architecture:** Um gerador de entrega reserva a próxima versão sob trava e gera a constante compilada. A interface recebe a versão do servidor. Cada pacote é imutável, verificável e inclui atualização/rollback da versão instalada.

**Tech Stack:** Java 17, JavaScript, Python 3, SQLite, Playwright.

### 1. Versão única e interface

- [x] Gerar `server/DashboardVersion.java` com `VERSION`, `BUILT_AT` e construtor privado. Trocar constantes de `/health`, export JSON e log em `server/AssessmentServer.java` por `DashboardVersion.VERSION`.
- [x] Substituir `web/version.js` por leitura de `/health` com `cache: 'no-store'`; validar `^\d+\.\d+\.\d+-beta$`, atualizar `interface-version` e `dashboard-version`, armazenar metadados em `window.AssessmentRelease`.
- [x] Mostrar a versão no cabeçalho em `web/index.html`, adicionar CSS de duas linhas que caiba em 390px e mostrar dashboard/data de build em Sobre, em `web/app.js`.
- [x] Criar `tests/DashboardVersionHttpTest.java`: iniciar servidor SQLite no loopback, verificar health e export JSON contra a constante, reiniciar e verificar persistência da versão; exigir `Cache-Control: no-store`.

### 2. Incremento e entrega

- [x] Criar `ops/dashboard_release.py` com `next_version`, reserva serializada e geração de Java; estado em `release/dashboard.json`, saída `output/dashboard-releases/<version>`.
- [x] Criar `tests/dashboard-release-test.py`: `1.0-beta → 1.0.1-beta → 1.0.2-beta`, invalid input, concorrência e proibição de sobrescrever entregas.
- [x] Criar `ops/build-dashboard-release.py`: verificar arquivos históricos aprovados, compilar Java17, executar teste HTTP, empacotar agente1.5.1 intacto e produzir runtime/fontes/manual com checksums e versão no nome.
- [x] Usar atualizador/publicador revisados em `ops/dashboard-release/`. Conservar parada, backup, configuração, CA e rollback. Adicionar `VERSION` aos componentes; aceitar bases exatas conhecidas; exigir health com a versão esperada antes de confirmar publicação; verificar também JS servido.
- [x] Executar os 18 testes existentes de parada contra o atualizador novo e adicionar testes isolados de versão/base/rollback. Nenhum subprocesso real permitido nesses testes.

### 3. Navegador, empacotamento e instruções

- [x] Criar `tests/dashboard-version-browser.cjs` usando `RuntimeUiFixture`: verificar os três locais contra `/health`, Sobre com runtime sem javac, desktop/móvel sem overflow e erros JS.
- [x] Executar Java HTTP/regressões pertinentes e testes de incremento/atualizador, depois o navegador usando classes da entrega.
- [x] Empacotar e verificar manifestos, classes Java17 e agente1.5.1, hashes e fontes. Registrar `validation-results.json` com `published:false`.
- [x] Documentar o comando de publicação desta versão e o comando de preparar a próxima em `docs/DASHBOARD_VERSION.md`; entregar o publicador manual ao usuário.

Projeto ignorado pelo repositório pai: não forçar Git add/commit, não modificar o clone JavaAssessment de outro chat. Não executar SSH ou publicação remota pelo Codex.

## Resultado

Entrega 1.0.1-beta preparada em output/dashboard-releases/1.0.1-beta, sem publicação remota. 7 testes de incremento, 26 testes do atualizador (incluindo backup e rollback de VERSION), 7 testes Java, 3 verificações de navegador e 3 verificações JS passaram. Manifestos, checksums, fontes de produto/ferramentas e agente1.5.1 conferidos. Resultado em validation-results.json da entrega. A próxima preparação reserva1.0.2-beta.
