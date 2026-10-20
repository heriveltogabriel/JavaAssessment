# Versão do driver JDBC no WebLogic

A coleta passiva associa a classe configurada no datasource aos arquivos vinculados ao processo WebLogic em execução. A busca inclui classpath explícito, CLASSPATH do processo quando não há opção explícita, lib do domínio e referências locais Class-Path dos manifestos. Não executa scripts nem JARs, não injeta código na JVM e não abre conexão com o banco.

O Oracle JDBC é o cenário principal: ojdbc6/7/8/11, nomes personalizados e classes OracleDriver (atual/legada) e OracleXADataSource. O nome do arquivo sozinho não define a versão. O coletor verifica a presença da classe exata e lê metadados internos do manifesto ou Maven. Derby recebe tratamento próprio para normalizar seu formato de versão.

## Resolução consolidada na 2.1.10

O motor resolve separadamente cada processo e prioriza bootclasspath, extensões de Java até 8, classpath efetivo e referências locais dos manifestos em profundidade. A biblioteca do domínio é um grupo sem ordem garantida. Diretórios de módulos Oracle Home são inspecionados de forma direcionada; um JAR encontrado somente em disco não vence um JAR da cadeia do processo. Aplicações com preferências de classloader têm escopo próprio; suas bibliotecas não são atribuídas a datasources de sistema.

A versão segue: Implementation-Version, Specification-Version, pom.properties JDBC, pasta do módulo Oracle JDBC e versão explícita no nome do arquivo. `ojdbc8.jar` informa família, não uma versão numérica do driver. A versão do WebLogic não é usada como versão JDBC.

## Interpretar o resultado

- **Em uso (ordem de carregamento) — IDENTIFIED:** classe exata selecionada por precedência determinística. É evidência passiva; não confirma diretamente a classe carregada pela JVM.
- **Provável — PROBABLE:** em um grupo sem ordem, apenas um candidato foi observado aberto ou mapeado pelo processo. A versão e o suporte ficam qualificados como prováveis.
- **Múltiplos candidatos — AMBIGUOUS:** não existe evidência suficiente para desempatar. Não há seleção alfabética.
- **Varia por instância/servidor — VARIES:** os processos associados têm resultados diferentes. Consulte a decisão por PID.
- **Inspeção incompleta — INCOMPLETE:** uma falha de acesso, exclusão ou limite anterior à decisão impediu escolher com segurança. Falhas posteriores não anulam uma decisão já sustentada.
- **Versão indisponível — VERSION_UNAVAILABLE:** a classe foi encontrada no arquivo selecionado, mas a versão permanece desconhecida.
- **Driver não localizado — NOT_FOUND:** nenhum candidato foi encontrado no escopo examinado.

Os detalhes mostram caminho, ordem, origem, versão, origem da versão, arquivo aberto e seleção por precedência. O campo técnico `loaded` representa essa seleção, não instrumentação da JVM. Um arquivo marcado como substituído em disco recebe observação de reinício pendente; a versão lida pertence ao arquivo atual em disco.

Bibliotecas e datasources têm JAR, versão e suporte separados. Relatórios JDBC oferecem origem da versão e motivo da identificação; os resumos JDBC dos demais modelos, PDF e CSV preservam a evidência. A versão do driver não é a versão do banco. Registros antigos continuam legíveis, sem fabricar evidências ausentes.

O mesmo motor atende [Tomcat e JBoss/WildFly](TOMCAT_JBOSS_DISCOVERY.md). Em módulos JBoss sem arquivo disponível, uma coordenada de artefato pode fornecer versão declarada, marcada como provável e `versionSource: module-artifact`, sem afirmar que o arquivo foi carregado.

## Limites e privacidade

Até 256 arquivos contendo classes JDBC e 512 referências no cache, profundidade de manifestos 24, 32 candidatos por decisão e 4.096 descritores de arquivo por processo. Índices ZIP têm limite de 65.535 entradas/8 MiB e o cabeçalho do manifesto, 64 KiB. A leitura compartilha os orçamentos globais de metadados, arquivos e tempo. Heap permanece em 96 MiB; não houve aumento dos padrões operacionais nesta mudança.

Bibliotecas de aplicações EAR/WAR são limitadas a 32 JARs internos e 4 MiB descomprimidos por JAR. Preferências e bibliotecas diretamente em APP-INF/lib ou WEB-INF/lib são examinadas. WARs dentro de EARs e classloaders personalizados exigem validação adicional; ausência de evidência não é tratada como confirmação.

Somente leitura local: sem attach, execução de JARs/scripts, conexão administrativa ou conexão ao banco. Referências remotas não são seguidas. Ambiente bruto, linha de comando, senha e URL JDBC completa não são enviados. Exclusões e inspeção desabilitada continuam respeitadas.

Inventário de patches JDBC via registry.xml permanece pendente até haver uma amostra validada. Não há inferência de driver pela versão ou pelo patch geral do WebLogic. Testes sintéticos e compilação Java 6 não substituem coleta real em RHEL 5/Java 6.

Referências de precedência: [especificação JAR](https://docs.oracle.com/en/java/javase/26/docs/specs/jar/jar.html), [classloaders WebLogic](https://docs.oracle.com/middleware/12213/wls/WLPRG/classloading.htm) e [configuração JDBC](https://docs.oracle.com/middleware/12213/wls/JDBCA/config.htm).

## Testar no laboratório

Na RHEL5 foi criado `LAB_ORACLE_DRIVER_SIMULATION`, descritor não referenciado pelo config.xml, com classe oracle.jdbc.OracleDriver. É uma configuração de simulação, **não um datasource ativado nem uma conexão com Oracle**. O JAR real do WebLogic forneceu versão 11.2.0.3.0; Derby forneceu 10.12.1.1. Após atualizar o agente e solicitar nova coleta, confira Bibliotecas Java e o relatório JDBC.

**Atualize os agentes separadamente.** Publicar o dashboard não recalcula inventários antigos. Confira a elegibilidade no painel, atualize uma máquina e execute uma coleta; instalações sem rota compatível usam o instalador completo configurado pelo ambiente.
