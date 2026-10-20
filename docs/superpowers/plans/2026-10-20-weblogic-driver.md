# Driver JDBC WebLogic — plano de implementação

Execução local na mesma entrega pendente de Spring, sem incremento de versão, pacotes, assinatura, push ou deploy. Especificação fornecida pelo usuário em 20/10/2026; execução sequencial com as skills de planejamento.

**Objetivo:** resolver o JAR e a versão por PID, com precedência, motivos específicos e candidatos visíveis.
**Arquitetura:** ProcessLaunch conserva apenas caminhos permitidos; HsPerfData compartilha os JARs mapeados já lidos. JdbcDriverResolver faz percurso em profundidade, usa cache de manifestos/consulta da classe exata e registra a decisão por associação. O dashboard e exportações mostram confiança, proveniência e divergências.
**Tecnologias:** Java 6 (ECJ) no agente, Java 17/SQLite no servidor, JavaScript no painel.

## Decisões da revisão
- A ordem determinística fornece evidência de precedência, não observação direta de uma classe carregada. Arquivo aberto é só PROBABLE.
- Diretórios ext e wildcards têm ordem interna não garantida; tratá-los como grupos, como lib do domínio. Não ordenar alfabeticamente para fabricar um vencedor.
- Uma falha antes de um possível vencedor bloqueia a decisão; falhas posteriores não invalidam um vencedor confirmado.
- Inspecionar a classe exata pelo diretório central/consulta pontual, inclusive em arquivos renomeados. Uma heurística pelo nome do JAR não prova ausência da classe.
- Candidatos direcionados no Oracle Home só vencem se confirmados na cadeia; outras versões em disco são informativas.
- Sem amostra de componente JDBC no registry.xml, não inferir versão por patches ou versão WebLogic.
- Preferência de classloader em módulos de aplicação é isolada dos módulos de sistema; configuração incerta gera motivo explícito.

## Etapas
- [x] 1. Ampliar caminhos boot/ext/perf e compartilhar maps; resolver precedência DFS e preservar cache. Testar PRE_CLASSPATH, cadeia longa, domínio e versões de Oracle Home lado a lado.
- [x] 2. Desempatar grupo sem ordem com fd/maps limitados; testar provável, ambíguo, indisponível e deleted.
- [x] 3. Implementar prioridade de versões manifesto/Specification/pom/pasta/nome, família e proveniência. Testar ausência e conflito sem inferir pelo produto.
- [x] 4. Gravar candidatos, motivo e decisão por associação; agregar consenso ou VARIES. Testar quatro PIDs, limites antes/depois do vencedor e opt-out.
- [x] 5. Atualizar tabelas/detalhes, suporte e relatórios/PDF/CSV, mantendo registros antigos. Testar renderização, escaping e exportação.
- [x] Rodar ops/test-source.sh com JDK 17, compilar coletor ECJ source/target 1.6, atualizar notas pendentes/TODO e revisar git diff --check.

Referências: especificação JAR (Class-Path imediatamente após o JAR de contexto) e documentação Oracle WebLogic de classloading/JDBC. Validação no cliente, JVM Java 6 real e inventário de patches JDBC ficam explicitamente separados dos testes sintéticos locais.
