# Melhorias planejadas

## Atualização remota completa do agente

**Status:** implementação e regressões locais concluídas em 2026-10-14, com origem mínima 1.5.12. Bridge com binário original, pacote completo, operação lógica, recuperação, limpeza, desinstalação, empacotamento e assessment posterior integrados. Serviços/reboot em Linux real e os dois canários permanecem gates de release. O mecanismo em produção continua sendo o atual.

**Candidato de laboratório:** 2.0.9, com bridge interno 2.0.8, publicado pelo operador. A primeira tentativa pelo dashboard ocorreu em uma terceira máquina de laboratório, RHEL 9/Java 11, e ficou em `PREPARED`: o coletor antigo, protegido por `ProtectSystem=full`, não podia criar o serviço de recuperação em `/etc`. Após a retomada pontual pelo operador, o mesmo Work request confirmou 2.0.9 em 2026-10-14 às 22:45 GMT-3. A máquina voltou a reportar e um assessment vinculado foi recebido. Os dois canários originais 1.5.12 continuam sem migrar.

**Correção em validação:** a próxima montagem deve iniciar o bootstrap systemd em um serviço independente criado pelo PID 1, preservando a proteção do coletor. Fonte corrigida e regressões locais passaram; os artefatos publicados 2.0.9 permanecem imutáveis. `ops/retomar-migracao.py` destravou exclusivamente o pedido identificado, conferindo assinatura, origem e hashes preservados de configuração, identidade, Java, CA e chave pública. O sucesso posterior foi conferido na interface real. O próximo candidato deve comprovar o fluxo automático em um canário original 1.5.12, sem essa intervenção. Isso não encerra os gates de Linux/Java 6, reinício/recuperação, segundo canário e evolução posterior.

**Teste do candidato 2.0.11:** publicado pelo operador. O canário original RHEL 8/Java 17 chegou ao bridge 2.0.10 e ao pacote `PREPARED`, mas não concluiu. O serviço temporário foi criado fora da proteção do coletor (`ProtectSystem=no`), confirmando essa parte da correção. Ele falhou ao disputar o flock com o atualizador original 1.5.12 (`LIFECYCLE_LOCK_BUSY`); a unidade falhada com nome fixo impediu nova execução automática. Configuração, identidade, Java e confiança continuam com hashes iguais aos anteriores. Nenhum bootstrap, origem de recuperação ou ponteiro ativo foi criado nessa tentativa. Os artefatos publicados permanecem imutáveis.

**Correção na fonte, ainda não publicada:** parar apenas o helper legado, sem segurar o flock, antes de enfileirar o bootstrap; persistir seu estado original, confirmar inatividade e contadores zerados e restaurá-lo condicionalmente se a fila falhar antes do handoff. A restauração precisa limpar somente o marcador operacional `stop` vazio, regular, privado e pertencente a root, porque o `ExecStart=loop` original não faz essa limpeza; start sem confirmação posterior não é sucesso. O pedido 2.0.11 deve ser retomado com seus arquivos assinados existentes. A segunda máquina original permanece em 1.5.12. Não declarar o fluxo automático validado nem liberar para cliente.

**Retomada pontual concluída:** o primeiro reparo 2.0.11 parou antes do bootstrap com `ORIGINAL_HELPER_DID_NOT_STOP`; os seis hashes de preservação continuaram iguais. O diagnóstico posterior confirmou o helper `inactive/dead`, MainPID e ControlPID zerados, `Job=` vazio, ControlGroup vazio e cgroups ausentes. O marcador de parada era regular, root, modo 600 e vazio. A leitura corrigida solicita explicitamente todas as propriedades, distingue campos ausentes de `Job=` vazio e aceita apenas a inatividade comprovada; quando o helper já está parado, não repete a parada. O operador retomou o mesmo pedido, e o dashboard confirmou 2.0.11 em 2026-10-14 às 23:35 GMT-3, 1/1 atualizada e nenhuma pendência; a máquina voltou a reportar. Assessment vinculado liberado e aguardando coleta na primeira conferência. Houve um falso negativo posterior no resumo do script ao exigir propriedades de serviço para o timer; a leitura foi corrigida sem nova execução na máquina. O sucesso com reparo manual não valida a próxima migração automática.

