'use strict';
window.AssessmentMachineCleanup=(()=>{
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const periods=[[300,'5 minutos ou mais'],[86400,'24 horas ou mais'],[604800,'7 dias ou mais'],[2592000,'30 dias ou mais']];
  let options,state={},key='',selected=new Set(),visible=[],dialog,epoch=0,busy=false;
  const active=()=>state.enabled&&['disconnected','silent'].includes(state.subset);
  const selectable=()=>active()&&options.isAdmin();
  function clear(){epoch++;selected.clear();visible=[];busy=false;if(dialog){dialog.close();dialog.remove();dialog=null;}paint();}
  function sync(next){const nextKey=JSON.stringify([next.enabled,next.environmentId,next.subset,next.minimumSeconds,next.page,next.size,next.query,next.from,next.to,options.isAdmin()]);if(key!==nextKey){clear();key=nextKey;}state=next;render();}
  function render(){const root=document.getElementById('machine-cleanup-controls');root.hidden=!state.enabled;if(!state.enabled)return;
    const focus=root.contains(document.activeElement)?document.activeElement.id:'';
    root.innerHTML='<div class="cleanup-filters"><button type="button" class="button '+(!active()?'primary':'')+'" data-cleanup-filter="" aria-pressed="'+!active()+'">Todas as máquinas</button><button type="button" class="button '+(active()?'primary':'')+'" data-cleanup-filter="disconnected" aria-pressed="'+!!active()+'">Sem comunicação</button>'+(active()?'<label>Último contato há <select id="cleanup-period">'+periods.map(([v,t])=>'<option value="'+v+'" '+(v===state.minimumSeconds?'selected':'')+'>'+t+'</option>').join('')+'</select></label>':'')+'</div>'+(active()?'<div class="cleanup-selection"><span id="cleanup-count" role="status"></span>'+(options.isAdmin()?'<button type="button" class="button" id="cleanup-delete">Excluir do inventário</button>':'')+'</div><p class="muted">'+(state.subset==='silent'?'Agentes desconectados deste ambiente. Use “Sem comunicação” para incluir também acessos revogados e agentes desinstalados.':'Inclui acessos revogados e agentes desinstalados com último contato conhecido.')+' Nenhuma máquina é excluída automaticamente.</p>':'');
    root.querySelectorAll('[data-cleanup-filter]').forEach(b=>b.onclick=()=>options.change(b.dataset.cleanupFilter,state.minimumSeconds));
    const period=root.querySelector('#cleanup-period');if(period)period.onchange=()=>options.change(state.subset,Number(period.value));
    const remove=root.querySelector('#cleanup-delete');if(remove)remove.onclick=preview;
    if(focus)document.getElementById(focus)?.focus({preventScroll:true});paint();
  }
  function prepare(rows){visible=selectable()?rows:[];const ids=new Set(visible.map(r=>r.id));for(const id of selected)if(!ids.has(id))selected.delete(id);}
  const checkbox=r=>'<input type="checkbox" data-cleanup-id="'+esc(r.id)+'" aria-label="Selecionar '+esc(r.hostname||r.id)+'" '+(selected.has(r.id)?'checked':'')+'>';
  const header=()=>'<input type="checkbox" id="cleanup-all" aria-label="Selecionar máquinas desta página">';
  function paint(){const count=document.getElementById('cleanup-count');if(count)count.textContent=options?.isAdmin()?selected.size+' selecionada'+(selected.size===1?'':'s')+' nesta página':'Consulte o último contato de cada máquina.';
    const remove=document.getElementById('cleanup-delete');if(remove)remove.disabled=busy||!selected.size;
    const all=document.getElementById('cleanup-all');if(all){all.checked=visible.length>0&&selected.size===visible.length;all.indeterminate=selected.size>0&&selected.size<visible.length;all.disabled=busy||!visible.length;all.onchange=()=>{selected=all.checked?new Set(visible.map(r=>r.id)):new Set();paint();};}
    document.querySelectorAll('[data-cleanup-id]').forEach(c=>{c.checked=selected.has(c.dataset.cleanupId);c.disabled=busy;c.onchange=()=>{c.checked?selected.add(c.dataset.cleanupId):selected.delete(c.dataset.cleanupId);paint();};});
  }
  function elapsed(value,now){const seconds=Math.floor((Date.parse(now)-Date.parse(value))/1000);if(!Number.isFinite(seconds)||seconds<0)return '—';const days=Math.floor(seconds/86400),hours=Math.floor(seconds%86400/3600),minutes=Math.floor(seconds%3600/60);return days?days+' d '+hours+' h':hours?hours+' h '+minutes+' min':minutes+' min';}
  const time=v=>Number.isFinite(Date.parse(v))?new Date(v).toLocaleString('pt-BR'):'Indisponível';
  async function preview(){if(!selectable()||busy||!selected.size)return;busy=true;paint();const turn=++epoch,revision=options.getRevision(),scope={environmentId:state.environmentId,minimumSeconds:state.minimumSeconds};
    try{const result=await options.api('hosts/cleanup/preview',{method:'POST',body:{...scope,hostIds:[...selected]}});if(turn!==epoch||revision!==options.getRevision())return;show(result,scope,turn,revision);}
    catch(e){if(turn===epoch&&revision===options.getRevision())options.notify(e.message,true);}finally{if(turn===epoch){busy=false;paint();}}
  }
  function show(result,scope,turn,revision){const eligible=result.items.filter(i=>i.eligible),targets=eligible.map(i=>({hostId:i.hostId,name:i.name,environmentId:i.environmentId,lastSeen:i.lastSeen}));
    dialog=document.createElement('dialog');dialog.id='cleanup-dialog';dialog.className='cleanup-dialog';dialog.setAttribute('aria-labelledby','cleanup-title');
    dialog.innerHTML='<form><header><h2 id="cleanup-title">Excluir do inventário</h2></header><p>A exclusão é permanente: remove a máquina, seu inventário e suas solicitações do dashboard. O agente instalado não será desinstalado. Sua credencial antiga será bloqueada.</p><div class="cleanup-preview">'+result.items.map(i=>'<article><strong>'+esc(i.name)+'</strong><small>'+esc(i.environmentName||'')+' · '+esc((i.ipAddresses||[]).join(', '))+'</small><small>Último contato: '+esc(time(i.lastSeen))+'</small><span>'+esc(i.eligible?'Selecionada para exclusão':i.reason)+'</span></article>').join('')+'</div><p>'+eligible.length+' de '+result.items.length+' máquina(s) podem ser excluídas. O contato será conferido novamente ao confirmar.</p>'+(eligible.length?'<label class="cleanup-confirm-label">Digite <strong>EXCLUIR</strong> para confirmar<input id="cleanup-confirm" autocomplete="off" spellcheck="false"></label>':'')+'<p id="cleanup-result" role="status" hidden></p><footer><button type="button" class="button" id="cleanup-cancel">Cancelar</button><button type="submit" class="button danger" id="cleanup-submit" disabled>Confirmar exclusão de '+eligible.length+' máquina(s)</button></footer></form>';
    document.body.append(dialog);const current=dialog,submit=current.querySelector('#cleanup-submit'),cancel=current.querySelector('#cleanup-cancel'),input=current.querySelector('#cleanup-confirm');let submitting=false,attempted=false;
    const close=()=>{if(submitting)return;current.close();current.remove();if(dialog===current)dialog=null;document.getElementById('cleanup-delete')?.focus();};
    cancel.onclick=close;current.addEventListener('cancel',e=>{e.preventDefault();close();});if(input)input.oninput=()=>{submit.disabled=attempted||input.value!=='EXCLUIR';};
    current.querySelector('form').onsubmit=async e=>{e.preventDefault();if(submit.disabled||!options.isAdmin()||turn!==epoch||revision!==options.getRevision())return;submitting=true;attempted=true;submit.disabled=true;cancel.disabled=true;input.disabled=true;const output=current.querySelector('#cleanup-result');output.hidden=false;output.textContent='Conferindo o contato e excluindo as máquinas…';
      try{const response=await options.api('hosts/cleanup',{method:'POST',body:{...scope,targets,confirmation:input.value}});if(turn!==epoch||revision!==options.getRevision())return;selected.clear();output.replaceChildren();const summary=document.createElement('p');summary.textContent=response.deleted.length+' máquina(s) excluída(s); '+response.skipped.length+' preservada(s).';output.append(summary);options.notify(summary.textContent);for(const item of response.skipped){const line=document.createElement('p');line.textContent=item.name+': '+item.reason;output.append(line);}submit.hidden=true;await options.refresh(false);}
      catch(error){if(turn===epoch&&revision===options.getRevision()){output.textContent='A exclusão não foi confirmada nesta tela. Consulte a lista antes de tentar novamente. '+error.message;submit.hidden=true;await options.refresh(false);}}
      finally{submitting=false;cancel.disabled=false;cancel.textContent='Fechar';paint();}
    };current.showModal();(input||cancel).focus();
  }
  return {init(o){options=o;},sync,prepare,paint,active,selectable,checkbox,header,elapsed,clear};
})();
