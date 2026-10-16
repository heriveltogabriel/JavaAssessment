# Instalar agentes por ambiente com Ansible

Um playbook atende vários grupos. Cada grupo define o ID do ambiente e a credencial de **Instalar via terminal**; o wget baixa o pacote configurado daquele ambiente. Não modifica o dashboard nem a versão do produto.

[Baixar todos os arquivos em ZIP](java-assessment-ansible.zip) · [SHA-256](java-assessment-ansible.zip.sha256)

## 1. Preparar o controlador

Use Ansible Core em uma máquina de controle com Python compatível e acesso SSH às máquinas. As tarefas remotas usam `raw` e `script`, sem exigir Python nos destinos. A sintaxe foi validada com Ansible Core 2.19; execução em todas as distribuições legadas não é garantida.

Nas máquinas Linux, providencie Java compatível com o agente, wget com TLS compatível com o dashboard, unzip com suporte a `-Z1` (zipinfo), sha256sum e os pré-requisitos normais do instalador (incluindo cron/crontab). O playbook não instala Java nem pacotes do sistema. O usuário SSH precisa de sudo para instalar; root direto também é suportado. Configure as chaves conhecidas do SSH normalmente.

Nesta pasta:

```sh
umask 077
mkdir -p private
cp inventory.example.yml inventory.yml
cp vault.example.yml private/vault.yml
```

## 2. Configurar os ambientes

No dashboard, em **Ambientes → Baixar agente → Instalar via terminal**, ative o acesso e abra o comando wget de cada ambiente. Copie para suas variáveis locais:

| Variável | Onde obter |
| --- | --- |
| `assessment_dashboard_url` | Endereço HTTPS do dashboard, sem `/api/...` |
| `assessment_environment_id` | Valor `env-...` depois de `environmentId=` no comando daquele ambiente |
| `assessment_install_token` | Credencial `eia1_...` da linha `Authorization: Bearer`, guardada no Vault |
| `assessment_ca_file` | Arquivo PEM no controlador contendo a CA presente no bloco `BEGIN CERTIFICATE` / `END CERTIFICATE` do comando |
| `assessment_java_path` | Opcional, caminho absoluto do executável Java no destino; se omitido, o instalador procura uma JVM existente |

Salve **somente o bloco PEM**, incluindo as linhas BEGIN/END, em `private/dashboard-ca.pem`. Não copie o delimitador do heredoc. Se os grupos usarem dashboards distintos, defina URL e arquivo de CA por grupo.

Edite `inventory.yml`: substitua os IPs ilustrativos, IDs dos ambientes, URL e usuário SSH. Exemplo de grupo:

```yaml
producao:
  vars:
    assessment_environment_id: env-ID-REAL-DO-AMBIENTE
    assessment_install_token: "{{ vault_install_tokens.producao }}"
  hosts:
    app-prod-01:
      ansible_host: 10.0.1.11
    app-prod-02:
      ansible_host: 10.0.1.12
```

Preencha os tokens correspondentes em `private/vault.yml` e criptografe:

```sh
ansible-vault encrypt private/vault.yml
# Para editar depois:
ansible-vault edit private/vault.yml
```

Não use token de agente nem código temporário de cadastro. A credencial de instalação é revogável e permite novos cadastros: mantenha o Vault privado. Não passe tokens por `-e` na linha de comando e não envie inventário real, scripts gerados ou senhas ao Git. A pasta `private/` e `inventory.yml` estão ignorados no Git.

## 3. Validar e executar

Verifique a sintaxe e o grupo selecionado:

```sh
ansible-playbook -i inventory.yml install-agents.yml --syntax-check
ansible-playbook -i inventory.yml install-agents.yml --list-hosts --limit producao
```

Comece por uma máquina:

```sh
ansible-playbook -i inventory.yml install-agents.yml \
  -e @private/vault.yml --ask-vault-pass \
  --limit app-prod-01 --ask-become-pass
```

