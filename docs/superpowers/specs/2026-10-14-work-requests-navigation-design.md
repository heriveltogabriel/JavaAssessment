# Work requests independente e resultados explicados

Aprovado pelo usuário: página exclusiva pelo menu lateral, filtros recolhíveis, status explícitos com motivo e separador antes de Administração. Implementação e empacotamento local autorizados, sem nova rodada de confirmação.

## Navegação e filtros
Work requests sai das abas horizontais do ambiente/máquina. Sua página exibe título, busca, filtros, lista e detalhe, sem abas, cabeçalho/ações/aviso de inventário. O menu lateral abre todos os ambientes; atalhos de pedidos preservam ambiente/máquina e pedido. Rotas anteriores continuam abrindo o pedido corretamente. Separadores sutis delimitam três grupos no menu: links de inventário, Work requests e Administração. O separador de Administração acompanha sua ausência para leitores.

Busca por texto permanece visível. Filtros avançados (ambiente, tipo, status e datas) começam recolhidos, com botão acessível aria-expanded/aria-controls e resumo de critérios aplicados. Recolher preserva critérios e rascunhos. Guardar escolha de expansão na sessão de navegação, limpar dados de sessão ao sair. Manter escopo aplicado e opção Limpar visíveis mesmo com filtros fechados. Mobile e teclado funcionam; reconsultas automáticas preservam foco, expansão e texto.

## Resultados e motivos
O PARTIAL de coletas passa a Coleta parcial; o de atualizações, Atualização parcial. Resultados mistos mostram contagens reais, como 2 atualizadas e 1 cancelada. Uma coleta parcial apresenta as limitações registradas naquele pedido, traduzidas quando conhecidas e escapadas sempre. Persistir avisos de cobertura junto ao pedido recebido para impedir que avisos de uma coleta posterior sejam atribuídos ao histórico. Pedidos antigos sem avisos próprios informam indisponibilidade; só usar cobertura atual como evidência da mesma coleta quando timestamps coincidem, com origem explícita. Nunca inventar motivos. Ver motivo abre o detalhe e destaca as observações. Leitores podem consultar; alterações continuam protegidas.

## Integração
Backend mantém contratos existentes e acrescenta observations, observationsAvailable e observationSource aos detalhes de máquinas de coleta; lista/resumo pode expor observationSummary quando houver registro seguro. Frontend usa campos opcionais e fallback honesto. Protocolos do agente e transições de atualização não mudam. Tests cobrem persistência de motivo após nova coleta, escapes, leitor e legado, além de rotas, filtros, foco e escopo.
