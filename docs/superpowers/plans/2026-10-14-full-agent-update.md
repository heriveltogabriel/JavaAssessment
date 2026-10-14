# Atualização completa do agente — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Atualizar instalações conhecidas a partir da 1.5.12 pelo dashboard, preservar o estado da máquina, recuperar falhas e executar um assessment opcional após a confirmação final.

**Architecture:** Uma operação lógica fixa a rota assinada entre instalação original, bridge e bundle final. Bootstrap/recuperação independentes promovem código e manager versionados, mantendo estado e uma geração anterior; uma intenção SQLite idempotente coordena a coleta posterior.

**Tech Stack:** Coletor e ferramentas com bytecode Java 6, dashboard Java 17/SQLite, shell POSIX para adapters locais, Python/OpenSSL para artefatos offline, Node/Chrome para interface.

**Desenho de referência:** [Atualização completa a partir da 1.5.12](../specs/2026-10-14-full-agent-update-design.md).

**Estado deste plano:** código de WF1–WF5 implementado; prova local com binário original 1.5.12, instalação nova, evolução T→U, falhas de I/O, recuperação, desinstalação e coleta posterior aprovadas. WF6 tem regressões locais aprovadas e gates de serviços/reboot/Java 6 real ainda abertos. WF7 aguarda Linux real e os dois canários. Nenhuma release de produção reservada ou publicada. Consulte [as evidências](../../validation/full-agent-update.md).

## Regras de execução

- Não ampliar a compatibilidade para versões inferiores à 1.5.12.
- Não relaxar assinatura, hash, propriedade/permissões, protocolo legado ou validação da JVM para tornar um agente artificialmente elegível.
- Não reservar versão ou assinar com chave de produção durante a prova de conceito. Fixtures usam chave exclusiva de teste e cópias de artefatos originais.
- Não alterar as duas máquinas reais durante o desenvolvimento. Elas são canários sequenciais do workflow 7.
- O número da release final será reservado apenas após integração aprovada. O bridge recebe uma versão interna própria e inferior ao alvo final, ambas imutáveis.
- Manter o teste real de 32 bits fora das alegações de validação enquanto não houver ambiente disponível.
- Cada workflow encerra com testes, evidência local sem segredos e commit próprio. Integração não deve ocorrer enquanto seu gate estiver aberto.

## Divisão e paralelismo

| Workflow | Responsabilidade | Depende de | Entrega verificável |
| --- | --- | --- | --- |
| WF0 | Provar a passagem do atualizador original 1.5.12 para o bootstrap | Nenhuma | Ciclo original, ACK, limpeza e handoff executados em fixture |
| WF1 | Contratos, perfis, bundles e assinatura | WF0 | Artefatos reproduzíveis e contratos congelados |
| WF2 | Instalação, recuperação, preservação e limpeza | WF1 | Atualizador completo independente do coletor |
| WF3 | Rotas e operação lógica no dashboard | WF1 | Uma operação rastreável, com subpedidos legados compatíveis |
| WF4 | Assessment após a atualização | Contrato de WF1; integra com WF3 | Intenção/job idempotentes e carga limitada |
| WF5 | Popup, prévia e Work requests | Contratos de WF3/WF4 | Progresso, seleção de coleta e links testados no navegador |
| WF6 | Integração e falhas em laboratório | WF2/WF3/WF4/WF5 | Matriz de falhas e segunda evolução de suporte aprovadas |
| WF7 | Duas máquinas 1.5.12 e release | WF6 | Dois canários em sequência e confirmação da atualização seguinte |

Ordem: **WF0 → WF1 → WF2 + WF3 + WF4 em paralelo → WF5 → WF6 → WF7**. O desenho visual de WF5 pode começar com fixtures assim que os contratos estiverem fixados; o teste real da interface espera WF3/WF4.

Três frentes de execução após o contrato: agente A cuida de WF2; agente B de WF3; agente C de WF4. O coordenador integra assinatura/build, resolve os pontos compartilhados e revisa os resultados. Uma frente não edita arquivos de outra sem combinar a transferência.

## WF0 — Prova bloqueadora da migração 1.5.12

**Arquivos novos de fixture/teste:**

