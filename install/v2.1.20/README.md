# Java Assessment 2.1.20

Coluna e detalhes **Profile/contexto** para distinguir datasources de mesmo nome. [Notas](../../docs/RELEASE_2.1.20.md) · [Guia Tomcat/JBoss](../../docs/TOMCAT_JBOSS_DISCOVERY.md) · [Validação](../../docs/validation/console-release-2.1.20.md).

## Atualizar pela console

Envie [assessment-dashboard-web-update-2.1.20.zip](assessment-dashboard-web-update-2.1.20.zip) em **Administração → Atualização do dashboard**. Aguarde a validação, confira origem/destino e confirme **Atualizar e reiniciar** com sua senha. Atualização direta da 2.1.18 e da 2.1.16 revisada validada em ambiente isolado. A ponte interna 2.1.19 não precisa de instalação manual.

Após o retorno, abra Datasources JDBC e confira **Profile/contexto** na tabela e nos detalhes. O inventário já coletado pode fornecer essa informação; nomes iguais não são unidos. Campo ausente aparece como Não identificado.

**Atualizar o dashboard não atualiza agentes automaticamente.** Não houve mudança funcional no coletor. Abra Revisar agentes para conferir versões e elegibilidade; o alinhamento é feito separadamente pelo painel quando compatível. Instalações sem rota elegível precisam do pacote do agente configurado para seu ambiente.

## Outros pacotes

- [Instalador completo](assessment-dashboard-installer-2.1.20.zip): servidor novo, Enterprise Linux 8+ e JDK 17+; não enviar na console nem instalar sobre dashboard existente.
- [Atualização por script](assessment-dashboard-update-2.1.20.zip): origens compatíveis sem executor independente; com executor, prefira web-update.

Cada ZIP possui arquivo .sha256. Versões anteriores preservadas. Os manuais genéricos de instalação continuam aplicáveis; o guia Tomcat/JBoss descreve a nova coluna. Nenhum deploy remoto foi realizado.
