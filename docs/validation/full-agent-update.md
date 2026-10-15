# Evidências da atualização completa

Data: 2026-10-14. Implementação consolidada na branch `codex/full-agent-update-plan`. As primeiras provas abaixo foram executadas com constantes do produto em 2.0.5. Posteriormente foi preparado o candidato exclusivo de laboratório 2.0.9, descrito ao final; nenhuma publicação ou migração real foi executada pelo agente nesta preparação.

## Validações locais

| Prova | Resultado e limite |
| --- | --- |
| Worker original 1.5.12 | 1.338 verificações: binário original carregado isoladamente, manifesto/assinatura, ciclo, ACK perdido, limpeza e recuperação. Serviços/probes/locks do SO são adapters |
| Verificador de bundle | 75 verificações, assinatura e inventário estritos; compilação major 50 |
| Assinatura Python/Java | 60 verificações com chave RSA exclusiva de teste, sem ler chave privada de produção |
| Lifecycle e limpeza | 4.362 verificações em heap de 96 MiB: instalação nova, T→U, dez fases duráveis, escrita/fsync/rename/cópia, espaço insuficiente, rede/ACK e limpeza com referências vivas |
| Desinstalação V3 | 2.896 verificações; snapshots, shim após rollback inicial, intent atômico, CA externa de 48 KiB, remoção parcial e retomada |
| Scripts de serviço | 30 verificações com caminhos e processos/serviços adapters; sem JVM ociosa |
| Escolha de perfil | 109 verificações, worker original e dois perfis assinados, HTTP/SQLite reais; só INCOMPATIBLE_ASSETS antes da troca avança |
| Coordenador SQLite | Reinício/replay, perfil esgotado, cancelamento/revogação/expiração e catálogo inválido sem fallback |
| Migração HTTP | 58 verificações: servidor, autenticação, catálogo assinado e SQLite reais; bridge não conclui pai; confirmação final exige bundle/manager/capacidade/heartbeat |
| Regressões de atualização e histórico | AgentUpdateHttpTest: 149 verificações; WorkRequestsHttpTest: 183; DashboardVersionHttpTest passou |
| Coleta posterior | PostUpdateAssessmentsTest: 65; PostUpdateAssessmentHttpTest: 67. Idempotência, opção, throttle, lease, reinício e retenção |
| Integração completa | 21 verificações: worker original → JAR bridge real → pacote completo → manager promovido → API real → um assessment. A execução de serviços/probes e a identidade do processo promovido são adapters explícitos |
| Navegador | Contrato, API real V1 e API real V3; seleção/refresh/ACK repetido, etapas, vínculos, opt-out, leitor, desktop/mobile |
| Publicação do dashboard | 63 testes locais, incluindo pais de atualização ocupados sem filho V1 e preservação do histórico |

Os testes de filesystem e evolução executam o código real com arquivos e assinaturas reais; serviços, processos do manager e probes do SO usam adapters declarados. O teste integrado não substitui a execução dos serviços Linux. A regressão geral de fontes e a suíte integrada consolidada passaram, incluindo atualização anterior, descoberta/JDBC, permissões, CSV e Work requests.

## Como reproduzir

Use um JDK 17 e o ECJ 3.33.0 para compilar o produto com target Java 6. Os ZIPs originais devem corresponder aos hashes de `agent-migration-baselines.json`. As fixtures nunca devem ser instaladas em uma máquina real.

```sh
export JAVA_HOME=/caminho/do/jdk17
export ASSESSMENT_ECJ_JAR=/caminho/do/ecj-3.33.0.jar
python3 -B tests/build-agent-migration-fixtures.py --output tmp/agent-migration/fixture
python3 -B tests/build-agent-full-update-fixtures.py --bridge-version 2.0.10 --target-version 2.0.11 --future-version 2.0.12 --output tmp/full-update/reproduction-211-fixture
python3 -B tests/build-agent-profile-fixtures.py --fixture tmp/full-update/reproduction-211-fixture --output tmp/full-update/reproduction-211-profiles
/bin/sh ops/test-agent-migration.sh --suite all --fixture tmp/full-update/reproduction-211-fixture --profile-fixture tmp/full-update/reproduction-211-profiles
python3 -B tests/full-agent-release-test.py --fixture tmp/full-update/reproduction-211-fixture
python3 -B tests/full-update-installer-test.py --fixture tmp/full-update/reproduction-211-fixture
python3 -B tests/agent-bundle-service-test.py
```

