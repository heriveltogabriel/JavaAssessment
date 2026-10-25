'use strict';
// Real browser, synthetic enrollment API; never contacts a dashboard.
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:900}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.setContent('<html lang="pt-BR"><body><div id="actions-menu"></div><button data-enrollment-list>Códigos de cadastro</button></body></html>');
  for(const name of ['styles.css','enrollment.css'])await page.addStyleTag({path:path.resolve('web',name)});
  await page.evaluate(()=>{window.AssessmentLocale={status:v=>v};window.fixtureCalls=[];});
  await page.addScriptTag({path:path.resolve('web/enrollment.js')});
  await page.evaluate(()=>AssessmentEnrollment.init({isAdmin:()=>true,getData:()=>({}),getEnrollmentData:()=>({scope:{environmentId:'fixture-env',name:'Teste'}}),api:async url=>{
   window.fixtureCalls.push(url);const p=Number(new URL(url,'https://fixture.invalid').searchParams.get('page'));
   return {page:p,total:601,enrollments:Array.from({length:p===7?1:100},(_,i)=>({id:'code-'+((p-1)*100+i),label:'Código '+((p-1)*100+i),status:'active',usedCount:0,maxUses:100000,expiresAt:'2030-01-01T00:00:00Z'}))};
  }}));
  await page.locator('[data-enrollment-list]').click();await page.getByText('Página 1 de 7 · 601 códigos').waitFor();
  assert.equal(await page.getByRole('button',{name:'Anterior',exact:true}).isDisabled(),true);
  for(let n=2;n<=7;n++){await page.getByRole('button',{name:'Próxima',exact:true}).click();await page.getByText('Página '+n+' de 7 · 601 códigos').waitFor();}
  assert.equal(await page.locator('[data-code-revoke="code-600"]').count(),1);
  assert.equal(await page.getByRole('button',{name:'Próxima',exact:true}).isDisabled(),true);
  await page.locator('[data-code-revoke="code-600"]').click();await page.getByRole('heading',{name:'Código 600',exact:true}).waitFor();
  assert.equal(await page.locator('#enrollment-form').getAttribute('data-target-id'),'code-600');
  await page.getByRole('button',{name:'Fechar',exact:true}).click();await page.setViewportSize({width:390,height:844});
  await page.locator('[data-enrollment-list]').click();await page.getByText('Página 1 de 7 · 601 códigos').waitFor();
  assert.equal(await page.locator('[data-code-revoke]').count(),100);
  const out=path.resolve('tmp/enrollment-capacity-browser');fs.mkdirSync(out,{recursive:true});await page.screenshot({path:path.join(out,'mobile.png'),fullPage:true});
  assert.deepEqual(errors,[]);assert.ok((await page.evaluate(()=>window.fixtureCalls)).every(u=>u.includes('environmentId=fixture-env')));
  console.log('ENROLLMENT_CAPACITY_BROWSER=PASS desktop/mobile, 7 pages, revoke code 601, environment scope, no JS errors; synthetic API');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
