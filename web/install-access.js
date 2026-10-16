/* Installation credentials live only in this administrator dialog, never inventory or storage. */
(function(root){
 'use strict';
 const esc=value=>String(value==null?'':value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 let ctx,dialog,body,status,error,state=null,environmentId='',revision=0,controller=null,busy=false,mutation=false,tab='curl',confirmation='';
 const admin=()=>!!ctx?.isAdmin?.();
 function init(context){
  ctx=context;
 }
 function mount(id,host){
  if(!admin()||!id)return;clear();
  dialog=document.createElement('section');dialog.id='install-access-panel';dialog.className='modal-body install-access-panel';dialog.setAttribute('role','tabpanel');dialog.setAttribute('aria-labelledby','agent-method-terminal');
  dialog.innerHTML='<div id="install-access-body"></div><p id="install-access-error" class="form-error" role="alert" hidden></p><p id="install-access-status" class="install-access-status" role="status" aria-live="polite"></p>';
  host.insertBefore(dialog,host.querySelector("#environment-error"));body=dialog.querySelector('#install-access-body');status=dialog.querySelector('#install-access-status');error=dialog.querySelector('#install-access-error');
  dialog.addEventListener('click',event=>{
   if(!admin()){clear();return;}
   const button=event.target.closest('button');if(!button||busy)return;
   if(button.dataset.installTab){tab=button.dataset.installTab;render();dialog.querySelector('[data-install-tab="'+tab+'"]').focus();return;}
   if(button.hasAttribute('data-install-save')){saveScript();return;}
   if(button.dataset.installCopy){copy(button.dataset.installCopy);return;}
   if(button.dataset.installAction){confirmation=button.dataset.installAction;render();dialog.querySelector('[data-install-confirm]')?.focus();return;}
   if(button.hasAttribute('data-install-cancel')){confirmation='';render();return;}
   if(button.hasAttribute('data-install-confirm')){request(confirmation);return;}
   if(button.hasAttribute('data-install-enable'))request('enable');
   if(button.hasAttribute('data-install-retry'))request();
  });
  dialog.addEventListener('keydown',event=>{const current=event.target.closest('[data-install-tab]');if(current&&['ArrowLeft','ArrowRight','Home','End'].includes(event.key)){event.preventDefault();tab=event.key==='Home'?'curl':event.key==='End'?'wget':tab==='curl'?'wget':'curl';render();dialog.querySelector('[data-install-tab="'+tab+'"]').focus();}});
  environmentId=id;request();
 }
 function reset(){revision++;controller?.abort();controller=null;state=null;environmentId='';confirmation='';busy=false;mutation=false;tab='curl';body?.replaceChildren();if(status)status.textContent='';if(error){error.textContent='';error.hidden=true;}dialog?.removeAttribute('aria-busy');}
 function clear(){reset();dialog?.remove();dialog=null;}
 function current(r,id){return r===revision&&dialog?.isConnected&&dialog.closest('dialog')?.open&&environmentId===id&&admin();}
 function open(id){if(admin())root.AssessmentEnvironments?.openPackage(id,'terminal');}
 async function request(action){
  if(!admin()){clear();return;}const id=environmentId,r=++revision;controller?.abort();controller=new AbortController();busy=true;mutation=!!action;state=null;confirmation='';status.textContent='';error.hidden=true;error.textContent='';dialog.setAttribute('aria-busy','true');body.innerHTML='<p class="install-access-loading" role="status">'+(action?'Atualizando acesso de instalação…':'Consultando acesso de instalação…')+'</p>';
  try{
   const response=await ctx.api('environments/install-access'+(action?'':'?'+new URLSearchParams({environmentId:id})),{...(action?{method:'POST',body:{environmentId:id,action}}:{}),signal:controller.signal});
   if(!current(r,id))return;
   if(response.environmentId!==id)throw new Error('Unexpected environment');state=response;busy=false;render();if(action)status.textContent=action==='revoke'?'Novos downloads e cadastros desse acesso foram desativados.':action==='renew'?'Acesso renovado. Use o novo comando nas próximas instalações.':'Acesso de instalação ativado.';
  }catch(e){if(!current(r,id)||e.name==='AbortError')return;state=null;busy=false;body.innerHTML='<p>Não foi possível consultar o acesso. Atualize para conferir o estado antes de tentar uma nova alteração.</p><button type="button" class="button" data-install-retry>Consultar novamente</button>';error.textContent='Não foi possível concluir esta operação. Nenhuma credencial é exibida até confirmar o estado no servidor.';error.hidden=false;}
  finally{if(current(r,id)){busy=false;dialog.removeAttribute('aria-busy');}}
 }
 function command(kind,label,text){return '<div class="environment-command"><div class="environment-command-heading"><span>'+esc(label)+'</span>'+(kind==='download'?'<button type="button" class="text-button" data-install-save>Baixar '+tab+' (.sh)</button>':'')+'<button type="button" class="text-button" data-install-copy="'+kind+'" aria-label="Copiar '+esc(label.toLowerCase())+'">Copiar</button></div><pre id="install-access-'+kind+'-command">'+esc(text)+'</pre></div>';}
 function render(){
  if(!admin()){clear();return;}if(!state)return;const env=ctx.getRaw()?.environments?.find(e=>e.id===environmentId);if(!env){clear();return;}
  const enabled=state.enabled===true,available=enabled&&typeof state.commands?.[tab]==='string'&&state.commands[tab];
  body.innerHTML='<div class="install-access-identity"><div><span class="eyebrow">AMBIENTE</span><h3>'+esc(env.name)+'</h3></div><span class="install-access-badge '+(enabled?'active':'')+'">'+(enabled?'Ativo · sem expiração':state.configured?'Desativado':'Não ativado')+'</span></div>'+
   '<p class="install-access-intro">Baixe o agente diretamente nas máquinas Linux deste ambiente. O mesmo comando pode ser reutilizado em várias máquinas.</p>'+
   (enabled?'<div class="install-access-security"><strong>Proteja o comando e o arquivo .sh como uma senha.</strong><p>Ele permite baixar o pacote e cadastrar máquinas neste ambiente até você renovar ou desativar o acesso. Compartilhe somente com quem instala os agentes.</p></div><div class="install-access-package"><span>Pacote disponível</span><strong>'+esc(state.version?'Versão '+state.version:'Versão indisponível')+'</strong><code>'+esc(state.filename)+'</code></div><section class="install-access-step"><h3><span>1</span> Baixar e extrair</h3><p>Execute na máquina de destino, com seu usuário normal, <strong>sem sudo</strong>. É necessário alcançar o dashboard por HTTPS e ter <code>unzip</code> e <code>sha256sum</code> instalados.</p><div class="install-access-tabs" role="tablist" aria-label="Ferramenta de download">'+['curl','wget'].map(tool=>'<button type="button" role="tab" id="install-access-tab-'+tool+'" aria-selected="'+(tab===tool)+'" tabindex="'+(tab===tool?'0':'-1')+'" aria-controls="install-access-command-panel" data-install-tab="'+tool+'">'+tool+'</button>').join('')+'</div><div id="install-access-command-panel" role="tabpanel" aria-labelledby="install-access-tab-'+tab+'">'+(available?command('download','Comando '+tab,state.commands[tab])+'<div class="install-script-actions"><p>Ou copie o comando acima. Para usar o arquivo, transfira-o para a máquina de destino e execute:</p><code>sh '+esc(scriptName())+'</code><small>O script baixa e extrai o pacote; a instalação continua no passo 2. Ele usa a versão disponível no dashboard no momento da execução.</small></div>':'<p>Comando indisponível. Consulte novamente o acesso.</p>')+'</div></section><section class="install-access-step"><h3><span>2</span> Instalar o agente</h3><p>Após o download, execute o comando de instalação indicado pelo terminal. O instalador com <code>--system-root</code> cria e ativa os serviços automaticamente; não é necessário configurá-los manualmente. O agente será instalado no caminho fixo <code>/opt/java-assessment-collector</code>.</p>'+(state.installCommand?command('install','Instalação',state.installCommand):'')+'</section><div class="environment-callout"><strong>O comando não expira; o cadastro do ZIP vale por 24 horas.</strong><br>Baixe novamente se o pacote expirar. Renovar ou desativar este acesso impede novos cadastros com os ZIPs gerados por ele. Agentes já cadastrados continuam funcionando.</div>':
   '<div class="install-access-disabled"><h3>'+(state.configured?'Novas instalações desativadas':'Ative o acesso para este ambiente')+'</h3><p>'+(state.configured?'Gere um novo acesso para voltar a baixar e cadastrar agentes pelo terminal.':'A ativação cria um comando reutilizável, sem expiração, disponível apenas para administradores.')+'</p><button type="button" class="button primary" data-install-enable>'+(state.configured?'Gerar novo acesso':'Ativar acesso de instalação')+'</button></div>')+
   '<p class="install-access-legacy">A aba <strong>Baixar ZIP</strong> usa um cadastro temporário independente deste acesso.</p>'+
   (enabled?'<section class="install-access-controls" aria-label="Administrar acesso de instalação">'+(confirmation?'<div class="install-access-confirm" role="group" aria-label="Confirmar alteração"><strong>'+(confirmation==='renew'?'Renovar acesso de instalação?':'Desativar novos cadastros?')+'</strong><p>O comando atual e os ZIPs gerados por ele deixarão de cadastrar novas máquinas. Os agentes já cadastrados não serão interrompidos.</p><div><button type="button" class="button" data-install-cancel>Cancelar</button><button type="button" class="button '+(confirmation==='revoke'?'danger':'primary')+'" data-install-confirm>'+(confirmation==='renew'?'Renovar acesso':'Desativar acesso')+'</button></div></div>':'<button type="button" class="text-button" data-install-action="renew">Renovar acesso de instalação</button><button type="button" class="text-button install-access-revoke" data-install-action="revoke">Desativar novos cadastros</button>')+'</section>':'');
 }
 function scriptName(){return 'java-assessment-'+environmentId.replace(/[^a-zA-Z0-9_-]/g,'-').slice(0,64)+'-'+tab+'.sh';}
 function saveScript(){
  if(!admin()||busy||!state?.enabled||!['curl','wget'].includes(tab)||typeof state.commands?.[tab]!=='string')return;
  const text='#!/bin/sh\n# Credencial de instalação: proteja este arquivo e não o publique.\n'+state.commands[tab]+'\n';
  const url=URL.createObjectURL(new Blob([text],{type:'text/x-shellscript;charset=utf-8'})),link=document.createElement('a');
  link.href=url;link.download=scriptName();link.hidden=true;document.body.append(link);
  try{link.click();status.textContent='Download iniciado. O arquivo contém uma credencial: proteja-o e não o publique em repositórios.';}
  finally{link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);}
 }
 async function copy(kind){
  if(!admin()){clear();return;}const node=document.getElementById('install-access-'+kind+'-command'),r=revision,id=environmentId;if(!node||busy)return;
  try{await navigator.clipboard.writeText(node.textContent);if(current(r,id))status.textContent='Comando copiado. A área de transferência contém uma credencial de instalação; não a compartilhe publicamente.';}
  catch(_){if(current(r,id))status.textContent='Não foi possível copiar. Selecione o comando e copie manualmente.';}
 }
 root.AssessmentInstallAccess={init,mount,open,clear,isBusy:()=>busy&&mutation,checkAccess:()=>{if(!admin())clear();}};
})(typeof window!=='undefined'?window:globalThis);
