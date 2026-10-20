# Revisão de exportação — plano de implementação

**Goal:** exportar JSON ou CSV único no ZIP e substituir a entrega 2.1.16 conforme autorização explícita.
**Architecture:** manter filtro e snapshot do InventoryArchive; adicionar escrita de documento único e CSV por recurso; manter endpoint protegido e seletor de formato na UI.
**Tech Stack:** Java 17, SQLite, JavaScript, Python de empacotamento.

- [ ] Alterar testes InventoryArchiveTest/HttpTest para ZIP com uma entrada, formato inválido, CSV legível e exclusão de credenciais em ambos. Testar cabeçalho, aspas, Unicode, fórmulas e listas aninhadas sem perda.
- [ ] Implementar em server/InventoryArchive.java formato validado, metadados no documento JSON e CSV com uma linha por recurso; preservar snapshot e orçamento. Ajustar AssessmentServer para query format.
- [ ] Acrescentar seletor JSON/CSV em web/inventory-export.js e proteção do seletor durante download. Atualizar testes UI e navegador para os dois formatos.
- [ ] Executar ops/test-source.sh e tests/inventory-export-browser.cjs; inspecionar downloads e imagens. Não mudar versão do agente/dashboard.
- [ ] Atualizar docs/INVENTORY_EXPORT.md, README, notas, TODO, manual e README dos pacotes para revisão na mesma versão.
- [ ] Preservar a entrega original localmente; reconstruir apenas dashboard/web/fontes com os mesmos artefatos dos agentes. Incluir baseline original 2.1.16 no atualizador por script e wrapper que pausa executor somente se não há operação pendente.
- [ ] Validar pacotes, instalador, atualização/rollback 2.1.14, fluxo de revisão da 2.1.16 e identidade binária dos agentes. Substituir ZIPs/checksums em install/v2.1.16, commit e push na main, sem deploy remoto.
