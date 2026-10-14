# Atualização completa do agente a partir da 1.5.12

## Objetivo e escopo

Solicitação retomada pelo usuário em 14/10/2026: atualizar pelo dashboard agentes a partir da versão 1.5.12, incluindo os componentes de suporte, preservar o necessário na máquina e oferecer um assessment após a atualização. Há duas máquinas ainda em 1.5.12 para canários posteriores. Esta etapa registra o desenho e o plano de execução; o mecanismo descrito aqui ainda não está implementado nem validado nas máquinas.

O resultado esperado é uma operação administrativa única no painel, por máquina ou ambiente. O painel coordena as etapas de transição necessárias, mostra o progresso e só declara sucesso quando o coletor e o atualizador finais funcionam. Futuras releases declaram seus requisitos e arquivos; uma mudança normal de scripts ou bibliotecas não deve obrigar a distribuição manual de um ZIP.

Compatibilidade mínima significa uma instalação conhecida de agente >= 1.5.12 com atualizador root funcional, identidade ativa, confiança HTTPS e ferramentas locais necessárias. A versão exibida isoladamente não comprova esses requisitos. Instalações alteradas, sem atualizador, sem privilégios ou sem comunicação precisam de diagnóstico explícito. Versões anteriores à 1.5.12 não entram nesta entrega.

## Fatos que condicionam a migração

- O atualizador original 1.5.12 aceita somente o manifesto V1 com suas oito chaves, `collector-jar-v1`, `minUpdater=1`, assinatura RSA-3072/SHA-256 e hashes exatos do suporte instalado.
- O payload deve ser um JAR Java 6, com versão superior à instalada e até 32 MiB. Não é possível enviar diretamente o novo formato completo ao verificador antigo.
- O worker executa a partir de `helper.jar`, separado do coletor. Trocar `collector.jar` não troca esse worker.
- O worker segura o lock de ciclo de vida. O polling pausa enquanto há journal de atualização.
- O worker só limpa seu estado depois do ACK terminal correto. Sua limpeza também exclui `backup.jar`; a recuperação nova não pode depender desse arquivo continuar existindo.
- A 1.5.12 não informa hashes completos no heartbeat. A prévia precisa distinguir rota disponível de compatibilidade já confirmada localmente.
- O catálogo atual oferece somente a versão corrente. São necessários artefatos intermediários imutáveis e uma operação lógica acima dos subpedidos legados.

## Decisões para a primeira geração

1. Adotar pacotes completos assinados e diretórios de código versionados, separados dos dados persistentes.
2. Instalar um bootstrap/recuperador independente do coletor e manter duas gerações verificadas do manager de atualização.
3. Migrar 1.5.12 e os demais perfis históricos conhecidos por bridge compatível com o verificador original. Não reduzir verificações nem anunciar genericamente `>=1.5.12` como suficiente.
4. Manter uma única operação visível com alvo final. O bridge é uma etapa interna, com versão própria; não encerra a operação nem dispara assessment.
5. Preservar formato de configuração, identidade, CA e seleção de Java nesta entrega. Não distribuir nem remover JVM automaticamente.
6. Reter o código ativo e uma versão anterior funcional; limpar somente componentes cuja propriedade e ausência de uso forem comprovadas.
7. Oferecer `Executar novo assessment após a atualização`, marcado por padrão nos novos pedidos feitos pela interface. Pedidos históricos não ganham coletas retroativas.

## Layout e propriedade

```text
/opt/java-assessment-collector/
  conf/                              configuração e identidade existentes
  trust/                             CA existente, conforme tlsCaFile
  collector-spool/                    filas, resultados não entregues e snapshot
  logs/                              diagnóstico com retenção limitada
  releases/<releaseId>/               JAR, scripts e bibliotecas de uma release
  current.json                       ponteiro textual durável para código validado
  root-service.sh                    entrada administrativa fixa
  agent-tool.sh                      entrada de diagnóstico que resolve a release ativa

/opt/java-assessment-update/
  bootstrap/                         inicialização e recuperação independentes
  managers/<managerId>/               manager e dependências verificados
  state/current-manager.json         geração ativa e geração de recuperação
  state/transaction.json             journal da operação local em andamento
  transactions/<operationId>/         manifesto, pacote e staging protegidos
  history/                           resumos sanitizados limitados
```

