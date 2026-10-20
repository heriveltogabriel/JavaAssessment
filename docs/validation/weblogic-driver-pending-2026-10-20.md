# Validação local — resolução JDBC WebLogic pendente

Data: 20/10/2026. Base 2.1.8, branch `codex/weblogic-driver-jdbc`. Sem incremento de versão, assinatura, pacotes, push ou deploy.

## Alterações

1. Classpath efetivo e boot/ext capturados com allowlist; referências de manifestos percorridas em profundidade e com cache.
2. Resolução por processo, precedência, grupos sem ordem e desempate provável por fd/maps. Versão e JAR com proveniência, família e razão da decisão.
3. Bibliotecas de aplicação isoladas dos datasources de sistema, incluindo preferências explícitas de classes; preferências de recursos não alteram a decisão.
4. Candidatos e resultados por PID na interface; JAR, versão, suporte e confiança separados. Relatórios e CSV incluem origem da versão e motivo; PDF compartilha as colunas de relatórios.
5. Documentação e TODO atualizados, incluindo limites e verificações reais ainda necessárias.

## Evidências

- `ops/test-source.sh`: regressão completa concluída com sucesso, incluindo coleta, servidor HTTP/SQLite, autorização, relatórios, exportações e renderização JavaScript.
- `WebLogicPrecedenceTest`: 24 verificações, incluindo 300 JARs de cadeia, módulos Oracle lado a lado, PRE_CLASSPATH, boot/ext, lib do domínio, fd/maps/deleted, consenso/divergência por PID, exclusões, limites, versões e aplicações EAR/explodidas.
- ECJ: source/target 1.6, 95 classes com major version 50. Bibliotecas de dependências fornecidas explicitamente ao compilador.
- `git diff --check`: sem erros. Versões e arquivos de `install/` preservados.
- Evidência local detalhada, não versionada: `tmp/weblogic-driver-full.log` e `tmp/weblogic-java6.log`.

## Limites de validação

Somente fixtures locais. Não houve teste em máquinas remotas nem execução em JVM Java 6 real. A seleção por precedência não prova carregamento de classe; fd/maps só fundamenta resultado provável. Registry de patches JDBC depende de amostra validada. WARs internos a EARs e classloaders personalizados precisam de cobertura adicional antes de ampliar o suporte.

As melhorias anteriores de Spring/JPA/Hibernate/JNDI e appConfigPaths foram mantidas e incluídas na mesma regressão. A publicação continua aguardando as demais implementações solicitadas pelo usuário.
