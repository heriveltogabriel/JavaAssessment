# Plano — Profile/contexto

**Objetivo:** exibir configurationContext na tabela e detalhes, conforme escopo aprovado.
**Arquitetura:** formatador único em web/datasources.js, consumido pela coluna e web/app.js. Nenhuma alteração de backend/coletor.
**Tecnologias:** JavaScript, testes Node existentes.

- [x] Adicionar contextLabel(row) em web/datasources.js: string aparada, remover prefixo domain profile, traduzir standalone/JBoss deploy; fallback Não identificado. Exportar na API e criar coluna configurationContext após name, escapando HTML.
- [x] Adicionar rótulo Profile/contexto no mapa friendly de web/app.js e renderizar com o mesmo formatador; incluir o campo ausente nos detalhes de datasources para mostrar fallback.
- [x] Adaptar tests/datasources-ui.test.cjs ao novo conjunto de colunas e verificar dois ExampleDS em full/full-ha, ausência, standalone, contexto genérico e HTML escapado.
- [x] Rodar node --check web/app.js, node --check web/datasources.js e testes datasources-ui, jdbc-driver-ui, jdbc-endpoint-ui, beta-ui. Conferir diff e preservar versão. Documentar em docs/TODO.md.

Execução nesta sessão; autorização já dada na conversa. Não gerar pacote nem publicar.

## Resultado

Implementação concluída sem alterar versão. Sintaxe e os 11 testes JavaScript usados por ops/test-source.sh passaram, incluindo datasources-ui, JDBC, locale, dashboard e seleção de versões. Novos casos diferenciam ExampleDS full/full-ha, validam ausência/contextos e escape HTML.

O teste legado tests/beta-ui.test.cjs, fora da suíte atual, falhou esperando `<strong>1,000</strong>`; a mesma falha foi reproduzida em arquivos intactos do HEAD 1f953e4 (tmp/profile-context-baseline/result.log). Não foi alterado neste escopo. A suíte completa Java/build não foi executada porque não houve mudança no coletor ou backend. Sem release, push ou deploy.

## Publicação autorizada posteriormente

Em 21/10/2026, o usuário autorizou finalizar, gerar versão e enviar ao Git. Entrega 2.1.20, ponte 2.1.19.

- [x] Suíte ops/test-source.sh passou antes da reserva.
- [x] Navegador com aplicação real e registros sintéticos: tabela, detalhes, full/full-ha/ausência e mobile. O seletor do teste temporário foi corrigido para #detail-drawer antes da execução aprovada.
- [x] Build e pacotes finais. A primeira tentativa de build foi bloqueada pelo sandbox ao abrir socket; preservar evidência e retomar a mesma reserva não publicada com permissão de loopback, sem ignorar testes.
- [x] Verificar assinaturas e atualizar/restaurar dashboard a partir da 2.1.18; conferir pacotes, links e hashes.
- [ ] Publicar instaladores, documentação e código; integrar main e confirmar no GitHub. Sem deploy remoto.

Pacotes, testes finais e limites registrados em [validação](../../validation/console-release-2.1.20.md). A publicação segue após o commit e conferência do remoto.
