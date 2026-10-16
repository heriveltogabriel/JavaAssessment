'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {spawn}=require('node:child_process'),{once}=require('node:events'),{chromium}=require('playwright');
(async()=>{
 const cwd=path.resolve(__dirname,'..'),out=path.join(cwd,'tmp/environment-presence/evidence');fs.mkdirSync(out,{recursive:true});
 const server=spawn(process.env.BETA_JAVA||'java',['-Xmx384m','-cp',process.env.BETA_CLASSPATH||'tmp/source-classes:lib/*','assessment.CompactOverviewTest','--serve'],{cwd,stdio:['pipe','pipe','pipe']});
 let browser,diagnostics='';const errors=[];server.stderr.on('data',chunk=>diagnostics+=chunk);
 try{
  const base=await new Promise((resolve,reject)=>{let output='';const timer=setTimeout(()=>reject(Error('Fixture timeout '+diagnostics)),30000);server.stdout.on('data',chunk=>{output+=chunk;const match=output.match(/OVERVIEW_UI_URL=(\S+)/);if(match){clearTimeout(timer);resolve(match[1]);}});server.on('exit',code=>{clearTimeout(timer);reject(Error('Fixture exit '+code+' '+diagnostics));});});
  browser=await chromium.launch({channel:'chrome',headless:true});const ctx=await browser.newContext({viewport:{width:1440,height:1000}});await ctx.route('**/*',route=>new URL(route.request().url()).origin===base?route.continue():route.abort());
  const page=await ctx.newPage();page.setDefaultTimeout(12000);page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base);await page.locator('#login-email').fill('fixture-admin@example.com');await page.locator('#password').fill('fixture-personal-password-1234');await page.locator('#login-submit').click();await page.locator('.overview-machine-row').first().waitFor();
  await page.locator('#side-nav [data-tab="environments"]').click();await page.locator('.environment-card').first().waitFor();
  const silent=page.locator('[data-environment-presence="env-a"][data-presence="silent"]');
  assert.match(await silent.innerText(),/9 Agentes Desconectados/);assert.match(await page.locator('[data-environment-presence="env-a"][data-presence="recent"]').innerText(),/71 Agentes Conectados/);
  assert.equal(await page.locator('[data-environment-coverage]').count(),0);assert.doesNotMatch(await page.locator('.environment-card-insights').first().innerText(),/5 min ou mais/);
  await page.screenshot({path:path.join(out,'environment-desktop.png'),fullPage:true});
  await silent.click();await page.waitForURL(/subset=silent/);await page.locator('#inventory-table tbody tr').first().waitFor();
  assert.equal(await page.locator('#inventory-table tbody tr').count(),9);assert.equal(await page.locator('#subset-filter').innerText(),'Máquinas sem comunicação');assert.equal(await page.locator('#environment-filter').inputValue(),'env-a');
  assert.match(await page.locator('#inventory-table tbody').innerText(),/Sem contato com o agente/);assert.doesNotMatch(await page.locator('#inventory-table tbody').innerText(),/machine-081/);
  await page.reload();await page.locator('#subset-filter').waitFor();assert.equal(await page.locator('#inventory-table tbody tr').count(),9);
  await page.locator('#refresh').click();await page.waitForFunction(()=>!document.querySelector('#refresh').disabled);assert.equal(await page.locator('#inventory-table tbody tr').count(),9);
  await page.locator('#clear-filters').click();await page.waitForURL(u=>!u.hash.includes('subset='));await page.waitForFunction(()=>document.querySelectorAll('#inventory-table tbody tr').length===20);
  await page.locator('#environment-filter').selectOption('');await page.locator('#side-nav [data-tab="environments"]').click();await page.locator('[data-environment-presence="env-b"][data-presence="silent"]').click();await page.waitForURL(/env=env-b.*subset=silent/);await page.waitForFunction(()=>document.querySelectorAll('#inventory-table tbody tr').length===3);
  await page.goBack();await page.locator('.environment-card').first().waitFor();await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'environment-mobile.png'),fullPage:true});
  assert.ok(await page.locator('.environment-card-insights').first().evaluate(el=>el.scrollWidth<=el.clientWidth+1));
  assert.deepEqual(errors,[]);console.log('ENVIRONMENT_PRESENCE_BROWSER=PASS real HTTP/SQLite, counts, scoped filters, reload, clearing, refresh, back and mobile');
 }finally{if(browser)await browser.close();server.stdin.end('\n');if(server.exitCode===null)await once(server,'exit');}
})().catch(e=>{console.error(e);process.exitCode=1;});
