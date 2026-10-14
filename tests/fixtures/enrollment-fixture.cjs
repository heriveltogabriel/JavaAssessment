/* Isolated browser fixture. These values are test-only and never contact the lab. */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const defaults = {enabled:false,frequency:'once',timeZone:'America/Sao_Paulo',windowStart:'00:00',windowEnd:'06:00',maxDurationSeconds:120,maxFiles:12000,batchSize:100,pauseMillis:30,includePaths:['/usr/java','/opt/java'],excludePaths:['/proc','/sys','/dev'],inspectArchives:false};
async function startFixture(webRoot) {
 const moment='2026-10-09T20:00:00Z';
 const fixture={requests:[],failNextEnrollment:false,authenticated:true,state:{fleet:{name:'enrollment-fixture',description:'Isolated browser test'},hosts:[{id:'fixture-host-1',hostname:'fixture-rhel5',distribution:'Red Hat Enterprise Linux 5.11',architecture:'amd64',registeredAt:'2026-10-09T18:00:00Z',firstContactAt:'2026-10-09T18:01:00Z',firstAssessmentAt:'2026-10-09T18:02:00Z',lastAssessmentAt:'2026-10-09T19:58:00Z',lastSeen:moment,credentialStatus:'active',settings:{...defaults},collectorJavaVersion:'1.6.0_45',logicalCpus:2,memoryTotalBytes:4294967296,disks:[],installation:{status:'installed',updatedAt:'2026-10-09T18:01:00Z',events:[{eventId:'e1',time:'2026-10-09T18:00:00Z',stage:'registration',status:'OK',message:'Collector registered.'},{eventId:'e2',time:'2026-10-09T18:00:20Z',stage:'permissions',status:'WARNING',message:'Some paths are not readable.'},{eventId:'e3',time:'2026-10-09T18:01:00Z',stage:'complete',status:'OK',message:'Installation completed.'}]}},{id:'fixture-host-2',hostname:'fixture-new-machine',credentialStatus:'active',registeredAt:'2026-10-09T19:59:00Z',installation:{status:'registering',events:[]},settings:{...defaults}}],runtimes:[{id:'runtime1',hostId:'fixture-host-1',version:'1.6.0_45',vendor:'Oracle',javaHome:'/opt/java',inUse:true}],processes:[],servers:[],libraries:[],applications:[],datasources:[],jobs:[],enrollments:[],settings:{...defaults},coverage:{status:'partial',warnings:['Test coverage limitation'],lastAssessmentAt:'2026-10-09T19:58:00Z',stats:{durationMillis:4000,filesVisited:100}},serverTime:moment}};
 const server=http.createServer(async(req,res)=>{
  const url=new URL(req.url,'http://127.0.0.1');
  const json=(status,obj)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(obj));};
  if(url.pathname.startsWith('/api/')){
   let body={};for await(const chunk of req){body._raw=(body._raw || '')+chunk;}if(body._raw){try{body=JSON.parse(body._raw);}catch(_){return json(400,{error:'Invalid JSON'});}}
   fixture.requests.push({path:url.pathname,method:req.method,body,csrf:req.headers['x-csrf-token']});
   if(url.pathname==='/api/session')return fixture.authenticated?json(200,{authenticated:true,csrfToken:'fixture-csrf',mustChangePassword:false,user:{id:'fixture-admin',name:'Fixture Admin',email:'fixture-admin@example.com',role:'ADMIN',status:'ACTIVE',mustChangePassword:false}}):json(401,{error:'Session expired'});
   if(url.pathname==='/api/login'){fixture.authenticated=true;return json(200,{authenticated:true,csrfToken:'fixture-csrf',mustChangePassword:false,user:{id:'fixture-admin',name:'Fixture Admin',email:'fixture-admin@example.com',role:'ADMIN',status:'ACTIVE',mustChangePassword:false}});}
   if(!fixture.authenticated)return json(401,{error:'Session expired'});
   if(req.method!=='GET'&&req.headers['x-csrf-token']!=='fixture-csrf')return json(403,{error:'Missing CSRF'});
   if(url.pathname==='/api/state')return json(200,fixture.state);
   if(url.pathname==='/api/enrollments'&&req.method==='POST'){
    if(fixture.failNextEnrollment){fixture.failNextEnrollment=false;return json(400,{error:'Enrollment request was rejected by the fixture.'});}
    const enrollment={id:'fixture-enrollment-'+(fixture.state.enrollments.length+1),label:body.label,expiresAt:'2026-10-09T21:00:00Z',maxUses:body.maxUses,usedCount:0,status:'active'};fixture.state.enrollments.push(enrollment);
    return json(201,{enrollment,enrollmentToken:'fixture-only-code-'+enrollment.id,serverUrl:fixture.base});
   }
   if(url.pathname==='/api/enrollments/revoke'){const e=fixture.state.enrollments.find(e=>e.id===body.enrollmentId);if(!e)return json(404,{error:'Unknown enrollment'});e.status='revoked';return json(200,{revoked:true});}
   if(url.pathname==='/api/hosts/revoke'){const h=fixture.state.hosts.find(h=>h.id===body.hostId);if(!h)return json(404,{error:'Unknown host'});h.credentialStatus='revoked';return json(200,{revoked:true});}
   if(url.pathname==='/api/hosts/settings'){const h=fixture.state.hosts.find(h=>h.id===body.hostId);if(!h)return json(404,{error:'Unknown host'});h.settingsOverrides=body.replaceOverrides?{...body.settings}:{...h.settingsOverrides,...body.settings};h.settings={...defaults,...h.settingsOverrides};return json(200,h.settings);}
   if(url.pathname==='/api/logout'){fixture.authenticated=false;return json(200,{loggedOut:true});}
   if(url.pathname==='/api/collector/info')return json(200,{version:'1.5.0',filename:'assessment-collector-1.5.0.zip'});
   if(url.pathname==='/api/collector.zip'){res.writeHead(200,{'Content-Type':'application/zip','Content-Disposition':'attachment; filename="assessment-collector-1.5.0.zip"'});return res.end(Buffer.from('504b0506000000000000000000000000000000000000','hex'));}
   return json(404,{error:'Unexpected fixture endpoint '+url.pathname});
  }
  const name=url.pathname==='/'?'index.html':url.pathname.slice(1);
  if(!['index.html','access.js','access.css','version.js','styles.css','dashboard.css','dashboard.js','app.js','enrollment.js','enrollment.css','environments.js','environments.css'].includes(name)){res.writeHead(404);return res.end();}
  res.writeHead(200,{'Content-Type':name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'text/html','Cache-Control':'no-store'});res.end(fs.readFileSync(path.join(webRoot,name)));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));fixture.base='http://127.0.0.1:'+server.address().port;fixture.close=()=>new Promise(resolve=>server.close(resolve));return fixture;
}
module.exports={startFixture};
if(require.main===module)startFixture(path.resolve(__dirname,'../../web')).then(f=>console.log('Fixture ready at '+f.base));
