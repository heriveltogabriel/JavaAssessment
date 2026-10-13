# Instalar outra máquina Linux

## Pacote configurado por ambiente

No dashboard, crie ou abra um **Ambiente** e escolha **Baixar agente**. Defina a validade, o número de máquinas autorizadas e a coleta inicial. O ZIP já contém o endereço do dashboard e uma autorização temporária vinculada ao ambiente. Cada instalação recebe sua própria identidade; hostname não é chave única.

Copie o ZIP para o Linux e use o nome indicado no popup do ambiente. Exemplo para **Desenv**, com instalação como serviço root:

No computador de administração, troque o caminho da chave, usuário, host e diretório pelos valores reais. `-O` usa o protocolo SCP legado, útil quando o SSH de um servidor antigo não oferece SFTP:

```sh
scp -O -i /caminho/da/chave_privada assessment-desenv.zip usuario@HOST:/home/usuario/
```

Entre no servidor por SSH e vá ao diretório para onde o ZIP foi copiado. Execute a instalação em uma pasta nova:

```sh
umask 077
unzip assessment-desenv.zip -d java-assessment-installer
cd java-assessment-installer
chmod +x install.sh
sudo ./install.sh --system-root
```

O instalador lê `conf/bootstrap.properties` como dados, protege o arquivo com modo 600 e não solicita entrada no terminal. O cadastro efetivo no ambiente é decidido pelo servidor. O ZIP configurado contém uma autorização de cadastro: mantenha o arquivo e a pasta extraída protegidos e remova-os quando não forem mais necessários. Eles não contêm senha de administrador, chave SSH ou credencial operacional de outra máquina.

O modo `--system-root` instala em `/opt/java-assessment-collector` e descobre uma JVM existente compatível, pertencente ao root e protegida contra escrita por outros usuários. A validação ocorre antes de executar o candidato. Se nenhuma JVM atender aos requisitos, a instalação termina com um diagnóstico. Não instala JDK nem modifica o WebLogic ou as permissões das aplicações.

Para instalar como usuário atual, execute `./install.sh` sem `--system-root`: o destino padrão é `$HOME/java-assessment`, com as permissões de leitura desse usuário. Nesse modo, `--dir /caminho/absoluto` permite outro diretório dedicado, sem espaços. A opção `--java /caminho/bin/java` escolhe explicitamente uma JVM existente; no modo root, ela passa pelas mesmas verificações de confiança.

A autorização inicial não é copiada para a instalação operacional. Se o ZIP foi extraído diretamente no diretório operacional, o arquivo de bootstrap consumido é removido. A credencial individual recebida fica protegida em `conf/collector.properties`.

Para **atualizar** uma instalação `--system-root`, baixe um novo ZIP do ambiente, extraia em outra pasta privada e execute novamente `sudo ./install.sh --system-root`. O instalador preserva a identidade, a credencial individual e o ambiente da máquina. Um pacote de outro ambiente não altera a associação; faça a mudança no dashboard. Confira `install.log`, registro e heartbeat após a atualização. Guarde o backup protegido criado pelo instalador até validar o novo ciclo.

Para **trocar por um dashboard novo**, o comando de atualização acima não serve: ele manteria o endereço e a credencial do dashboard antigo. Baixe um ZIP do ambiente no dashboard **novo**, extraia em uma pasta privada nova e execute:

```sh
chmod +x install.sh
sudo ./install.sh --system-root --switch-dashboard
```

Essa opção explícita faz um cadastro novo e cria uma identidade individual no dashboard novo. O serviço anterior é parado, e a configuração, os logs, os resultados pendentes e a CA antiga ficam em `/opt/java-assessment-collector-backup-<data>-<pid>`, acessível apenas a root. Os resultados pendentes do servidor antigo não são enviados ao novo. Confira o cadastro, o primeiro contato e o log antes de remover o backup; se a instalação falhar, o log local indica a etapa e o backup permanece para recuperação. O ZIP novo precisa trazer autorização válida e a CA correspondente. Essa opção só funciona para uma instalação existente `--system-root`; outros modos exigem migração planejada por um administrador.