- `tests/build-agent-migration-fixtures.py`: extrair/validar artefatos legados, criar pin de teste e payloads de transição isolados.
- `tests/LegacyAgentMigrationTest.java`: executar o worker original por classloader/adapters de teste, sem recompilá-lo como se fosse o atual.
- `tests/agent-migration-handoff-test.py`: exercitar scripts, locks e passagem para o serviço independente em ambiente Linux isolado.
- `docs/validation/agent-migration-baselines.json`: hashes públicos das baselines utilizadas, sem caminhos/identidades de cliente.

**Passos:**

- [x] Conferir checksums dos ZIPs originais 1.5.12 e dos demais perfis >=1.5.12; extrair em `tmp/agent-migration/legacy/`, sem executar arquivos extraídos como root no Mac.
- [x] Fixar as oito chaves do manifesto V1: `schema`, `type`, `version`, `format`, `minUpdater`, `sha256`, `bytes`, `requiredFiles`. Usar `minUpdater=1` na fixture de 1.5.12 e o inventário exato da origem.
- [x] Construir um bridge de teste com versão B e um JAR original de recuperação embutido. A rota fixa `(fromVersion, fromCollectorSha256, supportProfileId)`; o JAR final precisa continuar <=32 MiB e major 50.
- [ ] Executar o ciclo original completo: verificação → probe sem mutação → troca → heartbeat B → report SUCCEEDED → ACK exato → limpeza do journal/backup antigo → liberação efetiva do flock.
- [ ] Demonstrar que o bridge só inicia bootstrap fora dos locks do ciclo e em serviço separado; o collector que inicia a preparação deve sair antes da parada do seu serviço.
- [x] Materializar, após o ACK, o JAR original embutido e uma cópia validada de suporte/helper/unidades antes de qualquer alteração. Conferir recuperação para a origem, e não apenas retorno ao bridge.
- [x] Repetir com ACK perdido, processo interrompido, suporte modificado, assinatura inválida e início duplicado do bootstrap. Nenhuma tentativa pode apagar configuração ou executar candidato inválido.

**Comandos de aceite após criar as fixtures:**

```sh
python3 -B tests/build-agent-migration-fixtures.py --from-version 1.5.12 --output tmp/agent-migration/fixture
/bin/sh ops/test-agent-migration.sh --suite legacy
```

`ops/test-agent-migration.sh` será criado neste workflow: usa `JAVA_HOME`/`ASSESSMENT_ECJ_JAR`, compila classes de produto major 50 e fixtures Java 17 em diretórios separados, e executa somente as suítes selecionadas. O modo `legacy` deve falhar se não encontrar o binário original conferido. Não baixar nem substituir uma baseline silenciosamente.

**Gate:** só abrir WF1 após provar ACK/cleanup/lock/handoff e restauração da origem. Um PASS de `verify()` isolado não fecha o gate.

## WF1 — Contratos de atualização e artefatos assinados

**Arquivos:**

- Criar `docs/AGENT_UPDATE_V3.md`: schemas e contratos definitivos, incluindo matriz origem/perfil/bridge/destino.
- Criar `shared/AgentBundleManifest.java`: parser Java 6 compartilhado entre coletor e servidor, inventário e verificações de pacote.
- Criar `server/AgentUpdateRoutes.java`: catálogo imutável de rotas e perfis assinados, sem depender de apenas uma versão corrente.
- Modificar `ops/agent_update_signing.py`, `ops/unified_agent.py` e `dashboard-installer/build.py`: gerar/verificar bridge, bundle e catálogo, preservando os tipos V1 já publicados.
- Adaptar também o builder operacional existente em `../jms-teste-macos/assessment-pilot/ops/build-dashboard-release.py` e as templates `ops/dashboard-release/update-dashboard.py` / `test-update-dashboard.py` desse workspace. Esses scripts operacionais não entram no Git público com configuração de laboratório; a entrega precisa embutir o catálogo novo e reconhecer operações pai ocupadas.
- Criar `tests/AgentBundleManifestTest.java`, `tests/agent-bundle-signing-test.py`, `tests/AgentUpdateRoutesTest.java`.

**Contratos a fixar:**

```json
{
  "hostIds": ["host-fixture"],
  "targetVersion": "9.0.2",
  "requestKey": "fixture-update-0001",
  "expiresInMinutes": 60,
  "runAssessmentAfterUpdate": true
}
```

