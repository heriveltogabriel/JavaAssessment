# Java Assessment 2.1.24

Entrega do coletor: mais drivers JDBC identificados. A versão 2.1.24 reúne as correções abaixo; o dashboard não ganha funcionalidade nova.

> **Atenção: atualize também os agentes.** O ganho está no agente. Publicar o dashboard não atualiza os agentes das máquinas. Abra **Revisar agentes** para conferir a versão e a elegibilidade e atualize pelo painel as instalações compatíveis. Quando o painel não oferecer a atualização, use o pacote configurado para o ambiente. Depois de atualizar, execute um novo assessment: inventários anteriores não são recalculados.

## O que mudou

**WebLogic: o driver deixa de ficar "INCOMPLETE".** A JVM limita o texto `java.rt.vmArgs` do hsperfdata a 1.024 bytes, e as linhas de comando do WebLogic passam disso. O coletor tratava todo processo nessa situação como incompleto, mesmo com o `/proc/<pid>/cmdline` íntegro e um único driver candidato. Na coleta de 26/10/2026 isso afetava 1.805 de 1.811 datasources INCOMPLETE. Agora a truncagem só bloqueia a decisão quando a linha de comando é opaca (`@argfile`) ou quando o ambiente do processo define `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` ou `JDK_JAVA_OPTIONS`, ou não pode ser lido.

**Configuração de domínio WebLogic atribuída ao servidor certo.** Um processo auxiliar iniciado dentro do diretório do domínio (por exemplo, o Derby) recebia como sua a configuração do domínio, e os datasources apareciam com o nome `org.apache.derby.drda.NetworkServerControl datasource` e sem servidor. Agora a configuração do domain home pertence ao servidor WebLogic em execução desse domínio (o de menor PID na máquina), com `domainPath` e `domainName`. Endpoints que o `config.xml` já declara não viram linha nova. Sem servidor WebLogic ativo naquele domínio, nada é atribuído ao processo auxiliar.

**Tomcat com ojdbc14.** A família `ojdbc14` passa a ser reconhecida e a versão é lida de manifestos no formato "Oracle JDBC Driver version - X". Se o manifesto tiver outro texto, a versão continua indisponível e a família é informada.

**Diagnóstico.** Quando a resolução continua INCOMPLETE, o texto do motivo lista até quatro motivos distintos (separados por "| também:"), não só o primeiro.

## Resultado esperado e limites

Na coleta de 26/10/2026, 27% dos datasources Oracle tinham versão de driver. Estimativa, não medição: perto de 90% depois da atualização dos agentes. O valor só pode ser confirmado com uma coleta nova. Permanecem sem driver os datasources só de configuração (`/etc/config/*.yml`) sem processo associado.

Cada linha atribuída ao domínio WebLogic liga a um único servidor (o de menor PID); as do `config.xml` ligam a todos. Os endpoints vindos só do arquivo JSON não herdam o driver das demais linhas do domínio.

## Validação

Detalhes e comandos em [validação do coletor 2.1.24](validation/collector-driver-resolution.md). Suíte completa de origem aprovada, testes novos que falham no código anterior e prova em Linux real (JVM 8 em contêiner, linha de comando de 2.715 bytes). Não houve teste em máquina do cliente, nem do caminho de atualização do agente 2.1.20 ou 2.1.22 para 2.1.24 pelo painel: isso fica para o build, com os canários do processo de release.