O alvo da fixture precisa corresponder a `DashboardVersion.VERSION` da fonte conferida; os números acima correspondem ao candidato 2.0.11. Os destinos de fixture são imutáveis: se já existirem, use outra pasta e passe o mesmo caminho aos testes. O modo `all` usa separadamente a fixture original `tmp/agent-migration/fixture`. Evidências de execução ficam em `tmp/agent-migration/evidence/` e `tmp/full-update/`; screenshots/JSON de navegador em `output/agent-migration/`. Não são artefatos para distribuição.

## Gates ainda abertos para release

- Executar a migração em Linux com serviços reais systemd e SysV, incluindo saída do coletor, lock global e nova execução do manager.
- Executar com Java 6 real e Java moderno; bytecode major 50 não comprova execução em uma JVM real.
- Reiniciar/interromper uma VM Linux entre as fronteiras duráveis e validar recuperação e nenhuma sinalização de processos de aplicação.
- Medir RSS, espaço de pico e tempo de pausa em Linux. O limite configurado de heap é 96 MiB; ele não é uma medida de RSS total.
- Validar os dois canários originais 1.5.12, um por vez, e uma segunda atualização que altere suporte/manager.

Linux/Java de 32 bits permanecem sem teste real, conforme limitação já informada pelo usuário. Não declarar suporte de produção validado para essa arquitetura.

Acesso remoto por shell não foi usado nesta execução. O [runbook de canários](../runbooks/agent-update-1512-canary.md) descreve a execução necessária no laboratório. Não substituir essas provas por adapters locais nem contornar restrições de acesso remoto.

Detalhes adicionais: [desinstalação V3](agent-v3-uninstall.md) e [escolha sequencial de perfis](agent-profile-fallback.md). A montagem offline passou 16 verificações e o instalador completo passou 13 testes com a fixture final. O builder operacional validou seis origens de migração e dez baselines de helper, sem assinatura de produção.

## Pré-verificação dos dois canários

Em 2026-10-14 o operador executou `ops/validar-canarios.py` nas duas máquinas Linux, em sequência. Ambas informaram o coletor original 1.5.12, instalação `system-root`, capability `update-v1`, timer ativo e helper em execução. Uma usa RHEL 8/Java 17 com helper 1.5.12; a outra RHEL 7/Java 11 com helper 1.5.9. O serviço de coleta inativo entre disparos do timer não indica falha por si só.

Os hashes recebidos foram comparados aos ZIPs originais fixados em `agent-migration-baselines.json`: coletor, helper real, chave pública e os 23 arquivos de suporte conferiram em ambas. As sete classes `AgentUpdateWorker*` dos helpers 1.5.9 e 1.5.12 têm bytes idênticos; isso não substitui a execução da migração com as dependências e os serviços reais. Nenhum arquivo de operação pendente foi detectado e havia mais de 22 GiB disponíveis em cada máquina. Configuração, identidade, CA e seleção do Java tiveram seus hashes registrados privadamente para comparação posterior.

Os endereços, hashes de identidades/configurações e relatórios completos ficam fora do Git, em `output/agent-canaries/`. Essa etapa confirma a origem instalada para preparar o teste; não fecha os gates de migração, recuperação, reboot, confirmação final ou assessment posterior. Na ocasião dessa pré-verificação, o dashboard publicado estava em 2.0.5 com o mecanismo anterior.

## Candidato de laboratório 2.0.9

