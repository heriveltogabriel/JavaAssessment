# Validação da release 2.1.26

Data: 26/10/2026. Base funcional `404b73b`, branch `codex/agent-update-campaigns`. Ponte interna **2.1.25**, destino **2.1.26**. Build para teste do operador (`--lab-canary`), sem deploy remoto. Pacotes anteriores preservados.

## Provas locais

- `ops/test-source.sh`: suíte completa aprovada antes de reservar a versão. Compilação, inventário/coletor, HTTP/SQLite, permissões, exportações, atualização, aviso administrativo e JavaScript. Log `/private/tmp/ja-release-226-source.log`.
- `ops/build-dashboard-release.py --lab-canary --version 2.1.26`: aprovado, com coletor em bytecode Java 6, testes de descoberta com heap de 96 MiB, controles de atualização, assinaturas e pacotes finais. Log `/private/tmp/ja-release-226-build.log`, evidências em `output/dashboard-releases/2.1.26/evidence/`.
- `AgentUpdateCampaignsTest` na versão nova: **42 verificações e 5.000 máquinas**, PASS. Log `/private/tmp/ja-release-226-campaigns.log`. Não impõe teto de frota nem mede 5.000 downloads simultâneos.
- Web-update e instalador gerados pelos scripts oficiais. Três ZIPs verificados por CRC, duplicidade/traversal, SHA-256 externo e inventários internos. Manifesto web inclui as origens 2.1.20/2.1.22/2.1.24; fontes e arquivos web das campanhas conferem byte a byte com o código validado. Recibo `tmp/packages-2.1.26.json`.
- Verificadores extraídos dos instaladores publicados **2.1.20, 2.1.22 e 2.1.24** aceitaram o web-update e o catálogo novo: assinatura, baseline, pin, bundle, bootstrap imutável, bytecode major 50 e preservação dos 32 perfis legados. Logs `/private/tmp/ja-release-226-original-verifiers-2120.log`, `...-2122.log` e `...-2124.log`.
- **Console 2.1.20/2.1.22/2.1.24 → 2.1.26 → origem**: processos reais com classes publicadas, TLS/SNI, SQLite íntegro, identidade preservada e restauração conservando dados gravados após atualizar. Logs `/private/tmp/ja-release-226-console-original-2120.log`, `...-2122.log` e `...-2124.log`.
- **Agentes 2.1.20/2.1.22/2.1.24 → 2.1.26**: atualizador carregado do JAR original publicado, instalação V3 inicializada com o bundle assinado original, aplicação do bundle final, promoção de código/manager e confirmação. Bootstrap imutável e geração anterior preservados; identidade, configuração, CA, Java privado, spool e logs conservados. Logs `/private/tmp/ja-release-226-agent-original-2120.log`, `...-2122.log` e `...-2124.log`.
- `AgentCampaignMigrationTest` e `AgentUpdateCoordinatorTest` com classes e catálogo extraídos do runtime final: PASS. Ponte e V3 direto compartilham concorrência; pausa impede nova reivindicação; retomada preserva ponte; cancelamento conserva transferências já iniciadas. Logs `/private/tmp/ja-release-226-migration.log` e `/private/tmp/ja-release-226-coordinator.log`.
- Navegador Chrome com classes e web extraídos do runtime final: **PASS com 5.000 máquinas**, páginas de 50, filtros, seleção, snapshot, confirmação idempotente após perda de resposta, pausa, cancelamento em lote, eventos sob demanda, CSRF, perfil leitura e layout móvel. Revisão abriu em 436 ms neste Mac; não é garantia de desempenho no cliente. Evidências: `tmp/source-evidence/campaign-browser-226/results.json` e screenshots, log `/private/tmp/ja-release-226-browser.log`.
- Instalador final extraído: `install.py --check --no-package-install`, JDK 17, URL e identidade TLS sintéticas: PASS, sem instalação de serviços. Log `/private/tmp/ja-release-226-installer-precheck.log`.

O comando auxiliar de compilação precisou incluir as classes de fixtures do coletor no classpath. O primeiro precheck recusou `localhost` conforme a regra de URL pública e foi repetido com `dashboard.example.test`. Foram correções dos comandos de teste; nenhum código de produto foi alterado nem teste relaxado.

## Reproduzir os principais controles

```sh
JAVA_HOME=/caminho/jdk17 NODE=/caminho/node sh ops/test-source.sh
TEST_JAVA=/caminho/jdk17/bin/java python3 tests/dashboard-update-final-package.py --from-version 2.1.20 --to-version 2.1.26
TEST_JAVA=/caminho/jdk17/bin/java python3 tests/dashboard-update-final-package.py --from-version 2.1.22 --to-version 2.1.26
TEST_JAVA=/caminho/jdk17/bin/java python3 tests/dashboard-update-final-package.py --from-version 2.1.24 --to-version 2.1.26
```

Os testes de pacotes exigem os artefatos originais imutáveis e recibos locais em `output/dashboard-releases`. Não reconstruir uma origem para substituir a prova com seu binário publicado. Os helpers locais `tmp/verify-final-226.py` e `tmp/test-released-agent-226.py` exercitaram os verificadores e managers originais, respectivamente.

## Limites e operação

Provas em macOS/JDK 17 com registros sintéticos. Console usa processos filhos como adaptador de serviço. No teste do agente, serviços, transporte e confirmação pelo novo manager são adaptadores explícitos: não é prova de systemd, SysV, reboot, Java 6 nativo ou carga real. A marca `candidateOnly` foi mantida. Não houve acesso ou alteração em hosts do cliente.

O web-update permite as três origens verificadas diretamente. Agentes dessas versões com V3 íntegro podem atualizar separadamente pelo painel, sem instalar a ponte 2.1.25 manualmente. A elegibilidade é conferida na instalação real. Sem capacidade/rota compatível, use o pacote do agente configurado para o ambiente com `--system-root`; o instalador completo do dashboard é para servidor novo.

**Atualizar o dashboard não atualiza os agentes automaticamente. Atualize também os agentes separadamente.** Inicie um piloto pequeno, confirme versão/contato e só então amplie. As mudanças funcionais desta entrega estão no dashboard; o coletor recebeu apenas alinhamento de versão. Pedidos antigos conservam as regras anteriores: cancele a fila pendente antes de criar campanha nova. Instalações iniciadas não são interrompidas por cancelamento.

## Pacotes

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| assessment-dashboard-installer-2.1.26.zip | 92999534 | `03920ca94b47cc8f5c8321d9a0edf9ac18434ebf5b20b4855b436ee87f6f59f5` |
| assessment-dashboard-update-2.1.26.zip | 93334536 | `7c329961f37019c780a7a30f80bf4ea21d4e26befcbc4570ff669b475942d544` |
| assessment-dashboard-web-update-2.1.26.zip | 93288642 | `bba76c0b6f458fa9677ed757edec14584785870305af604e22400611fd3259cc` |