`releaseId` e `managerId` são identificadores derivados do manifesto assinado e de seu hash; não são caminhos aceitos da API. Os ponteiros são JSON com schema fixo, nunca shell executável ou symlinks. Diretórios e arquivos operacionais continuam root, sem escrita por grupo/outros. Os wrappers distinguem diretório de código e diretório de estado, preservando os caminhos de configuração e spool existentes.

Preservar byte a byte `conf/collector.properties`, `conf/installation-id`, a CA realmente apontada por `tlsCaFile`, seleção em `conf/java.path`, dados de cadastro, pedidos e resultados pendentes. Se a CA estiver em caminho externo, apenas verificar sua existência e confiança; não movê-la nem substituí-la. O Java privado em `/opt/java-assessment-runtime` e os Javas do sistema/aplicações ficam fora da limpeza. Logs recentes e snapshot continuam úteis após trocar código.

Os marcadores operacionais de capacidade e ponteiros podem mudar como parte da transação. Não confundir esses arquivos controlados com a configuração do cliente. Pacotes remotos não incluem tokens, identidade, configuração ativa ou uma CA que sobrescreva a instalada.

Fornecer `sudo /bin/sh /opt/java-assessment-collector/agent-tool.sh version` para consultar versão, release ativa e protocolo do manager, sem segredos. O diagnóstico deve resolver o ponteiro validado; um JAR legado remanescente na raiz não pode ser a fonte da versão efetivamente em execução. O runbook substituirá a antiga leitura direta do manifesto desse JAR pela entrada de diagnóstico.

## Artefatos assinados e evolução futura

Manter três tipos distintos de artefato: bridge legado, bundle completo e catálogo de rotas. Cada operação fixa os hashes de todos os artefatos que usará; atualizar a página ou preparar outra release não muda seu conteúdo.

O manifesto completo declara `schema`, tipo, versão do coletor, protocolo exigido do bootstrap/manager, requisitos de Java/plataforma, tamanho/hash do pacote, inventário de arquivos e modos permitidos, perfil de origem e adaptações locais tipadas. O inventário cobre cada executável e biblioteca. Defaults podem vir em arquivos de exemplo; não são aplicados automaticamente à configuração existente.

O formato inicial usa no máximo 64 MiB transferidos, 128 MiB expandidos e 256 arquivos. Validar assinatura, tamanhos, hashes, nomes duplicados, caminhos absolutos/`..`, links e arquivos especiais antes de executar candidatos. Só operações locais fixas podem alterar os serviços de nomes conhecidos. Não aceitar comandos shell, caminhos de destino ou URLs arbitrárias enviados pelo dashboard.

A confiança parte da chave pública já instalada. Rotação da chave fica fora desta entrega. O bootstrap tem protocolo estável e funções pequenas; uma futura mudança desse protocolo exige uma rota assinada explícita, mantendo um recuperador funcional durante a migração. Compatibilidade futura será por capacidades/requisitos documentados, sem exigir que todo suporte antigo seja idêntico ao da última release.

## Migração dos perfis antigos

Chamar de `B` a versão interna do bridge e de `T` a versão final: `origem < B < T`. Reservar ambas no processo de release, sem reutilizar números ou alterar artefatos publicados. Somente T é o alvo final apresentado ao operador.

1. Criar operação pai com seleção e intenção de assessment. Fixar rota e lista limitada de perfis de suporte históricos assinados. A prévia informa que a verificação local está pendente.
2. Criar subpedido V1 de uma única máquina para B, vinculado ao pai. Servir manifesto com exatamente o schema/minUpdater/hashes aceitos por aquele perfil legado.
3. O worker original verifica e instala B. Assinatura inválida, probe/serviço com falha e diagnóstico desconhecido encerram a tentativa; apenas `INCOMPATIBLE_ASSETS` comprovadamente anterior à troca permite tentar outro perfil conhecido, com limite igual à lista da rota.
4. O servidor recebe `SUCCEEDED` de B e devolve o ACK exato esperado pelo worker antigo. O pai permanece ocupado, impedindo assessment, agendamento normal e avanço indevido do lote.
5. Após o journal legado desaparecer e o lock ser efetivamente liberado, o ciclo normal do bridge instala os arquivos de recuperação assinados e solicita o serviço independente. O ciclo do coletor sai antes de esse serviço pausar o coletor.
6. O recuperador prepara o bundle T e o novo manager, adquire o lock global, pausa somente o coletor, ativa e verifica o conjunto completo.
7. Confirmar a versão T, o hash do bundle, a geração/capacidade do manager, funcionamento do serviço e novo heartbeat autenticado. Somente essa confirmação encerra o pai e habilita sua coleta opcional.