Com a autorização do usuário para testar a primeira VM sem snapshot, foram reservados B=2.0.8 e T=2.0.9. A reserva anterior 2.0.6/2.0.7 foi consumida por uma falha de sandbox ao abrir o servidor HTTP local do teste e não foi reutilizada. O pacote final está marcado `candidateOnly: true`, preserva a chave instalada e permanece sem publicação nesta preparação. O builder operacional usa `--lab-canary`; o marcador persiste no ledger, recibos e documentação embutida. O candidato não equivale à liberação para o cliente.

- As regressões executadas pelo builder passaram, incluindo descoberta, JDBC, autenticação, Work requests, instaladores e publicação.
- `ops/verify-full-agent-candidate.py` conferiu os cinco artefatos finais, assinaturas/pin, bundle, bootstrap, bytecode major 50, seis rotas e helpers conhecidos 1.5.12/1.5.9, sem ler chave privada. O teste negativo adicional passou dez verificações.
- A suíte completa de migração foi repetida com catálogo de teste separado B=2.0.8/T=2.0.9 e evolução U=2.0.10. Passaram origem original, contratos, lifecycle, desinstalação, perfis, servidor, assessment e integração. As ações do SO continuam sendo adapters explícitos.
- Chrome com API/SQLite reais locais confirmou o fluxo do painel 2.0.9, etapas internas, confirmação final, um assessment vinculado, opção desmarcada e layout desktop/mobile; nenhuma resposta de API foi simulada.
- A automação opcional de laboratório passou 16 testes offline: um único host, segredo somente em memória, assinatura/hash final, idempotência, retomada e reinício sem duplicação. A solicitação do primeiro teste real será feita pela interface, conforme preferência do operador.

Pacotes e evidências estão em `output/dashboard-releases/2.0.9/`; fixtures e logs complementares em `tmp/full-update/canary-209-*`. Os gates de Linux real, Java 6/SysV, reinício/interrupção, segundo canário e evolução real continuam abertos.

## Primeira tentativa nativa e correção do handoff systemd

O operador publicou 2.0.9 e iniciou pelo painel uma migração em outra VM de laboratório, RHEL 9/Java 11. O diagnóstico recebido mostrou bridge 2.0.8, helper legado 1.5.8 conhecido pelo catálogo, pacote final preparado e journal em `PREPARED`. Bootstrap, selo de arquivos e backup da origem estavam presentes; a unidade de recuperação em `/etc/systemd/system` não existia. O coletor estava em estado `failed`, com `ProtectSystem=full`, e o timer permanecia ativo. Essa tentativa não validou a migração completa.

A criação da unidade era executada por um filho do coletor e herdava seu namespace de arquivos protegido. A fonte passa a solicitar ao PID 1 um serviço independente com `systemd-run --service-type=simple`, com nome derivado exclusivamente do UUID da operação e caminho do bootstrap verificado. A proteção do coletor permanece ativa. O caminho SysV continua síncrono. São usadas opções presentes no systemd 219; não há fallback para escrita direta em `/etc` pelo coletor.

`AgentBootstrapHandoffTest` passou 20 verificações de despacho, replay, falha, argumentos fixos, separação do namespace modelado e preservação do caminho SysV. A suíte de contratos e os 30 checks dos scripts de serviço passaram novamente. A compilação do produto mantém bytecode major 50. Essas provas usam adapters para o sistema operacional; a correção ainda exige execução nativa em uma próxima montagem, sem substituir silenciosamente os ZIPs publicados 2.0.9.

Para a tentativa já preparada, `ops/retomar-migracao.py` verifica o candidato público final, a assinatura e o inventário remoto, a origem conhecida, a fase exata e os hashes de identidade/configuração. Ele executa somente o bootstrap assinado existente, a partir do shell administrativo do operador, e compara a preservação antes/depois. O caso privado com host e operação fica fora do Git. O resultado `HANDOFF_ONLY` confirma apenas a passagem para o recuperador; versão final e assessment ainda precisam aparecer no mesmo Work request. A execução remota dessa retomada permanece pendente; os testes locais não usam SSH real.

