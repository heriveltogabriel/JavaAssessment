/* Per-machine actions share one menu, outside scroll containers, and immutable confirmation targets. */
(function(root){
  'use strict';
  const $=id=>document.getElementById(id);
  const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const name=host=>String(host?.hostname||host?.id||'');
  const icon=id=>'<svg aria-hidden="true"><use href="#i-'+id+'"/></svg>';
  let ctx,menu,dialog,form,anchor=null,menuHost=null,target=null,mode='',revision=0,busy=false;
  function admin(){return !!ctx?.isAdmin();}
  function trigger(host){if(!admin())return '';return '<button type="button" class="machine-action-trigger" data-machine-actions="'+esc(host.id)+'" aria-label="Ações da máquina '+esc(name(host))+'" aria-haspopup="menu" aria-expanded="false" aria-controls="machine-actions-menu">'+icon('more')+'</button>';}
  function deletionReason(host){
    if(root.AssessmentAgentUpdates?.active(host))return 'Aguarde a atualização do agente ser resolvida antes de excluir.';
    if(['WAITING','UNINSTALLING','UNCONFIRMED'].includes(host?.uninstall?.status))return 'Aguarde a conclusão confirmada da desinstalação antes de excluir.';
    if(host?.credentialStatus!=='revoked'&&host?.agentLifecycle!=='uninstalled')return 'Revogue o acesso do agente ou conclua sua desinstalação antes de excluir.';
    return '';
  }
  function snapshot(host){return {id:host.id,name:name(host),environmentId:host.environmentId||''};}
  function staleReason(host,selected,action){
    if(!admin())return 'Esta ação requer um administrador.';
    if(!host||host.id!==selected?.id)return 'Esta máquina não está mais nesta lista. Feche e atualize a página.';
    if(name(host)!==selected.name)return 'O nome da máquina mudou. Feche e abra a ação novamente para confirmar.';
    if((host.environmentId||'')!==selected.environmentId)return 'O ambiente da máquina mudou. Feche e abra a ação novamente.';
    if(root.AssessmentAgentUpdates?.active(host)&&['move','detach'].includes(action))return 'Aguarde a atualização do agente ser resolvida antes de mudar o ambiente.';
    if(action==='delete')return deletionReason(host);
    if(action==='detach'&&!host.environmentId)return 'Esta máquina já está sem ambiente.';
    return '';
  }
  function menuItem(action,label,reason='',destructive=false){return '<button type="button" role="menuitem" tabindex="-1" data-machine-action="'+action+'" class="machine-menu-item'+(destructive?' destructive':'')+'" aria-disabled="'+!!reason+'"><span>'+label+'</span>'+(reason?'<small>'+esc(reason)+'</small>':'')+'</button>';}
  function closeMenu(restore=false){const prior=anchor;if(prior?.isConnected)prior.setAttribute('aria-expanded','false');anchor=null;menuHost=null;if(menu){menu.hidden=true;menu.replaceChildren();}if(restore&&prior?.isConnected)prior.focus();}
  function openMenu(button,focus=true){
    if(!admin())return;const host=ctx.getHost(button.dataset.machineActions);if(!host)return;
    if(anchor===button&&!menu.hidden){closeMenu(true);return;}closeMenu();ctx.closeOtherMenus?.();anchor=button;menuHost=snapshot(host);anchor.setAttribute('aria-expanded','true');menu.setAttribute('aria-label','Ações da máquina '+name(host));
    const canMove=ctx.getEnvironments().some(env=>env.id!==(host.environmentId||'')),updateReason=root.AssessmentAgentUpdates?.active(host)?'Aguarde a atualização do agente ser resolvida antes de mudar o ambiente.':'';
    menu.innerHTML='<div class="machine-menu-heading" aria-hidden="true">'+esc(name(host))+'</div>'+menuItem('move','Mover para outro ambiente',updateReason||(canMove?'':'Crie outro ambiente para mover esta máquina.'))+menuItem('detach','Desvincular do ambiente (manter cadastro)',updateReason||(host.environmentId?'':'Esta máquina já está sem ambiente.'))+menuItem('update','Atualizar agente')+'<div class="machine-menu-divider" role="separator"></div>'+menuItem('delete','Excluir máquina do dashboard',deletionReason(host),true);
    menu.hidden=false;positionMenu();if(focus)menu.querySelector('[role=menuitem]').focus();
  }
  function positionMenu(){
    if(!anchor?.isConnected){closeMenu();return;}const box=anchor.getBoundingClientRect(),margin=8,width=Math.min(304,innerWidth-margin*2);menu.style.width=width+'px';menu.style.maxHeight=Math.max(100,innerHeight-margin*2)+'px';
    const height=menu.getBoundingClientRect().height,left=Math.max(margin,Math.min(innerWidth-width-margin,box.right-width));let top=box.bottom+6;if(top+height>innerHeight-margin)top=Math.max(margin,box.top-height-6);menu.style.left=left+'px';menu.style.top=top+'px';
  }
  function resetDialog(){revision++;target=null;mode='';busy=false;if(form){form.reset();$('machine-dialog-body').replaceChildren();$('machine-dialog-error').textContent='';$('machine-dialog-error').hidden=true;dialog.removeAttribute('aria-busy');dialog.querySelectorAll('[data-machine-close]').forEach(button=>button.disabled=false);}}
  function closeDialog(force=false){if(busy&&!force)return;if(dialog?.open)dialog.close();resetDialog();}
  function clear(){closeMenu();closeDialog(true);}
  function showError(message){$('machine-dialog-error').textContent=message;$('machine-dialog-error').hidden=false;}
  function updateSubmit(){
    if(!dialog?.open||!target)return;const current=ctx.getHost(target.id),reason=staleReason(current,target,mode),matches=mode!=='delete'||form.elements.confirmationName?.value===target.name;
    $('machine-dialog-submit').disabled=busy||!!reason||!matches;if(reason)showError(reason);
  }
  function confirm(action,host){
    if(!admin()||!host)return;const reason=action==='delete'?deletionReason(host):!host.environmentId?'Esta máquina já está sem ambiente.':'';if(reason){ctx.notify(reason,true);return;}
    closeMenu();closeDialog(true);mode=action;target=snapshot(host);revision++;
    const deleting=action==='delete';$('machine-dialog-title').textContent=deleting?'Excluir máquina do dashboard':'Desvincular máquina do ambiente';$('machine-dialog-submit').textContent=deleting?'Excluir máquina':'Desvincular';$('machine-dialog-submit').classList.toggle('danger',deleting);$('machine-dialog-submit').classList.toggle('primary',!deleting);
    $('machine-dialog-body').innerHTML='<div class="machine-confirm-identity"><span class="machine-confirm-icon '+(deleting?'danger':'')+'">'+icon(deleting?'warning':'server')+'</span><div><strong>'+esc(target.name)+'</strong><span>'+esc(ctx.getEnvironments().find(env=>env.id===target.environmentId)?.name||'Sem ambiente')+'</span></div></div>'+(deleting?'<p>Esta ação exclui o cadastro, o inventário, as tarefas e o histórico desta máquina do dashboard. Ela é permanente.</p><div class="machine-confirm-note">O acesso antigo do agente continuará revogado. Para coletar essa máquina novamente, será necessário um novo registro.</div><label class="field" for="machine-confirmation-name"><span>Digite <strong>'+esc(target.name)+'</strong> para confirmar</span><input id="machine-confirmation-name" name="confirmationName" required autocomplete="off" spellcheck="false" aria-describedby="machine-confirmation-hint"></label><p class="form-note" id="machine-confirmation-hint">O nome deve ser igual, incluindo letras maiúsculas e espaços.</p>':'<p>A máquina ficará em <strong>Sem ambiente</strong>. Seu inventário e histórico serão preservados.</p><div class="machine-confirm-note">As configurações próprias da máquina serão mantidas. As demais configurações passarão a seguir os padrões do dashboard.</div>');
    dialog.showModal();updateSubmit();(deleting?form.elements.confirmationName:$('machine-dialog-submit')).focus();
  }
  async function verifyTarget(selected,action){
    const current=ctx.getHost(selected.id),reason=staleReason(current,selected,action);if(reason)throw new Error(reason);
    const params=new URLSearchParams({kind:'hosts',id:selected.id,hostId:selected.id,environmentId:ctx.getScope()||''});
    const result=await ctx.api('item?'+params.toString()),freshReason=staleReason(result.item,selected,action);if(freshReason)throw new Error(freshReason);return result.item;
  }
  async function submit(event){
    event.preventDefault();if(busy||!target||!dialog.open)return;updateSubmit();if($('machine-dialog-submit').disabled)return;
    const selected={...target},action=mode,generation=revision,accessRevision=ctx.getRevision();busy=true;updateSubmit();dialog.setAttribute('aria-busy','true');$('machine-dialog-error').hidden=true;dialog.querySelectorAll('[data-machine-close]').forEach(button=>button.disabled=true);if(form.elements.confirmationName)form.elements.confirmationName.disabled=true;
    const valid=()=>generation===revision&&accessRevision===ctx.getRevision()&&admin()&&dialog.open;
    try{
      await verifyTarget(selected,action);if(!valid())return;
      if(action==='delete')await ctx.api('hosts',{method:'DELETE',body:{hostId:selected.id,confirmationName:selected.name}});
      else await ctx.api('hosts/environment',{method:'PUT',body:{hostId:selected.id,environmentId:''}});
      if(!valid())return;closeDialog(true);await ctx.afterChange({hostId:selected.id,action,environmentId:''});ctx.notify(action==='delete'?'Máquina excluída do dashboard.':'Máquina movida para Sem ambiente.');
    }catch(error){if(valid())showError(error.message);}
    finally{if(generation===revision&&dialog.open){busy=false;dialog.removeAttribute('aria-busy');dialog.querySelectorAll('[data-machine-close]').forEach(button=>button.disabled=false);if(form.elements.confirmationName)form.elements.confirmationName.disabled=false;updateSubmit();}}
  }
  function sync(){closeMenu();if(dialog?.open){if(!admin())clear();else updateSubmit();}}
  function init(context){
    ctx=context;menu=document.createElement('div');menu.id='machine-actions-menu';menu.className='machine-actions-menu';menu.setAttribute('role','menu');menu.hidden=true;document.body.append(menu);
    dialog=document.createElement('dialog');dialog.id='machine-dialog';dialog.className='machine-dialog';dialog.setAttribute('aria-labelledby','machine-dialog-title');dialog.innerHTML='<form id="machine-action-form"><header class="modal-header"><div><span class="eyebrow">MÁQUINA · ADMINISTRAÇÃO</span><h2 id="machine-dialog-title"></h2></div><button type="button" class="icon-button" data-machine-close aria-label="Fechar confirmação">'+icon('close')+'</button></header><div class="modal-body" id="machine-dialog-body"></div><p class="form-error" id="machine-dialog-error" role="alert" hidden></p><footer class="modal-footer"><button type="button" class="button" data-machine-close>Cancelar</button><button type="submit" class="button primary" id="machine-dialog-submit">Confirmar</button></footer></form>';document.body.append(dialog);form=$('machine-action-form');form.addEventListener('submit',submit);form.addEventListener('input',updateSubmit);dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});dialog.addEventListener('close',()=>{if(!dialog.open)resetDialog();});
    document.addEventListener('click',event=>{
      const button=event.target.closest('[data-machine-actions]');if(button){event.preventDefault();openMenu(button);return;}
      const action=event.target.closest('[data-machine-action]');if(action&&menu.contains(action)){event.preventDefault();if(action.getAttribute('aria-disabled')==='true'||!menuHost||!admin())return;const selected={...menuHost},host=ctx.getHost(selected.id),reason=staleReason(host,selected,action.dataset.machineAction);if(reason){closeMenu();ctx.notify(reason,true);return;}closeMenu();if(action.dataset.machineAction==='move')ctx.move(selected.id,{preserveContext:true,expectedTarget:selected});else if(action.dataset.machineAction==='update')ctx.update(selected.id);else confirm(action.dataset.machineAction,host);return;}
      if(event.target.closest('[data-machine-close]'))closeDialog();
      if(!menu.contains(event.target))closeMenu();
    });
    document.addEventListener('keydown',event=>{
      const button=event.target.closest('[data-machine-actions]');if(button&&['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();if(menu.hidden||anchor!==button)openMenu(button);if(event.key==='ArrowUp')menu.querySelector('[role=menuitem]:last-child')?.focus();return;}
      if(menu.hidden)return;
      if(event.key==='Escape'){event.preventDefault();closeMenu(true);return;}
      if(event.key==='Tab'){closeMenu(true);return;}
      if(menu.contains(event.target)&&['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();const items=[...menu.querySelectorAll('[role=menuitem]')],index=items.indexOf(document.activeElement);items[event.key==='Home'?0:event.key==='End'?items.length-1:(index+(event.key==='ArrowDown'?1:items.length-1))%items.length].focus();}
    });
    document.addEventListener('scroll',event=>{if(!menu.hidden&&!menu.contains(event.target)){const box=anchor?.getBoundingClientRect();if(!box||box.bottom<0||box.top>innerHeight)closeMenu();else positionMenu();}},true);root.addEventListener('resize',()=>closeMenu());
  }
  root.AssessmentMachines={init,trigger,clear,sync,closeMenu,deletionReason,verifyTarget};
})(window);
