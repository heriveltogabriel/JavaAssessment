# Limpeza do inventário

Abra **Java Assessment → Máquinas → Sem comunicação**. Escolha o ambiente e o período desde o último contato: 5 minutos, 24 horas, 7 dias ou 30 dias. A lista mostra máquina, ambiente, IP, versão do agente, último contato e tempo sem comunicação. Os filtros são aplicados a todo o inventário, com paginação e busca.

O contador vermelho dos cartões abre o grupo de agentes desconectados daquele ambiente. Para incluir também cadastros revogados ou desinstalados, clique em **Sem comunicação** na lista. Cadastros sem um horário válido de último contato não entram nessa seleção por tempo.

Administradores podem selecionar uma máquina ou as máquinas da página e clicar em **Excluir do inventário**. A prévia apresenta a seleção e eventuais impedimentos. Digite **EXCLUIR** e confirme. Leitores podem consultar a lista, mas não excluir. A seleção é limitada à página visível e é limpa ao mudar filtros ou página.

A confirmação verifica novamente o contato, nome, ambiente e operações pendentes de cada máquina. Máquinas que voltaram a comunicar ou mudaram desde a prévia são preservadas. Atualizações/desinstalações pendentes ou sem confirmação e coletas com prazo de execução ativo impedem a exclusão. O resultado informa quantas foram excluídas e os motivos das preservadas.

A exclusão é permanente no banco ativo do dashboard. Remove o cadastro, as coleções de inventário, agendamentos, recibos e solicitações relacionadas. Esses dados deixam de aparecer nos totais, relatórios, exportações e listas de atualização. Em uma solicitação de atualização compartilhada, os registros das demais máquinas são preservados. A máquina não é movida para **Sem ambiente**.

Permanece somente a identidade mínima revogada, sem token em texto aberto, para bloquear a instalação antiga, além do registro administrativo limitado de quem realizou a exclusão. Não é criado um arquivo de histórico do inventário. Backups e arquivos que já foram exportados não são alterados.

A operação não desinstala o agente nem apaga arquivos na máquina. Um agente antigo que tente comunicar será recusado. A falta de comunicação nunca provoca exclusão automática. Se a conexão com o dashboard cair durante a confirmação, consulte a lista antes de tentar novamente.
