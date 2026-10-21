# Validação da release 2.1.20

Data: 21/10/2026. Base: commit 1f953e4 (2.1.18). Entrega: Profile/contexto na tabela e nos detalhes de Datasources JDBC. Ponte interna 2.1.19; final 2.1.20. Não há alteração funcional do agente, além da identificação de versão unificada gerada pelo build.

## Provas concluídas

- `ops/test-source.sh` passou antes de reservar a versão: regressão Java, HTTP, coletores e suíte JavaScript atual. Log: `tmp/profile-context-source-tests.log`.
- `build-dashboard-release.py --lab-canary`: concluído. A primeira execução foi impedida pelo sandbox de abrir um socket local (`java.net.SocketException: Operation not permitted`). O log foi preservado em `evidence/agent-update-worker-sandbox-block.log`; a mesma reserva ainda não publicada foi retomada com `--package-version 2.1.20 --lab-canary` e permissão de loopback. Nenhum teste foi removido ou ignorado; AgentUpdateWorkerTest passou com 165 verificações. Logs: `tmp/build-2.1.20.log` e `tmp/build-2.1.20-retry.log`.
- Web-update e instalador gerados com os scripts oficiais. ZIPs verificados por CRC, SHA-256 externo e manifesto interno quando presente; arquivos web/app.js e web/datasources.js do runtime correspondem ao código validado.
- Chrome com classes finais do dashboard e respostas sintéticas de datasources: três registros homônimos preservados, coluna e detalhes para full/full-ha/ausência, mobile sem transbordamento no painel e nenhum erro JavaScript. O teste temporário teve seu seletor corrigido de #drawer para #detail-drawer antes da aprovação. Logs: `tmp/profile-context-final-browser.log`; capturas em `tmp/profile-context-browser/`.
- Verificadores originais dos instaladores 2.1.18 e 2.1.16 revisada aceitaram assinatura, baseline e conteúdo do pacote 2.1.20. Chave de confiança, bootstrap imutável, bytecode Java 6 e as 32 rotas de migração anteriores preservados. Logs: `tmp/original-218-verifiers-220.log` e `tmp/original-216-verifiers-220.log`.
- Atualização/restauração **2.1.18 → 2.1.20 → 2.1.18** passou com classes publicadas, TLS/SNI, SQLite e identidade da instalação.
- Atualização/restauração **2.1.16 → 2.1.20 → 2.1.16** também passou, usando a compilação revisada de exportação em arquivo único. Logs: `tmp/dashboard-final-218-to-220.log` e `tmp/dashboard-final-216-to-220.log`.
- Instalador final: `install.py --check --no-package-install` passou com JDK 17 e URL de fixture; pacote e identidade TLS conferidos. Log: `tmp/installer-precheck-2.1.20.log`.

## Limites e pendência anterior

Provas locais em macOS/JDK 17, com serviços por processo filho no lugar de systemd. Não houve deploy remoto, canário em Linux/Java 6 nativo ou atualização real de agentes. O operador deve confirmar upload, reinício e Profile/contexto com o inventário do ambiente.

O teste legado `tests/beta-ui.test.cjs` (fora da suíte oficial atual) falha esperando `<strong>1,000</strong>`. A mesma falha foi reproduzida nos arquivos intactos de 1f953e4, sem a alteração desta entrega, e foi preservada sem correção fora do escopo. As 11 suítes JavaScript oficiais passaram, incluindo dashboard, locale, datasources e JDBC.

Atualização direta pela console validada da **2.1.18 e 2.1.16 revisada para 2.1.20**. Não é necessário instalar a ponte 2.1.19 manualmente. Instalador completo apenas para servidor novo.

**Atualizar o dashboard não atualiza os agentes automaticamente.** Revise versões e elegibilidade no painel para alinhar os agentes separadamente. Essa coluna usa configurationContext já coletado e não exige agente novo para funcionar.

## Pacotes em install/v2.1.20

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| assessment-dashboard-installer-2.1.20.zip | 92946345 | `77b74cd912510fa08d7c7ce8001f031db33b94103a3d7cac350039792849206e` |
| assessment-dashboard-update-2.1.20.zip | 93265968 | `1cfb3c07a95e342fcccd99e81ecb3647230b79e828e37d7e59732767947f9a57` |
| assessment-dashboard-web-update-2.1.20.zip | 93220463 | `c1bc695efeb49fb2f1be696cd2c729fad8b02360cbb60ebb84a87bbaadd3b30e` |
