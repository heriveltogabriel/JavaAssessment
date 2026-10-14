'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {spawn}=require('node:child_process'),{once}=require('node:events'),{chromium}=require('playwright');
(async()=>{
 const cwd=path.resolve(__dirname,'..'),out=path.join(cwd,'tmp/compact-overview/evidence');fs.mkdirSync(out,{recursive:true});
 const server=spawn(process.env.BETA_JAVA||'java',['-Xmx384m','-cp',process.env.BETA_CLASSPATH||'tmp/compact-overview/classes:lib/*','assessment.CompactOverviewTest','--serve'],{cwd,stdio:['pipe','pipe','pipe']});
 let browser,page,diagnostics='';const errors=[],blocked=[];server.stderr.on('data',chunk=>diagnostics+=chunk);
 try{
  const base=await new Promise((resolve,reject)=>{let output='';const timer=setTimeout(()=>reject(Error('Fixture timeout '+diagnostics)),30000);server.stdout.on('data',chunk=>{output+=chunk;const match=output.match(/OVERVIEW_UI_URL=(\S+)/);if(match){clearTimeout(timer);resolve(match[1]);}});server.on('exit',code=>{clearTimeout(timer);reject(Error('Fixture exit '+code+' '+diagnostics));});});
  browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL||'chrome',headless:true});const ctx=await browser.newContext({viewport:{width:1440,height:1100}});await ctx.route('**/*',route=>new URL(route.request().url()).origin===base?route.continue():(blocked.push(route.request().url()),route.abort()));
  page=await ctx.newPage();page.setDefaultTimeout(12000);page.on('pageerror',error=>errors.push(error.message));
  await page.goto(base);await page.locator('#login-email').fill('fixture-admin@example.com');await page.locator('#password').fill('fixture-personal-password-1234');await page.locator('#login-submit').click();await page.locator('.overview-machine-row').first().waitFor();
  assert.equal(await page.locator('[data-landscape]').count(),4);assert.equal(await page.locator('.overview-machine-row').count(),10);assert.equal(await page.locator('.machine-card').count(),0);
  for(const kind of ['java','os','servers','jdbc']){assert.equal(await page.locator('[data-landscape="'+kind+'"] .ring-chart strong').textContent(),'100');assert.ok(await page.locator('[data-landscape="'+kind+'"] .landscape-group').count()<=4);}
  const jdbc=await page.locator('[data-landscape="jdbc"]').textContent();assert.match(jdbc,/BillingDS/);assert.match(jdbc,/8\.0\.33/);assert.match(jdbc,/InventoryDS/);assert.match(jdbc,/Driver: Não informada/);assert.doesNotMatch(jdbc,/Unrelated JDBC package/);
  await page.locator('.overview-landscapes').screenshot({path:path.join(out,'overview-panels.png')});await page.screenshot({path:path.join(out,'overview-desktop.png'),fullPage:true});
  const next=page.locator('.overview-machines [data-page="2"]');await next.click();await page.locator('.overview-machine-row [data-machine="machine-010"]').waitFor();
  await page.locator('#overview-machine-query').fill('machine-099');await page.locator('#overview-machine-search').getByRole('button',{name:'Buscar',exact:true}).click();await page.locator('.overview-machine-row [data-machine="machine-099"]').waitFor();assert.equal(await page.locator('.overview-machine-row').count(),1);assert.equal(await page.locator('[data-landscape="jdbc"] .ring-chart strong').textContent(),'100');
  await page.locator('#refresh').click();await page.waitForFunction(()=>document.querySelector('#refresh').disabled===false);assert.equal(await page.locator('#overview-machine-query').inputValue(),'machine-099');
  await page.locator('#overview-machine-query').fill('nao-existe');await page.locator('#overview-machine-search').getByRole('button',{name:'Buscar',exact:true}).click();await page.getByText('Nenhuma máquina corresponde à busca.').waitFor();assert.equal(await page.locator('.overview-machine-row').count(),0);
  await page.locator('[data-clear-machine-search]').click();await page.locator('.overview-machine-row').first().waitFor();await page.locator('#environment-filter').selectOption('env-b');await page.locator('.overview-machine-row [data-machine="machine-080"]').waitFor();assert.equal(await page.locator('[data-landscape="servers"] .ring-chart strong').textContent(),'20');
  await page.locator('[data-machine-actions="machine-080"]').click();await page.locator('#machine-actions-menu').waitFor();await page.keyboard.press('Escape');
  await page.locator('[data-landscape="jdbc"] [data-inventory]').click();await page.waitForFunction(()=>location.hash.includes('libraries'));assert.match(page.url(),/env=env-b/);assert.match(page.url(),/subset=datasources/);
  await page.goto(base+'/#environment/overview?env=env-b');await page.locator('.overview-machine-row').first().waitFor();await page.locator('[data-machine="machine-080"]').click();await page.waitForFunction(()=>location.hash.includes('machine/machine-080'));
  await page.goto(base+'/#overview?env=all');await page.locator('.overview-machine-row').first().waitFor();await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'overview-mobile.png'),fullPage:true});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  const rows=await page.locator('.overview-machines .table-wrap').evaluate(el=>({width:el.clientWidth,scroll:el.scrollWidth}));assert.ok(rows.scroll>rows.width,'table scroll is contained on mobile');
  assert.deepEqual(errors,[]);assert.deepEqual(blocked,[]);console.log('PASS: four compact panels, 100-host SQL pagination/search, correct JDBC names/versions, scope, navigation, actions, desktop/mobile and no JavaScript errors.');
 }catch(error){if(page)await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});throw error;}
 finally{await browser?.close();server.stdin.end();if(server.exitCode===null)await once(server,'exit');if(diagnostics)process.stderr.write(diagnostics);}
})().catch(error=>{console.error(error);process.exitCode=1;});