## Pacote genérico e opções explícitas

O pacote genérico continua sem autorização embutida. Para esse caso, execute:

```sh
./install.sh --server https://dashboard.example.com
```

O código é solicitado sem eco no terminal, fora dos argumentos de processo. Para automação, grave somente o código em arquivo modo 600 e passe `--code-file /caminho/arquivo`; remova-o após o cadastro.

Em pacotes configurados, opções explícitas têm prioridade. Alterar `--server` exige também fornecer `--code-file` com uma autorização para o novo destino: o instalador recusa enviar o código do pacote para outro dashboard. Não substitua manualmente `environmentId`; a associação é vinculada à autorização no servidor.

## Usuário de serviço

```sh
sudo ./install.sh --system
```

O pacote configurado também dispensa perguntas neste modo. O processo privilegiado lê arquivos fornecidos pelo usuário com a identidade original de quem executou `sudo`; o coletor continua sendo executado pelo usuário de serviço.

Cria o usuário sem login `java-assessment`, instala em `/opt/java-assessment-collector` e configura o crontab desse usuário. JVMs são executadas com esse usuário. Opcionalmente `--read-group grupo_de_leitura` adiciona um grupo já existente, com os acessos aprovados pelo administrador. O instalador preserva permissões da aplicação. Caminhos inacessíveis aparecem no diagnóstico e a coleta reporta cobertura parcial. ACLs e políticas SELinux específicas devem ser tratadas pelo administrador local; não são desativadas.

## Serviço executado como root

Quando a coleta precisa ler metadados protegidos de aplicações, o administrador pode escolher explicitamente o modo root. Ele preserva permissões, ACLs e grupos das aplicações. É um modo diferente de `--system`, que continua usando a conta dedicada.

Com um pacote configurado pelo ambiente:

```sh
chmod +x install.sh
sudo ./install.sh --system-root
```

O instalador procura uma JVM existente e compatível e verifica sua propriedade e proteção antes de executá-la como root. Os arquivos do runtime e seus diretórios ancestrais precisam pertencer ao root e estar protegidos contra escrita de grupo/outros. Candidatos que não atendem a esses critérios são ignorados durante a descoberta automática.

A descoberta segue esta ordem e para ao encontrar um candidato válido:

1. Caminho Java salvo da instalação anterior.
2. `JAVA_HOME` e `JDK_HOME`, quando presentes no ambiente recebido pelo instalador. O `sudo` pode remover essas variáveis.
3. `PATH` de sistema controlado pelo instalador, `/usr/bin/java` (incluindo o destino validado de alternatives) e diretórios usuais: `/usr/lib/jvm`, `/usr/java`, `/opt/java-lab` e `/opt/java`.
4. Caminhos apontados por `/proc/PID/exe`. O processo fornece somente uma localização; o Java encontrado precisa passar pelas mesmas verificações de confiança e compatibilidade.
5. Busca de metadados nas raízes existentes `/opt`, `/usr/local`, `/u01`, `/u02`, `/app`, `/apps`, `/oracle` e `/data`, com profundidade máxima 8, até 10.000 entradas e limite de 10 segundos. A busca não atravessa limites de filesystem, não segue diretórios simbólicos e não entra em árvores sem propriedade root ou graváveis por grupo/outros. Esses limites pertencem à busca final, não ao tempo total da instalação.

São considerados no máximo 24 candidatos. A compatibilidade de cada candidato aprovado nas verificações de confiança é testada com `JavaProbe`, com prazo de cinco segundos e até mais um segundo para encerramento. O caminho selecionado é salvo em `conf/java.path` para os ciclos seguintes; a busca recursiva é uma etapa da instalação, não uma varredura repetida a cada consulta. Caminhos Java com espaços ou `+` são aceitos; use aspas ao informá-los na linha de comando.

Para escolher uma JVM específica, acrescente a opção abaixo:

```sh
sudo ./install.sh --system-root --java /caminho/jdk-protegido/bin/java
```

