'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});const page=await browser.newPage();let phase='IDLE',operation='a'.repeat(32),actions=[],offline=false;
 await page.exposeFunction('fixtureRequest',async(p,o)=>{
  if(offline)throw new Error('connection refused');
  if(p.endsWith('/upload')){phase='PREPARED';return {accepted:true,operationId:operation};}
  if(p.endsWith('/action')){actions.push(o.body.action);phase=o.body.action==='cancel'?'CANCELLED':o.body.action==='rollback'?'ROLLED_BACK':'STARTING';return {accepted:true};}
  return {id:phase==='IDLE'?undefined:operation,phase,installedVersion:phase==='SUCCEEDED'?'2.1.0':'2.0.57',version:phase==='IDLE'?undefined:'2.1.0',notes:'<img src=x onerror=alert(1)>',events:[{at:'2026-10-16T20:00:00Z',message:'Pacote <script> seguro'}],history:[],canRollback:phase==='SUCCEEDED'};
 });
 await page.setContent('<html lang="pt-BR"><body><div id="panel"></div></body></html>');
 await page.evaluate(()=>{window.AssessmentAccess={request:window.fixtureRequest};window.confirm=()=>true;});
 for(const css of ['styles.css','access.css','dashboard-update.css'])await page.addStyleTag({path:path.join(__dirname,'../web/'+css)});
 await page.addScriptTag({path:path.join(__dirname,'../web/dashboard-update.js')});
 await page.evaluate(()=>DashboardUpdate.mount(document.getElementById('panel')));
 await page.waitForFunction(()=>document.getElementById('du-phase').textContent==='Pronto para receber pacote');
 assert.equal(await page.locator('#du-notes img').count(),0);
 await page.locator('#du-file').setInputFiles({name:'update.zip',mimeType:'application/zip',buffer:Buffer.from('fixture')});
 await page.getByRole('button',{name:'Enviar e preparar'}).click();
 await page.waitForFunction(()=>document.getElementById('du-phase').textContent==='Pronto para atualizar');
 await page.getByRole('button',{name:'Atualizar e reiniciar',exact:true}).click();await page.getByRole('alert').filter({hasText:'Informe sua senha'}).waitFor();assert.equal(actions.length,0);
 await page.locator('#du-password').fill('fixture-password');await page.getByRole('button',{name:'Atualizar e reiniciar',exact:true}).click();
 await page.waitForFunction(()=>document.getElementById('du-phase').textContent==='Reiniciando e verificando');assert.deepEqual(actions,['confirm']);assert.equal(await page.locator('#du-password').inputValue(),'');
 offline=true;await page.getByRole('alert').filter({hasText:'Sem resposta'}).waitFor({timeout:10000});offline=false;phase='SUCCEEDED';
 await page.waitForFunction(()=>document.getElementById('du-phase').textContent==='Atualização concluída');
 assert.equal(await page.getByRole('button',{name:'Restaurar versão anterior',exact:true}).count(),1);
 await page.locator('#du-password').fill('fixture-password');await page.getByRole('button',{name:'Restaurar versão anterior',exact:true}).click();await page.waitForFunction(()=>document.getElementById('du-phase').textContent==='Versão anterior restaurada');
 phase='PREPARED';await page.waitForFunction(()=>!document.getElementById('du-cancel').hidden);await page.getByRole('button',{name:'Cancelar preparação'}).click();await page.waitForFunction(()=>document.getElementById('du-phase').textContent==='Preparação cancelada');
 const out=path.join(__dirname,'../output/dashboard-self-update');fs.mkdirSync(out,{recursive:true});await page.screenshot({path:path.join(out,'administration-update.png'),fullPage:true});
 assert.deepEqual(actions,['confirm','rollback','cancel']);await page.evaluate(()=>DashboardUpdate.stop());await browser.close();console.log('Dashboard updater browser passed: upload, reauthentication, restart reconnection, rollback, cancellation, escaping.');
})().catch(e=>{console.error(e);process.exit(1)});
