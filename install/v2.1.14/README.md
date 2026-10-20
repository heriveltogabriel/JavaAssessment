# Java Assessment 2.1.14

Classpath longo e versão/JAR JDBC prováveis visíveis na tabela. [Notas](../../docs/RELEASE_2.1.14.md) · [Validação](../../docs/validation/console-release-2.1.14.md).

## Atualizar pela console (cliente na 2.1.12)

Use [assessment-dashboard-web-update-2.1.14.zip](assessment-dashboard-web-update-2.1.14.zip) em **Administração → Atualização do dashboard**. Aguarde **Pronto para atualizar**, confira origem/destino, informe sua senha e confirme **Atualizar e reiniciar**. Aguarde o retorno e confira 2.1.14. O executor verifica assinatura/integridade e cria backup. Fechar a página não cancela aplicação iniciada.

A atualização é **2.1.12 → 2.1.14**. A 2.1.13 é ponte interna, sem instalação manual intermediária. Consulte as evidências para os limites da validação isolada.

**Atualize também os agentes separadamente pelo painel e solicite nova coleta.** A tabela pode mostrar candidatos já coletados após atualizar somente o dashboard; os novos limites precisam do agente novo. Sem rota elegível, use o instalador completo configurado para o ambiente. Não é necessário reiniciar o WebLogic.

## Outros pacotes

- [Instalador para servidor novo](assessment-dashboard-installer-2.1.14.zip): Enterprise Linux 8+, JDK 17+, Python compatível; não usar no upload da console nem sobre dashboard existente.
- [Atualização por script](assessment-dashboard-update-2.1.14.zip): origens compatíveis sem executor independente; com executor instalado, prefira o web-update.

Cada ZIP tem seu `.sha256`. Artefatos anteriores foram preservados. Manuais genéricos continuam em [Guia de instalação](../Guia-instalacao.pdf) e [Manual](../Manual.pdf); esta alteração não modifica o procedimento de instalação.
