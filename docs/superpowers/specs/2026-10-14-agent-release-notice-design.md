# Aviso de atualização dos agentes

Solicitado em 2026-10-14: manter a atualização remota atual e lembrar a atualização dos agentes tanto no dashboard quanto nas instruções de cada entrega. A atualização completa dos componentes de suporte fica no backlog.

## Comportamento aprovado

O administrador vê um aviso persistente no inventário quando há agentes cadastrados com versão conhecida inferior à versão do dashboard. A contagem cobre todos os ambientes, independentemente da tela, busca ou paginação atual. Agentes revogados ou desinstalados não contam; agentes sem contato recente continuam contando. Versões ausentes, inválidas, iguais ou superiores não são classificadas como antigas.

O aviso informa a versão de destino, a quantidade pendente e que atualizar o dashboard não atualiza os agentes. O botão “Revisar agentes” abre a prévia existente de todos os ambientes, com as mesmas regras de elegibilidade e confirmação. O aviso desaparece quando um novo estado não contém agentes antigos; não cria pedidos nem altera a compatibilidade. Não é exibido no login ou para usuários de leitura.

## Implementação

O estado autenticado do administrador contém um resumo calculado no servidor a partir das versões declaradas por identidades ativas. Uma consulta agrupada por versão evita carregar inventários ou verificar assinaturas a cada atualização da tela. A comparação numérica reutiliza a regra atual do catálogo. A interface usa o resumo recebido junto do inventário, sem uma segunda chamada nem dependência da ordem de carregamento de `/health`.

Alternativas consideradas: um popup a cada login interromperia o uso; contar somente a página visível ocultaria pendências. Um aviso persistente baseado no resumo global atende à solicitação sem esses problemas.

## Entregas e limites

Registrar a regra de aviso nas instruções do repositório e no processo de release. Toda entrega deve indicar o caminho remoto ou o instalador completo conforme a compatibilidade real. Não prometer que o mecanismo atual atualiza scripts, bibliotecas ou o próprio atualizador. Esta alteração de fonte não republica a versão 2.0.3 nem modifica máquinas instaladas.

## Validação

Verificar contagens reais em SQLite/HTTP, escopo global, igualdade e ordenação numérica, atualização por novo heartbeat, exclusão de revogados/desinstalados, visibilidade apenas administrativa e ausência de pedidos automáticos. Exercitar a apresentação em desktop e mobile e manter as regressões de atualização/refresh existentes.
