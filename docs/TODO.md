# Melhorias planejadas

## Atualização remota completa do agente

**Status:** implementação e regressões locais concluídas em 2026-10-14, com origem mínima 1.5.12. Bridge com binário original, pacote completo, operação lógica, recuperação, limpeza, desinstalação, empacotamento e assessment posterior integrados. Serviços/reboot em Linux real e os dois canários permanecem gates de release. O mecanismo em produção continua sendo o atual.

**Candidato de laboratório:** 2.0.9, com bridge interno 2.0.8, preparado após a pré-verificação dos dois canários e a autorização do usuário para usar a primeira VM de teste sem snapshot. Pacotes e assinaturas finais conferidos; nenhuma VM foi atualizada nesta preparação. A execução do canário será iniciada pelo dashboard, uma máquina por vez. Isso não encerra os gates de Linux/Java 6, reinício/recuperação, segundo canário e evolução posterior.

- [Desenho: migração, preservação, recuperação, limpeza e assessment](superpowers/specs/2026-10-14-full-agent-update-design.md)
- [Plano com oito workflows e gates de validação](superpowers/plans/2026-10-14-full-agent-update.md)

O primeiro gate usa o atualizador e os scripts originais 1.5.12, incluindo ACK, limpeza, locks e passagem para recuperação independente. Depois dos testes isolados, duas máquinas ainda em 1.5.12 serão canários em sequência. Uma segunda release que altere suporte/manager deverá comprovar a evolução posterior pelo painel.

Na release atualmente publicada, a atualização remota substitui somente `collector.jar`. Os scripts, bibliotecas e o atualizador instalado precisam corresponder aos arquivos de suporte do manifesto assinado. Pular versões é permitido quando esses requisitos continuam compatíveis.

Nos pacotes publicados, 1.5.10 a 2.0.0 usam `update-v1`; a 2.0.1 passou a `update-v2` e alterou o atualizador; a 2.0.2 alterou o launcher para 96 MiB. Os arquivos de suporte da 2.0.2 e 2.0.3 são iguais. A compatibilidade de entregas futuras precisa ser verificada, não inferida apenas pelo número da versão.

### Resultado desejado

- Permitir atualizar também scripts, bibliotecas e componentes do atualizador pelo dashboard, sem SSH, com pacote e manifesto assinados e verificação de integridade.
- Definir uma migração explícita para atualizadores antigos. Eles não passam a entender um novo formato só porque o dashboard mudou.
- Preservar identidade, ambiente, configuração, confiança TLS/CA e inventário existente.
- Validar arquivos e permissões antes da troca; manter backup e recuperação caso haja falha, interrupção de energia ou falha na verificação de funcionamento.
- Mostrar a compatibilidade real antes da confirmação e acompanhar todas as etapas em Work requests.
- Validar saltos entre releases, Java 6 e posteriores e os serviços SysV/systemd. Não declarar suporte testado em 32 bits enquanto não houver validação real.

Não remover verificações de assinatura, hashes ou geração do atualizador para liberar artificialmente agentes antigos.

Toda entrega continua lembrando a atualização dos agentes no dashboard e nas instruções, conforme [o processo de release](RELEASE_PROCESS.md). O código novo inclui a opção de executar um assessment após a confirmação final. Consulte o [contrato V3](AGENT_UPDATE_V3.md), as [evidências e limites](validation/full-agent-update.md) e o [runbook de canários](runbooks/agent-update-1512-canary.md).
