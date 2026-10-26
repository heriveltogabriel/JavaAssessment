# Java Assessment — instalador do dashboard

O passo a passo completo para a VM, os ambientes e os agentes está em [GUIA_INSTALACAO.md](GUIA_INSTALACAO.md), incluído neste ZIP.

As notas da versão e os cuidados com o banco local estão em [BETA_RELEASE.md](BETA_RELEASE.md). O arquivo `assessment-dashboard-installer-<VERSAO>.zip` e o nome de compatibilidade `assessment-dashboard-installer.zip` contêm o mesmo pacote. Compare o SHA-256 com o arquivo `.zip.sha256` entregue junto com o ZIP antes de instalar.

Para migrar um inventário existente para uma nova VM, consulte [BETA_MIGRATION.md](BETA_MIGRATION.md). A opção `--legacy-data /pasta/privada` importa um par consistente de `state.json` e `auth-registry.json` antes da primeira inicialização. Migrar dados não muda a URL ou a CA configuradas nos coletores.

Para uma VM nova com Oracle Linux 8/9 ou outra distribuição Enterprise Linux 8+ compatível, copie este ZIP e execute:

```sh
unzip assessment-dashboard-installer.zip -d dashboard-installer
cd dashboard-installer
./install.sh
```

O script usa `sudo` quando necessário. Ele confirma o IP ou DNS público no terminal, procura um JDK 17+ e instala um via `dnf` se faltar, gera CA e certificado HTTPS, solicita nome/email do primeiro administrador, cria uma senha temporária de 24 horas, usuário e serviço systemd, configura o firewall local se `firewalld` estiver ativo e testa a URL localmente. O progresso fica em `/var/log/java-assessment-dashboard-install.log`.

O inventário usa SQLite em `data/inventory.sqlite`. O driver, bibliotecas nativas, licenças e dependências já estão no ZIP, com versões e SHA-256 fixados em `lib/dependencies-lock.json`. Não é preciso instalar ou administrar um serviço de banco de dados. O instalador compila o dashboard para Java 17, prepara `data/native` com acesso exclusivo da conta do serviço e valida a integridade das bibliotecas antes da instalação.

Ao final, consulte email e senha temporária com `sudo cat /root/java-assessment-dashboard-admin.txt`. Abra a URL, entre com o email e essa senha e defina sua senha pessoal (8–128 caracteres). A troca é obrigatória antes de acessar o inventário. Depois, crie um ambiente e baixe o ZIP do agente. Esse ZIP já contém a URL do dashboard, a CA TLS e a credencial temporária de registro; a máquina monitorada não precisa de chave SSH do dashboard.

Se a máquina já usa um agente `--system-root` de **outro dashboard**, apenas executar `sudo ./install.sh --system-root` preservaria a identidade antiga. Para cadastrar no dashboard novo, extraia o ZIP novo em uma pasta separada e use `sudo ./install.sh --system-root --switch-dashboard`. O agente guarda um backup root da identidade, dos logs e dos resultados pendentes antigos. Consulte o `INSTALL.md` dentro do ZIP do agente antes da migração.

## Endereço e HTTPS

O endereço sugerido pode estar errado em redes com NAT, proxy ou balanceador. Confirme um IP ou DNS que os navegadores e todos os agentes consigam acessar. Para execução sem terminal, informe `--public-url https://IP-ou-DNS --admin-name "Nome do Administrador" --admin-email admin@exemplo.com`.

O certificado gerado é assinado por uma CA local. Os agentes confiam nela porque o ZIP a inclui. O navegador avisará que a CA não é pública até ela ser adicionada ao repositório de confiança do navegador/sistema. A CA pública fica em `/opt/java-assessment-dashboard/conf/agent-ca.pem`; a chave privada da CA fica apenas em `/root/java-assessment-dashboard-ca.key`, modo 600. Guarde esta chave para renovação futura; não a copie para os agentes.

Se já houver um certificado público, use:

```sh
./install.sh --public-url https://dashboard.exemplo.com \
  --tls-cert /caminho/fullchain.pem \
  --tls-key /caminho/privkey.pem \
  --agent-ca /caminho/ca.pem
```

O certificado precisa corresponder ao IP ou DNS escolhido e usar chave RSA PKCS#8 PEM sem senha. `--agent-ca` deve apontar à CA raiz que será embutida nos agentes. Para automação, `--admin-password-file /caminho/senha` aceita um arquivo de uma linha, modo 600/400, pertencente a root ou ao usuário instalador, com 8–128 caracteres; a senha é temporária, expira em 24 horas e exige troca. Se omitido, o instalador gera uma senha aleatória. `--java-home` seleciona um JDK existente. `--no-package-install` impede instalar um JDK via `dnf`.

