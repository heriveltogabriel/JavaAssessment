# Fontes da versão 1.0 Beta

O ZIP de fontes inclui servidor, frontend, coletor, código de testes, instalador, bibliotecas fixadas, licenças e o ZIP genérico do agente. Ele exclui configurações privadas, inventário, logs, credenciais, resultados de máquinas e scripts antigos de publicação remota. O pacote contém apenas código de testes; evidências de ambientes reais e fixtures com dados não fazem parte da entrega.

Na raiz extraída, usando Python 3 e um JDK 17+ local:

```sh
python3 -B ops/prepare-beta-dependencies.py --check
python3 -B tests/beta-installer-test.py
python3 -B dashboard-installer/build.py --with-source
```

Para incluir o teste real de inicialização local, configure `JAVA_HOME` para o JDK de teste e execute:

```sh
BETA_INSTALLER_RUNTIME_TEST=1 python3 -B tests/beta-installer-test.py
```

Esse teste compila o servidor para Java 17, gera certificado, abre uma porta HTTPS somente em `127.0.0.1`, cria SQLite e verifica backup/restauração em diretórios temporários. Não executa `systemctl`, SSH nem atualização de uma máquina externa. O sistema deve permitir escutar no loopback. Os testes de permissões de conta e de unidade systemd usam simulação; o teste local não substitui a homologação em Enterprise Linux.

O build produz o instalador versionado e o nome de compatibilidade em `dist`, com SHA-256. `--output /caminho/pacote.zip` gera uma cópia isolada. `--source-output /caminho/fontes.zip` escolhe outro destino para o pacote de fontes. A preparação de dependências sem `--check` baixa apenas os JARs/POMs fixados do Maven Central e exige os SHA-256 já registrados no repositório.

Consulte `BETA_MIGRATION.md` para importação dos JSON legados, backup SQLite e retorno à versão anterior. A geração dos pacotes não instala nem atualiza o dashboard.