O script de retomada passou 38 verificações offline, incluindo alvo/operação sintéticos, arquivo privado, assinatura pública, marcadores permitidos, espera pelo recuperador e timeout sem repetição automática. Uma prova adicional executou `verify_state()` inteiro em árvore temporária criada com os ZIPs públicos reais: staging e modos, bootstrap/selo, origem 1.5.12, helper 1.5.8 e journal sintético. OpenSSL real verificou a assinatura; arquivo de staging adulterado e diretório preparado diferente foram rejeitados. Somente identidade root/Linux/serviços foram simulados nessa prova, sem iniciar bootstrap ou SSH.

A primeira execução da retomada pelo operador encontrou timeout ao conectar à porta SSH. O relatório privado registrou exit code 255 e nenhum marcador remoto; essa tentativa não chegou a executar o reparo. A mensagem do wrapper foi corrigida para distinguir ausência de resposta SSH, timeout de resultado desconhecido, recusa específica de uma validação e divergência real de hashes. A regressão do script passou 53 verificações offline. A conexão e a conclusão nativa ainda estavam pendentes naquele momento; a retomada posterior está registrada abaixo.

## Confirmação nativa após a retomada

O operador informou que a VM estava desligada, ligou-a e executou novamente a retomada. O relatório retornou SSH 0, `RESUME_PRESERVATION=UNCHANGED` para os seis hashes conferidos e `RESUME_RECOVERY=ACTIVE`. `HANDOFF_ONLY` não foi usado como prova de conclusão; o estado `FAILED` do coletor nesse instante também não foi interpretado como uma nova falha definitiva.

A interface real do dashboard foi consultada pelo Chrome, sem requisições simuladas ou execução de SSH pelo agente. A máquina apareceu com 2.0.9 e contato `Reporting`. O mesmo pedido iniciado às 19:44 de 2026-10-14 mostrou migração 1.5.12 → 2.0.9, atualização completa confirmada e conclusão às 22:45:40 GMT-3. O assessment vinculado foi recebido às 22:45:57: quatro instalações Java, nenhum servidor e coleta parcial por caminho de inclusão ausente (`/usr/java`). Atualização e cobertura da coleta permaneceram resultados separados.

Essa prova confirma a continuação nativa do pacote 2.0.9 após uma recuperação manual, preservando configuração e identidade. Não comprova o novo despacho automático systemd ainda presente apenas na fonte. Os dois canários originais 1.5.12, a recuperação após interrupções, a evolução posterior e Java 6/SysV permanecem pendentes. Endereços, IDs completos e recibos ficam apenas em `output/agent-canaries/`, fora do Git.

## Candidato corrigido 2.0.11

Foram reservados e preparados B=2.0.10/T=2.0.11, exclusivamente para laboratório. Os cinco artefatos finais foram conferidos por recibo, assinatura e inventário. As sete rotas assinadas e o código do agente contêm o novo despacho independente; as 58 classes do agente mantêm major 50. Os bytes da interface são iguais aos da 2.0.9 conferida na sessão real. O bootstrap herdado da primeira V3 permanece exatamente 2.0.9, autenticado pelo ZIP público revisado, recibo, DER, assinatura e inventário; código e manager correspondem ao alvo novo.

O verificador público passou 20 checks em 2.0.9 e 2.0.11, incluindo adulteração dos pins, recibos, assinatura e herança. A suíte completa com fixtures novas B=2.0.10/T=2.0.11/U=2.0.12 passou: binário original, contratos, 20 checks do handoff corrigido, lifecycle, desinstalação, perfis, HTTP/SQLite, Work requests, assessment e integração final. A retomada pontual também passou novamente seus 53 checks, sem SSH. O builder validou as regressões de descoberta/JDBC, autenticação, instaladores e publicação.

Pacotes conferidos e instruções ficam em `output/dashboard-releases/2.0.11/`; o log integrado está em `tmp/full-update/canary-211-migration.log`. A publicação e o próximo pedido serão executados pelo operador. O próximo aceite nativo é um canário original 1.5.12 concluindo diretamente pelo painel, sem o reparo usado em 2.0.9. Java 6/SysV, dois canários, interrupções/reboot e evolução real continuam gates de produção.

