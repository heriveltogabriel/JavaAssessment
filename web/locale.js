/* Presentation-only translations. Never apply these helpers to names, paths, tokens or API keys. */
(function(root){
 'use strict';
 const states={SUCCEEDED:'Concluído',COMPLETE:'Concluído',COMPLETED:'Concluído',PARTIAL:'Parcial',FAILED:'Falhou',ERROR:'Erro',WARNING:'Aviso',INFO:'Informação',OK:'OK',RUNNING:'Em execução',QUEUED:'Na fila',PENDING:'Pendente',WAITING:'Aguardando',UNKNOWN:'Desconhecido',ACTIVE:'Ativo',INACTIVE:'Inativo',REVOKED:'Revogado',EXPIRED:'Expirado',CANCELLED:'Cancelado',ENABLED:'Ativado',DISABLED:'Desativado',CONFIRMED:'Confirmado',PRESERVED:'Preservado',INSTALLED:'Instalado',INSTALLING:'Instalando',REGISTERING:'Cadastrando',REGISTERED:'Cadastrado',UNINSTALLED:'Desinstalado',PROCESS_RUNNING:'Processo observado',FOUND:'Encontrado',FILE_FOUND:'Arquivo encontrado',CONFIGURED:'Configurado',APPLICATION_REPORTED:'Informado pela aplicação',SUSPENDED:'Suspenso',SHUTDOWN:'Desligado',OVERLOADED:'Sobrecarregado',UNHEALTHY:'Com falha',ONCE:'Uma vez',DAILY:'Diária',WEEKLY:'Semanal',CONFIGURATION:'Configuração',RUNTIME:'Execução',FILE:'Arquivo',PROCESS:'Processo',UNCLASSIFIED:'Não classificado'};
 const stages={system:'Sistema',java:'Java',permissions:'Permissões',tls:'Conexão TLS',registration:'Cadastro',scheduler:'Agendamento',heartbeat:'Contato do agente',complete:'Conclusão',diagnostic:'Diagnóstico',failure:'Falha'};
 const fields={durationMillis:'Duração (ms)',filesVisited:'Arquivos examinados',filesRead:'Arquivos lidos',archivesInspected:'Arquivos compactados inspecionados',bytesRead:'Bytes lidos',processesObserved:'Processos observados',javaProcesses:'Processos Java',runtimeCount:'Instalações Java',warningCount:'Quantidade de avisos',cpuMillis:'Tempo de CPU (ms)',collectorCpuMillis:'Tempo de CPU do coletor (ms)',peakHeapBytes:'Pico do heap (bytes)',peakResidentBytes:'Pico de memória residente (bytes)',maxFiles:'Limite de arquivos',maxDurationSeconds:'Duração máxima (s)',batchSize:'Tamanho do lote',pauseMillis:'Pausa entre lotes (ms)',includePaths:'Caminhos incluídos',excludePaths:'Caminhos excluídos',inspectArchives:'Inspeção de arquivos Java',enabled:'Ativado',frequency:'Frequência',timeZone:'Fuso horário',windowStart:'Início da janela',windowEnd:'Fim da janela',name:'Nome',description:'Descrição',region:'Região',createdAt:'Criado em'};
 const messages={
  'Kernel has no MemAvailable counter; available memory is unknown, not estimated':'O kernel não possui o contador MemAvailable; a memória disponível não foi estimada.',
  'Archive inspection is not enabled':'A inspeção de arquivos Java está desativada.',
  'Assessment deadline reached; remaining paths were not examined':'O prazo da coleta expirou; os caminhos restantes não foram examinados.',
  'File count limit reached; remaining paths were not examined':'O limite de arquivos foi atingido; os caminhos restantes não foram examinados.',
  'Known server launcher in a live Java process; service health not tested':'Inicializador de servidor identificado em um processo Java ativo; funcionamento do serviço não testado.',
  'Collector exceeded lease or did not report a result':'O agente excedeu o prazo de execução ou não informou um resultado.',
  'Assessment requested':'Coleta solicitada.',
  'No assessment has been reported':'Nenhuma coleta recebida.'
 };
 const prefixes=[['Include path is absent: ','Caminho de inclusão ausente: '],['Symlink path skipped: ','Link simbólico ignorado: '],['Directory is not readable: ','Sem permissão para ler o diretório: '],['Excluded path: ','Caminho excluído: ']];
 const status=value=>states[String(value||'').toUpperCase()]||String(value??'');
 function message(value){const s=String(value??'');if(messages[s])return messages[s];const match=s.match(/^Assessment received — (\d+) Java installation\(s\), (\d+) server\(s\)$/);if(match)return 'Coleta recebida — '+match[1]+' instalação(ões) Java, '+match[2]+' servidor(es)';for(const [prefix,label] of prefixes)if(s.startsWith(prefix))return label+s.slice(prefix.length);return s;}
 root.AssessmentLocale={status,message,stage:value=>stages[value]||String(value??''),field:value=>fields[value]||String(value??''),number:value=>Number(value).toLocaleString('pt-BR')};
 if(typeof module!=='undefined'&&module.exports)module.exports=root.AssessmentLocale;
})(typeof window!=='undefined'?window:globalThis);
