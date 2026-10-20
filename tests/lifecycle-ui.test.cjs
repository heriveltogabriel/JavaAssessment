const assert=require('node:assert/strict');
const lifecycle=require('../web/lifecycle.js');
const dashboard=require('../web/dashboard.js');

// Badge: text + icon (not color alone), escaped data, unknown fallback.
const badge=lifecycle.badge({status:'fora',linha:'<b>Java 6</b>',fimPrincipal:'2015-12-31',fimEstendido:'2018-12-31'});
assert.match(badge,/Fora de suporte/);assert.match(badge,/support-icon/);assert.match(badge,/31\/12\/2018/);
assert.ok(!badge.includes('<b>'),'line name escaped');
assert.match(lifecycle.badge({status:'inventado'}),/Desconhecido/);
assert.match(lifecycle.badge(null),/—/);

// Detail drawer: only https sources become links; reference text shown.
const detail=lifecycle.detail({status:'estendido',linha:'Java 8',fonte:'https://www.oracle.com/java/technologies/java-se-support-roadmap.html',referencia:'Referência: Oracle',dataReferencia:'2026-10-19'});
assert.match(detail,/href="https:\/\/www\.oracle\.com/);assert.match(detail,/rel="noopener noreferrer"/);assert.match(detail,/19\/10\/2026/);assert.match(detail,/Referência: Oracle/);
assert.ok(!lifecycle.detail({status:'fora',fonte:'javascript:alert(1)'}).includes('href='),'unsafe source not linked');

// Route parameters are whitelisted per view.
assert.equal(lifecycle.supportParam('servers','fora'),'fora');assert.equal(lifecycle.supportParam('runtimes','fora'),'fora');
assert.equal(lifecycle.supportParam('servers','qualquer'),'');assert.equal(lifecycle.supportParam('hosts','fora'),'');
assert.ok(lifecycle.subsetAllowed('libraries','driver-unresolved'));assert.ok(!lifecycle.subsetAllowed('servers','driver-unresolved'));
assert.equal(lifecycle.filterOptions('hosts',''),'');assert.match(lifecycle.filterOptions('servers','proximo'),/value="proximo" selected/);

// Risk panel: links keep the environment scope; zero counts are not links.
const data={scope:{environmentId:'prd'},risks:{javaHostsOut:3,serversOut:0,serversEnding:2,springOut:1,driversUnresolved:4,partialHosts:5,runtimesReplaced:1,dataReferencia:'2026-10-19'},summary:{},javaSupport:[{status:'fora',count:3,versions:[{name:'Java 6',count:3}]},{status:'suportado',count:6,versions:[{name:'Java 21',count:6}]}]};
const panel=lifecycle.riskPanel(data);
assert.match(panel,/href="#environment\/runtimes\?env=prd&amp;support=fora"/);
assert.match(panel,/href="#environment\/libraries\?env=prd&amp;subset=driver-unresolved"/);
assert.match(panel,/href="#environment\/hosts\?env=prd&amp;subset=coverage-partial"/);
assert.ok(!panel.includes('servers?env=prd&amp;support=fora'),'zero count is not a link');
assert.match(panel,/outros fornecedores podem ter datas diferentes/);
assert.match(lifecycle.riskPanel({...data,scope:{environmentId:''}}),/env=all/);
assert.equal(lifecycle.riskPanel({}),'');

// Overview ring: one slice per status, out-of-support versions named, never folded into "Outros".
global.AssessmentLifecycle=lifecycle;
const overview=dashboard.overview({...data,scoped:true,summary:{runtimes:9,hosts:2},hosts:[],environments:[],jobs:[]});
delete global.AssessmentLifecycle;
assert.match(overview,/Painel de riscos/);assert.match(overview,/Java 6 \(3\)/);assert.match(overview,/Fora de suporte/);
assert.ok(!/data-landscape="java"[\s\S]*?Outros[\s\S]*?data-landscape="os"/.test(overview),'no Outros slice in Java landscape');
console.log('PASS: lifecycle badges, filters, risk links and Java support ring.');
