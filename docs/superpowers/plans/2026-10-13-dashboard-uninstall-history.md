# Dashboard uninstall history Implementation Plan

**Goal:** Permitir a publicação com histórico de desinstalações encerradas e conservar o bloqueio de pedidos ocupados.

**Architecture:** Consultar estados em uma transação SQLite somente leitura em database(); deixar as verificações de parada, backup e rollback existentes. Gerar uma nova versão com o empacotador sequencial.

**Tech Stack:** Python3, SQLite, Java17, JavaScript/Playwright.

- [x] Confirmar os estados ocupados em server/UninstallRegistry.java e registrar a regra no design.
- [x] Alterar ops/dashboard-release/update-dashboard.py para aceitar somente histórico terminal, impedir estados ocupados/desconhecidos e fechar a conexão em finally; remover a segunda exigência de tabela vazia.
- [x] Acrescentar testes SQLite reais a ops/dashboard-release/test-update-dashboard.py e usar quatro registros encerrados na fixture de publicação/rollback.
- [x] Executar testes de incremento, atualização e Java/HTTP local; preparar1.0.2-beta com ops/build-dashboard-release.py --prepare-only.
- [x] Verificar a versão no navegador local e gerar pacotes com --package-version1.0.2-beta.
- [x] Conferir checksums, estado/versão e teste do atualizador empacotado; registrar validation-results.json e entregar o novo comando manual.

Não sobrescrever releases anteriores, apagar histórico, cancelar pedidos ou acessar as VMs.
