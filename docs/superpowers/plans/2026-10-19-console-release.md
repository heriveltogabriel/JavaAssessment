# Entrega para teste pela console — 19/10/2026

Escopo autorizado: consolidar alterações JDBC já validadas no laboratório, corrigir o JAR do datasource Spring e o bloqueio do lote por máquina desligada; atualizar documentação e Git; gerar pacote para o operador atualizar pela interface. Não publicar por SSH nesta entrega.

1. Propagar driverArtifact da biblioteca única associada à aplicação Spring; manter ausência em ambiguidade e remover associação incompatível após relato opcional de outra versão.
2. Persistir prazo de cinco minutos quando a máquina chega à frente do lote. A manutenção periódica (30 s) expira apenas pedidos ainda não iniciados e libera a próxima máquina. Manter validade global do pedido e recuperação de operações iniciadas. Bloquear claims tardios V1/V3 e cancelar bridge não iniciado.
3. Validar regressões de coleta/relatórios/exportações e timeout (reinício, ausência de heartbeat, bridge, retorno tardio e instalação iniciada). Manter versão durante os testes.
4. Após os testes, reservar uma entrega final e sua ponte interna, verificar pacotes e atualizar README, TODO, notas e instruções da console.
5. Enviar código, documentação e pacotes a Git. Entregar o ZIP web para teste manual; registrar esse teste como pendente até confirmação do operador.
