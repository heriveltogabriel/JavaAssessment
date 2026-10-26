# Validação do coletor — resolução de drivers JDBC (2.1.24)

Base: `main` em ab36455 (2.1.22). Commits 9ade067 e f0dde88. Coleta de referência: exportação de 26/10/2026 (dashboard 2.1.22, 1.148 máquinas, 3.534 datasources Oracle).

## Diagnóstico na coleta de referência

| Tipo | Datasources Oracle | Com versão de driver |
| --- | ---: | ---: |
| Spring Boot | 781 | 97% |
| JBoss | 43 | 100% |
| WebLogic | 1.959 | 7% |
| Tomcat | 88 | 10% |
| Atribuídos ao Derby | 444 | 0% |
| Sem servidor | 219 | 0% |

Causa do WebLogic: o motivo registrado em 1.914 casos era sempre "Argumentos da JVM truncados nos metadados hsperfdata", e 97% deles já tinham um único candidato de driver.

## Provas

- Testes unitários: `HsPerfDataTest`, `ApplicationJdbcTest` e `JdbcDriverResolverTest` cobrem vmArgs truncado com ambiente limpo, com `JAVA_TOOL_OPTIONS`, ambiente ilegível e `@argfile`; processo auxiliar em domain home com e sem servidor WebLogic ativo (com endpoint duplicado e endpoint novo); `ojdbc14` com e sem versão. Os de `ApplicationJdbcTest` e `JdbcDriverResolverTest` falham no código de ab36455 (o do Derby reproduz o nome visto no cliente).
- Linux real: `ops/test-linux-real.sh` (exige Docker). JVM 8 com `-cp` de 40 entradas ausentes e `-Dpadding` de 1.300 bytes, linha de comando de 2.715 bytes. Código de ab36455: `INCOMPLETE` com o motivo do cliente. Código novo: `IDENTIFIED 19.3.0.0.0`. Com `JAVA_TOOL_OPTIONS`: `INCOMPLETE`, motivo "Opções da JVM no ambiente (JAVA_TOOL_OPTIONS)…".
- `ops/test-source.sh` completo (executado sobre os arquivos rastreados pelo Git) sem falhas.

## Não coberto

- Nenhuma execução em máquina do cliente nem com um WebLogic real; o contêiner usa uma classe `weblogic.Server` mínima e jars de fixture.
- A leitura da versão do `ojdbc14` assume o texto "Oracle JDBC Driver version - X" no manifesto; não foi visto um manifesto real.
- Atualização de agentes 2.1.20 ou 2.1.22 para 2.1.24 pelo painel, e agentes em Java 6 nativo.
