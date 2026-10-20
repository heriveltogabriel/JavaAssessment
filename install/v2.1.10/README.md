# Java Assessment 2.1.10 — teste pela console

Entrega consolidada de configurações Spring, resolução JDBC WebLogic/Tomcat/JBoss e novos padrões de coleta. [Notas](../../docs/RELEASE_2.1.10.md) · [Validação](../../docs/validation/console-release-2.1.10.md).

## Arquivo para enviar na console

[assessment-dashboard-web-update-2.1.10.zip](assessment-dashboard-web-update-2.1.10.zip)

Em **Administração → Atualização do dashboard**, envie o ZIP, aguarde **Pronto para atualizar**, confira origem e destino, informe sua senha e confirme **Atualizar e reiniciar**. Aguarde o retorno e confira 2.1.10. O executor verifica assinatura/integridade e cria backup antes da aplicação. Fechar a página não cancela uma aplicação iniciada.

## Outros pacotes

- [Instalador para servidor novo](assessment-dashboard-installer-2.1.10.zip): Enterprise Linux 8+, JDK 17+, Python compatível. Não usar sobre dashboard existente nem no upload da console.
- [Atualização por script](assessment-dashboard-update-2.1.10.zip): para origens compatíveis sem executor independente; com o executor instalado, prefira o pacote web.

A rota **2.1.8 → 2.1.10** passou nos ensaios isolados do dashboard (incluindo restauração) e do agente com seu manager original. O operador deve confirmar o comportamento no ambiente real.

Cada ZIP acompanha seu `.sha256`. Versões anteriores foram preservadas. A validação local e as origens verificadas estão no documento de evidências; a confirmação na console real será feita pelo operador.

## Agentes e coleta

**Atualize também os agentes**, separadamente pelo painel, começando por uma máquina elegível. A 2.1.9 é a ponte interna desta entrega, sem atualização manual intermediária. Sem rota elegível, use o instalador completo configurado para o ambiente. Após confirmar 2.1.10, solicite nova coleta; dados antigos não são recalculados.

Os padrões novos incluem `/etc/config` e `/prd1` e excluem `/opt`, inclusive para arquivos associados a processos ativos. Listas já salvas não são migradas. Para inventariar configurações e drivers em `/opt`, remova essa exclusão. Processo e JVM observados continuam visíveis com uma observação.

Versão do driver não é versão do banco. Candidatos, versões declaradas e incertezas ficam explícitos; não há conexão ao banco ou à administração dos servidores.
