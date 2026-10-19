# Versão do driver JDBC por datasource

Proposta aprovada pelo usuário em 2026-10-19, com implementação e publicação no laboratório autorizadas.

O coletor deve associar a classe declarada no datasource de um domínio WebLogic em execução aos JARs acessíveis a esse processo. A busca inclui classpath explícito ou CLASSPATH do processo quando não há opção explícita, referências locais Class-Path de manifestos e lib do domínio. Não executa scripts, JARs, attach, WLST ou conexões com o banco. JMX continua opcional pela integração de snapshot existente; não serão provisionadas credenciais novas.

Cada candidato precisa conter a classe exata. A versão vem de metadados internos (manifesto, Maven e metadados Derby), com origem explícita. JAR renomeado deve funcionar. Arquivo encontrado não comprova carregamento na JVM: resultado único é identificado como evidência de arquivo, nunca runtime. Mais de um candidato é ambíguo, sem seleção arbitrária por ordem de diretório. Metadados conflitantes também não produzem uma versão única. Datasources compartilhados por processos mantêm candidatos e associações sem confundir domínios.

A leitura é limitada a 32 MiB de metadados globais, prazo, arquivos e até 256 archives JDBC por coleta (512 referências locais), com cache por caminho; links respeitam as exclusões. Manifestos aceitam somente referências locais, com profundidade máxima 8 e ciclos deduplicados. Logs só podem complementar uma evidência explícita por classe e servidor; esta entrega não implementará interpretação genérica de logs sem formato confiável. Se orçamento, permissões ou classloader impedirem resolução, apresentar incompleto/ambíguo em vez de afirmar uso.

Campos: driverVersion, driverArtifact, driverVersionEvidence, driverResolution, driverCandidates. Detalhes e tabela exibem versão/estado; relatórios JDBC oferecem caminho, evidência, resolução e candidatos; resumos JDBC de todos os modelos e exportação unificada incluem essas informações. Não coletar URL JDBC completa, propriedades arbitrárias ou segredos.

Validação: JAR renomeado, versão Maven/manifesto/Derby, classpath indireto, ciclos, múltiplos candidatos, versões conflitantes, múltiplos domínios/processos, limites e exclusões. Regressões de relatórios/CSV/UI e Java 6. Só reservar nova versão depois das verificações. Publicar pelo executor independente já instalado, verificar HTTPS/versão/serviços, preservar backup e avisar que os agentes precisam ser atualizados separadamente.
