# Pendente de release — Mapa de bancos e driver provável

Branch `mapa-bancos`, sobre `main` 2.1.16. Sem versão reservada; gerar como **2.1.18** (ponte 2.1.17) na máquina de build. Nunca reaproveitar o número 2.1.16.

## Dashboard

- **Exportar inventário → Mapa de bancos (CSV para Excel):** ZIP com `mapa-bancos.csv`, uma linha por datasource × destino do banco × processo/aplicação, separador `;`, chaves `chaveServico`, `chaveHostPorta` e `hostCurto` para cruzar com outro inventário. Detalhes em [INVENTORY_EXPORT.md](INVENTORY_EXPORT.md).
- **Driver provável:** a tela só mostra JAR/versão "provável" de uma inspeção incompleta quando o único candidato estava no classpath. Antes, um driver presente só na aplicação Tomcat (`WEB-INF/lib`) ou um JAR em disco fora do classpath do WebLogic podia aparecer como provável.

## Agente

Sem mudanças; agentes 2.1.16 não precisam atualização para o mapa (ele usa os dados já coletados).

## Testes

`InventoryArchiveTest` (mapa: junções, chaves, endpoints múltiplos, sem destino, candidato fora do classpath, segredos), `InventoryArchiveHttpTest` (download e bloqueio para leitores), `tests/inventory-export-ui.test.cjs` e `tests/jdbc-driver-ui.test.cjs`.
