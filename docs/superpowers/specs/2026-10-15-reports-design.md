# Relatórios de inventário — desenho aprovado

O usuário aprovou modelos com filtros e colunas ajustáveis, PDF e CSV, e autorizou implementar uma nova versão local para testar no dia seguinte. JDBC deve incluir datasource, driver, classe e versão do driver. Não incluir data da coleta nem cobertura; a data de geração do documento é apenas metadado da exportação.

## Comportamento

Menu lateral Relatórios, acessível a Administrador e Leitura. Modelos Máquinas, Java, Java Servers, JDBC e Resumo por ambiente. O último inventário disponível é a fonte; nenhuma coleta é disparada. Filtros combináveis por ambiente, máquina, SO, arquitetura, versão Java, produto/versão de servidor, datasource, driver e versão do driver. Texto usa comparação contém literal sem diferenciar maiúsculas; versões usam igualdade. Produto e versão devem corresponder ao mesmo registro. Os filtros JDBC devem corresponder ao mesmo datasource. Nos modelos de recursos, o filtro atua também na linha retornada; filtros de outros recursos delimitam máquinas, sem inventar vínculo entre um datasource e outro servidor da mesma máquina.

Cada linha representa uma máquina, instalação Java, servidor ou datasource conforme o modelo. Contar máquinas por identidade distinta, não por número de recursos. Resumo por ambiente agrupa os resultados. Mostrar indicadores de máquinas, ambientes e registros. Tabela paginada, seleção de colunas, filtros recolhíveis, estados de vazio/carregando/erro. Exportação considera todos os resultados, não somente a página. Campos permitidos excluem URLs JDBC, credenciais, tokens e configuração livre.

CSV UTF-8 com BOM, escaping de aspas/quebras e neutralização de fórmulas. PDF real baixável com título, filtros, totais, páginas, colunas selecionadas e cabeçalhos repetidos. Documento independente de rede externa e serviços de terceiros. Dividir muitas colunas em seções de no máximo cinco para legibilidade; texto longo quebra em linhas/páginas, sem cortar dados silenciosamente. Português preservado. Exportação e tela usam a mesma consulta; revision da consulta impede exportar silenciosamente outro inventário quando dados mudaram.

## Implementação e limites

Consultas SQL parametrizadas em Reports.java, sem produto cartesiano de Java/servidores/JDBC. Snapshot de leitura consistente do SQLite. Limite explícito de 10 mil registros por relatório e tamanho limitado por célula; exceder retorna orientação para refinar filtros. API retorna apenas uma página. Exportações compartilham o limitador existente. Classes separadas para consultas/CSV e geração PDF; módulo JS e CSS próprios. Não alterar coletor nem dados das aplicações. Preparar nova release pelo processo existente, preservando 2.0.13; não publicar nem atualizar máquinas durante este trabalho.

## Validação

Fixtures SQLite com múltiplos servidores/versões e datasources por máquina, nomes com acentos, campos ausentes, ambiente sem inventário, mais de uma página e entradas maliciosas. Verificar contagens distintas, pares corretos, CSV completo, revisão expirada, papéis e sessão. Renderizar PDF de várias páginas e colunas; verificar texto, números e layout. Navegador desktop/mobile e navegação do inventário. Verificar checksums internos e externos do candidato e do instalador final.
