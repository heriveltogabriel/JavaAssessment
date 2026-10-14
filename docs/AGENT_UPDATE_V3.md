# Atualização completa do agente

O fluxo V3 substitui código, scripts, bibliotecas e manager como uma geração assinada. A configuração e a identidade ficam fora do pacote. O dashboard mantém uma operação por máquina, incluindo a transição de agentes antigos e a coleta posterior.

Esta implementação ainda exige os gates de Linux e canários registrados em [validação](validation/full-agent-update.md). A versão de produto não foi incrementada nem publicada durante os testes.

## Caminhos de atualização

| Instalação | Caminho |
| --- | --- |
| Anterior a 1.5.12 | Instalador completo; nenhuma compatibilidade é inferida |
| A partir de 1.5.12, suporte e binários conhecidos, atualizador V1/V2 | Bridge assinado específico → limpeza/ACK original → recuperação independente → pacote final V3 |
| Atualizador V3 instalado | Pacote completo diretamente |
| Sem serviço root, suporte customizado ou perfil desconhecido | Reparação explícita pelo instalador completo |

A prévia identifica a migração como **verificação local pendente**. O número da versão identifica perfis candidatos; o atualizador antigo confere os arquivos e o probe confere o binário real antes da troca. Hashes e assinatura continuam obrigatórios.

O bridge tem número interno B, maior que as origens e menor que o destino T. O número exibido como alvo continua T. O sucesso de B não conclui o pedido nem permite uma coleta. B/T são reservados uma única vez; um bridge não é uma release de dashboard.

## Artefatos imutáveis

- `dist/agent-bundles/T/{manifest.json,manifest.sig,bundle.zip}`.
- `dist/agent-bridges/B/<hash-do-manifesto>/{manifest.json,manifest.sig,collector.jar}`.
- `dist/agent-update-routes/T/{routes.json,routes.sig}`.
- `dist/agent-update-public.der`: a chave já confiada pelos agentes.

Os formatos usam RSA-3072/SHA-256. O bridge conserva as oito chaves do manifesto V1 e seu ACK original. O catálogo fixa origem, hash do coletor, perfil de suporte, bridge e hash do manifesto final. Cada operação persiste seu catálogo e artefatos; uma release posterior não muda o seu destino.

O manifesto completo tem exatamente `schema`, `type`, `format`, `version`, `minUpdater`, `minJava`, `platform`, `bytes`, `sha256` e `files`. Nesta geração: schema 1, type `ASSESSMENT_AGENT_BUNDLE_V1`, format `agent-bundle-v1`, minUpdater 3, minJava 6 e platform `linux`.

Cada arquivo tem tamanho, SHA-256, modo 600/700 e papel: code, manager, bootstrap ou default. Há limites de 64 MiB transferidos, 128 MiB expandidos e 256 arquivos. O verificador compartilhado recusa links, arquivos especiais, duplicatas, caminhos inseguros e divergências de CRC/tamanho/hash. O JAR V1 continua limitado a 32 MiB e bytecode Java 6.

## Estado preservado e recuperação

A extração inicial pode ocorrer em qualquer pasta privada. A instalação root usa `/opt/java-assessment-collector`; o atualizador usa `/opt/java-assessment-update`.

| Área | Tratamento |
| --- | --- |
| Configuração, identidade, installation-id, CA indicada e Java selecionado | Preservados, com hashes conferidos antes da ativação |
| Spool, resultados pendentes e logs | Permanecem na área de estado |
| Releases e managers | Diretórios versionados e ponteiro JSON validado |
| Bootstrap/âncora e bibliotecas próprias | Recuperação independente, preservada entre atualizações |
| Código anterior funcional | Retido para restauração |
| Arquivos desconhecidos ou alterados | Preservados; impedem remoção automática do diretório |

O coletor que prepara a migração encerra seu ciclo. Um serviço separado espera sua saída, adquire o lock global, prepara recuperação e ativa a nova geração. O processo antigo não pode confirmar o manager que acabou de substituir: uma nova execução do manager promovido precisa conferir serviços e heartbeat final.

