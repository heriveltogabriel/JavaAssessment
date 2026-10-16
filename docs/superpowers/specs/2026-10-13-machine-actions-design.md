# Ações por máquina

Proposta aprovada pelo usuário em13/10/2026. Na lista Machines e nos cartões de máquinas, administradores terão menu⋮ com: Mover para outro ambiente; Remover do ambiente (Sem ambiente, preservando inventário/histórico); Excluir máquina do dashboard (cadastro/inventário removidos após confirmação do nome).

Mover reutiliza PUT/api/hosts/environment. Remover usa o mesmo endpoint com environmentId vazio, com confirmação clara. Excluir usa DELETE/api/hosts com hostId e confirmationName exato (hostname, fallback hostId). É permitido somente para identidade revogada ou ciclo uninstalled, sem desinstalação WAITING/UNINSTALLING/UNCONFIRMED. A validação é atômica no servidor e restrita a ADMIN/CSRF. Exclui inventário, tarefas, recibos, agenda e histórico de desinstalação desse host, preservando um registro mínimo da identidade revogada para impedir que credenciais antigas ou tentativas de registro da mesma instalação ressuscitem a máquina. Nenhuma ação executa scripts na VM ou desinstala automaticamente agentes. Outros hosts/ambientes/usuários são preservados.

O menu deve funcionar por mouse/teclado, fechar ao clicar fora/Escape/mudar de tela, posicionar-se sem corte por overflow da tabela emdesktop/mobile e mostrar a ação destrutiva separada emvermelho. Não aparece paraLeitura. Excluir exige nome exato, informa dados apagados e impede envios duplicados. A lista/contagens atualizam após sucesso; ao desvincular mantém o contexto do ambiente para facilitar sua exclusão. Emfalhas, mantém diálogo aberto com erro e permite tentar novamente.

A entrega será1.5.7, comum a dashboard/pacote de agente, sem sobrescrever releases anteriores. Publicação continuará pelo comando manual autorizado; não serão excluídas máquinas reais nesta implementação.
