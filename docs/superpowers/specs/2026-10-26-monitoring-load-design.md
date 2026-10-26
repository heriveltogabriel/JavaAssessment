# Reduzir carga do acompanhamento de campanhas

Correção de desempenho no escopo das melhorias de escala já autorizadas. Não alterar número de versão ou pacotes publicados durante a validação.

A captura mostra chamadas repetidas de state, lista e detalhe. O código confirma dois temporizadores (estado a cada 15 s e acompanhamento a cada 5 s), além de manutenção da fila e transação de escrita em cada GET de acompanhamento. Isso compete com os agentes; não prova que seja o único gargalo do ambiente.

A solução será retirar manutenção dos GETs de acompanhamento, executá-los em snapshots somente de leitura e conservar a manutenção independente a cada 5 s. No navegador, usar temporizador de 30 s após finalizar a consulta, com recuo para 60/120 s em falhas, sem ciclos sobrepostos e sem polling em aba oculta. Suprimir state automático na tela Solicitações. Manter atualização manual, escopo, autorização, cancelamento, detalhes e foco existentes. O último estado recebido permanece visível em erro. Sem cache compartilhado entre usuários.

Alternativas: só aumentar o intervalo alivia tráfego mas mantém contenção da escrita; SSE exige novo protocolo e gestão de conexões. A solução escolhida remove a contenção desnecessária e reduz chamadas sem alterar o agente. Não refatorar toda a consulta SQL nesta correção.

Provas: GETs de lista/ativo/detalhe respondem durante transação de escrita concorrente; GET não expira pedidos; manutenção independente continua expirando. Relógio controlado verifica intervalo, recuo, aba oculta, erro, limpeza e ausência de polling de state em Solicitações. Regressões HTTP e navegador, seguidas da suíte de origem. Nenhum deploy remoto.
