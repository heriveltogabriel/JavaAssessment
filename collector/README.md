# Coletor de assessment

Agente Java Assessment com bytecode Java 6. O pacote inclui os provedores Bouncy Castle BC/BCJSSE para oferecer TLS 1.2 com validação de certificados também nas JVMs antigas. O alvo de compatibilidade é Linux com Java 6 ou superior. Compatibilidade de bytecode não equivale a homologação de toda combinação de JVM, distribuição e arquitetura; Linux e Java de 32 bits ainda não foram homologados nesta entrega.

Desde o coletor 1.5.1, uma JVM de aplicação observada permite inventariar seu runtime Java mesmo sem `javac`. O registro fica classificado como `JRE`, com evidência de uso; JDKs com compilador continuam classificados como `JDK`. Várias JVMs no mesmo Java home contam como uma instalação. Runtimes sem evidência de uso e o Java privado exclusivo do coletor seguem excluídos. Versão e arquitetura vêm de metadados e do executável, sem iniciar o Java descoberto.

## Execução

Compile `collector/*.java` e `shared/Json.java` usando `-cp "lib/*"`, com os três JARs BC/BCJSSE presentes, para um diretório `classes`. Os launchers, `launcher-common.sh`, `lib/` e esse diretório ficam lado a lado. A distribuição fornece `collector.jar`; ele inclui as classes operacionais e `assessment.JavaProbe`, sem classes de testes.

```sh
chmod 700 ./run-once.sh
chmod 600 /caminho/conf/collector.properties
ASSESSMENT_JAVA=/home/opc/oracle/jdk1.6.0_45/bin/java ./run-once.sh /caminho/conf/collector.properties
```

O launcher seleciona Java nesta ordem: `ASSESSMENT_JAVA` explícito (ou caminho protegido salvo pelo instalador em `conf/java.path`), `ASSESSMENT_JAVA_HOME`, `JAVA_HOME`, executáveis Java de processos já existentes em `/proc`, `PATH`, caminhos Java conhecidos. A descoberta automática testa no máximo 24 executáveis distintos por execução; caminhos repetidos são ignorados. Cada candidato precisa executar `assessment.JavaProbe`, compilado em bytecode Java 6, com prazo de cinco segundos, mais até um segundo para término. Isso recusa GCJ e versões anteriores mesmo quando `java -version` retorna sucesso. Seleções explícitas incompatíveis causam erro; candidatos automáticos incompatíveis são ignorados. Não instala JDK. Executa a JVM com heap máximo de 96 MiB e prioridade `nice 10`. RSS inclui memória nativa e pode exceder o heap.

Sem job pendente, apenas envia heartbeat, verifica a fila de resultados e consulta o servidor. A varredura depende de um job que o servidor autorize e entregue. Nos modos usuário e `--system`, o cron chama esse comando uma vez por minuto; o servidor controla frequência/janela. FileLock impede execuções simultâneas. A JVM não permanece residente entre ciclos.

### Serviço root opcional

Depois de extrair um pacote configurado por ambiente:

```sh
chmod +x install.sh
sudo ./install.sh --system-root
```

O instalador descobre uma JVM existente compatível e verifica propriedade root e proteção contra escrita por outros usuários antes de executá-la. Arquivos do runtime e diretórios ancestrais precisam atender a esses requisitos. Sem candidato elegível, a instalação para com um diagnóstico. `--java /caminho/jdk-protegido/bin/java` é uma escolha explícita opcional e passa pelas mesmas verificações; uma escolha inválida causa falha. A descoberta não baixa Java nem altera o runtime das aplicações.

A seleção root tenta, em ordem, o caminho salvo da instalação anterior; `JAVA_HOME` e `JDK_HOME` presentes no ambiente do instalador (podem ser removidos pelo `sudo`); o `PATH` de sistema controlado, `/usr/bin/java`/alternatives e os diretórios usuais `/usr/lib/jvm`, `/usr/java`, `/opt/java-lab` e `/opt/java`; depois os caminhos de `/proc/PID/exe`, tratados apenas como localizações a validar. Como última etapa, busca metadados em `/opt`, `/usr/local`, `/u01`, `/u02`, `/app`, `/apps`, `/oracle` e `/data`: profundidade 8, até 10.000 entradas ou 10 segundos, sem atravessar filesystems, seguir diretórios simbólicos ou entrar em árvores sem proteção root. Os dez segundos limitam essa busca, não a instalação inteira.

