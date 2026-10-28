# Java Assessment 2.1.28 — entrega consolidada

Redução da contenção no SQLite durante campanhas e ingestão de coletas, acompanhamento da tela sem consultas sobrepostas, concorrência padrão 10/teto 25 e coleta agendada espalhada pela janela. [Notas](../../docs/RELEASE_2.1.28.md) · [Guia de campanhas](../../docs/AGENT_UPDATE_CAMPAIGNS.md) · [Provas e limites](../../docs/validation/console-release-2.1.28.md).

## Atualizar sua console

1. Baixe [assessment-dashboard-web-update-2.1.28.zip](assessment-dashboard-web-update-2.1.28.zip) e seu [SHA-256](assessment-dashboard-web-update-2.1.28.zip.sha256).
2. Abra **Administração → Atualização do dashboard**, envie o ZIP web-update e aguarde a validação da origem e assinatura.
3. Confira o destino **2.1.28** e confirme **Atualizar e reiniciar** com sua senha.
4. Após o retorno, confira versão, ambientes, inventário e contato dos agentes.

Atualização direta e rollback da console foram verificados localmente a partir de **2.1.20, 2.1.22, 2.1.24 e 2.1.26**, com classes publicadas, TLS e SQLite. Não é necessário instalar as versões intermediárias. O teste usa processos filhos como adaptador de serviço; upload e reinício no ambiente real permanecem a cargo do operador.

## Agentes e campanhas

**Atenção: atualize também os agentes. Atualizar o dashboard não atualiza os agentes automaticamente.** A melhora de desempenho já vale com os agentes atuais **2.1.26**. Atualizar esses agentes é **opcional** para obter a melhora; o protocolo e o comportamento funcional do coletor não mudaram, apenas seu número no pacote consolidado.

Atualizadores originais **2.1.20/2.1.22/2.1.24/2.1.26 → 2.1.28** passaram em fixtures com bundles assinados. Agentes com V3 íntegro e elegibilidade confirmada podem atualizar separadamente em **Revisar agentes**, sem instalar manualmente a ponte interna **2.1.27**. Sem capacidade/rota compatível, use o instalador do agente configurado para o ambiente com `--system-root`, preservando cadastro, configuração e CA. O instalador do dashboard não instala agentes.

Antes de ampliar uma campanha, encerre ou revise a fila anterior e faça um piloto com poucas máquinas. O padrão agora é grupo de 10 e até 10 instalações simultâneas; o limite global permite até 25. Um valor explicitamente salvo pelo administrador é respeitado. A campanha continua com o navegador fechado; falhas sem confirmação podem pausá-la e exigir intervenção. Não há garantia de concluir toda a frota durante a noite.

O servidor usa `sqliteSynchronous=NORMAL` por padrão. Configure `FULL` se precisar preservar a durabilidade anterior dos commits diante de falha de energia ou do sistema operacional. Agentes anteriores à 2.1.26 não foram exercitados sob respostas 503 nesta entrega; a prova de atualização não equivale a uma prova de carga dessas versões.

## Outros pacotes

- [Instalador completo do dashboard](assessment-dashboard-installer-2.1.28.zip) · [SHA-256](assessment-dashboard-installer-2.1.28.zip.sha256): para servidor novo, Enterprise Linux 8+ e JDK 17+. Não enviar este ZIP à console existente.
- [Atualização por script](assessment-dashboard-update-2.1.28.zip) · [SHA-256](assessment-dashboard-update-2.1.28.zip.sha256): alternativa por terminal, conforme o README incluído. Para atualização pela tela, use **web-update**.

Pacotes gerados com `--lab-canary` em clone limpo da `main` (`6f910ad`), sem deploy remoto ou acesso a hosts do cliente. Os instaladores anteriores foram preservados. Publicação no GitHub e integração na `main` autorizadas pelo usuário após a validação local.

## Integridade

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| assessment-dashboard-installer-2.1.28.zip | 93002581 | `30ae53675762380beb15b0982dd7a6671e43513ae66034d499e18d25837a3b15` |
| assessment-dashboard-update-2.1.28.zip | 93342284 | `f32deb59115b74e0207c267cc0d59cf8ddd1a71b906a87a762c9eafdd3fa8aff` |
| assessment-dashboard-web-update-2.1.28.zip | 93296804 | `912aaf53912d1e7d2e46cc46c107ca7c2b796ce98c87d5e8897be6c38a273e1f` |
