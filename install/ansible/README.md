# Agentes com Ansible

**Atualização de agentes existentes:** use o [guia de atualização em lotes](UPDATE.md) e `update-agents.yml`. Ele usa a API oficial, preserva o cadastro e não exige SSH. O restante desta página trata de instalação e recuperação.

Um playbook atende vários grupos e pode ser executado novamente após uma falha. O modo padrão `auto` preserva agentes íntegros e recupera instalações reconhecidas sem trocar o cadastro. Cada grupo define o ID do ambiente e a credencial de **Instalar via terminal**; o wget baixa o pacote configurado daquele ambiente. Não modifica o dashboard nem a versão do produto.

[Baixar todos os arquivos em ZIP](java-assessment-ansible-20261028.zip) · [SHA-256](java-assessment-ansible-20261028.zip.sha256)

Ao atualizar um playbook antigo, extraia **todos os arquivos do novo ZIP**, inclusive `files/manage-agent.sh` e `templates/`. Preserve seu `inventory.yml` e a pasta `private/`; eles não fazem parte do pacote público.

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
| `assessment_install_mode` | Opcional: `auto` (padrão) ou `reinstall` para reinstalação limpa explicitamente selecionada |
| `assessment_java_path` | Opcional, executável específico no destino. Se informado e rejeitado, a instalação para; não troca silenciosamente de JVM. Para descoberta automática, omita. |
| `assessment_java_min_major` | Opcional, versão principal mínima entre 6 e 99; padrão `6`. Use `8` para aceitar Java 8, 11, 17, 21 etc., independentemente do update instalado. |
| `assessment_java_exclude_ibm` | Opcional, `true` para excluir JVMs cujo `java.vendor` ou `java.vm.vendor` identifique IBM; padrão `false`. |

### Seleção automática de Java por grupo

Para novas instalações ou reparos clássicos do grupo afetado, sem fixar diretório ou update:

```yaml
assessment_java_min_major: 8
assessment_java_exclude_ibm: true
```

Omita `assessment_java_path`. A seleção inspeciona os executáveis disponíveis em cada host; o nome de um diretório não é prova da versão ou fornecedor. Java 8 de outro fornecedor pode ser escolhido em um host e Java 17 em outro. Não há exclusão universal de IBM: esses filtros são uma política administrativa opcional para o grupo e não uma declaração de incompatibilidade de todas as JVMs IBM.

O helper valida a propriedade e a proteção dos arquivos antes de executar cada candidato como root. Com filtros ativos, usa `-XshowSettings:properties -version` para obter a versão principal, `java.vendor` e `java.vm.vendor`, e depois executa o teste de compatibilidade do coletor. Candidatos sem metadados completos, que travem ou não executem o teste são descartados. A política exige o comando GNU `timeout`; se indisponível, falha antes de alterar o agente, com `JAVA_POLICY_TIMEOUT_REQUIRED`.

São examinados caminhos usuais (`/usr/java`, `/usr/lib/jvm`, `/opt/java`), executáveis de processos Java e uma busca limitada nos diretórios de aplicações. Limites: até 24 candidatos distintos, 5 segundos por execução Java, profundidade 8, 10 mil entradas e até 10 segundos para a busca adicional. Não é uma varredura irrestrita do servidor. Se nenhum candidato verificado atender, retorna `JAVA_POLICY_NO_MATCH`; não relaxa o mínimo nem retorna ao IBM. O log privado da operação registra caminhos, versão e motivos de descarte disponíveis.

A escolha é feita antes de parar serviços ou alterar o cadastro. Agentes saudáveis em modo `auto` permanecem preservados, inclusive os legados. A recuperação de serviços V3 valida a JVM já persistida; não valida uma JVM nova para depois iniciar outra. Caso essa JVM viole a política, a operação para para revisão. A instalação continua fazendo a validação HTTPS de costume; escolher Java 8+ não comprova que o problema TLS foi resolvido.

Este ajuste é apenas do Ansible: não muda JARs, Java global, `alternatives`, aplicações ou agentes saudáveis. `RESIDUE_REQUIRES_DIAGNOSIS` continua bloqueando uma instalação incompleta com identidade existente. Corrigir a escolha de Java não autoriza apagar `installation-id` nem força um novo cadastro.

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

## Comportamento automático

Execute o mesmo comando novamente; não é necessário remover diretórios manualmente.

| Estado encontrado | Ação com `assessment_install_mode: auto` |
| --- | --- |
| Agente íntegro, timer/supervisor e executores ativos | Preserva e ignora a instalação |
| Instalação clássica incompleta, com identidade válida | Faz backup e retoma pelo instalador oficial, preservando identidade, configuração de comunicação e CA |
| Instalação V3 com serviços parados/desabilitados | Faz backup, confere as definições e reativa os serviços; mantém os slots assinados e a versão instalada |
| Resíduos anteriores ao cadastro, sem identidade, helpers, processos ou agendamentos | Guarda a árvore reconhecida fora do caminho de instalação e instala novamente |
| Atualização/desinstalação pendente, lock ocupado, identidade incompleta, serviço alterado ou resíduo desconhecido | Interrompe com diagnóstico; não força a exclusão |

O coletor é executado em ciclos: não ter uma JVM continuamente ativa é normal. O estado local considera o timer do coletor e os executores auxiliares, não apenas a presença de arquivos. Ele não comprova o recebimento de dados pelo dashboard; confira **Solicitações** e o último contato.

A recuperação V3 cobre serviços parados/desabilitados com suas definições presentes e verificáveis. Arquivos assinados danificados, serviços ausentes ou locks de uma operação interrompida exigem diagnóstico; o playbook não fabrica recibos nem apaga transações para destravar a instalação.

