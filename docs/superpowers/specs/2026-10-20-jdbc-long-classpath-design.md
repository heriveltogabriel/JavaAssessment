# JDBC: classpath longo e candidato visível

Aprovado na conversa: exibir JAR/versão provável quando há um único candidato compatível, preservar alternativas conflitantes e ampliar limites. Após autorização adicional na conversa, gerar a próxima versão e validar a atualização a partir da 2.1.12 instalada no cliente.

- ProcessLaunch: 1.024 caminhos por lista, com sinalização de descarte real (não pelo tamanho igual ao limite); argumentos/environ até 256 KiB. Resolver: 4.096 caminhos. Orçamento global de leitura/tempo preservado.
- PerfData: registrar truncamento por campo. Classpath parcial não substitui classpath explícito completo quando o prefixo de entradas completas coincide. Fontes divergentes e argumentos JVM truncados continuam limitados, com motivo específico. Não aumentar o limite da própria JVM via flags nem reiniciar aplicações.
- Interface: sem alterar o resultado persistido, mostrar um único candidato JAR+versão como Provável. Se existem associações por PID, todos devem apresentar o mesmo único candidato; nenhum PID ausente/divergente pode ser ignorado. Ambiguidades mostram alternativas. Suporte continua desconhecido quando não confirmado. Detalhes preservam o motivo de inspeção incompleta.
- Não promover presença em disco a confirmação de classe carregada. Nenhum argumento bruto ou segredo entra no inventário.
- Validar limites exatos, excesso, perfdata parcial/divergente, argumentos longos, WebLogic após a entrada 64, quatro PIDs, conflitos e escape HTML. Executar suíte completa e compilação ECJ Java 6 sem alterar versão.
