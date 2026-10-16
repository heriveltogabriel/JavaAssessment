# Atualização remota do agente

Proposta de HTTPS iniciado pelo agente, assinatura offline, confirmação pelo administrador e restauração local aprovada pelo usuário. A implementação e o cenário de teste foram autorizados, incluindo trabalho em paralelo. Não publicar remotamente nesta sessão: o publicador continua sendo executado pelo usuário no Terminal.

## Limite e arquitetura

O dashboard agenda uma ação tipada UPDATE_AGENT_V1. O agente consulta a ação pelo HTTPS autenticado existente, e um executor independente instalado com sudo aplica a troca do collector.jar. O executor e seus arquivos protegidos ficam em /opt/java-assessment-update. A primeira instalação habilitadora é manual. Só instalações Linux root com serviço protegido são elegíveis; cron/manual não anuncia capacidade.

O formato inicial é collector-jar-v1. Atualiza todo o código Java do agente; scripts, bibliotecas TLS, configuração, identidade, CA, inventário e arquivos de aplicações são preservados. O manifesto contém um mapa de SHA256 dos assets estáveis exigidos (scripts/bibliotecas/chave pública). Se uma versão depender de assets diferentes, será recusada para atualização remota e exigirá atualização do instalador. Essa restrição impede executar um instalador arbitrário como root e permite uma primeira entrega verificável. O helper independente mantém o código de recuperação durante a troca.

Cada versão é assinada no Mac por RSA3072/SHA256withRSA. A chave privada fica em output/agent-update-signing/private-key.pem (diretório0700/arquivo0600), nunca é incluída em ZIP/runtime/fontes, nem enviada ao dashboard. A chave pública X509 DER é instalada pelo pacote habilitador e entregue ao dashboard. Não há atualização de chave remota neste protocolo. Manifesto exato UTF8 assinado, assinatura binária separada; tipo ASSESSMENT_AGENT_UPDATE_V1, schema1, version SemVer numérica, format collector-jar-v1, minUpdater1, sha256 do collector.jar, bytes <=32MiB, requiredFiles mapa nome relativo seguro→SHA256. JAR assinado deve declarar a mesma Implementation-Version. Sem downgrade ou reinstalação da mesma versão; rollback apenas para backup privado da transação local.

## Contrato de API

Rotas humanas ADMIN e CSRF nos writes:
- GET /api/agent-updates?hostId=ID ou environmentId=ID (vazio=all; __unassigned__ suportado) → {release:{version,available,reason},hosts:[{id,hostname,collectorVersion,eligible,reason,update}],requests:[]}. Contagens/hosts limitados a1000.
- POST /api/agent-updates → {hostIds:[IDs],targetVersion,requestKey,expiresInMinutes:60}, todos no mesmo ambiente/seleção aprovada pela UI, máximo1000 e seleção explicitada; retorna {requests:[],skipped:[]}. Um batchId ordena a fila e apenas uma máquina por lote é entregue/atualizada de cada vez. Não mover a seleção se ambiente mudar após confirmação: UI usa IDs do preview e servidor revalida elegibilidade.
- POST /api/agent-updates/cancel → {hostId,requestId}, somente WAITING.

Rotas do agente usam seu Bearer e conferem host:
- heartbeat acrescenta updateCapability:'update-v1'. GET actions/poll aceita updateCapability=update-v1. Resposta action {type:'UPDATE_AGENT_V1',requestId,targetVersion,expiresMillis}.
- POST /api/agent/update/claim {hostId,requestId} → {requestId,executable:true,targetVersion,expiresMillis}; idempotente, ou executable:false para cancelado/expirado. Revalida credencial e conflitos.
- GET /api/agent/update/manifest?hostId=ID&requestId=ID → bytes JSON exatos; signature mesma query em /signature; JAR em /package. Só request próprio claimed e ainda autorizado. Sem URL fornecida pelo dashboard, redirect ou download arbitrário.
- POST /api/agent/update/report {hostId,requestId,status,version,diagnostic} → {accepted:true,requestId,status}. Diagnósticos de enum controlado; não transmitir segredos/stacktraces.

Estados WAITING, DOWNLOADING, INSTALLING, VERIFYING, SUCCEEDED, FAILED, ROLLED_BACK, UNCONFIRMED, CANCELLED, EXPIRED. Estado local durável permite repetir report após perda de resposta e recuperar após interrupção. Silêncio não confirma sucesso; request claimed parado torna UNCONFIRMED e bloqueia conflitos. Agent update ativo bloqueia coleta nova, exclusão e desinstalação; job RUNNING e uninstall busy bloqueiam claim. Revogar credencial antes da troca cancela autorização; durante perda de rede, não declarar sucesso nem destruir backup.

## Operação local

Validar ownership/permissões/caminhos sem symlinks, chave protegida, assinatura, bytes/hash, versão e assets compatíveis antes da troca. Download limitado em pasta privada e arquivo fixo, sem extrair ZIP. Adquirir lock compartilhado com instalação manual; parar apenas o scheduler do collector e aguardar término do ciclo. Backup durável do JAR, publicação por rename, teste local do novo JAR usando modo probe sem coleta, restabelecer scheduler e heartbeat. Falha de código restaura o JAR anterior e scheduler; falha de rede fica sem confirmação e preserva estado. Helper mantém backup e journal até resultado reconhecido. Reinício em fase de troca deve restaurar/retomar de modo determinístico. Operações locais não aceitam shell livre, caminhos de payload nem parâmetros de comando vindos do dashboard.

## Interface e teste

⋮ → Atualizar agente (máquina) e Actions → Atualizar agentes (ambiente/todos) abre preview com versões, elegíveis/inelegíveis e razão. Administrador confirma seleção e acompanha progresso, cancelamento antes de iniciar e restauração. Reader não pode iniciar/cancelar. Desktop/mobile, erros, alvo obsoleto e sessão expirada são cobertos.

Testes usam HTTP loopback, SQLite descartável, chaves de teste e filesystem isolado com adaptadores de serviço explicitamente restritos aos testes. Cobrir sucesso, assinatura/JAR adulterados, assets incompatíveis, downgrade, código novo defeituoso com rollback, interrupção, perda de ACK, autorização/CSRF/ownership, concorrência, batch gradual e agentes antigos. Cenário humano: instalar release habilitadora em uma VM de homologação, publicar release seguinte, atualizar via dashboard, conferir versão e identidade; falhas injetadas apenas em laboratório. Nunca usar pacote adulterado em produção.
