# Instalação e configuração do Java Assessment

Este procedimento instala um **dashboard novo** em uma VM Linux e cadastra os coletores nas máquinas que serão avaliadas. O dashboard recebe inventário e resultados por HTTPS. Cada ambiente gera um ZIP de agente com o endereço do dashboard, a CA pública e uma autorização temporária de cadastro; não é necessário distribuir uma chave SSH do dashboard aos coletores.

O instalador tem verificações locais de integridade, dependências, geração de certificado, configuração do agente e inicialização do servidor em diretório isolado. Esses testes não substituem a execução em uma VM Enterprise Linux 8 ou superior. A atualização da máquina atual do dashboard requer autorização explícita antes de ser executada.

O pacote desta versão é `assessment-dashboard-installer-<VERSAO>.zip`; `assessment-dashboard-installer.zip` é uma cópia de compatibilidade. Os dois nomes podem ser usados nos comandos deste guia. Confira o SHA-256 entregue junto ao ZIP. As notas da versão estão em [BETA_RELEASE.md](BETA_RELEASE.md).

## Preparar a VM do dashboard

Use uma VM **Oracle Linux, RHEL ou distribuição Enterprise Linux compatível, versão 8 ou superior**, com `systemd` como processo inicial. Tenha:

- acesso SSH administrativo e permissão para usar `sudo`;
- `unzip` para extrair o pacote;
- um IP ou DNS que os navegadores e todas as máquinas coletoras consigam alcançar;
- acesso aos repositórios `dnf` se for preciso instalar Python 3, OpenSSL ou JDK 17.

O instalador procura um **JDK 17 ou posterior com `java` e `javac`**. Se não encontrar, tenta instalá-lo via `dnf`. O Java do dashboard é independente das versões Java presentes nas máquinas avaliadas.

O pacote já inclui SQLite JDBC e suas bibliotecas, com versões e hashes fixados. O banco é criado automaticamente em `/opt/java-assessment-dashboard/data/inventory.sqlite`, com acesso restrito à conta do serviço. A instalação reserva diretório privado em `data/native` para a biblioteca nativa do SQLite; o volume de `/opt` precisa permitir o carregamento dessa biblioteca. O serviço usa heap inicial e máximo de 1536 MiB (`-Xms1536m -Xmx1536m`) e limita a memória total a 2 GiB; dimensione a VM também para o sistema operacional e outros serviços.

Na OCI, configure uma regra de **ingresso TCP na porta 443** para a VNIC do dashboard, em um NSG ou na Security List da sub-rede. Use como origem os CIDRs autorizados dos administradores e das máquinas coletoras. Em rede interna ou outro provedor, faça a liberação equivalente no firewall da rede. O endereço pode ser DNS ou IP privado, desde que acessível aos agentes e navegadores; a opção `--public-url` também aceita esse endereço. Se escolher outra porta na URL, libere essa porta. O instalador configura `firewalld` local quando ele está ativo, mas não altera as regras da OCI. Consulte as instruções oficiais para [regras de NSG](https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/manage-nsg-security-rules.htm) ou [Security Lists](https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/update-securitylist.htm).

## Copiar o ZIP e instalar

No computador que contém `assessment-dashboard-installer.zip`, substitua os valores entre `<...>` pelos dados da nova VM:

```sh
scp -i "<caminho-da-chave-privada>" assessment-dashboard-installer.zip opc@<IP-DA-VM>:/home/opc/
ssh -i "<caminho-da-chave-privada>" opc@<IP-DA-VM>
```