O bridge deve preservar a possibilidade de voltar à origem mesmo após a limpeza do worker antigo. O artefato de bridge por versão/perfil inclui o JAR original conhecido como recurso assinado de recuperação. O probe inicial verifica em leitura a baseline de origem; probes após a troca reconhecem o próprio bridge e a transição correspondente. Nenhum probe instala código, muda configuração ou para serviços. O bootstrap materializa a baseline de recuperação e confere os arquivos de suporte conhecidos antes de qualquer mutação. Esta estratégia faz parte da prova obrigatória do workflow 0.

O bootstrap não chama o instalador atual dentro do worker antigo, não sobrescreve o helper em execução e não usa um `nohup` no mesmo cgroup do coletor como garantia de sobrevivência. Em systemd, roda em serviço separado; em SysV, usa entrada/supervisor próprios, com recuperação no boot.

Se a origem ou os serviços tiverem customizações desconhecidas, preservar a instalação e informar o motivo. Não tentar reconstruir confiança aceitando arbitrariamente os hashes relatados por uma máquina.

## Transação, recuperação e limites

Estados locais: `ACCEPTED -> DOWNLOADING -> VERIFIED -> PREPARED -> QUIESCING -> ACTIVATING -> LOCAL_VERIFYING -> REMOTE_CONFIRMING -> COMMITTED -> CLEANING -> DONE`. Exceções: `ROLLING_BACK`, `ROLLED_BACK`, `RECOVERY_REQUIRED`.

O journal contém schema, sequência monotônica, operação, host, hashes anterior/pretendido, manager anterior/pretendido, modo do serviço e ações locais necessárias. Não contém token ou configuração completa. Cada fronteira de mutação exige gravação temporária, sync dos bytes, rename e sincronização local antes de continuar. A durabilidade precisa ser validada em Linux real; APIs Java 6 e mocks não bastam para comprovar comportamento em queda de energia.

O mesmo lock `/opt/java-assessment-lifecycle.lock` arbitra instalador, atualização, remoção e recuperação. Não iniciar troca com coleta/remoção concorrente. Aguardar o ciclo limitado do coletor e atuar somente em processos comprovadamente do produto. Recuperação antecede o coletor no boot e usa componentes anteriores verificados, sem depender do coletor/manager candidato.

Falha de código/serviço restaura a baseline anterior registrada na transação. Falha apenas de rede após funcionamento local mantém confirmação pendente, sem alternar versões repetidamente. Journal contraditório produz `RECOVERY_REQUIRED`, mantendo cópias para diagnóstico. Verificar espaço para candidato, staging, recuperação e journal antes de pausar qualquer serviço; falta de espaço nunca autoriza excluir a única recuperação.

Atualização do próprio manager usa geração separada, probe, handoff confirmado e fallback anterior. A cópia que recupera a transação não é sobrescrita pela transação em execução. O sucesso final exige o manager correto, e não apenas a versão declarada pelo coletor.

## Limpeza

- Manter release ativa e uma anterior validada; manager ativo e manager de recuperação.
- Preservar todos os arquivos referenciados por journal ativo, ponteiros, processos vivos ou operação não confirmada, ainda que ultrapassem a retenção normal.
- Após confirmação/ACK, remover downloads, staging e versões excedentes somente pelo inventário de propriedade assinado e registro local de instalação.
- Conservar no máximo um candidato falho para diagnóstico por sete dias, desde que não esteja referenciado por recuperação em andamento.
- Reter resumos das últimas dez transações, com até 64 KiB de diagnóstico sanitizado em cada uma.
- Arquivos inesperados ou alterados permanecem preservados, com indicação de limpeza pendente. Falha de limpeza não muda uma atualização bem-sucedida para falha.
- Nunca executar limpeza por prefixo de versão em `/opt`, `/home` ou diretórios de aplicações. Nunca apagar configuração, confiança, JVM ou estado pendente para liberar espaço.

