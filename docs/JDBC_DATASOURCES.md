# Datasources JDBC de Tomcat e JBoss/WildFly

O coletor identifica declarações de datasource nas configurações dos servidores associados a processos Java observados. A descoberta acontece antes da inspeção de JARs e utiliza o diretório do processo, mesmo fora dos caminhos de varredura geral. Exclusions e montagens de rede continuam prevalecendo.

## O que foi encontrado na investigação

O código anterior criava registros de datasource a partir da configuração base de Spring Boot e do snapshot JMX de WebLogic. Tomcat e JBoss/WildFly eram identificados por processo e metadados de produto/driver, mas seus XMLs de datasource não eram lidos. As fontes arquivadas nas versões 1.5.3, 1.5.4, 1.5.6, 1.5.7, 1.5.8, 1.5.9, 1.5.10 e 1.5.11 confirmam essa lacuna: os dois pontos de inclusão de datasources são Spring e WebLogic; não há descoberta de `server.xml` ou `standalone.xml`.

A evidência local `docs/application-discovery-limited-validation.json`, de 9 de outubro de 2026, também registra zero datasources em `instance-ol8` e `instance-web`, com cobertura limitada por permissões. O histórico `docs/superpowers/plans/2026-10-09-jdbc-summary.md` registra uma investigação anterior sobre a diferença entre contagem de drivers e datasources e a implementação posterior de descoberta Spring.

Esses arquivos demonstram uma lacuna de descoberta que esta mudança corrige. Não demonstram o motivo exato da mudança de contagem observada agora no ambiente real de Desenvolvimento: o estado atual dos processos e configurações dessas VMs não foi inspecionado nesta validação local.

## Suporte

- **WebLogic:** domínio obtido de `weblogic.RootDirectory` ou do diretório de trabalho do processo observado. Lê `config/config.xml` e somente os módulos JDBC ali referenciados em `config/jdbc`. Retém nome, JNDI, classe de driver, família de banco, capacidade máxima configurada e targets declarados. Arquivos órfãos, caminhos que escapam do diretório, credenciais e URLs completas não entram no inventário. Associações indicam processos observados usando o domínio; os targets configurados são preservados separadamente e não comprovam implantação ou saúde do pool. Não cobre módulos dentro de EAR, deployment plans, sobreposições nem configurações mantidas somente em runtime. A integração opcional de snapshots JMX anteriores permanece independente, com seu horário original.

- **Tomcat:** `CATALINA_BASE/conf/server.xml` (`GlobalNamingResources/Resource` e `Context/Resource`), `conf/context.xml`, descritores `conf/<Engine>/<Host>/*.xml` e `context.xml.default`, e `webapps/<aplicação>/META-INF/context.xml` de aplicações expandidas. Os recursos precisam declarar `javax.sql.DataSource` ou `javax.sql.XADataSource`. `ResourceLink` referencia o recurso global, sem aumentar sua contagem.
- **JBoss/WildFly standalone:** `standalone/configuration/standalone.xml`, ou o diretório indicado por `jboss.server.base.dir`/`jboss.server.config.dir` e o arquivo selecionado por `-c`, `--server-config` ou `--read-only-server-config`, com formas `=arquivo` quando aplicáveis. Suporta o namespace `urn:jboss:domain:datasources:*`, `datasource`, `xa-datasource`, referências às classes declaradas em `drivers` e limites configurados de pool. Não lê configurações alternativas quando o processo selecionou outra.
- O mesmo arquivo e recurso/contexto não são contados outra vez por processos que o compartilham; cada associação é preservada separadamente. Recursos com o mesmo nome em diferentes contextos/origens continuam distintos.

## Significado dos registros

`evidenceLevel=configuration` e `state=CONFIGURED` significam **configuração encontrada**. Um processo Java observado e um XML selecionado não comprovam ativação efetiva, carregamento do driver, conexão com banco ou saúde do pool. O arquivo pode ter mudado após a inicialização.