O limite global é de 24 candidatos; cada `JavaProbe` de compatibilidade tem prazo de cinco segundos, mais até um segundo para encerramento, e só executa após a validação de confiança. O Java escolhido fica salvo em `conf/java.path` para os ciclos seguintes. A busca recursiva ocorre na instalação. Caminhos com espaços e `+` são aceitos; proteja-os com aspas quando usar `--java`.

O modo instala em `/opt/java-assessment-collector` e executa as consultas como root, usando serviço/timer systemd ou serviço SysV a cada 60 segundos. Não muda permissões, ACLs ou grupos das aplicações; mantém os limites e as exclusões de coleta. `--system` continua executando como o usuário dedicado `java-assessment`. Consulte `INSTALL.md` para preparo e diagnóstico. Em pacote de ambiente, URL e autorização inicial já estão configuradas; no genérico, informe `--server` e `--code-file` apontando para um arquivo protegido modo 600/400. O modo root não solicita código interativamente.

Credenciais são fornecidas em arquivo `collector.properties` pertencente ao usuário, com permissão 600 ou 400. Campos obrigatórios: `serverUrl`, `hostId`, `agentToken`, `spoolDirectory`. `hostname` e `snapshotRoot` são opcionais. Nunca inclua tokens no pacote de download. HTTP é permitido somente em loopback; fora da máquina é exigido HTTPS. HTTPS usa `tlsProvider=BCJSSE` e `tlsCaFile=/caminho/absoluto/conf/ca.pem`. O arquivo contém somente autoridades certificadoras públicas. A cadeia é validada por PKIX e a identidade DNS/IP é verificada durante o handshake, antes de enviar credenciais; nenhuma validação de certificado é desativada.

## Limites e evidências

