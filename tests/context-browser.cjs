/* Read-only UI regression checks against isolated loopback inventory fixtures. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { startFixture } = require('./fixtures/environments-fixture.cjs');

async function contextFixture(webRoot) {
  const fixture = await startFixture(webRoot), state = fixture.state;
  state.environments.push({ id:'env-empty', name:'Empty environment', description:'No registered machines', settings:{}, revision:1 });
  state.hosts[0].hostname='alpha-node';
  state.hosts[0].settingsOverrides={includePaths:['/srv/alpha-java'],maxFiles:11001};
  state.hosts[1].hostname='gamma-node';
  state.hosts[2].hostname='delta-node';
  state.hosts[2].settingsOverrides={includePaths:['/srv/delta-java'],maxFiles:44004};
  state.hosts.push({ ...state.hosts[0], id:'host-d', hostname:'beta-node', settingsOverrides:{includePaths:['/srv/beta-java'],maxFiles:33003},
    coverage:{status:'partial',warnings:['Beta archive unreadable'],lastAssessmentAt:'2026-10-09T19:00:00Z',stats:{filesVisited:333,durationMillis:3000}} });
  state.runtimes.push({id:'r-d',hostId:'host-d',version:'11.0.31',javaHome:'/srv/beta-java/jdk11'});
  for (const kind of ['servers','applications','libraries','datasources','processes','jobs']) {
    state[kind]=state.hosts.map((host,index)=>({id:kind+'-'+host.id,hostId:host.id,name:kind+' '+host.hostname,
      type:kind==='servers'?'WebLogic':'Fixture',version:'1.'+index,state:'RUNNING',status:'SUCCEEDED',
      observedAt:'2026-10-09T19:00:00Z',createdAt:'2026-10-09T19:00:00Z',
      driverName:kind==='datasources'?'JDBC '+host.hostname:undefined,driverVersion:kind==='datasources'?'4.'+index:undefined,
      summary:'Assessment of '+host.hostname}));
  }
  return fixture;
}

(async()=>{
  const root=path.resolve(__dirname,'..'), out=path.join(__dirname,'fixtures/context-output'), fixture=await contextFixture(path.join(root,'web'));
  fs.mkdirSync(out,{recursive:true});
  let browser;
  try {
    browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL || 'chrome',headless:true});
    const context=await browser.newContext({viewport:{width:1274,height:862}});
    await context.route('**/*',route=>new URL(route.request().url()).origin===fixture.base?route.continue():route.abort());
    const page=await context.newPage(), errors=[], completed=[];
    page.setDefaultTimeout(10000);page.on('pageerror',error=>errors.push(error.message));
    const capture=async name=>{await page.locator('#toast').waitFor({state:'hidden'});await page.screenshot({path:path.join(out,name),fullPage:true});};
    await page.goto(fixture.base);
    await page.locator('#login-layer').waitFor({state:'hidden'});
    await page.locator('.overview-machine-row').first().waitFor();
    const state=fixture.state;
    const tab=async view=>{await page.locator('#tabs [data-tab="'+view+'"]').click();};
    const rootTab=async view=>{
      if(!await page.locator('#side-nav').isVisible())await page.locator('#nav-toggle').click();
      await page.locator('#side-nav [data-tab="'+view+'"]').click();
    };
    const environment=async id=>page.locator('#environment-filter').selectOption(id);
    const machineIds=scope=>scope==='env-a'?['host-a','host-d']:scope==='env-b'?['host-b']:scope==='__unassigned__'?['host-c']:scope==='env-empty'?[]:state.hosts.map(h=>h.id);
    const expectedRows=(kind,ids)=>state[kind].filter(row=>ids.includes(row.hostId));
    async function exportScope(ids,label,machineScope) {
      await page.locator('#actions-toggle').click();
      const [download]=await Promise.all([page.waitForEvent('download'),page.locator('[data-export-scope]').click()]);
      const exported=JSON.parse(fs.readFileSync(await download.path(),'utf8'));
      assert.deepEqual(exported.hosts.map(h=>h.id).sort(),[...ids].sort(),label+' exported hosts');
      for(const kind of ['runtimes','applications','servers','libraries','datasources','processes','jobs'])
        assert.deepEqual(exported[kind].map(r=>r.id).sort(),expectedRows(kind,ids).map(r=>r.id).sort(),label+' exported '+kind);
      if(ids.length===1){
        const h=state.hosts.find(h=>h.id===ids[0]);
        if(machineScope){
          assert.deepEqual(exported.settings,h.settings,label+' exported effective host settings');
          assert.equal(exported.scope.machineId,h.id,label+' exported machine context');
          assert.deepEqual(exported.enrollments,[],label+' does not misattribute shared enrollment codes');
        }
        assert.equal(exported.coverage.status,h.coverage.status,label+' exported host coverage');
        assert.deepEqual(exported.coverage.stats,h.coverage.stats,label+' exported host collection stats');
      }
      return exported;
    }
    async function assertInventory(ids,label,machineScope=false) {
      for(const kind of ['runtimes','applications','servers','libraries','jobs']) {
        await tab(kind);
        const count=expectedRows(kind,ids).length+(kind==='libraries'?expectedRows('datasources',ids).length:0);
        assert.equal(await page.locator('#inventory-table tbody tr').count(),count,label+' '+kind+' count');
        const text=await page.locator('#inventory-table').innerText();
        for(const host of state.hosts) {
          if(ids.includes(host.id)&&count)assert.ok(text.includes(host.hostname),label+' '+kind+' includes '+host.hostname);
          else assert.ok(!text.includes(host.hostname),label+' '+kind+' excludes '+host.hostname);
        }
        if(kind==='libraries')assert.equal(await page.locator('#inventory-table tbody tr').filter({hasText:'JDBC datasource'}).count(),ids.length,label+' JDBC rows');
      }
      await tab('versions');
      assert.equal(await page.locator('.version-groups tbody tr').count(),expectedRows('runtimes',ids).length,label+' version rows');
      const versions=await page.locator('.version-groups').innerText();
      for(const runtime of state.runtimes)assert.equal(versions.includes(runtime.version),ids.includes(runtime.hostId),label+' version '+runtime.version);
      await tab('schedule');
      assert.equal(await page.locator('.environment-schedules tbody tr').count(),ids.length,label+' schedules');
      const scheduleText=await page.locator('.environment-schedules').innerText();
      for(const host of state.hosts)assert.equal(scheduleText.includes(host.hostname),ids.includes(host.id),label+' schedule '+host.hostname);
      await tab('properties');
      assert.deepEqual(await page.locator('.overview-total strong').allTextContents(),[
        String(ids.length),String(expectedRows('runtimes',ids).length),String(expectedRows('servers',ids).length),String(expectedRows('libraries',ids).length)
      ],label+' property totals');
      if(machineScope){
        const h=state.hosts.find(h=>h.id===ids[0]),properties=await page.locator('#special-view').innerText();
        for(const includePath of h.settings.includePaths)assert.ok(properties.includes(includePath),label+' effective collection path');
      }
      await page.locator('.property-card [data-coverage]').click();
      const coverage=await page.locator('#drawer-content').innerText();
      for(const h of state.hosts)for(const warning of h.coverage.warnings || [])assert.equal(coverage.includes(warning),ids.includes(h.id),label+' coverage '+warning);
      if(ids.length===1){
        const stats=state.hosts.find(h=>h.id===ids[0]).coverage.stats;
        if(stats.filesVisited!=null)assert.ok(coverage.includes(String(stats.filesVisited)),label+' collection files');
      }
      await page.locator('#close-drawer').click();
      await exportScope(ids,label,machineScope);
      completed.push(label);
    }

    // The root surfaces expose summary navigation, not context-specific tabs.
    assert.equal(await page.locator('#tabs').isVisible(),false,'root overview hides tabs');
    assert.equal(await page.locator('.os-distribution-panel .ring-chart strong').innerText(),'4','OS counts hosts, not five Java installations');
    await capture('01-root-overview-desktop.png');
    assert.deepEqual(await page.locator('#side-nav [data-tab]').evaluateAll(nodes=>nodes.map(n=>n.dataset.tab)),['overview','environments']);
    await rootTab('environments');assert.equal(await page.locator('#tabs').isVisible(),false);
    assert.equal(await page.locator('.environment-card').count(),3);
    await environment('env-a');assert.equal(await page.locator('.environment-card').count(),1,'environment list respects combo');
    assert.ok((await page.locator('.environment-card').innerText()).includes('Production'));
    await page.locator('[data-select-environment="env-a"]').click();
    assert.equal(await page.locator('#tabs').isVisible(),true,'explicit environment entry shows tabs');
    assert.equal(await page.locator('.overview-machine-row').count(),2,'same-environment hosts make leaks detectable');
    assert.equal(await page.locator('.os-distribution-panel .ring-chart strong').innerText(),'2','OS distribution follows environment');
    assert.ok(!(await page.locator('.os-distribution-panel').innerText()).includes('Oracle Linux Server 8.10'));
    await capture('02-environment-context-desktop.png');
    await assertInventory(machineIds('env-a'),'Production environment');
    // Shared enrollment credentials are managed from the environment context.
    await page.locator('#actions-toggle').click();await page.locator('#actions-menu [data-enrollment-list]').click();
    assert.ok((await page.locator('#enrollment-title').innerText()).includes('Production'));
    assert.equal(await page.locator('.enrollment-table tbody tr').count(),1);
    assert.ok((await page.locator('#enrollment-content').innerText()).includes('Production bootstrap'));
    assert.ok(!(await page.locator('#enrollment-content').innerText()).includes('Development bootstrap'));
    assert.equal(await page.locator('#actions-toggle').getAttribute('aria-expanded'),'false');
    await page.locator('[data-enroll-close]').first().click();

    // Every upper tab, settings summary and export must retain the individual machine.
    await tab('overview');await page.locator('.machine-title[data-machine="host-a"]').click();
    assert.ok(page.url().includes('#machine/host-a'));
    await tab('applications');await capture('03-machine-applications-desktop.png');
    await page.locator('#actions-toggle').click();
    assert.equal(await page.locator('#actions-menu [data-enrollment-list]').isVisible(),false,'machine hides shared enrollment credentials');
    assert.equal(await page.locator('[data-add-machine]').count(),0,'Add machine shortcut removed');
    await page.keyboard.press('Escape');
    await assertInventory(['host-a'],'Alpha machine',true);
    await tab('runtimes');
    assert.equal(await page.locator('#host-filter').inputValue(),'host-a');
    assert.ok((await page.locator('#host-filter option').allTextContents()).includes('beta-node'),'machine dropdown includes sibling');
    await page.locator('#host-filter').selectOption('host-d');
    assert.ok(page.url().includes('#machine/host-d'));
    await assertInventory(['host-d'],'Beta sibling machine',true);

    // Clearing the machine promotes the same current view to its environment.
    await tab('runtimes');await page.locator('#host-filter').selectOption('');
    assert.ok(page.url().includes('#environment/runtimes'));
    assert.equal(await page.locator('#inventory-table tbody tr').count(),3);
    await page.locator('#host-filter').selectOption('host-a');
    const alphaUrl=page.url();
    await environment('env-b');
    const gammaUrl=page.url();
    assert.equal(await page.locator('#host-filter').inputValue(),'','environment change clears previous host');
    assert.equal(await page.locator('#inventory-table tbody tr').count(),1);
    assert.ok((await page.locator('#inventory-table').innerText()).includes('21.0.12'));
    await page.goBack();assert.equal(page.url(),alphaUrl);assert.equal(await page.locator('#host-filter').inputValue(),'host-a');
    assert.equal(await page.locator('#environment-filter').inputValue(),'env-a');
    assert.equal(await page.locator('#inventory-table tbody tr').count(),2);
    await page.reload();await page.locator('#login-layer').waitFor({state:'hidden'});await page.locator('#inventory-table tbody tr').first().waitFor();
    assert.equal(await page.locator('#host-filter').inputValue(),'host-a','reload restores machine');
    assert.equal(await page.locator('#environment-filter').inputValue(),'env-a','reload restores environment');
    await page.goForward();assert.equal(page.url(),gammaUrl);assert.equal(await page.locator('#environment-filter').inputValue(),'env-b');
    await assertInventory(machineIds('env-b'),'Development environment');

    // Unassigned and empty environments cannot fall back to the whole fleet.
    await environment('__unassigned__');await assertInventory(['host-c'],'Unassigned environment');
    await environment('env-empty');await assertInventory([],'Empty environment');
    await tab('overview');assert.equal(await page.locator('.overview-machine-row').count(),0);
    await rootTab('environments');assert.equal(await page.locator('#tabs').isVisible(),false);
    assert.equal(await page.locator('.environment-card').count(),1);
    assert.ok((await page.locator('.environment-card').innerText()).includes('Empty environment'));
    await environment('');assert.equal(await page.locator('.environment-card').count(),3);
    await rootTab('overview');assert.equal(await page.locator('#tabs').isVisible(),false);
    await page.locator('#actions-toggle').click();
    assert.equal(await page.locator('#actions-menu [data-add-machine]').count(),0);
    assert.equal(await page.locator('#actions-menu [data-create-environment]').count(),0);
    assert.equal(await page.locator('#actions-menu a[href="/api/collector.zip"]').count(),0);
    await page.keyboard.press('Escape');
    await page.locator('.metric-card[data-inventory="runtimes"]').click();
    assert.equal(await page.locator('#tabs').isVisible(),true,'root metric enters explicit all-environments context');
    assert.equal(await page.locator('#inventory-table tbody tr').count(),5);

    // A copied deep link restores its scope despite a different stored filter.
    const fresh=await context.newPage();await fresh.goto(alphaUrl);await fresh.locator('#login-layer').waitFor({state:'hidden'});
    await fresh.locator('#inventory-table tbody tr').first().waitFor();
    assert.equal(await fresh.locator('#host-filter').inputValue(),'host-a');
    assert.equal(await fresh.locator('#environment-filter').inputValue(),'env-a');
    assert.equal(await fresh.locator('#inventory-table tbody tr').count(),2);await fresh.close();

    // Desktop collapse and the mobile drawer preserve route and accessible focus.
    const desktopUrl=page.url();await page.locator('#nav-toggle').click();
    assert.equal(await page.locator('#side-nav').isVisible(),false);assert.equal(page.url(),desktopUrl);
    await page.locator('#nav-toggle').click();assert.equal(await page.locator('#side-nav').isVisible(),true);
    await page.setViewportSize({width:390,height:844});
    await page.locator('#side-nav').waitFor({state:'hidden'});
    assert.equal(await page.locator('#side-nav').isVisible(),false);
    await page.locator('#nav-toggle').click();
    assert.equal(await page.locator('#nav-toggle').getAttribute('aria-expanded'),'true');
    assert.equal(await page.locator('#main-content').evaluate(el=>el.inert),true);
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#side-nav').isVisible(),false);
    assert.equal(await page.locator('#nav-toggle').evaluate(el=>el===document.activeElement),true);
    await rootTab('environments');assert.equal(await page.locator('#side-nav').isVisible(),false);
    assert.equal(await page.locator('#tabs').isVisible(),false);await environment('env-a');
    await page.locator('[data-select-environment="env-a"]').click();
    await page.locator('.machine-title[data-machine="host-d"]').click();await tab('applications');
    assert.equal(await page.locator('#inventory-table tbody tr').count(),1);
    assert.ok((await page.locator('#inventory-table').innerText()).includes('beta-node'));
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'mobile has no page overflow');
    await capture('04-machine-applications-mobile.png');
    assert.deepEqual(errors,[]);
    assert.ok(fixture.requests.every(request=>request.method==='GET'),'fixture audit makes no API mutations');
    const result={status:'PASS',scopes:completed,checks:['root versus contextual tabs','two machines in one environment','all inventory kinds and JDBC','versions/schedules/properties/coverage/export','environment-only enrollment codes and removed global shortcuts','machine selector and environment changes','history/reload/copied URLs','filtered environment list','unassigned and empty environments','desktop/mobile navigation and focus','no API mutations'],screenshots:fs.readdirSync(out).filter(name=>name.endsWith('.png'))};
    fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
  } finally { if(browser)await browser.close();await fixture.close(); }
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
