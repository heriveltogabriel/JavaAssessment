# Java Assessment 2.1.4 — coluna Arquivo JAR

Na tabela Bibliotecas Java, Arquivo JAR e Versão aparecem em colunas separadas. Datasources mostram o nome do driver identificado, como ojdbc6.jar e derby.jar; bibliotecas mostram o nome do JAR encontrado, inclusive arquivos aninhados. O caminho completo permanece nos detalhes. Sem identificação única, a interface não escolhe um candidato arbitrariamente.

O ajuste reutiliza os campos já coletados na 2.1.2. Não exige nova coleta nem atualização dos agentes que já enviam esses campos. A melhoria de timeout nos lotes permanece apenas no TODO.

**Atenção aos agentes:** publicar o dashboard não os atualiza automaticamente. Agentes 2.1.2 podem permanecer nessa versão para este ajuste visual. Para máquinas anteriores que ainda não coletam a versão/caminho do driver, confira a elegibilidade em Revisar agentes; use atualização pelo painel quando compatível ou o instalador completo configurado do ambiente quando não houver rota. A 2.1.3 é ponte interna de migração, sem instalação manual intermediária para V3.

Dashboard de laboratório publicado e confirmado por HTTPS em 19/10/2026 às 18:28 BRT, com configuração e backup da 2.1.2 preservados.

Pacotes e validações em [install/v2.1.4](../install/v2.1.4/README.md). Publicação de laboratório com backup da versão anterior; confira o resultado nas [evidências](validation/jdbc-jar-column-2026-10-19.md).
