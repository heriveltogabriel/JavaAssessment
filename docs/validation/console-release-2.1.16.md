# Validação da entrega 2.1.16 — 20/10/2026

## Antes da reserva

A exportação foi implementada sobre a 2.1.14 e validada antes de incrementar a versão. `ops/test-source.sh` passou integralmente: coletor, SQLite, autenticação, relatórios, instalação, atualização e interface. A [validação da funcionalidade](inventory-export-2026-10-20.md) registra os testes de conteúdo, exclusão de credenciais, autorização, limpeza de temporários e navegador real em desktop/mobile.

## Pacotes finais

- `build-dashboard-release.py --lab-canary` concluído: ponte 2.1.15 e destino 2.1.16. Compilação ECJ, classes do agente major 50 (Java 6). Execução dos testes em macOS/JDK 17, sem comprovar execução nativa em Java 6/Linux.
- `DIRECT_V3_UPGRADE=PASS 2.1.14 -> 2.1.16`: manager binário original e bundle final assinado, mesma operação, identidade/CA/âncora preservadas. SO e rede usam adaptadores locais.
- `ORIGINAL_214_VERIFIERS=PASS`: verificadores extraídos do instalador publicado 2.1.14 aceitaram assinatura, baseline, payload, chave pública e catálogo final de 32 rotas; 261 arquivos no web-update.
- `FINAL_JAVA_PACKAGE=PASS 2.1.14 -> 2.1.16 -> 2.1.14`: classes Java reais, TLS/SNI, SQLite e identidade preservados após atualização/restauração; serviço simulado por processo local.
- `InventoryArchiveTest` e `InventoryArchiveHttpTest` passaram novamente usando as classes extraídas do runtime final. O teste HTTP usa inventário de fixture, sem catálogo de suporte nessa instalação temporária.
- Instalador extraído: precheck de pacote, JDK, URL e identidade TLS. O primeiro comando de teste usou `localhost`, rejeitado corretamente por não ser endereço acessível aos agentes; repetido com domínio de fixture válido.
- Três ZIPs conferidos por CRC e SHA-256, todos abaixo de 100 MiB. Versões anteriores preservadas.

## Política do catálogo de migração

O catálogo anterior já ocupava o limite de 32 origens aceito pelos verificadores publicados. O build preserva integralmente as rotas antigas até 2.1.12. Origens 2.1.14+ só deixam de gerar ponte quando o recibo imutável, a assinatura do bundle V3 e a chave pública são verificados. Todas continuam nas baselines dos helpers. Não houve ampliação do limite nem descarte automático da origem mais antiga.

`tests/release-origin-policy-test.py` valida essa política, as 32 versões preservadas, a manutenção da baseline 2.1.14 e rejeição de novas origens inválidas. A atualização direta V3 foi comprovada com o manager original 2.1.14. Isso não transforma uma instalação antiga sem capacidade V3 em elegível automaticamente.

## Artefatos

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| assessment-dashboard-installer-2.1.16.zip | 92935915 | `8ab1a2186f4c1db7c82cf2aa2e14679bda842c8d65ac4e7f15963770ab3dafec` |
| assessment-dashboard-update-2.1.16.zip | 93232829 | `87e8f8082bc2a55ef463f49b4176a31f38f7f447b812407eaf408346e888782a` |
| assessment-dashboard-web-update-2.1.16.zip | 93185432 | `cb4e28792285bf36c74f1a688c8de67e6f6a0133c4c7bca0fde085dd9ea5ae42` |

README principal, índice de instalação, README dos pacotes, notas e documentação atualizados. Manual genérico passou a 17 páginas, com capítulo de exportação; capa e página nova renderizadas e inspecionadas. Guia de instalação revisado quanto ao escopo: o procedimento não mudou.

Logs locais em `tmp/`: `inventory-export-source-tests.log`, `build-2.1.16.log`, `direct-v3-2114-to-2116.log`, `original-214-verifiers-2116.log`, `dashboard-final-2114-to-2116.log`, `export-final-package-2.1.16.log` e `installer-precheck-2.1.16.log`. Regressões do build em `output/dashboard-releases/2.1.16/evidence/`.

## Teste do operador

Atualizar pela console e exportar em **Administração → Exportar inventário**. Conferir contagens e dados técnicos em uma amostra autorizada. **Atualizar também os agentes separadamente pelo painel.** Não há necessidade de nova coleta apenas para usar a exportação. Nenhum deploy remoto ou teste com dados do cliente foi executado nesta entrega.
