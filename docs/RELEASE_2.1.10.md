# Java Assessment 2.1.10

Entrega consolidada de Spring, WebLogic, Tomcat e JBoss/WildFly para teste pela console. O upload, reinício do dashboard e validação das máquinas serão realizados pelo operador.

## Agente

- Spring: parsing do launcher, configurações externas por aplicação (`appConfigPaths`, padrão `/etc/config`), prefixos de datasource, imports locais limitados, pistas de dialeto, JPA/Hibernate/XML e vínculos JNDI. Valores sensíveis não são emitidos.
- WebLogic: resolução de JAR e versão por PID, boot/ext/classpath/manifestos, grupos do domínio, evidência de arquivos abertos e bibliotecas de aplicação isoladas. Divergências e limites ficam explícitos.
- Tomcat: `common.loader`, `catalina.properties`, jsvc, docBase/xmlBase externo, instalações por pacote com symlinks e observação de ServerInfo personalizado.
- JBoss/WildFly: módulos multi-JAR, module path/camadas/overlays/add-ons, drivers do repositório de conteúdo e deployments, cached domain e descritores/JNDI de aplicações.
- Novas inclusões padrão: `/usr/java`, `/usr/lib/jvm`, `/u01`, `/etc/config`, `/prd1`; legado conserva `/home/opc/oracle`. Novas exclusões: `/proc`, `/sys`, `/dev`, `/tmp`, `/opt`. **Listas salvas não são migradas.** `/opt` excluído também bloqueia leitura de configurações e drivers de processos ativos; remova a exclusão para inventariá-los. Processo/JVM observados continuam visíveis com a observação.

## Dashboard e relatórios

JAR, versão e suporte separados, candidatos com ordem/origem, fonte da versão, motivos e resultados por processo. Estados provável, ambíguo e variável não escondem incerteza. Relatórios e exportações incluem a evidência da versão. Configurações novas recusam caminhos duplicados/conflitantes; a leitura das configurações antigas continua funcionando.

## Atualização

Envie `assessment-dashboard-web-update-2.1.10.zip` em **Administração → Atualização do dashboard**, confira origem/destino e confirme **Atualizar e reiniciar**. O instalador completo destina-se a servidores novos e não serve para upload. Para servidores sem executor web, consulte o pacote por script e suas origens aceitas.

A rota 2.1.8 → 2.1.10 foi validada em ensaios isolados do dashboard e do agente, incluindo restauração do dashboard.

**Atualize também os agentes**, separadamente pelo painel e começando por uma máquina elegível. A versão 2.1.9 é a ponte interna da entrega, sem instalação manual intermediária. Instalações sem rota elegível usam o pacote completo configurado do ambiente. Após confirmar a versão final, solicite nova coleta.

## Validação e limites

A regressão em fontes e a compilação Java 6 precedem o fechamento. Os pacotes finais são verificados pelo processo de release. Consulte a [evidência da entrega](validation/console-release-2.1.10.md) para resultados e compatibilidade comprovada dos artefatos.

Coleta passiva não comprova a classe efetivamente carregada nem a versão de um banco remoto. Coordenadas de módulo sem arquivo inspecionado são versão declarada, identificada como provável. Permanecem fora do escopo TomEE, `.installation`, bootable JAR em `/tmp`, segredos e servidores parados. Java 6/Linux real, configurações de cliente e atualização pela console precisam de validação operacional.

[Guia Tomcat/JBoss](TOMCAT_JBOSS_DISCOVERY.md) · [Guia WebLogic JDBC](JDBC_DRIVER_RESOLUTION.md) · [Spring](SPRING_DISCOVERY.md) · [Pacotes](../install/v2.1.10/README.md)