Antes de qualquer reinstalação/remoção, a automação baixa o pacote com wget como usuário SSH, verifica TLS e SHA-256, copia para uma área privada de root e verifica os pré-requisitos e uma JVM compatível. Só então prepara o backup e executa as operações oficiais com sudo. Os serviços são configurados pelo instalador.

Agentes preservados ou recuperados em `auto` continuam com o cadastro anterior, inclusive se o grupo Ansible indicar outro ambiente/dashboard. Use o dashboard para conferir o ambiente e atualizar versões. A recuperação não serve para trocar silenciosamente o vínculo da máquina.

## Reinstalação limpa de uma máquina

Para substituir uma instalação reconhecida, defina **somente no host desejado**:

```yaml
app-prod-01:
  ansible_host: 10.0.1.11
  assessment_install_mode: reinstall
```

Execute limitando esse host:

```sh
ansible-playbook -i inventory.yml install-agents.yml \
  -e @private/vault.yml --ask-vault-pass \
  --limit app-prod-01 --ask-become-pass
```

A opção `reinstall` faz backup, para os serviços verificados, chama o desinstalador oficial e instala o novo pacote. Também respeita as verificações de propriedade, locks e operações pendentes: **não é uma opção de exclusão forçada**. Se o desinstalador recusar a instalação antiga, o lote para e os arquivos restantes ficam para diagnóstico.

A reinstalação limpa cria um novo cadastro. O registro e a credencial antigos no dashboard não são removidos automaticamente; revogue/exclua o registro antigo pela interface. O Java do sistema e as aplicações da máquina são preservados. **Depois do sucesso, remova `assessment_install_mode: reinstall` do host**, para que a próxima execução volte ao modo automático.

## Backup e diagnóstico

O resultado informa o caminho privado `/opt/java-assessment-ansible.XXXXXX/backup` e, nas falhas após o preparo, `/opt/java-assessment-ansible.XXXXXX/operation.log`. A área tem acesso exclusivo de root. O backup pode conter tokens, configuração e coletas: não envie seu conteúdo ao Git, a tickets ou a logs compartilhados. Ele fica no próprio host para diagnóstico/restauração assistida; não há restauração automática depois de uma nova tentativa de cadastro.

O download temporário e o script com credencial no controlador são removidos ao terminar. O backup, o log e a cópia privada usada para a operação ficam preservados; o bootstrap de cadastro dessa cópia é removido após sucesso. Reserve espaço para a cópia do pacote e do agente existente. Após validar a recuperação, o administrador pode aplicar sua política de retenção aos backups identificados.

Uma falha interrompe novos lotes; o outro host já em execução pode concluir. A mensagem informa a etapa e um código de diagnóstico, por exemplo `PENDING_OPERATION`, `PREFLIGHT_JAVA`, `SERVICE_DEFINITION_CHANGED` ou `UNINSTALL_REFUSED`. Não compartilhe o log privado sem revisar seu conteúdo.

Um novo download usa a versão disponível no dashboard naquele momento. Evite atualizar o dashboard durante o lote se todas as máquinas precisarem receber a mesma versão. Revogar/renovar o acesso exige atualizar o token no Vault; não muda as credenciais dos agentes já cadastrados. Para atualizar agentes existentes, continue usando **Revisar agentes** no dashboard.

O segredo não é enviado como argumento do wget e as tarefas sensíveis têm `no_log`. Não execute com rastreamento de shell nem habilite logs de depuração internos do Ansible. O controlador gera arquivos de segredo em claro em diretório temporário privado durante a execução. Se houver encerramento abrupto ou perda da conexão, revise os temporários `java-assessment-ansible-*` no controlador e `/tmp/java-assessment-ansible.*` nos destinos antes de remover apenas os da execução interrompida.

Para falhas, a mensagem mostra a etapa. Confira pré-requisitos, acesso HTTPS e a credencial do ambiente; para instalação, leia `/opt/java-assessment-collector/logs/install.log` localmente. A presença de registro no dashboard, isoladamente, não confirma que os serviços terminaram de instalar.

## Validação realizada

**Validação desta revisão (2026-10-28): 43 testes locais aprovados.** A suíte do helper passou nos 39 cenários (27 existentes e 12 da seleção Java). A integração passou nos quatro cenários do playbook: seleção sem caminho fixo, ausência de candidato elegível, preservação de agente saudável e instalação padrão. Sintaxe e integridade ZIP/SHA-256 são verificadas na preparação do pacote. A execução em JVM IBM do cliente ainda depende de validação remota.

Os testes locais executam o playbook e o helper de recuperação com as fronteiras Linux (systemd, proprietário dos arquivos, wget, JVM e instalador) simuladas. Cobrem instalação, preservação, recuperação clássica/V3, reinstalação explícita, backups, validação de identidade, checksum, falha de rede/Java/instalador, links, montagens, serviços alterados, operações pendentes, proteção de segredos e limpeza dos temporários.

Esses testes não substituem a validação dos serviços em uma VM Linux real. Nenhuma máquina do cliente é alterada durante os testes; comece com `--limit` em uma máquina de cada distribuição. O pacote Ansible é independente da release do dashboard: esta revisão não altera a versão do dashboard nem dos agentes e baixa o agente disponibilizado pelo dashboard usado no inventário. O ZIP datado identifica apenas a revisão da automação; os pacotes anteriores permanecem preservados.

## Documentação Ansible

- [script: execução de script local no destino, sem Python remoto](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/script_module.html)
- [raw: execução sem o subsistema de módulos Python](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/raw_module.html)
- [Ansible Vault](https://docs.ansible.com/projects/ansible/latest/vault_guide/index.html)
