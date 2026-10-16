# Recuperar descoberta de datasources Tomcat e JBoss

Pedido: investigar desaparecimento dos datasources de Desenvolvimento e corrigir o necessário. Verificar o coletor, ingestão e contagens; comparar artefatos e fixtures locais disponíveis. Não inferir estado atual das VMs a partir de fixtures. Operação remota permanece sujeita às permissões da sessão; preparar publicação manual se não houver acesso permitido.

## Regra
Descobrir recursos JDBC de configuração em Tomcat e JBoss/WildFly com origem e horário, mantendo distinção entre configuração e uso em runtime. Não confundir um driver JAR com datasource, não afirmar conexão ativa e não coletar credenciais/URLs sensíveis. Metadados XML são lidos com limites existentes, entidades externas/DTD/rede desabilitadas e sem executar código descoberto. Deduplicar recursos por origem e contexto e associar processo/aplicação quando a evidência permitir.

Reproduzir primeiro o caminho ausente com configuração sintética realista. Cobrir Tomcat conf/server.xml, conf/context.xml e descritores de contexto e JBoss standalone/configuration ou arquivo indicado pelo processo conforme a implementação existente permitir. Respeitar excludes e budgets, identificar configurações não efetivas como observações. Inventário vazio real continua vazio: não mascarar falha mantendo contagem antiga como atual. Investigar suporte anterior e regressões de build antes de ampliar parser.

## Validação
Fixtures com datasource real (sem banco externo), configurações sem datasource, duplicações, namespaces, XA quando suportado, XML malformado, XXE, limites/exclusões e senha-canário que nunca pode sair no resultado. Testar envio autenticado, armazenamento e overview global/ambiente/máquina. Documentar causa confirmada, limites e necessidade de atualizar agente/rodar nova coleta para repovoar o painel. Publicar apenas pacote após checks completos.
