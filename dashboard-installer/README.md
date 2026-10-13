# Java Assessment 1.0 Beta — instalador do dashboard

O passo a passo completo para a VM, os ambientes e os agentes está em [GUIA_INSTALACAO.md](GUIA_INSTALACAO.md), incluído neste ZIP.

As mudanças da Beta e os cuidados com o banco local estão em [BETA_RELEASE.md](BETA_RELEASE.md). O arquivo `assessment-dashboard-installer-1.0-beta.zip` e o nome de compatibilidade `assessment-dashboard-installer.zip` contêm o mesmo pacote. Compare o SHA-256 com o arquivo `.zip.sha256` entregue junto com o ZIP antes de instalar.

Para migrar um inventário existente para uma nova VM, consulte [BETA_MIGRATION.md](BETA_MIGRATION.md). A opção `--legacy-data /pasta/privada` importa um par consistente de `state.json` e `auth-registry.json` antes da primeira inicialização. Migrar dados não muda a URL ou a CA configuradas nos coletores.

Para uma VM nova com Oracle Linux 8/9 ou outra distribuição Enterprise Linux 8+ compatível, copie este ZIP e execute:

```sh
unzip assessment-dashboard-installer.zip -d dashboard-installer
cd dashboard-installer
./install.sh
```

O script usa `sudo` quando necessário. Ele confirma o IP ou DNS público no terminal, procura um JDK 17+ e instala um via `dnf` se faltar, gera CA e certificado HTTPS, cria senha inicial, usuário e serviço systemd, configura o firewall local se `firewalld` estiver ativo e testa a URL localmente. O progresso fica em `/var/log/java-assessment-dashboard-install.log`.

O inventário usa SQLite em `data/inventory.sqlite`. O driver, bibliotecas nativas, licenças e dependências já estão no ZIP, com versões e SHA-256 fixados em `lib/dependencies-lock.json`. Não é preciso instalar ou administrar um serviço de banco de dados. O instalador compila o dashboard para Java 17, prepara `data/native` com acesso exclusivo da conta do serviço e valida a integridade das bibliotecas antes da instalação.

Ao final, consulte a senha com `sudo cat /root/java-assessment-dashboard-admin.txt`. Abra a URL mostrada pelo instalador, crie um ambiente e baixe o ZIP do agente. Esse ZIP já contém a URL do dashboard, a CA TLS e a credencial temporária de registro; a máquina monitorada não precisa de chave SSH do dashboard.

Se a máquina já usa um agente `--system-root` de **outro dashboard**, apenas executar `sudo ./install.sh --system-root` preservaria a identidade antiga. Para cadastrar no dashboard novo, extraia o ZIP novo em uma pasta separada e use `sudo ./install.sh --system-root --switch-dashboard`. O agente guarda um backup root da identidade, dos logs e dos resultados pendentes antigos. Consulte o `INSTALL.md` dentro do ZIP do agente antes da migração.

## Endereço e HTTPS

O endereço sugerido pode estar errado em redes com NAT, proxy ou balanceador. Confirme um IP ou DNS que os navegadores e todos os agentes consigam acessar. Para execução sem terminal, informe `--public-url https://IP-ou-DNS`.

O certificado gerado é assinado por uma CA local. Os agentes confiam nela porque o ZIP a inclui. O navegador avisará que a CA não é pública até ela ser adicionada ao repositório de confiança do navegador/sistema. A CA pública fica em `/opt/java-assessment-dashboard/conf/agent-ca.pem`; a chave privada da CA fica apenas em `/root/java-assessment-dashboard-ca.key`, modo 600. Guarde esta chave para renovação futura; não a copie para os agentes.

Se já houver um certificado público, use:

```sh
./install.sh --public-url https://dashboard.exemplo.com \
  --tls-cert /caminho/fullchain.pem \
  --tls-key /caminho/privkey.pem \
  --agent-ca /caminho/ca.pem
```

O certificado precisa corresponder ao IP ou DNS escolhido e usar chave RSA PKCS#8 PEM sem senha. `--agent-ca` deve apontar à CA raiz que será embutida nos agentes. Para automação, `--admin-password-file /caminho/senha` aceita um arquivo de uma linha, modo 600; se omitido, o instalador cria uma senha aleatória. `--java-home` seleciona um JDK existente. `--no-package-install` impede instalar um JDK via `dnf`.

## Rede e serviço

O dashboard atende em HTTPS na porta 443 por padrão. Abra **TCP 443** no NSG ou Security List da OCI para as origens desejadas. Essa regra da OCI não é alterada pelo instalador. Se a URL usar outra porta, abra essa porta. O firewall local é configurado automaticamente quando `firewalld` está ativo.

O serviço é `java-assessment-dashboard.service` e os arquivos ficam em `/opt/java-assessment-dashboard`. Verifique com:

```sh
sudo systemctl status java-assessment-dashboard --no-pager
sudo journalctl -u java-assessment-dashboard -n 100 --no-pager
curl --cacert /opt/java-assessment-dashboard/conf/agent-ca.pem https://IP-ou-DNS/health
```

O Java usa heap máximo de 384 MiB; o serviço tem limite de memória total de 768 MiB. Uma falta de heap encerra o processo para o systemd reiniciá-lo. Os logs de GC são rotacionados em `logs/gc.log` (até três arquivos de 5 MiB). O SQLite extrai sua biblioteca nativa em `data/native`; esse caminho precisa permitir carregamento de bibliotecas executáveis. `PrivateTmp` permanece habilitado.

O instalador não substitui uma instalação existente. Ele também não modifica `sshd` nem distribui chaves SSH para as máquinas coletadas: o agente se registra via HTTPS. Se o endereço público ou a CA mudar depois, atualize os agentes já registrados; novos downloads receberão a configuração nova.
