# Revisão de exportação — plano de implementação

**Goal:** exportar JSON ou CSV único no ZIP e substituir a entrega 2.1.16 conforme autorização explícita.
**Architecture:** manter filtro e snapshot do InventoryArchive; adicionar escrita de documento único e CSV por recurso; manter endpoint protegido e seletor de formato na UI.
**Tech Stack:** Java 17, SQLite, JavaScript, Python de empacotamento.

- [x] Alterar testes InventoryArchiveTest/HttpTest para ZIP com uma entrada, formato inválido, CSV legível e exclusão de credenciais em ambos. Testar cabeçalho, aspas, Unicode, fórmulas e listas aninhadas sem perda.
- [x] Implementar em server/InventoryArchive.java formato validado, metadados no documento JSON e CSV com uma linha por recurso; preservar snapshot e orçamento. Ajustar AssessmentServer para query format.
- [x] Acrescentar seletor JSON/CSV em web/inventory-export.js e proteção do seletor durante download. Atualizar testes UI e navegador para os dois formatos.
- [x] Executar ops/test-source.sh e tests/inventory-export-browser.cjs; inspecionar downloads e imagens. Não mudar versão do agente/dashboard.
- [x] Atualizar docs/INVENTORY_EXPORT.md, README, notas, TODO, manual e README dos pacotes para revisão na mesma versão.
- [x] Preservar a entrega original localmente; reconstruir apenas dashboard/web/fontes com os mesmos artefatos dos agentes. O operador restaurou a console para 2.1.14; usar fluxo web normal sem wrapper especial.
- [x] Validar pacotes, instalador, atualização/rollback 2.1.14, identidade binária dos agentes. Substituir ZIPs/checksums em install/v2.1.16, commit e push na main, sem deploy remoto.
