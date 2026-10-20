const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('web/inventory-export.js','utf8');
function fixture(){
  const elements=new Map(),events={},downloads=[],requests=[];let epoch=1,admin=true,response;
  const get=id=>{if(!elements.has(id))elements.set(id,{value:id==='#inventory-export-format'?'json':'Cliente A',hidden:false,disabled:false,textContent:'',addEventListener(name,fn){events[name]=fn;}});return elements.get(id);};
  const panel={innerHTML:'',querySelector:get};
  const window={AssessmentAccess:{isAdmin:()=>admin,getRevision:()=>epoch,showLogin(){epoch++;admin=false;}}};
  const context={window,AbortController,URLSearchParams,setTimeout:fn=>fn(),URL:{createObjectURL:()=> 'blob:fixture',revokeObjectURL(){}},document:{body:{appendChild(){}},createElement:()=>({remove(){},click(){downloads.push(this.download);}})},fetch:async(url,options)=>{requests.push({url,options});return response;}};
  vm.runInNewContext(source,context);window.InventoryExport.mount(panel);
  return {panel,get,requests,downloads,api:window.InventoryExport,expire(){epoch++;},submit:()=>events.submit({preventDefault(){}}),response(value){response=value;}};
}
const ok=()=>({ok:true,headers:{get:key=>key==='Content-Type'?'application/zip':'attachment; filename="java-assessment-inventario-2026-10-20.zip"'},blob:async()=>({})});
(async()=>{
  let h=fixture();h.response(ok());await h.submit();assert.equal(h.downloads.length,1);assert.match(h.requests[0].url,/label=Cliente\+A/);assert.equal(h.get('#inventory-export-format').disabled,false);assert.match(h.requests[0].url,/format=json/);assert.equal(h.get('#inventory-export-submit').disabled,false);assert.match(h.get('#inventory-export-status').textContent,/download foi iniciado/);assert.match(h.panel.innerHTML,/Não é um backup/);
  h=fixture();h.get('#inventory-export-format').value='csv';h.response(ok());await h.submit();assert.match(h.requests[0].url,/format=csv/);
  h=fixture();let complete;const pending=new Promise(resolve=>complete=resolve);h.response(pending);const work=h.submit();await h.submit();assert.equal(h.requests.length,1);assert.equal(h.get('#inventory-export-submit').disabled,true);complete(ok());await work;assert.equal(h.downloads.length,1);
  h=fixture();h.response({ok:false,status:429,json:async()=>({error:'Outra exportação em andamento'})});await h.submit();assert.equal(h.get('#inventory-export-error').hidden,false);assert.match(h.get('#inventory-export-error').textContent,/Outra exportação/);assert.equal(h.downloads.length,0);assert.equal(h.get('#inventory-export-submit').disabled,false);
  h=fixture();let blobDone;h.response({...ok(),blob:()=>new Promise(resolve=>blobDone=resolve)});const changed=h.submit();await new Promise(setImmediate);h.expire();blobDone({});await changed;assert.equal(h.downloads.length,0);
  h=fixture();let done;h.response(new Promise(resolve=>done=resolve));const leaving=h.submit();h.api.stop();assert.equal(h.requests[0].options.signal.aborted,true);done(ok());await leaving;assert.equal(h.downloads.length,0);
  h=fixture();h.response({ok:true,headers:{get:()=> 'application/json'}});await h.submit();assert.equal(h.downloads.length,0);assert.match(h.get('#inventory-export-error').textContent,/ZIP válido/);
  const access=fs.readFileSync('web/access.js','utf8');assert.match(access,/\['export','Exportar inventário'\]/);assert.match(access,/ids\.length/);assert.match(fs.readFileSync('web/index.html','utf8'),/src="\/inventory-export\.js"/);
  console.log('inventory-export-ui tests passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