## Falha nativa de coordenação do helper em 2.0.11

O operador publicou 2.0.11 e iniciou a migração do canário RHEL 8/Java 17 com collector e helper originais 1.5.12. Os diagnósticos mostraram bridge 2.0.10, pacote preparado, ausência de journal V1 e configuração/identidade/Java/CA/pin preservados. Bootstrap, selo, origem de recuperação, unidade persistente e ponteiro final estavam ausentes.

O novo diagnóstico limitado ao pedido mostrou o transiente existente, falhado, `ExecMainStatus=1`, `ProtectSystem=no` e `LIFECYCLE_LOCK_BUSY` no journal. O host usa systemd 239. Isso confirma a saída do namespace protegido e uma corrida diferente: o helper original abre o flock em cada ciclo, enquanto o bootstrap tenta adquiri-lo sem esperar. A primeira recusa deixa a unidade nomeada falhada, e o replay não a considera ativa. O intervalo exibido no painel não foi tratado como prova de timeout do `systemd-run`.

A fonte foi corrigida para parar o helper legado pelo D-Bus antes da fila, confirmar inatividade e preservar/restaurar seu estado em falhas anteriores ao handoff. A intenção original é gravada duravelmente no mesmo journal e não é recapturada como inativa em um replay. A restauração exige ausência de bootstrap/recuperador em execução, journal e identidade iguais e nenhuma operação V1; remove apenas o marcador `stop` vazio, regular, root, privado e sem hardlink, inicia o helper pelo PID 1 e confirma seu estado. Timeout ou resultado desconhecido não autoriza restauração especulativa. Os locks não devem ser removidos nem suas verificações relaxadas. O bootstrap 2.0.9 e todos os artefatos publicados continuam imutáveis. A migração automática permanece não validada.

A retomada pontual com os arquivos assinados 2.0.11 existentes passou inicialmente 217 verificações locais com sistema operacional e SSH simulados, incluindo assinatura e inventário públicos reais. Na primeira execução pelo operador ela parou na guarda `ORIGINAL_HELPER_DID_NOT_STOP`, antes de executar o bootstrap. Os seis hashes antes/depois continuaram iguais. O diagnóstico posterior mostrou helper `inactive/dead`, MainPID=0, ControlPID=0, `Job=` vazio, ControlGroup vazio e ausência dos cgroups conhecidos; o marcador `stop` era regular, root, modo 600 e tamanho zero. Esse resultado não comprova que o helper continuava rodando nem que houve timeout.

A leitura corrigida pede `--all`, exige presença das propriedades e interpreta explicitamente `Job=` vazio como ausência de tarefa. PIDs vazios, campo Job ausente e estados desconhecidos são recusados. A retomada não repete a parada quando o helper já está inativo e sem processos/tarefas/cgroup ativo; revalida os mesmos arquivos e a identidade antes do bootstrap. O marcador `stop` fica preservado nesse caminho. Somente uma restauração condicional do helper anteriormente ativo pode remover o marcador privado regular, root, sem hardlink e vazio, conferindo o inode antes da remoção e o serviço após o start.

A correção passou 256 checks do handoff, 323 checks da retomada 2.0.11, 54 checks da retomada anterior, manifesto (75), rotas (14) e wrappers de serviço (30). ECJ compilou 61 classes, todas major version 50. São regressões locais com serviços simulados; fonte e scripts foram corrigidos sem substituir qualquer ZIP publicado.

## Confirmação nativa após a retomada 2.0.11

O operador executou a retomada corrigida, que confirmou helper já inativo, os seis hashes preservados e recuperador ativo. O bootstrap assinado existente iniciou a instalação; o resumo final do script encontrou `INCOMPLETE_SERVICE_DIAGNOSTIC` apenas ao consultar o timer com campos específicos de serviços. Esse falso negativo de exibição não deve provocar nova execução: o handoff já ocorreu. A leitura de timer foi separada da leitura estrita de helper/recuperador/transiente. Ela consulta somente LoadState, ActiveState, SubState e Job; uma falha nessa exibição posterior registra `RESUME_TIMER=UNKNOWN`, preservando `HANDOFF_ONLY` sem declarar sucesso da atualização.

