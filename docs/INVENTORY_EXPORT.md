# Exportação de inventário para análise

Disponível na versão 2.1.16 revisada. Por solicitação explícita do operador, os pacotes dessa versão foram substituídos para oferecer JSON/CSV em arquivo único; a versão original permanece no histórico Git.

Em **Administração → Exportar inventário**, o administrador pode preencher uma identificação do levantamento ou cliente selecionar **JSON** ou **CSV** e clicar em **Exportar inventário em ZIP**. A identificação é opcional e deve ter até 120 caracteres. O arquivo gerado contém todo o inventário atual, independentemente de ambiente, máquina, busca ou filtros abertos nas demais telas. O download é sempre um ZIP com apenas um arquivo de dados no formato selecionado. Os relatórios PDF/CSV existentes continuam disponíveis.

O objetivo é permitir que uma aplicação separada faça pesquisas e levantamentos com os dados coletados. Este dashboard não importa o pacote. O arquivo não substitui um backup e não contém dados suficientes para restaurar o dashboard ou suas credenciais.

## Conteúdo técnico

- Ambientes e máquinas: nomes, FQDN, IPs, sistema operacional, distribuição, kernel, arquitetura, CPU, memória, discos, versão do agente e de sua JVM, datas de contato/coleta e observações/estatísticas da coleta atual.
- Instalações Java: caminho, fornecedor, versão, uso observado e divergências entre versão em disco e em execução.
- Processos e aplicações: PID, classe principal, JAR de entrada, framework, versões e associações.
- Servidores: tipo, versão, distribuição, patches, domínio, caminhos e evidências.
- Bibliotecas e JDBC: datasources, classe do driver, JAR, versão, candidatos, decisões por PID, JNDI, destinos configurados de banco, pools quando observados, fontes e motivos da identificação.
- Situação de suporte calculada na exportação, quando disponível no catálogo do dashboard, acompanhada da data de referência e fontes. Não representa uma nova consulta ao fabricante.

Valores desconhecidos permanecem desconhecidos. O pacote não completa lacunas da coleta. Versão do driver e versão do banco são campos diferentes. Um candidato a driver ou destino configurado não comprova carregamento efetivo nem conexão ativa. A aplicação de análise deve preservar os estados de identificação e as evidências, inclusive por processo.

## Dados excluídos

O exportador usa listas explícitas de campos permitidos, inclusive em objetos aninhados. Não exporta tabelas de usuários, auditoria de acesso, identidades, códigos de cadastro, tokens, hashes de autenticação, chaves, certificados, configurações operacionais, variáveis de ambiente, argumentos/comandos completos de processos, URLs JDBC brutas, conteúdo de arquivos, histórico de solicitações ou o banco SQLite. Campos novos não entram automaticamente: precisam ser revisados e incluídos no formato.

Textos técnicos permitidos passam também por remoção de padrões de credenciais, como senha/token atribuídos, autenticação em URLs, cabeçalhos Basic/Bearer e blocos de chaves privadas. Isso complementa a seleção de campos; não torna o inventário anônimo. Nomes de máquinas, IPs, caminhos e destinos de banco identificam infraestrutura. Compartilhe somente com pessoas autorizadas. Evite informações pessoais ou segredos na identificação livre do levantamento.

## Formato 2 — um único arquivo

O ZIP usa UTF-8 e contém **somente `inventario.json` ou `inventario.csv`**, conforme a escolha. A versão do formato de dados passou para 2; a versão do produto continua 2.1.16.

### JSON

Um documento com duas propriedades: `metadata` e `data`. `metadata` contém `format: java-assessment-inventory`, `formatVersion: 2`, `dashboardVersion`, `exportedAt` em UTC, `label`, `scope: all-current-inventory`, contagens e lista das coleções. `data` contém `environments`, `hosts`, `runtimes`, `processes`, `servers`, `libraries`, `applications` e `datasources`. Cada coleção é uma lista, inclusive quando vazia. As evidências e associações permanecem estruturadas, sem arquivos auxiliares.

### CSV

CSV UTF-8 com BOM, separador vírgula, aspas duplicadas e linhas CRLF. A primeira linha é o cabeçalho. A primeira linha de dados tem `recordType=metadata` e o objeto completo de metadados em `exportMetadata`; assim, até uma exportação vazia preserva identificação e contagens. As linhas seguintes têm **uma linha por recurso**.

As colunas iniciais `recordType`, `machineName`, `environmentName` e `hostIpAddresses` identificam o contexto. As demais abrangem todos os campos técnicos permitidos, como `javaVersion`, `driverArtifact`, `driverVersion`, `databaseHost` e `domainName`. Campos que não se aplicam ficam vazios. Listas e objetos (candidatos, associações por PID, destinos múltiplos, suporte, cobertura) são JSON dentro de uma célula CSV, sem resumir ou cortar seus detalhes. Strings que poderiam iniciar fórmulas de planilha recebem apóstrofo de proteção; no JSON permanecem strings originais sanitizadas. Null e campo ausente são células vazias; para preservar essa distinção, use JSON.

### Vínculos e interpretação

`hostId` referencia `hosts.id`; `hosts.environmentId` referencia `environments.id` ou é vazio para máquina sem ambiente. IDs de recursos são locais à máquina e à coleção: use `(coleção, hostId, id)`. Os vínculos com aplicações e servidores permanecem em seus campos e em `associations`. A ausência de um recurso vinculado pode refletir limites da coleta original.

A identificação do cliente é informativa, não autenticada. Não una clientes usando somente o ID da máquina. O consumidor deve validar formato e relações. As contagens não constituem assinatura criptográfica. Não é cópia integral de todas as tabelas SQLite: dados de acesso e controle operacional continuam excluídos.

## Consistência e limites

O endpoint `GET /api/admin/inventory-export?format=json|csv&label=...` exige sessão de administrador. Sem `format`, o padrão é JSON; outros valores são rejeitados. Uma leitura SQLite consistente abrange todas as coleções. A geração percorre os registros incrementalmente, escreve em temporário privado e só entrega o arquivo após concluir e conferir novamente a autorização. O temporário é removido em sucesso ou falha. A resposta usa `application/zip` e `Cache-Control: no-store`.

Há limite de uma exportação/relatório por vez, um milhão de registros somados e 512 MiB de conteúdo descompactado. Ao exceder o limite ou ocorrer falha, a operação retorna erro, sem entregar um pacote parcial. Os limites não truncam silenciosamente a coleta exportada. O arquivo representa o estado atual; não contém histórico de coletas anteriores.

Validação automatizada: `InventoryArchiveTest`, `InventoryArchiveHttpTest` e `tests/inventory-export-ui.test.cjs`, registrados em `ops/test-source.sh`.
