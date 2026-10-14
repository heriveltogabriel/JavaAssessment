const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync(require('node:path').join(__dirname,'../web/version.js'),'utf8');
async function run(response){
 const labels={'interface-version':{textContent:'Versão indisponível'},'dashboard-version':{textContent:'Versão indisponível'}},window={};let options;
 vm.runInNewContext(source,{window,document:{getElementById:id=>labels[id]},fetch:async(url,opts)=>{assert.equal(url,'/health');options=opts;if(response instanceof Error)throw response;return response;},Number,Date,Error});
 await new Promise(resolve=>setImmediate(resolve));return {labels,window,options};
}
(async()=>{
 for(const version of ['1.5.13','2.0.0','2.0.1']){
  const result=await run({ok:true,json:async()=>({status:'ok',version,builtAt:'2026-10-13T12:00:00Z'})});
  for(const label of Object.values(result.labels))assert.equal(label.textContent,'Versão '+version);
  assert.equal(result.window.AssessmentRelease.version,version);assert.equal(result.options.cache,'no-store');
 }
 for(const response of [Error('offline'),{ok:false},...['1.0 Beta','1.0-beta','<script>','1.0.1-beta\n',['1.0.1-beta']].map(version=>({ok:true,json:async()=>({status:'ok',version,builtAt:'2026-10-13T12:00:00Z'})})),{ok:true,json:async()=>({status:'ok',version:'1.0.1-beta',builtAt:'invalid-date'})}]){
  const result=await run(response);assert.equal(result.window.AssessmentRelease,null);for(const label of Object.values(result.labels))assert.equal(label.textContent,'Versão indisponível');
 }
 console.log('PASS: interface uses exact server version, new release value, no cache, unavailable on invalid/offline response.');
})().catch(error=>{console.error(error);process.exitCode=1;});
