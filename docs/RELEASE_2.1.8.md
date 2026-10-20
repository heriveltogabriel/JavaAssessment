# Java Assessment 2.1.8

Entrega preparada para teste pela console. Reúne melhorias de precisão e velocidade do agente e novas visões do dashboard. Nenhum deploy desta entrega foi feito por SSH; o envio pela console e a atualização dos agentes são feitos pelo operador.

## Agente

**Velocidade**
- Pausa entre lotes só quando a máquina está carregada (`/proc/loadavg` acima do número de CPUs), proporcional à carga e limitada por `pauseMillis`. Varredura de 50 mil arquivos: de ~16 s para ~1–2 s com carga normal.
- Java 6: um único `find` por raiz, em vez de um processo por diretório.
- Instalações de produto (Oracle Home/WebLogic, JBoss/EAP/WildFly, Tomcat) são identificadas por arquivos-alvo e seus internos não são percorridos. Domínios, deployments, webapps e drivers JDBC conhecidos continuam inventariados.
- Orçamentos separados para varredura geral e evidências de processos; `maxFiles` aceita até 500.000 (padrão 50.000). Controle de caminhos visitados compacto (~17 MiB para 1 milhão de caminhos).
- Mais pastas ignoradas por nome: `.m2`, `.gradle`, `.npm`, `.ivy2`, `__pycache__`, `oradata`, `diag`, `lost+found`.
- Bases de servidores vistas em processos são varridas mesmo fora de `includePaths`.

**Precisão — JVMs em execução**
- Leitura passiva do arquivo de desempenho da JVM (hsperfdata): versão real em execução, classpath efetivo (inclusive `@argfile` e `CLASSPATH`) e propriedades `-D`. Linha de comando bruta não é retida.
- Java atualizado em disco com a aplicação rodando: a JVM aparece com a versão em execução e a marca "Java substituído em disco (reinício pendente)". Antes, a versão do disco era atribuída à JVM.
- Processos em container não alteram a versão do Java da máquina.

**Precisão — arquivos e drivers**
- JARs internos do Spring Boot lidos sem descompactar o JAR inteiro (fat jar com ojdbc de 4 MB: de 8,5 MB para 65 KB lidos). Limites: 200 arquivos por coleta e 2.000 bibliotecas internas (antes 40 no total); até 65.535 entradas por arquivo (antes 5.000).
- Drivers reconhecidos: DB2 via Maven (`jcc`), Sybase, Informix, Teradata, SAP HANA, Snowflake, SQLite, Firebird. Pools (HikariCP, c3p0, DBCP, tomcat-jdbc, Oracle UCP) aparecem como `JDBC pool`, não como driver.

**Precisão — datasources**
- Placeholders `${...}` resolvidos com `-D` e variáveis de ambiente do processo ou o valor padrão da expressão; nunca resolve chaves de senha, segredo ou token. O registro indica a origem do destino.
- JBoss/WildFly: URL de datasources XA; driver por módulo ou deployment ligado ao JAR e à versão; modo domain com perfil do servidor; JBoss 4/5/6 (`*-ds.xml`).
- Tomcat: pools Hikari, UCP, tomcat-jdbc e DBCP; `ResourceLink`; `context.xml` dentro de WAR compactado; `appBase` do `server.xml`.
- Oracle: alias TNS resolvido pelo `tnsnames.ora`; `ldap://` identificado como serviço de nomes.
- WebLogic: módulos JDBC empacotados na aplicação (EAR); versão e patches do Oracle Home por `inventory/registry.xml`.
- Novos tipos de URL: jTDS, Sybase, Informix, AS/400, Teradata, SAP HANA, Snowflake.

## Dashboard

- **Status de suporte** de Java, WebLogic, JBoss EAP, WildFly, Tomcat, Spring Boot e drivers Oracle JDBC: Fora de suporte, Suporte estendido, Fim próximo, Suportado ou Desconhecido. Catálogo em `web/lifecycle.json` com fonte oficial e data do levantamento (19/10/2026). Java usa a referência Oracle; outros fornecedores podem ter datas diferentes. Algumas datas vieram de fontes secundárias e estão marcadas no catálogo para conferência.
- **Painel de riscos** na Visão geral, com links para as listas filtradas: Java fora de suporte, servidores fora de suporte ou com fim próximo, Spring Boot fora de suporte, drivers não identificados, coletas parciais e Java substituído em disco.
- Gráfico de versões Java agrupado por status; Java 6 e 7 não ficam mais escondidos em "Outros". Filtro "Status de suporte" nas listas.
- Abas **Datasources JDBC** e **Bancos de dados** ("quem acessa cada banco"), com drill-down.
- Coluna JDBC dos relatórios resumida na tela, com expansão; PDF e CSV completos, um datasource por linha.
- Detalhe do inventário mostra os campos novos do agente.
- Textos: "instalações Java", "registros", "Agentes cadastrados".

## Atualizar

1. Em **Administração → Atualização do dashboard**, envie `assessment-dashboard-web-update-2.1.8.zip`, confira origem e destino e confirme **Atualizar e reiniciar**.
2. **Atualize também os agentes**, pelo painel, começando por uma máquina elegível. Publicar o dashboard não atualiza os agentes. Sem rota compatível, use o pacote completo configurado do ambiente.
3. Solicite **nova coleta**: inventários anteriores não são recalculados.

## Limites conhecidos

- O formato de patches no `registry.xml` foi inferido sem amostra real; o `find -printf` do modo Java 6 não foi validado num RHEL 5 real; leitura do hsperfdata 1.0 (JDK 1.4.2) só validada com arquivos gerados.
- O catálogo de suporte é uma referência para planejamento, não substitui o contrato com o fornecedor.
- O status de suporte é calculado na consulta, sem índice; não foi medido em bases muito grandes.

[Validação](validation/console-release-2026-10-20.md) · [Pacotes](../install/v2.1.8/README.md) · [Operação da atualização](DASHBOARD_SELF_UPDATE.md)
