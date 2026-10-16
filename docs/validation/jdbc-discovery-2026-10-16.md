# Validação de JDBC e descoberta — 2026-10-16

Status: fontes implementados e testes de laboratório concluídos. Versão permanece 2.0.53; nenhum pacote publicado foi substituído e nenhum agente instalado foi atualizado por este ensaio. O instalador consolidado e sua atualização a partir da versão do cliente ainda precisam da validação de release.

## Escopo entregue na fonte

- WebLogic: domínios associados a processos em execução, caminho/nome do domínio, configuração JDBC referenciada e módulos adicionais identificados como sem referência de implantação confirmada. Domínios parados vizinhos e links que escapam do domínio não são percorridos. Dois AdminServer com o mesmo nome mantêm identidades distintas.
- Spring Boot: executável com metadados ou layout reconhecido, classe principal própria com evidência do núcleo Boot no classpath, recursos em diretórios e JARs explícitos, prioridade de configurações sobre dependências grandes e cache limitado. Ordem ambígua deixa observações, sem escolher arbitrariamente uma configuração posterior.
- Destino JDBC: host/IP, porta e banco/serviço, com múltiplos endpoints, sem URL completa ou credenciais. Oracle SID/Easy Connect/DESCRIPTION, RAC, IPv6 e outros protocolos reconhecidos cobertos por fixtures. Alias TNS e banco em memória não recebem host fictício.
- Todos os cinco modelos de relatório incluem Host/IP do banco por padrão. Filtros de host, porta e banco preservam o vínculo ao mesmo datasource/endpoint. CSV do inventário, CSV/PDF dos relatórios e detalhes de bibliotecas exibem esses dados.
- Padrões: 50.000 arquivos, 180 segundos e 16 MiB de metadados. Configurações salvas explicitamente são preservadas. Heap continua em 96 MiB.

## Regressões locais

`ops/test-source.sh` passou com JDK 17 e Node: coletor, Spring, WebLogic, JDBC, armazenamento, autenticação, HTTP, atualizações, limpeza, CSV e renderização. O sandbox exigiu liberação para sockets HTTP de loopback; a primeira tentativa parou ao abrir o socket, e a execução autorizada completa passou.

Testes específicos:

- JdbcEndpointTest: 61 checks, incluindo credenciais em URLs, SQL Server com senha entre chaves, múltiplos destinos, domínios ativos/parados e escapes por link.
- JdbcEndpointReportsTest: 68 checks, cinco modelos, filtros correlacionados, CSV/PDF, snapshots antigos, dados malformados e isolamento de servidores homônimos/contagem de aplicações.
- JdbcOverviewHttpTest: 59 checks, fixtures reais do coletor → HTTP autenticado → SQLite → detalhes; pesquisa também encontra endpoint secundário.
- JDBC overview no navegador: 42 checks com dados da cadeia HTTP real.
- ReportsTest, ReportsEnrichmentTest, CsvExportTest, ReportPdfTest e cenário Playwright de relatórios desktop/mobile passaram durante a implementação. Screenshot local: `output/reports/browser/reports-desktop.png`.
- BetaServerTest passou, incluindo padrão de 50.000 arquivos, configurações herdadas, APIs e persistência; log local `tmp/jdbc-beta-regression.log`.
- Python: scripts operacionais compilam sintaticamente e foram executados nas duas VMs.

Log completo local: `tmp/jdbc-source-regression.log`. Os testes não representam carga de produção nem varredura completa de 50.000 arquivos.

## Prova nativa isolada

Artefato: `output/jdbc-discovery/probe-n23j2oks/jdbc-discovery-probe.jar`.

SHA-256: `25f74fec2eb064a6bb8b8b91cdb78af9e7ac7dcbb34567f794104b07bdbef127`.

Compilado com ECJ para bytecode Java 6 (major 50), sem versão de release. O build guarda hashes dos fontes. O wrapper confere host e hash, executa os 61 checks de endpoints na JVM nativa e faz coleta apenas de arquivos vinculados aos processos. Não cadastra agente nem envia inventário ao dashboard. Saída privada, heap 96 MiB e watchdog de 240 segundos.

