# Campanhas de atualização de agentes

Escopo aprovado: revisão paginada, seleção explícita ou todos do filtro, campanhas persistentes em grupos de 10, concorrência global limitada, pausa/retomada, cancelamento em lote/selecionados e repetição de falhas. 1500 não é limite; validar 5000 hosts e permitir crescimento sem teto de negócio fixo.

## Arquitetura

Manter protocolo e binários dos agentes. Usar `agent_update_batches` como campanha e `agent_update_requests` como alvos persistentes, com campos adicionais no JSON para campanha/admissão/cancelamento. Campanhas novas têm grupos de 10 (configurável 1–100), concorrência global 2 (ajustável 1–10). Slots incluem WAITING admitido e operações iniciadas/sem confirmação; pontes contam no slot do pai. Instalações em andamento não perdem autorização ao pausar/cancelar.

Selecionar por páginas de 50 via SQL, busca, ambiente, versão e presença. Não selecionar automaticamente. Selecionar todos do filtro gera snapshot privado no servidor, vinculado ao administrador e versão, com validade curta; confirmar usa o snapshot e chave idempotente, sem enviar milhares de hosts ao navegador. Mostrar resumo de seleção e quantas têm contato. Alvos são materializados por SQL e verificados antes da admissão. O catálogo da release fica fixado; mudança de release pausa novas partidas.

Pedidos na fila não possuem prazo de início correndo. Só ao admitir um host iniciam os cinco minutos para claim. Hosts sem contato recente são expirados explicitamente para nova tentativa posterior. Dentro do grupo, o scheduler admite apenas a capacidade disponível. O próximo grupo abre após resolução do grupo atual. Resultado desconhecido pausa a campanha e conserva o slot; três falhas/rollbacks desde a última retomada também pausam. Falha de um host não cancela todos os demais automaticamente na campanha nova.

Pausa/cancelamento bloqueiam claims de pedidos não iniciados inclusive pontes já entregues. Retomada reinicia a janela de pedidos que ainda não foram iniciados; não renova a autorização de operações já iniciadas. Cancelamento marca a intenção em uma transação e termina em blocos limitados pelo scheduler, independentemente do navegador. Cancelar selecionados e cancelar todos do filtro afetam apenas WAITING; filtros nunca expandem a seleção depois da confirmação. Legacy batches também podem ser cancelados em lote.

Detalhe de Solicitações paginado e com filtro de máquina/estado/ambiente; eventos carregam ao expandir uma máquina. Contadores são agregados SQL sobre toda a campanha. Pausa/cancelamento e progresso ficam visíveis. Repetir falhas/expirados/cancelados gera nova seleção revisável, sem repetir sucessos nem operações desconhecidas.

Persistência SQLite e scheduler de manutenção continuam após reinício. Todas as admissões, claims e mudanças administrativas usam a transação serializada existente, evitando corrida de slots. Compatibilidade da API antiga é mantida para o Ansible; quando campanhas ativarem o controle global, pedidos antigos também respeitam o teto.

## Validação

Testes com 5000 hosts para seleção, paginação, criação com resposta limitada, avanço, snapshots, cancelamento e reinício. Corridas entre claim/cancelar/pausar, ponte e pai, operações desconhecidas e reautenticação administrativa. Testes de interface real com navegação entre páginas e nenhuma seleção automática. Executar regressão de origem e suites de atualização/solicitações. Sem novo número de versão, build ou deploy antes da validação.
