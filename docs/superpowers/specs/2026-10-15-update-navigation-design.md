# Atualização e navegação — escopo autorizado

O usuário autorizou em 2026-10-15 preparar a próxima versão com a correção da disputa do helper legado e dois ajustes de interface.

Após um POST de atualização confirmado pelo servidor, abrir automaticamente o Work request do `batchId` retornado. Usar o histórico de todos os ambientes para não ocultar máquinas de um lote iniciado pelo aviso global. Falha ou resposta perdida mantém a confirmação idempotente existente; zero pedidos ou ausência de identificador mantém a janela com os motivos. Fechar a janela somente depois de guardar o identificador em variável local. O botão manual continua como alternativa para respostas antigas.

Adicionar “Java Assessment” no menu lateral entre Overview e Environments, seguindo os ícones e espaçamento existentes. Abrir `#environment/hosts?env=all`, limpar busca, máquina, datas e paginação anteriores, persistir All environments e destacar o item em qualquer aba do workspace de inventário. Disponível também para leitores, respeitando as permissões atuais.

A alternativa de apenas acrescentar um botão de acompanhamento manteria o clique extra relatado. Reaproveitar a rota e a tela existentes atende diretamente ao pedido sem criar outro painel.

Preparar um novo par bridge/alvo imutável, incorporando a correção já revisada de parada do helper. Preservar os pacotes 2.0.11 e o anchor herdado. Publicação remota continua pelo script operacional executado pelo usuário. Registrar o sucesso informado da migração RHEL 5/Java 6 e do assessment, sem confundi-lo com os gates de falhas/reboot ou de 32 bits.

Validar navegação automática com API real local, confirmação repetida/idempotente e erros; atalho com escopo anterior e leitor; regressão do handoff; empacotamento, assinaturas e compatibilidade do novo candidato. Os gates conhecidos do anchor imutável e das falhas nativas permanecem explícitos.