Em imagens Oracle Linux e RHEL compatíveis da OCI, o usuário padrão costuma ser `opc`. Use o usuário definido para a sua imagem se ele for diferente. A [documentação de acesso SSH da OCI](https://docs.oracle.com/en-us/iaas/Content/Compute/Tasks/connect-to-linux-instance.htm) descreve a chave usada para entrar na VM; essa chave serve apenas para a administração da VM, não para o cadastro dos agentes.

Na VM, execute:

```sh
umask 077
unzip assessment-dashboard-installer.zip -d dashboard-installer
cd dashboard-installer
./install.sh
```

O script chama `sudo` se necessário. Ele sugere um endereço HTTPS e pede confirmação. **Confirme o IP ou DNS que os agentes realmente poderão acessar**, inclusive se houver NAT, VPN, proxy ou balanceador. Sem terminal interativo, informe o endereço diretamente:

```sh
./install.sh --public-url https://<IP-OU-DNS> \
  --admin-name "Nome do Administrador" --admin-email admin@exemplo.com
```

A instalação cria o usuário e o serviço `java-assessment-dashboard`, provisiona o primeiro administrador com nome/email e senha temporária aleatória de 24 horas, CA e certificado HTTPS, inicia o serviço e verifica `/health`. O endereço é exibido em uma linha `INSTALLED: https://...`. O log da instalação fica em `/var/log/java-assessment-dashboard-install.log`.

O instalador é destinado a uma VM **sem dashboard anterior**. Ele recusa substituir uma instalação ou credenciais já existentes. Se houver falha depois do início, examine o log e o estado do serviço antes de tentar novamente; não apague os dados instalados sem verificar a etapa em que parou.

## Abrir o painel e guardar as credenciais

Na VM, consulte o arquivo protegido que contém a URL, email e senha temporária:

```sh
sudo cat /root/java-assessment-dashboard-admin.txt
```

Abra a URL indicada, informe o email e a senha temporária e escolha sua senha pessoal de 8–128 caracteres. A troca é obrigatória no primeiro acesso; a credencial temporária expira após 24 horas. Os próximos acessos usam email e senha pessoal. Em **Administração → Usuários**, um administrador pode criar contas de **Administradores** ou **Leitura**. O certificado gerado é assinado por uma CA local; o navegador solicitará que essa CA seja confiável até que ela seja importada no repositório de confiança. A CA pública está em `/opt/java-assessment-dashboard/conf/agent-ca.pem`. A chave privada da CA fica em `/root/java-assessment-dashboard-ca.key`, acessível apenas a root. Guarde essa chave; **não** a copie para os agentes. O ZIP exportado para os agentes leva somente a CA pública.

Caso sua organização já tenha certificado próprio, instale usando os arquivos de certificado, chave e CA correspondentes:

```sh
./install.sh --public-url https://dashboard.exemplo.com \
  --tls-cert /caminho/fullchain.pem \
  --tls-key /caminho/privkey.pem \
  --agent-ca /caminho/ca.pem
```

Nesse modo, o certificado precisa corresponder ao DNS escolhido; a chave deve ser RSA PKCS#8 PEM sem senha. Essas opções são para uma instalação nova, não para trocar o certificado de um dashboard já instalado.

## Criar ambientes e baixar os agentes

1. No menu lateral do painel, abra **Environments** e selecione **Create environment**.
2. Informe o nome, por exemplo `Desenv`, `HM` ou `Produção`, e uma descrição opcional.
3. Em **Collection settings**, altere somente os limites ou a periodicidade que devem diferir dos padrões gerais. Salve o ambiente.
4. No cartão do ambiente, selecione **Download agent**. Revise a validade do pacote, o número máximo de máquinas e a opção de primeira coleta; confirme o download.

O nome do ZIP acompanha o ambiente, por exemplo `assessment-desenv-<VERSAO>.zip`. Por padrão, a autorização do pacote vale **24 horas**, aceita até **100 cadastros** e solicita uma primeira coleta. Esses valores podem ser ajustados antes do download. A validade restringe **novos cadastros**; máquinas já registradas recebem uma credencial individual e não deixam de funcionar quando o ZIP expira.

Trate cada ZIP de agente como artefato protegido enquanto sua autorização estiver válida. Um novo download gera outra autorização; distribua apenas o pacote do ambiente correto.

## Instalar um agente em uma máquina nova

A máquina avaliada precisa de Linux, `unzip`, acesso HTTPS de saída ao dashboard e um Java compatível já instalado. O agente procura um Java 6 ou posterior e não instala outro runtime. Não exige compilador no host monitorado. O bytecode permanece compatível com Java 6, mas esta entrega não foi executada em Java 6 real nem em Linux/JVM de 32 bits; homologue primeiro uma máquina representativa de cada plataforma do cliente. No modo root, ele aceita somente um Java cuja localização passe pelas verificações de propriedade e proteção contra escrita.

Copie o ZIP baixado para a máquina. Em seguida, no diretório que contém esse ZIP, use **o nome real do arquivo**; o exemplo abaixo considera o ambiente `Desenv`:

```sh
umask 077
unzip assessment-desenv-<VERSAO>.zip -d java-assessment-desenv-installer
cd java-assessment-desenv-installer
chmod +x install.sh
sudo ./install.sh --system-root
```

O agente usa heap inicial de 16 MiB e máximo de 96 MiB; a memória total do processo inclui estruturas nativas adicionais. O agente fica em `/opt/java-assessment-collector` e instala um timer `systemd` ou um serviço SysV, conforme o sistema operacional. Ele não modifica permissões dos diretórios das aplicações. A consulta por tarefas ocorre aproximadamente a cada 60 segundos; uma varredura acontece apenas quando solicitada pelo painel ou pela programação configurada.

Se a descoberta automática não selecionar um Java protegido, informe uma instalação existente:

```sh
sudo ./install.sh --system-root --java /caminho/do/jdk/bin/java
```

Esse caminho também precisa passar pelas verificações do instalador. Consulte o diagnóstico exibido no terminal antes de alterar a máquina.

## Cadastrar novamente um agente de outro dashboard

Este passo vale para uma máquina que **já executa o coletor como serviço root** e agora deve enviar dados para o dashboard novo. Baixe um ZIP do ambiente no painel novo e extraia-o em **uma pasta privada diferente** da instalação anterior. Execute:

```sh
chmod +x install.sh
sudo ./install.sh --system-root --switch-dashboard
```

Essa opção para o serviço anterior, faz um cadastro novo e preserva a identidade, a CA, os logs e os resultados pendentes do dashboard antigo em `/opt/java-assessment-collector-backup-<data>-<pid>`, protegido para root. Resultados pendentes do servidor antigo não são enviados ao novo. Mantenha o backup até confirmar o cadastro e a primeira coleta no painel novo.

**Não** use apenas `sudo ./install.sh --system-root` para trocar de dashboard: esse comando preserva a identidade e o endereço anteriores. A opção `--switch-dashboard` não se aplica a instalações antigas feitas no diretório do usuário.

Se a instalação anterior está no caminho padrão `~/java-assessment`, use a conta que executa o coletor para inspecionar `crontab -l`. Abra `crontab -e` e retire **somente** a linha que chama o `poll.sh` desse diretório. Depois que a coleta em andamento terminar, arquive o diretório antigo:

```sh
mv "$HOME/java-assessment" "$HOME/java-assessment-backup-$(date +%Y%m%dT%H%M%S)"
```

Instale então o ZIP novo como indicado na seção anterior. Esse procedimento conserva os arquivos antigos localmente e cria um cadastro novo no dashboard novo. Se o agente anterior usa outro diretório ou a conta dedicada `java-assessment`, siga a seção **Remoção** do `INSTALL.md` dentro do ZIP para desativar apenas o agendamento correspondente antes da nova instalação.

## Confirmar que está funcionando

No servidor do dashboard:

```sh
sudo systemctl status java-assessment-dashboard --no-pager
sudo journalctl -u java-assessment-dashboard -n 80 --no-pager
sudo tail -n 80 /var/log/java-assessment-dashboard-install.log
```

O instalador já verifica a resposta HTTPS de `/health` antes de declarar sucesso. Se a página não abrir de fora da VM, confira o endereço escolhido, a regra de ingresso OCI para TCP 443, o `firewalld` local e a rota entre o navegador e a VM.

Na máquina com agente root:

```sh
sudo tail -n 80 /opt/java-assessment-collector/logs/install.log
sudo /opt/java-assessment-collector/agent-tool.sh diagnose /opt/java-assessment-collector/conf/collector.properties
```

Em Linux com `systemd`, verifique o timer; o serviço de execução única pode estar inativo entre consultas:

```sh
sudo systemctl status java-assessment-collector.timer java-assessment-collector.service --no-pager
```

Em Linux antigo com SysV, use `sudo /sbin/service java-assessment-collector status`. No painel, confirme a máquina no ambiente escolhido e verifique **registro**, **último contato** e **primeira avaliação**. Um registro pode aparecer antes da primeira coleta; a coleta inicial depende da opção selecionada ao gerar o pacote.

Se a máquina não aparecer, leia primeiro `install.log`. Confira a validade e o limite de cadastros do ZIP, a saída HTTPS até o dashboard, a CA incluída no pacote e a JVM selecionada. Se a instalação do dashboard não concluir, use o log da instalação e `journalctl`; não substitua manualmente sua URL ou os arquivos TLS dentro dos agentes.

## Caminhos importantes

| Item | Local |
| --- | --- |
| Arquivos do dashboard | `/opt/java-assessment-dashboard` |
| Serviço do dashboard | `java-assessment-dashboard.service` |
| Log da instalação do dashboard | `/var/log/java-assessment-dashboard-install.log` |
| Acesso temporário do primeiro administrador | `/root/java-assessment-dashboard-admin.txt` |
| CA pública para navegadores e agentes | `/opt/java-assessment-dashboard/conf/agent-ca.pem` |
| Chave privada da CA gerada | `/root/java-assessment-dashboard-ca.key` |
| Agente instalado como serviço root | `/opt/java-assessment-collector` |
| Log de instalação do agente root | `/opt/java-assessment-collector/logs/install.log` |

O dashboard não abre conexões SSH para coletar dados. SSH é usado pelo administrador apenas para copiar o ZIP e administrar as VMs; o agente se registra, consulta tarefas e envia resultados ao painel por HTTPS.

## Contas e atualização do login

Uma instalação nova já possui o primeiro administrador provisionado. O arquivo privado de configuração não contém senha temporária nem hash da antiga senha compartilhada; o SQLite guarda hashes Argon2id. Remova o registro da senha temporária em `/root/java-assessment-dashboard-admin.txt` após definir sua senha pessoal. O nome/email continuam visíveis apenas nesse registro root e na conta administrativa.

Ao atualizar um dashboard anterior, a tela de configuração exige a senha administrativa atual e solicita nome, email e uma nova senha pessoal. Só uma conta pode concluir essa ativação. O inventário, os agentes e suas credenciais são preservados. Depois da ativação, o publicador recusa restaurar software antigo com autenticação por senha compartilhada; ele conserva os arquivos novos e o banco e registra o bloqueio no relatório privado. Use sempre o publicador da versão atual. Não execute instaladores ou publicadores históricos para voltar de versão.