Os números 9.0.1/9.0.2 são exclusivos de fixtures B/T; não definem as versões de uma entrega. O booleano faz parte da identidade do pedido. Ausência do campo em clientes antigos equivale a false.

Novos endpoints ficam sob `/api/agent/update-v3/`: `action`, `claim`, `manifest`, `signature`, `package`, `report`. Cada resposta/download fica preso a host autenticado, operação, tentativa e hash previstos na rota. A API V1 e seu ACK continuam byte/shape compatíveis com o cliente antigo; não acrescentar campos obrigatórios a manifests V1.

**Passos:**

- [x] Implementar e testar os formatos para pacote completo, catálogo e perfis, com assinatura/verificação da chave já confiável e requisitos explícitos de Java, plataforma e protocolo.
- [x] Gerar bridges por versão/hash/perfil conhecido, com baseline original embutida e prova de build reproduzível. Não aceitar origem desconhecida só porque declara a mesma versão.
- [x] Impor limites de 64 MiB transferidos, 128 MiB expandidos e 256 arquivos ao bundle, e 32 MiB ao bridge V1. Testar duplicidade, links, arquivos especiais, caminhos absolutos/`..` e divergências de tamanho/CRC/hash.
- [x] Separar defaults e código de identidade/configuração/CA. Falhar o build se um bundle completo contiver configuração ativa ou material privado.
- [x] Fixar a rota e os hashes no momento de criar a operação; downloads posteriores não consultam uma versão variável ou outra origem HTTP.
- [x] Documentar códigos de resultado, expiração de autorização, regras de retry e evolução de schema. Contratos compartilhados entre as frentes; commits de contrato precedem os commits de produto na entrega.

**Aceite:**

```sh
python3 -B tests/agent-bundle-signing-test.py
/bin/sh ops/test-agent-migration.sh --suite contracts
```

O modo `contracts` executa `AgentBundleManifestTest` e `AgentUpdateRoutesTest`. Deve recusar bundle válido para outra operação/perfil, downgrade arbitrário, assinatura/pin incorretos e schema não suportado.

## WF2 — Atualizador completo, recuperação e limpeza

**Proprietário:** frente A. Não editar arquivos de servidor/interface.

**Arquivos:**

- Criar `collector/AgentMigrationBootstrap.java`, `collector/AgentBundleInstaller.java`, `collector/AgentBundleRecovery.java`, `collector/AgentInstallLayout.java`, `collector/AgentBundleTransport.java`.
- Integrar `collector/AssessmentCollector.java`, `AgentUpdateProbe.java`, `AgentTransport.java` e `AgentTool.java`: início da continuação pelo bridge, probes legados compatíveis, transporte V3 limitado à origem autenticada e comando de diagnóstico. O verificador original de fixture permanece imutável.
- Criar `collector/update-bootstrap.sh`: entrada fixa mínima, recuperação e escolha de manager validado.
- Modificar `collector/update-install.sh`, `update-local.sh`, `update-service.sh`, `root-system-install.sh`, `install.sh`, `root-service.sh`, `root-trust.sh`, `launcher-common.sh`, `poll.sh`, `run-once.sh`, `agent-tool.sh`, `lifecycle-lock.sh`.
- Criados `tests/AgentBundleInstallerTest.java` (recuperação, I/O, limpeza e evolução), `tests/AgentV3UninstallInventoryTest.java` e `tests/agent-bundle-service-test.py` (adapters declarados).
- Estender testes existentes de instalação, serviço, migração, Java protegido e remoção.

**Passos:**