A interface autenticada mostrou o mesmo Work request avançar para instalação às 23:34, verificação às 23:35 e **Concluído, 1/1 atualizada**, em 2026-10-14 às 23:35:13 GMT-3, versão final 2.0.11. A lista de máquinas confirmou 2.0.11 e contato normal. O assessment vinculado foi liberado e aguardava coleta na primeira conferência. O recibo privado fica ao lado do relatório da retomada em `output/agent-canaries/`; os identificadores e hashes de configuração não entram no Git. Essa prova confirma a recuperação do canário original e a confirmação final, mas usou reparo manual; o fluxo automático corrigido, segundo canário, Java 6/SysV, reinício/falhas e evolução posterior continuam gates.

Na sequência foi recomendada a prova 2.0.9 para 2.0.11 em uma máquina já migrada. A prévia real mostrou uma máquina elegível, atualização completa pronta e assessment posterior selecionado. A conferência abriu apenas a prévia, sem criar pedido. O canário original restante deve aguardar um candidato novo com a coordenação corrigida; Java 6/SysV fica em uma etapa distinta.

## Evolução nativa 2.0.9 para 2.0.11

O operador confirmou o resultado por captura do Work request da máquina RHEL 9/Java 11. A tela mostrou atualização completa 2.0.9 para 2.0.11, iniciada em 2026-10-14 às 23:40:35 GMT-3, instalação às 23:41:52, verificação às 23:42:31 e conclusão às 23:42:35. O bloco de assessment posterior mostrou inventário recebido com 4 instalações Java e 0 servidores. Não houve retomada manual relatada para esse pedido; a evidência desta etapa é a captura fornecida pelo operador.

Esse ensaio confirma a atualização observada de uma instalação já migrada e o disparo/recebimento do assessment após confirmar a versão final. A captura e um recibo ficam privados em `output/agent-canaries/`. Isoladamente, não demonstra migração automática a partir de 1.5.12, Java 6/SysV, recuperação após interrupções ou compatibilidade irrestrita com versões futuras. A prova posterior com origem 1.5.12 está registrada abaixo; o candidato seguinte ainda precisa incluir e validar a coordenação corrigida.

## Migração automática nativa 1.5.12 para 2.0.11

O operador enviou a captura do segundo canário, RHEL 7/Java 11, com origem 1.5.12 e destino 2.0.11. O Work request mostrou "Migração automática" e "Atualização completa confirmada". O pedido começou em 2026-10-14 às 23:46:09 GMT-3 e concluiu às 23:48:58, após download, instalação e verificação. O assessment posterior foi recebido com 1 instalação Java e 2 servidores. Não foi relatado uso do script de retomada nesse pedido; a evidência desta etapa é a captura fornecida pelo operador, preservada com recibo privado em `output/agent-canaries/`.

O sucesso comprova essa execução da migração original para o pacote completo e o assessment posterior. Não invalida a disputa de flock reproduzida na RHEL 8: uma condição de concorrência pode não ocorrer em todas as tentativas, e o pacote 2.0.11 continua sem a correção preparada. Java 6/SysV e falhas/reboot/recuperação permanecem testes distintos. Os dois canários originais agora estão migrados; validar o candidato corrigido com origem 1.5.12 requer outra instalação original ou restauração controlada de laboratório, sem downgrade improvisado.

## Confirmação RHEL 5 / Java 6

Em 2026-10-15 o operador confirmou sucesso da atualização automática 2.0.5 → 2.0.11 e recebimento do assessment posterior na máquina RHEL 5/Java 6. Não houve intervenção manual relatada nesse pedido. Isso complementa os testes nativos de evolução normal; não constitui prova de falha/reboot, RSS máximo ou 32 bits.
