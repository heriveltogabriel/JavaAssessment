const assert=require('node:assert/strict');
const dash=require('../web/dashboard.js');
const d={hosts:[{id:'a',hostname:'alpha'},{id:'b',hostname:'<img src=x onerror=alert(1)>'}],runtimes:[{id:'r1',hostId:'a',version:'1.6.0_45'},{id:'r2',hostId:'b',version:'1.6.0_31'},{id:'r3',hostId:'b',version:'21.0.12'},{id:'r4',hostId:'b',version:null}],servers:[{id:'s1',hostId:'a',state:'RUNNING'},{id:'s2',hostId:'a',path:'/weblogic.jar',state:null}],jobs:[],coverage:{status:'partial'},serverTime:'2026-10-09T18:00:00Z'};
assert.equal(dash.groups(d).find(g=>g.name==='Java 6').hostCount,2);
assert.equal(dash.groups(d).find(g=>g.name==='Java 6').rows.length,2);
assert.equal(dash.groups(d).find(g=>g.name==='Java 21').rows.length,1);
assert.equal(dash.groups(d).find(g=>g.name==='Versão desconhecida').rows.length,1);
assert.equal(dash.groups(d,'exact').length,4);
assert.equal(dash.reportedServers(d).length,1);
assert.equal(dash.scoped(d,'a').runtimes.length,1);
assert.equal(dash.scoped(d,'b').servers.length,0);
assert.ok(!dash.overview(d).includes('<img src=x'));
assert.ok(dash.overview(d).includes('&lt;img src=x'));
assert.ok(dash.versions(d,'family','Java 21').includes('21.0.12'));
assert.ok(!dash.versions(d,'family','Java 21').includes('1.6.0_45'));
assert.ok(dash.machine(d,'missing').includes('não está no inventário'));
assert.ok(dash.overview({}).includes('Execute a primeira coleta'));
const liveHost={id:'live',hostname:'instance-ol8',lastSeen:'2026-10-09T20:00:00Z',lastAssessmentAt:'2026-10-09T19:00:00Z',coverage:{status:'partial',warnings:['Skipped protected path']}};
const at=serverTime=>dash.collectorPresence({serverTime},liveHost);
assert.equal(at('2026-10-09T20:01:00Z').state,'recent');
assert.equal(at('2026-10-09T20:03:00Z').state,'delayed');
assert.equal(at('2026-10-09T20:05:00Z').state,'silent');
assert.match(at('2026-10-09T20:05:00Z').label,/Sem contato com o agente/);
assert.equal(dash.collectorPresence({serverTime:'2026-10-09T20:06:00Z'},{...liveHost,lastSeen:null}).state,'awaiting');
assert.equal(dash.collectorPresence({serverTime:'2026-10-09T20:06:00Z'},{...liveHost,credentialStatus:'revoked'}).state,'revoked');
const silentData={...d,hosts:[liveHost],serverTime:'2026-10-09T20:07:00Z'};
assert.match(dash.machine(silentData,'live'),/Sem contato com o agente · 7 min atrás/);
assert.match(dash.machine(silentData,'live'),/O inventário abaixo foi coletado/);
assert.match(dash.machine(silentData,'live'),/A falta de contato não confirma que a máquina está desligada/);
assert.match(dash.machine(silentData,'live'),/Observações de cobertura ↗/);
assert.match(dash.machines(silentData),/machine-card silent/);
assert.equal(dash.assessmentStatus('PARTIAL',true),'Coleta disponível');
assert.equal(dash.assessmentStatus('PARTIAL'),'Concluído');
console.log('PASS: version groups, unique hosts, runtime vs installation counts, scoping, unknown versions, HTML escaping, empty state');