- [x] Introduzir layout de releases/managers com ponteiros JSON e separação explícita entre código e estado. Leitura de ponteiros aceita somente IDs derivados de manifestos validados, sem symlinks nem `source` de dados remotos.
- [x] Implementar transporte de bundle com limites novos e allowlist dos endpoints V3, sem redirecionamento nem reutilização de credenciais em outra origem. Preservar a interface V1 usada pelo bridge e o comportamento dos clientes antigos.
- [x] Manter entradas fixas para serviço e diagnóstico. Implementar `sudo /bin/sh /opt/java-assessment-collector/agent-tool.sh version`, consultando a release ativa e o manager, para evitar leitura de versão a partir de um JAR legado que ainda aguarda limpeza.
- [x] Preservar configuração, installation-id, confiança apontada por `tlsCaFile`, Java selecionado, logs úteis e spool. Corrigir reinstalação de uma identidade existente para não sobrescrever a CA com a do ZIP genérico.
- [x] Instalar serviço de recuperação separado em systemd e supervisor/init próprio em SysV. Fazer recuperação preceder polling no boot.
- [x] Preparar candidato e recuperação em filesystem compatível com as trocas; verificar espaço, permissões e hashes antes de pausar o coletor.
- [x] Implementar o journal e todas as fronteiras duráveis definidas no desenho, com sequência monotônica, retomada idempotente e tratamento explícito de journal inválido.
- [x] Adquirir o lock global antes da troca; aguardar a saída dos locks/processos do coletor, sem sinalizar JVMs de aplicações.
- [x] Promover código e manager com fallback independente. Falha de serviço/probe restaura código, helper e unidades anteriores; falha apenas de rede mantém confirmação pendente.
- [x] Inventariar propriedade e implementar limpeza repetível: ativo + anterior funcional, dois managers, referências vivas/journal protegidas, um falho até sete dias e últimos dez resumos sanitizados.
- [x] Recusar limpeza de arquivos desconhecidos/alterados, runtime Java, confiança/configuração e diretórios externos. Registrar limpeza pendente sem desfazer sucesso.

**Aceite:**

```sh
/bin/sh ops/test-agent-migration.sh --suite lifecycle
python3 -B tests/agent-bundle-service-test.py
python3 -B tests/root-migration-test.py
python3 -B tests/root-java-discovery-test.py
```

O modo `lifecycle` executa `AgentBundleInstallerTest` com injeção de falha em cada persistência/rename e compara hashes de dados preservados. Provas de systemd/SysV e reboot em Linux real continuam obrigatórias em WF6; mocks não as substituem.

## WF3 — Operação lógica, migração e autorização no dashboard

**Proprietário:** frente B. Proprietário dos hooks compartilhados de `AssessmentServer`, `AgentUpdateRegistry`, `InventoryStore` e `WorkRequests`; integrar as chamadas da frente C por contrato.

**Arquivos:**

- Criar `server/AgentUpdateCoordinator.java`: pai, rota fixada, etapas, retries limitados e sucesso final.
- Modificar `server/AgentUpdateCatalog.java`, `AgentUpdateRegistry.java`, `AssessmentServer.java`, `InventoryStore.java`, `WorkRequests.java`.
- Criar `tests/AgentUpdateMigrationHttpTest.java`, `tests/AgentUpdateCoordinatorTest.java`.
- Modificar fixtures de release/publicação para recusar substituição do dashboard durante uma operação pai ocupada.

**Persistência:**

- `agent_update_operations`: `id`, `host_id`, `batch_id`, `ordinal`, `status`, `payload`; uma operação lógica por host no lote.
- `agent_update_steps`: `id`, `operation_id`, `ordinal`, `kind`, `request_id`, `status`, `payload`; V1, bootstrap e bundle final são etapas internas.
- Registros V1 existentes continuam disponíveis; operações históricas não passam a ser migrações ativas.
- Criar índices de operações por host/status e lote/ordinal. Toda escrita usa a transação SQLite serializada existente.

**Passos:**

- [x] Criar migração aditiva/reexecutável das tabelas e mapear histórico atual sem criar ações ou coletas retroativas.
- [x] Fazer prévia distinguir rota direta, migração conhecida com verificação local pendente e intervenção necessária. Validar mínimo 1.5.12, identidade, conflitos e requisitos.
- [x] Persistir seleção, opção de coleta, rota, artefatos, perfis e prazo no pedido. Mesmo requestKey com qualquer intenção diferente retorna 409.
- [x] Usar subpedidos legados isolados por máquina/perfil, preservando endpoints, ordem e ACK que o worker original espera. Não deixar falha de um perfil cancelar o lote pai como se fosse falha final.
- [x] Tentar o próximo perfil somente para incompatibilidade de suporte comprovada antes da troca; não fazer retry cego de assinatura/probe/serviço.
- [x] Manter o pai ocupado durante todo o bridge. Polling, coleta manual/agendada, nova atualização, remoção e avanço de lote consultam o pai, mesmo depois de o filho V1 terminar.
- [x] Validar autorização ativa em claim, downloads e antes da ativação. Retomada não concede autorização infinita; pedido expirado/revogado conserva recuperação e estado verificável.
- [x] Exigir confirmação final de T + bundle + manager/capacidade + heartbeat posterior ao claim; heartbeat B não conclui a operação.
- [x] Gravar resultado final e habilitação da intenção de coleta na mesma transação; report/ACK repetido retorna a mesma operação e vínculo.
- [x] Adaptar Work requests, aviso de agentes e guardas de publicação para pais ocupados e etapas internas. Não expor tokens/configuração em respostas ou eventos.

