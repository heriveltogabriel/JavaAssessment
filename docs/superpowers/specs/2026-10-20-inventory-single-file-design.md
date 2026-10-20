# Revisão da exportação na 2.1.16

O usuário autorizou substituir os instaladores da 2.1.16 sem incrementar a versão e confirmou que sua console já está nessa versão. Esta autorização específica prevalece sobre a regra normal de imutabilidade de releases; o Git manterá o histórico anterior. Preservar os bytes dos pacotes, rotas e assinaturas dos agentes.

A aba administrativa oferece JSON ou CSV. O ZIP contém exatamente um arquivo: inventario.json ou inventario.csv. JSON inclui metadados e oito coleções dentro de um documento. CSV tem uma linha por recurso, colunas técnicas e objetos/listas completos serializados nas células, conforme escolha explícita do usuário. Inclui uma linha de metadados para preservar identificação/data/contagens mesmo com inventário vazio. As colunas de contexto identificam máquina e ambiente.

Escopo: todo o inventário técnico atual armazenado no SQLite, mantendo exclusão de dados de acesso, usuários, senhas, tokens, chaves, configurações operacionais e comandos brutos. Não é dump integral/backup do banco. A seleção explícita de campos, limpeza de textos, leitura consistente, limites e autorização administrativa continuam. CSV deve escapar aspas, quebras de linha e fórmulas.

A atualização web normal continua exigindo versão maior. Para a 2.1.16 original instalada, entregar script local que verifica estado terminal do executor, pausa o executor para evitar concorrência e usa o atualizador de script existente com baseline original reconhecida. Preservar configurações, SQLite, identidade e agentes. Não instalar remotamente sem solicitação.

Validar formatos, vínculos, exclusão de credenciais, conteúdo aninhado, CSV com fórmulas/Unicode, seleção na interface, HTTP e downloads reais. Reconstruir instaladores na mesma versão somente após validação, guardar os artefatos originais localmente, conferir hashes internos, assinaturas e agentes inalterados; atualizar documentação e publicar na main.
