# Acompanhamento em Work requests

Abra **Work requests** no menu lateral para consultar coletas e atualizações de agentes em uma página própria. O menu abre todos os ambientes. Um atalho de acompanhamento dentro de um ambiente ou máquina abre essa mesma página com o contexto e o pedido selecionados. Uma atualização de várias máquinas aparece como um único pedido; seus detalhes mostram cada máquina, a versão anterior, a versão solicitada e o resultado.

A busca por texto permanece visível. O botão **Filtros** abre ou recolhe ambiente, tipo, estado e período. Recolher o painel preserva os critérios aplicados e os valores em edição; o resumo e a opção **Limpar** continuam visíveis. A escolha de expansão permanece durante a navegação da sessão. Work requests aparece separado dos links de inventário e de Administração no menu lateral.

Após confirmar uma atualização, a janela mostra o acompanhamento e oferece **Ver em Work requests**. Fechar a janela não cancela o pedido. O resumo na tela do ambiente permite voltar aos lotes ativos; os detalhes podem ser reabertos depois de recarregar a página.

As informações são atualizadas periodicamente enquanto a página está visível. As contagens representam estados informados pelos agentes: aguardando, baixando, instalando, verificando, atualizado, falhou, versão anterior restaurada ou aguardando confirmação. O dashboard só mostra sucesso depois de receber as confirmações exigidas pelo protocolo. A ausência de confirmação não é interpretada como conclusão.

Nos detalhes, cada máquina mostra uma linha do tempo com os eventos e horários disponíveis. Pedidos anteriores a esta versão podem ter histórico parcial: são exibidos os registros existentes, sem inventar horários intermediários. Falha ou restauração de uma máquina interrompe o restante do lote; os pedidos cancelados por esse motivo indicam a causa.

**Coleta parcial** significa que o resultado chegou com limitações de cobertura. **Atualização parcial** significa que o lote terminou com resultados diferentes entre as máquinas; a tela apresenta as contagens reais. **Ver motivo** abre as observações do pedido. Novas coletas preservam seus próprios avisos, mesmo após uma coleta posterior. Para pedidos antigos sem avisos salvos, o dashboard informa a indisponibilidade; a cobertura atual só serve de evidência daquele pedido quando o horário corresponde à mesma coleta. A tela identifica essa origem.

O perfil **Leitura** pode acompanhar os pedidos. Somente um **Administrador** pode cancelar uma máquina que ainda esteja aguardando o agente. Quando a instalação já começou, a operação segue o fluxo protegido do agente.

O histórico das atualizações concluídas é preservado quando uma máquina é removida do inventário. Links salvos para esses pedidos continuam abrindo com seu escopo histórico, mesmo sem a máquina no inventário atual. Novos pedidos guardam o nome da máquina, o ambiente e o solicitante no momento da solicitação. Os eventos apresentados são diagnósticos do protocolo de atualização; não são uma cópia integral dos logs do sistema operacional.
