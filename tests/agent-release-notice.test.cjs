'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const nodes=new Map();
function element(){return {hidden:true,textContent:'',innerHTML:'',open:false,dataset:{},setAttribute(){},removeAttribute(){},addEventListener(){},querySelector:()=>element(),querySelectorAll:()=>[],replaceChildren(){},reset(){},showModal(){this.open=true;},close(){this.open=false;}};}
const get=id=>{if(!nodes.has(id))nodes.set(id,element());return nodes.get(id);};
let administrator=true;const requests=[],root={};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../web/agent-updates.js'),'utf8'),{window:root,document:{getElementById:get,createElement:element,body:{appendChild(){}}},AbortController,URLSearchParams,clearInterval(){}});
root.AssessmentAgentUpdates.init({isAdmin:()=>administrator,closeMenus(){},api(route,options){requests.push({route,options});return new Promise(()=>{});}});
const notice=root.AssessmentAgentUpdates.renderNotice;
notice({version:'2.0.3',outdatedCount:3});
assert.equal(get('agent-release-notice').hidden,false);
assert.equal(get('agent-release-notice-title').textContent,'Atualize os agentes para a versão 2.0.3');
assert.match(get('agent-release-notice-description').textContent,/3 agentes.*Todos os ambientes/);
notice({version:'2.0.10',outdatedCount:1});assert.match(get('agent-release-notice-title').textContent,/2\.0\.10$/);assert.match(get('agent-release-notice-description').textContent,/1 agente em versão anterior/);
for(const summary of [null,{}, {version:'2.0.3',outdatedCount:0},{version:'2.0.3',outdatedCount:-1},{version:'2.0.3',outdatedCount:1.5},{version:'2.0.3',outdatedCount:'1'},{version:'2.0.3<script>',outdatedCount:1}]){
  notice(summary);assert.equal(get('agent-release-notice').hidden,true);assert.equal(get('agent-release-notice-title').textContent,'');assert.equal(get('agent-release-notice-description').textContent,'');
}
administrator=false;notice({version:'2.0.3',outdatedCount:5});assert.equal(get('agent-release-notice').hidden,true);
administrator=true;notice({version:'2.0.3',outdatedCount:5});assert.equal(requests.length,0,'rendering never schedules updates or fetches the catalog');
get('agent-release-notice-review').onclick();
assert.equal(requests.length,1);assert.equal(requests[0].route,'agent-campaigns/preview?eligibility=eligible&environmentId=&page=1&pageSize=50');assert.equal(requests[0].options.method,undefined,'review only reads global eligibility');
notice(null);assert.equal(get('agent-release-notice').hidden,true,'logout clears the notice');
console.log('PASS: release notice visibility, pluralization, release changes, unknown/invalid data, admin permission, clearing and read-only global review.');
