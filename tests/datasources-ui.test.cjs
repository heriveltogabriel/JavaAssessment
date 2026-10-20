'use strict';
const assert=require('node:assert/strict');
globalThis.AssessmentDashboard=require('../web/dashboard.js');
const ds=require('../web/datasources.js');
// Report cell: summary plus one datasource per line.
const cell=['jdbc/Pedidos — oracle.jdbc.OracleDriver · 19.3.0.0 · Destino configurado: db1:1521 / PEDIDOS · Driver: Identificado em arquivo · Arquivos: /u01/lib/ojdbc8.jar — 19.3.0.0',
  'jdbc/PedidosRO — oracle.jdbc.OracleDriver · Não identificada · Destino configurado: DB1:1521 / pedidos · Driver: Driver não localizado',
  'jdbc/Billing — Não identificada · Não identificada · Destino configurado: Não identificada'].join('\n');
const html=ds.reportCell(cell);
assert.match(html,/<details class="report-jdbc"><summary>3 datasources · 1 banco · 2 drivers não identificados<\/summary>/);
assert.equal((html.match(/<li>/g)||[]).length,3);
assert(html.includes('<strong>jdbc/PedidosRO</strong>'));
assert(!html.includes('<span></span>'),'no empty segments rendered');
assert.equal(ds.summary([ds.parseLine('A — d · 1 · Destino configurado: x:1 · Driver: Identificado em arquivo')]),'1 datasource · 1 banco');
assert.equal(ds.summary([ds.parseLine('R — d · 1 · Destino configurado: Não identificado (serviço ${DB}) · Driver: Múltiplos candidatos')]),'1 datasource · 0 bancos · 1 driver não identificado');
assert.equal(ds.reportCell('Não identificada'),'Não identificada');
assert.equal(ds.reportCell(''),'Não identificado');
assert(!ds.reportCell('<img src=x> — d · Destino configurado: <b>').includes('<img'),'escaped');
// Tab columns and database groups.
const h={link:(k,id,label,sub)=>'['+label+(sub?'|'+sub:'')+']',hostLink:id=>'host:'+id,value:v=>v==null||v===''?'—':String(v),resolutionLabel:s=>({IDENTIFIED:'Identificado em arquivo',NOT_FOUND:'Driver não localizado'})[s]||''};
const row={id:'a',hostId:'h1',name:'jdbc/Pedidos',jndiNames:['jdbc/Pedidos','java:/Pedidos'],serverName:'AdminServer',associations:[{applicationName:'loja'}],linkedContexts:[{context:'context:/shop'}],databaseType:'Oracle',databaseHost:'db1',databasePort:1521,databaseName:'PEDIDOS',driverName:'oracle.jdbc.OracleDriver',driverVersion:'19.3',driverResolution:'NOT_FOUND',state:'DISABLED'};
const cols=ds.columns(h);
assert.deepEqual(cols.map(c=>c.key),['name','host','relations','databaseType','destination','driver','driverArtifact','driverVersion','support','driverResolution','state']);
const rendered=Object.fromEntries(cols.map(c=>[c.key,c.render(row)]));
assert.equal(rendered.name,'[jdbc/Pedidos|java:/Pedidos]');
assert(rendered.relations.includes('AdminServer')&&rendered.relations.includes('loja')&&rendered.relations.includes('context:/shop'));
assert(rendered.destination.includes('db1:1521 / PEDIDOS'));
assert(rendered.driverResolution.includes('warning')&&rendered.driverResolution.includes('Driver não localizado'));
assert.equal(rendered.state,'Desativado');
assert(cols.find(c=>c.key==='destination').render({}).includes('Não identificado'));
assert(cols.find(c=>c.key==='destination').render({databaseName:'${DB}'}).startsWith('<span class="unknown">Não identificado</span>'),'service without host is not a destination');
const view=ds.databases({scoped:true,pagination:{page:1,pages:1,total:2,pageSize:20},databaseGroups:[
  {key:'db1:1521/pedidos',label:'DB1:1521 / PEDIDOS',identified:true,datasources:3,machineCount:14,machines:[{id:'h1',name:'app-01'}],serverCount:1,servers:['AdminServer'],applicationCount:0,applications:[],databaseTypes:['Oracle']},
  {key:'__unidentified__',identified:false,datasources:1,machineCount:1,machines:[{id:'h3',name:'<b>x</b>'}],serverCount:0,servers:[],applicationCount:0,applications:[],databaseTypes:[]}]},'');
assert(view.includes('data-subset="db:db1:1521/pedidos"')&&view.includes('Ver 3 datasources'));
assert(view.includes('+13'),'remaining machines summarized');
assert(view.includes('Destino não identificado')&&view.includes('data-subset="db:__unidentified__"'));
assert(!view.includes('<b>x</b>'));
assert.equal(ds.focusLabel('db:__unidentified__'),'Destino não identificado');
assert.equal(ds.focusLabel('db:db1:1521/pedidos'),'Destino: db1:1521/pedidos');
console.log('DATASOURCES_UI_TESTS=PASS report cell summary/lines/escaping, tab columns, database groups, drill-down');
