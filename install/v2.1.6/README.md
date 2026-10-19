# Java Assessment 2.1.6 — teste pela console

JAR do datasource Spring e prazo de início por máquina no lote, além das melhorias JDBC das entregas anteriores. [Notas](../../docs/RELEASE_2.1.6.md) · [Validação](../../docs/validation/console-release-2026-10-19.md).

## Arquivo para enviar na console

[assessment-dashboard-web-update-2.1.6.zip](assessment-dashboard-web-update-2.1.6.zip)

Em **Administração → Atualização do dashboard**, envie o ZIP, aguarde **Pronto para atualizar**, confira as versões, informe sua senha e confirme **Atualizar e reiniciar**. Aguarde o retorno e confira a versão 2.1.6 e o log. Fechar a página não cancela uma aplicação iniciada. O executor verifica assinatura e integridade automaticamente e cria backup antes da aplicação.

O dashboard de laboratório ainda não recebeu esta entrega: o teste pela console será feito pelo operador. A origem instalada precisa corresponder aos hashes aceitos no pacote.

## Outros pacotes

- [Instalador de servidor novo](assessment-dashboard-installer-2.1.6.zip): Enterprise Linux 8+, JDK 17+, Python compatível. Não usar sobre instalação existente nem no upload.
- [Atualização por script](assessment-dashboard-update-2.1.6.zip): para origens compatíveis sem executor independente. Siga o README incluído; com executor instalado, use o pacote web.

Cada ZIP mantém seu arquivo .sha256; versões anteriores foram preservadas.

**Atualize também os agentes**, separadamente pelo painel, começando por uma máquina elegível, e solicite nova coleta para o campo JAR dos datasources Spring. Sem rota compatível, use o instalador completo configurado do ambiente. A 2.1.5 é ponte interna, não exige atualização manual intermediária de agentes V3.

Validação isolada: dashboard 2.1.4 → 2.1.6 com restauração; agentes V3 2.0.53, 2.1.0 e 2.1.4 → 2.1.6 com binários originais. O upload e reinício na instalação real aguardam seu teste.
