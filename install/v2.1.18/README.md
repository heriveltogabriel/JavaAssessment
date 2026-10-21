# Java Assessment 2.1.18

Excel (.xlsx), Mapa de bancos e driver provável somente com evidência de classpath. [Notas](../../docs/RELEASE_2.1.18.md) · [Formatos de exportação](../../docs/INVENTORY_EXPORT.md) · [Validação](../../docs/validation/console-release-2.1.18.md).

## Atualizar pela console

Use [assessment-dashboard-web-update-2.1.18.zip](assessment-dashboard-web-update-2.1.18.zip) em **Administração → Atualização do dashboard**. O caminho é direto da **2.1.14 ou 2.1.16 para 2.1.18**, sem instalar manualmente a ponte 2.1.17. Aguarde **Pronto para atualizar**, confira origem/destino, informe sua senha e confirme **Atualizar e reiniciar**. O executor confere assinatura/integridade e cria backup; fechar a página não cancela uma aplicação já iniciada.

Após o retorno, confira a versão 2.1.18 e teste **Administração → Exportar inventário → Excel (.xlsx)** ou **Mapa de bancos**. JSON e CSV completo continuam disponíveis. O mapa mostra destinos configurados, não conexões comprovadas; JSON preserva detalhes que a planilha resume.

**Atualizar o dashboard não atualiza os agentes automaticamente.** O agente não teve mudança funcional. Agentes 2.1.16 e dados já coletados atendem às novas funcionalidades, sem nova coleta obrigatória. O build mantém a identificação unificada 2.1.18; para alinhar versões, revise a elegibilidade e atualize agentes separadamente pelo painel. Instalações sem capacidade/rota elegível precisam do instalador configurado para seu ambiente.

## Outros pacotes

- [Instalador para servidor novo](assessment-dashboard-installer-2.1.18.zip): Enterprise Linux 8+, JDK 17+; não usar no upload da console nem sobre dashboard existente.
- [Atualização por script](assessment-dashboard-update-2.1.18.zip): para origens compatíveis sem executor independente. Com executor instalado, use o web-update.

Cada ZIP tem um arquivo `.sha256`. Os pacotes 2.1.14 e 2.1.16 permanecem preservados. O [Manual](../Manual.pdf) e o [Guia de instalação](../Guia-instalacao.pdf) mantêm seus nomes genéricos; o [guia de exportação](../../docs/INVENTORY_EXPORT.md) documenta as novas opções.

Nenhum deploy remoto foi realizado. Consulte os limites das provas locais na documentação de validação antes de testar no ambiente do cliente.
