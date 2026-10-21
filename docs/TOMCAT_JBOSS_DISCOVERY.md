# Datasources e drivers Tomcat/JBoss

A coleta é passiva e parte de processos em execução. Não usa JMX, CLI administrativa, HTTP dos servidores, attach ou conexão ao banco. Não executa scripts nem carrega classes. O motor JDBC compartilhado conserva os estados, a origem da versão, os candidatos e a decisão por PID do WebLogic.

## Tomcat

Recursos declarados pelo Tomcat usam as origens do carregador comum: boot/ext quando aplicável, classpath do processo e entradas de `common.loader`, preservando a ordem de base antes de home. Um wildcard ou uma pasta com vários drivers não fornece ordem garantida: um único JAR aberto pode fundamentar **Provável**; sem desempate, o resultado permanece **Múltiplos candidatos**. Um driver encontrado somente em WEB-INF/lib é informativo e não é atribuído ao datasource do Tomcat.

`catalina.properties` é lido no base e, quando ausente, no home, até 256 KiB. Propriedades `-D` têm prioridade; a origem dos valores resolvidos aparece no inventário. Variáveis de ambiente exigem a configuração EnvironmentPropertySource. Chaves sensíveis e expressões de credenciais não são resolvidas.

O agente reconhece `jsvc` com inicializador Tomcat e JVMs nativas com libjvm mapeada e inicializador reconhecido. Contextos com docBase externo, xmlBase, WARs e diretórios de pacote ligados por symlinks são examinados dentro das exclusões e limites. Há até 64 contextos. Um ServerInfo personalizado gera observação sem substituir a versão do catalina.jar.

## JBoss EAP/WildFly

A resolução de módulos considera module path, módulo direto, camadas, overlays ativos e add-ons. O primeiro descritor seleciona o módulo; dentro dele, a classe exata distingue o driver de UCP, ONS e bibliotecas auxiliares. Recursos seguintes e módulos encontrados em outras origens permanecem evidências, sem substituir o vencedor.

Drivers de deployment podem vir do repositório de conteúdo, de deployment não gerenciado ou da pasta deployments. A atribuição ao server-group e o estado habilitado são verificados na configuração; deployment desativado não é escolhido. O domínio secundário pode usar domain.cached-remote.xml; quando ausente, a observação orienta coletar o controlador.

Descritores `*-ds.xml`, declarações Java EE e referências JNDI em aplicações são lidos sem reter credenciais. Um vínculo JNDI inequívoco acrescenta a associação com a aplicação. Resultados distintos dos processos associados aparecem como **Varia por instância/servidor**.

Coordenadas Maven de um artefato de módulo podem fornecer versão declarada sem baixar o arquivo. Esse caso aparece como **Provável**, com origem `module-artifact` e motivo explícito; não comprova presença ou carregamento da classe.

Limites específicos: 8 raízes de módulos, 16 camadas, 32 add-ons, 32 overlays, 32 recursos por módulo e 128 deployments. Todos compartilham os limites globais de arquivos, metadados e tempo. A leitura truncada da origem decisiva impede uma identificação definitiva.

## Profile/contexto no dashboard (2.1.20)

A aba Datasources JDBC e os detalhes exibem **Profile/contexto**, usando `configurationContext` do inventário. Para `domain profile full`, a coluna mostra `full`; para `domain profile full-ha`, mostra `full-ha`. Contextos ausentes aparecem como **Não identificado**. Nomes iguais em perfis distintos permanecem registros distintos. A coluna pode ser ocultada no seletor de colunas.

Essa apresentação não muda o vínculo aos processos nem confirma o perfil efetivamente usado em execução. Quando o campo já foi coletado, basta atualizar o dashboard para visualizá-lo; não é necessário atualizar o agente ou repetir a coleta por essa mudança.

## Novos padrões de coleta

- Incluídos: `/usr/java`, `/usr/lib/jvm`, `/opt`, `/u01`, `/etc/config`, `/prd1`.
- Excluídos: `/proc`, `/sys`, `/dev`, `/tmp`.
- Pastas de configuração: `/etc/config`.
- O perfil legado conserva também `/home/opc/oracle`.

Listas já salvas não são migradas. `/opt` excluído impede leitura dos arquivos mesmo de um processo em execução; o processo e a evidência da JVM continuam aparecendo com uma observação. Para coletar configuração, bibliotecas e datasources nessa pasta, remova-a das exclusões e ajuste os caminhos incluídos conforme necessário.

## Limites conhecidos

A precedência é evidência de configuração, não observação direta de uma classe carregada. TomEE, patches `.installation`, WildFly bootable JAR em `/tmp`, vault/credential store e servidores parados não são cobertos por esta ampliação. Testes sintéticos locais não substituem validação em JVM Java 6 e servidores reais. Atualize os agentes e solicite nova coleta; atualizar somente o dashboard não recalcula inventários anteriores.

Referências: [Tomcat classloaders](https://tomcat.apache.org/tomcat-9.0-doc/class-loader-howto.html), [Tomcat JNDI JDBC](https://tomcat.apache.org/tomcat-9.0-doc/jndi-datasource-examples-howto.html), [JBoss Modules](https://jboss-modules.github.io/jboss-modules/manual/) e [Red Hat EAP classloading](https://docs.redhat.com/en/documentation/red_hat_jboss_enterprise_application_platform/7.1/html/configuration_guide/overview_of_class_loading_and_modules).
