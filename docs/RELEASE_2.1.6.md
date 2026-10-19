# Java Assessment 2.1.6

Entrega preparada para teste de atualização pela console. O dashboard de laboratório permanece na 2.1.4 até a confirmação do operador; nenhum deploy desta entrega foi feito por SSH.

## Alterações

- Datasources Spring passam a trazer o caminho do JAR do driver quando há uma única biblioteca associada à aplicação. O nome aparece separado da versão em Bibliotecas Java; o campo também alimenta relatórios e exportações. JARs ambíguos permanecem sem seleção. Um relato opcional de outra versão remove a associação anterior incompatível.
- Cada máquina tem cinco minutos para iniciar a atualização quando chega sua vez no lote. Se não iniciar, o pedido expira com motivo explícito e a próxima máquina recebe sua vez. A manutenção roda a cada 30 segundos, mesmo sem navegador ou heartbeat. Prazo e resultado persistem após reinício.
- Pedidos expirados não podem ser executados quando o agente volta. Crie um novo pedido. Instalações já iniciadas mantêm recuperação e confirmação existentes; falha real continua interrompendo o lote. A validade global configurada do pedido continua sendo respeitada.
- Consolida descoberta de versões JDBC WebLogic, evidências e colunas Arquivo JAR/Versão das entregas anteriores.
- Manual e Guia de instalação revisados, com nomes genéricos e instruções da console.

## Testar a atualização pela tela

1. Em **Administração → Atualização do dashboard**, envie `assessment-dashboard-web-update-2.1.6.zip`.
2. Aguarde **Pronto para atualizar** e confira origem/destino. É possível cancelar a preparação.
3. Informe a senha administrativa e confirme **Atualizar e reiniciar**. Aguarde o retorno; se necessário, faça login novamente e abra a mesma tela.
4. Confira **Atualização concluída**, versão 2.1.6, máquinas e relatórios. O teste real do upload/confirmar/reconectar pelo operador permanece pendente.

Use o ZIP **web-update** para esta operação. O ZIP **installer** é exclusivo para servidor novo. Origens e hashes aceitos são verificados pelo executor; instalação alterada é recusada para diagnóstico.

**Atenção: atualize também os agentes.** O dashboard não os atualiza automaticamente. Para obter o JAR nos datasources Spring, atualize um agente elegível pelo painel e solicite nova coleta. A 2.1.5 é ponte interna de migração, não um salto manual para agentes V3. Sem rota elegível ou sem executor root funcional, use o pacote completo configurado do ambiente.

A versão do driver/JAR não é a versão do banco. A coleta passiva não comprova carregamento real do driver nem conectividade JDBC. A integração opcional fornece evidências distintas.

[Validação](validation/console-release-2026-10-19.md) · [Pacotes](../install/v2.1.6/README.md) · [Operação da atualização](DASHBOARD_SELF_UPDATE.md)
