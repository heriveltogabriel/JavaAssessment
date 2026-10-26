# Java Assessment 2.1.26 — campanhas de atualização de agentes

Esta versão organiza atualizações de frotas grandes sem carregar todas as máquinas no navegador nem iniciar toda a fila ao mesmo tempo. A revisão e o acompanhamento usam páginas de 50 máquinas; os eventos são carregados ao abrir cada máquina. Não há teto fixo de 1.500 ou 3.000 agentes. A implementação foi exercitada com 5.000 registros sintéticos.

## Operação

- Seleção explícita por máquina, página ou todas as candidatas do filtro, com quantidade e versão confirmadas antes de criar pedidos.
- Grupos de 10 por padrão, configuráveis de 1 a 100; concorrência global de 2 por padrão, configurável de 1 a 10. Um grupo avança depois de encerrar suas operações pendentes.
- Fila persistente no SQLite, independente do navegador e retomada após reiniciar a console.
- Prazo de cinco minutos para início contado somente quando a máquina recebe sua vez. Máquinas sem contato recente expiram e não bloqueiam o restante indefinidamente.
- Pausar, retomar, cancelar a fila inteira ou por seleção/filtro e repetir resultados problemáticos. Cancelamento processado pelo servidor em blocos, sem interromper uma instalação já iniciada.
- Pausa automática diante de resultado desconhecido ou três falhas/restaurações desde a retomada; não há repetição infinita de instalações incertas.
- Coleta após atualização opcional, inicialmente desmarcada; liberada somente após confirmar a versão final.

Consulte o [guia de campanhas](AGENT_UPDATE_CAMPAIGNS.md). Para pedidos antigos, encerre a fila pendente e crie uma campanha nova para obter o prazo por admissão. Operações já iniciadas devem terminar ou ter seu resultado conferido antes de nova tentativa.

## Atualização

A versão final é **2.1.26**; **2.1.25** é uma ponte interna reservada pelo processo de build, sem instalação manual nas origens V3 elegíveis. Use o ZIP **web-update** em **Administração → Atualização do dashboard**. O instalador completo do dashboard é destinado a servidor novo.

Consulte as rotas verificadas a partir de 2.1.20, 2.1.22 e 2.1.24 e os limites das provas locais nas [evidências da release](validation/console-release-2.1.26.md), publicadas junto aos pacotes finais. A elegibilidade é conferida novamente na instalação real.

**Atualizar o dashboard não atualiza os agentes automaticamente. Atualize também os agentes separadamente.** As mudanças funcionais desta entrega estão no dashboard; o protocolo do agente foi preservado. Agentes antigos elegíveis podem entrar na campanha após a console ser atualizada. Instalações sem capacidade/rota compatível precisam do pacote de agente configurado para seu ambiente, com `--system-root`, preservando identidade, configuração e CA. Não utilize o instalador do dashboard para essa finalidade.

## Escopo de validação

Esta entrega é preparada para teste do operador, usando o fluxo `--lab-canary`. Testes locais e 5.000 registros sintéticos não equivalem a 5.000 transferências simultâneas nem garantem a conclusão durante uma noite. Comece com um pequeno piloto, confira versão e contato e então amplie a campanha. Nenhuma máquina do cliente foi atualizada durante o build.