const applications={...d,hosts:[{id:'a',hostname:'spring-host'},{id:'b',hostname:'tomcat-host'}],runtimes:[],servers:[{id:'tomcat',hostId:'b',name:'tomcat',type:'Tomcat',version:'9.0.122',state:null}],applications:[
  {id:'boot',hostId:'a',name:'demo <script>bad()</script>',type:'Spring Boot',applicationType:'Spring Boot',version:'1.0.0',frameworkVersion:'4.1.1',state:'PROCESS_RUNNING',path:'/opt/demo/app.jar',observedAt:'2026-10-09T19:00:00Z'},
  {id:'war',hostId:'b',name:'legacy-war',type:'WAR',state:null,path:'/opt/tomcat/webapps/legacy.war'},
  {id:'evil',hostId:'b',name:'test',type:'<img src=x onerror=1>',state:null}
],libraries:[
  {id:'h2',hostId:'a',name:'H2 Database Engine',version:'2.4.240',category:'JDBC',usage:'found',path:'/opt/demo/app.jar!/BOOT-INF/lib/h2.jar',applicationId:'boot',applicationName:'demo <script>bad()</script>'},
  {id:'pool',hostId:'a',name:'HikariCP',version:'7.0.2',category:'Java library',usage:'found',applicationId:'boot'},
  {id:'mysql',hostId:'b',name:'other-host-driver',version:'5.1.49',category:'JDBC',usage:'found',path:'/opt/tomcat/lib/mysql.jar',applicationId:'war'}
],datasources:[]};
const bootScope=dash.scoped(applications,'a'),bootHtml=dash.machine(applications,'a'),foundHtml=dash.machine(applications,'b'),cards=dash.machines(applications);
assert.equal(dash.observation(applications.applications[0]).label,'Processo observado');
assert.equal(dash.observation(applications.applications[1]).label,'Encontrado em arquivos');
assert.equal(dash.observation({state:'RUNNING'}).label,'Informado em execução');
assert.equal(dash.libraryObservation(applications.libraries[0]),'Encontrada no pacote');
assert.equal(dash.libraryObservation(applications.libraries[2]),'Encontrado em arquivos');
assert.deepEqual(dash.jdbcLibraries(bootScope).map(l=>l.id),['h2']);
assert.deepEqual(bootScope.applications.map(a=>a.id),['boot']);
assert.equal(dash.reportedServers(applications).length,0,'File-only Tomcat is not a runtime server');
assert.equal(dash.workloads(bootScope)[0].type,'Spring Boot');
assert.match(cards,/Spring Boot <span>· process/);
assert.match(cards,/Tomcat <span>· arquivos/);
assert.ok(!cards.includes('<img src=x'));
assert.ok(cards.includes('&lt;img src=x'));
assert.match(bootHtml,/host-applications/);
assert.match(bootHtml,/Versão do framework/);
assert.match(bootHtml,/4\.1\.1/);
assert.match(bootHtml,/1\.0\.0/);
assert.match(bootHtml,/Processo observado/);
assert.match(bootHtml,/host-jdbc-libraries/);
assert.match(bootHtml,/2\.4\.240/);
assert.match(bootHtml,/data-detail-kind="applications" data-detail-id="boot"/);
assert.match(bootHtml,/Encontrada no pacote/);
assert.match(bootHtml,/não confirma um datasource configurado/);
assert.match(bootHtml,/Nenhuma observação de datasource JDBC informada/);
assert.ok(!bootHtml.includes('other-host-driver'));
assert.ok(!bootHtml.includes('legacy-war'));
assert.ok(!bootHtml.includes('HikariCP'),'Connection pool library is not a Driver JDBC');
assert.ok(!bootHtml.includes('<script>'));
assert.match(bootHtml,/demo &lt;script&gt;bad\(\)&lt;\/script&gt;/);
assert.match(foundHtml,/9\.0\.122/);
assert.match(foundHtml,/Encontrado em arquivos/);
assert.ok(!foundHtml.includes('Processo observado'));
assert.ok(!foundHtml.includes('H2 Banco de dados Engine'));
console.log('PASS: application/framework versions, process vs file evidence, found server counts, JDBC package association, no datasource inference, host scope and escaping');

const shared={...applications,applications:[...applications.applications,{id:'boot2',hostId:'a',name:'second-app',type:'Spring Boot',state:'PROCESS_RUNNING'}],libraries:[{id:'shared',hostId:'a',name:'Shared JDBC',category:'JDBC',usage:'found',path:'/lib/driver.jar',associations:[{applicationId:'boot',applicationName:'untrusted label',pid:101},{applicationId:'boot2',applicationName:'second-app',pid:202}]}]};
assert.deepEqual(dash.associations(shared,shared.libraries[0]).map(a=>[a.application.id,a.pid]),[['boot',101],['boot2',202]]);
const sharedHtml=dash.machine(shared,'a');
assert.match(sharedHtml,/data-detail-id="boot"/);
assert.match(sharedHtml,/data-detail-id="boot2"/);
assert.match(sharedHtml,/PID 101/);
assert.match(sharedHtml,/PID 202/);
assert.ok(!sharedHtml.includes('untrusted label'),'Use the matching in-scope application name');
const crossHost=dash.associations(shared,{hostId:'a',associations:[{applicationId:'war',applicationName:'Unresolved association'}]})[0];
assert.equal(crossHost.application,undefined,'Do not link an ID belonging to a different host');
assert.equal(crossHost.name,'Unresolved association');
assert.equal(dash.workloads({servers:[{type:'Tomcat',state:null}],applications:[{type:'Tomcat',state:'PROCESS_RUNNING'}]}).length,1,'One type badge prefers process evidence over installation-only evidence');
console.log('PASS: multiple application/process associations, host guards and one evidence badge per workload type');

