# Descoberta passiva de aplicações Spring

Este documento descreve a descoberta consolidada na entrega 2.1.10. O coletor consolida destinos JDBC, classpath e associação do JAR ao datasource. Para novos campos, atualize o agente e solicite nova coleta; inventários anteriores não são recalculados.

A inspeção de arquivos (`inspectArchives`) deve estar habilitada no assessment. O coletor relaciona metadados à aplicação em execução sem carregar classes, iniciar JVMs da aplicação ou abrir conexões JDBC.

## Cobertura

- JAR executável Spring Boot e WAR executável com launchers reconhecidos, nos layouts BOOT-INF/classes e WEB-INF/classes.
- Aplicação iniciada por launcher ou classe principal própria, com evidência do núcleo Spring Boot e diretórios/JARs explícitos no classpath. A ordem dos recursos é preservada e o artefato da classe principal fornece a identidade.
- Nome/versão do manifesto ou build-info.properties; versão Spring Boot do manifesto; bibliotecas de servidor embutido aparecem como evidência de pacote, não como servidor independente confirmado.
- application.properties, application.yaml e application.yml de base e dos profiles indicados; configuração empacotada seguida da configuração externa local.
- spring.config.name, spring.config.location e spring.config.additional-location nos parâmetros de inicialização ou variáveis de ambiente correspondentes.
- Variáveis e argumentos permitidos de datasource; placeholders exatos como ${DATABASE_URL} e ${DATABASE_URL:jdbc:h2:mem:example}. Valores arbitrários, senhas e URLs JDBC completas não são gravados no inventário. Para formatos conhecidos, extrair somente host/IP, porta e banco/serviço em campos estruturados; múltiplos destinos são preservados como configuração, sem comprovar conexão. Banco em memória e alias TNS não geram host fictício.
- Datasource padrão, prefixos extras cadastrados e prefixos detectados automaticamente nas chaves terminadas em `datasource.url`, `datasource.jdbc-url`, `datasource.jndi-name` ou `datasource.driver-class-name`, com binding relaxado. Um datasource por processo/prefixo, evitando misturar aplicações que usam o mesmo JAR para destinos distintos.
- Driver associado apenas quando a biblioteca da própria aplicação é identificada sem ambiguidade. O campo driverArtifact conserva o caminho, inclusive !/BOOT-INF/lib no JAR executável, e driverVersion a versão disponível. Ambos usam a mesma biblioteca; vários candidatos não geram uma escolha automática. Sua presença não comprova carregamento em execução nem a versão do banco. Um relato opcional de versão diferente remove o caminho incompatível.

Para prefixos personalizados, acrescente ao collector.properties e solicite novo assessment:

```properties
spring.datasourcePrefixes=app.datasource.orders,app.datasource.audit
```

Prefixos como `cache-consultas-jndi.datasource` são detectados sem esse parâmetro; prefixos alternativos, como `app.datasource.orders`, continuam exigindo configuração explícita. Parâmetros de processo personalizados são restritos a nomes contendo `datasource`.

Os profiles exibidos são candidatos encontrados na configuração local. A resolução é `local-candidate` ou `partial`; não reproduz o contexto Spring em execução nem confirma conexão. Protocolos JDBC desconhecidos continuam produzindo evidência de configuração, com o produto do banco em branco.

## Limites explícitos

