# Instalação de agentes com Ansible

Pedido: um playbook que baixe pelo wget e escolha a configuração do ambiente por grupo do inventário. Não altera o dashboard, a versão, nem executa instalações reais durante o desenvolvimento.

Um inventário separa produção, homologação e desenvolvimento. Cada grupo informa o ID real do ambiente e a sua credencial de instalação; o endereço HTTPS e a CA podem ser comuns. Credenciais ficam em arquivo protegido com Ansible Vault. O template replica o download configurado do dashboard, mantendo a validação TLS e os checksums. O download ocorre como usuário SSH e o instalador existente executa com sudo, sem interação, com `--system-root` e Java opcional.

O padrão é ignorar instalações com cadastro existente, sem reatribuir ambiente nem atualizar componentes. Resíduos ou instalações parciais impedem a instalação e requerem diagnóstico. Lotes de duas máquinas e interrupção em falha limitam impacto. O resultado verifica o agendamento local; recebimento do assessment deve ser confirmado separadamente no dashboard.

`raw` e `script` evitam depender de Python remoto, mas não garantem compatibilidade de SSH/TLS/Java em todo Linux legado. Não instalar Java, alterar firewall ou desabilitar certificados automaticamente. Arquivos temporários com credenciais usam permissões privadas e limpeza em `always`; interrupção abrupta do controlador/rede pode exigir limpeza manual. Testes usam fixtures locais e nenhuma máquina real.
