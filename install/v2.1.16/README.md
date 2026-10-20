# Java Assessment 2.1.16

Exportação do inventário técnico em JSON ou CSV único dentro do ZIP pela Administração. [Notas](../../docs/RELEASE_2.1.16.md) · [Validação](../../docs/validation/console-release-2.1.16.md) · [Conteúdo exportado](../../docs/INVENTORY_EXPORT.md).

## Atualizar pela console

Envie [assessment-dashboard-web-update-2.1.16.zip](assessment-dashboard-web-update-2.1.16.zip) em **Administração → Atualização do dashboard**. Aguarde **Pronto para atualizar**, confira origem/destino, informe sua senha e confirme **Atualizar e reiniciar**. Após o retorno, confira a versão 2.1.16 e abra **Administração → Exportar inventário**.

A atualização e a restauração **2.1.14 → 2.1.16 → 2.1.14** foram verificadas com os pacotes finais em ambiente local isolado. O executor confere assinatura/integridade e cria backup. Fechar a página não cancela aplicação iniciada. A ponte 2.1.15 é interna, sem instalação manual intermediária.

**Atualize também os agentes separadamente pelo painel.** Agentes 2.1.14 com capacidade `update-v3` atualizam diretamente para 2.1.16; as 32 rotas antigas foram preservadas. Sem capacidade/rota elegível, use o instalador completo configurado para o ambiente. A exportação já funciona com os dados existentes, sem nova coleta obrigatória.

## Outros pacotes

- [Instalador para servidor novo](assessment-dashboard-installer-2.1.16.zip): Enterprise Linux 8+, JDK 17+; não usar no upload da console nem sobre dashboard existente.
- [Atualização por script](assessment-dashboard-update-2.1.16.zip): para origens compatíveis sem executor independente. Com executor instalado, prefira o web-update.

Cada ZIP acompanha seu `.sha256`. As versões anteriores foram preservadas. O [Manual](../Manual.pdf) inclui a nova aba e mantém o nome genérico; o [Guia de instalação](../Guia-instalacao.pdf) continua aplicável.

O ZIP do inventário exportado é destinado à análise em outra aplicação. Não é o pacote de atualização e não substitui backup. Exclui dados de acesso; nomes, IPs e caminhos técnicos continuam identificáveis.

## Revisão dos pacotes na mesma versão

Por solicitação explícita, estes arquivos substituem a primeira entrega 2.1.16. A console de teste foi restaurada para 2.1.14 antes do novo upload. O atualizador continua bloqueando upload sobre a mesma versão: não use este web-update sobre uma 2.1.16 instalada. As assinaturas e os bytes dos agentes foram preservados. Agentes já na 2.1.16 não precisam reinstalação. Os novos hashes identificam os pacotes revisados; descarte o ZIP de dashboard baixado anteriormente e baixe novamente.
