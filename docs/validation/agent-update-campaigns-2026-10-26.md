# Validação de campanhas de agentes — 26/10/2026

Status: implementação validada localmente, sem novo número de versão, sem instalador publicado e sem operação em hosts do cliente. Base `06f0e1d`, branch `codex/agent-update-campaigns`. Binários/protocolo dos agentes não foram alterados.

## Resultados

- `ops/test-source.sh`: PASS. Inclui coleta, inventário, permissões, exportações, dashboard, atualização e coletas vinculadas. A suíte foi repetida após a integração; os últimos ajustes de prazo/grupo foram revalidados nas suítes específicas abaixo.
- `AgentUpdateCampaignsTest`: **42 verificações**, 5.000 hosts, SQLite real e pacote de teste assinado. Execução em cerca de 5,2 s neste Mac (inclui preparação). Página de 50 e respostas limitadas, seleção de toda a frota, UUIDs consistentes, idempotência, reinício, concorrência global, grupos, cancelamento em blocos, preservação de claims, offline, pausa automática, repetição de resultados, propriedade/expiração do snapshot e revalidação das posições dos grupos.
- `AgentUpdateStartTimeoutTest`: **27 verificações**. Prazo preservado em reinício, cinco minutos somente na vez da máquina e ponte impedida após encerrar o pai.
- `AgentUpdateHttpTest`: **158 verificações**. API anterior, assinaturas, compatibilidade, replay, claims, heartbeat, rollback, expiração e revogação.
- `WorkRequestsHttpTest`: **179 verificações**. Histórico, autorização, escopo, exclusão de máquina, paginação SQL, eventos antigos e observações.
- `AgentUpdateCoordinatorTest`: **12 verificações**, usando cópia dos artefatos públicos assinados extraídos do instalador 2.1.24.
- `AgentCampaignMigrationTest`: PASS com os mesmos artefatos. Ponte original 1.5.12 e pedido V3 direto compartilham o teto global. Pausa impede claim de ponte já entregue, retomada conserva a ponte e cancelamento preserva downloads iniciados. Nenhum serviço de SO executado.
- `tests/agent-campaigns-browser.cjs`: PASS, Chrome real → HTTP real → SQLite, **5.000 hosts**. Nenhuma seleção automática; 50 registros por página; filtros; seleção de todo o filtro com resumo; confirmação perdida seguida de retry idempotente; opção de coleta explícita; pausa; cancelamento selecionado e total; conclusão do cancelamento no servidor; eventos sob demanda; CSRF e leitura sem permissão de mutação; viewport móvel de 390 px. Abertura da revisão medida entre **367 e 400 ms** nas execuções locais. Sem erros JavaScript.
- `tests/work-requests-browser.cjs`: PASS. Histórico, consultas lentas, retenção de foco/expansão, busca rápida, erro de rede, logout, leitor, escopo e versão móvel. Fixtures atualizadas para a seleção explícita da campanha.
- `tests/post-update-assessment-browser.cjs`: PASS com HTTP/SQLite reais e pacote de teste assinado. Opt-in explícito, confirmação final antes da coleta, poll, resultado parcial, deduplicação, vínculos nos dois sentidos e opt-out. Sem execução do worker de SO.
- `git diff --check`: PASS.

As entradas históricas `agent-updates-browser.cjs` e `agent-updates-real-browser.cjs` encaminham para o teste real da nova tela. As verificações do protocolo anterior permanecem em `AgentUpdateHttpTest`.

## Reprodução

A partir da raiz do repositório, use um JDK 17 e Node com Playwright/Chrome disponíveis:

```sh
JAVA_HOME=/caminho/do/jdk17 NODE=/caminho/do/node sh ops/test-source.sh
JAVA_HOME=/caminho/do/jdk17 NODE_PATH=/caminho/node_modules node tests/agent-campaigns-browser.cjs
NODE_PATH=/caminho/node_modules node tests/work-requests-browser.cjs
```

Para a migração, extraia somente `dist/` do instalador publicado 2.1.24 para um diretório de fixture isolado. Não altere o ZIP original e não use chaves privadas de produção. Depois:

```sh
"$JAVA_HOME/bin/java" -Xmx384m -cp 'tmp/source-classes:lib/*' assessment.AgentUpdateCoordinatorTest tmp/campaign-full-assets
"$JAVA_HOME/bin/java" -Xmx384m -cp 'tmp/source-classes:lib/*' assessment.AgentCampaignMigrationTest tmp/campaign-full-assets
```

O teste de coleta vinculada usa `AGENT_UPDATE_FIXTURE` com catálogo clássico de teste correspondente à versão do código, `BETA_CLASSPATH='tmp/source-classes:lib/*'` e `JAVA_HOME`. A fixture deve ter `assessment-collector.zip` na raiz e o catálogo em `dist/`; a chave é descartável. Não aponta para configuração, banco ou hosts de produção.

Evidências locais: `tmp/source-evidence/campaign-browser/` (JSON e screenshots), `output/work-requests/frontend/browser-results.json`, `output/agent-migration/real-browser/validation.json`. Esses diretórios são gerados e não entram nos instaladores.

## Limites da evidência e publicação

5.000 é o volume usado nos testes, não um teto de produto. Os testes usam registros sintéticos e tráfego de controle local; não simulam 5.000 transferências de pacote nem garantem tempo de conclusão durante a noite. A concorrência inicial é 2, ajustável até 10. Aumento de CPU não substitui o controle da fila.

Antes de distribuir ao cliente: gerar nova release consolidada (sem reutilizar 2.1.24), validar os ZIPs e o caminho de atualização da console, depois fazer um piloto com poucos agentes. Atualizar o dashboard não atualiza os agentes automaticamente. Pedidos antigos preservam suas regras de expiração; cancele a fila antiga e crie uma campanha nova para usar o prazo por admissão.
