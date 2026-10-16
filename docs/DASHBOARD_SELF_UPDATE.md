# Atualização do dashboard pela administração

A partir da 2.1.0, administradores podem usar **Administração → Atualização do dashboard**. O dashboard mantém seu usuário sem privilégios; um serviço independente verifica e executa as solicitações locais.

1. Envie `assessment-dashboard-web-update-X.Y.Z.zip` (até 256 MiB). O ZIP é assinado; não é necessário conferir checksum manualmente na interface.
2. Aguarde a verificação da assinatura, arquivos, origem instalada, espaço e operações de agentes. O painel permanece disponível nessa etapa. Você pode cancelar a preparação.
3. Confira as versões e notas. Informe sua senha administrativa e confirme **Atualizar e reiniciar**.
4. O serviço bloqueia novas operações, pausa o dashboard, cria backup consistente e aplica os componentes. A página consulta o resultado novamente quando o serviço voltar. Se a sessão terminar, entre novamente e abra a mesma aba; o processo independe do navegador.

**Conclusão** exige resposta HTTPS válida com a versão esperada e verificação da instalação. O histórico e o log da operação ficam disponíveis para consulta; logs públicos não incluem saída bruta, tokens, senhas ou chaves.

## Falha e restauração

Falha após iniciar a aplicação dispara tentativa automática de retornar ao código anterior. Os pacotes aceitos declaram compatibilidade com preservação do banco atual. O serviço nunca restaura silenciosamente um banco antigo, evitando apagar dados recebidos após uma atualização.

A última atualização concluída oferece **Restaurar versão anterior**, com senha e confirmação. A restauração é recusada se a instalação foi alterada, os arquivos de backup não conferem ou houve novas operações de atualização/desinstalação de agentes. Os backups completos permanecem privados em `/opt/java-assessment-dashboard/.dashboard-updates/<operação>/`.

Se a recuperação não puder ser confirmada, o estado será **Recuperação precisa de diagnóstico** e a manutenção permanecerá ativa. Não é possível prometer acesso pelo navegador se o serviço não consegue iniciar; nesse caso, um administrador do servidor deve consultar `journalctl -u java-assessment-dashboard-updater` e os backups privados. Não remova o marcador de manutenção para contornar uma recuperação incompleta.

## Primeira adoção e compatibilidade

A 2.0.57 não tem esse serviço. Para chegar à 2.1.0, use o atualizador tradicional por script, que instala o executor independente. Depois disso, use os pacotes **web-update** pela interface. ZIP de instalação nova e pacotes antigos de atualização não são aceitos como upload.

O script tradicional recusa concorrer com uma operação ou executor independente ativo. O formato assinado v1 aceita somente versões posteriores, origens exatas e retorno compatível com o banco. Não há atualização automática pela internet. O próprio executor pode ser atualizado por um pacote compatível e só recarrega seu código após concluir a operação.

O bootstrap adiciona uma exceção restrita ao serviço existente com `ProtectSystem=strict`: somente `/var/lib/java-assessment-dashboard-updater/inbox` recebe permissão de escrita. Estado, journal e backups continuam controlados pelo executor root. O drop-in é aplicado antes de iniciar o dashboard; se o bootstrap falhar, o atualizador remove somente os arquivos gerenciados que acabou de criar.

**Atualizar o dashboard não atualiza os agentes.** Revise a elegibilidade no painel e solicite as atualizações separadamente, começando por uma máquina. Instalações não elegíveis usam o instalador completo configurado pelo ambiente. Para novos campos do inventário, execute uma nova coleta.

## Preservação da 2.0.57

Os instaladores originais em `install/v2.0.57` permanecem intactos. O commit está marcado como `backup/v2.0.57-before-dashboard-updater`; o backup local inclui os dois ZIPs, seus hashes e um Git bundle completo. A instalação do laboratório recebeu backup privado consistente antes do desenvolvimento. Esse backup não corresponde a uma cópia do ambiente do cliente.
