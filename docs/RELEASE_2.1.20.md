# Java Assessment 2.1.20

## Profile/contexto dos datasources

A aba **Datasources JDBC** mostra a coluna **Profile/contexto** logo após o nome/JNDI. O mesmo campo aparece nos detalhes. Assim, dois `ExampleDS` declarados em perfis diferentes do JBoss, como `full` e `full-ha`, podem ser distinguidos.

A interface usa `configurationContext` já coletado: mostra o nome do perfil do domínio, Standalone ou o contexto informado. Quando ausente, exibe **Não identificado**. A coluna pode ser ocultada pelo seletor existente. A alteração não une registros, não muda a associação aos processos e não confirma uso do datasource em execução. Formatos de exportação permanecem iguais.

Inventários que já contêm esse campo mostram a informação após atualizar o dashboard, sem nova coleta obrigatória. Não houve mudança funcional no agente.

## Instalação e atualização

Use o [web-update 2.1.20](../install/v2.1.20/assessment-dashboard-web-update-2.1.20.zip) em **Administração → Atualização do dashboard**. Consulte [os pacotes](../install/v2.1.20/README.md) e [as validações](validation/console-release-2.1.20.md) para as origens verificadas. Atualização direta da 2.1.18 e da 2.1.16 revisada validada em ambiente isolado. A ponte interna 2.1.19 é reservada pelo build; não precisa ser instalada manualmente. O instalador completo é destinado a servidor novo.

**Atualizar o dashboard não atualiza os agentes automaticamente.** Abra **Revisar agentes** para conferir versões e elegibilidade. Para alinhar a versão do agente, atualize separadamente pelo painel quando elegível; instalações sem rota/capacidade compatível precisam do pacote configurado para o ambiente. Essa melhoria de apresentação não exige atualizar o agente.

Nenhum deploy remoto acompanha a publicação dos pacotes. Instaladores anteriores preservados.
