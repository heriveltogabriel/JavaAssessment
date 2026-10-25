# Cadastro em escala e release — plano de implementação

**Objetivo:** concluir a branch remover-limite-maquinas e publicar uma nova versão testável pela console instalada em 2.1.20.
**Arquitetura:** manter SQLite, credenciais e protocolos existentes. Limpar códigos expirados/revogados ao emitir novos códigos; preservar esgotados ainda válidos para repetição de cadastro. Consultar códigos em páginas de 100 por ambiente, apenas para administradores, sem expor segredos. Manter o resumo legado limitado.
**Tecnologias:** Java 17, SQLite, JavaScript e ferramentas Python de release.

Escopo aprovado na conversa: corrigir formulários, retenção e acesso aos códigos; validar e publicar nova versão. Execução nesta sessão, sem deploy remoto. Alterações locais anteriores permanecem fora dos commits.

- [x] Testes: estender tests/EnrollmentCapacityTest.java para atingir 10.000 códigos, preservar retry esgotado, remover expirados, percorrer páginas além do registro 500, filtrar ambiente e redigir segredos; validar limites de registrationPerMinute.
- [x] Implementação: server/EnrollmentRegistry.java limpa registros inelegíveis antes da contagem e fornece consulta paginada; server/AssessmentServer.java expõe GET /api/enrollments administrativo com page limitado e environmentId validado.
- [x] Interface: web/enrollment.js e web/environments.js aceitam 100.000 usos; lista com Anterior/Próxima e resposta assíncrona protegida contra fechamento/troca de ambiente. Revogação usa o registro da página carregada.
- [x] Regressão: testes HTTP de autorização e paginação, teste JavaScript da lista, ops/test-source.sh completo. Não reservar versão antes da aprovação.
- [x] Release: build-dashboard-release.py --lab-canary, web-update e dashboard-installer/build.py. Verificar reserva prevista 2.1.21/2.1.22 contra o contador atual; não sobrescrever pacotes.
- [x] Validar pacote final com atualização/restauração 2.1.20, assinatura, baseline, identidade, SQLite, TLS, CRC e SHA-256; precheck do instalador.
- [x] Atualizar README.md, install/README.md, README dos pacotes, notas e evidências. Informar agentes compatíveis 2.1.20 e atualização separada.
- [ ] Commit e push na branch remover-limite-maquinas. Não fazer merge no main antes do teste solicitado pelo operador.
