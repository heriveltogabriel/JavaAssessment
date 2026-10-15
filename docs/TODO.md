# Melhorias planejadas

## Atualização remota completa do agente

**Status:** implementação e regressões locais concluídas em 2026-10-14, com origem mínima 1.5.12. Bridge com binário original, pacote completo, operação lógica, recuperação, limpeza, desinstalação, empacotamento e assessment posterior integrados. Serviços/reboot em Linux real e os dois canários permanecem gates de release. O mecanismo em produção continua sendo o atual.

**Candidato de laboratório:** 2.0.9, com bridge interno 2.0.8, publicado pelo operador. A primeira tentativa pelo dashboard ocorreu em uma terceira máquina de laboratório, RHEL 9/Java 11, e ficou em `PREPARED`: o coletor antigo, protegido por `ProtectSystem=full`, não podia criar o serviço de recuperação em `/etc`. Após a retomada pontual pelo operador, o mesmo Work request confirmou 2.0.9 em 2026-10-14 às 22:45 GMT-3. A máquina voltou a reportar e um assessment vinculado foi recebido. Os dois canários originais 1.5.12 continuam sem migrar.

**Correção em validação:** a próxima montagem deve iniciar o bootstrap systemd em um serviço independente criado pelo PID 1, preservando a proteção do coletor. Fonte corrigida e regressões locais passaram; os artefatos publicados 2.0.9 permanecem imutáveis. `ops/retomar-migracao.py` destravou exclusivamente o pedido identificado, conferindo assinatura, origem e hashes preservados de configuração, identidade, Java, CA e chave pública. O sucesso posterior foi conferido na interface real. O próximo candidato deve comprovar o fluxo automático em um canário original 1.5.12, sem essa intervenção. Isso não encerra os gates de Linux/Java 6, reinício/recuperação, segundo canário e evolução posterior.

**Próximo candidato preparado:** 2.0.11, com bridge interno 2.0.10 e o despacho systemd corrigido. Builder, suíte integrada de migração, 20 verificações dos artefatos públicos e 53 checks da retomada passaram. As sete rotas conhecidas incluem 1.5.12; código e manager são novos, enquanto o bootstrap preserva exatamente a versão pública assinada 2.0.9. Publicação pelo operador e primeira migração automática do canário original ainda pendentes; selecionar apenas uma máquina. Continua exclusivo de laboratório.

- [Desenho: migração, preservação, recuperação, limpeza e assessment](superpowers/specs/2026-10-14-full-agent-update-design.md)
- [Plano com oito workflows e gates de validação](superpowers/plans/2026-10-14-full-agent-update.md)

O primeiro gate usa o atualizador e os scripts originais 1.5.12, incluindo ACK, limpeza, locks e passagem para recuperação independente. Depois dos testes isolados, duas máquinas ainda em 1.5.12 serão canários em sequência. Uma segunda release que altere suporte/manager deverá comprovar a evolução posterior pelo painel.

No mecanismo anterior ao candidato 2.0.9, a atualização remota substitui somente `collector.jar`. Os scripts, bibliotecas e o atualizador instalado precisam corresponder aos arquivos de suporte do manifesto assinado. Pular versões é permitido quando esses requisitos continuam compatíveis.

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
