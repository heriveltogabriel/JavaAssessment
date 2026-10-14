# WebLogic version Implementation Plan

> Execução nesta sessão, usando as verificações abaixo. A correção solicitada pelo usuário mantém o contrato de inventário existente.

**Goal:** Inventariar a versão do WebLogic, incluindo o caso RHEL 5/10.3.6.0 informado pelo usuário.

**Architecture:** Resolver o diretório de instalação por propriedades permitidas do processo e ler exclusivamente o manifesto do JAR grande. Reutilizar orçamento, cache, inventário e apresentação existentes.

**Tech Stack:** Java 6, ZIP/JAR, testes Java locais, ECJ e JDK 17.

---

- [x] Criar `collector/WebLogicVersionTest.java`: gerar 23.553 entradas, manifesto 10.3.6.0, processo sem classpath com `-Dweblogic.home`, e exigir `servers.version == "10.3.6.0"`. Executar contra código atual e confirmar falha.
- [x] Modificar `collector/ProcessLaunch.java`: capturar `weblogic.home` e `wls.home` com `path(value, cwd)`; usar os campos somente quando `serverType` for WebLogic. Não tratar diretório de instalação como domínio quando cwd não puder ser resolvido.
- [x] Criar `collector/WebLogicManifest.java`: ler EOCD, validar diretório central com limite específico, selecionar manifesto único, conferir cabeçalho local, tamanho e CRC, descompactar somente essa entrada. Cobrar bytes com `ArchiveMetadata.charge`/`read` e respeitar `ensureBudget`.
- [x] Modificar `collector/ArchiveMetadata.java`: no ramo exclusivo `weblogic.jar`, chamar o leitor de manifesto dentro do controle de arquivo/cache já existente; manter o fluxo genérico para os demais JARs.
- [x] Modificar `collector/AssessmentScan.java`: no ramo WebLogic de `discoverLinked`, consultar `new File(home, "lib/weblogic.jar")`. A associação existente propaga versão para servidor e framework.
- [x] Estender regressões: versão moderna por classpath, isolamento de instalações, campos ausentes, inspeção desativada, exclusões, arquivo comum com 5.001 entradas rejeitado, manifesto duplicado/CRC/tamanho inválidos e orçamento insuficiente.
- [x] Compilar `shared/Json.java`, `collector/*.java`, `server/*.java` e `tests/*.java` com JDK 17; executar WebLogicVersionTest, ProcessLaunchTest, ArchiveMetadataTest, LegacyDiscoveryTest, ServerDataSourcesTest, CollectorTest e CsvExportTest com `-Xmx96m`.
- [x] Compilar fontes de produção com ECJ `-source 1.6 -target 1.6`; exigir major 50 em todas as classes. Executar a regressão WebLogic também contra essas classes.
- [x] Documentar comportamento no README do coletor e registrar versão nova de entrega somente após testes, preservando os pacotes 2.0.2 existentes. Preparar um teste de leitura isolado para a VM, pois SSH direto está bloqueado.