Depois execute por ambiente, ou em todos os grupos:

```sh
# Produção
ansible-playbook -i inventory.yml install-agents.yml \
  -e @private/vault.yml --ask-vault-pass \
  --limit producao --ask-become-pass

# Todos os grupos de assessment_agents
ansible-playbook -i inventory.yml install-agents.yml \
  -e @private/vault.yml --ask-vault-pass --ask-become-pass
```

Se sudo não pedir senha, retire `--ask-become-pass`. Para escolher uma chave SSH, acrescente `--private-key /caminho/da/chave`. Por padrão, os lotes são de **duas máquinas**; use `-e assessment_batch_size=1` para executar uma por vez. Cada host deve pertencer a somente um grupo de ambiente. Não execute vários controladores ao mesmo tempo para as mesmas máquinas.

O playbook não usa `--check`: este modo é recusado para evitar uma falsa validação da instalação. `--syntax-check` não acessa as VMs nem valida credenciais ou conectividade.

## Comportamento

1. Consulta o estado local sem ler tokens. **Ignora agentes já cadastrados**, inclusive se estiverem em outro ambiente. Isso não certifica a saúde da instalação existente.
2. Recusa resíduos/instalações parciais. Não apaga o agente nem força migração.
3. Gera um script temporário privado no controlador. Baixa com wget **sem sudo**, validando TLS, recusando redirecionamentos e verificando SHA-256 do pacote.
4. Executa o instalador do pacote com sudo e `--system-root`, sem prompts. Ele cria os serviços; não é necessário configurá-los separadamente.
5. Verifica identidade local e timer do coletor no systemd (ou supervisor no SysV). A coleta e seu recebimento continuam sendo confirmados separadamente em **Solicitações** no dashboard.
6. Remove os temporários desta execução. Preserva instalação e logs para diagnóstico se o instalador falhar. Uma falha interrompe novos lotes; o outro host já em execução no lote pode concluir.

Um novo download usa a versão disponível no dashboard naquele momento. Evite atualizar o dashboard durante o lote se todas as máquinas precisarem receber a mesma versão. Revogar/renovar o acesso exige atualizar o token no Vault; não muda as credenciais dos agentes já cadastrados. Para atualizar agentes existentes, continue usando **Revisar agentes** no dashboard.

O segredo não é enviado como argumento do wget e as tarefas sensíveis têm `no_log`. Não execute com rastreamento de shell nem habilite logs de depuração internos do Ansible. O controlador gera arquivos de segredo em claro em diretório temporário privado durante a execução. Se houver encerramento abrupto ou perda da conexão, revise os temporários `java-assessment-ansible-*` no controlador e `/tmp/java-assessment-ansible.*` nos destinos antes de remover apenas os da execução interrompida.

Para falhas, a mensagem mostra a etapa. Confira pré-requisitos, acesso HTTPS e a credencial do ambiente; para instalação, leia `/opt/java-assessment-collector/logs/install.log` localmente. A presença de registro no dashboard, isoladamente, não confirma que os serviços terminaram de instalar.

## Validação realizada

Ansible Core 2.19.14: sintaxe e seleção por grupo aprovadas. Dez cenários locais passaram: instalação nos dois ambientes, preservação do agente existente, bloqueio de instalação parcial, retry, falha de rede, checksum inválido, caminho inseguro no ZIP, falha do instalador e recusa de check mode. Os testes usam wget e instalador simulados; verificam também ausência do token na saída e limpeza dos temporários. Nenhuma VM real foi alterada. Valide primeiro uma máquina de cada distribuição no ambiente do cliente.

## Documentação Ansible

- [script: execução de script local no destino, sem Python remoto](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/script_module.html)
- [raw: execução sem o subsistema de módulos Python](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/raw_module.html)
- [Ansible Vault](https://docs.ansible.com/projects/ansible/latest/vault_guide/index.html)
