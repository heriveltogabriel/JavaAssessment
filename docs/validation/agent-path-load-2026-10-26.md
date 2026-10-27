# Carga dos agentes e atualização em campanha — 26/10/2026

Base: `3e2ecf0` (2.1.26 mais a correção de acompanhamento). Fontes sem incremento de versão; nenhum host do cliente acessado.

## Diagnóstico

A correção anterior reduziu as consultas do navegador, mas o servidor continuava com um único lock de escrita disputado por todos os agentes:

- `/api/agent/poll` e `/api/agent/actions` rodavam `expireJobs()` e a manutenção completa de atualizações e desinstalações **por agente**, dentro da transação de escrita global. O agendador de 5 s já faz isso.
- Poll, actions e heartbeat abriam transação de escrita mesmo sem nada para entregar ou alterar (cerca de 57 transações por segundo com 1.150 agentes em ciclo de 60 s).
- `/api/agent/results` validava cada registro do inventário (um `Json.stringify` por linha, até 2 MiB) com o lock tomado.
- Com 15 das 16 threads do servidor esperando o lock, lista, detalhe e `state` do operador ficavam na fila junto com os agentes.

## Correção

- `InventoryStore.readOrWrite`: executa em snapshot de leitura e repete na transação de escrita somente se a operação chegar a uma escrita. Heartbeat, actions e poll ociosos não pegam o lock.
- Heartbeat sem mudança de dados registra a presença em memória; a manutenção de 5 s grava `lastSeen` de todos em um único commit (e também no encerramento do servidor).
- `delivery()` de atualização e desinstalação deixou de rodar a manutenção da frota; expiração e promoção dos lotes ficam no agendador (até 5 s de atraso, antes imediato no próximo poll).
- Resultados de coleta: autenticação, validação de linhas e preparação das linhas de inventário acontecem antes do lock; a gravação usa um único `PreparedStatement` em lote. Qualquer falha na pré-validação é repetida dentro do lock, preservando a ordem dos erros.
- Faixas limitadas: no máximo 10 chamadas simultâneas de heartbeat/actions/poll e 3 resultados disputando o lock. O excedente recebe 503 com `Retry-After`; o agente já mantém o resultado no spool e reenvia no ciclo seguinte.
- Pool de 16 threads (fila 128) e 8 leitores SQLite.
- Diagnóstico: requisições acima de 1,5 s e manutenção acima de 1 s são registradas em stderr com a espera acumulada pelo lock.
- Lista de solicitações: `group_concat` de hostnames só quando há busca.

## Medição (`tests/AgentFleetLoadBench.java`)

1.150 agentes simulados em ciclo de 60 s (heartbeat, actions, poll) contra uma campanha real de 10 em 10, 4 abas de operador (lista e detalhe a cada 10 s), commit simulado de 10 ms no escritor. Servidor e carga na mesma JVM, em um Mac; os números mostram a diferença entre as versões, não o desempenho do servidor do cliente.

| Regime estável, p50 / p95 | Antes | Depois |
|---|---|---|
| actions | 12 ms / 39 ms | 1 ms / 2 ms |
| heartbeat | 14 ms / 38 ms | 1 ms / 3 ms |
| poll | 9 ms / 35 ms | 0 ms / 1 ms |

Rajada de coleta (todo agente envia um resultado de cerca de 0,5 MB em 150 s, com reenvio quando recebe 503):

| p95 / erros | Antes | Depois |
|---|---|---|
| heartbeat | 6.120 ms, 1.461 falhas de 2.622 | 379 ms, 6 falhas de 2.870 |
| actions / poll | 4,2 s, 54% de falhas por timeout de 30 s | menos de 1 ms, sem falhas |
| lista do operador | 30.000 ms (timeout) | 43 ms |
| detalhe do operador | 3.721 ms | 21 ms |
| resultados | p95 4,5 s, 1.127 timeouts | p95 451 ms; 864 respostas 503 reenviadas no ciclo seguinte |

Em 150 s cerca de 80% dos resultados foram aceitos; o restante entra nos ciclos seguintes. A capacidade de ingestão continua limitada pelo escritor único do SQLite: cerca de 15 ms de SQL por resultado de 830 linhas, mais o commit.

## Ajustes finais

- **Limite de simultâneos.** Padrão 10 (igual ao tamanho de grupo padrão) e teto 25 na API e na tela. Instalações que guardam o `2` implícito gravado pelas versões anteriores ao criar a primeira campanha passam a usar 10; um valor salvo pelo administrador (`explicit`) é sempre respeitado. As ondas continuam: o grupo seguinte só começa quando o anterior termina. Simulação de 35 máquinas: 10, 10, 10 e 5, nunca acima do limite.
- **`synchronous`.** `conf/server.properties`: `sqliteSynchronous=NORMAL` (padrão) ou `FULL`. Valor inválido cai em `FULL`. Com `NORMAL` no WAL o banco não corrompe; uma queda de energia ou do sistema operacional pode perder só os últimos commits (queda do processo não perde). O ganho em disco lento não foi medido aqui, porque o fsync do Mac é barato; o teste de carga simula o custo do commit igualmente nos dois modos. Um erro de implementação (parâmetro escondendo o campo validado) foi encontrado pelo teste e corrigido antes de qualquer uso.
- **Espalhamento da coleta agendada.** `scheduleSpreadMinutes` (padrão 15; 0 desliga; no máximo 240 e metade da janela). Cada host tem um deslocamento fixo, derivado do ID, dentro dos primeiros minutos da janela, e o job só é enfileirado depois dele. Coleta manual e pós-atualização não mudam. Com 1.150 hosts e 15 minutos: de 66 a 86 hosts por minuto, em vez de todos no mesmo minuto.
- Teste de carga com o código final: regime estável idêntico ao anterior (agentes 0 a 1 ms, operador 14 a 47 ms); rajada sintética de resultados continua com p95 abaixo de 0,5 s e telas em 14 a 62 ms.

## Testes

- 31 suítes Java de servidor e agente passam (incluindo `ScheduleSpreadTest`, novo), incluindo `WorkRequestsHttpTest` (novo: heartbeat, actions e poll respondem com uma transação de escrita aberta; falha na versão anterior) e `InventoryStoreTest` (novo: `readOrWrite`).
- `AgentUpdateHttpTest` e `PostUpdateAssessmentHttpTest` passaram a chamar `maintainOperations()` onde antes o poll fazia a expiração.

## Limites

- Os testes de navegador não foram reexecutados; nenhum arquivo web mudou.
- Não foi medido o efeito de disco lento real nem de agentes reais. `synchronous=FULL` foi mantido.
- Agentes antigos continuam com ciclos alinhados; jitter exige nova versão do agente.
- Os 503 por excesso de simultaneidade são esperados em rajadas; se aparecerem de forma contínua no cliente, o servidor está no limite do escritor único.
