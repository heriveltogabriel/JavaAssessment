# Arquivo JAR em coluna separada

Solicitado pelo usuário: exibir ojdbc6.jar e derby.jar em coluna própria, mantendo a versão separada na tabela Bibliotecas Java.

Adicionar Arquivo JAR antes de Versão. Datasources usam somente driverArtifact identificado; bibliotecas usam path. Mostrar o nome final quando terminar em .jar; sem evidência, mostrar traço. Nunca escolher arbitrariamente um candidato. O caminho completo continua nos detalhes. Renderizar como texto escapado. Reutilizar o inventário recebido, sem alteração no coletor ou no banco.

Validação: conferir Oracle, Derby, JAR aninhado e ausência/ambiguidade; executar verificação de sintaxe e testes existentes de JDBC/UI. Esta alteração de fonte não modifica os pacotes 2.1.2 publicados; publicação é uma etapa separada.
