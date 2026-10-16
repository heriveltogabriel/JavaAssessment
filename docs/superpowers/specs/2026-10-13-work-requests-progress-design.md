# Acompanhamento de atualizações de agentes

Proposta aprovada pelo usuário: Work requests mantém o histórico permanente e a tela do ambiente mostra resumo vivo de atualizações. Ao confirmar, abrir acompanhamento, permitir fechar e voltar, agrupar o lote, mostrar cada máquina e eventos reais, filtros de tipo/estado/período/ambiente, leitura para READER e cancelamento de pedidos WAITING somente por ADMIN.

## Integração

Preservar contratos do agente e estados existentes. Criar WorkRequests.java para consultas limitadas e DTOs públicos; não reutilizar dados de credenciais. GET /api/work-requests lista coletas e lotes, /api/work-requests/active mostra até cinco lotes ativos e total, /api/work-requests/detail mostra máquinas/eventos. Consultas sempre respeitam environmentId/hostId; detalhes de lotes mistos retornam somente o escopo selecionado. Leitores podem acessar esses GETs; alterações seguem autorização e CSRF existentes.

Filtros da lista: type=ALL|ASSESSMENT|AGENT_UPDATE, status=ALL|ACTIVE|SUCCEEDED|FAILED|UNCONFIRMED|CANCELLED|EXPIRED|PARTIAL, q, from, to, page, pageSize. Usar convenções UTC e limites existentes. ACTIVE inclui pendentes, em execução e sem confirmação. Lista ordenada por criação decrescente, com desempate por tipo/id.

Resposta da lista: {items,pagination:{page,pageSize,total,pages}}. Cada item: {id,type,title,status,createdAt,updatedAt,finishedAt,requestedBy,environmentId,environmentName,targetVersion,counts}. counts contém total e contagem por estado original (SUCCEEDED, WAITING, DOWNLOADING, INSTALLING, VERIFYING, UNCONFIRMED, FAILED, ROLLED_BACK, CANCELLED, EXPIRED; coletas usam QUEUED/RUNNING/PARTIAL). Campo finishedAt só existe para lotes integralmente terminais. O resumo de lote prioriza UNCONFIRMED, depois RUNNING, WAITING, FAILED (falha/rollback), SUCCEEDED (todas), CANCELLED (todas), EXPIRED (todas), PARTIAL (demais misturas terminais).

Detalhe: {request:<item>,machines:[{id,hostId,hostname,environmentId,environmentName,fromVersion,targetVersion,status,createdAt,updatedAt,finishedAt,diagnostic,events:[{at,status,diagnostic}],historyPartial}],skipped:[{hostId,hostname,reason}]}. Para coletas, uma máquina, com eventos derivados somente de timestamps existentes. Eventos de atualização persistem no pedido e são deduplicados/limitados a 100. Pedidos antigos mostram explicitamente histórico parcial sem inventar etapas. Novos pedidos congelam hostname/ambiente/solicitante. Preservar pedidos terminais de atualização quando a máquina é excluída, usando snapshots para consulta histórica. Coletas mantêm política atual.

## Interface

Novo módulo work-requests.js/css cuida de lista, filtros, detalhes e banner; app.js apenas integra contexto/rotas/sessão. Aproveitar aba jobs (Work requests), com acesso aos detalhes existentes das coletas por Ver resultado da coleta. Rota usa parâmetros request e requestType para reabrir detalhe após reload. Formulário de atualização permanece com acompanhamento compatível e oferece Ver em Work requests; botão final muda de Concluir para Fechar acompanhamento. A navegação ao histórico é explícita pelo link, preservando tratamento de ACK perdido/idempotência e o progresso no popup.

Banner no ambiente/máquina mostra versão, máquinas atualizadas/em execução/aguardando/sem confirmação e Ver detalhes; reconsulta a cada cinco segundos enquanto visível. Histórico lista um item por lote. Detalhe mostra totais, versões por máquina, eventos com horários e motivo amigável; cancelar somente WAITING para ADMIN. Ao fechar, navegar, expirar sessão ou trocar usuário, abortar requisições e limpar dados. Falhas de rede mantêm último estado com aviso de atualização indisponível; jamais convertem falha de rede em sucesso. Requisições automáticas usam X-Assessment-Background:1 para não prolongar sessão.

## Validação e entrega

Testes HTTP/SQLite para filtros, agregação, lote misto, leitor, isolamento de escopo, paginação, persistência/restart, legado parcial, exclusão de máquina sem perda do histórico, cancelamento e estados incertos. Teste de navegador com servidor real e desktop/mobile, reload, mudança de escopo, perfis e banner. Regredir testes de atualização existentes. Gerar nova versão compartilhada dashboard/agente pelo builder existente; não publicar remotamente nesta tarefa. Entregar comando de publicação manual e evidência. Projeto é ignorado pelo Git pai; preservar checkout atual e não forçar commit.