## Rede e serviço

O dashboard atende em HTTPS na porta 443 por padrão. Abra **TCP 443** no NSG ou Security List da OCI para as origens desejadas. Essa regra da OCI não é alterada pelo instalador. Se a URL usar outra porta, abra essa porta. O firewall local é configurado automaticamente quando `firewalld` está ativo.

O serviço é `java-assessment-dashboard.service` e os arquivos ficam em `/opt/java-assessment-dashboard`. Verifique com:

```sh
sudo systemctl status java-assessment-dashboard --no-pager
sudo journalctl -u java-assessment-dashboard -n 100 --no-pager
curl --cacert /opt/java-assessment-dashboard/conf/agent-ca.pem https://IP-ou-DNS/health
```

O Java usa heap inicial e máximo de 1536 MiB (`-Xms1536m -Xmx1536m`); o serviço tem limite de memória total de 2 GiB. Reserve memória adicional para o sistema operacional e outros serviços. Uma falta de heap encerra o processo para o systemd reiniciá-lo. Os logs de GC são rotacionados em `logs/gc.log` (até três arquivos de 5 MiB). O SQLite extrai sua biblioteca nativa em `data/native`; esse caminho precisa permitir carregamento de bibliotecas executáveis. `PrivateTmp` permanece habilitado.

O instalador não substitui uma instalação existente. Ele também não modifica `sshd` nem distribui chaves SSH para as máquinas coletadas: o agente se registra via HTTPS. Se o endereço público ou a CA mudar depois, atualize os agentes já registrados; novos downloads receberão a configuração nova.

## Contas e atualização do login

Uma instalação nova já possui o primeiro administrador provisionado. O arquivo privado de configuração não contém senha temporária nem hash da antiga senha compartilhada; o SQLite guarda hashes Argon2id. Remova o registro da senha temporária em `/root/java-assessment-dashboard-admin.txt` após definir sua senha pessoal. O nome/email continuam visíveis apenas nesse registro root e na conta administrativa.

Ao atualizar um dashboard anterior, a tela de configuração exige a senha administrativa atual e solicita nome, email e uma nova senha pessoal. Só uma conta pode concluir essa ativação. O inventário, os agentes e suas credenciais são preservados. Depois da ativação, o publicador recusa restaurar software antigo com autenticação por senha compartilhada; ele conserva os arquivos novos e o banco e registra o bloqueio no relatório privado. Use sempre o publicador da versão atual. Não execute instaladores ou publicadores históricos para voltar de versão.

## Exportar inventário para análise

Em Administração → Exportar inventário, escolha JSON, CSV completo, Excel (.xlsx) com abas por tipo ou Mapa de bancos. O ZIP contém um único arquivo com dados técnicos, vínculos, versões Java/JDBC e JARs. Excel resume listas/evidências e avisa sobre células longas; JSON preserva os detalhes estruturados. O mapa relaciona datasource, destino configurado e processo/aplicação. Não inclui cadastros de usuários, credenciais, chaves ou configurações operacionais. Nomes, IPs e caminhos permanecem identificáveis. O pacote serve para análise em outra aplicação, não para restaurar o dashboard.

A entrega 2.1.18 permite atualizar o dashboard pela console a partir da 2.1.14 ou 2.1.16 com o web-update apropriado. Este instalador é apenas para servidor novo. Atualizar o dashboard não atualiza os agentes automaticamente; não houve mudança funcional no coletor e os novos formatos usam os dados já coletados.

## Profile/contexto — 2.1.20

A tabela e os detalhes de Datasources JDBC mostram o profile/contexto encontrado na configuração, sem unir nomes iguais de perfis diferentes. O campo usa dados existentes; não confirma atividade em execução. Atualizar o dashboard não atualiza os agentes automaticamente. Esta melhoria não exige agente novo. Use o web-update para atualizar uma instalação compatível; o instalador completo é para servidor novo.

## Drivers JDBC — 2.1.24

Melhor identificação de drivers WebLogic com argumentos longos, associação das configurações ao domínio correto, reconhecimento de `ojdbc14` e motivos detalhados de inspeção incompleta.

Para atualizar uma console existente, use o ZIP **web-update 2.1.24** pela Administração, após a validação de elegibilidade. Este instalador completo é para servidor novo.

**Atualizar o dashboard não atualiza os agentes automaticamente.** Atualize os agentes separadamente em **Revisar agentes** e solicite nova coleta. Sem capacidade/rota compatível no painel, use o pacote configurado para o ambiente. Não instale a ponte interna 2.1.23 manualmente.
