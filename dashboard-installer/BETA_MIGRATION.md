# Migração e backup do Java Assessment

Execute estes procedimentos somente depois da autorização para a mudança. A máquina atual do dashboard não é atualizada pelo pacote.

## Migrar de JSON para SQLite

O dashboard exige Enterprise Linux 8+ e JDK 17+. Um dashboard em RHEL 5 precisa de uma nova máquina compatível. Os coletores continuam compatíveis com seus ambientes existentes; o requisito Java 17 aplica-se ao servidor do dashboard.

1. Pare o dashboard antigo pelo mecanismo usado nessa instalação. Guarde uma cópia completa e privada de seus arquivos de programa, `conf` e `data`, preservando proprietários e modos. Mantenha essa cópia e a instalação antiga para retorno. Não copie um JSON enquanto o dashboard antigo o estiver modificando.
2. A partir desse mesmo backup consistente, prepare `state.json` e `auth-registry.json` em uma pasta privada na nova VM. Ambos devem ter modo 600 ou 400. Preserve a pasta original; o instalador apenas lê esses dois arquivos. Arquivos adicionais, certificados e configurações não são importados por `--legacy-data`.
3. Instale o dashboard na nova VM informando o diretório. Exemplo:

```sh
sudo ./install.sh --public-url https://novo-dashboard.exemplo.com \
  --legacy-data /root/assessment-migration
```

O instalador verifica se todas as máquinas do inventário têm suas identidades correspondentes no registro, copia os dois JSON para `data` com modo 600, atribui a pasta à conta do serviço e só então inicia o servidor. A importação ocorre em uma transação SQLite; JSON inválido ou falha na importação impede concluir a inicialização. Os originais e uma cópia em `data/migration-backup-*` são preservados. A marca `migration.v1` impede nova importação em reinicializações.

4. Confira o serviço, HTTPS e o banco antes de direcionar os agentes à nova máquina:

```sh
sudo systemctl status java-assessment-dashboard --no-pager
sudo /opt/java-assessment-dashboard/database.sh check \
  /opt/java-assessment-dashboard/data/inventory.sqlite
```

O comando deve mostrar `QUICK_CHECK_OK; MIGRATION_V1_COMPLETE` e contagens de máquinas, identidades, ambientes, itens e tarefas. Compare com o backup original e confira as telas, filtros e uma coleta representativa. O comando não imprime tokens nem inventário individual. A nova instalação gera credencial administrativa e TLS próprios por padrão.

5. Migrar o inventário não altera a URL nem a CA configuradas nos agentes. Se qualquer uma mudar, baixe o ZIP de cada ambiente no novo dashboard e siga `INSTALL.md` para cadastrar os coletores novamente; instalações `--system-root` usam `--switch-dashboard` conforme o guia do agente. Uma nova inscrição gera outra identidade. Se a continuidade das identidades existentes for necessária, planeje preservar o endereço e a confiança TLS antigos antes da troca. A transferência de configurações e chaves privadas deve ser uma operação administrativa explícita; este pacote não a automatiza.

## Backup consistente do dashboard em funcionamento

O utilitário incluído usa o SQLite JDBC do próprio pacote para executar `VACUUM INTO`, que cria uma cópia consistente do banco, incluindo dados ainda presentes no WAL. Ele recusa sobrescrever um arquivo de destino e verifica a cópia antes de concluir. Não é necessário instalar `sqlite3`, Python adicional ou outro serviço de banco.

Escolha um nome novo para cada cópia:

```sh
sudo install -d -m 700 /root/assessment-backups
sudo /opt/java-assessment-dashboard/database.sh backup \
  /opt/java-assessment-dashboard/data/inventory.sqlite \
  /root/assessment-backups/inventory-backup.sqlite
```

`BACKUP_OK` confirma a cópia após a checagem de integridade. O arquivo tem modo 600. Guarde também uma cópia privada de `conf`, da chave da CA e da senha inicial em `/root`. O backup SQLite contém identidades de agentes e deve ser tratado como segredo. Para copiar por arquivos em vez desse utilitário, pare o serviço antes e copie `data` inteira, incluindo quaisquer arquivos `-wal` e `-shm`; não copie apenas `.sqlite` com o serviço ativo.

## Restaurar uma cópia do dashboard

Primeiro valide o backup com `database.sh check /caminho/backup.sqlite`. Pare o serviço. Preserve a pasta `data` atual inteira com outro nome privado antes de substituí-la; nunca misture um backup do `.sqlite` com `-wal` ou `-shm` de outro momento. Crie uma nova pasta `data` e seu diretório `native`, ambos modo 700 e pertencentes à conta do serviço, e instale o backup como `inventory.sqlite`, modo 600. Por exemplo, em uma instalação com os caminhos padrão:

```sh
sudo systemctl stop java-assessment-dashboard
sudo mv /opt/java-assessment-dashboard/data /opt/java-assessment-dashboard/data-before-restore
sudo install -d -m 700 -o java-assessment-dashboard -g java-assessment-dashboard \
  /opt/java-assessment-dashboard/data /opt/java-assessment-dashboard/data/native
sudo install -m 600 -o java-assessment-dashboard -g java-assessment-dashboard \
  /root/assessment-backups/inventory-backup.sqlite \
  /opt/java-assessment-dashboard/data/inventory.sqlite
sudo systemctl start java-assessment-dashboard
sudo /opt/java-assessment-dashboard/database.sh check \
  /opt/java-assessment-dashboard/data/inventory.sqlite
```

Use um nome ainda inexistente para a pasta preservada e ajuste os comandos antes de executar. Mantenha `conf` correspondente ao backup. Confira `/health`, acesso administrativo e um agente após a restauração.

## Retornar ao dashboard anterior

Se a validação da migração falhar, pare o dashboard e preserve seus dados para análise. Reative a instalação antiga usando seus arquivos e configuração originais, com a URL, CA e credenciais anteriores. O dashboard antigo não lê SQLite; não substitua seus JSON pelo banco SQLite. Dados e cadastros recebidos exclusivamente pelo dashboard depois da troca não existem no backup antigo. Reverter o endereço ou a CA também exige restaurar a configuração correspondente dos coletores que já tenham sido trocados.
