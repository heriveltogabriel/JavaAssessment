# Java Assessment 2.0.3

## Versão do WebLogic

O coletor agora usa `weblogic.home` e `wls.home` de processos `weblogic.Server` para localizar `server/lib/weblogic.jar`, mesmo sem classpath explícito. A versão do manifesto alimenta o campo de versão do servidor e a versão do framework da aplicação associada, apresentados pelo dashboard existente e pelo CSV.

O caso reportado foi WebLogic 10.3.6.0 em RHEL 5: o JAR possui 23.553 entradas, acima do limite comum de 5.000. Uma leitura específica acessa somente o diretório central e o manifesto, com validação de cabeçalho, tamanho, duplicidade e CRC. Não carrega classes nem executa WebLogic, WLST ou JMX.

O heap do agente continua em 96 MiB. Permanecem os limites compartilhados de leitura de 8 MiB, 40 arquivos compactados, exclusões e duração da coleta. O limite específico do JAR WebLogic é 32.768 entradas e 4 MiB de diretório central; o manifesto é limitado a 64 KiB. Arquivos de aplicação comuns continuam com o limite anterior.

## Atualização

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza as máquinas automaticamente. Agentes 2.0.2 com atualização remota habilitada podem atualizar pelo painel; instalações anteriores precisam receber o pacote completo com os componentes de suporte atuais. Acompanhe a atualização em Work requests e confirme a versão informada pelo agente.

Dashboard e agente usam a versão 2.0.3. Publique o dashboard, solicite a atualização do agente e execute um novo assessment. O inventário já coletado não muda retroativamente.

Os arquivos de suporte exigidos pela atualização assinada são idênticos aos da 2.0.2. Agentes 2.0.2 com atualização remota habilitada podem receber o novo JAR pelo dashboard. A elegibilidade de instalações antigas depende dos arquivos e recursos locais informados por cada agente.

## Validação e limites

- Reprodução local de JAR com 23.553 entradas e propriedades de inicialização sem `-cp`.
- Versões 10.3.6.0, 12.2.1.4.0 e 14.1.2.0.0 em metadados de teste, com associação por processo e instalação.
- Arquivos inválidos, CRC incorreto, manifesto duplicado ou ausente, limites de tamanho e contagem, orçamento compartilhado, exclusões e inspeção desativada.
- Compilação das classes de produção para Java 6 (major 50), regressões com heap de 96 MiB e build integrado da entrega.
- Regressões de Tomcat, JBoss/WildFly, JDBC, inventário Java, CSV, autenticação e atualização de agentes.

Um teste isolado adicional, executado pelo usuário, confirmou a leitura do manifesto com resultado `CHECK=OK`. Os detalhes do ambiente e as medições dessa execução permanecem apenas no registro local de validação. O teste não instala o agente, não reinicia serviços e não envia inventário ao dashboard; a atualização do agente e um novo assessment são necessários para verificar o fluxo completo. Esse teste não comprova execução em todas as versões de Java/Linux nem em 32 bits.

A versão exibida é a declaração do manifesto do produto. Inventário de patches/PSUs não faz parte desta correção.