- A varredura geral usa `includePaths`; `/` e caminhos relativos são recusados. Antes dela, são examinados arquivos apontados diretamente pelos processos Java, mesmo fora das inclusões, sempre respeitando exclusões e volumes de rede. Esses registros têm `scanScope=process-linked`.
- A varredura geral pula a pasta de instalação identificada pelo caminho canônico do `collector.jar`, incluindo o próprio JAR, `lib/`, logs, fila de resultados e runtime privado. Esses arquivos não entram nos contadores de arquivos, arquivos compactados ou bytes examinados. A regra usa o limite exato do diretório e não afeta instalações próximas. Evidências de aplicações realmente apontadas por outros processos continuam sendo coletadas, mesmo dentro dessa pasta, respeitando as exclusões configuradas pelo usuário.
- `/proc`, `/sys`, `/dev`, `/tmp` são excluídos da varredura recursiva. Leituras específicas de `/proc` obtêm metadados de host e processos.
- Não segue links simbólicos na árvore, não entra em NFS/CIFS/SSHFS identificados em `/proc/mounts`, nem percorre diretórios de logs/cache/temporários.
- Limites por tempo, quantidade de arquivos (padrão de 10.000 quando não configurado), profundidade 12, até 8 MiB de metadados textuais, pausas por lote e limite de linhas por entidade. Prazo esgotado, acesso negado ou arquivos elegíveis que ultrapassem os limites geram cobertura parcial. Árvores intencionalmente excluídas não consomem orçamento nem geram aviso de cobertura parcial por profundidade ou quantidade de arquivos.
- Faixas iguais às do servidor: duração 10–900 segundos, 100–50000 arquivos, lotes de 1–500 e pausas de 0–2000 ms. O prazo de varredura é cooperativo: DNS e I/O bloqueado podem ultrapassá-lo. O launcher supervisiona somente o PID Java que criou, com teto global de 1050 segundos e até cinco segundos para término forçado. Nunca sinaliza WebLogic ou outras JVMs. Um job interrompido pode ficar sem resultado; o servidor registra a expiração, e a fila já gravada continua disponível.
- `inspectArchives=false` identifica candidatos por nomes, sem abrir arquivos ZIP; versões inferidas do nome são identificadas como inferência. Quando habilitado, lê metadados de manifesto/Maven/SPI e bibliotecas aninhadas em um único nível de `BOOT-INF/lib` ou `WEB-INF/lib`. Limites: 40 arquivos, arquivo externo até 128 MiB, diretório central até 2 MiB/5.000 entradas, metadado individual até 64 KiB, orçamento compartilhado de 8 MiB. Nunca carrega classes desses JARs.
- Binários encontrados pela varredura não são executados. Versões vêm de `release`, propriedades da JVM do coletor, manifest quando autorizado ou snapshot runtime explícito. Versão desconhecida fica vazia.
- O inventário inclui JDKs identificados por `bin/javac` e JREs com evidência de uso por uma aplicação. JREs sem evidência de uso são ignorados, sem percorrer seus arquivos internos. O `java.home` terminado em `/jre` das JVMs antigas é normalizado para o JDK pai quando ele contém `bin/javac`. Processos, aplicações, servidores e JDBC continuam sendo coletados quando executados em um JRE; o caminho observado permanece como evidência nesses registros.
- O JDK privado localizado no subdiretório `runtime/` ao lado do `collector.jar`, ou no diretório dedicado `/opt/java-assessment-runtime` da instalação root, é omitido quando usado somente pelo coletor. Se outra aplicação usar esse mesmo JDK, ele aparece normalmente, com seu vínculo ao processo. JDKs compartilhados fora desse diretório continuam no inventário, inclusive quando também executam o coletor; as propriedades conhecidas dessa JVM enriquecem sua versão. A identificação respeita limites de diretório e não exclui caminhos apenas por conterem a palavra `collector`.
- A arquitetura do host vem de `/bin/uname -m`. A arquitetura da instalação Java é obtida do cabeçalho ELF ou propriedades dessa JVM, mantendo Java 32 bits separado de host 64 bits.
- Processos Java são lidos em `/proc`. O PID do próprio coletor é excluído. Só os nomes de classes principais e propriedades de identificação de servidor permitidas são preservados; argumentos completos, senhas e variáveis de ambiente não são armazenados.
- Arquivos WAR/EAR encontrados não são apresentados como aplicações comprovadamente implantadas. Drivers encontrados em disco têm uso `found`; drivers retornados pelo datasource têm uso `confirmed`, sem inventar o caminho do JAR.
- WebLogic, JBoss/WildFly, Tomcat e Spring Boot são identificados por lançadores/metadados. Versão de aplicação, framework, produto e JDBC permanecem separadas. Bibliotecas compartilhadas mantêm múltiplas associações; um driver empacotado não comprova datasource ativo.
- Para aplicações Spring Boot executáveis identificadas em processos, o coletor examina a configuração base `BOOT-INF/classes/application.properties`, `application.yaml` ou `application.yml` durante a mesma leitura limitada do JAR. Um URL JDBC resolvido ou uma classe de driver explicitamente configurada permite registrar **presença de configuração** (`evidenceLevel=configuration`, `state=CONFIGURED`). Isso não confirma configuração efetiva, carregamento do driver, ativação de perfil, conexões ou saúde do banco. Um JAR JDBC ou uma propriedade de tamanho de pool, sozinhos, não criam datasource.
- A configuração Spring preserva somente a classe de driver validada, o tipo de banco derivado de uma lista de protocolos e, se informado, o limite Hikari `configuredMaxPoolSize` entre 1 e 100.000. URL completa, usuário, senha, nome arbitrário do pool e outras propriedades não entram no resultado, cache de metadados ou log. O nome do datasource é derivado do nome da aplicação. Versão do driver só é associada quando há exatamente uma biblioteca JDBC empacotada compatível; `driverVersionEvidence` deixa explícito que o carregamento em runtime não foi verificado. Capacidade atual do pool e conexões ativas não são inferidas do arquivo.
- A leitura `.properties` trata escapes e continuações. YAML suporta apenas mappings simples com escalares, indentação consistente e chaves únicas; estruturas complexas, aliases, anchors, tags, sequências, escapes não suportados e documentos múltiplos são descartados conservadoramente. Arquivos específicos de perfil, configurações condicionadas à ativação e placeholders não são resolvidos. Configurações externas, variáveis de ambiente, argumentos, datasources programáticos e múltiplos datasources personalizados ficam fora do escopo atual da descoberta. Se houver formatos base simultâneos, `.properties` é priorizado; os arquivos não são mesclados e uma configuração presente somente no YAML pode não aparecer.
- A configuração base do datasource tem prioridade sobre metadados Maven e bibliotecas aninhadas; continuam valendo 64 KiB por entrada, 40 arquivos compactados, 8 MiB de leitura e prazo da coleta. Não há nova varredura, extração, carregamento de classes, attach/JMX, consulta de logs ou conexão ao banco. O mesmo JAR executado por vários processos gera um datasource lógico com associações separadas; não aumenta artificialmente a contagem. As observações WebLogic existentes mantêm a fonte e os campos de runtime originais.
- Se `/proc/PID/exe` não for legível, o coletor aproveita os campos permitidos de `cmdline`. Ele não muda permissões da aplicação; falta de acesso aos arquivos deixa detalhes desconhecidos. `--system` executa como o usuário dedicado `java-assessment`, não como root.
- `snapshotRoot` permite reutilizar o snapshot do coletor WebLogic anterior. A fonte e o timestamp original permanecem em cada linha. Não roda novamente WLST nem declara observação antiga como nova.

