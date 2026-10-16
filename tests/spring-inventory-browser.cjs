'use strict';
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs');
const {chromium}=require('playwright');
const {startFixture}=require('./fixtures/environments-fixture.cjs');
(async()=>{
 const root=path.resolve(__dirname,'..'),fixture=await startFixture(path.join(root,'web')),state=fixture.state;
 state.applications=[{id:'boot',hostId:'host-a',name:'Orders <img src=x>',type:'Spring Boot',applicationType:'Spring Boot',frameworkVersion:'3.5',embeddedServers:[{type:'Tomcat',version:'10.1.42'},{type:'<img src=x>',version:'<script>bad()</script>'}],activeProfiles:['prod','<script>bad()</script>'],configurationResolution:'partial',state:'PROCESS_RUNNING'}];
 state.datasources=[{id:'reported',hostId:'host-a',name:'ordersPool <img src=x>',applicationId:'boot',applicationName:'Orders',state:'APPLICATION_REPORTED',evidenceLevel:'application-reported',driverName:'PostgreSQL',driverVersion:'42.7',databaseType:'PostgreSQL',databaseVersion:'16.4',databaseVersionEvidence:'application-reported',datasourceKey:'orders',poolName:'orders-pool',configurationResolution:'partial'}, {id:'passive',hostId:'host-a',name:'legacy',state:'CONFIGURED',evidenceLevel:'configuration',driverVersion:'42.6'}];
 let browser;
 try {
  browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL||'chrome',headless:true});const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),errors=[];
  await context.route('**/*',route=>{const url=new URL(route.request().url());if(url.origin!==fixture.base)return route.abort();if(/^\/[a-z-]+\.(js|css)$/.test(url.pathname))return route.fulfill({path:path.join(root,'web',url.pathname.slice(1)),contentType:url.pathname.endsWith('.js')?'application/javascript':'text/css'});return route.continue();});page.on('pageerror',error=>errors.push(error.message));
  await page.goto(fixture.base+'/#libraries?env=all&subset=datasources');await page.locator('#login-layer').waitFor({state:'hidden'});
  await page.locator('#inventory-table [data-detail-id="reported"]').click();const drawer=page.locator('#drawer-content');
  assert.match(await drawer.innerText(),/Informado pela aplicação/);assert.match(await drawer.innerText(),/não verificou independentemente/);assert.match(await drawer.innerText(),/16\.4/);assert.match(await drawer.innerText(),/42\.7/);assert.match(await drawer.innerText(),/orders-pool/);assert.equal(await drawer.locator('img,script').count(),0);
  assert.equal(await drawer.locator('dt').filter({hasText:/^Versão do banco$/}).count(),1);
  await drawer.locator('[data-detail-kind="applications"][data-detail-id="boot"]').first().click();assert.match(await drawer.innerText(),/Profiles identificados/i);assert.match(await drawer.innerText(),/Tomcat · 10\.1\.42/);assert.match(await drawer.innerText(),/ativação em execução não confirmada/);assert.doesNotMatch(await drawer.innerText(),/\[object Object\]/);assert.match(await drawer.innerText(),/prod, <script>bad\(\)<\/script>/);assert.match(await drawer.innerText(),/Parcial; existem fontes ou valores não resolvidos/);assert.equal(await drawer.locator('img,script').count(),0);
  await page.locator('#close-drawer').click();await page.locator('#inventory-table [data-detail-id="passive"]').click();assert.match(await drawer.innerText(),/Não identificada/);assert.match(await drawer.innerText(),/Configuration found/);assert.doesNotMatch(await drawer.innerText(),/Informado pela aplicação/);
  await page.setViewportSize({width:390,height:844});assert.equal(await drawer.evaluate(el=>el.scrollWidth>el.clientWidth),false);assert.deepEqual(errors,[]);
  fs.mkdirSync(path.join(root,'output/spring-inventory'),{recursive:true});await page.screenshot({path:path.join(root,'output/spring-inventory/passive-details-mobile.png')});
  console.log('PASS: Spring detail profiles, partial configuration, application-reported evidence, distinct driver/database versions, old snapshot fallback, escaped strings and mobile layout');
 }finally{if(browser)await browser.close();await fixture.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
