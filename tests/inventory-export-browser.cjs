/* Disposable Java/SQLite + actual browser download. Never contacts a remote dashboard. */
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs');
const {spawn}=require('node:child_process'),{once}=require('node:events'),{chromium}=require('playwright');
(async()=>{
  const cwd=path.resolve(__dirname,'..'),out=path.join(cwd,'tmp/inventory-export-ui');fs.mkdirSync(out,{recursive:true});
  const server=spawn(process.env.BETA_JAVA||'java',['-Xmx384m','-cp','tmp/source-classes:lib/*','assessment.UserAccessUiFixture',path.join(cwd,'web')],{cwd,stdio:['pipe','pipe','pipe']});
  let diagnostics='',browser,page;server.stderr.on('data',data=>diagnostics+=data);
  const errors=[],blocked=[];
  try{
    const base=await new Promise((resolve,reject)=>{let text='';const timer=setTimeout(()=>reject(Error('Fixture timeout '+diagnostics)),30000);server.stdout.on('data',data=>{text+=data;const match=text.match(/USER_ACCESS_UI_URL=(\S+)/);if(match){clearTimeout(timer);resolve(match[1]);}});server.on('exit',code=>{clearTimeout(timer);reject(Error('Fixture exit '+code+' '+diagnostics));});});
    browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL||'chrome',headless:true});
    const context=await browser.newContext({viewport:{width:1440,height:1100},acceptDownloads:true});
    await context.route('**/*',route=>new URL(route.request().url()).origin===base?route.continue():(blocked.push(route.request().url()),route.abort()));
    const provision=await context.request.post(base+'/api/auth/bootstrap',{data:{currentPassword:'legacy-fixture-secret',name:'Fixture Admin',email:'fixture-admin@example.com',password:'fixture-personal-password-1234'}});assert.ok([200,201].includes(provision.status()));
    page=await context.newPage();page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));
    await page.goto(base);
    await page.locator('#side-nav [data-tab="administration"]').click();await page.locator('#access-tab-export').click();
    await page.locator('#inventory-export-submit').waitFor();assert.match(await page.locator('#inventory-export-panel').textContent(),/senhas, tokens, chaves privadas/);
    await page.locator('#inventory-export-label').fill('Cliente de teste');
    const downloadWait=page.waitForEvent('download');await page.locator('#inventory-export-submit').click();const download=await downloadWait;
    assert.match(download.suggestedFilename(),/^java-assessment-inventario-[0-9-]+\.zip$/);await download.saveAs(path.join(out,'inventario.zip'));assert.equal(await download.failure(),null);
    await page.getByText('Pacote gerado. O download foi iniciado;', {exact:false}).waitFor();assert.equal(await page.locator('#inventory-export-submit').isEnabled(),true);
    await page.screenshot({path:path.join(out,'desktop.png'),fullPage:true});
    await page.locator('#access-tab-export').focus();await page.keyboard.press('ArrowRight');assert.equal(await page.locator('#access-tab-update').getAttribute('aria-selected'),'true');
    await page.keyboard.press('ArrowLeft');await page.locator('#inventory-export-submit').waitFor();assert.equal(await page.locator('#access-tab-export').getAttribute('aria-selected'),'true');
    await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'mobile.png'),fullPage:true});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'no page horizontal overflow');
    assert.deepEqual(errors,[]);assert.deepEqual(blocked,[]);
    console.log('Inventory export browser passed: admin tab, real ZIP download, keyboard, desktop/mobile, no JS errors.');
  }catch(error){if(page)await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});throw error;}
  finally{await browser?.close();server.stdin.end();if(server.exitCode===null)await once(server,'exit');if(diagnostics)process.stderr.write(diagnostics);}
})().catch(error=>{console.error(error);process.exitCode=1;});
