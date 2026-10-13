# Java Assessment 1.0 Beta

Esta versão usa SQLite local para manter o inventário. O dashboard consulta páginas e resumos no servidor, reduzindo o volume enviado ao navegador nas trocas de tela e filtros. A meta de validação é mil máquinas com aplicações e bibliotecas representativas; os tempos efetivamente medidos e o ambiente do teste devem acompanhar a entrega da versão.

## Instalação nova

O instalador mantém o fluxo de instalação por `./install.sh`: JDK 17+, certificado HTTPS, conta dedicada, senha inicial, firewall local e serviço systemd. O driver SQLite JDBC 3.53.4.0, SLF4J API/JUL 1.7.36 e Bouncy Castle 1.86 estão incluídos. `lib/dependencies-lock.json` registra origem, versão, licença e SHA-256; os POMs e as licenças estão nas pastas `lib/provenance` e `lib/licenses`.

O script valida o manifesto antes de instalar e compila todas as fontes do servidor com `--release 17`. Não há download de bibliotecas durante a instalação. Se o JDK, Python e OpenSSL já estiverem presentes, use `--no-package-install` para impedir a instalação de um JDK pelo instalador Python.

Os arquivos de programa pertencem a root e são somente leitura para o serviço. `data`, `data/native` e `logs` pertencem à conta `java-assessment-dashboard`, com modo 700; dados privados usam modo 600. Essa preparação acontece antes da primeira inicialização, evitando falhas ao proteger o armazenamento privado.

## Operação e backup

O banco fica em `data/inventory.sqlite`, com arquivos auxiliares `inventory.sqlite-wal` e `inventory.sqlite-shm` enquanto está em uso. O modo WAL permite consultar o inventário durante uma gravação. Não copie somente o arquivo `.sqlite` enquanto o serviço estiver rodando. Para uma cópia por arquivos, pare o serviço, guarde `data` e `conf` completos com seus proprietários e permissões, e reinicie o serviço. Guarde também a chave da CA e a senha inicial, mantidas separadamente em `/root`.

O Java usa `-Xmx384m` e `-XX:+ExitOnOutOfMemoryError`. O systemd reinicia falhas com intervalo de três segundos e limita tentativas repetidas; o teto de memória do serviço é 768 MiB. Consulte `journalctl -u java-assessment-dashboard` para erros e `logs/gc.log` para atividade do coletor de lixo. `GET /health` é usado pelo instalador para confirmar a inicialização HTTPS.

Para limitar o crescimento do histórico por máquina, `conf/server.properties` inclui `jobHistoryPerHost=100` (faixa de 10 a 1.000) e `receiptHistoryPerHost=200` (faixa de 200 a 2.000). O primeiro controla a retenção de tarefas finalizadas, preservando as que estão na fila ou em execução; o segundo, os registros usados para reconhecer resultados já recebidos. Ajustes exigem reiniciar o serviço. Guarde esses parâmetros junto com a configuração no backup.

O SQLite usa `data/native` para extrair a biblioteca nativa; esse diretório é privado e gravável pelo serviço. Ele deve estar em um sistema de arquivos que permita carregar bibliotecas nativas. `PrivateTmp`, `ProtectSystem=strict` e `UMask=0077` estão ativos no serviço.

## Atualização de uma instalação existente

Este ZIP instala um dashboard novo e recusa substituir uma instalação existente. A atualização da máquina atual é uma etapa separada, executada somente após autorização. Antes dela, é necessário guardar uma cópia consistente dos dados e da configuração e preparar o retorno à versão anterior. Os JSON legados devem ser preservados para recuperação após a importação inicial; não exclua esses arquivos manualmente durante a validação da Beta.

O pacote de instalação contém fontes, recursos estáticos, bibliotecas e o coletor genérico. Identidades de agentes, tokens de cadastro, senhas, chaves privadas e dados de uma instalação existente não fazem parte dele.

Para uma origem em RHEL 5, prepare uma nova VM Enterprise Linux 8+; não há atualização direta do dashboard Java 17 nesse sistema antigo. [BETA_MIGRATION.md](BETA_MIGRATION.md) descreve `--legacy-data`, verificação, backup consistente com `database.sh`, restauração e retorno à instalação anterior. A mudança de URL ou CA exige configurar os agentes para o novo dashboard.