- WebLogic também tem descoberta direta de configuração: o processo fornece o domínio, `config/config.xml` referencia os módulos em `config/jdbc`, e o inventário distingue configuração encontrada de observação do pool. Isso dispensa `snapshotRoot` para listar datasources configurados.
- Java legado sem versão/fabricante no arquivo `release` usa o manifesto de `jre/lib/rt.jar` (JDK) ou `lib/rt.jar` (JRE). Exige título `Java Runtime Environment` e versão de implementação explícita; não usa nome da pasta, versão de especificação nem `Created-By`. É metadado básico da instalação, lido mesmo quando a inspeção de arquivos de aplicação está desativada. Não executa o Java descoberto. Limites: arquivo de até 128 MiB, índice de até 32.768 entradas/3 MiB e manifesto de até 32 KiB, compartilhando os limites globais de leitura, arquivos e tempo. Exclusões são respeitadas e a origem fica em `runtimeMetadataSource`.
- Em kernels sem `MemAvailable`, o campo fica desconhecido. `memoryFreeBytes` é preservado separadamente; não é usado como estimativa de memória disponível.

## Proteções adicionadas na 2.0.2

A enumeração de diretórios é gradual. Java 7+ usa `DirectoryStream`, carregado por reflexão para manter o bytecode Java 6. No Java 6/Linux, usa `/usr/bin/find` sem interpolar caminhos no comando, com saída separada por NUL, fila de oito entradas, entrada de até 4096 bytes, memória virtual do subprocesso limitada a 64 MiB, CPU a cinco segundos e espera sem dados a cinco segundos, respeitando também o prazo global. Um limite ou falha produz cobertura parcial; não carrega listas ilimitadas no heap. Esse subprocesso é apenas de leitura. Seus recursos e a memória nativa da JVM são adicionais aos 96 MiB do heap. Diretórios em processamento podem manter leitores abertos até a profundidade limitada da varredura.

O atualizador não inicia sua JVM quando não há pedido nem journal. Suas JVMs usam a mesma opção local de aleatoriedade não bloqueante do launcher; nenhum Java de aplicação ou arquivo global `java.security` é alterado. Um timeout de heartbeat mantém o journal para retentativa; uma reprovação real do código ainda restaura a versão anterior. O loop SysV preserva os últimos 4096 bytes do diagnóstico não vazio, inclusive o resultado final.

Esta versão muda os scripts e o helper: requer uma execução do instalador completo com `--system-root`. O marcador local `update-v2` e o mínimo assinado `minUpdater=2` impedem oferecer esse pacote a um executor antigo. A troca isolada do JAR não habilita a nova geração. Atualizações futuras que preservem os arquivos de suporte podem voltar a usar o dashboard.

## Comunicação e reenvio

APIs: `POST /api/agent/heartbeat`, `GET /api/agent/poll?hostId=...`, `POST /api/agent/results`, com Bearer token por host. Conexões têm timeouts, redirects são recusados, respostas limitadas e resultado limitado a 2 MiB.

O resultado é gravado atomicamente como `pending-<eventId>.json` antes do envio. Em falhas, permanece para reenvio do mesmo evento, mantendo idempotência no servidor. Um resultado só sai da fila após confirmação `accepted=true` e o mesmo `eventId`. Uma falha em envio pendente impede aceitar novo job naquele ciclo. São enviados até três pendentes antes do poll. `latest-snapshot.json` e `last-ack.json` ficam disponíveis para diagnóstico local, protegidos pelo umask 077.

Medições: `durationMillis`, `filesVisited`, `bytesRead`, tempo de CPU do processo quando disponível, maior heap observado durante amostragem e `VmHWM` do processo quando disponível. As medições não incluem o tempo de upload, e o heap é uma amostra, não um profiler contínuo.

