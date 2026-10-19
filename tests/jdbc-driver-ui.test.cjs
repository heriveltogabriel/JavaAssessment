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
