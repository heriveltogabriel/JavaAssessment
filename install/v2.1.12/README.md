# Java Assessment 2.1.12 — teste pela console

Release da branch `correcoes-2.1.10`, com as correções do agente preservadas. [Notas](../../docs/RELEASE_2.1.12.md) · [Validação](../../docs/validation/console-release-2.1.12.md).

## Arquivo para enviar na console

[assessment-dashboard-web-update-2.1.12.zip](assessment-dashboard-web-update-2.1.12.zip)

Em **Administração → Atualização do dashboard**, envie o ZIP, aguarde **Pronto para atualizar**, confira origem e destino, informe sua senha e confirme **Atualizar e reiniciar**. Aguarde o retorno e confira 2.1.12. O executor verifica assinatura e integridade e cria backup antes da aplicação. Fechar a página não cancela uma aplicação iniciada.

A rota **2.1.8 → 2.1.12** passou nos ensaios isolados do dashboard, incluindo restauração, e do agente com seu manager original. **Não é necessário instalar a 2.1.10.** O operador deve confirmar o comportamento no ambiente real.

## Outros pacotes

- [Instalador para servidor novo](assessment-dashboard-installer-2.1.12.zip): Enterprise Linux 8+, JDK 17+, Python compatível. Não usar sobre dashboard existente nem no upload da console.
- [Atualização por script](assessment-dashboard-update-2.1.12.zip): para origens compatíveis sem executor independente; com o executor instalado, prefira o pacote web.

Cada ZIP acompanha seu `.sha256`. Versões anteriores foram preservadas. Nenhum deploy remoto foi realizado nesta entrega.

## Agentes e coleta

**Atualize também os agentes**, separadamente pelo painel, começando por uma máquina elegível. A 2.1.11 é a ponte interna desta entrega, sem instalação manual intermediária. Sem rota elegível, use o instalador completo configurado para o ambiente. Após confirmar 2.1.12, solicite nova coleta; dados antigos não são recalculados.

`/opt` volta às inclusões padrão e sai das exclusões. Listas já salvas não são migradas: revise ambientes que salvaram `/opt` como exclusão. Os novos padrões também incluem `/etc/config` e `/prd1`.

Esta versão corrige descoberta Tomcat/jsvc, associação de configurações externas, leitura de manifestos assinados e resolução JDBC quando a JVM está em caminho excluído. Versão do driver não é versão do banco; a coleta continua passiva, sem conexão ao banco ou à administração dos servidores.
