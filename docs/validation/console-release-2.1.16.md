# Validação da entrega 2.1.16 — 20/10/2026

## Antes da reserva

A exportação foi implementada sobre a 2.1.14 e validada antes de incrementar a versão. `ops/test-source.sh` passou integralmente: coletor, SQLite, autenticação, relatórios, instalação, atualização e interface. A [validação da funcionalidade](inventory-export-2026-10-20.md) registra os testes de conteúdo, exclusão de credenciais, autorização, limpeza de temporários e navegador real em desktop/mobile.

## Pacotes finais

- `build-dashboard-release.py --lab-canary` concluído: ponte 2.1.15 e destino 2.1.16. Compilação ECJ, classes do agente major 50 (Java 6). Execução dos testes em macOS/JDK 17, sem comprovar execução nativa em Java 6/Linux.
- `DIRECT_V3_UPGRADE=PASS 2.1.14 -> 2.1.16`: manager binário original e bundle final assinado, mesma operação, identidade/CA/âncora preservadas. SO e rede usam adaptadores locais.
- `ORIGINAL_214_VERIFIERS=PASS`: verificadores extraídos do instalador publicado 2.1.14 aceitaram assinatura, baseline, payload, chave pública e catálogo final de 32 rotas; 264 arquivos no web-update.
- `FINAL_JAVA_PACKAGE=PASS 2.1.14 -> 2.1.16 -> 2.1.14`: classes Java reais, TLS/SNI, SQLite e identidade preservados após atualização/restauração; serviço simulado por processo local.
- `InventoryArchiveTest` e `InventoryArchiveHttpTest` passaram novamente usando as classes extraídas do runtime final. O teste HTTP usa inventário de fixture, sem catálogo de suporte nessa instalação temporária.
- Instalador extraído: precheck de pacote, JDK, URL e identidade TLS. O primeiro comando de teste usou `localhost`, rejeitado corretamente por não ser endereço acessível aos agentes; repetido com domínio de fixture válido.
- Três ZIPs conferidos por CRC e SHA-256, todos abaixo de 100 MiB. Versões anteriores preservadas.

## Política do catálogo de migração

O catálogo anterior já ocupava o limite de 32 origens aceito pelos verificadores publicados. O build preserva integralmente as rotas antigas até 2.1.12. Origens 2.1.14+ só deixam de gerar ponte quando o recibo imutável, a assinatura do bundle V3 e a chave pública são verificados. Todas continuam nas baselines dos helpers. Não houve ampliação do limite nem descarte automático da origem mais antiga.

`tests/release-origin-policy-test.py` valida essa política, as 32 versões preservadas, a manutenção da baseline 2.1.14 e rejeição de novas origens inválidas. A atualização direta V3 foi comprovada com o manager original 2.1.14. Isso não transforma uma instalação antiga sem capacidade V3 em elegível automaticamente.

## Artefatos revisados — JSON/CSV único

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| assessment-dashboard-installer-2.1.16.zip | 92937212 | `b447ffeedfd1daf1d2130a1d0bf54ae7eadf6d8d51a4c810428180f3c08cc8b2` |
| assessment-dashboard-update-2.1.16.zip | 93237064 | `e1c77492421a50e764cc8a9d9faf6e967b15921fcdbf410c01603acb3f290121` |
| assessment-dashboard-web-update-2.1.16.zip | 93190468 | `9be1b0cfd8ea063dbeee4542cd17d4a07df196915e8f5ef25b3e371bc1c9fcbe` |

README principal, índice de instalação, README dos pacotes, notas e documentação atualizados. Manual genérico passou a 17 páginas, com capítulo de exportação; capa e página nova renderizadas e inspecionadas. Guia de instalação revisado quanto ao escopo: o procedimento não mudou.

Logs locais em `tmp/`: `inventory-export-source-tests.log`, `build-2.1.16.log`, `direct-v3-2114-to-2116.log`, `original-214-verifiers-2116.log`, `dashboard-final-2114-to-2116.log`, `export-final-package-2.1.16.log` e `installer-precheck-2.1.16.log`. Regressões do build em `output/dashboard-releases/2.1.16/evidence/`.

## Teste do operador

Atualizar pela console e exportar em **Administração → Exportar inventário**. Conferir contagens e dados técnicos em uma amostra autorizada. **Atualizar também os agentes separadamente pelo painel.** Não há necessidade de nova coleta apenas para usar a exportação. Nenhum deploy remoto ou teste com dados do cliente foi executado nesta entrega.

## Revisão solicitada pelo operador na mesma 2.1.16

O operador pediu explicitamente substituir os pacotes sem incrementar a versão e restaurou sua console para 2.1.14. O caminho de teste é novamente web-update 2.1.14 → 2.1.16. O bloqueio de atualização sobre a mesma versão foi mantido. O commit original `3ce956d` e uma cópia local em `output/dashboard-revisions/2.1.16-original` preservam a primeira entrega. A baseline original também foi registrada para permitir reconhecê-la em futuras atualizações.

JSON ou CSV selecionável; o ZIP tem exatamente um arquivo. JSON contém `metadata` e `data`; CSV contém uma linha de metadados e uma por recurso, com detalhes aninhados completos nas células. A versão do formato de dados é 2. As exclusões de credenciais foram mantidas.

Validação da revisão:

- Suíte completa `ops/test-source.sh` passou. Teste novo com leitor CSV/JSON do Python comparou todos os campos, evidências aninhadas, contagens, Unicode, aspas, quebras de linha e proteção contra fórmulas.
- HTTP validou ambos os formatos, formato inválido, administrador/leitor/anônimo, limites e limpeza. Chrome baixou os dois ZIPs, com seletor e navegação por teclado, desktop/mobile sem erro JavaScript; imagens inspecionadas.
- Os testes de exportação foram repetidos nas classes finais do pacote, com sucesso.
- Verificadores originais 2.1.14 aprovaram o novo web-update; atualização e restauração 2.1.14 → 2.1.16 → 2.1.14 passaram com Java/TLS/SQLite reais em processo local.
- Todos os arquivos `dist/` do runtime e do instalador novo foram comparados com a primeira entrega: agentes, bundles, rotas e assinaturas idênticos byte a byte. O build do instalador normalmente altera timestamps do ZIP interno; o ZIP original do agente foi reaproveitado após verificar igualdade de todas as entradas. O precheck foi repetido depois dessa preservação.
- CRC, checksums internos e SHA-256 dos três pacotes foram verificados. Manual genérico atualizado e página de exportação renderizada/inspecionada.

Logs locais: `tmp/export-single-source-tests.log`, `tmp/export-single-browser.log`, `tmp/rebuild-216-single.log`, `tmp/original-214-verifiers-2116-single.log`, `tmp/dashboard-final-2114-to-2116-single.log`, `tmp/export-single-final.log`, `tmp/installer-precheck-2.1.16-single.log`.

Reconstrução excepcional: `ops/rebuild-dashboard-2.1.16-export.py` verifica o instalador original por hash, constrói em pasta separada e preserva artefatos do agente. `ops/preserve-agent-2.1.16-revision.py` mantém também os bytes do ZIP do agente dentro do instalador novo. Não são substitutos do processo normal de reservar nova versão.

Não houve deploy remoto nem alteração de agentes. Agentes já na 2.1.16 não precisam ser reinstalados; **atualize separadamente os agentes antigos pelo painel**. O inventário existente já pode ser exportado.
