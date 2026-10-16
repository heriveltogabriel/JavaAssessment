# Ações por máquina

Administradores encontram o menu **⋮** nos cartões e na lista **Machines**.

- **Mover para outro ambiente** muda a associação da máquina e preserva o inventário e o histórico.
- **Remover do ambiente** deixa a máquina em **Unassigned**, preservando os dados. Depois de remover ou mover todas as máquinas, o ambiente pode ser excluído.
- **Excluir máquina do dashboard** remove o cadastro, inventário, tarefas e histórico de desinstalação da máquina selecionada. Exige digitar seu nome exato e só fica disponível depois de revogar o agente ou concluir sua desinstalação. Desinstalações pendentes, em execução ou sem confirmação bloqueiam a exclusão.

Revogar a credencial impede novas coletas, mas mantém a máquina vinculada ao ambiente. Essas ações não desinstalam nem executam comandos na VM. O perfil de leitura não pode realizá-las.

A exclusão é atômica e restrita ao administrador. O servidor conserva uma identificação mínima revogada, sem o token em texto, para impedir o uso da credencial antiga ou o recadastro da mesma instalação. Para cadastrar novamente a máquina, use uma nova instalação/identidade do agente.

## Publicação

A versão 1.5.7 inclui esse menu e a API de exclusão. Atualize o dashboard pelo `deployment/publish-dashboard.py` dessa versão. Não é necessário atualizar os agentes instalados para usar essas ações. A versão do pacote disponível para download acompanha a do dashboard.
