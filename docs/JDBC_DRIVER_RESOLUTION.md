# Versão do driver JDBC no WebLogic

A coleta passiva associa a classe configurada no datasource aos arquivos vinculados ao processo WebLogic em execução. A busca inclui classpath explícito, CLASSPATH do processo quando não há opção explícita, lib do domínio e referências locais Class-Path dos manifestos. Não executa scripts nem JARs, não injeta código na JVM e não abre conexão com o banco.

O Oracle JDBC é o cenário principal: ojdbc6/7/8/11, nomes personalizados e classes OracleDriver (atual/legada) e OracleXADataSource. O nome do arquivo sozinho não define a versão. O coletor verifica a presença da classe exata e lê metadados internos do manifesto ou Maven. Derby recebe tratamento próprio para normalizar seu formato de versão.

## Interpretar o resultado

- **Identificado em arquivo:** um candidato com versão interna identificada no escopo examinado. Não confirma a classe efetivamente carregada pela JVM.
- **Múltiplos candidatos:** mais de um arquivo contém a classe; caminhos e versões aparecem nos detalhes. Não escolhe pela ordem alfabética nem pelo nome do JAR.
- **Inspeção incompleta:** limite, exclusão, permissão ou referência não suportada impediu concluir a busca. Candidatos encontrados continuam disponíveis.
- **Versão indisponível:** a classe foi encontrada, mas a versão está ausente ou seus metadados são conflitantes.
- **Driver não localizado:** nenhum candidato foi encontrado no escopo examinado.

Na tabela Bibliotecas Java, a partir do dashboard 2.1.4, Arquivo JAR e Versão ficam em colunas separadas. O nome vem do arquivo identificado; o caminho completo continua nos detalhes.

Relatórios JDBC oferecem Arquivo do driver, Identificação do driver, Evidência da versão e Drivers candidatos. Os resumos JDBC dos demais modelos e exportações PDF/CSV incluem as evidências; o CSV único do inventário também recebe os campos. A versão do driver não é a versão do banco.

## Limites e privacidade

Até 256 JARs JDBC e 512 referências por coleta, profundidade de manifestos 8, cache por caminho, índices de até 65.535 entradas/8 MiB e cabeçalho de manifesto até 64 KiB. O orçamento global de metadados passa a 32 MiB, lidos ao longo da coleta; não é alocação de um buffer desse tamanho. Heap permanece em 96 MiB, máximo de arquivos em 50.000 e duração padrão em 180 segundos. Limites/exclusões explícitos continuam respeitados.

Manifestos assinados podem ser grandes: somente o cabeçalho com atributos principais é lido. Índices ZIP sem comentário usam leitura curta do registro final; formatos incomuns usam fallback limitado. Referências remotas não são seguidas. Conteúdo bruto de ambiente, URL JDBC completa, senha e propriedades arbitrárias não são enviados.

JMX/WLST permanece opcional pelo mecanismo de snapshot já existente. Esta entrega não cria conexões administrativas nem faz interpretação genérica de logs. Nenhuma varredura passiva garante qual classe está carregada quando há classloaders personalizados ou arquivos alterados após o início da JVM.

## Testar no laboratório

Na RHEL5 foi criado `LAB_ORACLE_DRIVER_SIMULATION`, descritor não referenciado pelo config.xml, com classe oracle.jdbc.OracleDriver. É uma configuração de simulação, **não um datasource ativado nem uma conexão com Oracle**. O JAR real do WebLogic forneceu versão 11.2.0.3.0; Derby forneceu 10.12.1.1. Após atualizar o agente e solicitar nova coleta, confira Bibliotecas Java e o relatório JDBC.

**Atualize os agentes separadamente.** Publicar o dashboard não recalcula inventários antigos. Confira a elegibilidade no painel, atualize uma máquina e execute uma coleta; instalações sem rota compatível usam o instalador completo configurado pelo ambiente.