O coletor conserva nome do recurso, origem, contexto, horário, associação ao processo/servidor/aplicação, classe do driver, tipo de banco deduzido sem reter a URL e máximo de pool configurado quando disponível. Não inventa versão do driver ou métricas de execução. `configuredMaxPoolSize` é diferente de capacidade/uso medidos em runtime.

`enabled=false` em JBoss produz `state=DISABLED` e `configuredEnabled=false`. Expressões não resolvidas não são executadas; ativação variável aparece com `activationUnresolved=true`. Declarações desativadas continuam sendo configurações inventariadas, sem alegar que estão ativas.

## Segurança e limites

O parser SAX bloqueia DOCTYPE, entidades externas, DTD externo e XInclude, usa processamento seguro e nunca faz requisições de rede ou abre conexão JDBC. Não executa classes descobertas e não altera arquivos ou permissões das aplicações. Usuário, senha, URL JDBC completa, propriedades XA e XML bruto não entram no resultado, cache ou diagnóstico. Erros do parser têm mensagem genérica, pois exceções poderiam conter valores do arquivo.

Limites: 256 KiB por XML, 128 arquivos por coleta, 512 entradas examinadas por diretório, 12.000 elementos e 64 níveis por XML, 1.024 caracteres por contexto, 512 registros de datasource no inventário e no cache agregado e 16 associações por recurso. Ao atingir o limite de linhas, novos arquivos deixam de ser lidos; recursos já em cache ainda podem receber associações de outros processos. Compartilha os limites globais de arquivos, tempo e 8 MiB de leitura de metadados. Arquivos ausentes obrigatórios, inválidos, inseguros, inacessíveis ou acima dos limites geram aviso de cobertura; o inventário antigo não é reapresentado como atual.

A descoberta é vinculada a processos identificados. Não cobre servidor parado, perfil domain ou JBoss legado, configuração somente em management runtime, Agroal, descritores dentro de WAR não expandido, caminhos Tomcat customizados de `appBase`/`xmlBase` ou configurações externas herdadas que não estejam nos locais descritos. Não resolve variáveis, sobreposições, perfis nem seleção de recursos efetivos por aplicação.

## Validação e uso

`assessment.ServerDataSourcesTest` cria configurações e `/proc` sintéticos sem iniciar servidores de aplicação ou bancos. Cobre Tomcat e JBoss, namespaces, XA/desativação, configuração selecionada, associação e deduplicação, XML inválido/XXE, sigilo por canários, limites, exclusões e regressões. O conjunto passou 77 verificações com heap de 64 MiB, incluindo arquivos repetidos e XMLs com contextos artificialmente aninhados, além de compilação com bytecode Java 6 e `--release 8`. A opção `--export caminho.json` exporta os registros reais gerados por essas fixtures para testes autenticados de ingestão e overview. As regressões existentes Spring, WebLogic, inventário de JVM, launcher, arquivos e coleta também devem passar.

Para repovoar o dashboard de uma máquina que usa esses formatos, atualize o agente para a versão que inclui `ServerDataSources` e solicite uma nova coleta. Publicar apenas o dashboard não acrescenta dados que o agente antigo não envia. Depois confira Work requests, avisos de cobertura e o caminho de origem de cada datasource. Uma nova coleta realmente vazia deve continuar vazia, em vez de preservar dados antigos como se fossem observações novas.

## Referências primárias

- [WebLogic 10.3.6 JDBC resources](https://docs.oracle.com/middleware/11119/wls/JDBCA/config.htm): módulos referenciados em `config.xml` e descritores em `config/jdbc`.

- [Tomcat JNDI Resources](https://tomcat.apache.org/tomcat-9.0-doc/jndi-resources-howto.html): configuração dos recursos em Context e GlobalNamingResources.
- [Tomcat Context](https://tomcat.apache.org/tomcat-9.0-doc/config/context.html): descritores por aplicação, Resource e ResourceLink.
- [WildFly Admin Guide](https://docs.wildfly.org/26/Admin_Guide.html): argumentos de seleção de configuração e diretório `jboss.server.config.dir`.
