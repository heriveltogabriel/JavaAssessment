# Avisos em cada nova versão

## Antes de entregar

1. Incrementar a versão conforme o processo de release e manter a identificação dos pacotes coerente. Não substituir silenciosamente os artefatos de uma versão já publicada.
2. Comparar o manifesto assinado, a geração mínima do atualizador e os hashes dos arquivos de suporte com os pacotes anteriores.
3. Informar nas notas e na mensagem de entrega quais versões podem atualizar pelo painel e quais precisam do instalador completo. Não presumir compatibilidade só porque a versão instalada é maior que 1.5.10.
4. Incluir o alerta abaixo, mesmo se a alteração parecer ser apenas de interface. O alerta pode esclarecer que já não há agentes pendentes, se isso foi verificado.
5. Verificar que o aviso administrativo do dashboard aparece com agentes antigos, desaparece quando as versões confirmadas estão atualizadas e abre a prévia de atualização sem criar pedidos automaticamente.

## Texto para a entrega

> **Atenção: atualize também os agentes.** Publicar o dashboard não atualiza os agentes das máquinas. Abra **Revisar agentes** para conferir a versão e a elegibilidade. Use o painel para instalações compatíveis; quando os componentes de suporte exigirem migração, baixe o pacote configurado para o ambiente no dashboard e execute o instalador completo com `--system-root`. Acompanhe o resultado em **Work requests** e confirme a versão informada pela máquina.

Acrescentar ao texto a versão entregue e os caminhos de atualização efetivamente validados. Para novas capacidades de descoberta, orientar um novo assessment após a atualização; inventários anteriores não são recalculados retroativamente.

Use o pacote configurado para o dashboard e ambiente corretos. A operação deve preservar cadastro e configuração existentes, inclusive a CA usada na conexão HTTPS.

## Atualização completa em validação

O código V3 e seus gates estão em [TODO](TODO.md) e no [contrato](AGENT_UPDATE_V3.md). Para a primeira release, reservar B/T com `reserve_update_pair()`, preparar origens conhecidas por checksum e incluir o catálogo/bridges/bundle no runtime e instalador. Validar os canários antes de anunciar compatibilidade real. O aviso não cria pedidos nem instala nada automaticamente.
