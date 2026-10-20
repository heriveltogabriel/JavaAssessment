# Exportação de inventário para análise

Funcionalidade em desenvolvimento; os instaladores 2.1.14 publicados permanecem inalterados.

Em **Administração → Exportar inventário**, o administrador pode preencher uma identificação do levantamento ou cliente e clicar em **Exportar inventário em ZIP**. A identificação é opcional e deve ter até 120 caracteres. O arquivo gerado contém todo o inventário atual, independentemente de ambiente, máquina, busca ou filtros abertos nas demais telas. Não há opção de baixar JSON ou CSV avulso nessa aba. Os relatórios PDF/CSV existentes continuam disponíveis.

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

## Formato 1

O ZIP usa UTF-8 e contém dez arquivos:

| Arquivo | Conteúdo |
| --- | --- |
| `manifest.json` | `format: java-assessment-inventory`, `formatVersion: 1`, `dashboardVersion`, `exportedAt` em UTC, `label`, `scope: all-current-inventory`, contagens por coleção e lista das coleções |
| `environments.json` | Lista de ambientes |
| `hosts.json` | Lista de máquinas, recursos e cobertura da coleta |
| `runtimes.json` | Lista de instalações Java |
| `processes.json` | Lista de processos Java |
| `servers.json` | Lista de servidores Java |
| `libraries.json` | Lista de bibliotecas |
| `applications.json` | Lista de aplicações |
| `datasources.json` | Lista de datasources JDBC |
| `LEIA-ME.txt` | Orientações sobre interpretação, vínculos e compartilhamento |

Os JSONs são internos ao pacote, para facilitar seu consumo pela futura aplicação. Cada coleção é uma lista, inclusive quando vazia. `hostId` referencia `hosts.id`; `hosts.environmentId` referencia `environments.id` ou é vazio para uma máquina sem ambiente. IDs de recursos são locais à máquina e à coleção: use a chave composta `(coleção, hostId, id)`. Os vínculos com aplicações e servidores são preservados em seus campos e em `associations`. A ausência de um recurso vinculado pode refletir limites da coleta original; não significa um registro corrompido.

A identificação do cliente é informativa, não um identificador autenticado de origem. Não una inventários de clientes diferentes usando somente o ID da máquina. O consumidor deve validar o formato e as relações antes de analisar. O manifesto inclui contagens; não é uma assinatura criptográfica.

## Consistência e limites

O endpoint `GET /api/admin/inventory-export?label=...` exige sessão de administrador. Uma leitura SQLite consistente abrange todas as coleções. A geração percorre os registros incrementalmente, escreve em temporário privado e só entrega o arquivo após concluir e conferir novamente a autorização. O temporário é removido em sucesso ou falha. A resposta usa `application/zip` e `Cache-Control: no-store`.

Há limite de uma exportação/relatório por vez, um milhão de registros somados e 512 MiB de conteúdo descompactado. Ao exceder o limite ou ocorrer falha, a operação retorna erro, sem entregar um pacote parcial. Os limites não truncam silenciosamente a coleta exportada. O arquivo representa o estado atual; não contém histórico de coletas anteriores.

Validação automatizada: `InventoryArchiveTest`, `InventoryArchiveHttpTest` e `tests/inventory-export-ui.test.cjs`, registrados em `ops/test-source.sh`.
