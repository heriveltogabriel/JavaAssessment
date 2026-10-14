'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {startFixture}=require('./fixtures/environments-fixture.cjs');
(async()=>{
 const root=path.resolve(__dirname,'..'),fixture=await startFixture(path.join(root,'web')),errors=[],requests=[];
 const h=fixture.state.hosts.find(h=>h.id==='host-a');h.uninstallCapability='uninstall-v1';h.credentialStatus='active';let browser;
 try{
  browser=await chromium.launch({channel:'chrome',headless:true});const context=await browser.newContext({viewport:{width:1274,height:862}});
  await context.route('**/*',async route=>{
   const req=route.request(),u=new URL(req.url());if(u.origin!==fixture.base)return route.abort();
   if(u.pathname.startsWith('/api/hosts/uninstall')){
    const b=req.postDataJSON();requests.push({path:u.pathname,b,csrf:req.headers()['x-csrf-token']});assert.equal(req.headers()['x-csrf-token'],'fixture-csrf');assert.equal(b.hostId,h.id);
    if(u.pathname.endsWith('/cancel'))h.uninstall={...h.uninstall,status:'CANCELLED'};
    else{assert.equal(b.confirmationName,h.hostname);assert.equal(b.expiresInMinutes,60);assert.match(b.requestKey,/^[a-z0-9-]{36}$/);h.uninstall={id:'fixture-request',status:'WAITING',createdAt:'2026-10-13T15:00:00Z',expiresAt:'2026-10-13T16:00:00Z',audit:[{time:'2026-10-13T15:00:00Z',status:'WAITING'}]};}
    return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(h.uninstall)});
   }return route.continue();
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.setDefaultTimeout(10000);
  const goto=async hash=>{await page.goto(fixture.base+'/'+hash);await page.locator('#login-layer').waitFor({state:'hidden'});await page.locator('#fleet-name').filter({hasText:'environment-fixture'}).waitFor();};
  const open=async()=>{await page.locator('#actions-toggle').click();await page.locator('#uninstall-agent').click();await page.locator('#modal').waitFor({state:'visible'});};
  await goto('#overview?env=all');await page.locator('#actions-toggle').click();assert.equal(await page.locator('#uninstall-agent').isVisible(),false);
  await goto('#machine/host-a?view=overview&env=env-a');await open();assert.match(await page.locator('#modal-body').innerText(),/1 hora/);
  await page.locator('input[name=confirmationName]').fill(h.hostname.toUpperCase());await page.locator('#modal-submit').click();await page.locator('#modal-error').waitFor({state:'visible'});assert.equal(requests.length,0);
  await page.locator('input[name=confirmationName]').fill(h.hostname);assert.equal(await page.locator('#modal-error').isVisible(),false);const out=path.join(root,'output/remote-uninstall-review/evidence');fs.mkdirSync(out,{recursive:true});await page.screenshot({path:path.join(out,'confirmation-desktop.png')});await page.locator('#modal-submit').click();await page.locator('#modal').waitFor({state:'hidden'});
  await open();assert.match(await page.locator('#modal-body').innerText(),/Aguardando conexão/);assert.equal(await page.locator('#modal-submit').innerText(),'Cancelar solicitação');await page.locator('#modal-submit').click();await page.locator('#modal').waitFor({state:'hidden'});assert.equal(requests.length,2);
  h.uninstall={...h.uninstall,status:'UNCONFIRMED'};await goto('#machine/host-a?view=overview&env=env-a');await open();assert.match(await page.locator('#modal-body').innerText(),/ausência de heartbeat não confirma/);assert.equal(await page.locator('input[name=confirmationName]').count(),0);await page.screenshot({path:path.join(out,'unconfirmed-desktop.png')});await page.locator('#cancel-modal').click();
  delete h.uninstall;h.uninstallCapability='';await goto('#machine/host-a?view=overview&env=env-a');await open();assert.match(await page.locator('#modal-title').innerText(),/Atualização/);assert.equal(await page.locator('#modal-submit').innerText(),'Fechar');await page.locator('#cancel-modal').click();
  h.uninstallCapability='uninstall-v1';await page.setViewportSize({width:390,height:844});await goto('#machine/host-a?view=overview&env=env-a');await open();assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await page.screenshot({path:path.join(out,'confirmation-mobile.png')});assert.deepEqual(errors,[]);
  console.log('remote-uninstall-browser PASS: machine-only action, exact name, 1h validity, CSRF, pending/cancel, unconfirmed, legacy update, desktop/mobile, no JS errors.');
 }finally{if(browser)await browser.close();await fixture.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