**Aceite:**

```sh
/bin/sh ops/test-agent-migration.sh --suite server
```

O modo `server` executa `AgentUpdateCoordinatorTest`, `AgentUpdateMigrationHttpTest` e as regressões `AgentUpdateHttpTest`, `WorkRequestsHttpTest`, `DashboardVersionHttpTest`. Incluir reinício do servidor entre etapas, perda de ACK, replay, escopo/CSRF/Leitura, revogação, conflitos e rota imutável.

## WF4 — Assessment posterior idempotente e carga controlada

**Proprietário:** frente C. Editar o coordenador e seus testes; frente B integra hooks e schema nos arquivos compartilhados.

**Arquivos:**

- Criar `server/PostUpdateAssessments.java`.
- Criar `tests/PostUpdateAssessmentsTest.java`, `tests/PostUpdateAssessmentHttpTest.java`.
- Hooks por WF3 em `InventoryStore`, `AssessmentServer`, `AgentUpdateCoordinator` e `WorkRequests`.

**Persistência:**

```sql
CREATE TABLE agent_update_assessments (
  operation_id TEXT PRIMARY KEY,
  host_id TEXT NOT NULL,
  batch_id TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  state TEXT NOT NULL,
  job_id TEXT,
  not_before INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL,
  payload TEXT NOT NULL
);
CREATE INDEX agent_update_assessments_ready
  ON agent_update_assessments(state, not_before);
CREATE INDEX agent_update_assessments_host
  ON agent_update_assessments(host_id, state);
```

Na implementação, envolver `CREATE` no mecanismo reexecutável de migração existente. `payload` mantém resumo e links sanitizados, inclusive quando detalhes antigos de job forem removidos.

**Passos:**

- [x] Criar intenção por operação se a opção estiver true. Não criar intenção em histórico antigo e não habilitar job durante bridge.
- [x] Habilitar intenção na confirmação final transacional de WF3; dispatcher executa na manutenção/poll antes do agendamento normal.
- [x] Reutilizar job QUEUED cujo lease ocorrerá após T ou criar apenas um job `post-update`, ligando ambos atomicamente. Coleta que iniciou antes da confirmação não conta como coleta posterior.
- [x] Resolver configuração efetiva atual no lease, inclusive quando o agendamento estiver desligado; preservar janela/frequência/slot das coletas agendadas.
- [x] Aplicar uma coleta vinculada por ambiente e duas globais, dez segundos entre inícios por ambiente, com controle durável de autorização do lease. Não dormir segurando a transação SQLite.
- [x] Expirar após 24h a intenção que não iniciou; novas atualizações suspendem sua entrega e vinculam/substituem a intenção anterior pela coleta após a versão mais recente. Revogação/remoção cancela a intenção.
- [x] Manter sucesso do upgrade independente do resultado da coleta. Retry de report, reinício ou falha de resposta não cria outro job; repetir coleta exige ação explícita.
- [x] Preservar estado/resumo da relação após `trimJobs`; expor links bidirecionais e acompanhar relações pendentes mesmo com upgrade concluído.

**Aceite:**

```sh
/bin/sh ops/test-agent-migration.sh --suite assessment
```

O modo `assessment` executa `PostUpdateAssessmentsTest` e `PostUpdateAssessmentHttpTest`: true/false/omissão/inválido; bridge; report repetido; ACK perdido; reinício; dois pedidos simultâneos; job prévio; mudança de escopo; schedule desligado; throttle entre lotes/ambientes; lease expirado; rollback; revogação; nova versão antes do dispatch; retenção de histórico.

## WF5 — Interface e acompanhamento

**Arquivos:** `web/agent-updates.js`, `web/agent-updates.css`, `web/work-requests.js`, `web/work-requests.css`; novas fixtures/testes `tests/agent-migration-browser.cjs`, `tests/post-update-assessment-browser.cjs` e `tests/AgentMigrationUiFixture.java`.