const configuredDatasource={id:'spring-ds',hostId:'a',name:'Spring datasource',state:'CONFIGURED',evidenceLevel:'configuration',driverName:'H2',driverClass:'org.h2.Driver',driverVersion:'2.4.240',configurationSource:'/opt/app.jar!/BOOT-INF/classes/application.properties <untrusted>',configuredMaxPoolSize:10,evidence:'Configuration found; runtime connection not verified',associations:[{applicationId:'boot',applicationName:'demo',pid:101},{applicationId:'boot2',applicationName:'second-app',pid:202}]};
const runtimeDatasource={id:'wls-ds',hostId:'b',name:'Runtime datasource',serverName:'AdminServer',state:'Running',driverName:'Derby',driverVersion:'10.12',poolCapacity:5,activeConnections:2,evidence:'Existing WebLogic JMX snapshot /lab/snapshot.json; original observation time preserved'};
const datasourceData={...shared,datasources:[configuredDatasource,runtimeDatasource]},snapshotBefore=JSON.stringify(datasourceData);
assert.equal(dash.datasourceObservation(configuredDatasource).label,'Configuração encontrada');
assert.equal(dash.datasourceObservation({state:'CONFIGURED',evidence:'runtime not verified'}).kind,'configuration');
assert.equal(dash.datasourceObservation(runtimeDatasource).label,'Informado em execução');
assert.equal(dash.datasourceObservation({state:'PROCESS_RUNNING',evidence:'Java process found'}).kind,'unknown','A Processo Java does not prove a datasource observation');
assert.equal(dash.datasourceObservation({evidence:'Runtime connection not confirmed'}).kind,'unknown','Negated evidence cannot confirm runtime use');
assert.deepEqual(dash.datasourceSummary(datasourceData),{configured:1,runtime:1,applicationReported:0,unknown:0,label:'1 configurados · 1 observações em execução'});
const springDatasourceHtml=dash.machine(datasourceData,'a'),runtimeDatasourceHtml=dash.machine(datasourceData,'b');
assert.match(springDatasourceHtml,/Datasources JDBC/);
assert.match(springDatasourceHtml,/1 configurados · 0 observações em execução/);
assert.match(springDatasourceHtml,/Configuração encontrada/);
assert.match(springDatasourceHtml,/Tamanho máximo configurado do pool: 10/);
assert.match(springDatasourceHtml,/Driver empacotado/);
assert.match(springDatasourceHtml,/perfil efetivo, uma conexão ativa ou o estado do pool em execução/);
assert.ok(!springDatasourceHtml.includes('wls-ds'));
assert.ok(!springDatasourceHtml.includes('<untrusted>'));
assert.ok(springDatasourceHtml.includes('&lt;untrusted&gt;'));
assert.match(runtimeDatasourceHtml,/0 configurados · 1 observações em execução/);
assert.match(runtimeDatasourceHtml,/Informado em execução/);
assert.ok(!runtimeDatasourceHtml.includes('spring-ds'));
assert.deepEqual(dash.associations(datasourceData,configuredDatasource).map(a=>a.application.id),['boot','boot2']);
assert.equal(dash.scoped(datasourceData,'a').datasources.length,1,'Multiple processes sharing one datasource configuration remain one record');
assert.equal(JSON.stringify(datasourceData),snapshotBefore,'Evidência display must not rewrite raw datasource records');
console.log('PASS: configured vs runtime datasources, no connection inference, shared process association, scoped counts, packaged driver/source and pool labeling');

