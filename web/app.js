'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const esc = (value) => String(value == null ? '' : value).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const tabs = [
    ['overview','Overview','A summary of your Java estate.'],
    ['environments','Environments','Organize machines and download configured installers.'],
    ['versions','Java versions','Explore installations grouped by version.'],
    ['properties','Fleet properties','Fleet configuration and assessment coverage.'],
    ['hosts','Machines','Hosts reporting to this fleet are listed in the table. Select a host to inspect its resources and collection coverage.'],
    ['runtimes','Java runtimes','Java runtimes discovered in configured assessment paths are listed in the table.'],
    ['applications','Applications','Applications identified by the collector are listed with their original observation time.'],
    ['servers','Java servers','Java servers discovered during the assessment are listed in the table.'],
    ['libraries','Java libraries','Java libraries and JDBC drivers discovered by the collector are listed with their evidence.'],
    ['schedule','Scheduled tasks','Manage when assessments run and set limits on collection work.'],
    ['jobs','Work requests','Track queued, running and completed assessments for your fleet.']
  ];
  let rawData=null, environmentData=null, environmentScope=AssessmentEnvironments.stored(), contextKind='root';
  let data = null, csrf = '', current = 'overview', query = '', hostFilter = '', page = 1, size = 20;
  let dateFrom = '', dateTo = '', modalHandler = null, toastTimer, pollTimer, refreshing = false;
  let machineId = '', versionMode = 'family', versionFocus = '', inventorySubset = '';
  const columnVisibility = {};
  const narrowNavigation = window.matchMedia('(max-width:850px)');
  let desktopNavigationOpen = true;
  let renderedHash='',requestRevision=0,stateController=null,requestKey='',detailRevision=0;
  const count=(key)=>AssessmentDashboard.count(data || {},key);
  function stateParams(){return new URLSearchParams({environmentId:environmentScope,hostId:contextKind==='machine'?machineId:'',view:current==='schedule'?'hosts':current==='properties'?'settings':current,page:String(page),pageSize:String(size),q:query,from:dateFrom,to:dateTo,subset:inventorySubset,version:versionFocus,versionMode});}
  function exportParams(){const params=stateParams();params.delete('page');params.delete('pageSize');return params.toString();}

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
    else if(isView(path)){current=path;contextKind=path==='overview'?'root':'environment';}
    const subset=params.get('subset');inventorySubset=(subset==='runtime'&&current==='servers'||subset==='datasources'&&current==='libraries')?subset:'';
    versionMode=params.get('group')==='exact'?'exact':'family';versionFocus=params.get('version') || '';
    renderedHash=location.hash;
  }
  function routeHash() {
    const params=new URLSearchParams({env:environmentScope===''?'all':environmentScope==='__unassigned__'?'unassigned':environmentScope});
    let path=current;
    if(contextKind==='machine'){path='machine/'+encodeURIComponent(machineId);params.set('view',routeView());}
    else if(contextKind==='environment')path='environment/'+routeView();
    if(inventorySubset)params.set('subset',inventorySubset);
    if(current==='versions'){if(versionMode==='exact')params.set('group','exact');if(versionFocus)params.set('version',versionFocus);}
    return '#'+path+'?'+params.toString();
  }
  function writeRoute(replace=false) {
    const hash=routeHash(),state={contextKind,view:routeView(),environmentId:environmentScope,machineId,inventorySubset};
    history[replace||location.hash===hash?'replaceState':'pushState'](state,'',hash);renderedHash=hash;
  }
  function resetTransient() {query='';inventorySubset='';versionFocus='';dateFrom='';dateTo='';page=1;$('search').value='';$('global-search').value='';}
  function projectData() {
    if(!rawData){environmentData=null;data=null;return;}
    environmentScope=AssessmentEnvironments.validScope(rawData,environmentScope);
    AssessmentEnvironments.persist(environmentScope);
    if(rawData.scoped){const scope=rawData.scope || {},id=scope.machineId || scope.hostId || '',machine=(rawData.hosts || []).find(h=>h.id===id);rawData.scope={...scope,unassigned:scope.unassigned || scope.environmentId==='__unassigned__',machineId:id,machineName:machine?.hostname || machine?.fqdn || id,name:AssessmentEnvironments.label(rawData,environmentScope)};environmentData=rawData;data=rawData;hostFilter=contextKind==='machine'?machineId:'';return;}
    environmentData=AssessmentEnvironments.scope(rawData,environmentScope);
    if(contextKind==='machine'&&!(environmentData.hosts || []).some(h=>h.id===machineId)){contextKind='environment';machineId='';hostFilter='';if(current==='machine')current='overview';resetTransient();}
    hostFilter=contextKind==='machine'?machineId:'';
    data=contextKind==='machine'?AssessmentDashboard.scoped(environmentData,machineId):environmentData;
  }
  function finishNavigation(replace=false) {closeActions();hostFilter=contextKind==='machine'?machineId:'';closeMobileNavigation();closeDrawer();writeRoute(replace);data=null;render();loadState(false);window.scrollTo({top:0,behavior:'instant'});}
  function closeActions(restoreFocus=false) {
    const wasOpen=!$('actions-menu').hidden;
    $('actions-menu').hidden=true;$('actions-toggle').setAttribute('aria-expanded','false');
    if(wasOpen&&restoreFocus)$('actions-toggle').focus();
  }
  function renderActions() {
    $('run-assessment').hidden=data?.scoped?count('hosts')===0:!(data?.hosts || []).some(h=>h.credentialStatus!=='revoked');
    const machine=contextKind==='machine',environment=!!environmentScope&&environmentScope!=='__unassigned__',defaults=contextKind==='root'&&!environmentScope;
    $('open-settings').hidden=!data||!(machine||environment||defaults);
    $('open-settings').textContent=machine?'Machine settings':environment?'Environment settings':'Default assessment settings';
    $('actions-menu').querySelector('[data-enrollment-list]').hidden=!data||machine;
  }
  readRoute();
  const icon = (name) => '<svg aria-hidden="true"><use href="#i-'+name+'"/></svg>';
  function setNavigation(open,options={}) {
    const mobile=narrowNavigation.matches;
    if(!mobile)desktopNavigationOpen=open;
    $('side-nav').hidden=!open;
    $('nav-backdrop').hidden=!(mobile&&open);
    $('nav-toggle').setAttribute('aria-expanded',String(open));
    $('nav-toggle').setAttribute('aria-label',open?'Close navigation':'Open navigation');
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
    return esc(d.toLocaleString('en-US', {month:'short',day:'2-digit',year:'numeric',...(compact ? {} : {hour:'2-digit',minute:'2-digit',hour12:false,timeZoneName:'short'})}));
  }
  function bytes(value) {
    if (value == null || value === '') return '—';
    const n = Number(value); if (!Number.isFinite(n)) return '—';
    if (n === 0) return '0 B'; const units = ['B','KiB','MiB','GiB','TiB'];
    const power = Math.min(Math.floor(Math.log(Math.abs(n))/Math.log(1024)),4);
    return (n / Math.pow(1024,power)).toLocaleString('en-US',{maximumFractionDigits:1})+' '+units[power];
  }
  const value = (v) => v == null || v === '' ? '<span class="unknown">—</span>' : esc(v);
  function badge(v,coverage=false) {
    if (!v) return value(null);
    const str = String(v), low = str.toLowerCase();
    if(str.toUpperCase()==='PROCESS_RUNNING')return '<span class="badge neutral">Process observed</span>';
    const color = /failed|error|stopped|shutdown/.test(low) ? 'error' : /queued|pending|unknown/.test(low) ? 'warning' : /partial/.test(low) ? 'neutral' : /running|complete|success|succeeded|confirmed/.test(low) ? '' : 'neutral';
    return '<span class="badge '+color+'"'+(low==='partial'?' title="'+esc(AssessmentDashboard.limitationsDescription)+'"':'')+'>'+esc(AssessmentDashboard.assessmentStatus(str,coverage))+'</span>';
  }
  const host = (id) => (data?.hosts || []).find((h) => h.id === id);
  const hostName = (id) => host(id)?.hostname || host(id)?.fqdn || id || '—';
  const link = (kind,id,label,sub,hostId='') => '<button class="link" data-detail-kind="'+esc(kind)+'" data-detail-id="'+esc(id)+'" data-detail-host="'+esc(hostId)+'">'+esc(label || 'Unnamed')+'</button>'+(sub ? '<span class="cell-sub" title="'+esc(sub)+'">'+esc(sub)+'</span>' : '');
  const hostLink = (id) => host(id) ? link('hosts',id,hostName(id)) : value(id);
  const pathCell = (path) => '<span class="cell-path" title="'+esc(path || '')+'">'+value(path)+'</span>';
  const timeCell = (v) => '<span class="date-cell">'+date(v)+'</span>';
  const observationCell = r => '<span class="badge neutral">'+esc(AssessmentDashboard.observation(r).label)+'</span>';
  const applicationCell = (r,context=data) => {
    return AssessmentDashboard.associations(context,r).map(a=>'<div class="association-item">'+(a.application?link('applications',a.application.id,a.name,'',a.hostId):value(a.name || (a.serverName?'Server: '+a.serverName:'Java process')))+(a.pid?'<span class="cell-sub">PID '+esc(a.pid)+'</span>':'')+'</div>').join('') || value(null);
  };
  const datasourceEvidence = r => {const evidence=AssessmentDashboard.datasourceObservation(r);return '<span class="badge neutral datasource-'+esc(evidence.kind)+'">'+esc(evidence.label)+'</span>';};
  const libraryEvidence = r => r._kind==='datasources'?datasourceEvidence(r):String(r.usage || '').toLowerCase()==='found'?'<span class="badge neutral">'+AssessmentDashboard.libraryObservation(r)+'</span>':badge(r.usage);
  const column = (key,label,render) => ({key,label,render});
  function libraryRows() {
    const result = (data?.libraries || []).map((r) => ({...r,_kind:'libraries'}));
    (data?.datasources || []).forEach((d) => result.push({...d,_kind:'datasources',category:'JDBC datasource',usage:AssessmentDashboard.datasourceObservation(d).label,version:d.driverVersion,name:d.name || d.driverName}));
    return result;
  }
  function columns() {
    const hostCol = column('host','Managed instance',(r) => hostLink(r.hostId));
    const last = column('observedAt','Last reported',(r) => timeCell(r.observedAt));
    switch(current) {
      case 'hosts': return [column('hostname','Host / FQDN',r=>link('hosts',r.id,r.hostname || r.fqdn,r.fqdn !== r.hostname ? r.fqdn : '')),column('environment','Environment',r=>value(r.environmentId?r.environmentName || r.environmentId:'Unassigned')),column('os','Operating system',r=>value(r.distribution || r.osName)),column('ip','IP addresses',r=>(r.ipAddresses || []).map(esc).join('<br>') || value(null)),column('architecture','Architecture',r=>value(r.architecture)),column('collector','Collector Java',r=>value(r.collectorJavaVersion)),column('connection','Collector contact',r=>{const p=AssessmentDashboard.collectorPresence(data,r);return '<span class="presence '+esc(p.state)+'"><i></i>'+esc(p.label)+'</span>'; }),column('lastSeen','Last seen',r=>timeCell(r.lastSeen))];
      case 'runtimes': return [column('version','Runtime version',r=>link('runtimes',r.id,r.version,'',r.hostId)),column('vendor','Vendor',r=>value(r.vendor)),column('kind','Runtime',r=>value(r.kind)),column('inUse','In use',r=>r.inUse === true ? badge('Confirmed') : r.inUse === false ? '<span class="muted">Not observed</span>' : value(null)),hostCol,column('javaHome','Installation path',r=>pathCell(r.javaHome)),last];
      case 'servers': return [column('name','Server',r=>link('servers',r.id,r.type || r.name,r.type && r.name !== r.type ? r.name : '',r.hostId)),column('version','Version',r=>value(r.version)),column('applications','Associated applications',r=>{const n=data.scoped?r.applicationCount:(data.applications || []).filter(a=>a.hostId === r.hostId && a.serverName && a.serverName === r.name).length;return n ? String(n) : '<span class="unknown" title="No application relationship has been reported">—</span>';}),hostCol,column('state','Evidence',observationCell),last];
      case 'applications': return [column('name','Application',r=>link('applications',r.id,r.name,'',r.hostId)),column('type','Type',r=>value(r.applicationType || r.type)),column('version','Application version',r=>value(r.version)),column('frameworkVersion','Framework version',r=>value(r.frameworkVersion)),column('server','Java server',r=>value(r.serverName)),hostCol,column('state','Evidence',observationCell),last];
      case 'libraries': return [column('name','Library / JDBC datasource',r=>link(r._kind,r.id,r.name,r._kind === 'datasources' ? r.configurationSource || [r.serverName,r.driverName].filter(Boolean).join(' · ') : r.category,r.hostId)),column('version','Version',r=>value(r.version)),column('category','Type',r=>value(r.category)),column('application','Associated application',applicationCell),hostCol,column('usage','Usage evidence',libraryEvidence),last];
      case 'jobs': return [column('id','Work request',r=>link('jobs',r.id,'Assessment '+String(r.id).slice(0,8),'',r.hostId)),hostCol,column('status','Status',r=>badge(r.status)),column('createdAt','Requested',r=>timeCell(r.createdAt)),column('finishedAt','Completed',r=>timeCell(r.finishedAt)),column('summary','Result',r=>value(r.error || r.summary))];
      default: return [];
    }
  }
  function rows() { return current === 'libraries' ? libraryRows() : data?.[current] || []; }
  function matchedRows() {
    if(data?.scoped)return rows();
    const text = query.trim().toLowerCase();
    return rows().filter((r) => {
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
  async function api(path, options={}) {
    const opts={credentials:'same-origin',cache:'no-store',...options,headers:{Accept:'application/json',...options.headers}};
    if (options.body !== undefined) {opts.headers['Content-Type']='application/json';opts.body=JSON.stringify(options.body);}
    if (options.method && options.method !== 'GET' && csrf) opts.headers['X-CSRF-Token']=csrf;
    const response=await fetch('/api/'+path,opts);
    let result;try {result=await response.json();} catch (_) {result={error:response.ok ? 'Unexpected server response.' : 'The server could not complete the request.'};}
    if (!response.ok) { if (response.status===401) showLogin(); throw new Error(result.error || 'Request failed ('+response.status+').'); }
    return result;
  }
  async function downloadPackage(path,body){
    const response=await fetch('/api/'+path,{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json','Accept':'application/zip','X-CSRF-Token':csrf},body:JSON.stringify(body)});
    if(!response.ok){let message='The package could not be downloaded.';try{message=(await response.json()).error || message;}catch(_){}if(response.status===401)showLogin();throw new Error(message);}
    if(!(response.headers.get('Content-Type') || '').includes('application/zip'))throw new Error('The server returned an unexpected package format.');
    const disposition=response.headers.get('Content-Disposition') || '';const match=disposition.match(/filename="([^"]+)"/i);return {blob:await response.blob(),filename:match?match[1]:'assessment-environment.zip'};
  }
  function selectEnvironment(id,options={}) {
    environmentScope=AssessmentEnvironments.validScope(rawData,id);machineId='';hostFilter='';resetTransient();
    if(options.overview){contextKind='environment';current='overview';}
    else if(contextKind!=='root'){contextKind='environment';if(current==='machine')current='overview';}
    if(options.machineId){openMachine(options.machineId);return;}
    finishNavigation();
  }
  function showLogin() { requestRevision++;stateController?.abort();closeActions();AssessmentEnvironments.clear();AssessmentEnrollment.clear();clearInterval(pollTimer);$('login-layer').hidden=false;$('logout').hidden=true;setTimeout(()=>$('password').focus(),50); }
  async function loadState(quiet) {
    const key=stateParams().toString();if(quiet&&refreshing&&key===requestKey)return;
    const revision=++requestRevision;requestKey=key;stateController?.abort();stateController=new AbortController();refreshing=true;$('refresh').classList.add('spin');$('main-content').setAttribute('aria-busy','true');
    try {const next=await api('state?'+key,{signal:stateController.signal});if(revision!==requestRevision)return;rawData=next;page=next.pagination?.page || page;projectData();writeRoute(true);render();if(!quiet)$('last-refresh').textContent='Updated '+new Date().toLocaleTimeString('en-US',{hour:'2-digit',minute:'2-digit',hour12:false});}
    catch(e){if(revision===requestRevision&&e.name!=='AbortError'){notify(e.message,true);if(!data){$('special-view').innerHTML='<div class="dash-empty" role="alert">Unable to load this view. Use Refresh to try again.</div>';$('empty-description').textContent='Unable to load this view. Use Refresh to try again.';}}}
    finally {if(revision===requestRevision){refreshing=false;$('refresh').classList.remove('spin');$('main-content').removeAttribute('aria-busy');}}
  }
  async function startSession() {
    try {const session=await api('session');if(!session.authenticated){showLogin();return;}csrf=session.csrfToken || ''; $('login-layer').hidden=true;$('logout').hidden=false;await loadState(false);clearInterval(pollTimer);pollTimer=setInterval(()=>{if(!document.hidden)loadState(true);},15000);}
    catch(_){showLogin();}
  }
  function goRoot(id='overview') {contextKind='root';current=id==='environments'?'environments':'overview';machineId='';hostFilter='';resetTransient();finishNavigation();}
  function setTab(id,options={}) {
    if(id==='environments'){goRoot(id);return;}
    if(!isView(id))return;
    if(contextKind==='root')contextKind='environment';
    if(options.machineId){if(!rawData?.scoped&&!(environmentData?.hosts || []).some(h=>h.id===options.machineId))return;contextKind='machine';machineId=options.machineId;}
    current=contextKind==='machine'&&id==='overview'?'machine':id;resetTransient();
    inventorySubset=options.subset || '';versionFocus=options.version || '';
    finishNavigation();
  }
  function openMachine(id) {
    if(!rawData?.scoped&&!(environmentData?.hosts || []).some(h=>h.id===id)){notify('This machine is outside the selected environment.',true);return;}
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
    $('context-kicker').textContent=contextKind==='machine'?'MACHINE WORKSPACE':'ENVIRONMENT WORKSPACE';
    $('context-title').textContent=machine?.hostname || machine?.fqdn || (contextKind==='machine'?machineId:environmentName);
    const hosts=count('hosts'),runtimes=count('runtimes');
    $('context-description').textContent=(contextKind==='machine'?environmentName+' · ':'')+hosts+' machine'+(hosts===1?'':'s')+' · '+runtimes+' Java installation'+(runtimes===1?'':'s')+' · All views and exports use this scope';
    $('context-back').textContent=contextKind==='machine'?'← '+environmentName:'← Environments';
    $('context-back').dataset.contextBack=contextKind==='machine'?'environment':'root';
  }
  function render() {
    renderActions();
    document.body.classList.toggle('summary-view',['overview','environments','versions','machine'].includes(current));
    $('tabs').hidden=contextKind==='root';
    $('tabs').innerHTML=tabs.filter(([id])=>id!=='environments').map(([id,title])=>{const active=routeView()===id,label=id==='properties'?(contextKind==='machine'?'Machine properties':'Environment properties'):title;return '<button type="button" class="'+(active?'active':'')+'" data-tab="'+id+'" aria-current="'+(active?'page':'false')+'">'+label+'</button>';}).join('');
    $('side-nav').querySelectorAll('[data-tab]').forEach(item=>{const active=contextKind==='root'&&item.dataset.tab===current;item.classList.toggle('active',active);if(active)item.setAttribute('aria-current','page');else item.removeAttribute('aria-current');});
    renderContext();
    const selected=tabs.find(t=>t[0]===current) || ['machine','Machine overview','Resources and Java inventory for this host.'];$('view-title').textContent=current==='properties'?(contextKind==='machine'?'Machine properties':'Environment properties'):selected[1];$('view-description').textContent=selected[2];
    $('standard-view').hidden=['overview','environments','versions','machine','properties','schedule'].includes(current);$('special-view').hidden=!$('standard-view').hidden;
    if(!$('standard-view').hidden)$('special-view').innerHTML='';
    $('view-actions').innerHTML='';
    if(!data){$('special-view').innerHTML='<div class="dash-empty" role="status">Loading inventory…</div>';$('inventory-table').querySelector('tbody').innerHTML='';$('table-footer').hidden=true;$('result-count').textContent='';$('empty-description').textContent='Loading inventory…';$('empty-assessment').hidden=true;$('inventory-table').querySelector('thead').innerHTML='<tr>'+columns().map(c=>'<th>'+c.label+'</th>').join('')+'</tr>';$('empty-state').hidden=false;return;}
    $('fleet-name').textContent=data.fleet?.name || 'Java assessment';$('fleet-description').textContent=data.fleet?.description || 'Java estate assessment pilot';
    const status=data.coverage?.status || 'pending';$('fleet-status').textContent=status==='complete'?'Assessed':AssessmentDashboard.assessmentStatus(status,true);$('fleet-status').className='badge '+(status==='partial'?'assessment-ready':status==='failed'?'error':status==='complete'?'':'neutral');
    const warnings=data.coverage?.warnings || [];let heading,description;
    if(status==='pending'){heading='Waiting for the first assessment';description='Run a bounded assessment on a connected host to populate verified inventory.';}
    else if(status==='partial'){heading='Assessment ready';description=AssessmentDashboard.coverageDescription(data.coverage)+(warnings.length?' '+warnings.length+' coverage '+(warnings.length===1?'note is':'notes are')+' available.':'');}
    else if(status==='failed'){heading='Assessment failed';description='The assessment could not complete. Open Work requests to review the error.';}
    else if(!data.settings?.inspectArchives){heading='Archive inspection is not enabled';description='Inventory uses configured paths and process metadata. Enable archive inspection in Assessment settings when deeper library discovery is needed.';}
    else {heading='Assessment coverage is complete';description='Configured paths were assessed within the collection limits. Inventory reflects the evidence available to the collector.';}
    $('notice-title').textContent=heading;$('notice-text').textContent=description;$('coverage-notice').classList.toggle('complete',status==='complete' && !!data.settings?.inspectArchives);$('coverage-notice').classList.toggle('notes',status==='partial');
    $('coverage-notice').querySelector('svg use').setAttribute('href',status==='complete' && data.settings?.inspectArchives?'#i-check':'#i-warning');
    $('date-label').textContent=dateFrom || dateTo ? (dateFrom || 'Any time')+' – '+(dateTo || 'Now')+' UTC' : 'All time';
    $('host-filter').innerHTML='<option value="">All machines in this environment scope</option>'+(environmentData?.hosts || []).map(h=>'<option value="'+esc(h.id)+'">'+esc(h.hostname || h.fqdn || h.id)+'</option>').join('');$('host-filter').value=hostFilter;if(data.scoped){$('host-filter').innerHTML='<option value="">All machines in this environment scope</option>'+(hostFilter?'<option value="'+esc(hostFilter)+'">'+esc(hostName(hostFilter))+'</option>':'')+'<option value="__browse__">Find a machine…</option>';$('host-filter').value=hostFilter;}
    $('clear-filters').hidden=!query&&!inventorySubset;
    $('subset-filter').hidden=!inventorySubset;$('subset-filter').textContent=inventorySubset==='runtime'?'Runtime evidence only':'JDBC datasources only';
    if(current==='overview')$('special-view').innerHTML=AssessmentDashboard.overview(data);else if(current==='versions')$('special-view').innerHTML=AssessmentDashboard.versions(data,versionMode,versionFocus);else if(current==='machine')$('special-view').innerHTML=AssessmentDashboard.machine(data,machineId);else if(current==='properties')renderProperties();else if(current==='schedule')$('special-view').innerHTML=AssessmentEnvironments.schedule(data);else if(current!=='environments')renderTable();
    AssessmentEnrollment.render(data,current,machineId);
    AssessmentEnvironments.render(rawData,current,machineId,environmentScope,data);
    $('last-refresh').textContent=data.serverTime?'As of '+new Date(data.serverTime).toLocaleTimeString('en-US',{hour:'2-digit',minute:'2-digit',hour12:false}):'Inventory loaded';
  }
  function renderTable() {
    if(!data)return;
    const all=matchedRows(), cols=columns().filter(c=>columnVisibility[current]?.[c.key]!==false),total=data.scoped?Number(data.pagination?.total || 0):all.length;
    const pages=data.scoped?Math.max(1,data.pagination?.pages || 1):Math.max(1,Math.ceil(total/size));page=data.scoped?(data.pagination?.page || page):Math.min(page,pages);
    $('inventory-table').querySelector('thead').innerHTML='<tr>'+cols.map(c=>'<th scope="col">'+c.label+'</th>').join('')+'</tr>';
    const visible=data.scoped?all:all.slice((page-1)*size,page*size);
    $('inventory-table').querySelector('tbody').innerHTML=visible.map(r=>'<tr>'+cols.map(c=>'<td>'+c.render(r)+'</td>').join('')+'</tr>').join('');
    $('empty-state').hidden=visible.length>0;$('table-footer').hidden=total===0;
    const noData=!query&&!dateFrom&&!dateTo&&!inventorySubset&&total===0;$('empty-description').textContent=noData ? current==='jobs'?'No assessments have been requested yet. Run an assessment on a connected host.':'No matching inventory has been reported. Run an assessment and review the configured paths and coverage.' : 'Search again using different filters, a wider date range, or another managed instance.';
    $('empty-assessment').hidden=!noData;$('result-count').textContent=total ? total+' '+(total===1?'item':'items') : '';
    $('row-summary').textContent=total ? ((page-1)*size+1)+'–'+Math.min((page-1)*size+visible.length,total)+' of '+total+' items' : '';
    $('page-label').textContent='Page '+page+' of '+pages;$('prev-page').disabled=page<=1;$('next-page').disabled=page>=pages;
  }
  function details(items) {return '<dl class="details-list">'+items.map(([k,v])=>'<div><dt>'+esc(k)+'</dt><dd>'+v+'</dd></div>').join('')+'</dl>';}
  function renderProperties() {
    const f=data.fleet || {},coverage=data.coverage || {},stats=coverage.stats || {};
    const selectedEnvironment=(rawData.environments || []).find(e=>e.id===environmentScope),selectedMachine=contextKind==='machine'?host(machineId):null;
    const information=selectedMachine?[['Hostname',value(selectedMachine.hostname || selectedMachine.fqdn)],['Environment',value(selectedMachine.environmentId?selectedMachine.environmentName || selectedMachine.environmentId:'Unassigned')],['Operating system',value(selectedMachine.distribution || selectedMachine.osName)],['Architecture',value(selectedMachine.architecture)]]:selectedEnvironment?[['Name',value(selectedEnvironment.name)],['Description',value(selectedEnvironment.description)],['Created',timeCell(selectedEnvironment.createdAt)],['Product','Independent Java assessment pilot']]:[['Scope',value(AssessmentEnvironments.label(rawData,environmentScope))],['Fleet',value(f.name)],['Region',value(f.region)],['Product','Independent Java assessment pilot']];
    $('special-view').innerHTML='<div class="overview-totals">'+[['Managed instances',count('hosts')],['Java runtimes',count('runtimes')],['Java servers',count('servers')],['Java libraries',count('libraries')]].map(([label,n])=>'<div class="overview-total"><span>'+label+'</span><strong>'+n+'</strong></div>').join('')+'</div><div class="property-grid"><section class="property-card"><h3>'+(selectedMachine?'Machine information':'Environment information')+'</h3>'+details(information)+'</section><section class="property-card"><h3>Assessment coverage</h3>'+details([['Status',badge(coverage.status || 'pending',true)],['Last assessment',timeCell(coverage.lastAssessmentAt)],['Files visited',value(stats.filesVisited)],['Duration',stats.durationMillis != null?esc((stats.durationMillis/1000).toFixed(1)+' seconds'):value(null)],['Archives',data.settings?.inspectArchives?'Enabled':'Not inspected']])+'<button class="text-button" data-coverage style="margin-top:14px">View coverage details</button></section><section class="property-card"><h3>Collection paths</h3>'+details([['Included',(data.settings?.includePaths || []).map(p=>'<div class="cell-path">'+esc(p)+'</div>').join('') || value(null)],['Excluded',(data.settings?.excludePaths || []).map(p=>'<div class="cell-path">'+esc(p)+'</div>').join('') || value(null)]])+'</section><section class="property-card"><h3>Assessment controls</h3><p class="muted">Assessments are bounded by time and file limits. A host runs one assessment at a time.</p><button class="button" data-settings>Assessment settings</button><p class="muted" style="font-size:12px">Discovery evidence is not a compatibility certification or a vulnerability assessment.</p></section></div>';
  }
  function renderSchedule() {
    const s=data.settings || {};const active=!!s.enabled;
    $('special-view').innerHTML='<section class="schedule-card"><div><h3>Assessment schedule '+badge(active?'Enabled':'Disabled')+'</h3><p>'+(active?'Automatic assessments are queued during the configured window.':'Automatic assessments are disabled. You can run an assessment manually.')+'</p>'+details([['Frequency',value(s.frequency)],['Collection window',esc((s.windowStart || '—')+' – '+(s.windowEnd || '—'))],['Time zone',value(s.timeZone)]])+'<button class="button" data-settings>Edit schedule</button></div><div><h3>Collection limits</h3><p>Limits reduce assessment work on legacy hosts. If a limit is reached, collected findings are retained and the assessment completes with limitations.</p><div class="limits-grid">'+[['Maximum duration',s.maxDurationSeconds!=null?s.maxDurationSeconds+' seconds':'—'],['Maximum files',s.maxFiles!=null?Number(s.maxFiles).toLocaleString():'—'],['Batch size',s.batchSize??'—'],['Pause between batches',s.pauseMillis!=null?s.pauseMillis+' ms':'—']].map(([k,v])=>'<div><span>'+esc(k)+'</span><strong>'+esc(v)+'</strong></div>').join('')+'</div></div></section><section class="property-card secondary-section"><h3>On-demand assessment</h3><p class="muted">Queue an assessment for one host using the same collection limits. A collector must be connected to accept it.</p><button class="button primary" data-run>Run assessment</button></section>';
  }
  function openDrawer(title,kind,html) {$('drawer-title').textContent=title;$('drawer-kind').textContent=kind;$('drawer-content').innerHTML=html;$('detail-drawer').hidden=false;$('drawer-backdrop').hidden=false;$('close-drawer').focus();}
  function closeDrawer() {detailRevision++;$('detail-drawer').hidden=true;$('drawer-backdrop').hidden=true;}
  function coverageHtml(coverage) {const c=coverage || {};return details([['Status',badge(c.status || 'pending',true)],['Last assessment',timeCell(c.lastAssessmentAt || c.observedAt)]])+(c.status==='partial'?'<p class="coverage-explanation">'+esc(AssessmentDashboard.coverageDescription(c))+'</p>':'')+(c.warnings?.length?'<section class="detail-section"><h3>Coverage notes</h3><ul class="coverage-list">'+c.warnings.map(w=>'<li>'+esc(typeof w==='string'?w:JSON.stringify(w))+'</li>').join('')+'</ul></section>':'<p class="muted" style="font-size:12px">'+(c.status==='pending'?'No assessment has been reported.':'No coverage warnings were reported.')+'</p>')+'<section class="detail-section"><h3>Collection overhead</h3>'+details([['Duration',c.stats?.durationMillis!=null?esc((c.stats.durationMillis/1000).toFixed(1)+' seconds'):value(null)],['Files visited',value(c.stats?.filesVisited)],['Bytes read',bytes(c.stats?.bytesRead)],['Collector CPU time',c.stats?.collectorCpuMillis!=null?esc(c.stats.collectorCpuMillis+' ms'):value(null)],['Peak Java heap',bytes(c.stats?.peakHeapBytes)],['Peak resident memory',bytes(c.stats?.collectorPeakRssBytes)]])+'</section>';}
  function openCoverage() {if(data)openDrawer('Assessment coverage','Collection evidence',coverageHtml(data.coverage));}
  async function showDetail(kind,id,recordHostId='') {
    if(kind==='hosts'){openMachine(id);return;}
    let r=(data?.[kind] || []).find(item=>String(item.id)===String(id)&&(!recordHostId||item.hostId===recordHostId)),detailData=data;const revision=++detailRevision;
    if(data?.scoped){try{const result=await api('item?'+new URLSearchParams({kind,id,hostId:recordHostId || r?.hostId || machineId,environmentId:environmentScope}));if(revision!==detailRevision)return;r=result.item;detailData={...data,...result.related};}catch(error){if(revision===detailRevision)notify(error.message,true);return;}}
    if(!r)return;
    let body='';
    if(kind==='hosts'){
      body=details([['Hostname',value(r.hostname)],['Fully qualified name',value(r.fqdn)],['Operating system',value(r.distribution || r.osName)],['Kernel',value(r.kernelVersion)],['Architecture',value(r.architecture)],['IP addresses',(r.ipAddresses || []).map(esc).join('<br>') || value(null)],['CPU',value(r.cpuModel)],['Logical CPUs',value(r.logicalCpus)],['Total memory',bytes(r.memoryTotalBytes)],['Available memory',bytes(r.memoryAvailableBytes)],['Collector Java',value(r.collectorJavaVersion)],['Last seen',timeCell(r.lastSeen)],['Host identity',value(r.id)]])+'<section class="detail-section"><h3>File systems</h3>'+(r.disks?.length?r.disks.map(d=>'<div class="detail-item"><strong>'+esc(d.mount)+'</strong><p>'+bytes(d.availableBytes)+' available of '+bytes(d.totalBytes)+'</p></div>').join(''):'<p class="muted">No file system measurements reported.</p>')+'</section><section class="detail-section"><h3>'+ (r.coverage?'Host coverage':'Fleet assessment coverage')+'</h3>'+coverageHtml(r.coverage || data.coverage)+'</section>';
    } else {
      const friendly={id:'Record identity',hostId:'Managed instance',javaHome:'Java home',version:kind==='applications'?'Application version':'Version',vendor:'Vendor',architecture:'Architecture',kind:'Runtime kind',inUse:'Observed in use',name:'Name',type:'Type',applicationType:'Application framework',frameworkVersion:'Framework version',applicationId:'Associated application',applicationName:'Application name',mainClass:'Java main class',startClass:'Application start class',path:'Path',state:'State',category:'Category',usage:'Usage evidence',scanScope:'Discovery scope',serverName:'Java server',driverName:'JDBC driver',driverClass:'Driver class',driverVersion:kind==='datasources'&&AssessmentDashboard.datasourceObservation(r).kind==='configuration'?'Packaged driver version':'Driver version',databaseType:'Database type',driverVersionEvidence:'Driver version evidence',configurationSource:'Configuration source',configuredMaxPoolSize:'Configured maximum pool size',poolCapacity:'Pool capacity',poolAvailable:'Available connections',activeConnections:'Active connections',observedAt:'Observed at',pid:'Process ID',serverType:'Server type',status:'Status',createdAt:'Requested',startedAt:'Started',finishedAt:'Finished',summary:'Result',error:'Error'};
      const ordered=Object.keys(friendly).filter(k=>r[k]!==undefined && k!=='id');ordered.push('id');
      body=(kind==='applications'||kind==='servers'?'<div class="evidence-summary">'+observationCell(r)+'<p>Process evidence identifies a Java launcher. File presence does not confirm deployment or health.</p></div>':kind==='libraries'&&String(r.usage || '').toLowerCase()==='found'?'<div class="evidence-summary">'+libraryEvidence(r)+'<p>Package or file presence does not confirm that this library is loaded or used by a datasource.</p></div>':'')+details(ordered.map(k=>[friendly[k],k==='hostId'?esc(hostName(r[k])):k==='applicationId'?applicationCell(r,detailData):/At$/.test(k)?timeCell(r[k]):k==='state'||k==='status'?badge(r[k]):typeof r[k]==='boolean'?(r[k]?'Yes':'Not observed'):value(typeof r[k]==='object'?JSON.stringify(r[k]):r[k]) ]));
      if(kind==='datasources'){const observation=AssessmentDashboard.datasourceObservation(r),configured=observation.kind==='configuration';body='<div class="evidence-summary datasource-evidence-summary">'+datasourceEvidence(r)+'<p>'+(configured?'Configuration was found in the reported source. The effective profile, active connection and runtime pool state were not verified.':observation.kind==='runtime'?'Runtime observations retain their original timestamp. Reported state and pool measurements describe that observation.':'The datasource evidence level was not classified. Review the source evidence before inferring configuration or runtime activity.')+'</p></div>'+body;}
      if(kind==='jobs'&&r.status==='PARTIAL')body='<div class="evidence-summary assessment-limitation-summary"><p>'+esc(AssessmentDashboard.limitationsDescription)+'</p>'+(host(r.hostId)?'<button class="text-button" data-host-coverage="'+esc(r.hostId)+'">View latest host coverage</button>':'')+'</div>'+body;
      if(r.evidence)body+='<div class="evidence-box"><strong>Evidence</strong><br>'+esc(typeof r.evidence==='string'?r.evidence:JSON.stringify(r.evidence))+'</div>';
      if((kind==='libraries'||kind==='datasources')&&Array.isArray(r.associations)&&r.associations.length)body+='<section class="detail-section"><h3>Associated applications and processes</h3>'+applicationCell(r,detailData)+'</section>';
      if(r.stats)body+='<section class="detail-section"><h3>Assessment statistics</h3>'+details(Object.entries(r.stats).map(([k,v])=>[k,value(v)]))+'</section>';
      if(kind==='applications'){
        const libraries=AssessmentDashboard.jdbcLibraries(detailData).filter(l=>l.hostId===r.hostId&&AssessmentDashboard.associations(detailData,l).some(a=>a.applicationId===r.id));
        const sources=(detailData.datasources || []).filter(ds=>ds.hostId===r.hostId&&AssessmentDashboard.associations(detailData,ds).some(a=>a.applicationId===r.id));
        if(sources.length)body+='<section class="detail-section application-datasources"><h3>JDBC datasources</h3>'+sources.map(ds=>'<div class="detail-item">'+link('datasources',ds.id,ds.name,'',ds.hostId)+'<p>'+esc(ds.driverName || ds.driverClass || 'Driver not reported')+'</p>'+datasourceEvidence(ds)+(ds.configurationSource?'<p class="cell-path">'+esc(ds.configurationSource)+'</p>':'')+'</div>').join('')+'</section>';
        if(libraries.length)body+='<section class="detail-section"><h3>JDBC libraries found</h3><p class="muted">Presence does not confirm datasource configuration or JDBC usage.</p>'+libraries.map(l=>'<div class="detail-item">'+link('libraries',l.id,l.name,'',l.hostId)+'<p>'+esc(l.version || 'Version not reported')+' · '+esc(AssessmentDashboard.libraryObservation(l))+'</p></div>').join('')+'</section>';
      }
      if(kind==='servers'){
        const ds=(detailData.datasources || []).filter(d=>d.hostId===r.hostId && d.serverName && r.name && d.serverName===r.name);
        if(ds.length)body+='<section class="detail-section"><h3>JDBC datasources</h3>'+ds.map(d=>'<div class="detail-item">'+link('datasources',d.id,d.name,'',d.hostId)+'<p>'+esc(d.driverName || 'Driver not reported')+' · '+esc(d.driverVersion || 'Version not reported')+'</p>'+datasourceEvidence(d)+'</div>').join('')+'</section>';
      }
    }
    openDrawer(r.hostname || r.name || r.version || 'Assessment '+String(r.id).slice(0,8),tabs.find(t=>t[0]===kind)?.[1] || (kind==='datasources'?'JDBC datasource':'Inventory detail'),body);
  }
  function openModal(title,kicker,body,submit,handler) {
    modalHandler=handler;$('modal-title').textContent=title;$('modal-kicker').textContent=kicker;$('modal-body').innerHTML=body;$('modal-submit').textContent=submit;$('modal-submit').disabled=false;$('modal-error').hidden=true;$('modal').showModal();
  }
  function field(label,name,type,val,extra='',hint=''){return '<label class="field">'+esc(label)+'<input name="'+name+'" type="'+type+'" value="'+esc(val??'')+'" '+extra+'>'+ (hint?'<small>'+esc(hint)+'</small>':'')+'</label>';}
  function runAssessment() {
    closeActions();const total=count('hosts');
    if(!total){openModal('Connect a collector','ASSESSMENT','<p>Create an environment, download its configured installer and run it on a machine. Registered machines appear automatically.</p>','Close',async()=>{});return;}
    const selectedMachine=contextKind==='machine',target=selectedMachine?hostName(machineId):AssessmentEnvironments.label(rawData,environmentScope);
    openModal('Run an assessment','ON-DEMAND COLLECTION','<p>Queue a bounded assessment for '+(selectedMachine?'<strong>'+esc(target)+'</strong>':'all <strong>'+total+' machines</strong> in '+esc(target))+'. Each machine uses its effective collection limits.</p><p>Machines with revoked access or an assessment already queued or running are skipped. Follow progress in Work requests.</p>','Queue assessment',async()=>{const result=await api('jobs/run-scope',{method:'POST',body:{environmentId:environmentScope,hostId:selectedMachine?machineId:''}});notify(result.queued+' assessment(s) queued'+(result.skipped?' · '+result.skipped+' skipped':'')+'.');setTab('jobs');});
  }
  function editSettings(hostId='') {
    closeActions();
    if(hostId||contextKind==='machine'){AssessmentEnvironments.editHostSettings(hostId || machineId);return;}
    if(environmentScope&&environmentScope!=='__unassigned__'){AssessmentEnvironments.openEdit(environmentScope);return;}
    if(contextKind!=='root'||environmentScope){notify('Choose a machine or a specific environment to edit its settings. Fleet defaults are available from Overview with All environments selected.');return;}
    if(!data)return;const selectedHost=hostId?host(hostId):null;if(hostId&&!selectedHost)return;const s={...(rawData.settings || {}),...(selectedHost?.settings || {})};
    const body='<label class="checkbox-field"><input type="checkbox" name="enabled" '+(s.enabled?'checked':'')+'><span><strong>Enable scheduled assessments</strong><small>Keep disabled to run only on-demand assessments.</small></span></label><div class="form-grid"><label class="field">Frequency<select name="frequency">'+['once','daily','weekly'].map(v=>'<option value="'+v+'" '+(s.frequency===v?'selected':'')+'>'+({once:'Once',daily:'Daily',weekly:'Weekly'}[v])+'</option>').join('')+'</select></label><label class="field">Time zone<select name="timeZone">'+[...new Set(['America/Sao_Paulo','UTC',s.timeZone].filter(Boolean))].map(z=>'<option '+(s.timeZone===z?'selected':'')+'>'+z+'</option>').join('')+'</select></label>'+field('Window starts','windowStart','time',s.windowStart || '00:00','required')+field('Window ends','windowEnd','time',s.windowEnd || '06:00','required')+'</div><h3 class="form-subhead">Collection limits</h3><div class="form-grid">'+field('Maximum duration (seconds)','maxDurationSeconds','number',s.maxDurationSeconds??120,'min="10" max="900" required')+field('Maximum files','maxFiles','number',s.maxFiles??10000,'min="100" max="50000" required')+field('Files per batch','batchSize','number',s.batchSize??100,'min="1" max="500" required')+field('Pause between batches (ms)','pauseMillis','number',s.pauseMillis??30,'min="0" max="2000" required')+'</div><h3 class="form-subhead">Discovery paths</h3><div class="form-grid"><label class="field full">Include paths<textarea name="includePaths" rows="4" required>'+(s.includePaths || []).map(esc).join('\n')+'</textarea><small>One absolute path per line. Keep paths specific to your Java estate.</small></label><label class="field full">Exclude paths<textarea name="excludePaths" rows="3">'+(s.excludePaths || []).map(esc).join('\n')+'</textarea></label></div><label class="checkbox-field" style="margin-top:18px"><input type="checkbox" name="inspectArchives" '+(s.inspectArchives?'checked':'')+'><span><strong>Inspect Java archives</strong><small>Inspect supported archive metadata within the same time and file limits.</small></span></label><p class="form-note">Changes apply to subsequent assessments. The current assessment, if any, continues with its existing limits.</p>';
    openModal(hostId?'Machine settings':'Assessment settings',hostId?'SETTINGS · '+hostName(hostId):'FLEET DEFAULTS', (hostId?'<p>These settings apply only to <strong>'+esc(hostName(hostId))+'</strong>. Saving creates explicit settings for this machine.</p>':'<p>Fleet defaults apply to machines without their own settings.</p>')+body,'Save changes',async(f)=>{
      const paths=(key)=>String(f.get(key)||'').split(/\r?\n/).map(x=>x.trim()).filter(Boolean);
      const include=paths('includePaths'), exclude=paths('excludePaths');if(!include.length || include.length>20 || exclude.length>20 || [...include,...exclude].some(p=>!p.startsWith('/') || p==='/' || p.includes('..') || p.length>512))throw new Error('Use up to 20 explicit absolute paths per list. Include at least one path; the filesystem root and parent traversal are not allowed.');
      const next={enabled:f.has('enabled'),frequency:f.get('frequency'),timeZone:f.get('timeZone'),windowStart:f.get('windowStart'),windowEnd:f.get('windowEnd'),maxDurationSeconds:Number(f.get('maxDurationSeconds')),maxFiles:Number(f.get('maxFiles')),batchSize:Number(f.get('batchSize')),pauseMillis:Number(f.get('pauseMillis')),includePaths:include,excludePaths:exclude,inspectArchives:f.has('inspectArchives')};
      await api(hostId?'hosts/settings':'settings',{method:'PUT',body:hostId?{hostId,settings:next}:next});notify(hostId?'Machine settings saved.':'Assessment settings saved.');await loadState(true);
    });
  }
  function dateDialog() {
    openModal('Choose a date range','INVENTORY FILTER','<p>Filter by the original observation time. Dates use UTC. Records without an observation time remain visible.</p><div class="date-presets"><button type="button" data-days="1">Today (UTC)</button><button type="button" data-days="7">Last 7 days</button><button type="button" data-days="30">Last 30 days</button><button type="button" data-days="all">All time</button></div><div class="form-grid">'+field('From','from','date',dateFrom)+field('To','to','date',dateTo)+'</div>','Apply',async(f)=>{const start=f.get('from'),end=f.get('to');if(start&&end&&start>end)throw new Error('The start date must be before the end date.');dateFrom=start;dateTo=end;page=1;await loadState(false);});
  }
  function columnDialog() {
    const cols=columns();openModal('Choose table columns','TABLE OPTIONS','<p>Choose the columns shown in this view.</p>'+cols.map(c=>'<label class="checkbox-field"><input type="checkbox" name="col-'+esc(c.key)+'" '+(columnVisibility[current]?.[c.key]!==false?'checked':'')+'><span>'+esc(c.label)+'</span></label>').join(''),'Apply',async(f)=>{if(!cols.some(c=>f.has('col-'+c.key)))throw new Error('Select at least one column.');columnVisibility[current]=Object.fromEntries(cols.map(c=>[c.key,f.has('col-'+c.key)]));renderTable();});
  }
  document.addEventListener('click',async(event)=>{
    if(event.target.closest('#actions-menu button, #actions-menu a')||!event.target.closest('.menu-anchor'))closeActions();
    if(event.target.closest('[data-environment-overview]')){environmentOverview();return;}
    const back=event.target.closest('[data-context-back]');if(back){back.dataset.contextBack==='environment'?environmentOverview():goRoot('environments');return;}
    const tab=event.target.closest('[data-tab]');if(tab){event.preventDefault();if(tab.closest('#side-nav')||tab.classList.contains('brand'))goRoot(tab.dataset.tab);else setTab(tab.dataset.tab);return;}
    const machine=event.target.closest('[data-machine]');if(machine){openMachine(machine.dataset.machine);return;}
    const inventory=event.target.closest('[data-inventory]');if(inventory){setTab(inventory.dataset.inventory,{machineId:inventory.dataset.scope,subset:inventory.dataset.subset});return;}
    const versionGroup=event.target.closest('[data-version-group]');if(versionGroup){versionMode='family';setTab('versions',{machineId:versionGroup.dataset.scope,version:versionGroup.dataset.versionGroup});return;}
    const pageButton=event.target.closest('[data-page]');if(pageButton){page=Number(pageButton.dataset.page);loadState(false);return;}
    const selectedVersion=event.target.closest('[data-select-version]');if(selectedVersion){versionFocus=selectedVersion.dataset.selectVersion;page=1;writeRoute();loadState(false);return;}
    const mode=event.target.closest('[data-version-mode]');if(mode){versionMode=mode.dataset.versionMode;versionFocus='';page=1;writeRoute();loadState(false);return;}
    if(event.target.closest('[data-clear-version]')){versionFocus='';page=1;writeRoute();loadState(false);return;}
    const detail=event.target.closest('[data-detail-kind]');if(detail){showDetail(detail.dataset.detailKind,detail.dataset.detailId,detail.dataset.detailHost);return;}
    const hostCoverage=event.target.closest('[data-host-coverage]');if(hostCoverage){let h=host(hostCoverage.dataset.hostCoverage);const revision=++detailRevision;try{if(data?.scoped)h=(await api('item?'+new URLSearchParams({kind:'hosts',id:hostCoverage.dataset.hostCoverage,hostId:hostCoverage.dataset.hostCoverage,environmentId:environmentScope}))).item;if(h&&revision===detailRevision)openDrawer('Latest host coverage',hostName(h.id),'<p class="muted">These details describe the host’s most recent assessment and may be newer than this work request.</p>'+coverageHtml(h.coverage));}catch(error){if(revision===detailRevision)notify(error.message,true);}return;}
    const environmentCoverage=event.target.closest('[data-environment-coverage]');if(environmentCoverage){const scoped=AssessmentEnvironments.scope(rawData,environmentCoverage.dataset.environmentCoverage);openDrawer('Assessment coverage',AssessmentEnvironments.label(rawData,environmentCoverage.dataset.environmentCoverage),coverageHtml(scoped.coverage));return;}
    if(event.target.closest('[data-settings]'))editSettings();if(event.target.closest('[data-run]'))runAssessment();if(event.target.closest('[data-coverage]'))openCoverage();
    const days=event.target.closest('[data-days]');if(days){const n=days.dataset.days, f=$('modal-form');if(n==='all'){f.elements.from.value='';f.elements.to.value='';}else{f.elements.to.value=new Date().toISOString().slice(0,10);f.elements.from.value=new Date(Date.now()-(Number(n)-1)*86400000).toISOString().slice(0,10);}}
  });
  $('actions-toggle').onclick=()=>{const open=$('actions-menu').hidden;$('actions-menu').hidden=!open;$('actions-toggle').setAttribute('aria-expanded',String(open));};
  $('nav-toggle').onclick=()=>setNavigation($('side-nav').hidden,{focus:narrowNavigation.matches});
  $('nav-backdrop').onclick=()=>setNavigation(false,{restoreFocus:true});
  narrowNavigation.addEventListener('change',()=>{const restore=narrowNavigation.matches&&$('side-nav').contains(document.activeElement);setNavigation(narrowNavigation.matches?false:desktopNavigationOpen,{restoreFocus:restore});});
  $('search-form').onsubmit=(e)=>{e.preventDefault();query=$('search').value;page=1;loadState(false);$('clear-filters').hidden=!query&&!inventorySubset;};
  $('global-search-form').onsubmit=(e)=>{e.preventDefault();const entered=$('global-search').value;if(['overview','environments','versions','machine','properties','schedule'].includes(current))setTab('runtimes');query=entered;$('global-search').value=entered;$('search').value=query;page=1;loadState(false);$('clear-filters').hidden=!query&&!inventorySubset;};
  $('clear-filters').onclick=()=>{query='';inventorySubset='';$('search').value='';$('global-search').value='';page=1;writeRoute();loadState(false);};
  $('host-filter').onchange=()=>{const id=$('host-filter').value;if(id==='__browse__'){contextKind='environment';machineId='';hostFilter='';setTab('hosts');return;}contextKind=id?'machine':'environment';machineId=id;resetTransient();finishNavigation();};
  $('page-size').onchange=()=>{size=Number($('page-size').value);page=1;loadState(false);};$('prev-page').onclick=()=>{page--;loadState(false);};$('next-page').onclick=()=>{page++;loadState(false);};
  $('date-filter').onclick=dateDialog;$('column-settings').onclick=columnDialog;$('run-assessment').onclick=runAssessment;$('empty-assessment').onclick=runAssessment;$('open-settings').onclick=()=>editSettings();$('open-install-guide').onclick=()=>AssessmentEnvironments.openInstallGuide();$('view-coverage').onclick=openCoverage;
  $('refresh').onclick=()=>loadState(false);$('close-drawer').onclick=closeDrawer;$('drawer-backdrop').onclick=closeDrawer;
  $('close-modal').onclick=()=>{$('modal').close();};$('cancel-modal').onclick=()=>{$('modal').close();};
  $('modal-form').onsubmit=async(e)=>{e.preventDefault();if(!modalHandler)return;$('modal-submit').disabled=true;$('modal-error').hidden=true;try{await modalHandler(new FormData(e.target));$('modal').close();}catch(err){$('modal-error').textContent=err.message;$('modal-error').hidden=false;}finally{$('modal-submit').disabled=false;}};
  $('login-form').onsubmit=async(e)=>{e.preventDefault();$('login-submit').disabled=true;$('login-error').hidden=true;try{const response=await api('login',{method:'POST',body:{password:$('password').value}});csrf=response.csrfToken || '';$('password').value='';await startSession();}catch(err){$('login-error').textContent=err.message;$('login-error').hidden=false;}finally{$('login-submit').disabled=false;}};
  $('logout').onclick=async()=>{AssessmentEnvironments.clear();AssessmentEnrollment.clear();try{await api('logout',{method:'POST',body:{}});}catch(_){}csrf='';rawData=null;data=null;showLogin();};
  $('about').onclick=()=>openDrawer('Java Assessment','Independent pilot','<p>This independent pilot inventories Java runtimes, application servers, libraries and JDBC configuration using evidence collected on registered hosts.</p><div class="evidence-box">This application is not Oracle Java Management Service. Discovery does not establish vendor certification, security status or software compatibility.</div><section class="detail-section"><h3>How the inventory works</h3><p>Each assessment is bounded by configured paths, time and file limits. Open any row to review its evidence and observation time. Missing values are shown as a dash.</p><p>Scheduled assessments are disabled until enabled in Assessment settings. Manual requests are tracked under Work requests.</p></section>');
  document.addEventListener('keydown',e=>{
    if(narrowNavigation.matches&&!$('side-nav').hidden){
      if(e.key==='Escape'){e.preventDefault();setNavigation(false,{restoreFocus:true});return;}
      if(e.key==='Tab'){const items=[$('nav-toggle'),...$('side-nav').querySelectorAll('[data-tab]')],first=items[0],last=items[items.length-1];if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}
    }
    if(e.key==='Escape'){closeDrawer();closeActions(true);}
  });
  AssessmentEnrollment.init({api,getData:()=>data,getEnrollmentData:()=>environmentData,refresh:loadState,notify,editHostSettings:editSettings,addMachine:()=>{if(environmentScope&&environmentScope!=='__unassigned__')AssessmentEnvironments.openPackage(environmentScope);else{goRoot('environments');notify('Choose or create an environment to download its configured agent.');}}});
  AssessmentEnvironments.init({api,download:downloadPackage,exportParams,getRaw:()=>rawData,getScoped:()=>data,getScope:()=>environmentScope,select:selectEnvironment,refresh:loadState,notify,navigate:setTab});
  setNavigation(!narrowNavigation.matches);render();startSession();
})();