Não busca configurações em Config Server, Vault, rede, imports remotos nem árvores de secrets. Imports locais em `file:`, `optional:file:` e caminhos relativos são seguidos com limites de profundidade 3, 20 arquivos e 1 MiB por arquivo externo. Imports classpath ficam restritos ao arquivo Java inspecionado. Ciclos, imports remotos, grupos/includes de profiles, documentos condicionais, YAML complexo e locais com wildcard/grupos são assinalados como parciais. Não resolve valores arbitrários por código, SpEL ou configurações criadas por beans. Diretórios config/* automáticos não são enumerados nesta versão. WAR implantado em servidor externo não tem toda a resolução Spring desse fluxo; a descoberta existente do servidor permanece disponível. Não expande Manifest Class-Path nem resolve ordem ambígua de wildcard; esses casos são sinalizados como parciais. Não classifica R2DBC como JDBC.

Configurações externas Spring têm limite de 1 MiB por arquivo, no máximo 64 leituras por aplicação, até oito profiles/nomes e 32 prefixos totais (oito extras explícitos). Configuração Spring empacotada mantém os limites mais restritos: 64 KiB por entrada e cache de até 32 entradas/256 KiB por arquivo Java. O perfil padrão permite 50.000 arquivos, 180 segundos e 32 MiB de metadados, mantendo heap de 96 MiB. Limites de tempo/arquivos explicitamente configurados são preservados. Atingir um limite encerra essa parte da descoberta, sem prometer que todos os arquivos serão examinados. Configuração empacotada tem prioridade sobre dependências e cache transitório limitado, nunca serializado. JARs até 1 GiB podem ter somente os metadados selecionados lidos; ZIP64 e diretórios centrais excessivos continuam recusados. Bibliotecas grandes podem conservar apenas evidência de nome quando o orçamento não permite ler o manifesto.

## Pastas externas, JPA, Hibernate e JNDI

`appConfigPaths` aparece como **Pastas de configuração das aplicações**, nos padrões gerais e nas personalizações do ambiente/máquina. O padrão é `/etc/config`; uma lista vazia desativa essa descoberta adicional. A configuração segue a herança máquina → ambiente → padrão geral. As listas de inclusão já salvas não são alteradas. `/opt` sai das inclusões novas e entra nas exclusões padrão. A exclusão também bloqueia arquivos ligados a processos ativos; remova-a para inventariar configurações e drivers nessa pasta. Processo e JVM observados continuam visíveis com observação.

Arquivos com o nome da aplicação ou citados em locais/imports explícitos são candidatos dessa aplicação. Arquivos `application.*` genéricos só são vinculados automaticamente quando existe um único Spring Boot observado; com vários, ficam como configuração da máquina não vinculada. A busca adicional examina até 512 caminhos, 128 arquivos de texto e três níveis, sem seguir links simbólicos. Perfis não são tratados como ativos só por existirem em disco.

JPA (`persistence.xml`), Hibernate (`hibernate.cfg.xml`/`hibernate.properties`) e beans XML de datasource/JNDI também são lidos. A busca genérica de `jdbc:` fica restrita às pastas das aplicações observadas e aos arquivos Java associados: até 512 entradas, 128 textos de 512 KiB e três níveis. EARs permitem inspeção de módulos JAR/WAR com até 4 MiB descompactados; dependências não são tratadas como configuração da aplicação. XML mantém o teto de 256 KiB do parser seguro. Todos compartilham o orçamento global da coleta.

Uma declaração JNDI é normalizada e comparada aos datasources de servidores observados na mesma máquina, depois da descoberta de todos os processos. Um único candidato fornece destino e driver; ausência, divergência ou múltiplos candidatos ficam explícitos, sem escolher um servidor por suposição. O dialeto/JPA pode indicar o produto do banco quando falta a URL; a URL prevalece se houver divergência. Nada disso comprova conexão ou a versão real do banco.

Os detalhes conservam a origem (`discoveryMethod` e `resolvedFrom`): `spring-properties`, `spring-import`, `app-config-dir`, `persistence-xml`, `hibernate-cfg`, `spring-xml-bean`, `jndi-link` ou `generic-scan`. URLs completas, usuários, senhas, tokens e comandos brutos não são serializados. Opções JVM após o JAR geram somente observação genérica, sem seus valores.

## Versão real do banco

A coleta passiva não preenche databaseVersion por inferência a partir de driver. A [integração opcional](SPRING_INTEGRATION.md) permite que a aplicação forneça esse dado localmente, sempre rotulado como informado pela aplicação. Configuração e relatório têm identidades por processo, e relatórios antigos ou de outro PID são ignorados.

## Validação operacional

Atualize uma máquina de teste, solicite assessment e confira nome/versão da aplicação, profiles, datasource, banco e driver. Compare com o comando de inicialização e os arquivos autorizados. Confira relatórios JDBC, CSV e PDF. Para dados de banco, habilite a integração somente na aplicação escolhida. Não interpretar campo desconhecido como ausência de banco ou falha da aplicação.
