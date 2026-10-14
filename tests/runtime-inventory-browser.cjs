/* Real Java/SQLite and collector fixture. Every browser request stays on loopback. */
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs');
const {spawn}=require('node:child_process'),{once}=require('node:events');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const cwd=path.resolve(__dirname,'..'),out=process.env.ASSESSMENT_TEST_EVIDENCE||path.join(cwd,'output/runtime-without-compiler/evidence');fs.mkdirSync(out,{recursive:true});
 const server=spawn(process.env.BETA_JAVA||'java',['-cp',process.env.BETA_CLASSPATH||'output/runtime-without-compiler/build/server:output/runtime-without-compiler/build/collector:lib/*','assessment.RuntimeUiFixture',path.join(cwd,'web')],{cwd,stdio:['pipe','pipe','pipe']});
 let diagnostics='',browser;server.stderr.on('data',data=>diagnostics+=data);
 try {
  const base=await new Promise((resolve,reject)=>{let output='';const timer=setTimeout(()=>reject(Error('Fixture start timeout: '+diagnostics)),30000);server.stdout.on('data',data=>{output+=data;const match=output.match(/RUNTIME_UI_URL=(\S+)/);if(match){clearTimeout(timer);resolve(match[1]);}});server.on('exit',code=>{clearTimeout(timer);reject(Error('Fixture exited '+code+': '+diagnostics));});});
  browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL||'chrome',headless:true});
  const context=await browser.newContext({viewport:{width:1274,height:900}});const blocked=[];
  await context.route('**/*',route=>{if(new URL(route.request().url()).origin===base)return route.continue();blocked.push(route.request().url());return route.abort();});
  const page=await context.newPage(),errors=[];page.on('pageerror',error=>errors.push(error.message));page.setDefaultTimeout(10000);
  await page.goto(base);await page.locator('#login-email').fill('fixture-admin@example.com');await page.locator('#password').fill('fixture-personal-password-1234');await page.locator('#login-submit').click();await page.locator('.machine-card').first().waitFor();
  const ready=()=>page.waitForFunction(()=>!document.getElementById('main-content').hasAttribute('aria-busy'));
  assert.equal(await page.locator('.metric-card[data-inventory="runtimes"] strong').innerText(),'1');
  assert.equal(await page.locator('.metric-card[data-inventory="hosts"] strong').innerText(),'1');
  await page.screenshot({path:path.join(out,'runtime-no-compiler-desktop.png')});
  await page.locator('.metric-card[data-inventory="runtimes"]').click();await ready();
  assert.equal(await page.locator('#inventory-table tbody tr').count(),1);
  assert.match(await page.locator('#inventory-table tbody').innerText(),/21\.0\.12/);
  assert.match(await page.locator('#inventory-table tbody').innerText(),/JRE/);
  assert.match(await page.locator('#inventory-table tbody').innerText(),/Confirmed/);
  assert.match(await page.locator('#inventory-table tbody').innerText(),/aarch64/);
  await page.locator('#inventory-table [data-detail-kind="runtimes"]').click();await page.locator('#detail-drawer').waitFor({state:'visible'});
  assert.match(await page.locator('#drawer-content').innerText(),/JRE/);assert.match(await page.locator('#drawer-content').innerText(),/21\.0\.12/);
  await page.locator('#close-drawer').click();await page.locator('#tabs [data-tab="applications"]').click();await ready();
  assert.equal(await page.locator('#inventory-table tbody tr').count(),2);
  await page.locator('#inventory-table [data-detail-kind="applications"]').first().click();await page.locator('#detail-drawer').waitFor({state:'visible'});
  assert.match(await page.locator('#drawer-content').innerText(),/java-21-openjdk-21\.0\.12/);await page.locator('#close-drawer').click();
  await page.locator('#inventory-table [data-detail-kind="hosts"]').first().click();await ready();
  assert.equal(await page.locator('.host-metrics .metric-card[data-inventory="runtimes"] strong').innerText(),'1');
  await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.screenshot({path:path.join(out,'runtime-no-compiler-mobile.png')});
  assert.deepEqual(errors,[]);assert.deepEqual(blocked,[]);
  fs.writeFileSync(path.join(out,'runtime-browser-results.json'),JSON.stringify({status:'PASS',environment:'isolated loopback; real collector fixture and SQLite',checks:['no-javac runtime metric=1','two JVMs counted as one installation','JRE/version/in-use details','two application records share Java home','machine metric=1','desktop/mobile without overflow or JavaScript errors']},null,2)+'\n');
  console.log('PASS: Java 21 without javac shows one installation, two JVMs/applications, runtime details and desktop/mobile metrics.');
 }finally{await browser?.close();server.stdin.end();if(server.exitCode===null)await once(server,'exit');if(diagnostics)process.stderr.write(diagnostics);}
})().catch(error=>{console.error(error);process.exitCode=1;});
