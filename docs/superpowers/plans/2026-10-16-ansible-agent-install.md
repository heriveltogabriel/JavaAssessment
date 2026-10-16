# Ansible agent installation implementation plan

**Goal:** entregar playbook reutilizável para instalação nova por ambiente usando wget.
**Architecture:** variáveis por grupo, template de download privado no controlador, script sem sudo no destino e instalador com sudo. Instalações existentes ignoradas; parciais bloqueadas.
**Tech stack:** Ansible Core, POSIX sh, wget, HTTPS, instalador assinado existente.

- [x] Criar `install/ansible/install-agents.yml`, inventário de exemplo e Vault de exemplo. Validar URL HTTPS, ID, token, Java opcional e CA antes de download. Desabilitar facts e check mode mutante.
- [x] Criar `templates/download-agent.sh.j2` com wget via configuração privada, recusa de redirecionamento, tentativas limitadas, checksums e marcador de staging. Remover staging em erro e credencial após download.
- [x] Separar leitura do estado, download e instalação. Ignorar cadastro completo, recusar resíduos, instalar com stdin fechado e verificar timer/systemv.
- [x] Documentar configuração, mapeamento dos grupos, Vault, comando por grupo, primeira máquina e limites. Não publicar credenciais nem executar em VMs.
- [x] Validar sintaxe Ansible e shell; exercitar fixtures de download, checksum inválido, retry, falha e ausência de credenciais na saída. Verificar plano local sem conexão de produção.

Validação concluída: Ansible Core 2.19.14, syntax-check, list-hosts e 10 cenários locais com downloader/instalador simulados. Nenhuma VM, serviço real ou conexão de produção foi usada. A escolha de ignorar agentes existentes foi confirmada pelo usuário.