Um caminho explícito inválido ou sem a proteção necessária causa falha; ele não é substituído silenciosamente por outro candidato. Se não houver Java elegível, o administrador precisa disponibilizar uma instalação existente em uma localização protegida, por exemplo uma cópia separada sob controle do root. O instalador não baixa Java nem muda permissões, ACLs ou grupos do runtime da aplicação. As verificações iniciais aparecem no terminal antes de existir o log da instalação.

O destino é `/opt/java-assessment-collector`. Em máquinas com systemd, o instalador cria um serviço de execução única e um timer com intervalo de 60 segundos. Em sistemas SysV, configura um serviço com loop de consulta de 60 segundos. A JVM é iniciada por ciclo; não permanece residente entre consultas. Só há varredura quando existe tarefa autorizada, com os mesmos limites de duração, arquivos e memória.

Para pacote genérico, acrescente `--server https://seu-dashboard --code-file /arquivo-protegido`. O arquivo deve conter somente o código, com modo 600 ou 400. O modo root não solicita o código interativamente. Pacotes configurados por ambiente dispensam esse arquivo. Não coloque códigos nos argumentos da linha de comando.

O diagnóstico e os logs deste modo exigem root:

```sh
sudo /opt/java-assessment-collector/agent-tool.sh diagnose /opt/java-assessment-collector/conf/collector.properties
sudo tail -f /opt/java-assessment-collector/logs/install.log
sudo tail -f /opt/java-assessment-collector/logs/collector.log
```

Verifique o agendamento conforme o sistema:

```sh
# systemd
sudo systemctl status java-assessment-collector.timer java-assessment-collector.service

# SysV
sudo service java-assessment-collector status
```

Em systemd, o timer fica ativo e o serviço de execução única pode aparecer inativo entre ciclos; isso é esperado.

O acesso root permite ler arquivos bloqueados por permissões Unix; não garante cobertura completa. Exclusões, limites de coleta, SELinux e arquivos indisponíveis continuam sendo registrados como limitações.

### Migrar uma instalação existente

Para manter a mesma máquina no inventário, use o diretório operacional atual como origem:

```sh
sudo ./install.sh --system-root \
  --migrate-from /home/opc/java-assessment
```

A migração preserva a identidade, a credencial individual, `installation-id` e opções como `hostname` e `snapshotRoot`. Os caminhos operacionais de spool, logs e CA são ajustados para o destino root. Resultados pendentes, recibos recentes e eventos de instalação ainda não enviados são copiados de forma limitada (até 256 arquivos, com até 2 MiB cada); eventos `.sent` não são reenviados. Os arquivos originais e os logs permanecem na origem como histórico. Não crie um novo cadastro para a mesma instalação.

A entrada exata do cron da origem é removida somente após a instalação e o diagnóstico bem-sucedidos. Seu backup fica em `/opt/java-assessment-collector/backups`. A pasta original não é removida automaticamente. Ao atualizar uma instalação já existente no destino fixo, a configuração, os logs e o spool anteriores são preservados em `/opt/java-assessment-collector-backup-<data>-<pid>`, protegido para root. Não execute os dois agendadores em paralelo para a mesma identidade.

## Evidências e diagnóstico

```sh
tail -f "$HOME/java-assessment/logs/install.log"
"$HOME/java-assessment/agent-tool.sh" diagnose "$HOME/java-assessment/conf/collector.properties"
tail -f "$HOME/java-assessment/logs/collector.log"
```

No modo `--system`, substitua a raiz por `/opt/java-assessment-collector` e execute o diagnóstico como `java-assessment`, usando `sudo -u java-assessment`. No modo `--system-root`, use `sudo` sem trocar o usuário.

O log registra horário UTC, etapa e resultado. `ASSESSMENT_REGISTRATION=CONFIRMED` comprova cadastro; `ASSESSMENT_HEARTBEAT=CONFIRMED` comprova contato operacional; `ASSESSMENT_ACCEPTED=...` comprova recebimento da coleta. `ASSESSMENT_NO_JOB` significa conectado sem tarefa pendente. Os eventos anteriores ao cadastro ficam locais até a conexão ser concluída; depois são enviados. Diagnóstico não dispara coleta.

