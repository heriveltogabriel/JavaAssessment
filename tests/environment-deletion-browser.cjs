/* Destructive requests are restricted to this ephemeral loopback fixture. */
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {startFixture}=require('./fixtures/environments-fixture.cjs');
(async()=>{
 const root=path.resolve(__dirname,'..'),out=path.join(__dirname,'fixtures/environment-deletion-output');fs.mkdirSync(out,{recursive:true});
 const fixture=await startFixture(path.join(root,'web'));
 const escapedName='QA <img src=x onerror=window.xss=1> & "Blue"';
 fixture.state.environments.push({id:'env-empty',name:escapedName,description:'Empty environment for deletion checks',settings:{}},{id:'env-race',name:'Release Review',description:'Concurrent rename fixture',settings:{}});
 fixture.state.enrollments.push({id:'code-empty',environmentId:'env-empty',label:'Empty environment installer',status:'active'});
 let browser;
 try{
  browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL || 'chrome',headless:true});
  const context=await browser.newContext({viewport:{width:1274,height:862}});await context.route('**/*',route=>new URL(route.request().url()).origin===fixture.base?route.continue():route.abort());
  const page=await context.newPage(),errors=[];page.setDefaultTimeout(10000);page.on('pageerror',e=>errors.push(e.message));
  await page.goto(fixture.base+'/#environments');await page.locator('#login-layer').waitFor({state:'hidden'});await page.locator('.environment-card').first().waitFor();
  const dialog=page.locator('#environment-dialog'),input=page.locator('[name="confirmationName"]'),submit=page.locator('#environment-submit');
  const deleteRequests=()=>fixture.requests.filter(r=>r.method==='DELETE');
  const cancel=async()=>{await dialog.locator('[data-env-close]').last().click();await dialog.waitFor({state:'hidden'});};
  const forceSubmit=()=>page.locator('#environment-form').evaluate(form=>form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  const openDelete=async id=>{assert.equal(await page.locator('.environment-card [data-delete-environment]').count(),0);await page.locator('[data-edit-environment="'+id+'"]').click();if(id==='env-empty'){assert.equal(await dialog.evaluate(el=>el.scrollWidth>el.clientWidth),false);await page.screenshot({path:path.join(out,page.viewportSize().width<600?'05-edit-delete-mobile.png':'04-edit-delete-desktop.png')});}const action=dialog.locator('[data-delete-environment="'+id+'"]');assert.equal(await action.getAttribute('type'),'button');assert.equal(await submit.innerText(),'Save changes');const saves=fixture.requests.filter(r=>r.method==='PUT'&&r.path==='/api/environments').length;await action.click();assert.equal(await page.locator('#environment-dialog-title').innerText(),'Delete environment');assert.equal(fixture.requests.filter(r=>r.method==='PUT'&&r.path==='/api/environments').length,saves,'opening deletion never submits Edit');};

  await openDelete('env-a');
  assert.ok((await page.locator('#environment-delete-description').innerText()).includes('1 registered machine'));
  assert.equal(await input.count(),0);assert.equal(await submit.isDisabled(),true);await forceSubmit();assert.equal(deleteRequests().length,0);await cancel();
  await openDelete('env-empty');
  assert.equal(await input.evaluate(el=>document.activeElement===el),true,'confirmation receives focus');
  assert.equal(await dialog.getAttribute('aria-describedby'),'environment-delete-description');
  assert.equal(await submit.isDisabled(),true);assert.equal(await dialog.locator('img').count(),0);assert.equal(await page.evaluate(()=>window.xss),undefined);
  assert.equal(await page.locator('.environment-delete-heading h3').innerText(),escapedName);
  assert.ok((await page.locator('#environment-delete-description').innerText()).includes('previously downloaded packages'));
  for(const wrong of ['wrong',escapedName.toLowerCase(),' '+escapedName,escapedName+' ']){
   await input.fill(wrong);assert.equal(await submit.isDisabled(),true);await input.press('Enter');await forceSubmit();assert.equal(deleteRequests().length,0,'mismatched input cannot submit');
  }
  await input.fill(escapedName);assert.equal(await submit.isDisabled(),false);await cancel();
  await openDelete('env-empty');assert.equal(await input.inputValue(),'');assert.equal(await submit.isDisabled(),true);await cancel();
  await page.locator('.environments-page [data-create-environment]').click();assert.equal(await dialog.locator('[data-delete-environment]').count(),0);assert.equal(await submit.isDisabled(),false);assert.equal(await submit.evaluate(el=>el.classList.contains('danger')),false);assert.equal(await dialog.getAttribute('aria-describedby'),null);await cancel();
  // A rename received by the normal refresh cycle invalidates the displayed name.
  await openDelete('env-race');await input.fill('Release Review');
  fixture.state.environments.find(e=>e.id==='env-race').name='Release Review Renamed';
  await page.locator('#refresh').evaluate(el=>el.click());await page.waitForFunction(()=>document.getElementById('environment-submit').disabled);
  await forceSubmit();assert.ok((await page.locator('#environment-error').innerText()).includes('name changed'));assert.equal(deleteRequests().length,0);await cancel();
  await page.locator('#environment-filter').selectOption('env-empty');await openDelete('env-empty');
  await input.fill(escapedName);fixture.failNextDelete='A machine joined this environment. Move all machines before deleting it.';
  await submit.click();await page.locator('#environment-error').waitFor({state:'visible'});
  assert.equal(await dialog.isVisible(),true);assert.equal(await input.inputValue(),escapedName);assert.equal(await submit.isDisabled(),false);assert.equal(await input.isDisabled(),false);
  assert.ok((await page.locator('#environment-error').innerText()).includes('A machine joined'));assert.equal(fixture.state.environments.some(e=>e.id==='env-empty'),true);
  await page.screenshot({path:path.join(out,'01-delete-error-desktop.png')});
  await cancel();await page.setViewportSize({width:390,height:844});await openDelete('env-empty');
  assert.equal(await input.inputValue(),'');assert.equal(await submit.isDisabled(),true);await input.fill(escapedName);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.equal(await dialog.evaluate(el=>el.scrollWidth>el.clientWidth),false);
  await page.screenshot({path:path.join(out,'02-delete-confirm-mobile.png')});
  // Pending deletion disables close and rejects duplicate event dispatches.
  fixture.deleteDelay=500;const before=deleteRequests().length;await submit.click();
  assert.equal(await submit.innerText(),'Deleting…');assert.equal(await submit.isDisabled(),true);assert.equal(await input.isDisabled(),true);assert.equal(await dialog.getAttribute('aria-busy'),'true');
  assert.equal(await dialog.locator('[data-env-close]').last().isDisabled(),true);await page.keyboard.press('Escape');assert.equal(await dialog.isVisible(),true);await forceSubmit();
  await dialog.waitFor({state:'hidden'});await page.waitForFunction(()=>document.getElementById('environment-filter').value==='');
  assert.equal(deleteRequests().length,before+1);assert.deepEqual(deleteRequests().at(-1).body,{environmentId:'env-empty',confirmationName:escapedName});
  assert.equal(deleteRequests().at(-1).csrf,'fixture-csrf');assert.equal(fixture.state.environments.some(e=>e.id==='env-empty'),false);assert.equal(fixture.state.enrollments.find(e=>e.id==='code-empty').status,'revoked');
  assert.equal(await page.locator('#environment-filter option[value="env-empty"]').count(),0);assert.equal(await page.evaluate(()=>localStorage.getItem('java-assessment.environment')),'');assert.equal(new URL(page.url()).hash,'#environments?env=all');
  assert.equal(await page.locator('.environment-card').count(),3);assert.equal(fixture.state.hosts.length,3);assert.equal(await page.locator('#tabs').isVisible(),false);assert.deepEqual(errors,[]);
  await page.screenshot({path:path.join(out,'03-deleted-mobile.png')});
  const result={status:'PASS',scope:'isolated loopback fixture only',checks:['Delete appears only inside Edit, never on cards or Create','Delete opens confirmation without submitting Edit','populated environment blocked without DELETE','exact name including case and whitespace','Enter and programmatic submit mismatch blocked','cancel and reopen reset confirmation','other dialog modes reset destructive styles','concurrent rename invalidates confirmation','409 preserves confirmation and shows error','one DELETE while pending; cancel and Escape blocked','CSRF and exact JSON contract','deleted scope and stored filter reset','environment and package revoked in fixture; hosts preserved','escaped environment name','desktop and mobile layout without overflow','no JavaScript errors']};
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
 }finally{if(browser)await browser.close();await fixture.close();}
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
