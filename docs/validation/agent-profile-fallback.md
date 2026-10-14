# Tentativa sequencial de perfis legados conhecidos

`tests/build-agent-profile-fixtures.py` deriva uma fixture separada de `http-fixture-final3`. Mantém o binário original 1.5.12 e o pin de teste. O primeiro perfil conhecido tem um `poll.sh` diferente; o segundo corresponde ao suporte original. Cada ponte tem manifesto V1 e assinatura próprios, e a ordem dos dois perfis é fixada pelo índice assinado.

`AgentUpdateProfileFallbackTest` carrega o worker do JAR original 1.5.12 em `URLClassLoader` isolado, sem herdar classes do produto atual. O worker usa HTTP autenticado de loopback contra o servidor real e seu SQLite. As verificações criptográficas, downloads limitados, troca do JAR, journal, backup e limpeza após ACK são executados pelo worker original.

A validação local passou **109 verificações**:

- Suporte incompatível gera `INCOMPATIBLE_ASSETS` antes de probe, pausa ou troca do JAR.
- O servidor seleciona o segundo perfil assinado, cria outro filho persistente e o worker original instala a ponte com sucesso.
- O sucesso da ponte mantém a operação completa em andamento e não agenda assessment.
- Assinatura inválida, probe do candidato inválido, falha de serviço e probe da versão instalada inválido não selecionam outro perfil.
- Configuração, identidade, caminho da JVM, suporte local e snapshot permanecem preservados.

A evidência é escrita em `tmp/full-update/profile-fixture/profile-fallback-evidence.json`. O teste declara os adapters de serviço/probe e não comprova execução em Java 6 ou Linux real. A fixture completa final permanece intacta.

Para reproduzir em um diretório novo:

```bash
python3 -B tests/build-agent-profile-fixtures.py \
  --fixture tmp/full-update/http-fixture-final3 \
  --output tmp/full-update/profile-fixture-novo
```

Após compilar as fontes atuais, `TestAccounts`, `AgentUpdateRoutesTest`, `AgentUpdateMigrationHttpTest` e `AgentUpdateProfileFallbackTest` com JDK 17, execute:

```bash
"$JAVA_HOME/bin/java" -Xmx384m \
  -cp 'tmp/full-update/profile-test-classes:lib/*' \
  assessment.AgentUpdateProfileFallbackTest \
  tmp/full-update/profile-fixture-novo
```

O ambiente deve permitir abrir somente o servidor HTTP local utilizado pelo teste. Nenhuma chave de produção, VM ou serviço do sistema é necessário.
