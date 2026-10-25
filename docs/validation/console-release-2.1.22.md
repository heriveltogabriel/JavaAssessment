# Validação da release 2.1.22

Data: 25/10/2026. Base: branch remover-limite-maquinas (79fdfdc e a02d9f4) sobre main 3d1b78c / 2.1.20. Ponte interna 2.1.21, final 2.1.22. Mudanças funcionais somente no dashboard; identificação do agente unificada pelo build.

## Provas concluídas

- `ops/test-source.sh` aprovado antes de reservar a versão. Java, SQLite, HTTP, coletores e suítes JavaScript: `tmp/limits-source-tests.log`.
- `EnrollmentCapacityTest`: 10.020 verificações, teto exato de 10.000 códigos ativos, abertura de vaga após esgotamento, retry de identidade no limite, limpeza abaixo do teto, todos os 5.002 registros de um ambiente acessíveis em páginas, ausência de credenciais e 2.000 novos cadastros. Executado também com as classes finais: `tmp/limits-final-capacity.log`. Isso não é um benchmark de operação com milhares de agentes ativos.
- `UserAccessHttpTest`: GET administrativo paginado, parâmetros inválidos, ambiente inexistente, usuário sem sessão e leitor bloqueados. A matriz de permissões completa passou.
- `enrollment-capacity-ui.test.cjs`: máximos dos formulários, paginação, revogação além de 500, respostas antigas, troca de ambiente, erro com tentativa de consulta e fechamento.
- `enrollment-capacity-browser.cjs`: Chrome desktop/mobile com API sintética; sete páginas, revogação do código 601 e ausência de erros JavaScript. Log `tmp/enrollment-capacity-browser.log`; captura `tmp/enrollment-capacity-browser/mobile.png`.
- `build-dashboard-release.py --lab-canary` concluído na primeira execução: `tmp/build-2.1.22.log`; evidências detalhadas em `output/dashboard-releases/2.1.22/evidence/`. Web-update e instalador gerados com scripts oficiais.
- Verificadores originais publicados na 2.1.20 aceitaram assinatura, baseline e conteúdo da 2.1.22; chave de confiança, bootstrap imutável, bytecode Java 6 e 32 rotas anteriores preservados: `tmp/original-220-verifiers-222.log`.
- Atualização e restauração **2.1.20 → 2.1.22 → 2.1.20** aprovadas com classes reais publicadas, TLS/SNI, SQLite e identidade da instalação: `tmp/dashboard-final-220-to-222.log`.
- Três ZIPs verificados por CRC, SHA-256 externo e manifesto interno quando presente. Web-update inclui 2.1.20 nas origens e os fontes web/servidor correspondem à implementação validada: `tmp/packages-2.1.22.json`.
- Instalador final extraído: `install.py --check --no-package-install` aprovado com JDK 17 e URL de fixture, sem instalar serviços: `tmp/installer-precheck-2.1.22.log`.

## Limites de validação e teste do operador

Provas locais em macOS/JDK 17, com serviço representado por processos filhos. Não houve deploy remoto, teste de carga de infraestrutura, canário em Linux/Java 6 nativo nem atualização real de agentes. A release permanece marcada como candidata de laboratório pelo processo existente, para o operador testar primeiro na própria console 2.1.20. Não foi instalada no cliente.

Para o teste, enviar o web-update pela Administração, confirmar 2.1.20 → 2.1.22 e reiniciar. Conferir contas, ambientes, inventário, contato dos agentes e a lista Códigos de cadastro. Não instalar a ponte 2.1.21 manualmente. Instalador completo apenas para servidor novo.

**Atualizar o dashboard não atualiza os agentes automaticamente.** Agentes 2.1.20 podem permanecer instalados para esta alteração. Para alinhar versões separadamente, conferir elegibilidade no painel; não prometer migração de origens não verificadas.

## Pacotes

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| assessment-dashboard-installer-2.1.22.zip | 92947646 | `b6ebf1a072e8c33986fe41d3ea6998d29f54b40247e162508595d96934b224a4` |
| assessment-dashboard-update-2.1.22.zip | 93268040 | `664a6e8c959e0abd8bd44968b212e9807f7a8338ae9ce67c9c248525ac784533` |
| assessment-dashboard-web-update-2.1.22.zip | 93222338 | `ea5cae9cef71436780f27de5b5f14cca4d9ac4511530da4eaf31e8e984ed4cbf` |
