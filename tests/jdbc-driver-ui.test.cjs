const fs=require('node:fs');const vm=require('node:vm');const assert=require('node:assert/strict');
const code=fs.readFileSync('web/app.js','utf8').split('\n').slice(0,3).join('\n');
const ctx={};vm.runInNewContext(code+'\nthis.jar=jdbcJarFileName;this.label=jdbcDriverResolutionLabel;this.candidates=jdbcDriverCandidatesText;',ctx);
assert.equal(ctx.label('AMBIGUOUS'),'Múltiplos candidatos');
assert.equal(ctx.label('INCOMPLETE'),'Inspeção incompleta');
assert.equal(ctx.candidates([null,{path:'/domain/lib/ojdbc8.jar',version:'19.22.0.0.0',password:'SECRET'}]),'/domain/lib/ojdbc8.jar — 19.22.0.0.0');
assert.equal(ctx.candidates(null),'');
assert(ctx.candidates([{path:'/domain/lib/renamed.jar'}]).includes('Versão indisponível'));
console.log('JDBC_DRIVER_UI=PASS statuses candidate allowlist malformed values');

assert.equal(ctx.jar({_kind:'datasources',driverArtifact:'/wl/server/lib/ojdbc6.jar'}),'ojdbc6.jar');
assert.equal(ctx.jar({_kind:'datasources',driverArtifact:'/domain/lib/derby.jar'}),'derby.jar');
assert.equal(ctx.jar({_kind:'libraries',path:'/app/app.jar!/BOOT-INF/lib/ojdbc11.jar'}),'ojdbc11.jar');
assert.equal(ctx.jar({_kind:'datasources',configurationSource:'/domain/config/jdbc/ds.xml',driverCandidates:[{path:'/lib/ojdbc6.jar'}],driverResolution:'AMBIGUOUS'}),'');
assert.equal(ctx.jar({_kind:'libraries',path:'/lib/classes'}),'');
console.log('JDBC_JAR_COLUMN=PASS identified files, nested archive, no candidate guessing');
const helpers=fs.readFileSync('web/app.js','utf8').split('\n').slice(0,20).join('\n');
// Read only the standalone driver helpers, before the application bootstrap.
const source=fs.readFileSync('web/app.js','utf8');const end=source.indexOf('\n',source.indexOf('const jdbcDriverProcessTable'));
const ui={};vm.runInNewContext(source.slice(0,end)+'\nthis.table=jdbcDriverCandidatesTable;this.artifact=jdbcDriverArtifactCell;this.version=jdbcDriverVersionCell;this.processes=jdbcDriverProcessTable;',ui);
assert.equal(ctx.label('PROBABLE'),'Provável');assert.equal(ctx.label('VARIES'),'Varia por instância/servidor');
assert(ui.artifact({driverResolution:'PROBABLE',driverArtifact:'/driver/ojdbc8.jar'}).includes('Provável'));
assert(ui.artifact({driverResolution:'VARIES',driverCandidates:[{path:'/one.jar'},{path:'/two.jar'}]}).includes('2 candidatos'));
const table=ui.table([{path:'<script>.jar',version:'19.3',versionSource:'manifest',order:1,open:true,loaded:true,password:'SECRET'}]);
assert(table.includes('&lt;script&gt;'));assert(!table.includes('SECRET'));assert(table.includes('Manifesto'));assert(table.includes('Selecionado'));
assert(ui.version({driverResolution:'PROBABLE',driverVersion:'19.3'}).includes('Provável'));
assert(ui.processes([{pid:123,serverName:'Managed',driverResult:{driverResolution:'IDENTIFIED',driverCandidates:[],driverResolutionReason:'Ordered'}}]).includes('PID 123'));
console.log('WEBLOGIC_DRIVER_UI=PASS confidence candidate-table PID escaping legacy');
