# Java Assessment 2.1.28 — atualização de agentes em frotas grandes

Release consolidada a partir da `main`, commit `6f910ad`. Ponte interna **2.1.27** e destino **2.1.28**. Veja os [pacotes](../install/v2.1.28/README.md) e as [provas e limites da validação final](validation/console-release-2.1.28.md).

Esta versão reduz a contenção no servidor durante campanhas e ingestão de coletas, conforme as provas locais com agentes simulados. As mudanças de desempenho estão no servidor; **o protocolo do agente não mudou**. Como dashboard e agente compartilham o número de versão, agentes 2.1.26 aparecerão como candidatos a atualização, mas atualizá-los não é necessário para obter a melhora.

## O que mudou

- **Agentes não disputam mais o lock de escrita quando não há trabalho.** Heartbeat, consulta de ações e poll ociosos são servidos por leitura e só gravam quando algo muda. A manutenção da frota (expiração, promoção dos lotes) deixou de rodar a cada chamada de agente e fica no agendador de 5 s. Na medição anterior documentada, com 1.150 agentes simulados: de 12 a 14 ms para cerca de 1 ms por chamada.
- **Resultados de coleta** são validados e preparados antes do lock e gravados em lote. No máximo 3 resultados disputam o lock; os demais recebem 503 com `Retry-After` e o agente reenvia do spool no ciclo seguinte. Heartbeats e telas do operador deixam de ficar atrás da fila de resultados.
- **Faixa limitada para agentes** (10 chamadas simultâneas de heartbeat/actions/poll), pool de 16 threads e 8 leitores SQLite, para reservar capacidade de atendimento às telas da console.
- **Limite de máquinas atualizando ao mesmo tempo**: padrão 10 (antes 2) e teto 25 (antes 10). As ondas continuam: o grupo seguinte só começa quando o anterior termina. Instalações que guardam o 2 implícito de versões anteriores passam a usar 10; um valor salvo pelo administrador é respeitado.
- **Coleta agendada espalhada**: cada host recebe um deslocamento fixo (derivado do ID) dentro dos primeiros 15 minutos da janela, em vez de todos coletarem no mesmo minuto. Configurável em `conf/server.properties` com `scheduleSpreadMinutes` (0 desliga; máximo 240 e metade da janela).
- **`sqliteSynchronous=NORMAL`** passa a ser o padrão (`FULL` volta ao comportamento anterior). No modo WAL, `NORMAL` reduz a durabilidade dos commits mais recentes diante de queda de energia ou falha do sistema operacional; configure `FULL` quando precisar da durabilidade anterior.
- Diagnóstico no stderr do servidor: `slow request` (acima de 1,5 s, com a espera acumulada pelo lock) e `maintenance took` (acima de 1 s).

## Mudanças de comportamento a conhecer

- Expiração de pedidos e promoção do próximo lote acontecem em até 5 s, e antes aconteciam no próximo poll.
- Em rajadas, 503 nas chamadas de agente são esperados e transitórios; se aparecerem de forma contínua, o servidor está no limite do escritor único do SQLite.
- A coleta agendada começa até 15 minutos depois da abertura da janela, conforme o host.
- Agentes anteriores à 2.1.26 não foram exercitados com respostas 503; o coletor atual mantém o resultado no spool e reenvia.

## Atualização

**Atenção: atualize também os agentes. Atualizar o dashboard não atualiza os agentes automaticamente.** Para esta versão a melhora de desempenho já vale com os agentes atuais **2.1.26**; atualizar os agentes é **opcional** para obter essa melhora. Use **Revisar agentes** e a campanha apenas se quiser levar as máquinas à nova versão. Para uma console existente, use o **web-update** conforme as origens comprovadas na validação. O instalador completo do dashboard é destinado a servidor novo.

## Processo usado nesta entrega

1. Clonar a branch `main` no commit `6f910ad` ou posterior e garantir `output/` com a chave de assinatura e as releases anteriores, `tools/ecj-3.33.0.jar` e JDK 17.
2. Rodar `ops/test-source.sh` num clone limpo (não use uma pasta com cópias do Finder `* 2.java`, que quebram a compilação).
3. Rodar `python3 ops/build-dashboard-release.py --lab-canary` (como na 2.1.26), que reserva ponte e destino, recompila dashboard e agente, assina e empacota.
4. Verificar CRC, hashes e inventários dos ZIPs e a atualização a partir de 2.1.20, 2.1.22, 2.1.24 e 2.1.26, conforme `docs/RELEASE_PROCESS.md`.
5. Preparar em `install/v2.1.28`, criar o `README` dos pacotes, atualizar `install/README.md` e o README principal com a versão final, e copiar as evidências para `docs/validation/console-release-2.1.28.md`.
6. Primeiro piloto: uma onda de 10 máquinas, depois 25, observando `slow request` e `maintenance took` no stderr. Detalhes das medições em [agent-path-load-2026-10-26.md](validation/agent-path-load-2026-10-26.md).

Esta entrega foi gerada com `--lab-canary` para validação local e teste do operador. Não houve acesso a hosts do cliente nem deploy remoto. O envio ao GitHub depende de confirmação do usuário.
