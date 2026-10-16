# Java Assessment 2.0.53

## Limpeza do inventário

A tela **Java Assessment → Máquinas** oferece o filtro rápido **Sem comunicação**, com períodos de 5 minutos, 24 horas, 7 dias e 30 dias. A listagem inclui máquina, ambiente, IP, versão do agente, último contato e tempo sem comunicação. Busca, paginação e filtro por ambiente funcionam sobre todo o inventário. O contador vermelho dos ambientes também abre a lista.

Administradores podem excluir uma máquina ou um lote explicitamente selecionado da página, após uma prévia e confirmação digitada. A confirmação revalida o último contato e preserva máquinas reconectadas ou modificadas. Operações pendentes impedem a exclusão. Não há exclusão automática.

A exclusão remove cadastro e inventário dos totais, relatórios, exportações e atualizações, incluindo seus registros de solicitações. Os demais participantes de uma solicitação compartilhada são preservados. Nenhuma máquina é movida para “Sem ambiente”. A credencial antiga fica bloqueada por um registro técnico mínimo, e a ação é registrada na auditoria administrativa limitada. Não há arquivo histórico do inventário. Serviços e arquivos remotos, backups e exportações anteriores não são alterados.

Mantidos os ajustes visuais anteriores: cartões com agentes conectados/desconectados, coleta finalizada com observações exibida como **Concluído**, indicador verde, link **Observações** e título **Observações da coleta**.

Consulte [o guia de limpeza](MACHINE_CLEANUP.md).

## Validação e atualização

Testes locais com HTTP/SQLite e navegador real verificam permissões, CSRF, mais de 100 máquinas, períodos, paginação, confirmação, retorno por heartbeat, cadastros de mesmo nome, remoção de vínculos, isolamento das outras máquinas e reversão transacional em falha. Nenhuma máquina real é excluída na validação.

**Atualize também os agentes.** Publicar o dashboard não atualiza os agentes automaticamente. A limpeza usa os contatos já recebidos e funciona após atualizar o dashboard, sem exigir uma nova coleta. Em **Revisar agentes**, use a atualização pelo painel para instalações aceitas pelos perfis assinados; para as demais, use o instalador completo configurado para o ambiente com `--system-root`. Acompanhe em **Solicitações**.

O pacote adiciona a origem 2.0.51 ao catálogo revisado. A elegibilidade depende dos hashes dos componentes, não apenas da versão informada. Verificação de pacotes não equivale a teste universal em todas as versões de Java/Linux ou em 32 bits.

## Ajustes finais da interface incluídos no instalador

- Solicitações: resumo **1 coleta concluída com observações.**, mantendo o indicador verde e o acesso às observações.
- Relatórios: **Filtros de relatório** inicia recolhido ao entrar na tela ou trocar de modelo, preservando os critérios escolhidos.
- Guia de instalação do agente no menu superior: corrigida a referência `key` fora de escopo que impedia abrir o guia.

O instalador em `install/v2.0.53/` foi gerado com esses ajustes, mantendo a versão 2.0.53 e os componentes assinados do agente. Seu checksum acompanha o ZIP. O pacote original de preparação permanece preservado no arquivo local da entrega; o instalador publicado no Git é identificado pelo novo checksum.
