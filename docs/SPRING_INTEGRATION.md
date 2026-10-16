# Integração opcional de aplicações Java e Spring

A descoberta passiva continua funcionando sem esta integração. O coletor não abre conexões JDBC ou HTTP para descobrir a versão do banco. Quando habilitada explicitamente, **a própria aplicação** publica um pequeno relatório JSON local com os metadados obtidos de seus datasources. O coletor associa esse relatório ao processo em execução.

A versão do driver é diferente da versão do banco. A integração apresenta `driverVersion` e `databaseVersion` separadamente, com a indicação **informado pela aplicação**. O agente não testa independentemente a conexão nem considera esse arquivo uma prova assinada de identidade do banco.

## O que instalar na aplicação

O diretório [integration/spring](../integration/spring/) contém:

- `JavaAssessmentReporter.java`: helper sem dependências externas, compatível com APIs Java 6. Copie para o código da aplicação e adapte o pacote se necessário.
- `SpringAssessmentConfiguration.java.example`: configuração opcional para Spring Boot 2/3/4; adapte o nome do bean `DataSource`. A configuração não é incluída nem ativada automaticamente.
- `JavaAssessmentReporterTest.java`: testes locais usando JDBC simulado, sem banco nem rede.

O helper usa `DataSource.getConnection()` e `DatabaseMetaData`, fecha a conexão emprestada e mantém cache de cinco minutos por datasource. Não executa SQL próprio, não chama `getURL()` ou `getUserName()`, não lê arquivos de credenciais nem exporta senhas. O driver pode realizar comunicação com o banco ao fornecer os metadados.

**Antes de ativar**, configure os limites de espera de obtenção de conexão do pool e os timeouts de rede do driver. A API JDBC não oferece um timeout genérico confiável para todas essas operações. O exemplo utiliza uma única thread dedicada, com intervalo de cinco minutos; uma chamada bloqueada não abre threads adicionais, mas pode impedir a renovação do relatório. A integração é para Linux com `/proc`, no mesmo namespace de PID do coletor. Containers com namespace diferente exigem adaptação explícita; não relaxe a validação de identidade.

## Ativação explícita

Exemplo para uma aplicação `/opt/orders/orders.jar`, executada pelo usuário `orders`. Primeiro, crie um diretório privado, gravável somente pela conta da aplicação:

```bash
sudo install -d -m 700 -o orders -g orders /var/lib/orders-assessment
```

O exemplo grava arquivos com permissão `600` e substitui o relatório por renomeação no mesmo diretório. O coletor instalado como root consegue ler esses arquivos. Não conceda escrita a outros usuários, não use links simbólicos e não coloque o relatório em um filesystem de rede.

Na configuração da aplicação, depois de integrar o helper e o exemplo:

```properties
assessment.integration.enabled=true
assessment.integration.application-path=/opt/orders/orders.jar
assessment.integration.report-file=/var/lib/orders-assessment/report.json
```

Por padrão, `assessment.integration.enabled` é ausente ou `false`: o exemplo Spring não cria o worker e o helper desabilitado não realiza I/O. A integração passa a valer na próxima inicialização/deploy da aplicação feito pelo operador.

No `conf/collector.properties` da instalação do agente, adicione um mapeamento explícito:

```properties
spring.integration.orders.applicationPath=/opt/orders/orders.jar
spring.integration.orders.file=/var/lib/orders-assessment/report.json
```

O agente lê esses parâmetros na próxima execução. Solicite um assessment no dashboard após a aplicação gerar o relatório. Nenhum endpoint Actuator precisa ser exposto. A coleta passiva permanece ativa e independe do relatório.

Para múltiplos datasources, substitua os mapas `singletonMap` do exemplo por mapas dos beans escolhidos. Cada nome deve ser único e cada `configurationKey` deve corresponder ao prefixo da descoberta passiva: `spring.datasource` para o padrão, ou um prefixo personalizado explicitamente configurado. Evite enumerar todos os beans ou consultar um datasource de roteamento sem selecionar e autorizar o contexto adequado. O exemplo usa o bean `dataSource`; a aplicação deve escolher conscientemente os beans que pode consultar.

## Formato e associação

Contrato de exemplo; os números de identidade e data abaixo são ilustrativos e devem ser produzidos em tempo de execução:

```json
{
  "schemaVersion": 1,
  "applicationPath": "/opt/orders/orders.jar",
  "pid": 12345,
  "processStartTicks": 98765,
  "generatedAtEpochMillis": 1792051200000,
  "datasources": [
    {
      "name": "dataSource",
      "configurationKey": "spring.datasource",
      "driverName": "PostgreSQL JDBC Driver",
      "driverVersion": "42.7.4",
      "databaseType": "PostgreSQL",
      "databaseVersion": "16.5"
    }
  ]
}
```

`processStartTicks` é o campo 22 de `/proc/<pid>/stat`. O coletor confere o caminho da aplicação, PID, instante de início e idade do relatório, e repete a leitura da identidade antes de aceitar os dados. Relatórios com mais de dez minutos, data futura, identidade diferente, JSON inválido ou campos de identidade ausentes são ignorados com um aviso genérico. O cache da aplicação pode acrescentar até cinco minutos à idade efetiva dos metadados; a data do relatório representa sua publicação.

O importador aceita no máximo 32 mapeamentos, 32 datasources por relatório e 64 KiB por arquivo, dentro do limite global de 512 datasources da coleta. Respeita as exclusões de caminhos, o prazo da coleta e o orçamento compartilhado de 8 MiB de leitura de metadados; um mapeamento explícito não libera um caminho excluído. Descarta campos desconhecidos, URLs, controles e textos fora dos limites. Nomes e versões aceitam um conjunto restrito de caracteres; valores atípicos são omitidos em vez de exportados livremente. O helper normaliza quebras de linha e limita versões longas a 256 caracteres. `configuredMaxPoolSize` é opcional e aceita somente valores inteiros de 1 a 100.000; o helper básico não coleta métricas de pool.

Um datasource passivo é enriquecido somente quando sua associação ao processo e seu nome ou `configurationKey` são inequívocos. Caso contrário, o relatório cria um item separado com associação à aplicação e PID, evitando atribuir o banco de um processo a outro processo que use o mesmo JAR. O estado exportado é `APPLICATION_REPORTED`, com `evidenceLevel=application-reported`; a versão do banco possui sua própria evidência.

## Operação e limites

Se a consulta falhar, o helper não substitui o último relatório e o exemplo registra apenas uma mensagem genérica. Após dez minutos sem publicação, o coletor deixa de aproveitar o arquivo. A coleta passiva continua mostrando somente o que consegue comprovar nos arquivos. Para desativar, remova o mapeamento do coletor e desative `assessment.integration.enabled` na aplicação no próximo deploy.

O relatório é confiável apenas dentro do controle de acesso local administrado pelo cliente: uma aplicação ou conta com escrita no arquivo pode informar metadados incorretos. PID, início e caminho impedem associações acidentais e relatórios antigos; não constituem autenticação criptográfica. O helper deve ser revisado e testado na aplicação antes da produção. O exemplo Spring é um ponto de integração, não um agente automaticamente injetado na aplicação.

A validação desta entrega utiliza fixtures locais e JDBC simulado com heap de 96 MiB. Não substitui testes no banco do cliente, em Java 6 real, em processos de containers ou em Linux 32 bits.
