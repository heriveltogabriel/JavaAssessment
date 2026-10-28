# Campanhas de atualização de agentes

Campanhas disponíveis desde a 2.1.26; melhorias de desempenho consolidadas na **2.1.28**, preparada para teste do operador. Os instaladores anteriores foram preservados. Veja os [pacotes](../install/v2.1.28/README.md) e as [provas de atualização](validation/console-release-2.1.28.md).

**Atenção: atualize também os agentes. Atualizar o dashboard não atualiza os agentes automaticamente.** A melhora do servidor já atende os agentes **2.1.26**; sua atualização é **opcional** para obter essa melhora.

## Redução de carga na 2.1.28

As consultas de acompanhamento usam leitura e a manutenção independente no servidor continua a cada 5 segundos. A tela consulta a cada 30 segundos depois da resposta anterior, com recuo para 60/120 segundos em falhas, sem consultas automáticas de inventário geral em Solicitações. Aba oculta não consulta; o botão Atualizar agora continua disponível. O último estado recebido permanece visível em erro.

Agentes ociosos também usam leitura, resultados são preparados fora do lock e gravados em lote, e faixas de atendimento limitam a pressão no escritor SQLite. A coleta agendada se distribui nos primeiros 15 minutos da janela por padrão. `sqliteSynchronous=NORMAL` é o padrão; `FULL` mantém a durabilidade anterior diante de falha de energia/SO. Consulte as [notas e opções](RELEASE_2.1.28.md), as [provas de acompanhamento](validation/monitoring-load-2026-10-26.md) e as [medições anteriores do servidor](validation/agent-path-load-2026-10-26.md). Isso não garante resolver todos os gargalos de um ambiente real.

## Revisar e iniciar

1. Em **Revisar agentes**, filtre por máquina, ambiente, versão, contato recente ou elegibilidade. A consulta retorna 50 máquinas por página. Nenhuma vem selecionada automaticamente.
2. Selecione máquinas individualmente, as elegíveis da página ou **candidatas de todo o filtro**. A seleção do filtro é salva no servidor; não depende de carregar todas as páginas no navegador. Alterar o filtro limpa a seleção manual.
3. Em **Configuração**, escolha o tamanho dos grupos (padrão 10, de 1 a 100). O limite simultâneo é **global** (padrão 10, ajustável de 1 a 25; instalações que ainda guardam o 2 implícito de versões anteriores passam a usar 10 até o administrador salvar um valor) e vale também para pedidos antigos quando o controle global está habilitado. Salvar o limite exige confirmação. Reduzir o limite não interrompe instalações iniciadas.
4. Revise a quantidade, a versão e o contato das máquinas. Confirme **Iniciar campanha**. A seleção pertence ao administrador que a criou e vale por 15 minutos. Repetir uma confirmação após falha de comunicação usa a mesma chave e não cria outra campanha.
5. Acompanhe em **Solicitações**. Pode fechar o navegador: os pedidos e controles ficam no SQLite e a manutenção continua no servidor. Depois de reiniciar a console, a fila retoma do estado persistido.

A opção de coleta após atualizar começa desmarcada. Ao ativá-la, a coleta só é liberada após confirmar a atualização final. Os controles existentes de carga das coletas continuam válidos.

## Execução e falhas

Um grupo abre por vez; dentro dele, só iniciam máquinas quando há vagas globais. O grupo seguinte abre após o anterior deixar de ter operações pendentes. Pontes de migração ocupam a mesma vaga do pedido principal. A compatibilidade é conferida novamente antes da admissão; a seleção inicial mostra candidatas e pode conter instalações que precisarão de intervenção.

Pedidos ainda na fila não têm o prazo de início correndo. Ao admitir a máquina, começa uma janela de cinco minutos. Sem contato nos últimos cinco minutos, ela é marcada como expirada por ausência de contato e a campanha segue; uma máquina que não inicia na sua vez também expira. É possível revisar e tentar novamente depois.

Uma instalação sem resultado confirmado (`UNCONFIRMED`) mantém sua vaga ocupada e pausa a campanha. Não é tratada como sucesso ou cancelamento. Três falhas/restaurações desde a última retomada também pausam a campanha. A mudança do pacote disponível pausa novas partidas; nesse caso, cancele a fila restante e crie outra campanha para o pacote atual.

A previsão de concluir durante a noite depende do tempo de cada instalação, da rede e da capacidade da console. Grupos de 10 não significam automaticamente 10 downloads simultâneos. O padrão é 10 vagas; em ambiente ainda sobrecarregado, reduza temporariamente e aumente após observar CPU, memória, disco e latência do servidor. O teto configurável é 25.

## Pausar, cancelar e repetir

- **Pausar novas partidas / Retomar campanha:** controla a admissão. Transações já iniciadas continuam autorizadas e acompanhadas. Uma operação sem confirmação deve ser resolvida antes de retomar.
- **Cancelar toda a fila:** impede imediatamente novos inícios no pedido inteiro, inclusive fora do filtro visual. A confirmação informa o alcance. O servidor conclui o cancelamento em blocos de até 200, sem centenas de POSTs do navegador.
- **Cancelar selecionadas / aguardando neste filtro:** cancela somente as máquinas ainda aguardando e que pertencem à seleção/filtro aplicado. Também funciona com pedidos criados antes desta implementação.
- **Revisar falhas, expiradas e canceladas:** abre uma nova seleção, excluindo sucessos, instalações em andamento e resultados ainda desconhecidos. Confirme um novo pedido depois de revisar a causa.

Cancelar não desfaz uma instalação que já começou. Não apague locks, serviços ou registros do banco para acelerar a fila.

## Escala e compatibilidade

Não há teto de 1.500, 3.000 ou 5.000 máquinas na campanha. Os limites são por página e por ciclo: até 100 registros nas APIs paginadas (50 na tela), até 200 cancelamentos e até 200 avaliações de novos alvos por manutenção, visitando até 100 campanhas por ciclo com cursor persistido. Os grupos aceitam de 1 a 100 máquinas; o limite global aceita de 1 a 25 instalações simultâneas.

Solicitações consulta contadores de toda a campanha, mas renderiza apenas uma página. Eventos individuais são buscados ao abrir a máquina. A API antiga permanece para o playbook Ansible, que continua usando lotes pequenos e o protocolo original. Campanhas novas corrigem a expiração de filas grandes; pedidos antigos preservam seu prazo original e podem ser cancelados em lote e refeitos.

As validações são locais com 5.000 registros e APIs reais; não representam 5.000 agentes transferindo pacotes simultaneamente nem substituem um piloto no cliente. [Resultados e comandos](validation/agent-update-campaigns-2026-10-26.md).