Falha local de candidato, manager ou serviço restaura a origem. Falha de rede depois da verificação local mantém a geração íntegra e o pedido aguardando confirmação. O journal permite retomada e repetição de ACK sem reinstalar. Autorização vencida não dá permissão ilimitada para baixar ou ativar código.

A limpeza usa inventários de propriedade e referências, preservando ativo, anterior funcional e recuperação. Não percorre diretórios de aplicações nem remove a JVM. A desinstalação V3 também usa plano durável e recusa conteúdo desconhecido.

O JAR no caminho raiz pode permanecer como artefato de recuperação. Depois da migração, consulte a release ativa pelo diagnóstico abaixo, em vez de inferir a versão apenas desse JAR legado.

Para diagnóstico após a instalação V3:

```sh
sudo /bin/sh /opt/java-assessment-collector/agent-tool.sh version
```

## API e confirmação

Endpoints autenticados: `/api/agent/update-v3/action`, `claim`, `manifest`, `signature`, `package` e `report`. O transporte fica preso à origem configurada, sem redirecionar credenciais. Não há endpoint de execução de comandos arbitrários.

A action contém `operationId`, `targetVersion`, `expiresMillis` e `signedPlanHash`. O claim próprio do bundle é separado do claim do bridge. Downloads e reports exigem esse claim; a resposta inclui hashes do bundle e manager. O agente compara a rota recebida com a rota persistida.

O report informa status, versão, hashes e diagnóstico. O sucesso exige versão T, capacidade `update-v3`, hashes de bundle/manager e heartbeat autenticado posterior ao claim V3. Report final repetido retorna o mesmo ACK; report conflitante é recusado. As etapas não retrocedem depois de confirmação pendente.

Diagnósticos como `MIGRATION_PROFILE_REQUIRED`, `IDENTITY_CHANGED`, `INVALID_BUNDLE`, `INSUFFICIENT_DISK`, `MANAGER_PROBE_FAILED` e `RECOVERY_REQUIRED` indicam a intervenção necessária sem divulgar tokens ou configuração.

## Assessment posterior

A opção **Executar assessment após atualizar** vem marcada na interface. Clientes antigos da API que omitem o campo conservam o comportamento anterior: false. O booleano participa da identidade do pedido; o mesmo requestKey com outra escolha é recusado.

A intenção é criada uma vez e habilitada somente após confirmar T. Ela aproveita um job QUEUED ainda não iniciado ou cria um job `post-update`, usando a configuração efetiva no lease. Funciona com agendamento desligado, sem consumir a janela do agendamento normal.

Há uma coleta vinculada por ambiente, duas globais e dez segundos entre inícios por ambiente. A intenção que não iniciar em 24 horas expira. Revogação/remoção cancela a intenção; uma atualização mais recente pode substituí-la. Repetições, reinícios e ACK perdido não criam outro job.

Work requests mostra separadamente a atualização e a coleta, com links nos dois sentidos. Falha ou resultado parcial da coleta mantém o sucesso da atualização. O vínculo e seu resumo permanecem quando os detalhes antigos do job forem removidos.

## Build e evolução

`ops/full_agent_release.py` prepara assets a partir do pacote genérico e de origens com checksums independentes. O bundle é gerado antes de ser embutido como `full-update/{manifest.json,manifest.sig,bundle.zip}` no instalador, evitando autorreferência. O instalador usa esses assets em uma inicialização local administrativa, sem criar pedidos falsos na API. Instalações V3 existentes usam o fluxo completo pelo painel; uma reinstalação administrativa que poderia desmontar esse layout é recusada antes de parar serviços.

`reserve_update_pair()` reserva B/T sob o mesmo lock. O builder operacional inclui catálogo, bridges e bundle no runtime. A publicação preserva catálogos anteriores e recusa operações de atualização ocupadas, inclusive pais sem filho V1 ativo.

Uma futura mudança de schema/bootstrap exige nova regra explícita de compatibilidade. Não basta aumentar o número da versão. Bibliotecas do bootstrap anterior permanecem independentes das bibliotecas novas. O build herda a âncora e suas bibliotecas do primeiro instalador V3 assinado entre as origens verificadas; não a substitui pela âncora de cada release nova.
