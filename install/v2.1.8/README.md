# Java Assessment 2.1.8 — teste pela console

Agente mais rápido e preciso (JVMs em execução, JARs internos, drivers e datasources) e dashboard com status de suporte, painel de riscos e visões de bancos de dados. [Notas](../../docs/RELEASE_2.1.8.md).

## Arquivo para enviar na console

[assessment-dashboard-web-update-2.1.8.zip](assessment-dashboard-web-update-2.1.8.zip)

Em **Administração → Atualização do dashboard**, envie o ZIP, aguarde **Pronto para atualizar**, confira as versões, informe sua senha e confirme **Atualizar e reiniciar**. Aguarde o retorno e confira a versão 2.1.8. Fechar a página não cancela uma aplicação iniciada. O executor verifica assinatura e integridade e cria backup antes da aplicação.

## Outros pacotes

- [Instalador de servidor novo](assessment-dashboard-installer-2.1.8.zip): Enterprise Linux 8+, JDK 17+, Python compatível. Não usar sobre instalação existente nem no upload.
- [Atualização por script](assessment-dashboard-update-2.1.8.zip): para origens compatíveis sem executor independente. Com executor instalado, use o pacote web.

Cada ZIP mantém seu arquivo .sha256; versões anteriores foram preservadas.

**Atualize também os agentes**, separadamente pelo painel, começando por uma máquina elegível, e solicite nova coleta para obter os campos novos. Sem rota compatível, use o instalador completo configurado do ambiente. A 2.1.7 é ponte interna, não exige atualização manual intermediária.
