# OL7 uninstall implementation plan

**Goal:** remover corretamente os arquivos produzidos pela instalação, sem aceitar arquivos desconhecidos.
**Architecture:** correção limitada à classificação de propriedade no inventário V3; recuperação antiga pelo executor original após preservar resíduos comprovados.
**Tech Stack:** Java 6/17, POSIX shell, systemd, testes de assinatura com fixtures.

- [x] Diagnóstico remoto somente de leitura e comparação com código gerador dos arquivos.
- [x] Reprodução da recusa em AgentV3UninstallInventoryTest com lock e relatório de permissões.
- [x] Ajuste de rootData, queuedTransactions e validação de chave pública local em AgentV3UninstallInventory.java.
- [x] Regressões de remoção systemd/SysV, prepared-0 e recibo extra alterado recusado.
- [x] Inventário corrigido validado em leitura na própria OL7; desinstalação original executada após preservar resíduos exatos.
- [ ] Verificar e publicar 2.0.47; entregar novo roteiro de download para teste limpo.
