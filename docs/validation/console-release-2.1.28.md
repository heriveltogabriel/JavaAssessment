# Validação da release 2.1.28

Execução em 26/10/2026, horário de Brasília; a reserva ocorreu em 27/10/2026 01:21 UTC. Clone limpo da `main` no commit **6f910ad**, branch local `codex/release-2.1.28`. Ponte interna **2.1.27**, destino **2.1.28**. Build `--lab-canary`; estado `bridge-prepared`/`prepared` e `candidateOnly` preservado. Nenhum candidato intermediário publicado, nenhuma versão anterior sobrescrita e nenhum host do cliente acessado.

## Resultado dos controles

| Controle | Resultado e alcance |
| --- | --- |
| Pré-requisitos | PASS: clone independente; sem cópias Finder `* N.java`; JDK 17.0.20.1+1, ECJ 3.33.0, releases históricas e chave original; pin público conferido sem expor chave privada |
| `ops/test-source.sh` completo | PASS antes da reserva: compilação, coletor, HTTP/SQLite, permissões, exportações, atualizações, aviso administrativo e JavaScript |
| `build-dashboard-release.py --lab-canary` | PASS: reserva única 2.1.27/2.1.28, bytecode Java 6, descoberta com heap de 96 MiB, testes do atualizador, assinatura e pacotes finais |
| Web-update e instalador | PASS: geradores oficiais, três ZIPs finais; precheck do instalador extraído com JDK 17 e URL/TLS sintéticos |
| CRC e nomes dos ZIPs | PASS nos três pacotes: sem CRC inválido, duplicidade, caminho absoluto ou traversal |
| SHA-256 externo e inventários internos | PASS: hashes externos, inventários completos `SHA256SUMS` quando presentes; payload do web-update conferido pelo manifesto assinado |
| Assinaturas e manifesto | PASS nos verificadores originais das quatro origens: pin, baseline, catálogo, bundle e bootstrap; 277 arquivos no manifesto web e 32 perfis legados preservados |
| Campanhas no runtime final | PASS: 47 verificações, 5.000 registros sintéticos, limites e controle persistente |
| Agendamento no runtime final | PASS: 48.310 verificações; 1.150 hosts distribuídos em 15 minutos, 66–86 por minuto |
| SQLite e coordenação no runtime final | PASS: persistência, transações, rollback, migração, WAL/read snapshots; coordenador com 12 verificações |
| Navegador com classes e web do runtime final | PASS: 5.000 registros, páginas de 50, filtros, seleção, confirmação idempotente, pausa, cancelamento em lote, eventos sob demanda, CSRF, perfil leitura e layout móvel |

A revisão abriu em **249 ms neste Mac** no ensaio final do navegador. É um tempo de fixture, não uma garantia para o cliente nem teste de 5.000 agentes transferindo pacotes. A compilação dos testes produziu avisos de depreciação/unchecked, sem erro. Nenhum teste de produto falhou nesta execução.

## Compatibilidade comprovada localmente

| Origem publicada | Verificador original aceita pacote | Console → 2.1.28 → rollback à origem | Agente original → 2.1.28 |
| --- | --- | --- | --- |
| 2.1.20 | PASS | PASS | PASS |
| 2.1.22 | PASS | PASS | PASS |
| 2.1.24 | PASS | PASS | PASS |
| 2.1.26 | PASS | PASS | PASS |

Os instaladores originais conferem com os hashes versionados em Git. Os runtimes e bundles originais conferem com seus recibos históricos; nenhuma origem foi reconstruída para substituir o binário publicado. Verificadores extraídos de cada instalador aceitaram assinatura, baseline e arquivos do pacote novo. As quatro origens V3 seguem diretamente: não requerem instalar manualmente a ponte 2.1.27.

**Console:** classes Java publicadas executadas de verdade, TLS/SNI, preparação/confirmação do ZIP assinado, saúde na versão nova e rollback. Identidade de instalação preservada; registro SQLite criado depois da atualização permaneceu após rollback e `PRAGMA integrity_check` retornou `ok`. Serviços foram representados por processos filhos locais.

