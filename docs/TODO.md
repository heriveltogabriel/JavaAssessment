# Melhorias planejadas

## Atualização remota completa do agente

**Status:** adiada por decisão do usuário em 2026-10-14. Manter o mecanismo atual por enquanto.

Hoje a atualização remota substitui somente `collector.jar`. Os scripts, bibliotecas e o atualizador instalado precisam corresponder aos arquivos de suporte do manifesto assinado. Pular versões é permitido quando esses requisitos continuam compatíveis.

Nos pacotes publicados, 1.5.10 a 2.0.0 usam `update-v1`; a 2.0.1 passou a `update-v2` e alterou o atualizador; a 2.0.2 alterou o launcher para 96 MiB. Os arquivos de suporte da 2.0.2 e 2.0.3 são iguais. A compatibilidade de entregas futuras precisa ser verificada, não inferida apenas pelo número da versão.

### Resultado desejado

- Permitir atualizar também scripts, bibliotecas e componentes do atualizador pelo dashboard, sem SSH, com pacote e manifesto assinados e verificação de integridade.
- Definir uma migração explícita para atualizadores antigos. Eles não passam a entender um novo formato só porque o dashboard mudou.
- Preservar identidade, ambiente, configuração, confiança TLS/CA e inventário existente.
- Validar arquivos e permissões antes da troca; manter backup e recuperação caso haja falha, interrupção de energia ou falha na verificação de funcionamento.
- Mostrar a compatibilidade real antes da confirmação e acompanhar todas as etapas em Work requests.
- Validar saltos entre releases, Java 6 e posteriores e os serviços SysV/systemd. Não declarar suporte testado em 32 bits enquanto não houver validação real.

Não remover verificações de assinatura, hashes ou geração do atualizador para liberar artificialmente agentes antigos.

Enquanto esta melhoria está adiada, toda entrega deve lembrar a atualização dos agentes no dashboard e nas instruções, conforme [o processo de release](RELEASE_PROCESS.md).
