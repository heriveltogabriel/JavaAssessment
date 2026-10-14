/* Contextual Actions menu: isolated local fixtures, no server mutations. */
'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {startFixture}=require('./fixtures/environments-fixture.cjs');
(async()=>{
 const root=path.resolve(__dirname,'..'),fixture=await startFixture(path.join(root,'web')),errors=[],checks=[];
 fixture.state.environments.push({id:'empty',name:'Empty',settings:{},revision:1},{id:'revoked',name:'Revoked',settings:{},revision:1});
 fixture.state.hosts.push({...fixture.state.hosts[0],id:'revoked-host',hostname:'revoked-host',environmentId:'revoked',credentialStatus:'revoked'});
 let browser;
 try{
  browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL || 'chrome',headless:true});
  const context=await browser.newContext({viewport:{width:1274,height:862}});
  await context.route('**/*',route=>new URL(route.request().url()).origin===fixture.base?route.continue():route.abort());
  // Exercise the real refresh path quickly instead of waiting 15 seconds per scenario.
  await context.addInitScript(()=>{const interval=window.setInterval.bind(window);window.setInterval=(fn,delay,...args)=>interval(fn,delay===15000?250:delay,...args);});
  const page=await context.newPage();page.setDefaultTimeout(10000);page.on('pageerror',error=>errors.push(error.message));
  const menu=page.locator('#actions-menu'),toggle=page.locator('#actions-toggle');
  const waitReady=async()=>{await page.locator('#login-layer').waitFor({state:'hidden'});await page.locator('#fleet-name').filter({hasText:'environment-fixture'}).waitFor();};
  const route=async hash=>{await page.goto(fixture.base+'/'+hash);await waitReady();};
  const assertClosed=async()=>{assert.equal(await menu.isVisible(),false);assert.equal(await toggle.getAttribute('aria-expanded'),'false');};
  const open=async()=>{if(!await menu.isVisible())await toggle.click();assert.equal(await toggle.getAttribute('aria-expanded'),'true');};
  async function expectMenu(hash,expected){await route(hash);await open();assert.deepEqual(await menu.locator('button:visible,a:visible').allTextContents(),[...expected.slice(0,-1),'Agent installation guide',expected.at(-1)]);assert.equal(await page.locator('.fleet-header [data-create-environment]').count(),0);assert.equal(await menu.locator('[data-add-machine], [data-create-environment], a[href="/api/collector.zip"]').count(),0);checks.push(hash);}
  const all=['Run assessment','Default assessment settings','Enrollment codes','Exportar CSV'];
  await expectMenu('#overview?env=all',all);
  const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/state');await response;assert.equal(await menu.isVisible(),true,'State polling keeps an open menu readable');assert.equal(await toggle.getAttribute('aria-expanded'),'true');
  await page.keyboard.press('Escape');await assertClosed();assert.equal(await page.locator(':focus').getAttribute('id'),'actions-toggle');
  await open();await page.locator('#fleet-name').click();await assertClosed();
  await open();await toggle.click();await assertClosed();
  await open();await page.locator('#run-assessment').click();await assertClosed();assert.equal(await page.locator('#modal').isVisible(),true);await page.locator('#cancel-modal').click();
  await open();await page.locator('#open-settings').click();await assertClosed();assert.equal(await page.locator('#modal').isVisible(),true);await page.locator('#cancel-modal').click();
  await open();await page.locator('#open-install-guide').click();await assertClosed();assert.equal(await page.locator('#environment-dialog-title').innerText(),'Agent installation guide');assert.equal(await page.locator('#environment-package-filename').innerText(),'assessment-ENVIRONMENT-1.5.0.zip');assert.equal(await page.locator('#environment-submit').isVisible(),false);assert.equal(await page.locator('#environment-package-options').count(),0);assert.match(await page.locator('#environment-extract-command').innerText(),/assessment-ENVIRONMENT-1\.5\.0\.zip/);assert.match(await page.locator('#environment-form-body').innerText(),/does not generate a package or enrollment credential/);await page.locator('.modal-footer [data-env-close]').click();assert.equal(await page.locator('#environment-dialog').isVisible(),false);
  await open();await page.locator('#side-nav [data-tab="environments"]').click();await assertClosed();await page.locator('[data-create-environment]:visible').waitFor({state:'visible'});assert.equal(await page.locator('[data-create-environment]:visible').count(),1,'Create environment has one existing entry on Environments');
  await expectMenu('#environment/overview?env=env-a',['Run assessment','Environment settings','Enrollment codes','Exportar CSV']);
  await page.locator('#open-settings').click();await assertClosed();assert.equal(await page.locator('#environment-dialog').isVisible(),true);await page.locator('[data-env-close]').first().click();
  await open();await menu.locator('[data-enrollment-list]').click();await assertClosed();assert.match(await page.locator('#enrollment-title').innerText(),/Production/);await page.locator('[data-enroll-close]').first().click();
  async function exportIds(expected){await open();await menu.locator('[data-export-scope]').click();await assertClosed();const selection=page.locator('[name=exportScope]');await selection.waitFor({state:'visible'});if(page.url().includes('#machine/'))await selection.selectOption('__machine__');const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#environment-submit').click()]);assert.equal(download.suggestedFilename(),'assessment-inventory-fixture.csv');const text=fs.readFileSync(await download.path(),'utf8').replace(/^\ufeff/,'').trimEnd(),rows=text.split('\r\n').map(line=>[...line.matchAll(/"((?:[^"]|"")*)"(?:,|$)/g)].map(m=>m[1].replace(/""/g,'"'))),columns=rows.shift(),body=rows.map(row=>Object.fromEntries(columns.map((c,i)=>[c,row[i]])));assert.deepEqual(body.filter(r=>r.record_type==='hosts').map(h=>h.host_id).sort(),expected.slice().sort());for(const key of ['runtimes','applications','servers','libraries','datasources','processes','jobs'])assert.ok(body.filter(r=>r.record_type===key).every(row=>expected.includes(row.host_id)),'Scoped CSV export '+key);await page.locator('#environment-dialog').waitFor({state:'hidden'});return fixture.requests.filter(r=>r.path==='/api/export.csv').at(-1).query;}

  await exportIds(['host-a']);
  await expectMenu('#machine/host-a?view=applications&env=env-a',['Run assessment','Desinstalar agente','Machine settings','Exportar CSV']);
  assert.equal(await menu.locator('[data-enrollment-list]').isVisible(),false);await page.locator('#open-settings').click();await assertClosed();assert.equal(await page.locator('#environment-dialog-title').innerText(),'Machine settings');await page.locator('[data-env-close]').first().click();
  assert.equal((await exportIds(['host-a'])).hostId,'host-a');
  await open();await page.locator('#tabs [data-tab="runtimes"]').click();await assertClosed();
  await open();await page.locator('#environment-filter').selectOption('env-b');await assertClosed();assert.ok(page.url().includes('#environment/runtimes'));await exportIds(['host-b']);
  await expectMenu('#environment/overview?env=empty',['Environment settings','Enrollment codes','Exportar CSV']);await exportIds([]);
  await expectMenu('#overview?env=unassigned',['Run assessment','Enrollment codes','Exportar CSV']);await exportIds(['host-c']);
  await expectMenu('#environment/overview?env=all',['Run assessment','Enrollment codes','Exportar CSV']);
  await expectMenu('#overview?env=env-a',['Run assessment','Environment settings','Enrollment codes','Exportar CSV']);
  await expectMenu('#environment/overview?env=revoked',['Environment settings','Enrollment codes','Exportar CSV']);await exportIds(['revoked-host']);
  await expectMenu('#machine/revoked-host?view=overview&env=revoked',['Desinstalar agente','Machine settings','Exportar CSV']);
  await expectMenu('#machine/host-c?view=overview&env=unassigned',['Run assessment','Desinstalar agente','Machine settings','Exportar CSV']);
  await open();await page.evaluate(()=>history.back());await assertClosed();
  await page.setViewportSize({width:390,height:844});await expectMenu('#machine/host-a?view=applications&env=all',['Run assessment','Desinstalar agente','Machine settings','Exportar CSV']);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await page.keyboard.press('Escape');await assertClosed();
  assert.deepEqual(fixture.requests.filter(req=>!['GET','HEAD'].includes(req.method)),[]);assert.deepEqual(errors,[]);
  console.log(JSON.stringify({status:'PASS',checks,behavior:['poll retains open menu','Escape focus restoration','clickaway/toggle/action/navigation/history close and reset aria','contextual action visibility','scoped export','mobile layout','no API mutations'],errors},null,2));
 }finally{if(browser)await browser.close();await fixture.close();}
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
