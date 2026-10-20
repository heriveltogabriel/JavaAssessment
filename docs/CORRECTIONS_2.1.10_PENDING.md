# Correções da 2.1.10 — incorporadas na 2.1.12

Correções da branch `correcoes-2.1.10`, sobre `main` 2.1.10, preservadas na release 2.1.12 com ponte interna 2.1.11. O nome deste arquivo foi mantido para preservar referências. Veja as [notas da entrega](RELEASE_2.1.12.md) e a [validação dos pacotes finais e da rota direta 2.1.8 → 2.1.12](validation/console-release-2.1.12.md).

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

Na implementação, validado com fixtures em Linux (JDK 21 executando a suíte) e lint Java 6 heurístico. No fechamento da 2.1.12, a suíte completa passou no Mac/JDK 17 e o build confirmou compilação ECJ 1.6 e classes major 50. Não validado em cliente real.