**Passos:**

- [x] Exibir opção de assessment marcada nos novos pedidos; congelar booleano junto do payload após submissão e preservar escolha durante refresh/seleção/retry.
- [x] Mostrar por máquina o caminho direto/migração/intervenção, com motivo. Compatibilidade local ainda não verificada deve ficar explícita.
- [x] Apresentar etapas internas no mesmo Work request, mantendo T como alvo. B aparece apenas como etapa de transição, sem sucesso final antecipado.
- [x] Mostrar seção de assessment com estado e link; no job, mostrar origem e link para a atualização. Falha/parcial de coleta mantém “Atualização concluída”.
- [x] Manter acompanhamento de coletas vinculadas pendentes, limites de linhas/eventos, acessibilidade e layout desktop/mobile. Leitura não recebe ações administrativas.

**Aceite:**

```sh
node tests/agent-migration-browser.cjs
node tests/post-update-assessment-browser.cjs
node tests/agent-release-notice.test.cjs
node tests/interface-refresh.test.cjs
```

Usar Chrome/Playwright local e a fixture HTTP/SQLite; restringir rede à origem de teste. Verificar escolha padrão, desmarcar, refresh, perda de resposta, etapas, links, retry só da coleta, permissões, logout e ausência de erros JavaScript.

## WF6 — Integração, recuperação e evolução posterior

**Arquivos novos:** `tests/AgentFullUpdateEndToEndTest.java`, `tests/AgentV3UninstallInventoryTest.java`, `docs/validation/full-agent-update.md`. Estender `ops/test-source.sh`, build e testes do instalador para incluir as suítes novas.

- [ ] Executar 1.5.12 original → B → T, incluindo serviços e helper originais. Comparar hashes de identidade, configuração, CA, Java e resultados pendentes antes/depois.
- [ ] Testar instalação nova diretamente em T e atualização completa T → U, em que U muda JAR, pelo menos um script/biblioteca e o manager. Isso comprova evolução posterior, além da ponte inicial.
- [ ] Injetar interrupção em cada fronteira durável: downloads, ACK, cada rename, parada, ativação, handoff, confirmação e limpeza. Reabrir a transação e comprovar anterior íntegro ou candidato funcional.
- [ ] Simular rede indisponível, disco cheio, escrita/sync com falha, pacote corrompido, serviço que não inicia, coleta em andamento, uninstall concorrente, customizações desconhecidas e arquivos/links inesperados.
- [ ] Executar serviços e reboot em Linux isolado com systemd e SysV; validar Java 6 e Java moderno. Comparar processos de aplicação e provar que nenhum foi parado/sinalizado.
- [ ] Medir memória, espaço de pico, tempo de pausa e consumo sem tarefas. Conservar o heap do coletor em 96 MiB nesta geração e impedir JVM residente de atualização quando estiver ociosa.
- [x] Confirmar uma coleta pós-update por intenção, escopo preservado e throughput limitado entre ambientes; falha da coleta não restaura binário.
- [x] Registrar comandos, versões e resultados locais, com adapters identificados. Não rotular testes simulados como prova de reboot/energia ou de 32 bits.

**Aceite:**

```sh
/bin/sh ops/test-agent-migration.sh --suite integration
/bin/sh ops/test-agent-migration.sh --suite lifecycle
/bin/sh ops/test-source.sh
python3 -B tests/agent-update-installer-test.py
python3 -B tests/dashboard_installer_test.py
```

O modo `integration` executa `AgentFullUpdateEndToEndTest` com fixtures reais e pin de teste. A evidência de Linux real identifica separadamente ambiente, serviço e JVM. Qualquer falha de preservação/recuperação mantém WF7 fechado.

## WF7 — Canários 1.5.12 e entrega

**Arquivo:** criar `docs/runbooks/agent-update-1512-canary.md` com comandos específicos à release reservada, diagnóstico sem segredos, comparação de hashes e procedimento de retomada. Dados e endereços privados ficam fora do Git.

