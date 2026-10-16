# Java Assessment 2.0.23 — Aplicações Spring e JDBC

**Candidato de laboratório; não liberado para o cliente.** Preparado para teste local. Dashboard e agente: **2.0.23**; bridge interno: **2.0.22**. A versão 2.0.17 permanece preservada. As montagens locais 2.0.19 e 2.0.21 falharam na validação final de classificação/catálogo, foram descartadas como origens e permanecem preservadas sem publicação; use somente 2.0.23 para este teste. A preparação não publica o dashboard nem atualiza máquinas.

## Descoberta passiva

O coletor relaciona aplicações Spring Boot em execução a seus arquivos autorizados, sem executar classes da aplicação ou abrir conexões JDBC. A inspeção de arquivos (`inspectArchives`) precisa estar habilitada no assessment.

- Nome e versão da aplicação em manifesto/build-info, versão Spring Boot e bibliotecas de servidores embutidos.
- Configuração base e profiles em properties/YAML, com arquivos externos locais e parâmetros de inicialização permitidos.
- Datasource, produto do banco, classe e versão do driver quando a associação é identificável. Prefixos personalizados podem ser cadastrados explicitamente.
- Separação por processo: duas aplicações usando o mesmo JAR não têm seus datasources misturados.
- Configuração incompleta é identificada como parcial. URLs JDBC, senhas, tokens e ambiente bruto não entram no inventário.

Os profiles e valores encontrados são candidatos locais; não equivalem ao contexto efetivo do Spring. Config Server, Vault, imports remotos, YAML complexo, configurações programáticas e R2DBC não são resolvidos. Veja a [cobertura e os limites](SPRING_DISCOVERY.md).

## Versão do banco e integração opcional

A versão do driver e a versão do banco são campos separados. A versão real do banco não é deduzida do nome do JAR.

A [integração opcional](SPRING_INTEGRATION.md) fornece um exemplo de componente para a própria aplicação produzir um relatório local usando metadados de sua conexão JDBC. Exige habilitação explícita na aplicação e no coletor; não altera aplicações automaticamente. O coletor aceita somente relatórios recentes associados ao caminho, PID e início do processo configurado, com campos e tamanho limitados.

Esses dados aparecem como **Informado pela aplicação**, sem afirmar uma verificação independente de conectividade pelo coletor. O exemplo abre uma conexão pelo datasource da aplicação: configure os limites de conexão/pool e valide o impacto antes de habilitar.

## Dashboard e relatórios

Detalhes da aplicação mostram profiles, resolução da configuração e servidores embutidos como evidência de pacote. JDBC distingue configuração, observação e informação fornecida pela aplicação. Relatórios, filtros, CSV e PDF incluem versão do banco e sua origem, separadas da versão do driver. Inventários antigos continuam aceitos; campos ausentes permanecem desconhecidos.

## Validação e limites

Testes locais cobrem precedência, profiles, placeholders, prefixos extras, múltiplos processos, arquivos inválidos, privacidade, limites de leitura e um JAR esparso maior que 128 MiB. A integração cobre identidade, validade, mapeamento, orçamento de leitura e campos permitidos; o helper verifica fechamento de conexão, cache e desabilitação por padrão. A regressão de relatórios inclui filtros, associações, CSV/PDF, dados maliciosos e layout móvel.

O coletor conserva bytecode Java 6 e heap configurado em 96 MiB. Os testes desta alteração executam em JVM 17; compatibilidade de bytecode não substitui teste em Java 6 real. Spring Boot com banco real, Linux/Java 32 bits e todas as combinações de distribuição/JVM continuam sem validação nesta release. Não se declara compatibilidade universal.

O candidato final passou em 24 verificações independentes de pacote e oito testes do instalador. Assinaturas, hashes internos/externos, bytecode Java 6 e catálogo de 11 perfis legados conhecidos foram conferidos. A validação não acessou hosts nem substitui os testes operacionais.

## Testar a release

```bash
python3 "/Users/heriveltosantos/Documents/Testes Pyton/JavaAssessment/output/dashboard-releases/2.0.23/deployment/publish-dashboard.py"
```

1. Publique o dashboard e atualize uma máquina de teste com Spring Boot pelo painel quando elegível.
2. Solicite novo assessment com inspeção de arquivos. Confira aplicação, profiles, datasources e driver contra os arquivos autorizados.
3. Confira filtros JDBC e exportações CSV/PDF. Campo desconhecido não significa ausência de banco.
4. Se precisar da versão real do banco, habilite a integração opcional nessa aplicação e repita o assessment.

O instalador de um dashboard novo fica em `output/dashboard-releases/2.0.23/installer/assessment-dashboard-installer-2.0.23.zip`.

## Atualização dos agentes

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza as máquinas automaticamente. Esta capacidade exige agente **2.0.23** e um **novo assessment**; o inventário anterior não é recalculado.

Abra **Revisar agentes** e use a atualização pelo painel somente quando a instalação for elegível. O catálogo mantém os perfis conhecidos de migração a partir de 1.5.12 e o caminho V3; versão numérica sozinha não confirma compatibilidade. Instalações desconhecidas ou sem atualizador funcional precisam do pacote configurado para o ambiente, instalado com `--system-root`. Preserve cadastro, configuração e CA; acompanhe o resultado em Work requests.
