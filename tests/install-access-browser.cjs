'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const {startFixture}=require('./fixtures/environments-fixture.cjs');
(async()=>{
 const root=path.resolve(__dirname,'..'),out=path.join(root,'output/install-access-ui');fs.mkdirSync(out,{recursive:true});const fixture=await startFixture(path.join(root,'web'));
 let browser,role='ADMIN',delay=0,fail=false;const records=new Map([['env-a',{configured:true,enabled:true,generation:1}],['env-b',{configured:false,enabled:false,generation:0}]]),calls=[];
 try{
  browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL||'chrome',headless:true});const context=await browser.newContext({viewport:{width:1280,height:940},acceptDownloads:true,permissions:['clipboard-read','clipboard-write']}),page=await context.newPage(),errors=[];page.setDefaultTimeout(12000);page.on('pageerror',e=>errors.push(e.message));
  await context.route('**/*',async route=>{
   const req=route.request(),url=new URL(req.url());if(url.origin!==fixture.base)return route.abort();
   if(/^\/[a-z-]+\.(js|css)$/.test(url.pathname))return route.fulfill({path:path.join(root,'web',url.pathname.slice(1)),contentType:url.pathname.endsWith('.js')?'application/javascript':'text/css'});
   if(url.pathname==='/api/session')return route.fulfill({json:{authenticated:true,csrfToken:'fixture-csrf',mustChangePassword:false,user:{id:'fixture-admin',name:'Fixture Admin',email:'fixture@example.com',role,status:'ACTIVE',mustChangePassword:false}}});
   if(url.pathname==='/api/environments/install-access'){
    const payload=req.method()==='POST'?req.postDataJSON():{},id=payload.environmentId||url.searchParams.get('environmentId'),action=payload.action;calls.push({id,action,method:req.method(),csrf:req.headers()['x-csrf-token']});
    let record=records.get(id);if(!record){record={configured:true,enabled:true,generation:1};records.set(id,record);}if(delay)await new Promise(resolve=>setTimeout(resolve,delay));
    if(fail){fail=false;return route.fulfill({status:503,json:{error:'Internal failure fixture-secret-must-not-be-echoed'}});}
    if(action==='revoke')record.enabled=false;else if(action==='enable'||action==='renew'){record.configured=true;record.enabled=true;record.generation++;}
    const secret='fixture-install-secret-'+record.generation,command=tool=>`(\n  umask 077\n  # Fixture only: private configuration\n  printf '%s\\n' 'Authorization: Bearer ${secret}' > private-config\n  ${tool} --config private-config https://dashboard.example/api/install/package\n  # Download and extraction only; no sudo execution\n)`;
    return route.fulfill({json:{environmentId:id,...record,version:'2.0.23',filename:'assessment-production-2.0.23.zip',expiresAt:null,...(record.enabled?{commands:{curl:command('curl'),wget:command('wget')}}:{})}});
   }return route.continue();
  });
  await page.goto(fixture.base+'/#environments');await page.locator('#login-layer').waitFor({state:'hidden'});await page.locator('[data-download-environment="env-a"]').waitFor();
  const dialog=page.locator('#environment-dialog'),body=page.locator('#install-access-body'),close=()=>dialog.locator('[data-env-close]').first().click(),start=async id=>{await page.locator('[data-download-environment="'+id+'"]').click();await page.locator('[data-agent-method="terminal"]').click();},open=async id=>{await start(id);await page.locator('.install-access-identity').waitFor();};
  assert.equal(await page.locator('[data-install-environment]').count(),0,'one entry per environment');
  assert.equal(await page.locator('[data-download-environment]').count(),2,'original download remains');await page.locator('[data-download-environment="env-a"]').click();await page.waitForFunction(()=>document.getElementById('environment-submit').textContent==='Confirmar download');assert.equal(calls.length,0,'ZIP preview does not request permanent credentials');await close();await open('env-a');assert.match(await body.innerText(),/Ativo · sem expiração/);assert.match(await body.innerText(),/24 horas/);assert.match(await body.innerText(),/Agentes já cadastrados continuam funcionando/);
  assert.equal(await body.locator('#install-access-install-command').count(),0,'no fictitious staging path to install');
  assert.match(await body.innerText(),/sem sudo/);assert.match(await body.innerText(),/cria e ativa os serviços automaticamente/);assert.match(await body.innerText(),/não é necessário configurá-los manualmente/);
  for(const tool of ['curl','wget']){
   await page.locator('[data-install-tab="'+tool+'"]').click();const command=await page.locator('#install-access-download-command').textContent();
   const [script]=await Promise.all([page.waitForEvent('download'),page.locator('[data-install-save]').click()]);
   assert.match(script.suggestedFilename(),new RegExp('^java-assessment-env-a-'+tool+'\\.sh$'));
   const content=fs.readFileSync(await script.path(),'utf8');assert.equal(content,'#!/bin/sh\n# Credencial de instalação: proteja este arquivo e não o publique.\n'+command+'\n');
  }
  await page.locator('[data-agent-method="zip"]').click();assert.equal(await page.evaluate(()=>document.getElementById('install-access-body')?.textContent||''),'','switch removes credential DOM');await page.waitForFunction(()=>document.getElementById('environment-submit').textContent==='Confirmar download');
  await page.locator('#environment-package-options').evaluate(e=>e.open=true);await page.locator('[name="maxUses"]').fill('42');
  await page.locator('[data-agent-method="zip"]').press('End');await page.locator('.install-access-identity').waitFor();await page.locator('[data-agent-method="terminal"]').press('ArrowLeft');assert.equal(await page.locator('[name="maxUses"]').inputValue(),'42','ZIP options survive switching');
  await page.locator('[data-agent-method="terminal"]').click();await page.locator('.install-access-identity').waitFor();
  await page.locator('[data-install-copy="download"]').click();assert.match(await page.evaluate(()=>navigator.clipboard.readText()),/fixture-install-secret-1/);
  await page.locator('[data-install-tab="wget"]').click();assert.match(await page.locator('#install-access-download-command').textContent(),/wget --config/);
  await page.locator('[data-install-tab="wget"]').press('Home');assert.equal(await page.locator('[data-install-tab="curl"]').getAttribute('aria-selected'),'true');
  await page.screenshot({path:path.join(out,'terminal-desktop.png')});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.locator('[data-install-action="renew"]').click();assert.equal(calls.filter(c=>c.action==='renew').length,0,'renew confirmation has no mutation');await page.locator('[data-install-confirm]').click();await page.waitForFunction(()=>document.querySelector('#install-access-download-command')?.textContent.includes('secret-2'));
  await page.locator('[data-install-action="revoke"]').click();await page.locator('[data-install-cancel]').click();assert.equal(calls.filter(c=>c.action==='revoke').length,0);await page.locator('[data-install-action="revoke"]').click();await page.locator('[data-install-confirm]').click();await page.locator('[data-install-enable]').waitFor();assert.ok(!(await body.innerText()).includes('fixture-install-secret'));assert.match(await body.innerText(),/Novas instalações desativadas/);
  await page.locator('[data-install-enable]').click();await page.waitForFunction(()=>document.querySelector('#install-access-download-command')?.textContent.includes('secret-3'));await close();assert.equal(await page.evaluate(()=>document.getElementById('install-access-body')?.textContent||''),'');assert.ok(!(await page.locator('body').textContent()).includes('fixture-install-secret'));
  await open('env-b');assert.match(await body.innerText(),/Não ativado/);assert.equal(calls.filter(c=>c.id==='env-b'&&c.action).length,0,'GET never activates');await page.locator('[data-install-enable]').click();await page.locator('#install-access-download-command').waitFor();await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'terminal-mobile.png')});assert.equal(await dialog.evaluate(e=>e.scrollWidth>e.clientWidth),false);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await close();
  delay=300;await start('env-a');await page.locator('[data-agent-method="zip"]').click();await page.waitForTimeout(400);assert.equal(await page.evaluate(()=>document.getElementById('install-access-body')?.textContent||''),'','leaving pending GET ignores late credentials');await close();
  delay=300;await start('env-a');await close();await page.waitForTimeout(400);assert.equal(await page.evaluate(()=>document.getElementById('install-access-body')?.textContent||''),'','late GET cannot restore secrets');assert.equal(await dialog.isVisible(),false);delay=0;
  await open('env-a');await page.evaluate(()=>AssessmentEnvironments.clear());assert.equal(await page.evaluate(()=>document.getElementById('install-access-body')?.textContent||''),'','logout integration clears secret DOM');assert.equal(await dialog.isVisible(),false);
  fail=true;await start('env-a');await page.locator('#install-access-error').waitFor({state:'visible'});assert.ok(!(await dialog.textContent()).includes('fixture-secret-must-not-be-echoed'));await page.locator('[data-install-retry]').click();await page.locator('#install-access-download-command').waitFor();await close();
  await page.setViewportSize({width:1280,height:940});await page.locator('[data-download-environment="env-a"]').click();await page.waitForFunction(()=>document.getElementById('environment-submit').textContent==='Confirmar download');const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#environment-submit').click()]);await download.path();await page.waitForFunction(()=>document.getElementById('environment-submit').textContent==='Concluir');assert.equal(fixture.packageCount,1,'legacy temporary ZIP still downloads');await page.locator('#environment-submit').click();
  await page.locator('[data-create-environment]').click();await page.locator('#environment-form [name="name"]').fill('Novo <script>ambiente</script>');await page.locator('#environment-submit').click();await page.locator('.install-access-identity').waitFor();assert.match(await page.locator('.install-access-identity').textContent(),/Novo <script>ambiente<\/script>/);assert.equal(await body.locator('script').count(),0,'escape environment name');await close();
  assert.equal(await page.evaluate(()=>JSON.stringify(localStorage).includes('fixture-install-secret')||JSON.stringify(sessionStorage).includes('fixture-install-secret')),false);
  await open('env-a');await page.evaluate(()=>AssessmentEnvironments.clear());assert.equal(await page.evaluate(()=>document.getElementById('install-access-body')?.textContent||''),'');
  role='VIEWER';await page.reload();await page.locator('#login-layer').waitFor({state:'hidden'});assert.equal(await page.locator('[data-install-environment]:visible').count(),0);const before=calls.length;await page.evaluate(()=>AssessmentInstallAccess.open('env-a'));assert.equal(calls.length,before);assert.equal(await dialog.isVisible(),false);
  assert.ok(calls.filter(c=>c.method==='POST').every(c=>c.csrf==='fixture-csrf'));assert.deepEqual(errors,[]);
  console.log('INSTALL_ACCESS_UI=PASS lifecycle/copy/roles/CSRF/stale-close/logout/mobile/create/legacy-ZIP; screenshots='+out);
 }finally{if(browser)await browser.close();await fixture.close();}
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
