# Profile/contexto dos datasources

Escopo aprovado na conversa: mostrar o profile/contexto para distinguir datasources homônimos; rótulo exato solicitado pelo usuário: **Profile/contexto**.

Usar `configurationContext` já enviado pelo coletor. Na tabela Datasources JDBC, inserir a coluna logo após Datasource / JNDI. Nos detalhes, adicionar o mesmo campo. Remover somente o prefixo técnico `domain profile ` para mostrar o nome do perfil; traduzir standalone e JBoss deploy; preservar demais contextos. Ausência, tipo inválido ou perfil vazio resultam em “Não identificado”. Escapar a apresentação como os outros valores da interface.

Não consolidar registros por nome, não alterar identidade, associação a processos, coleta, formatos de exportação nem versão. A coluna participa do seletor existente e aparece por padrão. Inventário antigo que já contém o campo ganha a informação sem nova coleta.

Alternativas avaliadas: apenas detalhes exigiria abrir cada ocorrência; apenas subtítulo dificultaria comparar linhas. Coluna e detalhes atendem à comparação pedida.

Validar nomes iguais em perfis distintos, standalone, contexto genérico, dados ausentes e texto HTML. Executar os testes existentes da interface JDBC e verificar sintaxe. Nenhum instalador, deploy ou nova versão nesta alteração.
