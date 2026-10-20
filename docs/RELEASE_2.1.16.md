# Java Assessment 2.1.16

Exportação do inventário técnico em **Administração → Exportar inventário**, para análise em outra aplicação. Ponte interna reservada: 2.1.15; destino final: 2.1.16.

- Um ZIP com máquinas, ambientes, sistemas operacionais, hardware, Java, processos, aplicações, servidores, bibliotecas e datasources JDBC.
- Preserva versões, JARs, vínculos, destinos configurados, evidências por PID, observações e estatísticas da coleta atual. A identificação do cliente/levantamento é opcional.
- Exclui cadastros de usuários, credenciais, chaves, certificados, configurações operacionais e comandos completos. Nomes, IPs e caminhos técnicos continuam identificáveis; não é um pacote anonimizado.
- Exportação restrita a administradores, geração consistente e incremental, limites explícitos e remoção de temporários. Não há importação no dashboard. Relatórios PDF/CSV continuam disponíveis.

## Instalação e atualização

Para um dashboard existente com executor, envie **assessment-dashboard-web-update-2.1.16.zip** em Administração → Atualização do dashboard. O instalador completo é para servidor novo. Consulte [os pacotes](../install/v2.1.16/README.md), [a validação](validation/console-release-2.1.16.md) e [o formato do inventário](INVENTORY_EXPORT.md).

**Atenção: atualize também os agentes pelo painel.** Atualizar o dashboard não atualiza os agentes automaticamente. A exportação já funciona com os dados existentes, sem nova coleta obrigatória; a versão do agente acompanha a entrega unificada. Agentes 2.1.14 com `update-v3` usam o pacote completo diretamente. As 32 rotas antigas de migração até 2.1.12 foram preservadas; não foi ampliado o limite dos verificadores publicados. Instalações sem capacidade V3/rota elegível devem usar o instalador completo configurado para o ambiente.

Entrega para teste do operador. Não houve instalação remota nem validação em dados do cliente. Os limites de cada prova estão registrados na documentação de validação.
