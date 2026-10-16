# Repetir a validação JDBC nas máquinas de laboratório

Execute no Terminal do **Mac**, na cópia deste projeto. Não é um instalador nem substitui o agente. Os comandos já foram executados com sucesso nas duas máquinas em 2026-10-16.

```bash
cd "/Users/heriveltosantos/Documents/Testes Pyton/JavaAssessment"

python3 ops/run-jdbc-discovery-probe.py \
  --host 157.151.0.69 \
  --jar output/jdbc-discovery/probe-n23j2oks/jdbc-discovery-probe.jar

python3 ops/run-jdbc-discovery-probe.py \
  --host 157.151.15.169 \
  --jar output/jdbc-discovery/probe-n23j2oks/jdbc-discovery-probe.jar
```

Pré-requisitos: VMs ligadas, SSH acessível, chave `~/OneDrive/id_rsa`, hosts já verificados em known_hosts e sudo não interativo para o usuário opc. Use `--identity /caminho/da/chave` se necessário. O wrapper aceita somente essas duas VMs e verifica seu hostname. Mantém verificação de chave SSH; opções legadas ficam restritas à RHEL5.

Resultado esperado: `JDBC_ENDPOINT_TESTS=PASS checks=61`, `PROBE_COMPLETE=ISOLATED_NO_UPLOAD` e caminho da evidência privada no Mac. Confira `result.json`; ausência de host/IP em H2/Derby local é correta. As duas coletas reais usam apenas caminhos vinculados aos processos em execução, sem percorrer toda a máquina. O probe tem heap 96 MiB e prazo supervisionado, não registra nem envia resultados ao dashboard. Não distribua esse JAR como agente ou instalador.

Para recompilar após alterações nos fontes, usando as ferramentas locais deste laboratório:

```bash
python3 ops/build-jdbc-discovery-probe.py \
  --java-home /private/tmp/assessment-jdk17/jdk-17.0.20.1+1/Contents/Home \
  --ecj /private/tmp/assessment-uninstall-ecj-3.33.0.jar
```

O build imprime um **novo** caminho de JAR e SHA-256; substitua o valor de `--jar` nos comandos anteriores. Esses caminhos de compilador são locais e temporários; em outra máquina, informe um JDK e ECJ compatíveis disponíveis.

Em falha de SSH ou validação, consulte `failure.txt` e `ssh.log` na pasta de evidências impressa. Não precisa limpar nem reinstalar o agente para repetir o probe; cada execução usa diretório próprio. A aplicação precisa estar em execução para a descoberta solicitada. Os arquivos do probe permanecem privados para investigação, sem alteração dos pacotes publicados.

Veja [resultados e limites](../validation/jdbc-discovery-2026-10-16.md).
