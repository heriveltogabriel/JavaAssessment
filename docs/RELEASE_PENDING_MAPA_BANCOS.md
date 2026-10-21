# Pendente de release — Excel, Mapa de bancos e driver provável

Branch `mapa-bancos`, sobre `main` 2.1.16. Sem versão reservada; gerar como **2.1.18** (ponte 2.1.17) na máquina de build. Nunca reaproveitar o número 2.1.16.

## Dashboard

- **Exportar inventário → Excel (.xlsx):** um arquivo com uma aba por tipo (Resumo, Ambientes, Máquinas, Java, Processos, Servidores, Bibliotecas, Aplicações, Datasources e Mapa de bancos), só com as colunas de cada tipo, listas resumidas em texto, primeira linha fixa com filtro e aviso quando uma célula passaria do limite do Excel. O CSV completo continua disponível sem mudanças.

- **Exportar inventário → Mapa de bancos (CSV para Excel):** ZIP com `mapa-bancos.csv`, uma linha por datasource × destino do banco × processo/aplicação, separador `;`, chaves `chaveServico`, `chaveHostPorta` e `hostCurto` para cruzar com outro inventário. Detalhes em [INVENTORY_EXPORT.md](INVENTORY_EXPORT.md).
- **Driver provável:** a tela só mostra JAR/versão "provável" de uma inspeção incompleta quando o único candidato estava no classpath. Antes, um driver presente só na aplicação Tomcat (`WEB-INF/lib`) ou um JAR em disco fora do classpath do WebLogic podia aparecer como provável.

## Agente

Sem mudanças; agentes 2.1.16 não precisam atualização para o mapa (ele usa os dados já coletados).

## Testes

`InventoryArchiveTest` (planilha: partes do arquivo, abas, resumos, células longas, escape XML e segredos; mapa: junções, chaves, endpoints múltiplos, sem destino, candidato fora do classpath, segredos), `InventoryArchiveHttpTest` (download e bloqueio para leitores), `tests/inventory-export-ui.test.cjs` e `tests/jdbc-driver-ui.test.cjs`.