## Cadastro e diagnóstico autônomos

O instalador chama as seguintes interfaces internas, sem expor segredos em argumentos:

```sh
./agent-tool.sh register /caminho/conf/enrollment.properties /caminho/conf/collector.properties
./agent-tool.sh diagnose /caminho/conf/collector.properties
./agent-tool.sh events /caminho/conf/collector.properties /caminho/logs/install-events.json
```

`enrollment.properties` precisa pertencer ao usuário do coletor e ter modo 600/400. Campos: `serverUrl`, `enrollmentToken`, `installationIdPath`, `spoolDirectory`, `tlsProvider=BCJSSE`, `tlsCaFile`. `hostname`, `snapshotRoot` e `logDirectory` são opcionais. Não guarde esse arquivo após concluir o registro; o instalador o remove. A credencial de operação recebida fica somente em `collector.properties` (600). Um UUID persistido antes da primeira tentativa permite recuperar a mesma identidade quando uma resposta de cadastro se perde. O cadastro usa bloqueio de arquivo e nunca substitui um arquivo operacional já existente: valida seu acesso e o preserva.

`diagnose` consulta `/health`, envia heartbeat autenticado e obtém configurações por uma API que não reserva tarefas. Verifica leitura e travessia **da entrada de cada caminho configurado**, sem varrer a árvore; descendentes são verificados durante o assessment. Preserva `logs/permission-check.json` e registra um evento no dashboard. Caminhos inacessíveis geram aviso de cobertura; falhas de autenticação/TLS/conectividade retornam código diferente de zero.

`events` recebe um array JSON de até 30 eventos (`eventId`, `time`, `stage`, `status`, `message`), ou objeto com propriedade `events`. Mensagens têm até 300 caracteres, sem controles, e devem ser textos diagnósticos fixos. A ferramenta recusa o token atual dentro de um evento. Não imprime corpo de respostas, tokens ou exceções brutas; falhas usam códigos estáveis, como `HTTP_401`, `DASHBOARD_UNREACHABLE` e `TLS_VALIDATION_OR_NEGOTIATION_FAILED`.

A opção TLS do pacote usa autoridades públicas confiáveis. Certificados privados do cliente requerem inclusão explícita da CA pública apropriada em `conf/ca.pem`; nunca usar certificados do servidor como atalhos para ignorar validação. A chave privada do dashboard não faz parte do pacote. A data/hora local precisa estar correta. O monitor deve manter o certificado HTTPS renovado.

## Testes isolados

Com JDK 6 e as dependências em `lib/`:

```sh
javac -encoding UTF-8 -cp 'lib/*' -d classes shared/Json.java collector/*.java
java -Xmx64m -cp 'classes:lib/*' assessment.CollectorTest
java -Xmx64m -cp 'classes:lib/*' assessment.ProcessLaunchTest
java -Xmx64m -cp 'classes:lib/*' assessment.JdkInventoryTest
java -Xmx64m -cp 'classes:lib/*' assessment.CollectorSweepTest
java -Xmx64m -cp 'classes:lib/*' assessment.SpringDataSourceTest
java -Xmx64m -cp 'classes:lib/*' assessment.ArchiveMetadataTest
java -Xmx64m -cp 'classes:lib/*' assessment.AgentToolTest
java -Xmx64m -cp 'classes:lib/*' assessment.AgentTlsTest HTTPS_ORIGIN TRUST_CA_PEM UNRELATED_CA_PEM
```

`AgentToolTest` verifica perda de resposta/retentativa com o mesmo UUID, preservação da identidade, recusa de redirecionamento da origem no cadastro, ausência do código inicial no arquivo operacional, diagnóstico sem reserva de tarefas, relatório de permissões, limites e supressão de credencial em eventos. `AgentTlsTest` consulta apenas `/health`, verifica certificado e identidade corretos, rejeita nome incorreto e rejeita uma CA sem relação com o servidor. Os testes não são incluídos no JAR distribuído.

## Versão da entrega

Dashboard e agente usam uma versão comum, sem sufixo beta. O empacotador gera as constantes, recompila os dois e valida a versão do JAR, do ZIP e dos downloads. Uma VM continua reportando a versão real instalada até receber o pacote novo. Para atualizar a instalação root mantendo a identidade, baixe o ZIP do ambiente, extraia em uma pasta nova e execute `sudo ./install.sh --system-root`.
