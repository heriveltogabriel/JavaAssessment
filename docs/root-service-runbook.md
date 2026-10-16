# Coletor como serviço do sistema

Este documento inclui medições históricas da versão 1.4.0; elas não homologam versões posteriores. A revisão de prontidão do pacote 2.0.0 está em `output/production-readiness/2.0.0-20261014/PARECER.txt`.

## Operação

O coletor é independente do Oracle JMS. O serviço executa como **root** para ler os artefatos e os processos das aplicações, sem mudar permissões, proprietários, ACLs ou grupos das aplicações.

O painel recebe os resultados por HTTPS com uma credencial individual por máquina. A migração conserva essa credencial, o ID da máquina e o ambiente. Não usa SSH para enviar os inventários.

A consulta à fila ocorre aproximadamente a cada minuto. Uma consulta sem trabalho não inicia uma varredura. A coleta segue a agenda e os limites configurados no painel; o laboratório usa coleta sob demanda. Cada ciclo encerra sua JVM. O limite de heap é 96 MiB, com prioridade de CPU reduzida; memória total do processo pode ser maior que o heap.

## Instalar em uma próxima máquina

1. No painel, abra **Environments**, escolha o ambiente e baixe o pacote configurado.
2. Copie e extraia o ZIP na máquina. O pacote de ambiente já contém a autorização inicial; trate-o como material protegido.
3. Escolha um Java compatível instalado pelo sistema, com arquivos e diretórios pertencentes ao root e sem escrita para outros usuários.
4. Execute, substituindo o caminho do Java:

```sh
sudo /bin/sh ./install.sh --system-root --java /caminho/do/jdk/bin/java
```

O instalador cria `/opt/java-assessment-collector`, conserva as configurações das aplicações e confirma registro, conexão e contato com o painel. Não é necessário fornecer a chave SSH do servidor do dashboard.

**JVM pertencente a uma conta de aplicação:** não execute essa JVM como root nem altere suas permissões. A cópia privada usada no RHEL 5 do laboratório foi movida para `/opt/java-assessment-runtime/jdk1.6.0_45`, com validação dos links internos e proteção somente da cópia. O caminho antigo `/opt/java-assessment-collector/runtime/` é incompatível com a verificação estrita do atualizador quando contém os links legítimos do JDK. Na 2.0.2, o script histórico `ops/prepare-root-runtime.sh` passou a preparar a cópia em `/opt/java-assessment-runtime`; ele continua específico do laboratório, com origem Java 6 conhecida, e não deve ser usado como instalador genérico de JVM. Não foi baixada outra versão de Java. A migração de uma cópia antiga dentro do coletor precisa preceder a atualização; o procedimento de recuperação RHEL 5 existente preserva identidade e aplicações. As combinações de sistemas e JVMs do cliente ainda precisam de validação operacional.

## Migrar uma instalação existente

Use o pacote novo e indique o diretório anterior:

```sh
sudo /bin/sh ./install.sh --system-root \
  --java /caminho/confiavel/bin/java \
  --migrate-from /home/opc/java-assessment
```

A migração preserva a identidade, o último resultado, os resultados ainda pendentes e os eventos aguardando envio. O instalador remove apenas o cron reconhecido daquele coletor. Outros agendamentos, como a coleta JMX do WebLogic no laboratório, permanecem intactos.

O nome **java-assessment-collector** é exclusivo do coletor. O serviço **java-assessment** já existente na máquina RHEL 5 pertence ao dashboard e deve permanecer separado.

## RHEL 7/8 e outros sistemas com systemd

```sh
sudo systemctl status java-assessment-collector.timer
sudo systemctl status java-assessment-collector.service
sudo systemctl list-timers java-assessment-collector.timer
sudo journalctl -u java-assessment-collector.service -n 50
```

O serviço é `oneshot`: ficar `inactive (dead)` entre ciclos é normal. O **timer** deve estar habilitado e ativo. Para executar uma consulta à fila imediatamente:

```sh
sudo systemctl start java-assessment-collector.service
```

Para parar ou retomar:

```sh
sudo systemctl stop java-assessment-collector.timer java-assessment-collector.service
sudo systemctl start java-assessment-collector.timer
```

## RHEL 5 — SysV

```sh
sudo service java-assessment-collector status
sudo chkconfig --list java-assessment-collector
sudo service java-assessment-collector stop
sudo service java-assessment-collector start
```

Um pequeno processo shell supervisiona os ciclos. A parada aguarda o ciclo atual terminar; não encerra JVMs das aplicações.

## Logs e diagnóstico

```sh
sudo tail -n 80 /opt/java-assessment-collector/logs/install.log
sudo tail -n 80 /opt/java-assessment-collector/logs/collector.log
sudo tail -n 80 /opt/java-assessment-collector/logs/service.log
```

