# Unified release version Implementation Plan

> Use executing-plans inline for this authorized change. Do not spawn agents or modify other chats.

**Goal:** Dashboard, agente e ZIP da entrega sempre usam exatamente a mesma versão.

**Architecture:** Um único contador gera as constantes dos dois produtos e controla os nomes de artefatos. Compilar os dois em cada entrega e exigir igualdade entre binários, manifestos, HTTP e recibos de publicação.

**Tech Stack:** Python3, Java17, ECJ/bytecodeJava6, SQLite, JavaScript/Playwright.

- [x] Em ops/dashboard_release.py, acrescentar piso de versão à reserva e helpers para ler/gerar AgentTransport.VERSION. Testar migração para1.5.2 e próxima1.5.3 em diretório temporário.
- [x] Criar ops/unified_agent.py para compilar os produtos do agente em bytecode50, recompilar independentemente e montar o ZIP com versão comum, manifests completos e dependências/CA verificadas da base1.5.1.
- [x] Alterar ops/build-dashboard-release.py para gerar as constantes, compilar ambos, executar testes HTTP cruzados e empacotar agente/servidor com igualdade obrigatória. Acrescentar os novos helpers/docs ao pacote de fontes.
- [x] Ajustar ops/dashboard-release/update-dashboard.py: exigir metadata do ZIP igual à entrega antes de parar o serviço, repetir validação após preservar a CA e relatar collectorVersion igual à versão comum.
- [x] Criar tests/UnifiedReleaseHttpTest.java com health, pacote real/binary reflection, info e downloads generic/configurado, e atualizar fixtures que afirmam versão corrente1.5.1 para usar o valor real do agente.
- [x] Preparar1.5.3, executar regressões de agente, instalador e atualização, conferir a versão no navegador e finalizar os pacotes.
- [x] Validar checksums, versão Java6/JAR/JSON/ZIP/HTTP e publicar instruções para atualização manual do dashboard e das VMs; registrar validation-results.json.

Não executar SSH, alterar identidades ou reescrever versões de agentes que ainda estão instalados. Projeto ignorado no Git pai; não forçar commit.
