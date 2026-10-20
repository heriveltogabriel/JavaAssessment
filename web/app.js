const jdbcDriverResolutionLabel = status => ({IDENTIFIED:'Identificado em arquivo',AMBIGUOUS:'Múltiplos candidatos',INCOMPLETE:'Inspeção incompleta',NOT_FOUND:'Driver não localizado',VERSION_UNAVAILABLE:'Versão indisponível'})[status] || status || '';
const jdbcDriverCandidatesText = candidates => Array.isArray(candidates) ? candidates.filter(c => c && typeof c === 'object').slice(0,32).map(c => [c.path, c.version || 'Versão indisponível', c.source].filter(Boolean).join(' — ')).join(' | ') : '';
const jdbcJarFileName = row => { const path = row._kind === 'datasources' ? row.driverArtifact : row.path; return typeof path === 'string' && /\.jar$/i.test(path) ? path.split(/[\\/]/).pop() : ''; };
'use strict';
(() => {
  const message = value => window.AssessmentLocale.message(value);
  const stateLabel = value => window.AssessmentLocale.status(value);
  const $ = (id) => document.getElementById(id);
  const esc = (value) => String(value == null ? '' : value).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const tabs = [
    ['overview',"Visão geral","Um resumo do seu ambiente Java."],
    ['environments',"Ambientes","Organize máquinas e baixe instaladores configurados."],
    ['versions',"Versões Java","Explore as instalações agrupadas por versão."],
    ['properties',"Propriedades do inventário","Configuração do inventário e cobertura das coletas."],
    ['hosts',"Máquinas","As máquinas deste inventário estão na tabela. Selecione uma para consultar seus recursos e a cobertura da coleta."],
    ['runtimes',"Instalações Java","As instalações Java encontradas nos caminhos configurados estão na tabela."],
    ['applications',"Aplicações","As aplicações identificadas pelo coletor são exibidas com o horário original da observação."],
    ['servers',"Servidores de aplicação Java","Os servidores de aplicação Java encontrados na coleta estão na tabela."],
    ['libraries',"Bibliotecas Java","Bibliotecas Java e drivers JDBC encontrados pelo coletor são exibidos com suas evidências."],
    ['schedule',"Tarefas agendadas","Defina quando executar as coletas e os limites de processamento."],
    ['reports','Relatórios','Consulte, filtre e exporte o inventário.'],
    ['jobs',"Solicitações",'Acompanhe coletas e atualizações de agentes, por ambiente e por máquina.']
  ];
  let rawData=null, environmentData=null, environmentScope=AssessmentEnvironments.stored(), contextKind='root';
  let data = null, csrf = '', current = 'overview', query = '', hostFilter = '', page = 1, size = 20;
  let dateFrom = '', dateTo = '', modalHandler = null, toastTimer, pollTimer, refreshing = false, manualRefresh = null;
  const presenceLabels={disconnected:'Máquinas sem comunicação',recent:'Máquinas conectadas',delayed:'Máquinas com contato atrasado',silent:'Máquinas sem comunicação',awaiting:'Aguardando primeiro contato',revoked:'Acesso revogado',uninstalled:'Agente desinstalado',unknown:'Horário do contato indisponível'};
  let minimumSeconds=300;
  let machineId = '', versionMode = 'family', versionFocus = '', inventorySubset = '';
  let workRequestId='',workRequestType='',supportFilter='';
  const columnVisibility = {};
  const narrowNavigation = window.matchMedia('(max-width:850px)');
  let desktopNavigationOpen = true;
  let renderedHash='',requestRevision=0,stateController=null,requestKey='',detailRevision=0;
  const count=(key)=>AssessmentDashboard.count(data || {},key);
  function stateParams(){
    // History owns its scope, including snapshots whose machine is no longer in live inventory.
    if(current==='jobs')return new URLSearchParams({view:'jobs',page:'1',pageSize:'20'});
    return new URLSearchParams({environmentId:environmentScope,hostId:contextKind==='machine'?machineId:'',view:current==='schedule'?'hosts':current==='properties'?'settings':current,page:String(page),pageSize:String(size),q:query,from:dateFrom,to:dateTo,subset:inventorySubset,support:supportFilter,minimumSeconds:String(minimumSeconds),version:versionFocus,versionMode});
  }

  const allowedView=id=>AssessmentAccess.isAdmin()||!['properties','schedule','administration'].includes(id);
  const isView=id=>tabs.some(t=>t[0]===id)&&id!=='environments';
  const routeView=()=>current==='machine'?'overview':current;
  function readRoute() {
    const hash=location.hash.slice(1),split=hash.indexOf('?'),path=split<0?hash:hash.slice(0,split),params=new URLSearchParams(split<0?'':hash.slice(split+1));
    const env=params.get('env');
    environmentScope=params.has('env')?(env==='all'?'':env==='unassigned'?'__unassigned__':env):path.startsWith('machine/')?'':AssessmentEnvironments.stored();
    contextKind='root';machineId='';hostFilter='';current='overview';
    if(path.startsWith('machine/')){
      try{machineId=decodeURIComponent(path.slice(8));}catch(_){machineId='';}
      if(machineId){contextKind='machine';hostFilter=machineId;const view=params.get('view') || 'overview';current=isView(view)?view:'overview';if(current==='overview')current='machine';}
    }else if(path.startsWith('environment/')){contextKind='environment';const view=path.slice(12);current=isView(view)?view:'overview';}
    else if(path==='environments')current='environments';
    else if(path==='administration')current='administration';
    else if(isView(path)){current=path;contextKind=path==='overview'?'root':'environment';}
    if(path==='jobs'){environmentScope=params.has('env')?environmentScope:'';const id=params.get('host')||'';if(id){contextKind='machine';machineId=id;hostFilter=id;}}
    const subset=params.get('subset');inventorySubset=(subset==='runtime'&&current==='servers'||subset==='datasources'&&current==='libraries'||current==='hosts'&&Object.hasOwn(presenceLabels,subset)||AssessmentLifecycle.subsetAllowed(current,subset))?subset:'';supportFilter=AssessmentLifecycle.supportParam(current,params.get('support'));
    minimumSeconds=[300,86400,604800,2592000].includes(Number(params.get('minimumSeconds')))?Number(params.get('minimumSeconds')):300;
    versionMode=params.get('group')==='exact'?'exact':'family';versionFocus=params.get('version') || '';
    workRequestId=current==='jobs'?(params.get('request')||''):'';workRequestType=workRequestId&&params.get('requestType')==='ASSESSMENT'?'ASSESSMENT':workRequestId?'AGENT_UPDATE':'';
    renderedHash=location.hash;
  }
  function routeHash() {
    if(current==='reports')return '#reports';
    const params=new URLSearchParams({env:environmentScope===''?'all':environmentScope==='__unassigned__'?'unassigned':environmentScope});
    let path=current;
    if(contextKind==='machine'){path='machine/'+encodeURIComponent(machineId);params.set('view',routeView());}
    else if(contextKind==='environment')path='environment/'+routeView();
    if(current==='jobs'){path='jobs';params.delete('view');if(contextKind==='machine'&&machineId)params.set('host',machineId);}
    if(inventorySubset)params.set('subset',inventorySubset);
    if(supportFilter)params.set('support',supportFilter);
    if(current==='hosts'&&['silent','disconnected'].includes(inventorySubset))params.set('minimumSeconds',String(minimumSeconds));
    if(current==='versions'){if(versionMode==='exact')params.set('group','exact');if(versionFocus)params.set('version',versionFocus);}
    if(current==='jobs'&&workRequestId){params.set('request',workRequestId);params.set('requestType',workRequestType||'AGENT_UPDATE');}
    return '#'+path+'?'+params.toString();
  }
  function writeRoute(replace=false) {
    const hash=routeHash(),state={contextKind,view:routeView(),environmentId:environmentScope,machineId,inventorySubset};
    history[replace||location.hash===hash?'replaceState':'pushState'](state,'',hash);renderedHash=hash;
  }
  function resetTransient() {minimumSeconds=300;query='';inventorySubset='';supportFilter='';versionFocus='';dateFrom='';dateTo='';workRequestId='';workRequestType='';page=1;$('search').value='';$('global-search').value='';}
  function projectData() {
    if(!rawData){environmentData=null;data=null;return;}
    if(current==='jobs'){environmentData=rawData;data=rawData;hostFilter=contextKind==='machine'?machineId:'';return;}
    environmentScope=AssessmentEnvironments.validScope(rawData,environmentScope);
    AssessmentEnvironments.persist(environmentScope);
    if(rawData.scoped){const scope=rawData.scope || {},id=scope.machineId || scope.hostId || '',machine=(rawData.hosts || []).find(h=>h.id===id);rawData.scope={...scope,unassigned:scope.unassigned || scope.environmentId==='__unassigned__',machineId:id,machineName:machine?.hostname || machine?.fqdn || id,name:AssessmentEnvironments.label(rawData,environmentScope)};environmentData=rawData;data=rawData;hostFilter=contextKind==='machine'?machineId:'';return;}
    environmentData=AssessmentEnvironments.scope(rawData,environmentScope);
    if(contextKind==='machine'&&!(environmentData.hosts || []).some(h=>h.id===machineId)){contextKind='environment';machineId='';hostFilter='';if(current==='machine')current='overview';resetTransient();}
    hostFilter=contextKind==='machine'?machineId:'';
    data=contextKind==='machine'?AssessmentDashboard.scoped(environmentData,machineId):environmentData;
  }
  function finishNavigation(replace=false) {if(!AssessmentAccess.canInventory())return;if(current==='reports')AssessmentReports.enter();if(!allowedView(current)){current='overview';contextKind='root';}closeActions();AssessmentAccess.closeMenu();AssessmentMachines.clear();AssessmentAgentUpdates.clear();AssessmentWorkRequests.clear({preserveFilters:true});hostFilter=contextKind==='machine'?machineId:'';closeMobileNavigation();closeDrawer();writeRoute(replace);data=null;render();loadState(false);window.scrollTo({top:0,behavior:'instant'});}
  function openAllMachines(){contextKind='environment';current='hosts';environmentScope='';machineId='';hostFilter='';resetTransient();finishNavigation();}
  function openAgentUpdateRequest(id,type){
    // A global update can be requested from a filtered inventory or a machine.
    // Its receipt must show the entire confirmed batch, not the previous scope.
    if(type==='AGENT_UPDATE'){contextKind='environment';environmentScope='';machineId='';hostFilter='';}
    openWorkRequest(id,type);
  }
  function openWorkHistory(){contextKind='environment';current='jobs';environmentScope='';machineId='';hostFilter='';resetTransient();finishNavigation();}
  function openWorkRequest(id='',type='AGENT_UPDATE') {
    if(!AssessmentAccess.canInventory())return;
    if(current==='jobs'){workRequestId=id;workRequestType=id?type:'';writeRoute();render();return;}
    if(contextKind==='root')contextKind='environment';current='jobs';resetTransient();workRequestId=id;workRequestType=id?type:'';finishNavigation();
  }
  function closeActions(restoreFocus=false) {
    const wasOpen=!$('actions-menu').hidden;
    $('actions-menu').hidden=true;$('actions-toggle').setAttribute('aria-expanded','false');
    if(wasOpen&&restoreFocus)$('actions-toggle').focus();
  }
  function openAgentUpdates() {
    if(!AssessmentAccess.isAdmin()||!data)return;
    closeActions();
    AssessmentAgentUpdates.open(contextKind==='machine'?{hostId:machineId,label:hostName(machineId)}:{environmentId:environmentScope,label:AssessmentEnvironments.label(rawData,environmentScope)});
  }
  function renderMachineUpdateAction() {
    if(!AssessmentAccess.isAdmin()||!data||current!=='hosts'||contextKind==='machine')return;
    const button=document.createElement('button');
    button.type='button';button.className='button';button.dataset.updateEnvironment='';button.dataset.adminOnly='';
    button.textContent=environmentScope==='__unassigned__'?'Atualizar agentes sem ambiente':environmentScope?'Atualizar agentes do ambiente':'Atualizar agentes';
    const actions=$('view-actions').querySelector('.enrollment-actions')||$('view-actions');
    actions.prepend(button);
  }
  function renderActions() {
    $('run-assessment').hidden=!AssessmentAccess.isAdmin()||(data?.scoped?count('hosts')===0:!(data?.hosts || []).some(h=>h.credentialStatus!=='revoked'));
    if(contextKind==='machine'){const selected=host(machineId);if(selected?.agentLifecycle==='uninstalled'||selected?.credentialStatus==='revoked'||['WAITING','UNINSTALLING','UNCONFIRMED'].includes(selected?.uninstall?.status))$('run-assessment').hidden=true;}
    const machine=contextKind==='machine',environment=!!environmentScope&&environmentScope!=='__unassigned__',defaults=contextKind==='root'&&!environmentScope;
    $('uninstall-agent').hidden=!AssessmentAccess.isAdmin()||!data||!machine;
    $('open-settings').hidden=!AssessmentAccess.isAdmin()||!data||!(machine||environment||defaults);
    $('open-settings').textContent=machine?"Configurações da máquina":environment?"Configurações do ambiente":"Configurações padrão da coleta";
    $('actions-menu').querySelector('[data-enrollment-list]').hidden=!AssessmentAccess.isAdmin()||!data||machine;
    $('open-install-guide').hidden=!AssessmentAccess.isAdmin();
    $('open-agent-updates').hidden=!AssessmentAccess.isAdmin()||!data;
    const updateBusy=contextKind==='machine'&&AssessmentAgentUpdates.active(host(machineId));if(updateBusy)$('run-assessment').hidden=true;
    $('uninstall-agent').disabled=!!updateBusy;$('uninstall-agent').title=updateBusy?'Aguarde a atualização do agente ser resolvida.':'';
  }
  readRoute();
  const icon = (name) => '<svg aria-hidden="true"><use href="#i-'+name+'"/></svg>';
  function setNavigation(open,options={}) {
    const mobile=narrowNavigation.matches;
    if(!mobile)desktopNavigationOpen=open;
    $('side-nav').hidden=!open;
    $('nav-backdrop').hidden=!(mobile&&open);
    $('nav-toggle').setAttribute('aria-expanded',String(open));
    $('nav-toggle').setAttribute('aria-label',open?"Fechar navegação":"Abrir navegação");
    document.body.classList.toggle('sidebar-collapsed',!mobile&&!open);
    document.body.classList.toggle('navigation-open',mobile&&open);
    $('main-content').inert=mobile&&open;
    document.querySelectorAll('.topbar > :not(#nav-toggle)').forEach(element=>{element.inert=mobile&&open;});
    if(open&&options.focus)($('side-nav').querySelector('[aria-current="page"]') || $('side-nav').querySelector('[data-tab]'))?.focus();
    if(!open&&options.restoreFocus)$('nav-toggle').focus();
  }
  function closeMobileNavigation() {
    if(narrowNavigation.matches&&!$('side-nav').hidden)setNavigation(false,{restoreFocus:true});
  }
  function date(value, compact) {
    if (!value) return '—';
    const d = new Date(value); if (!Number.isFinite(d.getTime())) return esc(value);
    return esc(d.toLocaleString("pt-BR", {month:'short',day:'2-digit',year:'numeric',...(compact ? {} : {hour:'2-digit',minute:'2-digit',hour12:false,timeZoneName:'short'})}));
  }
  function bytes(value) {
    if (value == null || value === '') return '—';
    const n = Number(value); if (!Number.isFinite(n)) return '—';
    if (n === 0) return '0 B'; const units = ['B','KiB','MiB','GiB','TiB'];
    const power = Math.min(Math.floor(Math.log(Math.abs(n))/Math.log(1024)),4);
    return (n / Math.pow(1024,power)).toLocaleString("pt-BR",{maximumFractionDigits:1})+' '+units[power];
  }
  const value = (v) => v == null || v === '' ? '<span class="unknown">—</span>' : esc(v);
  function badge(v,coverage=false) {
    if (!v) return value(null);
    const str = String(v), low = str.toLowerCase();
    if(str.toUpperCase()==='PROCESS_RUNNING')return "<span class=\"badge neutral\">Processo observado</span>";
    const color = /failed|error|stopped|shutdown/.test(low) ? 'error' : /queued|pending|unknown/.test(low) ? 'warning' : /partial/.test(low) ? 'neutral' : /running|complete|success|succeeded|confirmed/.test(low) ? '' : 'neutral';
    return '<span class="badge '+color+'"'+(low==='partial'?' title="'+esc(AssessmentDashboard.limitationsDescription)+'"':'')+'>'+esc(AssessmentDashboard.assessmentStatus(str,coverage))+'</span>';
  }
  const host = (id) => (data?.hosts || []).find((h) => h.id === id);
  const hostName = (id) => host(id)?.hostname || host(id)?.fqdn || id || '—';
  const link = (kind,id,label,sub,hostId='') => '<button class="link" data-detail-kind="'+esc(kind)+'" data-detail-id="'+esc(id)+'" data-detail-host="'+esc(hostId)+'">'+esc(label || "Sem nome")+'</button>'+(sub ? '<span class="cell-sub" title="'+esc(sub)+'">'+esc(sub)+'</span>' : '');
  const hostLink = (id) => host(id) ? link('hosts',id,hostName(id)) : value(id);
  const pathCell = (path) => '<span class="cell-path" title="'+esc(path || '')+'">'+value(path)+'</span>';
  const timeCell = (v) => '<span class="date-cell">'+date(v)+'</span>';
  const observationCell = r => '<span class="badge neutral">'+esc(AssessmentDashboard.observation(r).label)+'</span>';
  const applicationCell = (r,context=data) => {
    return AssessmentDashboard.associations(context,r).map(a=>'<div class="association-item">'+(a.application?link('applications',a.application.id,a.name,'',a.hostId):value(a.name || (a.serverName?"Servidor: "+a.serverName:"Processo Java")))+(a.pid?'<span class="cell-sub">PID '+esc(a.pid)+'</span>':'')+'</div>').join('') || value(null);
  };
  function embeddedServersCell(records) {
    if(!Array.isArray(records))return value(null);
    const labels=records.filter(item=>item&&typeof item==='object'&&!Array.isArray(item)).map(item=>[typeof item.type==='string'?item.type:'',typeof item.version==='string'?item.version:'Versão não identificada'].filter(Boolean).join(' · '));
    return labels.length?labels.map(esc).join('<br>')+'<small class="table-sub">Bibliotecas empacotadas; ativação em execução não confirmada.</small>':value(null);
  }
  const datasourceEvidence = r => {const evidence=AssessmentDashboard.datasourceObservation(r);return '<span class="badge neutral datasource-'+esc(evidence.kind)+'">'+esc(evidence.label)+'</span>';};
  const libraryEvidence = r => r._kind==='datasources'?datasourceEvidence(r):String(r.usage || '').toLowerCase()==='found'?'<span class="badge neutral">'+AssessmentDashboard.libraryObservation(r)+'</span>':badge(r.usage);
  const column = (key,label,render) => ({key,label,render});
  function libraryRows() {
    const result = (data?.libraries || []).map((r) => ({...r,_kind:'libraries'}));
    (data?.datasources || []).forEach((d) => result.push({...d,_kind:'datasources',category:"Datasource JDBC",usage:AssessmentDashboard.datasourceObservation(d).label,version:d.driverVersion,name:d.name || d.driverName}));
    return result;
  }
  function columns() {
    const hostCol = column('host',"Máquina",(r) => hostLink(r.hostId));
    const last = column('observedAt',"Último registro",(r) => timeCell(r.observedAt));
    switch(current) {
      case 'hosts': return [...(AssessmentMachineCleanup.selectable()?[column('_cleanupSelect',AssessmentMachineCleanup.header(),AssessmentMachineCleanup.checkbox)]:[]),...(AssessmentMachineCleanup.active()?[column('noContact','Tempo sem comunicação',r=>AssessmentMachineCleanup.elapsed(r.lastSeen,data?.serverTime))]:[]),column('hostname',"Máquina / FQDN",r=>link('hosts',r.id,r.hostname || r.fqdn,r.fqdn !== r.hostname ? r.fqdn : '')),column('environment',"Ambiente",r=>value(r.environmentId?r.environmentName || r.environmentId:"Sem ambiente")),column('os',"Sistema operacional",r=>value(r.distribution || r.osName)),column('ip',"Endereços IP",r=>AssessmentDashboard.machineIpCell(r)),column('architecture',"Arquitetura",r=>value(r.architecture)),column('agentVersion','Versão do agente',r=>'<span class="collector-version-cell">'+value(r.collectorVersion)+'</span>'),column('collector','JVM do agente',r=>value(r.collectorJavaVersion)),column('connection',"Contato do agente",r=>{const p=AssessmentDashboard.collectorPresence(data,r);return '<span class="presence '+esc(p.state)+'"><i></i>'+esc(p.label)+'</span>'; }),column('lastSeen',"Último contato",r=>timeCell(r.lastSeen)),...(AssessmentAccess.isAdmin()?[column('machineActions','Ações',r=>'<div class="machine-row-actions">'+AssessmentMachines.trigger(r)+'</div>')]:[])].filter(c=>!AssessmentMachineCleanup.active()||['_cleanupSelect','hostname','environment','ip','agentVersion','connection','lastSeen','noContact','machineActions'].includes(c.key)).sort((a,b)=>AssessmentMachineCleanup.active()?['_cleanupSelect','hostname','environment','ip','agentVersion','lastSeen','noContact','connection','machineActions'].indexOf(a.key)-['_cleanupSelect','hostname','environment','ip','agentVersion','lastSeen','noContact','connection','machineActions'].indexOf(b.key):0);
      case 'runtimes': return [column('version',"Versão do Java",r=>link('runtimes',r.id,r.version,'',r.hostId)),column('vendor',"Fornecedor",r=>value(r.vendor)),column('kind',"Ambiente de execução",r=>value(r.kind)),column('support',"Suporte",r=>AssessmentLifecycle.badge(r.support)),column('inUse',"Em uso",r=>r.inUse === true ? badge('CONFIRMED') : r.inUse === false ? "<span class=\"muted\">Não observado</span>" : value(null)),hostCol,column('javaHome',"Caminho de instalação",r=>pathCell(r.javaHome)),last];
      case 'servers': return [column('name',"Servidor",r=>link('servers',r.id,r.type || r.name,r.type && r.name !== r.type ? r.name : '',r.hostId)),column('version',"Versão",r=>value(r.version || (r._kind==='datasources'?jdbcDriverResolutionLabel(r.driverResolution):null))),column('support',"Suporte",r=>AssessmentLifecycle.badge(r.support)),column('applications',"Aplicações associadas",r=>{const n=data.scoped?r.applicationCount:(data.applications || []).filter(a=>AssessmentDashboard.serverAssociation(a,r)).length;return n ? String(n) : "<span class=\"unknown\" title=\"Nenhuma associação com aplicação foi informada\">—</span>";}),hostCol,column('state',"Evidência",observationCell),last];
      case 'applications': return [column('name',"Aplicação",r=>link('applications',r.id,r.name,'',r.hostId)),column('type',"Tipo",r=>value(r.applicationType || r.type)),column('version',"Versão da aplicação",r=>value(r.version)),column('frameworkVersion',"Versão do framework",r=>value(r.frameworkVersion)),column('support',"Suporte do framework",r=>AssessmentLifecycle.badge(r.support)),column('server',"Servidor de aplicação Java",r=>value(r.serverName)),hostCol,column('state',"Evidência",observationCell),last];
      case 'libraries': return [column('name',"Biblioteca / datasource JDBC",r=>link(r._kind,r.id,r.name,r._kind === 'datasources' ? r.configurationSource || [r.serverName,r.driverName].filter(Boolean).join(' · ') : r.category,r.hostId)),column('jarFile',"Arquivo JAR",r=>value(jdbcJarFileName(r))),column('version',"Versão",r=>value(r.version || (r._kind==='datasources'?jdbcDriverResolutionLabel(r.driverResolution):null))),column('support',"Suporte",r=>r._kind==='libraries'?AssessmentLifecycle.badge(r.support):value(null)),column('category',"Tipo",r=>value(r.category)),column('databaseDestination',"Destino do banco (configurado)",r=>r._kind==='datasources'?value(AssessmentDashboard.databaseDestination(r)):value(null)),column('application',"Aplicação associada",applicationCell),hostCol,column('usage',"Evidência de uso",libraryEvidence),last];
      case 'jobs': return [column('id',"Solicitação",r=>link('jobs',r.id,"Coleta "+String(r.id).slice(0,8),'',r.hostId)),hostCol,column('status',"Estado",r=>badge(r.status)),column('createdAt',"Solicitado",r=>timeCell(r.createdAt)),column('finishedAt',"Concluído",r=>timeCell(r.finishedAt)),column('summary',"Resultado",r=>value(message(r.error || r.summary)))];
      default: return [];
    }
  }
  function rows() { return current === 'libraries' ? libraryRows() : data?.[current] || []; }
  function matchedRows() {
    if(data?.scoped)return rows();
    const text = query.trim().toLowerCase();
    return rows().filter((r) => {
      if(current==='hosts'&&['silent','disconnected'].includes(inventorySubset)&&!(Date.parse(r.lastSeen)<=Date.parse(data.serverTime)-minimumSeconds*1000))return false;
      if(current==='hosts'&&inventorySubset!=='disconnected'&&Object.hasOwn(presenceLabels,inventorySubset)&&AssessmentDashboard.collectorPresence(data,r).state!==inventorySubset)return false;
      if(inventorySubset==='runtime'&&current==='servers'&&!r.state)return false;
      if(inventorySubset==='datasources'&&current==='libraries'&&r._kind!=='datasources')return false;
      if (hostFilter && (current === 'hosts' ? r.id : r.hostId) !== hostFilter) return false;
      if (text && !(JSON.stringify(r)+' '+hostName(r.hostId)+(current==='jobs'?' '+AssessmentDashboard.assessmentStatus(r.status):'')).toLowerCase().includes(text)) return false;
      const stamp = r.observedAt || r.lastSeen || r.createdAt;
      if (stamp) { const t = new Date(stamp).getTime(); if (dateFrom && t < new Date(dateFrom+'T00:00:00Z').getTime()) return false; if (dateTo && t > new Date(dateTo+'T23:59:59.999Z').getTime()) return false; }
      return true;
    });
  }
  function notify(message,error) {
    $('toast').textContent=message; $('toast').classList.toggle('error',!!error); $('toast').hidden=false;
    clearTimeout(toastTimer); toastTimer=setTimeout(()=>$('toast').hidden=true,6500);
  }
  async function api(path,options={}) { return AssessmentAccess.request(path,options); }
  async function downloadPackage(path,body){
    if(!AssessmentAccess.isAdmin())throw new Error('Esta ação requer um administrador.');
    const accessRevision=AssessmentAccess.getRevision();
    const response=await fetch('/api/'+path,{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json',"Aceitar":'application/zip','X-CSRF-Token':csrf},body:JSON.stringify(body)});
    if(!response.ok){let message="Não foi possível baixar o pacote.";try{message=(await response.json()).error || message;}catch(_){}if(response.status===401)showLogin();const error=new Error(message);error.status=response.status;throw error;}
    if(accessRevision!==AssessmentAccess.getRevision())throw new Error('A sessão mudou. Faça o download novamente.');
    if(!(response.headers.get('Content-Type') || '').includes('application/zip'))throw new Error("O servidor retornou um formato de pacote inesperado.");
    return {blob:await response.blob(),filename:downloadFilename(response)};
  }
  function downloadFilename(response){
    const match=(response.headers.get('Content-Disposition') || '').match(/filename="([^"\r\n]+)"/i);
    if(!match||!match[1]||/[\\/]/.test(match[1]))throw new Error("O servidor não informou um nome de arquivo válido.");
    return match[1];
  }
  async function downloadGet(path,{signal,accept}={}){
    const accessRevision=AssessmentAccess.getRevision();
    const response=await fetch('/api/'+path,{credentials:'same-origin',cache:'no-store',signal,headers:{Accept:accept || 'application/octet-stream'}});
    if(!response.ok){let message='Não foi possível baixar o arquivo.';try{message=(await response.json()).error || message;}catch(_){}if(response.status===401)showLogin();const error=new Error(message);error.status=response.status;throw error;}
    if(accessRevision!==AssessmentAccess.getRevision())throw new Error('A sessão mudou. Faça o download novamente.');
    if(accept&&!(response.headers.get('Content-Type') || '').toLowerCase().includes(accept.toLowerCase()))throw new Error('O servidor retornou um formato de arquivo inesperado.');
    return {blob:await response.blob(),filename:downloadFilename(response)};
  }
  function selectEnvironment(id,options={}) {
    environmentScope=AssessmentEnvironments.validScope(rawData,id);machineId='';hostFilter='';resetTransient();
    if(options.overview){contextKind='environment';current='overview';}
    else if(contextKind!=='root'){contextKind='environment';if(current==='machine')current='overview';}
    if(Object.hasOwn(presenceLabels,options.presence)){contextKind='environment';current='hosts';inventorySubset=options.presence;}
    if(options.machineId){openMachine(options.machineId);return;}
    finishNavigation();
  }
  function clearSession() {
    AssessmentReports.clear();AssessmentMachineCleanup.clear();
    AssessmentAgentUpdates.renderNotice(null);
    requestRevision++;detailRevision++;stateController?.abort();closeActions();closeDrawer();AssessmentMachines.clear();AssessmentAgentUpdates.clear();AssessmentWorkRequests.clear();AssessmentEnvironments.clear();AssessmentEnrollment.clear();clearInterval(pollTimer);csrf='';rawData=null;environmentData=null;data=null;refreshing=false;manualRefresh=null;
    if($('modal').open)$('modal').close();$('modal-body').replaceChildren();$('drawer-content').replaceChildren();$('special-view').replaceChildren();$('inventory-table').querySelector('tbody').replaceChildren();$('view-actions').replaceChildren();updateRefreshState();clearTimeout(toastTimer);$('toast').hidden=true;$('toast').textContent='';
  }
  function showLogin(){AssessmentAccess.showLogin('Sua sessão terminou. Entre novamente.');}
  function updateRefreshState() {
    const busy=refreshing||!!manualRefresh;
    $('refresh').classList.toggle('spin',busy);$('refresh').disabled=!!manualRefresh;
    $('refresh').setAttribute('aria-busy',String(busy));
    $('refresh').setAttribute('aria-label',busy?'Atualizando dados':'Atualizar dados');
    if(busy)$('main-content').setAttribute('aria-busy','true');else $('main-content').removeAttribute('aria-busy');
  }
  async function loadState(quiet) {
    if(!AssessmentAccess.canInventory())return false;
    if(current==='reports'){if(quiet)return true;requestRevision++;stateController?.abort();refreshing=false;updateRefreshState();return AssessmentReports.refresh(false);}
    const key=current==='administration'?'administration':stateParams().toString();if(quiet&&refreshing&&key===requestKey)return;
    const revision=++requestRevision;requestKey=key;stateController?.abort();stateController=new AbortController();refreshing=true;updateRefreshState();
    try {
      if(current==='administration')return await AssessmentAccess.refreshAdministration(quiet);
      const next=await api('state?'+key,{signal:stateController.signal,headers:quiet?{'X-Assessment-Background':'1'}:{}});
      if(revision!==requestRevision)return false;
      rawData=next;page=next.pagination?.page || page;projectData();writeRoute(true);render();
      if(!quiet&&!manualRefresh)$('last-refresh').textContent="Atualizado às "+new Date().toLocaleTimeString("pt-BR",{hour:'2-digit',minute:'2-digit',hour12:false});
      return true;
    } catch(e){
      if(revision===requestRevision&&e.name!=='AbortError'){notify(e.message,true);if(!data){$('special-view').innerHTML="<div class=\"dash-empty\" role=\"alert\">Não foi possível carregar esta tela. Use Atualizar para tentar novamente.</div>";$('empty-description').textContent="Não foi possível carregar esta tela. Use Atualizar para tentar novamente.";}}
      return false;
    } finally {if(revision===requestRevision){refreshing=false;updateRefreshState();}}
  }
  async function refreshCurrentView() {
    if(!AssessmentAccess.canInventory()||manualRefresh)return;
    const token={access:AssessmentAccess.getRevision(),route:renderedHash,params:stateParams().toString()};manualRefresh=token;updateRefreshState();
    const stillCurrent=()=>manualRefresh===token&&token.access===AssessmentAccess.getRevision()&&token.route===renderedHash&&token.params===stateParams().toString();
    try {
      const updated=await loadState(false);
      if(!stillCurrent())return;
      const workUpdated=['administration','reports'].includes(current)?true:await AssessmentWorkRequests.refresh(false);
      if(!stillCurrent())return;
      if(updated&&workUpdated){$('last-refresh').textContent='Atualizado às '+new Date().toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit',second:'2-digit'});notify('Dados atualizados.');}
      else notify('Não foi possível atualizar todos os dados. O último estado conhecido foi mantido.',true);
    } catch(error){if(stillCurrent())notify(error.message||'Não foi possível atualizar os dados.',true);}
    finally {if(manualRefresh===token){manualRefresh=null;updateRefreshState();}}
  }
  async function startSession(session) {
    csrf=session.csrfToken || '';if(!allowedView(current)){current='overview';contextKind='root';machineId='';}setNavigation(narrowNavigation.matches?false:desktopNavigationOpen);render();await loadState(false);clearInterval(pollTimer);pollTimer=setInterval(()=>{if(!document.hidden)loadState(true);},15000);
  }
  async function afterMachineChange({hostId:changedId}) {
    if(contextKind==='machine'&&machineId===changedId){contextKind='environment';machineId='';hostFilter='';current='hosts';resetTransient();writeRoute(true);}
    await loadState(false);
  }
  function goRoot(id='overview') {contextKind='root';current=['environments','administration','reports'].includes(id)&&allowedView(id)?id:'overview';machineId='';hostFilter='';resetTransient();finishNavigation();}
  function setTab(id,options={}) {
    if(!allowedView(id))return;
    if(['environments','administration'].includes(id)){goRoot(id);return;}
    if(!isView(id))return;
    if(contextKind==='root')contextKind='environment';
    if(options.machineId){if(!rawData?.scoped&&!(environmentData?.hosts || []).some(h=>h.id===options.machineId))return;contextKind='machine';machineId=options.machineId;}
    current=contextKind==='machine'&&id==='overview'?'machine':id;resetTransient();
    inventorySubset=options.subset || '';versionFocus=options.version || '';
    finishNavigation();
  }
  function openMachine(id) {
    if(!rawData?.scoped&&!(environmentData?.hosts || []).some(h=>h.id===id)){notify("Esta máquina está fora do ambiente selecionado.",true);return;}
    contextKind='machine';machineId=id;current='machine';resetTransient();finishNavigation();
  }
  function environmentOverview() {contextKind='environment';machineId='';hostFilter='';current='overview';resetTransient();finishNavigation();}
  function restoreRoute() {if(location.hash===renderedHash)return;closeActions();resetTransient();readRoute();finishNavigation(true);}
  window.addEventListener('popstate',restoreRoute);
  window.addEventListener('hashchange',restoreRoute);
  function renderContext() {
    const section=$('context-bar');section.hidden=contextKind==='root';section.dataset.kind=contextKind;
    if(contextKind==='root')return;
    const machine=contextKind==='machine'?(environmentData?.hosts || []).find(h=>h.id===machineId):null;
    const environmentName=AssessmentEnvironments.label(rawData,environmentScope);
    $('context-kicker').textContent=contextKind==='machine'?"MÁQUINA":"AMBIENTE";
    $('context-title').textContent=machine?.hostname || machine?.fqdn || (contextKind==='machine'?machineId:environmentName);
    const hosts=count('hosts'),runtimes=count('runtimes');
    $('context-description').textContent=(contextKind==='machine'?environmentName+' · ':'')+hosts+" máquina"+(hosts===1?'':'s')+' · '+runtimes+(runtimes===1?" instalação Java":" instalações Java")+" · As visualizações usam este escopo";
    $('context-back').textContent=contextKind==='machine'?'← '+environmentName:"← Ambientes";
    $('context-back').dataset.contextBack=contextKind==='machine'?'environment':'root';
  }
  function render() {
    AssessmentMachineCleanup.sync({enabled:!!data&&current==='hosts'&&contextKind!=='machine',environmentId:environmentScope,subset:inventorySubset,minimumSeconds,page,size,query,from:dateFrom,to:dateTo});
    AssessmentAgentUpdates.renderNotice(rawData?.agentUpdateNotice);
    AssessmentMachines.sync();
    document.body.classList.toggle('reports-page',current==='reports');
    $('reports-view').hidden=current!=='reports';
    document.body.classList.toggle('work-requests-page',current==='jobs');
    AssessmentWorkRequests.sync({enabled:!!data&&AssessmentAccess.canInventory()&&contextKind!=='root'&&current!=='administration',view:current,environmentId:environmentScope,hostId:contextKind==='machine'?machineId:'',environments:rawData?.environments||[],requestId:workRequestId,requestType:workRequestType});
    document.body.classList.toggle('administration-view',current==='administration');
    $('admin-view').hidden=current!=='administration';
    if(current==='reports'){$('side-nav').querySelectorAll('[data-tab]').forEach(item=>{const active=item.dataset.tab==='reports';item.classList.toggle('active',active);if(active)item.setAttribute('aria-current','page');else item.removeAttribute('aria-current');});AssessmentReports.render();return;}
    if(current==='administration'){$('side-nav').querySelectorAll('[data-tab]').forEach(item=>{item.classList.toggle('active',item.dataset.tab==='administration');if(item.dataset.tab==='administration')item.setAttribute('aria-current','page');else item.removeAttribute('aria-current');});AssessmentAccess.renderAdministration();return;}
    renderActions();
    document.body.classList.toggle('summary-view',['overview','environments','versions','machine'].includes(current));
    $('tabs').hidden=contextKind==='root'||current==='jobs';
    $('tabs').innerHTML=tabs.filter(([id])=>!['environments','jobs','reports'].includes(id)&&allowedView(id)).map(([id,title])=>{const active=routeView()===id,label=id==='properties'?(contextKind==='machine'?"Propriedades da máquina":"Propriedades do ambiente"):title;return '<button type="button" class="'+(active?'active':'')+'" data-tab="'+id+'" aria-current="'+(active?'page':'false')+'">'+label+'</button>';}).join('');
    $('side-nav').querySelectorAll('[data-tab]').forEach(item=>{const active=item.dataset.tab==='hosts'?contextKind!=='root'&&!['jobs','administration'].includes(current):item.dataset.tab==='jobs'?current==='jobs':contextKind==='root'&&item.dataset.tab===current;item.classList.toggle('active',active);if(active)item.setAttribute('aria-current','page');else item.removeAttribute('aria-current');});
    renderContext();
    const selected=tabs.find(t=>t[0]===current) || ['machine',"Visão geral da máquina","Recursos e inventário Java desta máquina."];$('view-title').textContent=current==='properties'?(contextKind==='machine'?"Propriedades da máquina":"Propriedades do ambiente"):selected[1];$('view-description').textContent=selected[2];
    $('standard-view').hidden=['overview','environments','versions','machine','properties','schedule','jobs'].includes(current);$('special-view').hidden=current==='jobs'||!$('standard-view').hidden;
    if(!$('standard-view').hidden)$('special-view').innerHTML='';
    $('view-actions').innerHTML='';
    if(!data){$('special-view').innerHTML="<div class=\"dash-empty\" role=\"status\">Carregando inventário…</div>";$('inventory-table').querySelector('tbody').innerHTML='';$('table-footer').hidden=true;$('result-count').textContent='';$('empty-description').textContent="Carregando inventário…";$('empty-assessment').hidden=true;$('inventory-table').querySelector('thead').innerHTML='<tr>'+columns().map(c=>'<th>'+c.label+'</th>').join('')+'</tr>';$('empty-state').hidden=false;return;}
    $('fleet-name').textContent=data.fleet?.name || "Coleta Java";$('fleet-description').textContent=data.fleet?.description || "Inventário do ambiente Java";
    const status=data.coverage?.status || 'pending';$('fleet-status').textContent=status==='complete'?"Coletado":AssessmentDashboard.assessmentStatus(status,true);$('fleet-status').className='badge '+(status==='partial'?'assessment-ready':status==='failed'?'error':status==='complete'?'':'neutral');
    const warnings=data.coverage?.warnings || [];let heading,description;
    if(status==='pending'){heading="Aguardando a primeira coleta";description="Execute uma coleta com limites definidos em uma máquina conectada para preencher o inventário.";}
    else if(status==='partial'){heading="Coleta disponível";description=AssessmentDashboard.coverageDescription(data.coverage)+(warnings.length?' '+warnings.length+(warnings.length===1?" observação de cobertura disponível.":" observações de cobertura disponíveis."):'');}
    else if(status==='failed'){heading="Falha na coleta";description="Não foi possível concluir a coleta. Abra Solicitações para consultar o erro.";}
    else if(!AssessmentAccess.isAdmin()){heading="Cobertura da coleta";description="O inventário reflete as evidências enviadas pelos agentes conectados.";}
    else if(!data.settings?.inspectArchives){heading="A inspeção de arquivos Java está desativada";description="O inventário usa os caminhos configurados e os metadados dos processos. Ative a inspeção de arquivos Java nas Configurações da coleta para aprofundar a descoberta de bibliotecas.";}
    else {heading="Cobertura da coleta completa";description="Os caminhos configurados foram examinados dentro dos limites da coleta. O inventário reflete as evidências disponíveis ao coletor.";}
    $('notice-title').textContent=heading;$('notice-text').textContent=description;$('coverage-notice').classList.toggle('complete',status==='complete' && !!data.settings?.inspectArchives);$('coverage-notice').classList.toggle('notes',status==='partial');
    $('coverage-notice').querySelector('svg use').setAttribute('href',status==='complete' && data.settings?.inspectArchives?'#i-check':'#i-warning');
    $('date-label').textContent=dateFrom || dateTo ? (dateFrom || "Qualquer período")+' – '+(dateTo || "Agora")+' UTC' : "Todo o período";
    $('host-filter').innerHTML="<option value=\"\">Todas as máquinas deste ambiente</option>"+(environmentData?.hosts || []).map(h=>'<option value="'+esc(h.id)+'">'+esc(h.hostname || h.fqdn || h.id)+'</option>').join('');$('host-filter').value=hostFilter;if(data.scoped){$('host-filter').innerHTML="<option value=\"\">Todas as máquinas deste ambiente</option>"+(hostFilter?'<option value="'+esc(hostFilter)+'">'+esc(hostName(hostFilter))+'</option>':'')+"<option value=\"__browse__\">Buscar uma máquina…</option>";$('host-filter').value=hostFilter;}
    $('clear-filters').hidden=!query&&!inventorySubset&&!supportFilter;
    $('support-filter').innerHTML=AssessmentLifecycle.filterOptions(current,supportFilter);$('support-filter-label').hidden=!$('support-filter').innerHTML;
    $('subset-filter').hidden=!inventorySubset;$('subset-filter').textContent=presenceLabels[inventorySubset] || AssessmentLifecycle.subsetLabel(inventorySubset) || (inventorySubset==='runtime'?"Somente evidências de execução":"Somente datasources JDBC");
    if(current==='overview'){const input=$('overview-machine-query'),editing=input&&document.activeElement===input,draft=editing?{value:input.value,start:input.selectionStart,end:input.selectionEnd}:null;$('special-view').innerHTML=AssessmentDashboard.overview(data);if(draft){const fresh=$('overview-machine-query');fresh.value=draft.value;fresh.focus({preventScroll:true});fresh.setSelectionRange(draft.start,draft.end);}}else if(current==='versions')$('special-view').innerHTML=AssessmentDashboard.versions(data,versionMode,versionFocus);else if(current==='machine')$('special-view').innerHTML=AssessmentDashboard.machine(data,machineId);else if(current==='properties')renderProperties();else if(current==='schedule')$('special-view').innerHTML=AssessmentEnvironments.schedule(data);else if(!['environments','jobs'].includes(current))renderTable();
    AssessmentEnrollment.render(data,current,machineId);
    AssessmentEnvironments.render(rawData,current,machineId,environmentScope,data);
    renderMachineUpdateAction();
    AssessmentAccess.applyPermissions();renderActions();
    $('last-refresh').textContent=data.serverTime?"Dados de "+new Date(data.serverTime).toLocaleTimeString("pt-BR",{hour:'2-digit',minute:'2-digit',hour12:false}):"Inventário carregado";
  }
  function renderTable() {
    AssessmentMachines.sync();
    if(!data)return;
    const all=matchedRows(), cols=columns().filter(c=>c.key==='_cleanupSelect'||columnVisibility[current]?.[c.key]!==false),total=data.scoped?Number(data.pagination?.total || 0):all.length;
    const pages=data.scoped?Math.max(1,data.pagination?.pages || 1):Math.max(1,Math.ceil(total/size));page=data.scoped?(data.pagination?.page || page):Math.min(page,pages);
    $('inventory-table').querySelector('thead').innerHTML='<tr>'+cols.map(c=>'<th scope="col">'+c.label+'</th>').join('')+'</tr>';
    const visible=data.scoped?all:all.slice((page-1)*size,page*size);
    AssessmentMachineCleanup.prepare(visible);
    $('inventory-table').querySelector('tbody').innerHTML=visible.map(r=>'<tr>'+cols.map(c=>'<td>'+c.render(r)+'</td>').join('')+'</tr>').join('');
    AssessmentMachineCleanup.paint();
    $('empty-state').hidden=visible.length>0;$('table-footer').hidden=total===0;
    const noData=!query&&!dateFrom&&!dateTo&&!inventorySubset&&!supportFilter&&total===0;$('empty-description').textContent=noData ? current==='jobs'?"Nenhuma coleta foi solicitada. Execute uma coleta em uma máquina conectada.":"Nenhum inventário correspondente informado. Execute uma coleta e revise os caminhos configurados e a cobertura." : "Refaça a busca com outros filtros, um período maior ou outra máquina.";
    $('empty-assessment').hidden=!AssessmentAccess.isAdmin()||!noData;$('result-count').textContent=total ? total+' '+(total===1?'registro':'registros') : '';
    $('row-summary').textContent=total ? ((page-1)*size+1)+'–'+Math.min((page-1)*size+visible.length,total)+" de "+total+" itens" : '';
    $('page-label').textContent="Página "+page+" de "+pages;$('prev-page').disabled=page<=1;$('next-page').disabled=page>=pages;
  }
  function details(items) {return '<dl class="details-list">'+items.map(([k,v])=>'<div><dt>'+esc(k)+'</dt><dd>'+v+'</dd></div>').join('')+'</dl>';}
  function renderProperties() {
    const f=data.fleet || {},coverage=data.coverage || {},stats=coverage.stats || {};
    const selectedEnvironment=(rawData.environments || []).find(e=>e.id===environmentScope),selectedMachine=contextKind==='machine'?host(machineId):null;
    const information=selectedMachine?[["Nome da máquina",value(selectedMachine.hostname || selectedMachine.fqdn)],["Ambiente",value(selectedMachine.environmentId?selectedMachine.environmentName || selectedMachine.environmentId:"Sem ambiente")],["Sistema operacional",value(selectedMachine.distribution || selectedMachine.osName)],["Arquitetura",value(selectedMachine.architecture)]]:selectedEnvironment?[["Nome",value(selectedEnvironment.name)],["Descrição",value(selectedEnvironment.description)],["Criado em",timeCell(selectedEnvironment.createdAt)],["Produto",'Java Assessment']]:[["Escopo",value(AssessmentEnvironments.label(rawData,environmentScope))],["Inventário",value(f.name)],["Região",value(f.region)],["Produto",'Java Assessment']];
    $('special-view').innerHTML='<div class="overview-totals">'+[["Máquinas gerenciadas",count('hosts')],["Instalações Java",count('runtimes')],["Servidores de aplicação Java",count('servers')],["Bibliotecas Java",count('libraries')]].map(([label,n])=>'<div class="overview-total"><span>'+label+'</span><strong>'+n+'</strong></div>').join('')+'</div><div class="property-grid"><section class="property-card"><h3>'+(selectedMachine?"Informações da máquina":"Informações do ambiente")+'</h3>'+details(information)+"</section><section class=\"property-card\"><h3>Cobertura da coleta</h3>"+details([["Estado",badge(coverage.status || 'pending',true)],["Última coleta",timeCell(coverage.lastAssessmentAt)],["Arquivos examinados",value(stats.filesVisited)],["Duração",stats.durationMillis != null?esc((stats.durationMillis/1000).toLocaleString('pt-BR',{maximumFractionDigits:1})+" segundos"):value(null)],["Arquivos compactados",data.settings?.inspectArchives?"Ativado":"Não inspecionado"]])+"<button class=\"text-button\" data-coverage style=\"margin-top:14px\">Ver detalhes da cobertura</button></section><section class=\"property-card\"><h3>Caminhos da coleta</h3>"+details([["Incluídos",(data.settings?.includePaths || []).map(p=>'<div class="cell-path">'+esc(p)+'</div>').join('') || value(null)],["Excluídos",(data.settings?.excludePaths || []).map(p=>'<div class="cell-path">'+esc(p)+'</div>').join('') || value(null)]])+"</section><section class=\"property-card\"><h3>Controles da coleta</h3><p class=\"muted\">As coletas respeitam limites de tempo e quantidade de arquivos. Cada máquina executa uma coleta por vez.</p><button class=\"button\" data-settings>Configurações da coleta</button><p class=\"muted\" style=\"font-size:12px\">As evidências de descoberta não constituem certificação de compatibilidade nem análise de vulnerabilidades.</p></section></div>";
  }
  function renderSchedule() {
    const s=data.settings || {};const active=!!s.enabled;
    $('special-view').innerHTML="<section class=\"schedule-card\"><div><h3>Agendamento de coleta "+badge(active?"Ativado":"Desativado")+'</h3><p>'+(active?"As coletas automáticas entram na fila durante a janela configurada.":"As coletas automáticas estão desativadas. Você pode solicitar uma coleta manual.")+'</p>'+details([["Frequência",value(stateLabel(s.frequency))],["Janela de coleta",esc((s.windowStart || '—')+' – '+(s.windowEnd || '—'))],["Fuso horário",value(s.timeZone)]])+"<button class=\"button\" data-settings>Editar agendamento</button></div><div><h3>Limites da coleta</h3><p>Os limites reduzem a carga em máquinas legadas. Ao atingir um limite, os dados encontrados são preservados e a coleta termina com observações.</p><div class=\"limits-grid\">"+[["Duração máxima",s.maxDurationSeconds!=null?s.maxDurationSeconds+" segundos":'—'],["Quantidade máxima de arquivos",s.maxFiles!=null?Number(s.maxFiles).toLocaleString('pt-BR'):'—'],["Tamanho do lote",s.batchSize??'—'],["Pausa entre lotes",s.pauseMillis!=null?s.pauseMillis+' ms':'—']].map(([k,v])=>'<div><span>'+esc(k)+'</span><strong>'+esc(v)+'</strong></div>').join('')+"</div></div></section><section class=\"property-card secondary-section\"><h3>Coleta sob demanda</h3><p class=\"muted\">Solicite uma coleta para uma máquina usando os limites configurados. O agente precisa estar conectado para recebê-la.</p><button class=\"button primary\" data-run>Executar coleta</button></section>";
  }
  function openDrawer(title,kind,html) {$('drawer-title').textContent=title;$('drawer-kind').textContent=kind;$('drawer-content').innerHTML=html;$('detail-drawer').hidden=false;$('drawer-backdrop').hidden=false;$('close-drawer').focus();}
  function closeDrawer() {detailRevision++;$('detail-drawer').hidden=true;$('drawer-backdrop').hidden=true;}
  function coverageHtml(coverage) {const c=coverage || {};return details([["Estado",badge(c.status || 'pending',true)],["Última coleta",timeCell(c.lastAssessmentAt || c.observedAt)]])+(c.status==='partial'?'<p class="coverage-explanation">'+esc(AssessmentDashboard.coverageDescription(c))+'</p>':'')+(c.warnings?.length?"<section class=\"detail-section\"><h3>Observações de cobertura</h3><ul class=\"coverage-list\">"+c.warnings.map(w=>'<li>'+esc(typeof w==='string'?w:JSON.stringify(w))+'</li>').join('')+'</ul></section>':'<p class="muted" style="font-size:12px">'+(c.status==='pending'?"Nenhuma coleta recebida.":"Nenhuma observação de cobertura informada.")+'</p>')+"<section class=\"detail-section\"><h3>Consumo da coleta</h3>"+details([["Duração",c.stats?.durationMillis!=null?esc((c.stats.durationMillis/1000).toFixed(1)+" segundos"):value(null)],["Arquivos examinados",value(c.stats?.filesVisited)],["Bytes lidos",bytes(c.stats?.bytesRead)],["Tempo de CPU do coletor",c.stats?.collectorCpuMillis!=null?esc(c.stats.collectorCpuMillis+' ms'):value(null)],["Pico do heap Java",bytes(c.stats?.peakHeapBytes)],["Pico de memória residente",bytes(c.stats?.collectorPeakRssBytes)]])+'</section>';}
  function openCoverage() {if(data)openDrawer("Cobertura da coleta","Evidências da coleta",coverageHtml(data.coverage));}
  async function showDetail(kind,id,recordHostId='') {
    if(kind==='hosts'){openMachine(id);return;}
    let r=(data?.[kind] || []).find(item=>String(item.id)===String(id)&&(!recordHostId||item.hostId===recordHostId)),detailData=data;const revision=++detailRevision;
    if(data?.scoped){try{const result=await api('item?'+new URLSearchParams({kind,id,hostId:recordHostId || r?.hostId || machineId,environmentId:environmentScope}));if(revision!==detailRevision)return;r=result.item;detailData={...data,...result.related};}catch(error){if(revision===detailRevision)notify(error.message,true);return;}}
    if(!r)return;
    let body='';
    if(kind==='hosts'){
      body=details([["Nome da máquina",value(r.hostname)],["Nome completo (FQDN)",value(r.fqdn)],["Sistema operacional",value(r.distribution || r.osName)],['Kernel',value(r.kernelVersion)],["Arquitetura",value(r.architecture)],["Endereços IP",(r.ipAddresses || []).map(esc).join('<br>') || value(null)],['CPU',value(r.cpuModel)],["CPUs lógicas",value(r.logicalCpus)],["Memória total",bytes(r.memoryTotalBytes)],["Memória disponível",bytes(r.memoryAvailableBytes)],["Java do agente",value(r.collectorJavaVersion)],["Último contato",timeCell(r.lastSeen)],["Identidade da máquina",value(r.id)]])+"<section class=\"detail-section\"><h3>Sistemas de arquivos</h3>"+(r.disks?.length?r.disks.map(d=>'<div class="detail-item"><strong>'+esc(d.mount)+'</strong><p>'+bytes(d.availableBytes)+" disponíveis de "+bytes(d.totalBytes)+'</p></div>').join(''):"<p class=\"muted\">Nenhuma medição de sistema de arquivos informada.</p>")+'</section><section class="detail-section"><h3>'+ (r.coverage?"Cobertura da máquina":"Cobertura das coletas do inventário")+'</h3>'+coverageHtml(r.coverage || data.coverage)+'</section>';
    } else {
      const friendly={id:"Identificador do registro",hostId:"Máquina",javaHome:"Diretório Java",version:kind==='applications'?"Versão da aplicação":"Versão",vendor:"Fornecedor",architecture:"Arquitetura",kind:"Tipo de instalação Java",inUse:"Uso observado",name:"Nome",type:"Tipo",applicationType:"Framework da aplicação",frameworkVersion:"Versão do framework",embeddedServers:'Servidores embutidos encontrados',activeProfiles:"Perfis identificados",configurationResolution:'Resolução da configuração',applicationId:"Aplicação associada",applicationName:"Nome da aplicação",mainClass:"Classe principal Java",startClass:"Classe inicial da aplicação",path:"Caminho",state:"Estado",category:"Categoria",usage:"Evidência de uso",scanScope:"Escopo da descoberta",serverName:"Servidor de aplicação Java",driverName:"Driver JDBC",driverClass:"Classe do driver",driverVersion:kind==='datasources'&&AssessmentDashboard.datasourceObservation(r).kind==='configuration'?"Versão do driver empacotado":"Versão do driver",databaseType:"Tipo de banco",databaseVersion:'Versão do banco',databaseVersionEvidence:'Origem da versão do banco',datasourceKey:'Identificador do datasource',poolName:'Nome do pool',databaseEndpoints:'Destinos do banco (configurados)',databaseHost:'Host/IP do banco (configurado)',databasePort:'Porta do banco (configurada)',databaseName:'Banco / serviço / SID (configurado)',driverArtifact:"Arquivo do driver",driverResolution:"Identificação do driver",driverCandidates:"Drivers candidatos",driverVersionEvidence:"Evidência da versão do driver",domainName:"Domínio WebLogic",domainPath:"Caminho do domínio",configurationScope:"Vínculo da configuração",configuredTargets:"Destinos WebLogic configurados",configurationSource:"Origem da configuração",configuredMaxPoolSize:"Tamanho máximo configurado do pool",poolCapacity:"Capacidade do pool",poolAvailable:"Conexões disponíveis",activeConnections:"Conexões ativas",observedAt:"Observado em",pid:"ID do processo",serverType:"Tipo de servidor",status:"Estado",createdAt:"Solicitado",startedAt:"Iniciado",finishedAt:"Finalizado",summary:"Resultado",error:"Erro",javaVersion:"Versão Java em execução",javaVendor:"Fornecedor da JVM em execução",runningVersions:"Versões em execução (memória)",runtimeReplacedOnDisk:"Java substituído em disco (reinício pendente)",distribution:"Distribuição do produto",patches:"Patches aplicados",resolvedFrom:"Origem dos valores resolvidos",databaseEndpointEvidence:"Evidência do destino do banco",linkedContexts:"Contextos vinculados (ResourceLink)",applicationDeployment:"Deployment da aplicação",driverModule:"Módulo do driver",driverDeployment:"Deployment do driver"};
      const ordered=Object.keys(friendly).filter(k=>(r[k]!==undefined || kind==='datasources'&&k==='databaseVersion') && k!=='id');ordered.push('id');
      body=(kind==='applications'||kind==='servers'?'<div class="evidence-summary">'+observationCell(r)+"<p>A evidência de processo identifica um inicializador Java. A presença de arquivos não confirma implantação ou funcionamento.</p></div>":kind==='libraries'&&String(r.usage || '').toLowerCase()==='found'?'<div class="evidence-summary">'+libraryEvidence(r)+"<p>A presença de pacotes ou arquivos não confirma que a biblioteca esteja carregada ou em uso por um datasource.</p></div>":'')+details(ordered.map(k=>[friendly[k],k==='hostId'?esc(hostName(r[k])):k==='applicationId'?applicationCell(r,detailData):k==='driverCandidates'?value(jdbcDriverCandidatesText(r[k])):k==='driverResolution'?value(jdbcDriverResolutionLabel(r[k])):k==='embeddedServers'?embeddedServersCell(r[k]):k==='databaseEndpoints'?value(AssessmentDashboard.databaseEndpoints(r).map(e=>(e.host.includes(':')&&!e.host.startsWith('[')?'['+e.host+']':e.host)+(e.port?':'+e.port:'')).join(', ')):k==='databaseVersion'?value(r[k] || 'Não identificada'):k==='databaseVersionEvidence'?value(r[k]==='application-reported'?'Informado pela aplicação':r[k]):k==='runningVersions'||k==='patches'||k==='distribution'?value(Array.isArray(r[k])?r[k].join(', '):r[k]):k==='resolvedFrom'?value((Array.isArray(r[k])?r[k]:[r[k]]).map(x=>({'environment':'variável de ambiente','system-property':'propriedade -D do processo','default':'valor padrão da expressão'})[x] || x).join(', ')):k==='runtimeReplacedOnDisk'?value(r[k]?'Sim — a JVM continua com a versão anterior até reiniciar':'Não'):k==='linkedContexts'?value((Array.isArray(r[k])?r[k]:[]).map(c=>c&&typeof c==='object'?[c.context,c.linkName].filter(Boolean).join(' → '):String(c)).join(', ')):k==='databaseEndpointEvidence'?value(({'configured':'Configuração','configured-resolved':'Configuração com valores resolvidos','tnsnames':'Alias TNS (tnsnames.ora)','directory-naming':'Serviço de nomes (LDAP)'})[r[k]] || r[k]):k==='activeProfiles'?value(Array.isArray(r[k])?r[k].join(', '):r[k]):k==='configurationScope'?value(({'domain-system-module':'Módulo JDBC referenciado pelo domínio','domain-unreferenced-module':'Arquivo no domínio ativo; referência de implantação não encontrada'})[r[k]] || r[k]):k==='configurationResolution'?value(({'local-candidate':'Configuração local encontrada; uso não confirmado',partial:'Parcial; existem fontes ou valores não resolvidos'})[r[k]] || r[k]):k==='state'&&r[k]==='APPLICATION_REPORTED'?datasourceEvidence(r):/At$/.test(k)?timeCell(r[k]):k==='state'||k==='status'?badge(r[k]):k==='summary'||k==='error'?value(message(r[k])):k==='usage'?value(stateLabel(r[k])):typeof r[k]==='boolean'?(r[k]?"Sim":"Não observado"):value(typeof r[k]==='object'?JSON.stringify(r[k]):r[k]) ]));
      if(kind==='datasources'){const observation=AssessmentDashboard.datasourceObservation(r),configured=observation.kind==='configuration';body='<div class="evidence-summary datasource-evidence-summary">'+datasourceEvidence(r)+'<p>'+(configured?"A configuração foi encontrada na origem informada. O perfil efetivo, a conexão ativa e o estado do pool em execução não foram verificados.":observation.kind==='applicationReported'?'Metadados informados pela aplicação por integração opcional. O coletor não verificou independentemente a conexão com o banco.':observation.kind==='runtime'?"As observações em execução mantêm o horário original. O estado e as métricas do pool descrevem aquela observação.":"A evidência do datasource não foi classificada. Consulte a origem antes de inferir configuração ou atividade em execução.")+'</p></div>'+body;}
      if(kind==='jobs'&&r.status==='PARTIAL')body='<div class="evidence-summary assessment-limitation-summary"><p>'+esc(AssessmentDashboard.limitationsDescription)+'</p>'+(host(r.hostId)?'<button class="text-button" data-host-coverage="'+esc(r.hostId)+"\">Ver cobertura mais recente da máquina</button>":'')+'</div>'+body;
      body=AssessmentLifecycle.detail(r.support)+body;
      if(r.evidence)body+="<div class=\"evidence-box\"><strong>Evidência</strong><br>"+esc(typeof r.evidence==='string'?r.evidence:JSON.stringify(r.evidence))+'</div>';
      if((kind==='libraries'||kind==='datasources')&&Array.isArray(r.associations)&&r.associations.length)body+="<section class=\"detail-section\"><h3>Aplicações e processos associados</h3>"+applicationCell(r,detailData)+'</section>';
      if(r.stats)body+="<section class=\"detail-section\"><h3>Estatísticas da coleta</h3>"+details(Object.entries(r.stats).map(([k,v])=>[AssessmentLocale.field(k),value(v)]))+'</section>';
      if(kind==='applications'){
        const libraries=AssessmentDashboard.jdbcLibraries(detailData).filter(l=>l.hostId===r.hostId&&AssessmentDashboard.associations(detailData,l).some(a=>a.applicationId===r.id));
        const sources=(detailData.datasources || []).filter(ds=>ds.hostId===r.hostId&&AssessmentDashboard.associations(detailData,ds).some(a=>a.applicationId===r.id));
        if(sources.length)body+="<section class=\"detail-section application-datasources\"><h3>Datasources JDBC</h3>"+sources.map(ds=>'<div class="detail-item">'+link('datasources',ds.id,ds.name,'',ds.hostId)+'<p>'+esc(ds.driverName || ds.driverClass || "Driver não informado")+'</p><p>Destino configurado: '+esc(AssessmentDashboard.databaseDestination(ds)||'Não identificado')+'</p>'+datasourceEvidence(ds)+(ds.configurationSource?'<p class="cell-path">'+esc(ds.configurationSource)+'</p>':'')+'</div>').join('')+'</section>';
        if(libraries.length)body+="<section class=\"detail-section\"><h3>Bibliotecas JDBC encontradas</h3><p class=\"muted\">A presença não confirma a configuração de datasource ou o uso de JDBC.</p>"+libraries.map(l=>'<div class="detail-item">'+link('libraries',l.id,l.name,'',l.hostId)+'<p>'+esc(l.version || "Versão não informada")+' · '+esc(AssessmentDashboard.libraryObservation(l))+'</p></div>').join('')+'</section>';
      }
      if(kind==='servers'){
        const ds=(detailData.datasources || []).filter(d=>AssessmentDashboard.serverAssociation(d,r));
        if(ds.length)body+="<section class=\"detail-section\"><h3>Datasources JDBC</h3>"+ds.map(d=>'<div class="detail-item">'+link('datasources',d.id,d.name,'',d.hostId)+'<p>'+esc(d.driverName || "Driver não informado")+' · '+esc(d.driverVersion || "Versão não informada")+'</p><p>Destino configurado: '+esc(AssessmentDashboard.databaseDestination(d)||'Não identificado')+'</p>'+datasourceEvidence(d)+'</div>').join('')+'</section>';
      }
    }
    openDrawer(r.hostname || r.name || r.version || "Coleta "+String(r.id).slice(0,8),tabs.find(t=>t[0]===kind)?.[1] || (kind==='datasources'?"Datasource JDBC":"Detalhes do inventário"),body);
  }
  function openModal(title,kicker,body,submit,handler) {
    modalHandler=handler;$('modal-title').textContent=title;$('modal-kicker').textContent=kicker;$('modal-body').innerHTML=body;$('modal-submit').textContent=submit;$('modal-submit').disabled=false;$('modal-error').hidden=true;$('modal').showModal();
  }
  function field(label,name,type,val,extra='',hint=''){return '<label class="field">'+esc(label)+'<input name="'+name+'" type="'+type+'" value="'+esc(val??'')+'" '+extra+'>'+ (hint?'<small>'+esc(hint)+'</small>':'')+'</label>';}
  const uninstallLabels={WAITING:'Aguardando conexão',UNINSTALLING:'Desinstalando',SUCCEEDED:'Agente desinstalado',FAILED:'Falha na desinstalação',UNCONFIRMED:'Conclusão não confirmada',CANCELLED:'Solicitação cancelada',EXPIRED:'Solicitação expirada'};
  function uninstallAgent() {
    if(!AssessmentAccess.isAdmin())return;
    if(AssessmentAgentUpdates.active(host(machineId))){notify('Aguarde a atualização do agente ser resolvida.',true);return;}
    closeActions();const h=host(machineId);if(!h)return;const r=h.uninstall,name=h.hostname || h.id;
    let body='<p>Máquina: <strong>'+esc(name)+'</strong></p><p>Somente o serviço e os arquivos próprios do coletor serão removidos. O último inventário fica no histórico; JVMs e aplicações são preservadas.</p>';
    if(r){body+='<p role="status"><strong>'+esc(uninstallLabels[r.status] || r.status)+'</strong></p><p>Solicitado em '+esc(r.createdAt)+' · validade: '+esc(r.expiresAt)+'</p><ul>'+(r.audit || []).map(e=>'<li>'+esc(e.time)+' · '+esc(uninstallLabels[e.status] || e.status)+'</li>').join('')+'</ul>';}
    if(r?.status==='WAITING'){
      openModal('Desinstalação do agente','AGENTE · HISTÓRICO',body+'<p>O pedido pode ser cancelado até o agente iniciar a remoção.</p>','Cancelar solicitação',async()=>{await api('hosts/uninstall/cancel',{method:'POST',body:{hostId:h.id,requestId:r.id}});notify('Solicitação cancelada.');await loadState(false);});return;
    }
    if(['UNINSTALLING','UNCONFIRMED','SUCCEEDED'].includes(r?.status)){
      openModal('Desinstalação do agente','AGENTE · HISTÓRICO',body+(r.status==='UNCONFIRMED'?'<p>A ausência de heartbeat não confirma a remoção. O executor repete a confirmação durante até 24 horas; depois é necessária inspeção local.</p>':''),'Fechar',async()=>{});return;
    }
    if(h.uninstallCapability!=='uninstall-v1'||h.credentialStatus!=='active'){
      openModal('Atualização do agente necessária','DESINSTALAÇÃO NÃO COMPATÍVEL',body+'<p>Este agente precisa do pacote atualizado em modo serviço root (systemd ou SysV). Instalações com cron ou execução manual não suportam remoção remota.</p>','Fechar',async()=>{});return;
    }
    const requestKey=crypto.randomUUID();
    body+='<p>O pedido vale por 1 hora. O agente o recebe pelo canal HTTPS após terminar a coleta atual. Enquanto pendente, novas coletas ficam bloqueadas.</p>'+field('Digite exatamente o nome da máquina','confirmationName','text','','required autocomplete="off"');
    openModal('Desinstalar agente','CONFIRMAÇÃO INDIVIDUAL',body,'Solicitar desinstalação',async(f)=>{if(f.get('confirmationName')!==name)throw new Error('Digite exatamente o nome da máquina.');await api('hosts/uninstall',{method:'POST',body:{hostId:h.id,confirmationName:f.get('confirmationName'),requestKey,expiresInMinutes:60}});notify('Desinstalação solicitada. Aguardando conexão do agente.');await loadState(false);});
    $('modal-form').elements.confirmationName.addEventListener('input',()=>{$('modal-error').hidden=true;});
  }
  function runAssessment() {
    if(!AssessmentAccess.isAdmin())return;
    closeActions();const total=count('hosts');
    if(!total){openModal("Conectar um agente",'COLETA',"<p>Crie um ambiente, baixe o instalador configurado e execute-o em uma máquina. As máquinas cadastradas aparecem automaticamente.</p>","Fechar",async()=>{});return;}
    const selectedMachine=contextKind==='machine',target=selectedMachine?hostName(machineId):AssessmentEnvironments.label(rawData,environmentScope);
    openModal("Executar uma coleta","COLETA SOB DEMANDA","<p>Solicitar uma coleta com limites definidos para "+(selectedMachine?'<strong>'+esc(target)+'</strong>':'todas as <strong>'+total+" máquinas</strong> em "+esc(target))+". Cada máquina usa seus limites efetivos de coleta.</p><p>Máquinas com acesso revogado ou coleta já na fila ou em execução são ignoradas. Acompanhe o andamento em Solicitações.</p>","Solicitar coleta",async()=>{const result=await api('jobs/run-scope',{method:'POST',body:{environmentId:environmentScope,hostId:selectedMachine?machineId:''}});notify(result.queued+" coleta(s) na fila"+(result.skipped?' · '+result.skipped+" ignorados":'')+'.');setTab('jobs');});
  }
  function editSettings(hostId='') {
    if(!AssessmentAccess.isAdmin())return;
    closeActions();
    if(hostId||contextKind==='machine'){AssessmentEnvironments.editHostSettings(hostId || machineId);return;}
    if(environmentScope&&environmentScope!=='__unassigned__'){AssessmentEnvironments.openEdit(environmentScope);return;}
    if(contextKind!=='root'||environmentScope){notify("Escolha uma máquina ou um ambiente para editar suas configurações. Os padrões gerais estão disponíveis na Visão geral, com Todos os ambientes selecionado.");return;}
    if(!data)return;const selectedHost=hostId?host(hostId):null;if(hostId&&!selectedHost)return;const s={...(rawData.settings || {}),...(selectedHost?.settings || {})};
    const body='<label class="checkbox-field"><input type="checkbox" name="enabled" '+(s.enabled?'checked':'')+"><span><strong>Ativar coletas agendadas</strong><small>Mantenha desativado para executar apenas coletas sob demanda.</small></span></label><div class=\"form-grid\"><label class=\"field\">Frequência<select name=\"frequency\">"+['once','daily','weekly'].map(v=>'<option value="'+v+'" '+(s.frequency===v?'selected':'')+'>'+({once:"Uma vez",daily:"Diária",weekly:"Semanal"}[v])+'</option>').join('')+"</select></label><label class=\"field\">Fuso horário<select name=\"timeZone\">"+[...new Set(['America/Sao_Paulo','UTC',s.timeZone].filter(Boolean))].map(z=>'<option '+(s.timeZone===z?'selected':'')+'>'+z+'</option>').join('')+'</select></label>'+field("Início da janela",'windowStart','time',s.windowStart || '00:00','required')+field("Fim da janela",'windowEnd','time',s.windowEnd || '06:00','required')+"</div><h3 class=\"form-subhead\">Limites da coleta</h3><div class=\"form-grid\">"+field("Duração máxima (segundos)",'maxDurationSeconds','number',s.maxDurationSeconds??180,'min="10" max="900" required')+field("Quantidade máxima de arquivos",'maxFiles','number',s.maxFiles??50000,'min="100" max="500000" required')+field("Arquivos por lote",'batchSize','number',s.batchSize??100,'min="1" max="500" required')+field("Pausa entre lotes (ms)",'pauseMillis','number',s.pauseMillis??30,'min="0" max="2000" required')+"</div><h3 class=\"form-subhead\">Caminhos de descoberta</h3><div class=\"form-grid\"><label class=\"field full\">Caminhos incluídos<textarea name=\"includePaths\" rows=\"4\" required>"+(s.includePaths || []).map(esc).join('\n')+"</textarea><small>Um caminho absoluto por linha. Use caminhos específicos do seu ambiente Java.</small></label><label class=\"field full\">Caminhos excluídos<textarea name=\"excludePaths\" rows=\"3\">"+(s.excludePaths || []).map(esc).join('\n')+'</textarea></label></div><label class="checkbox-field" style="margin-top:18px"><input type="checkbox" name="inspectArchives" '+(s.inspectArchives?'checked':'')+"><span><strong>Inspecionar arquivos Java</strong><small>Identifica versões de aplicações, frameworks, drivers e datasources. Ativado por padrão, com limites de leitura e tempo.</small></span></label><p class=\"form-note\">As alterações valem para as próximas coletas. Uma coleta em andamento continua com os limites anteriores.</p>";
    openModal(hostId?"Configurações da máquina":"Configurações da coleta",hostId?"CONFIGURAÇÕES · "+hostName(hostId):"PADRÕES GERAIS", (hostId?"<p>Estas configurações se aplicam somente a <strong>"+esc(hostName(hostId))+"</strong>. Ao salvar, serão criadas configurações próprias para esta máquina.</p>":"<p>Os padrões gerais se aplicam às máquinas sem configurações próprias.</p>")+body,"Salvar alterações",async(f)=>{
      const paths=(key)=>String(f.get(key)||'').split(/\r?\n/).map(x=>x.trim()).filter(Boolean);
      const include=paths('includePaths'), exclude=paths('excludePaths');if(!include.length || include.length>20 || exclude.length>20 || [...include,...exclude].some(p=>!p.startsWith('/') || p==='/' || p.includes('..') || p.length>512))throw new Error("Use até 20 caminhos absolutos por lista. Inclua pelo menos um caminho; a raiz do sistema e referências a diretórios superiores não são permitidas.");
      const next={enabled:f.has('enabled'),frequency:f.get('frequency'),timeZone:f.get('timeZone'),windowStart:f.get('windowStart'),windowEnd:f.get('windowEnd'),maxDurationSeconds:Number(f.get('maxDurationSeconds')),maxFiles:Number(f.get('maxFiles')),batchSize:Number(f.get('batchSize')),pauseMillis:Number(f.get('pauseMillis')),includePaths:include,excludePaths:exclude,inspectArchives:f.has('inspectArchives')};
      await api(hostId?'hosts/settings':'settings',{method:'PUT',body:hostId?{hostId,settings:next}:next});notify(hostId?"Configurações da máquina saved.":"Configurações de coleta salvas.");await loadState(true);
    });
  }
  function dateDialog() {
    openModal("Escolher período","FILTRO DO INVENTÁRIO","<p>Filtre pelo horário original da observação. O filtro de datas usa UTC. Registros sem horário permanecem visíveis.</p><div class=\"date-presets\"><button type=\"button\" data-days=\"1\">Hoje (UTC)</button><button type=\"button\" data-days=\"7\">Últimos 7 dias</button><button type=\"button\" data-days=\"30\">Últimos 30 dias</button><button type=\"button\" data-days=\"all\">Todo o período</button></div><div class=\"form-grid\">"+field("De",'from','date',dateFrom)+field("Até",'to','date',dateTo)+'</div>',"Aplicar",async(f)=>{const start=f.get('from'),end=f.get('to');if(start&&end&&start>end)throw new Error("A data inicial deve ser anterior à data final.");dateFrom=start;dateTo=end;page=1;await loadState(false);});
  }
  function columnDialog() {
    const cols=columns().filter(c=>c.key!=='_cleanupSelect');openModal("Escolher colunas da tabela","OPÇÕES DA TABELA","<p>Escolha as colunas exibidas nesta tela.</p>"+cols.map(c=>'<label class="checkbox-field"><input type="checkbox" name="col-'+esc(c.key)+'" '+(columnVisibility[current]?.[c.key]!==false?'checked':'')+'><span>'+esc(c.label)+'</span></label>').join(''),"Aplicar",async(f)=>{if(!cols.some(c=>f.has('col-'+c.key)))throw new Error("Selecione pelo menos uma coluna.");columnVisibility[current]=Object.fromEntries(cols.map(c=>[c.key,f.has('col-'+c.key)]));renderTable();});
  }
  document.addEventListener('submit',event=>{if(event.target.id!=='overview-machine-search'||current!=='overview')return;event.preventDefault();query=$('overview-machine-query').value.trim();page=1;loadState(false);});
  document.addEventListener('click',async(event)=>{
    if(event.target.closest('[data-clear-machine-search]')){query='';page=1;loadState(false);return;}
    if(event.target.closest('#actions-menu button, #actions-menu a')||!event.target.closest('.menu-anchor'))closeActions();
    if(event.target.closest('[data-update-environment]')){if(current==='hosts'&&contextKind!=='machine')openAgentUpdates();return;}
    if(event.target.closest('[data-environment-overview]')){environmentOverview();return;}
    const back=event.target.closest('[data-context-back]');if(back){back.dataset.contextBack==='environment'?environmentOverview():goRoot('environments');return;}
    const tab=event.target.closest('[data-tab]');if(tab){event.preventDefault();if(tab.dataset.tab==='hosts'&&tab.closest('#side-nav'))openAllMachines();else if(tab.dataset.tab==='jobs')tab.closest('#side-nav')?openWorkHistory():setTab('jobs');else if(tab.closest('#side-nav')||tab.classList.contains('brand'))goRoot(tab.dataset.tab);else setTab(tab.dataset.tab);return;}
    const machine=event.target.closest('[data-machine]');if(machine){openMachine(machine.dataset.machine);return;}
    const inventory=event.target.closest('[data-inventory]');if(inventory){setTab(inventory.dataset.inventory,{machineId:inventory.dataset.scope,subset:inventory.dataset.subset});return;}
    const versionGroup=event.target.closest('[data-version-group]');if(versionGroup){versionMode='family';setTab('versions',{machineId:versionGroup.dataset.scope,version:versionGroup.dataset.versionGroup});return;}
    const pageButton=event.target.closest('[data-page]');if(pageButton){page=Number(pageButton.dataset.page);loadState(false);return;}
    const selectedVersion=event.target.closest('[data-select-version]');if(selectedVersion){versionFocus=selectedVersion.dataset.selectVersion;page=1;writeRoute();loadState(false);return;}
    const mode=event.target.closest('[data-version-mode]');if(mode){versionMode=mode.dataset.versionMode;versionFocus='';page=1;writeRoute();loadState(false);return;}
    if(event.target.closest('[data-clear-version]')){versionFocus='';page=1;writeRoute();loadState(false);return;}
    const network=event.target.closest('[data-machine-ips]');if(network){
      const h=host(network.dataset.machineIps);if(h)openDrawer("Endereços IP",hostName(h.id),'<p class="muted">Lista completa informada pelo agente, incluindo endereços locais. A tabela prioriza IPv4 de rede.</p>'+details([['Máquina',esc(hostName(h.id))],['Endereços IP',(h.ipAddresses || []).map(esc).join('<br>') || value(null)]]));return;
    }
    const detail=event.target.closest('[data-detail-kind]');if(detail){showDetail(detail.dataset.detailKind,detail.dataset.detailId,detail.dataset.detailHost);return;}
    const hostCoverage=event.target.closest('[data-host-coverage]');if(hostCoverage){let h=host(hostCoverage.dataset.hostCoverage);const revision=++detailRevision;try{if(data?.scoped)h=(await api('item?'+new URLSearchParams({kind:'hosts',id:hostCoverage.dataset.hostCoverage,hostId:hostCoverage.dataset.hostCoverage,environmentId:environmentScope}))).item;if(h&&revision===detailRevision)openDrawer("Cobertura mais recente da máquina",hostName(h.id),"<p class=\"muted\">Estes detalhes descrevem a coleta mais recente da máquina e podem ser posteriores a esta solicitação.</p>"+coverageHtml(h.coverage));}catch(error){if(revision===detailRevision)notify(error.message,true);}return;}
    const environmentCoverage=event.target.closest('[data-environment-coverage]');if(environmentCoverage){const scoped=AssessmentEnvironments.scope(rawData,environmentCoverage.dataset.environmentCoverage);openDrawer("Cobertura da coleta",AssessmentEnvironments.label(rawData,environmentCoverage.dataset.environmentCoverage),coverageHtml(scoped.coverage));return;}
    if(event.target.closest('[data-settings]'))editSettings();if(event.target.closest('[data-run]'))runAssessment();if(event.target.closest('[data-coverage]'))openCoverage();
    const days=event.target.closest('[data-days]');if(days){const n=days.dataset.days, f=$('modal-form');if(n==='all'){f.elements.from.value='';f.elements.to.value='';}else{f.elements.to.value=new Date().toISOString().slice(0,10);f.elements.from.value=new Date(Date.now()-(Number(n)-1)*86400000).toISOString().slice(0,10);}}
  });
  $('open-agent-updates').onclick=openAgentUpdates;
  $('uninstall-agent').onclick=uninstallAgent;
  $('actions-toggle').onclick=()=>{const open=$('actions-menu').hidden;$('actions-menu').hidden=!open;$('actions-toggle').setAttribute('aria-expanded',String(open));};
  $('nav-toggle').onclick=()=>setNavigation($('side-nav').hidden,{focus:narrowNavigation.matches});
  $('nav-backdrop').onclick=()=>setNavigation(false,{restoreFocus:true});
  narrowNavigation.addEventListener('change',()=>{const restore=narrowNavigation.matches&&$('side-nav').contains(document.activeElement);setNavigation(narrowNavigation.matches?false:desktopNavigationOpen,{restoreFocus:restore});});
  $('search-form').onsubmit=(e)=>{e.preventDefault();query=$('search').value;page=1;loadState(false);$('clear-filters').hidden=!query&&!inventorySubset;};
  $('global-search-form').onsubmit=(e)=>{e.preventDefault();const entered=$('global-search').value;if(['overview','environments','versions','machine','properties','schedule'].includes(current))setTab('runtimes');query=entered;$('global-search').value=entered;$('search').value=query;page=1;loadState(false);$('clear-filters').hidden=!query&&!inventorySubset;};
  $('support-filter').onchange=()=>{supportFilter=$('support-filter').value;page=1;writeRoute();loadState(false);};
  $('clear-filters').onclick=()=>{query='';inventorySubset='';supportFilter='';$('search').value='';$('global-search').value='';page=1;writeRoute();loadState(false);};
  $('host-filter').onchange=()=>{const id=$('host-filter').value;if(id==='__browse__'){contextKind='environment';machineId='';hostFilter='';setTab('hosts');return;}contextKind=id?'machine':'environment';machineId=id;resetTransient();finishNavigation();};
  $('page-size').onchange=()=>{size=Number($('page-size').value);page=1;loadState(false);};$('prev-page').onclick=()=>{page--;loadState(false);};$('next-page').onclick=()=>{page++;loadState(false);};
  $('date-filter').onclick=dateDialog;$('column-settings').onclick=columnDialog;$('run-assessment').onclick=runAssessment;$('empty-assessment').onclick=runAssessment;$('open-settings').onclick=()=>editSettings();$('open-install-guide').onclick=()=>AssessmentEnvironments.openInstallGuide();$('view-coverage').onclick=openCoverage;
  $('refresh').onclick=refreshCurrentView;$('close-drawer').onclick=closeDrawer;$('drawer-backdrop').onclick=closeDrawer;
  $('close-modal').onclick=()=>{$('modal').close();};$('cancel-modal').onclick=()=>{$('modal').close();};
  $('modal-form').onsubmit=async(e)=>{e.preventDefault();if(!modalHandler)return;$('modal-submit').disabled=true;$('modal-error').hidden=true;try{await modalHandler(new FormData(e.target));$('modal').close();}catch(err){$('modal-error').textContent=err.message;$('modal-error').hidden=false;}finally{$('modal-submit').disabled=false;}};
  $('about').onclick=()=>openDrawer('Java Assessment','Sobre o produto',details([['Versão',window.AssessmentRelease?esc(window.AssessmentRelease.version):"Versão indisponível"],["Versão gerada em",window.AssessmentRelease?timeCell(window.AssessmentRelease.builtAt):value(null)]])+"<p>O Java Assessment reúne instalações Java, servidores de aplicação, bibliotecas e configurações JDBC a partir de evidências coletadas nas máquinas cadastradas.</p><div class=\"evidence-box\">Esta aplicação não é o Oracle Java Management Service. A descoberta não comprova certificação do fornecedor, estado de segurança ou compatibilidade de software.</div><section class=\"detail-section\"><h3>Como funciona o inventário</h3><p>Cada coleta respeita os caminhos e limites de tempo e arquivos configurados. Abra um registro para consultar suas evidências e o horário da observação. Valores ausentes são exibidos com um traço.</p><p>A contagem inclui JDKs encontrados e instalações Java observadas em uso. Não é necessário haver compilador. Várias JVMs usando o mesmo diretório Java contam como uma instalação.</p><p>As coletas agendadas ficam desativadas até serem habilitadas nas configurações. Acompanhe as solicitações manuais em Solicitações.</p></section>");
  document.addEventListener('keydown',e=>{
    if(narrowNavigation.matches&&!$('side-nav').hidden){
      if(e.key==='Escape'){e.preventDefault();setNavigation(false,{restoreFocus:true});return;}
      if(e.key==='Tab'){const items=[$('nav-toggle'),...$('side-nav').querySelectorAll('[data-tab]')],first=items[0],last=items[items.length-1];if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}
    }
    if(e.key==='Escape'){closeDrawer();closeActions(true);}
  });
  AssessmentEnrollment.init({isAdmin:AssessmentAccess.isAdmin,api,getData:()=>data,getEnrollmentData:()=>environmentData,refresh:loadState,notify,editHostSettings:editSettings,addMachine:()=>{if(environmentScope&&environmentScope!=='__unassigned__')AssessmentEnvironments.openPackage(environmentScope);else{goRoot('environments');notify("Escolha ou crie um ambiente para baixar o agente configurado.");}}});
  AssessmentEnvironments.init({isAdmin:AssessmentAccess.isAdmin,api,download:downloadPackage,downloadGet,getExportContext:()=>({environmentId:environmentScope,hostId:contextKind==='machine'?machineId:'',hostName:host(machineId)?.hostname || host(machineId)?.fqdn || machineId}),getRaw:()=>rawData,getScoped:()=>data,getScope:()=>environmentScope,select:selectEnvironment,refresh:loadState,notify,navigate:setTab,afterMachineChange});
  AssessmentMachines.init({isAdmin:AssessmentAccess.isAdmin,getRevision:AssessmentAccess.getRevision,api,getHost:id=>(data?.hosts||[]).find(h=>h.id===id),getEnvironments:()=>rawData?.environments||[],getScope:()=>environmentScope,move:AssessmentEnvironments.openAssignment,update:id=>AssessmentAgentUpdates.open({hostId:id,label:hostName(id)}),afterChange:afterMachineChange,notify,closeOtherMenus:()=>{closeActions();AssessmentAccess.closeMenu();}});
  AssessmentMachineCleanup.init({isAdmin:AssessmentAccess.isAdmin,getRevision:AssessmentAccess.getRevision,api,notify,refresh:loadState,change:(subset,seconds)=>{inventorySubset=subset;minimumSeconds=seconds;page=1;writeRoute();loadState(false);}});
  AssessmentReports.init({api,download:(path,options,accept)=>downloadGet(path,{...options,accept})});
  AssessmentWorkRequests.init({canRead:AssessmentAccess.canInventory,isAdmin:AssessmentAccess.isAdmin,getRevision:AssessmentAccess.getRevision,api,select:openWorkRequest,changeEnvironment:selectEnvironment,openAssessment:(id,hostId)=>showDetail('jobs',id,hostId),notify});
  AssessmentAgentUpdates.init({isAdmin:AssessmentAccess.isAdmin,getRevision:AssessmentAccess.getRevision,api,refreshInventory:()=>{loadState(true);AssessmentWorkRequests.refresh(true);},openWorkRequest:openAgentUpdateRequest,notify,closeMenus:()=>{closeActions();AssessmentMachines.closeMenu();AssessmentAccess.closeMenu();}});
  AssessmentAccess.init({onSession:startSession,onSignedOut:clearSession,notify});
  setNavigation(!narrowNavigation.matches);render();AssessmentAccess.start();
})();
