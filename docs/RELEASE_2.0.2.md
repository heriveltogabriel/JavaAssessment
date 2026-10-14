# Java Assessment 2.0.2

Dashboard e pacote do agente usam a mesma versão completa, sem o sufixo Beta. Uma máquina já instalada continua exibindo a versão efetivamente informada pelo agente até ser atualizada.

## Funcionalidades desde a base inicial

- Downloads identificam ambiente e versão real. Exportação em CSV único com seleção de ambiente ou máquina.
- Inventário inclui JVMs em uso mesmo sem compilador, com evidência e associação à aplicação.
- Contas individuais, Administrador e Leitura, troca de senha inicial, redefinição protegida e histórico de administração.
- Ações de máquina, revogação, remoção do inventário e desinstalação remota protegida.
- Atualização assinada por HTTPS, individual ou por ambiente, com executor separado, backup, validação e restauração em falha de código.
- Work requests dedicado no menu lateral, linha do tempo por máquina, acompanhamento persistente e filtros recolhíveis.
- Datasources por configuração em Spring Boot, Tomcat, JBoss/WildFly standalone e WebLogic, nos formatos suportados.
- Interface com usuário, informações e recarregamento na barra superior e versão completa na lateral.

## Correções do agente

Diretórios são percorridos gradualmente, com limites de arquivos, leitura e tempo. Caminhos visitados usam identificadores SHA-256 de tamanho fixo. O coletor usa heap máximo de 96 MiB; a memória total do processo é maior.

O atualizador evita iniciar uma JVM quando não há pedido nem journal. Usa aleatoriedade não bloqueante nas suas próprias JVMs, preserva diagnósticos e distingue falha de código de ausência de confirmação. Timeout de heartbeat mantém o estado para nova tentativa; falha real do novo código pode restaurar a versão anterior.

O executor geração 2 é necessário nesta entrega. A passagem de instalações antigas para 2.0.2 requer o instalador completo com `--system-root` uma vez. A identidade e o ambiente existentes são preservados. Não troque apenas o JAR para contornar essa exigência.

## Validação e limites

Os fontes usam bytecode Java 6 para o coletor e Java 17 para o servidor. Há testes locais de descoberta, JDBC, limites, identidade, autenticação, exportação, assinatura, elegibilidade, atualização, rollback, perda de confirmação e instalador.

Esta entrega ainda exige homologação operacional em Java 6 real e em Linux/JVM de 32 bits. A repetição do cenário pesado com caminhos longos foi adiada; não há conclusão de sucesso para esse cenário na 2.0.2. Tempo e consumo devem ser medidos em uma amostra do cliente antes da expansão.

O empacotador do repositório também verifica o catálogo público em um clone independente, incluindo recusa de assinatura alterada, arquivos ausentes e entradas inesperadas. A chave privada permanece fora do repositório.

Os arquivos em `release/agent-updates/2.0.2` e o agente genérico são entradas públicas da entrega. Não altere seus bytes assinados para publicar outra versão. O histórico de reservas em `release/dashboard.json` registra números já usados; não reutilize versões de tentativas anteriores.