## Assessment após atualizar

Adicionar booleano estrito `runAssessmentAfterUpdate` ao pedido. Interface envia explicitamente `true` por padrão; API antiga que omite o campo e registros anteriores equivalem a `false`. A opção participa da comparação de idempotência do `requestKey` e fica congelada nos reenvios.

Criar uma intenção durável por operação/máquina, sem job executável durante o bridge. Sucesso final e habilitação da intenção são gravados na mesma transação SQLite. Repetição de report/ACK e reinício devem recuperar a mesma relação.

O dispatcher aproveita um job QUEUED que ainda será entregue após o alvo final ou cria um único job com origem `post-update`. Uma coleta que já estava rodando antes da confirmação não atende ao requisito: esperar terminar e, então, criar a coleta posterior sem duplicidade. Resolver escopo e limites atuais no lease, mesmo com o agendamento automático desligado; não alterar frequência, janela ou slot de agendamento.

Limitar inicialmente coletas vinculadas a uma por ambiente e duas globais, com dez segundos entre novos inícios por ambiente, usando `not_before` e controle durável. São limites das coletas pós-update; não representam limitação geral das coletas manuais que já existem. A próxima atualização do lote pode prosseguir enquanto a coleta anterior roda, respeitando esses limites. Uma máquina sem contato não bloqueia indefinidamente a coleta das demais; aplicar leases/timeouts existentes e expiração explícita de 24 horas para intenção ainda não iniciada.

Se uma atualização posterior ocupar a mesma máquina antes do dispatch, suspender a intenção anterior e associá-la à coleta feita após a versão final mais recente; marcar a relação anterior como substituída/vinculada, sem coletar durante transição. Revogação/remoção cancela a intenção. Falha, rollback, cancelamento ou expiração da atualização não cria assessment automático.

Assessment parcial/falho/expirado não desfaz a atualização nem cancela atualizações de outras máquinas. Repetir uma coleta é ação explícita, registrada como nova tentativa. Work requests mantém links de ida e volta, estado independente e resumo da coleta mesmo após a retenção remover detalhes antigos.

## Interface

O popup inclui a opção marcada e explica: “Após confirmar a nova versão, executar uma coleta com o escopo e os limites configurados”. Preservar seleção em refresh e reenvio após perda de resposta.

A prévia distingue atualização direta, migração automática e intervenção necessária, com motivo verificável. Migração pendente de perfil não aparece como compatibilidade comprovada. A operação mostra preparação, transição, instalação, verificação e resultado final. Uma falha de coleta aparece na seção “Assessment após a atualização”, mantendo “Atualização concluída”.

Usuários de leitura consultam resultados conforme as permissões existentes; apenas administradores criam/cancelam atualizações. O acompanhamento de coletas vinculadas pendentes continua visível depois de o update atingir sucesso, sem falsificar seu estado.

## Provas e liberação

A primeira prova usa os JARs e scripts originais 1.5.12 em cópias isoladas, com chave de teste exclusiva. Precisa exercitar o ciclo completo, ACK perdido, limpeza, liberação do lock e handoff, não somente chamar `verify()`. Não assinar código de teste com a chave de produção.

Depois, testar pacote completo, alteração real de suporte, retomada/reboot, arquivos inválidos, permissões, disco cheio, falhas de serviço e limites de coleta. Validar Linux systemd e SysV e execução Java 6/Java moderno. Linux/Java 32 bits permanece sem validação real, conforme a restrição atual.

Os dois canários 1.5.12 são usados em sequência, após os testes isolados. Conferir identidade/CA/configuração/Java e serviços antes/depois, uma única coleta vinculada, ausência de coleta no bridge e atualização futura que altere suporte. Uma segunda release completa de teste é obrigatória para comprovar evolução posterior à migração.

Não atualizar as duas máquinas de uma vez nem publicar uma release como compatível com 1.5.12 com base apenas no número de versão ou em testes no JDK 17.

## Limite desta entrega de planejamento

Este documento define os requisitos e decisões para implementação. Não é evidência de migração bem-sucedida. O plano de workflows associado registra arquivos, ordem, responsáveis, testes e gates. Nenhuma VM será alterada na elaboração do plano.
