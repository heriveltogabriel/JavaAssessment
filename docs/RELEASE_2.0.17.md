# Java Assessment 2.0.17 — Relatórios com contexto JDBC

Preparada localmente para validação. Dashboard e agente: 2.0.17; bridge interno: 2.0.16. A 2.0.15 permanece preservada. Nenhum dashboard ou agente remoto foi alterado.

## Informações disponíveis

| Relatório | Colunas padrão e contexto |
| --- | --- |
| Máquinas | Máquina, ambiente, IP, sistema operacional, versões Java, servidores e JDBC da máquina. |
| Java | Máquina, ambiente, IP, versão, Java home, fornecedor e JDBC da máquina. O JDBC não comprova dependência de cada instalação Java. |
| Java Servers | Máquina, ambiente, IP, produto, nome e versão do servidor, versão Java associada, sistema operacional e JDBC do servidor. Caminho, PID, Java home e JDBC da máquina podem ser adicionados em Colunas. |
| JDBC | Máquina, ambiente, IP, datasource/nome JNDI, banco, driver, versão e classe, servidores e aplicações associados, além do resumo JDBC. Tipo de recurso, estado, referência do driver, pool máximo configurado e SO são colunas adicionais. |
| Resumo por ambiente | Quantidades de máquinas, instalações Java, servidores e datasources, com os conjuntos JDBC distintos das máquinas filtradas. |

A coluna JDBC mantém **datasource — driver · versão** juntos, uma entrada por linha. Os títulos identificam o escopo no dashboard, no CSV e no PDF. O Java de um servidor vem da instalação cujo Java home corresponde ao registrado para esse servidor na mesma máquina. JDBC do servidor respeita as associações registradas, incluindo múltiplos processos/aplicações; recursos encontrados somente na mesma máquina não são atribuídos automaticamente ao servidor.

## Filtros JDBC

Os filtros estão separados em **Máquinas e servidores** e **JDBC e datasources**. Além de datasource e driver/versão, agora é possível pesquisar por:

- Presença de JDBC no inventário: com, sem ou ambos.
- Nome de datasource / nome JNDI, quando o coletor o registrou nesse campo.
- Banco, classe do driver e referência do driver.
- Servidor associado e aplicação associada.
- Tipo de recurso (por exemplo, DataSource ou XADataSource) e estado informado.

Os campos oferecem sugestões do inventário, limitadas a 100 valores por campo; também aceitam digitação. Recarregar atualiza as sugestões. Todos os critérios JDBC precisam corresponder ao mesmo datasource. No relatório Java Servers, precisam corresponder a um datasource associado ao servidor daquela linha. Nos demais modelos, os filtros gerais de Java/servidor delimitam as máquinas; os filtros de associação JDBC permitem especificar o vínculo registrado.

Inventários antigos podem não conter alguns campos. A classe registrada historicamente em driverName é aproveitada quando tem formato de classe; não é buscada a versão em um driver arbitrário da máquina. Estado de configuração e pool máximo não confirmam conectividade ou saúde. Não são incluídos data da coleta, cobertura, URLs JDBC, senhas ou tokens.

## Exportações e validação

PDF e CSV usam as mesmas colunas e filtros da consulta, incluindo todas as páginas. O PDF também informa o contexto das associações, a data de geração legível em UTC e a seção correta no rodapé de cada página. Os limites de 10.000 registros e 8.192 caracteres por célula continuam em vigor; resumos muito extensos pedem refinamento dos filtros.

Validação local: 41 verificações de consulta/HTTP, 32 verificações específicas de associação e enriquecimento e 2.147 verificações de PDF aprovadas. Navegador com SQLite real: cinco modelos com JDBC, novos filtros combinados, sugestões, CSV/PDF, leitor, recarregar, sessão e layout móvel. Revisão independente das consultas não encontrou problemas acionáveis. Pacotes finais: 24 verificações do candidato e 8 testes do instalador aprovados; checksums internos/externos e assinaturas conferidos para os dez perfis legados conhecidos. O builder concluiu as regressões existentes. Os resultados não substituem a conferência do inventário real.

## Teste no ambiente

```bash
python3 "/Users/heriveltosantos/Documents/Testes Pyton/JavaAssessment/output/dashboard-releases/2.0.17/deployment/publish-dashboard.py"
```

Depois de publicar e entrar novamente:

1. Em Relatórios → Java Servers, filtre WebLogic e confira IP, versão do servidor, Java e JDBC associado.
2. Em JDBC, combine banco, aplicação, classe e versão do driver; compare com a listagem de inventário.
3. Use as sugestões e o filtro de presença. Confira os cinco modelos e exporte PDF/CSV com as colunas desejadas.
4. Para ver datasources da máquina sem associação comprovada ao servidor, habilite **JDBC da máquina** em Colunas.

O instalador para um dashboard novo está em `output/dashboard-releases/2.0.17/installer/assessment-dashboard-installer-2.0.17.zip`.

## Agentes

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza as máquinas automaticamente. Os relatórios usam o inventário existente, sem exigir nova capacidade do coletor. Abra **Revisar agentes**: agentes V3 e perfis legados conhecidos a partir de 1.5.12 podem usar o painel quando forem elegíveis; versão numérica sozinha não comprova compatibilidade. Instalações desconhecidas ou sem atualizador funcional precisam do pacote configurado do ambiente com `--system-root`. Acompanhe o Work request e a versão final.

Linux/Java 32 bits e cenários de falha/reboot da migração continuam sem validação suficiente para liberação geral de compatibilidade.
