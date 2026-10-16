# Runtime Java sem compilador — coletor 1.5.1

A instrução do usuário de 13/10/2026 substitui a exclusão anterior de todo JRE: uma JVM da aplicação executando comprova um runtime Java, mesmo sem `javac`. O caso confirmado foi OpenJDK 21 em EL9/aarch64, na máquina `brunods-dev-vnic`, com `bin/java` presente e `bin/javac` ausente.

## Comportamento

- JDKs com compilador continuam aparecendo por descoberta, com `kind:JDK`.
- Runtimes sem compilador entram quando há evidência de uso por aplicação, com `kind:JRE` e `inUse:true`.
- Várias JVMs usando o mesmo Java home canônico contam como uma instalação. Processos e aplicações continuam separados.
- Runtimes sem evidência de uso e o Java privado exclusivo do coletor seguem excluídos.
- JVMs do coletor, diagnóstico, probe e executor de desinstalação, identificadas pelas classes e caminhos do próprio agente, não são tratadas como aplicações.
- Observações de snapshots mantêm o timestamp original. A indicação de uso descreve a evidência coletada; não verifica a saúde da aplicação.

O servidor aplica o mesmo critério ao armazenamento e ao resumo do assessment. Valida propriedade do host, IDs e limites antes de filtrar qualquer registro. Métricas, grupos de versão, CSV e JSON incluem os runtimes aceitos. Não há alteração de esquema nem reconstrução de instalações a partir do caminho de uma aplicação antiga: a próxima coleta do agente novo produz o registro correto.

## Verificação local

`RuntimeInventoryTest` reproduz OpenJDK21/EL9/aarch64 sem compilador com duas JVMs da classe App. Confirma uma instalação Java21 em uso, arquitetura aarch64, duas aplicações vinculadas e nenhuma duplicata. O teste falhou no coletor1.5.0 e passou após a mudança.

`RuntimeInventoryHttpTest` envia esse snapshot real de fixture pela API HTTP ao SQLite. Verifica registro por pacote de ambiente, validação de propriedade, atomicidade, resumo/contagem1, grupo Java21, CSV/JSON, reinício e idempotência. A versão anterior do servidor descarta esse runtime; a versão nova o preserva.

O navegador usa o servidor Java e SQLite reais em loopback: contador1, detalhes JRE/versão/caminho, duas aplicações e a mesma contagem na máquina, em desktop/mobile. As regressões de JDK6/17/21, processos, arquivos, banco, CSV, pacote e desinstalação também são executadas localmente. Dois testes antigos dependiam do caminho temporário não canônico do macOS; sua fixture foi normalizada sem alterar o comportamento de coleta.

Evidências em `output/runtime-without-compiler/evidence/`. A compatibilidade do coletor é verificada por compilação ECJ para bytecode Java6 (major50); os testes executam no JDK17. A instalação real na VM do usuário permanece pendente.

## Aplicar a atualização

Primeiro publique o dashboard e o novo ZIP disponível para download. O publicador manual usa a baseline da publicação CSV confirmada em 13/10/2026:

```sh
python3 "/Users/heriveltosantos/Documents/Testes Pyton/jms-teste-macos/assessment-pilot/output/runtime-without-compiler/deployment/publish-dashboard.py"
```

O procedimento preserva banco, configuração, CA e identidades, com backup e recuperação do código em caso de falha. Ele atualiza o pacote oferecido para download; não instala agentes nas máquinas.

Depois entre novamente no dashboard, baixe o agente1.5.1 pelo ambiente da máquina, copie o ZIP e extraia em uma pasta nova. Exemplo para Homologação:

```sh
umask 077
PASTA=$(mktemp -d "$HOME/assessment-installer-XXXXXX")
unzip assessment-homologacao-1.5.1.zip -d "$PASTA"
cd "$PASTA"
chmod +x install.sh
sudo ./install.sh --system-root
```

Esse comando atualiza uma instalação root no mesmo dashboard, preservando cadastro e credencial; não exige desinstalar antes. Após confirmar registro/heartbeat, solicite um novo assessment. Com a JVM da App rodando, o Java sem compilador entrará na contagem.

Os artefatos desta entrega ficam em `output/runtime-without-compiler/packages/`, separados das versões anteriores. A publicação e a instalação são manuais nesta sessão.
