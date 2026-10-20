# Correções da 2.1.10 — pendentes de release

Branch local `correcoes-2.1.10`, sobre `main` 2.1.10. Sem versão reservada, pacotes, assinatura ou push. Entra na próxima release (2.1.12, ponte interna 2.1.11, reservadas pelo build).

## Agente

- **Padrões de coleta:** `/opt` volta às inclusões e sai das exclusões. Incluídos: `/usr/java`, `/usr/lib/jvm`, `/opt`, `/u01`, `/etc/config`, `/prd1` (legado conserva `/home/opc/oracle`). Excluídos: `/proc`, `/sys`, `/dev`, `/tmp`. Listas salvas não são migradas: ambientes que salvaram as listas da 2.1.10 precisam ser ajustados na tela.
- **jsvc:** `-java-home` (usado pelo `bin/daemon.sh` do Tomcat), `-cwd <dir>` e `-jvm <nome>` têm o valor consumido; antes o valor virava classe principal e o Tomcat não era detectado. Um `jsvc` renomeado por `-procname` continua reconhecido pelo executável.
- **JVM em caminho excluído:** boot classpath e `ext` da própria JVM nesse caminho não bloqueiam mais a decisão do driver (antes: `INCOMPLETE` em todos os datasources da JVM). O resultado traz a ressalva `driverResolutionCaveat`. A observação distingue "Java em caminho excluído" de "processo/servidor em caminho excluído".
- **`/etc/config` por nome:** nomes normalizados (`_`, `-`, `.` e espaço equivalentes; extensão e versão do JAR removidas). Casamento por token: `<app>.ext`, `<app>-sufixo.ext` ou pasta `<app>`. `application*`/`bootstrap*` só valem pela pasta. `FORMACAO_PERIODOS_SE.jar` casa com `formacao-periodos-se/`; `app.jar` não captura mais `application.yml`.
- **JARs assinados:** só a seção principal do `MANIFEST.MF` é lida. Manifestos com digest por classe (BouncyCastle, provavelmente ojdbc) deixam de perder a versão e de gerar "exceeds 64 KiB".
- **Avisos da varredura:** links simbólicos e limite de profundidade são agregados ("N links… (ex.: …)") e não ocupam a lista de 40 observações. Links para arquivos que o agente não lê (`.so` etc.) são ignorados em silêncio. O limite de profundidade da varredura geral não marca mais a coleta como parcial.
- **Cópias de pacote do agente** (`collector.jar` com `DEPENDENCIES.json`/`update-public.der`) não são inventariadas; há uma observação com o caminho e a versão.
- **`catalina.properties`:** segue a ordem do Tomcat (`-Dcatalina.config` local, depois `$CATALINA_BASE/conf`, depois o padrão embutido); o arquivo de `$CATALINA_HOME/conf` não é mais usado como fallback.
- **Processos:** `cmdline` só é lido quando o executável é Java ou `jsvc`, o `comm` é `java`/`jsvc`, ou executável/`comm` não são legíveis.

## Testes

- `ServerDataSourcesTest` não depende mais de `/proc/1` (falhava em Linux e interrompia a suíte).
- Novos casos em `NativeTomcatTest`, `TomcatDiscoveryTest`, `ApplicationConfigFilesTest`, `ArchiveMetadataTest` e `BoundedDirectoryTest`; padrões atualizados em `ArchiveDefaultsHttpTest` e `SettingsPathsHttpTest`.

## Limites

Validado com fixtures em Linux (JDK 21 executando a suíte) e lint Java 6 heurístico; a compilação ECJ 1.6 precisa ser confirmada no build do Mac. Não validado em cliente real.