- [ ] Conferir em ambas as máquinas o coletor 1.5.12, helper real, capability, configuração, CA, modo de serviço, espaço e ausência de operações concorrentes. Identificar as máquinas pelos IDs do dashboard; não pressupor que versão do helper seja a do JAR.
- [ ] Fazer backup/snapshot recuperável do canário e registrar o estado esperado sem revelar token ou senha. Reservar/signar artefatos de produto somente após revisão e testes; não assinar fixtures com a chave real.
- [ ] Publicar primeiro o dashboard preparado e conferir saúde/versão e catálogo. Não criar automaticamente pedidos de atualização nas duas máquinas.
- [ ] Canário A: solicitar migração com opção marcada; acompanhar todo o Work request, conferir a saída efetiva da 1.5.12, manager final, identidade/ambiente/CA/Java e exatamente uma coleta na versão final.
- [ ] Confirmar estabilidade por pelo menos dois contatos de polling após a coleta, retomada do agendamento e ausência de processos/arquivos em uso removidos pela limpeza.
- [ ] Canário B, somente após A: preparar uma coleta QUEUED sem a iniciar e provar seu aproveitamento depois da atualização, sem coleta extra. Exercitar opção desmarcada em uma atualização posterior de teste; não depender de falha de rede para validar a interface.
- [ ] Usar uma segunda release completa que altere suporte/manager e atualizar novamente pelo painel, uma máquina por vez. Conferir limpeza das versões excedentes e recuperação preservada.
- [ ] Entregar notas, limites validados, rota por perfil, backups, indicadores de sucesso e comando de publicação. Incluir o aviso obrigatório para atualizar também os agentes. Somente declarar migração 1.5.12 testada após evidência dos canários.

**Regra operacional:** o usuário já autorizou a implementação; este plano não pede outra autorização genérica. Se a ferramenta continuar bloqueando shell remoto, preparar os comandos e solicitar apenas a execução necessária no Terminal do usuário. Não contornar um bloqueio de ferramenta por outro wrapper.

## Critério final de pronto

- [ ] As duas instalações 1.5.12 evoluem pelo painel sem recadastro nem SCP para a migração normal.
- [ ] Uma atualização seguinte altera suporte e manager pelo mesmo mecanismo, sem nova instalação manual.
- [ ] Falhas/interrupções preservam recuperação, configuração, confiança e aplicações do cliente.
- [ ] Somente componentes conhecidos e não referenciados são removidos; ativo e anterior funcional permanecem.
- [ ] A opção de assessment funciona uma única vez após confirmação final e mostra resultado separado no histórico.
- [ ] Documentação e mensagem de entrega refletem exatamente os ambientes testados e as exceções que ainda exigem reparo.

## Revisão do plano

Foram confrontados o worker original, os scripts atuais, os locks, o ACK/cleanup legado, a retenção de jobs, os gates de polling e o catálogo atual. A separação de workflows evita editar simultaneamente os mesmos arquivos comuns. A principal prova pendente é o ciclo completo da migração original, definido como primeiro gate; as análises de viabilidade feitas para este plano não o substituem.


## Registro da execução local

- Baselines públicas: 10 ZIPs verificados pelos recibos; seis origens >=1.5.12 e dois grupos distintos de arquivos de suporte. Helpers anteriores só são aceitos se seus hashes vierem de ZIPs conhecidos e pin idêntico.
- Worker original 1.5.12: 1.338 verificações. O trecho de flock/serviços reais de WF0 permanece aberto; adapters não o encerram.
- Lifecycle: 4.362 verificações em heap de 96 MiB; instalação nova, T→U com JAR/manager/script/biblioteca diferentes e bootstrap preservado, interrupções em dez fases, escrita/fsync/rename/cópia e disco insuficiente.
- Desinstalação: 2.896 verificações; CA de 48 KiB, remoção parcial, intent atômico e shim explícito após primeiro rollback.
- Scripts: 30 verificações com caminhos/serviços de teste. Backend e navegadores usam HTTP/SQLite reais em loopback, sem execução de serviços Linux.
- Empacotamento público: 13 testes; assembly offline: 16; publicação: 63. As chaves das fixtures são exclusivas de teste.
- A regressão de fontes passou. Os resultados consolidados e os limites ficam em `docs/validation/full-agent-update.md`.

Os passos de WF2 marcados referem-se à implementação e às provas locais; instalação/serviços/reboot no SO real continuam em WF6/WF7. Não publicar nem declarar compatibilidade de produção até fechar esses gates. O commit de contratos é separado para revisão; desenvolvimento foi coordenado pelas frentes antes da consolidação final dos commits.