**Regressões da correção:** 256 checks do handoff, 323 checks da retomada 2.0.11, 54 checks da retomada anterior, contratos de manifesto/rotas e 30 checks dos wrappers passaram localmente. O timer usa apenas propriedades comuns de unidade; falha nessa exibição final registra `UNKNOWN` sem invalidar handoff já comprovado. Os guardas dos serviços permanecem estritos. ECJ compilou 61 classes, todas com bytecode Java 6. Os serviços continuam simulados nesses testes: nenhuma dessas contagens substitui a prova nativa.

**Evolução após a migração confirmada:** o operador atualizou a máquina RHEL 9/Java 11 de 2.0.9 para 2.0.11 pelo painel. A captura enviada mostrou atualização completa confirmada, verificação e conclusão em 2026-10-14 às 23:42:35 GMT-3. O assessment automático posterior foi recebido, com 4 instalações Java e 0 servidores; não houve retomada manual relatada nesse teste. Isso comprova o salto observado para uma instalação já migrada, sem encerrar os gates das demais rotas e plataformas. Preservar o segundo canário original 1.5.12 para um candidato novo que inclua a correção do handoff, e validar Java 6/SysV em etapa própria. Os pacotes publicados 2.0.11 ainda não incluem a correção da corrida com o helper legado.

**Gate adicional de recuperação:** conferir falhas fatais em `PREPARED` depois da instalação do bootstrap, antes de `QUIESCING`. O anchor imutável 2.0.9 pode encerrar o pedido sem restaurar helper/origem nessa fronteira. Uma alteração apenas na fonte nova não corrige retroativamente o anchor instalado; esse caso requer desenho e prova antes da entrega ao cliente.

- [Desenho: migração, preservação, recuperação, limpeza e assessment](superpowers/specs/2026-10-14-full-agent-update-design.md)
- [Plano com oito workflows e gates de validação](superpowers/plans/2026-10-14-full-agent-update.md)

O primeiro gate usa o atualizador e os scripts originais 1.5.12, incluindo ACK, limpeza, locks e passagem para recuperação independente. Depois dos testes isolados, duas máquinas ainda em 1.5.12 serão canários em sequência. Uma segunda release que altere suporte/manager deverá comprovar a evolução posterior pelo painel.

No mecanismo anterior ao candidato 2.0.9, a atualização remota substitui somente `collector.jar`. Os scripts, bibliotecas e o atualizador instalado precisam corresponder aos arquivos de suporte do manifesto assinado. Pular versões é permitido quando esses requisitos continuam compatíveis.

Nos pacotes publicados, 1.5.10 a 2.0.0 usam `update-v1`; a 2.0.1 passou a `update-v2` e alterou o atualizador; a 2.0.2 alterou o launcher para 96 MiB. Os arquivos de suporte da 2.0.2 e 2.0.3 são iguais. A compatibilidade de entregas futuras precisa ser verificada, não inferida apenas pelo número da versão.

### Resultado desejado

- Permitir atualizar também scripts, bibliotecas e componentes do atualizador pelo dashboard, sem SSH, com pacote e manifesto assinados e verificação de integridade.
- Definir uma migração explícita para atualizadores antigos. Eles não passam a entender um novo formato só porque o dashboard mudou.
- Preservar identidade, ambiente, configuração, confiança TLS/CA e inventário existente.
- Validar arquivos e permissões antes da troca; manter backup e recuperação caso haja falha, interrupção de energia ou falha na verificação de funcionamento.
- Mostrar a compatibilidade real antes da confirmação e acompanhar todas as etapas em Work requests.
- Validar saltos entre releases, Java 6 e posteriores e os serviços SysV/systemd. Não declarar suporte testado em 32 bits enquanto não houver validação real.

Não remover verificações de assinatura, hashes ou geração do atualizador para liberar artificialmente agentes antigos.

Toda entrega continua lembrando a atualização dos agentes no dashboard e nas instruções, conforme [o processo de release](RELEASE_PROCESS.md). O código novo inclui a opção de executar um assessment após a confirmação final. Consulte o [contrato V3](AGENT_UPDATE_V3.md), as [evidências e limites](validation/full-agent-update.md) e o [runbook de canários](runbooks/agent-update-1512-canary.md).
