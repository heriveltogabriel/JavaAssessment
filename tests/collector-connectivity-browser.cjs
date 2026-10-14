/* A missing heartbeat changes the visible connection state without altering inventory. */
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {startFixture}=require('./fixtures/environments-fixture.cjs');

(async()=>{
  const root=path.resolve(__dirname,'..'),out=path.join(__dirname,'fixtures/collector-connectivity-output');
  fs.mkdirSync(out,{recursive:true});
  const fixture=await startFixture(path.join(root,'web'));
  const host=fixture.state.hosts.find(h=>h.id==='host-b');
  fixture.state.serverTime='2026-10-09T20:00:00Z';
  host.lastSeen='2026-10-09T19:53:00Z';
  host.lastAssessmentAt='2026-10-09T18:00:00Z';
  let browser;
  try{
    browser=await chromium.launch({channel:process.env.BROWSER_CHANNEL || 'chrome',headless:true});
    const context=await browser.newContext({viewport:{width:1280,height:900},serviceWorkers:'block'}),errors=[];
    const page=await context.newPage();
    page.on('pageerror',error=>errors.push(error.message));
    await page.goto(fixture.base+'/#overview?env=all');
    await page.locator('#login-layer').waitFor({state:'hidden'});
    const card=page.locator('.overview-machine-row.silent').filter({has:page.locator('[data-machine="host-b"]')});
    await card.waitFor();
    assert.match(await card.innerText(),/No collector contact · 7 min ago/);
    await page.screenshot({path:path.join(out,'01-overview-stale-machine.png'),fullPage:true});

    await card.locator('[data-machine="host-b"]').first().click();
    await page.locator('.collector-notice.silent').waitFor();
    assert.match(await page.locator('.collector-notice').innerText(),/Last check-in:/);
    assert.match(await page.locator('.collector-notice').innerText(),/Inventory shown below was collected/);
    assert.match(await page.locator('.collector-notice').innerText(),/does not establish that the VM is powered off/);
    assert.equal(await page.locator('.machine-hero [data-run]').innerText(),'Queue assessment');
    assert.equal(await page.locator('#fleet-status').innerText(),'Assessment ready');
    await page.locator('.coverage-chip').click();
    assert.match(await page.locator('#drawer-content').innerText(),/Dev path permission note/);
    await page.locator('#close-drawer').click();
    await page.screenshot({path:path.join(out,'02-machine-no-contact.png'),fullPage:true});

    host.lastSeen='2026-10-09T20:00:00Z';
    await page.locator('#refresh').click();
    await page.locator('.machine-hero .presence.recent').waitFor();
    assert.equal(await page.locator('.collector-notice').count(),0);
    assert.equal(await page.locator('.machine-hero [data-run]').innerText(),'Run assessment');
    fixture.state.serverTime='2026-10-09T20:03:00Z';
    await page.locator('#refresh').click();
    await page.locator('.collector-notice.delayed').waitFor();
    fixture.state.serverTime='2026-10-09T20:06:00Z';
    await page.locator('#refresh').click();
    await page.locator('.collector-notice.silent').waitFor();
    await page.locator('#tabs [data-tab="hosts"]').click();
    assert.match(await page.locator('#inventory-table thead').innerText(),/Collector contact/);
    assert.match(await page.locator('#inventory-table tbody .presence.silent').innerText(),/No collector contact/);

    await page.setViewportSize({width:390,height:844});
    await page.locator('#tabs [data-tab="overview"]').click();
    await page.locator('.collector-notice.silent').waitFor();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.screenshot({path:path.join(out,'03-machine-no-contact-mobile.png'),fullPage:true});
    assert.deepEqual(errors,[]);
    assert.ok(fixture.requests.every(request=>request.method==='GET'));
    console.log('PASS: stale collector visible in overview, machine and inventory; recovers on heartbeat; delayed and no-contact thresholds; coverage notes retained; mobile layout; no API mutation');
  }finally{if(browser)await browser.close();await fixture.close();}
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