Em falha, o instalador sai com código diferente de zero, mantém o log e preserva a identidade já registrada. Execute novamente o mesmo comando. Uma instalação já cadastrada não precisa de novo código: executar um pacote de outro ambiente preserva a identidade e o ambiente atuais. Para mover a máquina, use a associação administrativa no dashboard.

Se a autorização de um pacote novo expirou, foi revogada ou atingiu o limite de máquinas, baixe outro pacote do mesmo ambiente. A expiração não interrompe máquinas já cadastradas. O instalador não recorre a uma pergunta interativa quando um pacote configurado falha. Se o cadastro ocorreu no servidor mas a resposta se perdeu antes da gravação local, repita com o mesmo código antes de expirar; o ID salvo permite recuperar a mesma tentativa com o código original ainda válido. Se a credencial operacional foi perdida e o código original expirou, solicite recuperação administrativa; um código novo não recupera automaticamente a identidade anterior.

Nos modos usuário e `--system`, o cron consulta a fila a cada minuto. `--no-cron` é modo manual explícito nesses modos; execute `poll.sh` por um agendador existente. `--system-root` usa o serviço descrito acima. Sem tarefa não há varredura. Logs têm rotação por tamanho; eventos de instalações concluídas são mantidos por 14 dias. Resultados pendentes são preservados e reenviados com o mesmo ID.

## Rede, TLS e limites

É necessária saída HTTPS para o dashboard. O pacote inclui Bouncy Castle 1.86 e a CA pública configurada pelo dashboard; valida cadeia e nome/IP do servidor. Não inclui chave SSH, JDK ou material privado TLS. CA corporativa exige configurar `tlsCaFile` com a CA autorizada. Proxy autenticado e atualização automática do coletor não estão implementados.

Baseline: Java 6 ou posterior compatível com o bytecode; a validação desta entrega usa Java 6 no RHEL 5 e Java 21. Outras combinações precisam de validação. O coletor roda separado da aplicação, com heap de 64 MiB e prioridade reduzida. A leitura de arquivos detecta instalações e drivers; confirmar datasources ativos requer fonte de runtime/JMX. O `snapshotRoot` da primeira VM é integração opcional específica daquele laboratório.

## Remoção

Revogue a credencial no dashboard. No modo usuário, use `crontab -e` e remova somente a linha que contém o caminho desta instalação e o marcador `java-assessment-...`. No modo serviço, use `sudo crontab -u java-assessment -e` e remova somente a linha dessa instalação. Após confirmar que não há coleta ativa e salvar os dados necessários, remova o diretório dedicado. Remoção do usuário/grupos deve ser feita pelo administrador após verificar outras dependências.

Para `--system-root`, interrompa e desabilite o agendamento antes de remover a instalação:

```sh
# systemd
sudo systemctl disable --now java-assessment-collector.timer
sudo systemctl stop java-assessment-collector.service

# SysV
sudo service java-assessment-collector stop
sudo chkconfig java-assessment-collector off
```

Parar o serviço preserva os arquivos de configuração e os resultados. No modo SysV, a parada aguarda o ciclo ativo terminar. Não restaure o cron da instalação anterior enquanto o serviço root ainda estiver ativo. Depois de revogar a credencial individual no dashboard, confirmar que o processo terminou e salvar qualquer resultado pendente, remova apenas a definição correspondente ao sistema da máquina:

```sh
# systemd
sudo rm -f /etc/systemd/system/java-assessment-collector.timer /etc/systemd/system/java-assessment-collector.service
sudo systemctl daemon-reload

# SysV: use estes dois comandos somente nos hosts com SysV
sudo chkconfig --del java-assessment-collector
sudo rm -f /etc/init.d/java-assessment-collector
```

Por último, remova o diretório dedicado com `sudo rm -rf -- /opt/java-assessment-collector`. Essa remoção não exclui o histórico da máquina no dashboard.

O certificado HTTPS do dashboard tem validade definida na instalação. Sua renovação deve seguir o procedimento operacional do servidor. Não desative a verificação TLS para contornar um certificado expirado.
