/* Isolated generic onboarding regression; every request is fulfilled in memory. */
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs=require('node:fs');
const assert=require('node:assert/strict');
const path=require('node:path');
const root=path.resolve(__dirname,'..'),out=path.join(root,'output/versioned-download-csv/evidence');
fs.mkdirSync(out,{recursive:true});
const source=fs.readFileSync(path.join(root,'web/enrollment.js'),'utf8');
(async()=>{
 const browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL || 'chrome',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1274,height:862}});const errors=[];page.on('pageerror',e=>errors.push(e.message));let metadataVersion='1.5.0',downloadVersion='1.5.1',infoFail=false,downloadFail=false,delayInfo=null,issues=0;
  await page.route('**/*',async route=>{
   const path=new URL(route.request().url()).pathname;
   if(path==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><html><body><div id="actions-menu"></div><div id="view-actions"></div><script src="/enrollment.js"></script><script>AssessmentEnrollment.init({api:async(p,o={})=>{const r=await fetch("/api/"+p,{method:o.method||"GET",headers:{"Content-Type":"application/json"},body:o.body?JSON.stringify(o.body):undefined});const b=await r.json();if(!r.ok)throw new Error(b.error);return b;},getData:()=>({hosts:[]}),refresh:async()=>{},notify:()=>{}});</script></body></html>'});
   if(path==='/enrollment.js')return route.fulfill({contentType:'application/javascript',body:source});
   if(path==='/api/collector/info'){if(delayInfo)await delayInfo;return route.fulfill({status:infoFail?500:200,contentType:'application/json',body:JSON.stringify(infoFail?{error:'Package metadata unavailable'}:{version:metadataVersion,filename:'assessment-collector-'+metadataVersion+'.zip'})});}
   if(path==='/api/enrollments'){issues++;return route.fulfill({status:201,contentType:'application/json',body:JSON.stringify({enrollmentToken:'fixture-code',enrollment:{label:'test',expiresAt:'2026-10-14T00:00:00Z',maxUses:1},serverUrl:'https://fixture.invalid'})});}
   if(path==='/api/collector.zip')return route.fulfill({status:downloadFail?500:200,headers:{'Content-Type':downloadFail?'application/json':'application/zip','Content-Disposition':'attachment; filename="assessment-collector-'+downloadVersion+'.zip"'},body:downloadFail?'{}':Buffer.from('504b0506000000000000000000000000000000000000','hex')});
   throw new Error('Unexpected path '+path);
  });
  await page.goto('http://fixture.invalid');
  async function open(){await page.evaluate(()=>AssessmentEnrollment.openWizard());await page.locator('[name="label"]').fill('test');}
  await open();infoFail=true;await page.locator('#enrollment-submit').click();await page.locator('#enrollment-error').waitFor({state:'visible'});assert.equal(issues,0);assert.equal(await page.locator('[name="label"]').inputValue(),'test');
  infoFail=false;await page.locator('#enrollment-submit').click();await page.locator('#enrollment-token').waitFor();assert.equal(issues,1);
  downloadFail=true;await page.locator('[data-enrollment-download]').click();await page.locator('#enrollment-error').waitFor({state:'visible'});assert.equal(issues,1);assert.equal(await page.locator('#enrollment-submit').isEnabled(),true);
  downloadFail=false;const [download]=await Promise.all([page.waitForEvent('download'),page.locator('[data-enrollment-download]').click()]);assert.equal(download.suggestedFilename(),'assessment-collector-1.5.1.zip');
  await page.locator('#enrollment-submit').click();assert.ok((await page.locator('#enrollment-user-command').textContent()).includes('unzip assessment-collector-1.5.1.zip'));assert.ok(!(await page.locator('#enrollment-user-command').textContent()).includes('fixture-code'));
  await page.locator('[data-enroll-close]').first().click();assert.equal(await page.locator('#enrollment-token').count(),0);
  let resume;delayInfo=new Promise(r=>resume=r);await open();await page.locator('#enrollment-submit').click();await page.locator('[data-enroll-close]').first().click();await open();resume();delayInfo=null;await page.waitForTimeout(100);assert.equal(await page.locator('[name="label"]').inputValue(),'test');assert.equal(issues,1);assert.equal(await page.locator('#enrollment-submit').isEnabled(),true);
  await page.locator('#enrollment-submit').click();await page.locator('#enrollment-token').waitFor();assert.equal(issues,2);await page.locator('#enrollment-submit').click();assert.ok((await page.locator('#enrollment-user-command').textContent()).includes('unzip assessment-collector-1.5.0.zip'));
  assert.deepEqual(errors,[]);const result={status:'PASS',environment:'isolated browser fixture; all requests fulfilled in memory; no remote dashboard or agents',checks:['metadata failure before enrollment issuance','failed download retry','download header authority','extraction command synchronization','delayed metadata discarded after close/reopen','no token in commands','no JavaScript errors']};fs.writeFileSync(path.join(out,'versioned-generic-browser.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
