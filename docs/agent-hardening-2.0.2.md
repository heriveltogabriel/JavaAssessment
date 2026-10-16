# Correções do agente — 2.0.2

Dashboard e agente têm a mesma versão. A entrega foi preparada para validação e instalação controlada; não é uma certificação de todas as combinações Linux/Java.

## Correções

- Diretórios são lidos gradualmente, sem construir uma lista ilimitada antes da coleta. A reprodução com 250.000 nomes e orçamento de 100 itens passou com 64 MiB na preparação anterior. O registro de caminhos visitados agora usa identificadores SHA-256 de tamanho fixo, evitando reter caminhos completos longos.
- Timeout de heartbeat mantém versão, backup e journal para tentar confirmar novamente. Falha real do código ainda provoca restauração da versão anterior.
- O atualizador usa a opção de aleatoriedade não bloqueante somente em suas próprias JVMs. Não muda o Java das aplicações nem a configuração global.
- Sem pedido ou journal, o atualizador não inicia uma JVM. O loop SysV preserva o final do último diagnóstico não vazio; falhas locais passam a usar seu código específico.
- A cópia dedicada em `/opt/java-assessment-runtime` e os processos do atualizador deixam de aparecer como aplicações do cliente. Uma aplicação real que use esse Java continua no inventário.
- O catálogo assinado exige executor geração 2. A interface recusa a atualização por JAR quando o helper instalado ainda é geração 1 e orienta instalar o pacote completo.

## Validação e limites

Os testes usam JDK 17 de 64 bits no macOS ARM64, arquivos descartáveis, servidores HTTP/TLS em loopback e simulações das fronteiras Linux/root/systemd/SysV. O produto é compilado pelo ECJ com `source/target 1.6`; todas as classes distribuídas devem ter major version 50. O processo de release faz uma compilação independente e compara os resultados.

As regressões cobrem descoberta Java/JRE/JDK, Java 6 por metadados, Tomcat/JBoss/WebLogic, JDBC, exclusões, privacidade, limites, TLS, assinatura, elegibilidade, autenticação, atualização, rollback, perda de confirmação, reinstalação e preservação da identidade. O protocolo de enumeração legado inclui testes de nomes com espaços/quebras de linha, saída inválida, bloqueio e encerramento do subprocesso.

**Não houve execução desta entrega em Linux de 32 bits, JVM de 32 bits ou JVM Java 6 real.** O usuário adiou a homologação de 32 bits. O fallback Java 6 usa GNU find com limites próprios; um limite ou dependência indisponível resulta em aviso de cobertura parcial. A validação operacional em uma VM de cada família do cliente continua necessária antes da expansão.

O coletor inicia com `-Xms16m` e tem limite `-Xmx96m`, conforme solicitado. As sondagens leves continuam em 64 MiB; o worker de atualização já usa 96 MiB. Memória nativa, bibliotecas e subprocessos são adicionais; esse número não é um limite de RSS do serviço. Os testes locais de desempenho não devem ser extrapolados para discos ou kernels do cliente.

A versão local 2.0.1 foi reprovada após falta de memória no teste com 45 mil caminhos longos. Seus registros e pacotes foram preservados. A 2.0.2 inclui a correção dos identificadores e o heap de 96 MiB. **A repetição desse teste pesado foi dispensada a pedido do usuário**; as verificações normais de compilação e regressão continuam no processo de release. Isso não comprova a resolução daquele cenário de estresse.

## Publicação e instalação

No Mac, publique somente o dashboard:

```sh
python3 "/Users/heriveltosantos/Documents/Testes Pyton/jms-teste-macos/assessment-pilot/output/dashboard-releases/2.0.2/deployment/publish-dashboard.py"
```

Em cada máquina, baixe o ZIP **2.0.2** do ambiente no dashboard atualizado, copie-o e extraia em uma pasta privada nova. Na pasta extraída:

```sh
sudo /bin/sh ./install.sh --system-root
```

Essa passagem exige o instalador completo **uma vez**, porque o helper e os scripts também mudaram. A identidade e o ambiente existentes são preservados; o destino operacional continua `/opt/java-assessment-collector`. Não substitua apenas `collector.jar` e não apague journal ou pedidos em andamento. O pacote genérico `assessment-collector-2.0.2.zip` também pode atualizar uma instalação root existente, usando sua configuração salva; uma instalação nova precisa do pacote configurado pelo ambiente ou de cadastro explícito.

Se uma instalação antiga ainda usa Java privado dentro de `/opt/java-assessment-collector/runtime`, a cópia deve ser migrada para a localização separada antes da atualização. O procedimento específico do laboratório RHEL 5 é `ops/recuperar-atualizador-rhel5.sh`; não o aplique a outras topologias sem validar os seus pré-requisitos. O script histórico `ops/prepare-root-runtime.sh` passou a usar a localização externa para novas cópias do laboratório.

Confirme localmente, sem imprimir credenciais:

```sh
sudo unzip -p /opt/java-assessment-collector/collector.jar META-INF/MANIFEST.MF
sudo cat /opt/java-assessment-collector/conf/update-capability
sudo /bin/sh /opt/java-assessment-update/update-local.sh check
```

Resultados esperados: `Implementation-Version: 2.0.2`, marcador `update-v2` e check com código zero. Confirme o novo heartbeat no dashboard e execute um assessment para conferir aplicações e datasources. Atualizações futuras que mantiverem scripts/bibliotecas compatíveis podem voltar a ser solicitadas pelo dashboard.

Esta entrega não altera a memória do dashboard já instalado. O ajuste solicitado de `Xms=Xmx=1536m` tem procedimento operacional separado em `output/dashboard-memory/1536m/adjust-dashboard-memory.py`.