const landscapeFixture={hosts:[{id:'one',hostname:'<img src=x onerror=1>'},{id:'two',hostname:'two'}],servers:[{type:'Tomcat',version:'9.0.122',hostId:'one',state:'PROCESS_RUNNING'},{type:'Tomcat',version:'9.0.122',hostId:'two',state:'RUNNING'},{type:'WebLogic',version:null,hostId:'one',state:'PROCESS_RUNNING'},{type:'FilesOnly',version:'1',hostId:'one'}],datasources:[{name:'OrdersDS',driverVersion:'42.7.8',hostId:'one'},{name:'OrdersDS',driverVersion:'42.7.8',hostId:'two'},{name:'<script>unsafe</script>',driverVersion:null,hostId:'one'}]};
assert.equal(dash.productGroups(landscapeFixture,'servers')[0].count,2);
assert.equal(dash.productGroups(landscapeFixture,'servers')[0].hostCount,2);
assert.equal(dash.productGroups(landscapeFixture,'servers').length,2);
assert.equal(dash.productGroups(landscapeFixture,'datasources')[0].name,'OrdersDS');
assert.equal(dash.productGroups(landscapeFixture,'datasources')[0].version,'42.7.8');
const compact= dash.compactGroups(Array.from({length:8},(_,i)=>({name:'Group '+i,count:10})),80);
assert.equal(compact.length,4);assert.equal(compact[3].count,50);assert.equal(compact.reduce((n,g)=>n+g.count,0),80);
const compactHtml=dash.overview(landscapeFixture);
assert.equal((compactHtml.match(/data-landscape=/g)||[]).length,4);
assert.match(compactHtml,/Driver: Não informada/);assert.ok(!compactHtml.includes('<script>unsafe'));
assert.match(compactHtml,/overview-machine-table/);assert.ok(!compactHtml.includes('class="machine-card'));
console.log('PASS: compact server and datasource-version groups, unknown metadata, remainder totals, four panels and escaped host table.');

// Network summary is a view only; local addresses remain in the original inventory.
const ips=['fe80:0:0:0:0:17ff:fe00:920%ens3','10.0.0.63','0:0:0:0:0:0:0:1%lo','127.0.0.1'];
assert.deepEqual(dash.networkAddresses(ips),['10.0.0.63']);assert.equal(ips.length,4);
assert.deepEqual(dash.networkAddresses(['2001:db8::1','10.1.2.3','10.1.2.3','2001:0db8:0:0:0:0:0:1']),['10.1.2.3','2001:db8::1']);
assert.deepEqual(dash.networkAddresses(['::1','127.2.3.4','fe90::1%eth0','FEAF::1','febf::1','::','0.0.0.0','::ffff:127.0.0.1']),[]);
assert.deepEqual(dash.networkAddresses(['fec0::1','fd12::1','2001:db8::2']),['fec0::1','fd12::1','2001:db8::2']);
assert.deepEqual(dash.networkAddresses(['bad','999.1.2.3','<img src=x>','fe80:::1',null]),[]);
assert.deepEqual(dash.networkAddresses(null),[]);
assert.match(dash.machineIpCell({id:'h',ipAddresses:['::1']}),/unknown/);
assert.doesNotMatch(dash.machineIpCell({id:'h',ipAddresses:ips}),/fe80|127\.0|machine-ip-more/);
assert.match(dash.machineIpCell({id:'h',hostname:'a<bad>',ipAddresses:['2001:db8::1','10.0.0.1','10.0.0.2']}),/\+2<\/button>/);
assert.doesNotMatch(dash.machineIpCell({id:'h',hostname:'a<bad>',ipAddresses:['10.0.0.1','10.0.0.2']}),/<bad>/);
console.log('Machine IP presentation PASS: IPv4 priority, IPv6-only, loopback/link-local, duplicate normalization, empty and escaped details.');

const reportedDs={id:'reported',hostId:'a',name:'Orders <img src=x>',state:'APPLICATION_REPORTED',evidenceLevel:'application-reported',databaseType:'PostgreSQL',databaseVersion:'16.4',databaseVersionEvidence:'application-reported',driverVersion:'42.7'};
assert.equal(dash.datasourceObservation(reportedDs).label,'Informado pela aplicação');
assert.equal(dash.datasourceSummary({datasources:[reportedDs]}).applicationReported,1);
assert.equal(dash.datasourceSummary({datasources:[reportedDs]}).runtime,0);
const reportedHtml=dash.machine({...applications,datasources:[reportedDs]},'a');
assert.match(reportedHtml,/Versão do driver/);assert.match(reportedHtml,/Banco \/ versão/);assert.match(reportedHtml,/16\.4/);assert.match(reportedHtml,/42\.7/);assert.match(reportedHtml,/Informado pela aplicação/);assert.ok(!reportedHtml.includes('<img src=x>'));
console.log('PASS: application-reported JDBC evidence, separate driver/database versions and HTML escaping');