`service.log` é usado pelo supervisor SysV. Em systemd, consulte também o journal. Não publique `conf/collector.properties`, o conteúdo do pacote de ambiente ou os arquivos de credenciais.

Os resultados de diagnóstico distinguem:

- **Process observed**: lançador encontrado em um processo vivo; não é uma verificação de saúde.
- **Found on disk**: instalação ou biblioteca encontrada; não comprova execução.
- **JDBC packaged/found**: driver disponível; não comprova conexão ou datasource ativo.
- **Runtime observation**: evidência observada por uma fonte de runtime, preservando o horário original.

## Reversão

Pare o serviço/timer novo antes de restaurar o agendamento anterior. O cron antigo é salvo em `/opt/java-assessment-collector/backups/crontab-<usuario>-<execucao>`. Revise esse arquivo e o crontab atual antes de restaurar, para conservar alterações posteriores de outros sistemas. A instalação anterior em `/home/opc/java-assessment` permanece disponível com a identidade original.

Estados anteriores do destino são guardados em `/opt/java-assessment-collector-backup-<execucao>`. Não combine um token de uma máquina com o ID de outra e não execute os dois agendadores simultaneamente.

## Evidência da implantação — 9 de outubro de 2026

Os três coletores usam a versão **1.4.0**. Serviços habilitados para inicialização do SO; validada a execução de ciclos automáticos após sair do SSH. Não foi reiniciada nenhuma VM.

| Máquina | Serviço | Evidência recebida |
| --- | --- | --- |
| instance-rh5 | SysV, supervisor UID 0 | WebLogic 10.3.6 e datasource Derby da observação JMX existente |
| instance-ol8 | systemd, User=root | Spring Boot 4.1.1, aplicação springdemo 1.0.0 e H2 JDBC 2.4.240 |
| instance-web | systemd, User=root | WildFly 26.1.3.Final em processo; Tomcat 9.0.122 encontrado em disco |

O serviço do Tomcat já apresentava tentativas de reinício e não tinha JVM viva na inspeção. O coletor registra somente a instalação encontrada. Nenhuma alteração foi feita nessa aplicação.

### Medidas das coletas reais

| Máquina | Duração da varredura | Arquivos examinados | Pico amostrado de heap | Pico RSS do processo |
| --- | ---: | ---: | ---: | ---: |
| instance-rh5 | 1.21 s | 3342 | 31.1 MiB | 87.1 MiB |
| instance-ol8 | 4.29 s | 12000 | 39.0 MiB | 131.8 MiB |
| instance-web | 4.27 s | 11190 | 21.3 MiB | 117.0 MiB |

Duração acima é da varredura, não do ciclo completo de inicialização, verificação e envio. Heap limitado a 64 MiB; RSS inclui estruturas nativas da JVM. As JVMs do coletor encerram após os ciclos. Os resultados ficaram **PARTIAL** porque os limites de arquivos, arquivos compactados e metadados foram respeitados; não houve aviso de acesso negado nos resultados dessas três varreduras.

A cópia privada do Java 6 ocupava aproximadamente 216 MiB nesse teste. A exclusão do inventário implementada desde 1.4.2 cobre o subdiretório `runtime/` do coletor. Na 2.0.2, a exclusão também cobre `/opt/java-assessment-runtime` associado à instalação root canônica; processos do próprio atualizador são filtrados. Uma aplicação real que use esse Java continua sendo inventariada. O JDK compartilhado com uma aplicação continua sendo inventariado; a versão Java do coletor permanece disponível nos metadados de diagnóstico da máquina.

### Preservação e testes

- Mesmos três IDs e ambientes, sem cadastro duplicado.
- Cron antigo do coletor removido nas três máquinas; demais agendamentos preservados.
- Nenhuma alteração de dono ou modo nos 13.535 caminhos de aplicações comparados.
- PIDs originais das JVMs de WebLogic, Spring Boot, WildFly e dashboard preservados.
- Reinstalação do serviço testada no RHEL 8, mantendo identidade e estado.
- Compilação e testes de parser, arquivos compactados, transporte, retry e identidade em Java 6.
- Guardas de pacote/JVM testadas nos Java 6, 11 e 17 reais, incluindo rejeição de caminhos inseguros.
- 17 fixtures de bootstrap, fixtures de migração, testes do dashboard e navegação com filtros passaram.

Arquivos de evidência: `root-service-live-validation.json`, `root-service-permission-validation.json`, `application-discovery-live.json` e `application-discovery-ui-validation.json`.

Pacote publicado SHA-256: `b3af68b52c644b43531ae03af9b33ebe3027793db1332491524183474a28abb0`. A diferença em relação ao ZIP implantado antes da publicação é textual nas instruções README/INSTALL; os scripts e o JAR operacionais são iguais.
