/* Real Java/SQLite fixture; all traffic stays on loopback. */
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs');
const {spawn}=require('node:child_process'),{once}=require('node:events'),{chromium}=require('playwright');
(async()=>{
 const cwd=path.resolve(__dirname,'..'),release=JSON.parse(fs.readFileSync(path.join(cwd,'release/dashboard.json'),'utf8'));
 const out=process.env.VERSION_EVIDENCE||path.join(cwd,'output/dashboard-releases',release.version,'evidence');fs.mkdirSync(out,{recursive:true});
 const server=spawn(process.env.BETA_JAVA||'java',['-cp',process.env.BETA_CLASSPATH,'assessment.RuntimeUiFixture',path.join(cwd,'web')],{cwd,stdio:['pipe','pipe','pipe']});
 let diagnostics='',browser;server.stderr.on('data',data=>diagnostics+=data);
 try{
  const base=await new Promise((resolve,reject)=>{let output='';const timer=setTimeout(()=>reject(Error('Fixture timeout '+diagnostics)),30000);server.stdout.on('data',data=>{output+=data;const match=output.match(/RUNTIME_UI_URL=(\S+)/);if(match){clearTimeout(timer);resolve(match[1]);}});server.on('exit',code=>{clearTimeout(timer);reject(Error('Fixture exit '+code+' '+diagnostics));});});
  browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL||'chrome',headless:true});
  const context=await browser.newContext({viewport:{width:1274,height:900}}),blocked=[];
  await context.route('**/*',route=>new URL(route.request().url()).origin===base?route.continue():(blocked.push(route.request().url()),route.abort()));
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));page.setDefaultTimeout(10000);
  await page.goto(base);const health=await (await context.request.get(base+'/health')).json();assert.equal(health.version,release.version);
  await page.waitForFunction(version=>document.getElementById('dashboard-version').textContent==='Versão '+version,health.version);
  assert.equal(await page.locator('#interface-version').textContent(),'Versão '+health.version);
  await page.locator('#login-email').fill('fixture-admin@example.com');await page.locator('#password').fill('fixture-personal-password-1234');await page.locator('#login-submit').click();await page.locator('.overview-machine-row').first().waitFor();
  assert.equal(await page.locator('.metric-card[data-inventory="runtimes"] strong').innerText(),'1');
  await page.locator('#about').click();await page.locator('#detail-drawer').waitFor({state:'visible'});
  assert.match(await page.locator('#drawer-content').innerText(),new RegExp(health.version.replaceAll('.','\\.')));
  assert.match(await page.locator('#drawer-content').innerText(),/Release built at/i);await page.locator('#close-drawer').click();
  await page.screenshot({path:path.join(out,'dashboard-version-desktop.png')});
  await page.reload();await page.waitForFunction(version=>document.getElementById('dashboard-version').textContent==='Versão '+version,health.version);
  await page.locator('.overview-machine-row').first().waitFor();assert.equal(await page.locator('#interface-version').textContent(),'Versão '+health.version);
  await page.setViewportSize({width:390,height:844});assert.equal(await page.locator('#dashboard-version').isVisible(),true,'Full version visible on mobile');
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.evaluate(()=>new Promise(resolve=>{scrollTo(0,0);requestAnimationFrame(()=>requestAnimationFrame(resolve));}));
  assert.equal(await page.locator('header.topbar').count(),1);
  await page.screenshot({path:path.join(out,'dashboard-version-mobile.png')});
  await page.locator('#nav-toggle').click();assert.equal(await page.locator('#interface-version').isVisible(),true);
  assert.equal(await page.locator('#interface-version').textContent(),'Versão '+health.version);await page.locator('#nav-toggle').click();
  assert.deepEqual(errors,[]);assert.deepEqual(blocked,[]);
  const result={status:'PASS',dashboardVersion:health.version,checks:['header/sidebar/About equal real HTTP version','release timestamp','reload retains version','headless Java runtime metric=1','full version visible on mobile','no overflow or JavaScript errors'],remoteOperationsExecuted:false};
  fs.writeFileSync(path.join(out,'version-browser-results.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
 }finally{await browser?.close();server.stdin.end();if(server.exitCode===null)await once(server,'exit');if(diagnostics)process.stderr.write(diagnostics);}
})().catch(error=>{console.error(error);process.exitCode=1;});
