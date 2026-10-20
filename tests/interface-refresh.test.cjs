'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const source=name=>fs.readFileSync(path.join(__dirname,'../web',name),'utf8');
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function dom(){
  const nodes=new Map();
  const element=id=>({id,hidden:false,disabled:false,value:'',textContent:'',innerHTML:'',dataset:{},attributes:{},open:false,
    classList:{values:new Set(),toggle(name,on){if(on===undefined)on=!this.values.has(name);if(on)this.values.add(name);else this.values.delete(name);},add(name){this.values.add(name);},remove(name){this.values.delete(name);},contains(name){return this.values.has(name);}},
    setAttribute(key,value){this.attributes[key]=String(value);},removeAttribute(key){delete this.attributes[key];},getAttribute(key){return this.attributes[key]??null;},addEventListener(){},querySelector(){return null;},querySelectorAll(){return [];},contains(){return false;},replaceChildren(){this.innerHTML='';},focus(){},scrollIntoView(){},close(){this.open=false;}});
  const get=id=>{if(!nodes.has(id))nodes.set(id,element(id));return nodes.get(id);};
  return {get,document:{getElementById:get,activeElement:element('body'),hidden:false,body:element('body'),querySelectorAll:()=>[],addEventListener(){}}};
}
function runtime(){const view=dom();return {...view,globals:{window:{},document:view.document,URLSearchParams,AbortController,CSS:{escape:String},console,Date,Map,Set,Promise,Error,FormData:class{constructor(form){this.values=form.values||{};}get(key){return this.values[key]??null;}},setInterval:()=>1,clearInterval(){},setTimeout:()=>1,clearTimeout(){},sessionStorage:{getItem:()=>null,setItem(){},removeItem(){}}}};}
function appHarness(){
  const test=runtime(),calls=[],notices=[];let epoch=1,readable=true;const stateQueue=[],workQueue=[],adminQueue=[],reportsQueue=[];
  const modules={AssessmentMachineCleanup:{init(){},clear(){},sync(){}},AssessmentReports:{init(context){assert.equal(typeof context.api,'function');assert.equal(typeof context.download,'function');},render(){},clear(){},refresh(background){calls.push({reports:true,background});const request=reportsQueue.shift();assert.ok(request,'Unexpected reports refresh');return request.promise;}},AssessmentDashboard:{},AssessmentEnrollment:{init(){}},AssessmentMachines:{init(){}},AssessmentAgentUpdates:{init(){}},AssessmentEnvironments:{stored:()=>'',init(){},openAssignment(){}},AssessmentLifecycle:require('../web/lifecycle.js')};
  Object.assign(test.globals,modules,{history:{},location:{hash:'#environment/hosts?env=prod'},AssessmentAccess:{canInventory:()=>readable,isAdmin:()=>true,getRevision:()=>epoch,init(){},request(path,options){calls.push({path,options});return stateQueue.shift().promise;},refreshAdministration(quiet){calls.push({admin:true,quiet});return adminQueue.shift().promise;}},AssessmentWorkRequests:{init(){},refresh(background){calls.push({work:true,background});return workQueue.shift().promise;}}});
  Object.assign(test.globals.window,{matchMedia:()=>({matches:false,addEventListener(){}}),addEventListener(){}});
  const instrumented=source('app.js').replace('  setNavigation(!narrowNavigation.matches);render();AssessmentAccess.start();',`
    render=()=>{};projectData=()=>{data=rawData;};writeRoute=()=>{};notify=(message,error)=>window.notices.push({message,error});
    window.test={loadState,refreshCurrentView,configure(value){current=value.current||'hosts';environmentScope=value.environmentId||'prod';contextKind=value.contextKind||'environment';machineId=value.machineId||'';query=value.query||'';page=value.page||1;size=value.size||20;dateFrom=value.from||'';dateTo=value.to||'';renderedHash=value.route||'#environment/hosts?env=prod';data=value.data||{existing:true};},expire(){manualRefresh=null;refreshing=false;updateRefreshState();},getData:()=>data};
  `);
  test.globals.window.notices=notices;vm.runInNewContext(instrumented,test.globals);
  return {...test,api:test.globals.window.test,calls,notices,stateQueue,workQueue,adminQueue,reportsQueue,expire(){epoch++;readable=false;test.globals.window.test.expire();}};
}
function workHarness(){
  const test=runtime(),queue=[],calls=[];
  const instrumented=source('work-requests.js').replace('  root.AssessmentWorkRequests={init,sync,clear,refresh};',`
    root.AssessmentWorkRequests={init,sync,clear,refresh};root.test={configure(value){scope={view:'jobs',environmentId:'prod',hostId:'host-a',...value.scope};selection={id:'batch-a',type:'AGENT_UPDATE',...value.selection};filters={...defaults(),q:'java',status:'ACTIVE',page:2,...value.filters};},state:()=>({list,detail,errors})};
  `);
  vm.runInNewContext(instrumented,test.globals);test.globals.window.AssessmentWorkRequests.init({canRead:()=>true,isAdmin:()=>true,getRevision:()=>1,api(path,options){calls.push({path,options});return queue.shift().promise;}});test.globals.window.test.configure({});
  return {...test,queue,calls,api:test.globals.window.AssessmentWorkRequests,state:test.globals.window.test.state};
}
function accessHarness(){
  const test=runtime(),queue=[],calls=[];
  const instrumented=source('access.js').replace('  root.AssessmentAccess={init,start,request,showLogin,isAdmin,canInventory,applyPermissions,renderAdministration,refreshAdministration,closeMenu,getUser:()=>session?.user||null,getRevision:()=>epoch};',`
    root.AssessmentAccess={refreshAdministration};root.test={configure(){session={authenticated:true,user:{id:'self',name:'Admin',role:'ADMIN'}};adminLoaded=true;adminQuery='Alice';roleFilter='READER';statusFilter='ACTIVE';},switchSession(){epoch++;session={authenticated:true,user:{id:'new-admin',role:'ADMIN'}};users=[{id:'new-user'}];adminLoading=null;},getState:()=>({users,events,adminQuery,roleFilter,statusFilter,adminTab})};
  `);
  test.globals.fetch=async(url,options)=>{calls.push({url,options});const result=await queue.shift().promise;return {ok:true,json:async()=>result};};
  vm.runInNewContext(instrumented,test.globals);test.globals.window.test.configure();
  return {...test,queue,calls,api:test.globals.window.AssessmentAccess,state:test.globals.window.test.getState,switchSession:test.globals.window.test.switchSession};
}
(async()=>{
  let checks=0;
  {
    const h=appHarness(),state=deferred(),work=deferred();h.stateQueue.push(state);h.workQueue.push(work);h.api.configure({query:'jdbc',page:3,size:50,from:'2026-10-01',to:'2026-10-14',machineId:'host-a',contextKind:'machine'});
    h.get('modal').open=true;h.get('modal-draft').value='unsaved';const run=h.get('refresh').onclick();await h.get('refresh').onclick();assert.equal(h.calls.length,1);assert.equal(h.get('refresh').disabled,true);assert.equal(h.get('refresh').getAttribute('aria-busy'),'true');
    const params=new URLSearchParams(h.calls[0].path.split('?')[1]);for(const [key,value]of Object.entries({q:'jdbc',page:'3',pageSize:'50',environmentId:'prod',hostId:'host-a',from:'2026-10-01',to:'2026-10-14'}))assert.equal(params.get(key),value);
    state.resolve({hosts:[],pagination:{page:3}});await flush();assert.equal(h.calls[1].work,true);assert.equal(h.get('refresh').classList.contains('spin'),true);assert.equal(h.notices.length,0);
    work.resolve(true);await run;assert.equal(h.get('refresh').disabled,false);assert.equal(h.get('main-content').getAttribute('aria-busy'),null);assert.equal(h.notices.at(-1).message,'Dados atualizados.');assert.equal(h.get('modal').open,true);assert.equal(h.get('modal-draft').value,'unsaved');assert.ok(h.calls.every(call=>!call.options?.method));checks+=4;
  }
  for(const failure of ['state','work']){
    const h=appHarness(),state=deferred(),work=deferred();h.stateQueue.push(state);h.workQueue.push(work);h.api.configure({data:{preserved:true}});const run=h.api.refreshCurrentView();failure==='state'?state.reject(Error('Offline')):state.resolve({hosts:[]});await flush();work.resolve(failure!=='work');await run;assert.equal(h.notices.at(-1).error,true);assert.match(h.notices.at(-1).message,/último estado/);assert.equal(h.get('refresh').disabled,false);if(failure==='state')assert.equal(h.api.getData().preserved,true);checks++;
  }
  {
    const h=appHarness(),request=deferred();h.adminQueue.push(request);h.api.configure({current:'administration',route:'#administration?env=all'});const run=h.api.refreshCurrentView();assert.equal(h.calls[0].admin,true);assert.equal(h.get('refresh').getAttribute('aria-busy'),'true');request.resolve(true);await run;assert.equal(h.calls.length,1);assert.equal(h.notices.at(-1).message,'Dados atualizados.');checks++;
  }
  for(const success of [true,false]){
    const h=appHarness(),request=deferred();h.reportsQueue.push(request);h.api.configure({current:'reports',route:'#reports'});
    assert.equal(await h.api.loadState(true),true);assert.equal(h.calls.length,0,'background polling does not query reports');
    const refresh=h.api.refreshCurrentView();assert.equal(h.calls.length,1);assert.equal(h.calls[0].reports,true);assert.equal(h.calls[0].background,false);assert.equal(h.get('refresh').disabled,true);
    request.resolve(success);await refresh;assert.equal(h.calls.length,1,'reports refresh does not also request inventory or work requests');assert.equal(h.get('refresh').disabled,false);
    assert.equal(h.notices.at(-1).message,success?'Dados atualizados.':'Não foi possível atualizar todos os dados. O último estado conhecido foi mantido.');assert.equal(!!h.notices.at(-1).error,!success);checks++;
  }
  for(const transition of ['navigation','logout']){
    const h=appHarness(),request=deferred();h.adminQueue.push(request);h.api.configure({current:'administration',route:'#administration?env=all'});const run=h.api.refreshCurrentView();if(transition==='navigation')h.api.configure({current:'jobs',route:'#jobs?env=all'});else h.expire();request.resolve(true);await run;assert.equal(h.notices.length,0);assert.equal(h.calls.length,1);assert.equal(h.get('refresh').disabled,false);checks++;
  }
  {
    const h=appHarness(),old=deferred(),newer=deferred();h.stateQueue.push(old,newer);h.api.configure({query:'old'});const manual=h.api.refreshCurrentView();h.api.configure({query:'new'});const filtered=h.api.loadState(false);newer.resolve({result:'new'});await filtered;old.resolve({result:'old'});await manual;assert.equal(h.api.getData().result,'new');assert.equal(h.notices.length,0);assert.equal(h.calls.length,2);assert.equal(h.get('refresh').disabled,false);checks++;
  }
  {
    const h=workHarness(),list=deferred(),detail=deferred();h.queue.push(list,detail);h.get('work-request-filters').values={q:'not applied',type:'ASSESSMENT'};let done=false;const run=h.api.refresh(false).then(value=>{done=true;return value;});assert.equal(h.calls.length,2);for(const call of h.calls){assert.equal(call.options.method,undefined);const params=new URLSearchParams(call.path.split('?')[1]);assert.equal(params.get('environmentId'),'prod');assert.equal(params.get('hostId'),'host-a');}const params=new URLSearchParams(h.calls[0].path.split('?')[1]);assert.equal(params.get('q'),'java');assert.equal(params.get('status'),'ACTIVE');assert.equal(params.get('page'),'2');
    list.resolve({items:[],pagination:{page:2,total:0,pages:2}});await flush();assert.equal(done,false);detail.resolve({request:{id:'batch-a',type:'AGENT_UPDATE',counts:{total:0},status:'SUCCEEDED'},machines:[]});assert.equal(await run,true);assert.equal(h.get('work-request-filters').values.q,'not applied');assert.equal(h.get('work-request-filters').values.type,'ASSESSMENT');checks+=2;
    const failedList=deferred(),failedDetail=deferred();h.queue.push(failedList,failedDetail);const failed=h.api.refresh(false);failedList.reject(Error('offline'));failedDetail.reject(Error('offline'));assert.equal(await failed,false);assert.equal(h.state().detail.request.id,'batch-a');assert.match(h.get('work-request-results').innerHTML,/último estado conhecido/);assert.match(h.get('work-request-detail').innerHTML,/último estado conhecido/);checks++;
  }
  {
    const h=accessHarness(),users=deferred(),audit=deferred();h.queue.push(users,audit);h.get('admin-view').contains=()=>true;h.get('admin-view').innerHTML='old';h.get('access-dialog').open=true;h.get('access-name').value='Draft user';
    const background=h.api.refreshAdministration(true),foreground=h.api.refreshAdministration(false);assert.equal(h.calls.length,2);assert.ok(h.calls.every(call=>call.options.method===undefined&&call.options.headers['X-Assessment-Background']==='1'));
    users.resolve({users:[{id:'alice',name:'Alice Updated',email:'alice@example.com',role:'READER',status:'ACTIVE'}]});audit.resolve({events:[]});assert.equal(await background,true);assert.equal(await foreground,true);assert.match(h.get('admin-view').innerHTML,/Alice Updated/);assert.match(h.get('admin-view').innerHTML,/value="Alice"/);assert.match(h.get('admin-view').innerHTML,/value="READER" selected/);assert.match(h.get('admin-view').innerHTML,/value="ACTIVE" selected/);assert.equal(h.get('access-dialog').open,true);assert.equal(h.get('access-name').value,'Draft user');checks+=2;
    const badUsers=deferred(),badAudit=deferred();h.queue.push(badUsers,badAudit);const failedBackground=h.api.refreshAdministration(true),failed=h.api.refreshAdministration(false);badUsers.reject(Error('No connection'));badAudit.resolve({events:[]});assert.equal(await failedBackground,false);assert.equal(await failed,false);assert.equal(h.state().users[0].name,'Alice Updated');assert.equal(h.get('access-admin-error').hidden,false);assert.equal(h.get('access-admin-error').textContent,'No connection');checks++;
  }
  {
    const h=accessHarness(),users=deferred(),audit=deferred();h.queue.push(users,audit);h.get('admin-view').innerHTML='new session';const background=h.api.refreshAdministration(true),manual=h.api.refreshAdministration(false);h.switchSession();users.resolve({users:[{id:'old-user'}]});audit.resolve({events:[]});assert.equal(await background,false);assert.equal(await manual,false);assert.equal(h.state().users[0].id,'new-user');assert.equal(h.get('admin-view').innerHTML,'new session');checks++;
  }
  console.log('PASS: '+checks+' refresh checks (awaited requests, loading/error feedback, scopes and drafts, admin background join, navigation/session safety; GET only).');
})().catch(error=>{console.error(error);process.exitCode=1;});
