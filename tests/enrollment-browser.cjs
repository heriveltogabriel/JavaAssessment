/* Browser contract test: all requests terminate at an isolated loopback fixture. */
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {startFixture}=require('./fixtures/enrollment-fixture.cjs');
(async()=>{
 const root=path.resolve(__dirname,'..'),out=path.join(root,'output/versioned-download-csv/evidence/enrollment-browser');fs.mkdirSync(out,{recursive:true});
 const fixture=await startFixture(path.join(root,'web'));
 const browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL || 'chrome',headless:true});
 try{
  const context=await browser.newContext({viewport:{width:1274,height:862}});
  const page=await context.newPage(),errors=[];page.setDefaultTimeout(10000);page.on('pageerror',e=>errors.push(e.message));
  await context.route('**/*',route=>new URL(route.request().url()).origin===fixture.base?route.continue():route.abort());
  await page.goto(fixture.base);await page.locator('#login-layer').waitFor({state:'hidden'});
  await page.locator('#actions-toggle').click();await page.locator('#open-settings').click();await page.locator('#modal').waitFor({state:'visible'});await page.locator('#cancel-modal').click();
  // The visible Add machine action now uses configured environment packages. Exercise the retained generic wizard directly.
  await page.evaluate(()=>AssessmentEnrollment.openWizard());
  await page.getByLabel('Enrollment label',{exact:true}).fill('Fixture second machine');
  assert.equal(await page.locator('[name="expiresInMinutes"]').inputValue(),'60');assert.equal(await page.locator('[name="maxUses"]').inputValue(),'1');assert.equal(await page.locator('[name="autoCollect"]').isChecked(),true);
  await page.locator('[name="expiresInMinutes"]').fill('4');await page.locator('#enrollment-submit').click();assert.equal(fixture.state.enrollments.length,0,'HTML validity must reject sub-minimum expiry');
  await page.locator('[name="expiresInMinutes"]').fill('60');
  await page.screenshot({path:path.join(out,'01-wizard-desktop.png')});
  await page.locator('#enrollment-submit').click();await page.locator('#enrollment-token').waitFor();
  assert.ok(fixture.requests.some(r=>r.path==='/api/collector/info'&&r.method==='GET'));
  const token=await page.locator('#enrollment-token').inputValue(),request=fixture.requests.find(r=>r.path==='/api/enrollments'&&r.method==='POST');
  assert.equal(request.csrf,'fixture-csrf');assert.deepEqual(request.body,{label:'Fixture second machine',expiresInMinutes:60,maxUses:1,settings:{includePaths:['/usr/java','/usr/lib/jvm','/opt','/home/opc/oracle']},autoCollect:true});
  assert.equal(fixture.state.settings.enabled,false);assert.equal(JSON.stringify(fixture.state).includes(token),false);
  assert.equal(await page.evaluate(t=>JSON.stringify(localStorage).includes(t)||JSON.stringify(sessionStorage).includes(t),token),false);
  await page.locator('#enrollment-submit').click();await page.locator('#enrollment-user-command').waitFor();
  const command=await page.locator('#enrollment-user-command').textContent();assert.ok(command.includes('./install.sh --server '+fixture.base));assert.ok(!command.includes(token));assert.ok(!command.includes('--token'));assert.ok(command.includes('unzip assessment-collector-1.5.0.zip -d java-assessment-installer'));
  await page.locator('.enrollment-system summary').first().click();assert.ok((await page.locator('#enrollment-system-command').textContent()).includes('sudo ./install.sh --system --server'));
  await page.screenshot({path:path.join(out,'02-install-commands-desktop.png')});
  await page.locator('#enrollment-back').click();await page.locator('#enrollment-token').waitFor();
  const [download]=await Promise.all([page.waitForEvent('download'),page.getByRole('link',{name:'Download installer package',exact:true}).click()]);assert.equal(download.suggestedFilename(),'assessment-collector-1.5.0.zip');
  await page.locator('[data-enroll-close]').first().click();await page.locator('#enrollment-dialog').waitFor({state:'hidden'});assert.equal(await page.locator('#enrollment-token').count(),0);assert.equal((await page.locator('body').textContent()).includes(token),false);
  await page.locator('#actions-toggle').click();await page.locator('#actions-menu [data-enrollment-list]').click();await page.getByRole('button',{name:'Revoke code',exact:true}).click();assert.equal(fixture.state.enrollments[0].status,'active');
  await page.locator('[name="confirmRevoke"]').check();await page.locator('#enrollment-submit').click();await page.locator('#enrollment-dialog').waitFor({state:'hidden'});assert.equal(fixture.state.enrollments[0].status,'revoked');
  await page.locator('[data-machine="fixture-host-1"]').first().click();await page.locator('.installation-panel').waitFor();
  assert.ok((await page.locator('.installation-panel').textContent()).includes('First assessment'));assert.ok((await page.locator('.installation-panel').textContent()).includes('First operational contact'));assert.ok((await page.locator('.installation-panel').textContent()).includes('Some paths are not readable.'));
  await page.locator('[data-host-settings="fixture-host-1"]').click();await page.locator('.environment-settings summary').click();await page.locator('[name="override:maxFiles"]').check();await page.locator('[name="value:maxFiles"]').fill('13000');await page.locator('#environment-submit').click();await page.locator('#environment-dialog').waitFor({state:'hidden'});
  assert.equal(fixture.state.hosts[0].settings.maxFiles,13000);assert.equal(fixture.state.hosts[1].settings.maxFiles,12000);assert.equal(fixture.state.settings.maxFiles,12000);
  await page.screenshot({path:path.join(out,'03-machine-progress-desktop.png')});
  await page.locator('[data-host-revoke="fixture-host-1"]').click();await page.locator('[data-enroll-close]').first().click();assert.equal(fixture.state.hosts[0].credentialStatus,'active','Cancel must preserve credential');
  await page.locator('[data-host-revoke="fixture-host-1"]').click();await page.locator('[name="confirmRevoke"]').check();await page.locator('#enrollment-submit').click();await page.locator('#enrollment-dialog').waitFor({state:'hidden'});assert.equal(fixture.state.hosts[0].credentialStatus,'revoked');assert.ok(await page.locator('[data-host-revoke="fixture-host-1"]').isDisabled());assert.ok(await page.locator('.machine-hero [data-run]').isDisabled());
  await page.locator('#side-nav [data-tab="overview"]').click();await page.locator('[data-machine="fixture-host-2"]').first().click();assert.ok((await page.locator('.install-events').textContent())==='');assert.ok((await page.locator('.installation-panel').textContent()).includes('No installation events received'));
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'04-machine-progress-mobile.png')});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'Machine page must not overflow');
  await page.evaluate(()=>AssessmentEnrollment.openWizard());await page.locator('[name="label"]').fill('Fixture rejected request');fixture.failNextEnrollment=true;await page.locator('#enrollment-submit').click();await page.locator('#enrollment-error').waitFor({state:'visible'});assert.ok((await page.locator('#enrollment-error').textContent()).includes('rejected'));assert.equal(fixture.state.enrollments.length,1);
  await page.screenshot({path:path.join(out,'05-wizard-mobile.png')});assert.equal(await page.locator('#enrollment-dialog').evaluate(e=>e.scrollWidth>e.clientWidth),false,'Wizard must not overflow');
  await page.locator('[data-enroll-close]').first().click();
  assert.deepEqual(errors,[]);assert.ok(fixture.requests.filter(r=>r.method==='POST'||r.method==='PUT').every(r=>r.csrf==='fixture-csrf'));
  const result={status:'PASS',environment:'isolated loopback fixture; no live credentials or writes',checks:['wizard defaults and bounds','CSRF on all mutations','one-time code removed on close','no token in storage/state/commands','versioned generic metadata, download and extraction commands','code revocation confirmation','independent host settings','host revocation cancel and confirm','install events and separate first-contact/assessment timestamps','missing pre-registration events remain unknown','server error display','desktop1274x862','mobile390x844','no JavaScript errors'],screenshots:fs.readdirSync(out).filter(f=>f.endsWith('.png'))};
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
 }finally{await browser.close();await fixture.close();}
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
