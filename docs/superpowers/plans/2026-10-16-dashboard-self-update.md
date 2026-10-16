# Dashboard self-update implementation plan

> **For agentic workers:** Execute inline using executing-plans; no delegation needed.

**Goal:** Administradores enviam pacote assinado, preparam, confirmam reinício e acompanham recuperação sem SSH.

**Architecture:** Java encaminha solicitações fechadas por fila; Python 3.6/systemd independente verifica assinatura e aplica componentes com journal durável. Interface reaproveita Administração; bootstrap instala o serviço apenas na nova versão.

**Tech Stack:** Java 17, Python 3.6+, systemd, OpenSSL, SQLite, JavaScript existente.

- [ ] Preservar commit e pacotes 2.0.57 em `backups/v2.0.57-before-dashboard-updater`; backup privado online consistente na VM. Verificar hashes e `git bundle verify`.
- [ ] Criar `maintenance/dashboard_update.py`: validação estrita de pacote, fila local, journal/estado sanitizado, backup, aplicação, rollback e recuperação. Testar com `python3 tests/dashboard-self-update-test.py` usando raiz temporária/adaptador de serviço, certificados e assinaturas reais.
- [ ] Criar `ops/build-dashboard-web-update.py`: produzir manifesto assinado do runtime/fontes já validados, sem executar scripts do upload. Testar assinatura alterada, arquivo fora de raiz, links, tamanho/duplicatas, versão/origem e checksum.
- [ ] Criar `server/DashboardMaintenance.java`; integrar API em `AssessmentServer.java` com autorização ADMIN, CSRF, streaming até 256 MiB, exclusão mútua e manutenção. `DashboardMaintenanceHttpTest.java` comprova restrições reais HTTP.
- [ ] Criar `web/dashboard-update.js` e CSS; integrar aba em `web/access.js` e assets em `web/index.html`. Testar preparação, cancelamento, confirmação/reinício, log, histórico, recuperação/relogin e resposta perdida.
- [ ] Instalar worker com caminhos/permissões fixos e unidade independente por `maintenance/install.py`; incluir nos builds/instalador/atualizador local, preservando serviços existentes no rollback.
- [ ] Executar regressões de fonte, instalador, atualizador e testes nativos isolados. Documentar evidências e limites; manter versão durante essas verificações.
- [ ] Depois dos testes, reservar 2.1.0 com ponte interna inferior, gerar pacotes finais imutáveis e conferir assinaturas/integridade. Não publicar no cliente ou substituir 2.0.57. Entregar comandos e arquivos.
