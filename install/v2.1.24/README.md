# Java Assessment 2.1.24

Correções da identificação de drivers JDBC no WebLogic, associação das configurações ao domínio correto, reconhecimento de `ojdbc14` e diagnóstico detalhado. [Notas](../../docs/RELEASE_2.1.24.md) · [Validação](../../docs/validation/console-release-2.1.24.md).

## Atualizar a console

1. Baixe [assessment-dashboard-web-update-2.1.24.zip](assessment-dashboard-web-update-2.1.24.zip) e seu [SHA-256](assessment-dashboard-web-update-2.1.24.zip.sha256).
2. Na console existente, abra **Administração → Atualização do dashboard** e envie o ZIP web-update.
3. Aguarde a validação de origem/assinatura, confira o destino **2.1.24** e confirme **Atualizar e reiniciar** com sua senha.
4. Após o retorno, confira versão, ambientes, inventário e contato dos agentes.

As origens 2.1.20 e 2.1.22 são verificadas pelas provas locais desta entrega; consulte as evidências e seus limites. Não é necessário instalar uma versão intermediária manualmente. A ponte 2.1.23 é interna.

## Atualizar os agentes e coletar novamente

**Atualizar o dashboard não atualiza os agentes automaticamente.** As melhorias desta entrega estão no agente.

1. Abra **Revisar agentes**, confira a elegibilidade e comece com uma máquina do cliente.
2. Para instalações 2.1.20/2.1.22 com atualizador V3 íntegro, solicite a atualização pelo painel para **2.1.24**. Não há instalação manual da ponte.
3. Acompanhe **Solicitações** e confirme a versão final informada pelo agente e seu contato com o dashboard.
4. Execute uma nova coleta (ou selecione a coleta após a atualização) e confira JAR/versão dos drivers, associação WebLogic e observações. O inventário antigo não é recalculado.
5. Depois dessa validação, atualize as demais máquinas elegíveis.

Se o painel indicar uma instalação sem capacidade/rota compatível, baixe **o pacote do agente configurado para o ambiente correto** e execute seu instalador completo com `--system-root`, preservando cadastro/configuração e CA. O ZIP do instalador do dashboard abaixo não é um instalador de agente.

## Outros pacotes

- [Instalador completo do dashboard](assessment-dashboard-installer-2.1.24.zip) · [SHA-256](assessment-dashboard-installer-2.1.24.zip.sha256): servidor novo, Enterprise Linux 8+ e JDK 17+. Não enviar este arquivo à console nem usá-lo para reinstalar uma console existente.
- [Atualização por script](assessment-dashboard-update-2.1.24.zip) · [SHA-256](assessment-dashboard-update-2.1.24.zip.sha256): alternativa operacional por terminal. Consulte o README e os scripts incluídos; na console 2.1.20/2.1.22, prefira web-update.

Os pacotes anteriores foram preservados. Esta publicação não instala nada nas máquinas do cliente. A confirmação em produção exige a atualização e uma coleta nova no ambiente real; não há promessa de percentual de identificação de drivers.
