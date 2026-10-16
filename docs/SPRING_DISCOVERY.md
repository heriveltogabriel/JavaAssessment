# Descoberta passiva de aplicações Spring

A inspeção de arquivos (`inspectArchives`) deve estar habilitada no assessment. O coletor relaciona metadados à aplicação em execução sem carregar classes, iniciar JVMs da aplicação ou abrir conexões JDBC.

## Cobertura

- JAR executável Spring Boot e WAR executável com launchers reconhecidos, nos layouts BOOT-INF/classes e WEB-INF/classes.
- Aplicação descompactada iniciada pelo launcher Spring Boot com diretório explícito no classpath.
- Nome/versão do manifesto ou build-info.properties; versão Spring Boot do manifesto; bibliotecas de servidor embutido aparecem como evidência de pacote, não como servidor independente confirmado.
- application.properties, application.yaml e application.yml de base e dos profiles indicados; configuração empacotada seguida da configuração externa local.
- spring.config.name, spring.config.location e spring.config.additional-location nos parâmetros de inicialização ou variáveis de ambiente correspondentes.
- Variáveis e argumentos permitidos de datasource; placeholders exatos como ${DATABASE_URL} e ${DATABASE_URL:jdbc:h2:mem:example}. Valores arbitrários, senhas e URLs JDBC não são gravados no inventário.
- Datasource padrão e prefixos extras explicitamente cadastrados. Um datasource por processo/prefixo, evitando misturar aplicações que usam o mesmo JAR para destinos distintos.
- Driver associado apenas quando a biblioteca da própria aplicação é identificada sem ambiguidade. Sua presença não comprova carregamento em execução.

Para prefixos personalizados, acrescente ao collector.properties e solicite novo assessment:

```properties
spring.datasourcePrefixes=app.datasource.orders,app.datasource.audit
```

Sem esse parâmetro, permanece apenas `spring.datasource`. Prefixos personalizados em arquivos são suportados; parâmetros de processo personalizados são restritos a nomes contendo `datasource`.

Os profiles exibidos são candidatos encontrados na configuração local. A resolução é `local-candidate` ou `partial`; não reproduz o contexto Spring em execução nem confirma conexão. Protocolos JDBC desconhecidos continuam produzindo evidência de configuração, com o produto do banco em branco.

## Limites explícitos

Não busca configurações em Config Server, Vault, rede, imports remotos nem árvores de secrets. Imports, grupos/includes de profiles, documentos condicionais, YAML complexo e locais com wildcard/grupos são assinalados como parciais. Não resolve valores arbitrários por código, SpEL ou configurações criadas por beans. Diretórios config/* automáticos não são enumerados nesta versão. WAR implantado em servidor externo e aplicação descompactada iniciada diretamente por classe própria não têm toda a resolução Spring desse fluxo; a descoberta existente do servidor permanece disponível. Não classifica R2DBC como JDBC.

Arquivos de configuração têm limite de 64 KiB, no máximo 32 arquivos por aplicação, até oito profiles/nomes e oito prefixos extras. O orçamento global de leitura continua em 8 MiB, com prazo da coleta, limites de entradas ZIP e heap de 96 MiB. JARs até 1 GiB podem ter somente os metadados selecionados lidos; ZIP64 e diretórios centrais excessivos continuam recusados. Bibliotecas grandes podem conservar apenas evidência de nome quando o orçamento não permite ler o manifesto.

## Versão real do banco

A coleta passiva não preenche databaseVersion por inferência a partir de driver. A [integração opcional](SPRING_INTEGRATION.md) permite que a aplicação forneça esse dado localmente, sempre rotulado como informado pela aplicação. Configuração e relatório têm identidades por processo, e relatórios antigos ou de outro PID são ignorados.

## Validação operacional

Atualize uma máquina de teste, solicite assessment e confira nome/versão da aplicação, profiles, datasource, banco e driver. Compare com o comando de inicialização e os arquivos autorizados. Confira relatórios JDBC, CSV e PDF. Para dados de banco, habilite a integração somente na aplicação escolhida. Não interpretar campo desconhecido como ausência de banco ou falha da aplicação.
