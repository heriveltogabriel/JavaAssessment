# Atualização com histórico de desinstalações

Bug reproduzido: o atualizador da entrega1.0.1-beta exige `count(uninstall_requests)=0`, recusando até pedidos já concluídos. A falha reportada ocorre antes da parada do serviço e da troca de componentes.

A correção permite os estados terminais SUCCEEDED, FAILED, CANCELLED e EXPIRED, preservando todos os registros. Os estados ocupados definidos em UninstallRegistry.busy (WAITING, UNINSTALLING e UNCONFIRMED) continuam bloqueando, assim como estados desconhecidos. Integridade, contagens e agrupamento de estados são lidos na mesma transação SQLite somente leitura. Nenhuma alteração de estado ou cancelamento é feita pelo publicador.

Conservar as três checagens: antes da preparação, depois de parar o serviço (fecha a corrida com novos pedidos) e depois de iniciar o código novo. Contagens de histórico não podem diminuir. Backup e rollback existentes continuam preservando banco/configuração/CA/versão. Diagnósticos não expõem payload, identidade ou token de pedidos.

Gerar entrega imutável1.0.2-beta com o fluxo sequencial existente. A1.0.1-beta anterior não é sobrescrita. Testar SQLite real com todos os estados, ausência de tabela, rechecagem com um pedido novo, ausência de escrita e o fluxo de sucesso/rollback com histórico não vazio. Publicação manual pelo usuário, sem acesso remoto pelo Codex.
