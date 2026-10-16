/* Dashboard maintenance: no credentials persisted, package bytes stream to the server. */
(function(root){
  'use strict';
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const labels={IDLE:'Pronto para receber pacote',UNAVAILABLE:'Atualizador indisponível',VERIFYING:'Verificando pacote',PREPARED:'Pronto para atualizar',BACKUP:'Criando backup',APPLYING:'Aplicando atualização',STARTING:'Reiniciando e verificando',SUCCEEDED:'Atualização concluída',ROLLING_BACK:'Restaurando versão anterior',ROLLED_BACK:'Versão anterior restaurada',CANCELLED:'Preparação cancelada',FAILED:'Atualização não realizada',RECOVERY_REQUIRED:'Recuperação precisa de diagnóstico'};
  let panel=null,timer=null,generation=0,busy=false,state=null,pendingId='',lastEventKey='';
  const request=(path,opts)=>root.AssessmentAccess.request('admin/dashboard-update'+path,opts);
  function stop(){generation++;clearTimeout(timer);timer=null;panel=null;busy=false;state=null;pendingId='';lastEventKey='';}
  function mount(target){stop();panel=target;if(!panel)return;const rev=generation;
    panel.innerHTML='<section class="dashboard-update"><h2>Atualização do dashboard</h2><p>Envie o pacote assinado. A preparação mantém o painel disponível; a aplicação exige confirmar o reinício. Os agentes são atualizados separadamente.</p><p id="du-error" role="alert" hidden></p><div class="du-summary" role="status" aria-live="polite"><strong id="du-phase">Consultando atualizador…</strong><span id="du-versions"></span></div><p id="du-notes"></p><form id="du-upload"><label for="du-file">Pacote de atualização (.zip, até 256 MiB)</label><input id="du-file" type="file" accept=".zip,application/zip" required><button class="button primary" type="submit">Enviar e preparar</button></form><form id="du-actions" hidden><p id="du-warning"></p><label for="du-password">Sua senha de administrador para confirmar</label><input id="du-password" type="password" autocomplete="current-password"><div class="du-buttons"><button class="button primary" type="submit" id="du-confirm">Atualizar e reiniciar</button><button class="button" type="button" id="du-cancel">Cancelar preparação</button></div></form><details open><summary>Etapas da atualização</summary><ol id="du-events" aria-live="polite"></ol><button type="button" class="text-button" id="du-log">Baixar log</button></details><details><summary>Histórico de atualizações</summary><ul id="du-history"></ul></details></section>';
    panel.querySelector('#du-upload').addEventListener('submit',upload);
    panel.querySelector('#du-actions').addEventListener('submit',e=>{e.preventDefault();command(state?.phase==='PREPARED'?'confirm':'rollback');});
    panel.querySelector('#du-cancel').addEventListener('click',()=>command('cancel'));
    panel.querySelector('#du-log').addEventListener('click',()=>{const blob=new Blob([(state?.events||[]).map(e=>e.at+' '+e.message).join('\n')],{type:'text/plain;charset=utf-8'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='atualizacao-dashboard.log';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
    poll(rev);
  }
  const find=id=>panel?.querySelector('#'+id);
  function error(message){const el=find('du-error');if(el){el.textContent=message;el.hidden=!message;}}
  function render(next){
    state=next;const phase=next.phase||'UNAVAILABLE';
    find('du-phase').textContent=labels[phase]||'Estado desconhecido';
    find('du-versions').textContent='Instalada: '+(next.installedVersion||'—')+(next.version?' · Pacote: '+next.version:'');
    find('du-notes').textContent=next.notes||next.message||'';
    find('du-upload').hidden=!['IDLE','SUCCEEDED','ROLLED_BACK','CANCELLED','FAILED'].includes(phase);
    const prepared=phase==='PREPARED',rollback=next.canRollback===true;
    find('du-actions').hidden=!(prepared||rollback);
    find('du-cancel').hidden=!prepared;
    find('du-confirm').textContent=prepared?'Atualizar e reiniciar':'Restaurar versão anterior';
    find('du-warning').textContent=prepared?'O dashboard ficará indisponível por alguns instantes. O backup será criado antes da troca. Se a nova versão falhar, o serviço tentará restaurar a anterior.':'A restauração reinicia o dashboard e preserva o banco atual. Ela será recusada se houver operações de agentes pendentes ou incompatibilidade.';
    const events=JSON.stringify(next.events||[]);if(events!==lastEventKey){lastEventKey=events;find('du-events').innerHTML=(next.events||[]).map(e=>'<li><time>'+esc(e.at)+'</time> '+esc(e.message)+'</li>').join('');}
    find('du-history').innerHTML=(next.history||[]).slice().reverse().map(e=>'<li>'+esc(e.at)+' · '+esc(e.version||'Pacote')+' · '+esc(labels[e.phase]||e.phase)+'</li>').join('');
    panel.querySelectorAll('button,input').forEach(el=>{el.disabled=busy;});
  }
  async function poll(rev){
    if(rev!==generation||!panel?.isConnected)return;
    try{const result=await request('',{headers:{'X-Assessment-Background':'1'}});if(rev!==generation)return;
      if(pendingId&&result.id!==pendingId){find('du-phase').textContent='Pacote enviado. Aguardando preparação…';}
      else {pendingId='';render(result);error('');}
    }catch(e){if(rev!==generation)return;error('Sem resposta do dashboard. Durante o reinício isso é esperado; a consulta será repetida. Se aparecer o login, entre novamente e abra esta aba.');}
    if(rev===generation)timer=setTimeout(()=>poll(rev),2500);
  }
  async function upload(e){e.preventDefault();if(busy)return;const file=find('du-file').files[0];if(!file||file.size>256*1024*1024){error('Selecione um ZIP de até 256 MiB.');return;}
    const rev=generation;busy=true;render(state||{});error('');find('du-phase').textContent='Enviando pacote…';
    try{const result=await request('/upload',{method:'POST',binary:true,body:file,headers:{'Content-Type':'application/zip'}});if(rev!==generation)return;pendingId=result.operationId;find('du-upload').reset();}
    catch(e){if(rev===generation)error(e.message);}finally{if(rev===generation){busy=false;if(state)render(state);}}
  }
  async function command(action){if(busy||!state?.id)return;const rev=generation;const password=find('du-password').value;
    if(action!=='cancel'&&!password){error('Informe sua senha para confirmar.');return;}
    if(action!=='cancel'&&!root.confirm(action==='confirm'?'Confirmar a atualização e reiniciar o dashboard?':'Restaurar a versão anterior, preservando o banco atual?'))return;
    busy=true;render(state);error('');
    try{await request('/action',{method:'POST',body:{action,operationId:state.id,adminPassword:password}});if(rev===generation)find('du-phase').textContent='Solicitação enviada. Aguardando o serviço…';}
    catch(e){if(rev===generation)error(e.message);}finally{if(rev===generation){find('du-password').value='';busy=false;}}
  }
  root.DashboardUpdate={mount,stop,labels};
})(window);