| Máquina | JVM do teste | Coleta | Heap amostrado | RSS máximo | Resultado |
|---|---|---:|---:|---:|---|
| OL7 — 157.151.0.69 | Java 1.6.0_41 | 197 ms | 18,96 MiB | 56,02 MiB | Spring Boot 3.5.16, lab-spring-pedidos 1.0.0, PedidosLab/H2, driver 2.3.232 |
| RHEL5 — 157.151.15.169 | Java 1.6.0_45 | 150 ms | 5,23 MiB | 23,33 MiB | WebLogic 10.3.6.0, domínio jms_lab, datasource JmsLabDerby |

Métricas são do snapshot da coleta (não incluem transferência, testes de fixtures ou finalização). Metadados: 8.422.309 bytes na OL7 e 5.898.850 na RHEL5. `scanRoots=[]`: não foi feito benchmark de varredura geral. A OL7 teve cobertura completa sem avisos; a RHEL5 teve apenas a observação conhecida de MemAvailable indisponível no kernel.

Os dois bancos de laboratório são locais/em memória; portanto não comprovam extração de endereço de um banco remoto real. Essa extração foi validada com fixtures controladas nas JVMs Java 6 das duas máquinas e na cadeia HTTP/relatórios local. Nenhuma conexão ao banco foi aberta pelo coletor.

A aplicação Spring existente estava parada e foi iniciada com seu script de laboratório já existente. Ficou em execução. Não foram alterados serviço, cadastro, configuração ou binário dos agentes. Arquivos privados do probe ficaram em diretórios temporários próprios no laboratório.

Evidências locais privadas:

- `output/jdbc-discovery/lab-ol07-2mj3cjdc/`
- `output/jdbc-discovery/lab-instance-rh5-s9inn94t/`

## Limites e próxima entrega

Não reproduzimos o ambiente do cliente. Manifest Class-Path implícito, ordem ambígua de wildcard, configuração específica de WAR em container externo, configuração remota e datasources programáticos continuam limitados. Versão real do banco exige a integração opcional; não é inferida da versão do driver.

Antes da release consolidada, concluir os itens do instalador/inicialização em TODO, gerar os pacotes assinados com uma única nova versão e validar instalação e atualização da 2.0.53. Depois da publicação, atualizar também os agentes e solicitar novo assessment: inventários anteriores não são enriquecidos retroativamente.

## Publicação autorizada posterior — 2.0.55

Em 2026-10-16, após o usuário solicitar publicação, o dashboard de laboratório `https://167.126.4.37` foi atualizado de 2.0.53 para 2.0.55. A versão 2.0.54 é exclusivamente o bridge interno. As ferramentas de build públicas ausentes foram recuperadas do arquivo de fontes anterior verificado; a chave privada original foi recuperada da antiga pasta do projeto, em diretório privado, após confirmar sua correspondência com o pin público existente. Não houve rotação da confiança.

O build executou novamente as regressões de versão/HTTP e testes com o pacote final. Verificação independente confirmou assinaturas, 23 perfis de origem (incluindo 2.0.53), âncora imutável e checksums dos cinco artefatos. Os componentes de suporte da 2.0.53 e 2.0.55 são idênticos. Os 64 testes do publicador passaram; relatórios JDBC passaram em 68 verificações com o servidor final.

Publicação confirmada com backup privado, `/health` interno e HTTPS externo 2.0.55. Hashes externos de app.js, dashboard.js e reports.js iguais aos fontes publicados. Banco preservado com contagens iguais antes/depois, configuração e CA preservadas. Nenhum agente instalado foi atualizado. As correções pendentes do instalador não estão nesta publicação.

Recibos locais: `output/dashboard-releases/2.0.55/deployment/deployment-result.json`, `evidence/public-verification.json` e `evidence/external-ui-verification.json`. O backup remoto consta do recibo de publicação. A verificação de pacotes não substitui ensaio operacional de atualização dos agentes nem autoriza afirmar compatibilidade universal de produção.