**Agentes:** manager carregado do `collector.jar` publicado de cada origem, com instalação V3 inicializada a partir de seu bundle assinado. Aplicação do bundle final, promoção de código, handoff e confirmação, preservando bootstrap, geração anterior, identidade, configuração, CA, Java privado, spool e logs. Serviços, transporte e confirmação do novo manager são adaptadores explícitos; execução local em JDK 17.

## Evidências e reprodução

[Saídas dos controles locais](evidence/console-release-2.1.28.txt). Logs completos no Mac em `/private/tmp/ja-release-228-*.log`; build em `output/dashboard-releases/2.1.28/evidence/`; recibo dos ZIPs em `tmp/packages-2.1.28.json`; screenshots e resultados de navegador em `tmp/source-evidence/campaign-browser-228/`.

```sh
export ASSESSMENT_JAVA_HOME="$PWD/tools/jdk17/jdk-17.0.20.1+1/Contents/Home"
export JAVA_HOME="$ASSESSMENT_JAVA_HOME"
# A suíte usa também Node.js disponível no PATH/NODE.
sh ops/test-source.sh
# Já executado uma vez nesta entrega; não repetir para reservar outro par:
# python3 ops/build-dashboard-release.py --lab-canary

for origem in 2.1.20 2.1.22 2.1.24 2.1.26; do
  TEST_JAVA="$JAVA_HOME/bin/java" python3 tests/dashboard-update-final-package.py \
    --from-version "$origem" --to-version 2.1.28
done
```

Os controles exigem os artefatos históricos imutáveis em `output/dashboard-releases`. Helpers locais `tmp/verify-final-228.py`, `tmp/test-released-agent-228.py` e `tmp/ReleasedAgentUpgradeTest.java` exercitaram verificadores/managers originais. Os testes adicionais foram compilados contra classes finais extraídas; o harness de navegador apontou explicitamente para `tmp/release-228-runtime/classes` e `tmp/release-228-runtime/web`.

## Limites e operação

O instalador completo passou por precheck; não foi executada uma instalação nova de serviços, nem o pacote alternativo de atualização por script em um servidor real.

Não foram verificados nesta entrega: deploy real, upload/reinício no cliente, systemd/SysV ou reboot, Java 6 nativo, carga de milhares de agentes reais, nem tratamento de 503 nos agentes 2.1.20/2.1.22/2.1.24. Não se declara nova prova runtime de migração das 32 origens legadas; seus artefatos e assinaturas foram preservados, mas as rotas exercitadas aqui são as quatro acima.

As medições de contenção com 1.150 agentes simulados em [agent-path-load-2026-10-26.md](agent-path-load-2026-10-26.md) são evidências anteriores da implementação, não um benchmark repetido na preparação desta release. A menção histórica a `synchronous=FULL` naquele documento descreve uma fase anterior; o pacote consolidado tem **NORMAL por padrão**, conforme o ajuste final, com opção FULL. `NORMAL` reduz a durabilidade dos commits mais recentes em falha de energia/SO.

**Atenção: atualize também os agentes. Atualizar o dashboard não atualiza os agentes automaticamente.** A melhora de desempenho já vale com agentes atuais **2.1.26**; sua atualização é **opcional** para obtê-la. Se atualizar, confira V3/rota/elegibilidade real em **Revisar agentes**. Sem capacidade compatível, use o instalador do agente configurado para seu ambiente com `--system-root`, conservando identidade e CA. O instalador completo do dashboard é para servidor novo.

Release inicialmente registrada no commit local `9363b0f`; posteriormente o usuário autorizou a publicação no GitHub e a integração na `main`. Os pacotes validados permanecem inalterados. Testar primeiro uma onda pequena e observar carga antes de ampliar; não há promessa de concluir uma frota durante a noite.

## Pacotes finais

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| assessment-dashboard-installer-2.1.28.zip | 93002581 | `30ae53675762380beb15b0982dd7a6671e43513ae66034d499e18d25837a3b15` |
| assessment-dashboard-update-2.1.28.zip | 93342284 | `f32deb59115b74e0207c267cc0d59cf8ddd1a71b906a87a762c9eafdd3fa8aff` |
| assessment-dashboard-web-update-2.1.28.zip | 93296804 | `912aaf53912d1e7d2e46cc46c107ca7c2b796ce98c87d5e8897be6c38a273e1f` |
