/* Individual accounts. Passwords and temporary credentials are never persisted by the browser. */
(function(root){
  'use strict';
  const $=id=>document.getElementById(id);
  const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon=name=>'<svg aria-hidden="true"><use href="#i-'+name+'"/></svg>';
  const roleName=role=>role==='ADMIN'?'Administrador':'Leitura';
  const statusName=status=>({ACTIVE:'Ativo',FIRST_ACCESS:'Primeiro acesso',DISABLED:'Desativado'})[status] || 'Indisponível';
  const stamp=value=>value&&Number.isFinite(Date.parse(value))?new Date(value).toLocaleString('pt-BR',{dateStyle:'short',timeStyle:'short'}):'Ainda não entrou';
  const initials=name=>String(name||'').trim().split(/\s+/).filter(Boolean).slice(0,2).map(s=>Array.from(s)[0]).join('').toUpperCase();
  const mutationSelector='[data-update-host],[data-update-cancel],[data-machine-actions],[data-machine-action],[data-admin-only],[data-run],[data-settings],[data-host-settings],[data-host-revoke],[data-code-revoke],[data-install-events],[data-create-environment],[data-edit-environment],[data-delete-environment],[data-download-environment],[data-assign-environment],[data-add-machine],[data-enrollment-list],[data-enrollment-download]';
  let ctx,session=null,epoch=0,authMode='login',authBusy=false,users=[],events=[],adminTab='users',adminQuery='',roleFilter='',statusFilter='',adminLoaded=false,adminLoading=null;
  let dialog,dialogForm,dialogMode='',targetId='',dialogRevision=0,dialogBusy=false,returnFocus=null;
  const isAdmin=()=>session?.user?.role==='ADMIN'&&!session.mustChangePassword&&!session.user.mustChangePassword;
  const canInventory=()=>!!session?.authenticated&&!session.mustChangePassword&&!session.user?.mustChangePassword;
  function field(label,name,type='text',value='',attrs=''){
    const id='access-'+name;
    return '<label class="field" for="'+id+'">'+label+'</label><div class="'+(type==='password'?'access-password':'access-input')+'"><input id="'+id+'" name="'+name+'" type="'+type+'" value="'+esc(value)+'" '+attrs+'>'+(type==='password'?'<button type="button" class="access-show" data-show-password="'+id+'" aria-label="Mostrar '+esc(label.toLowerCase())+'" aria-pressed="false">Mostrar</button>':'')+'</div>';
  }
  function errorAt(id,message){$(id).textContent=message;$(id).hidden=false;}
  async function request(path,options={}){
    const provided=options,generation=epoch;
    const opts={credentials:'same-origin',cache:'no-store',...provided,headers:{Accept:'application/json',...provided.headers}};
    if(provided.body!==undefined&&!provided.binary){opts.headers['Content-Type']='application/json';opts.body=JSON.stringify(provided.body);}
    if(opts.method&&opts.method!=='GET'&&session?.csrfToken)opts.headers['X-CSRF-Token']=session.csrfToken;
    delete opts.binary;
    const response=await fetch('/api/'+path,opts);
    if(generation!==epoch)throw new Error('A sessão mudou. Repita a solicitação após entrar.');
    let result;try{result=await response.json();}catch(_){result={error:'Não foi possível concluir a solicitação.'};}
    if(generation!==epoch)throw new Error('A sessão mudou. Repita a solicitação após entrar.');
    if(!response.ok){if(response.status===401&&session)showLogin('Sua sessão terminou. Entre novamente.');const err=new Error(result.error||'Não foi possível concluir a solicitação.');err.status=response.status;throw err;}
    return result;
  }
  function blockProduct(block){document.body.classList.toggle('access-locked',block);$('main-content').inert=block;document.querySelector('.topbar').inert=block;$('side-nav').inert=block;}
  function clearState(){root.DashboardUpdate?.stop();root.InventoryExport?.stop();epoch++;session=null;users=[];events=[];adminLoaded=false;adminLoading=null;adminQuery='';roleFilter='';statusFilter='';closeDialog();closeMenu();$('user-account').hidden=true;$('admin-view').replaceChildren();ctx?.onSignedOut();}
  function showLogin(message=''){
    clearState();blockProduct(true);authMode='login';authBusy=false;renderAuth();if(message)errorAt('login-error',message);
    const revision=epoch;
    request('auth/status').then(status=>{if(revision!==epoch||session)return;if(status.bootstrapRequired){authMode='bootstrap';renderAuth();}}).catch(()=>{if(revision===epoch)errorAt('login-error','Não foi possível verificar o acesso. Atualize a página para tentar novamente.');});
  }
  function renderAuth(){
    const first=authMode==='password',bootstrap=authMode==='bootstrap';
    $('login-layer').hidden=false;
    $('login-form').innerHTML='<div class="login-logo"><span class="brand-mark" aria-hidden="true">J<span>•</span></span><span>Java Assessment<small>ACESSO INDIVIDUAL</small></span></div><span class="access-kicker">'+(first?'PRIMEIRO ACESSO':bootstrap?'CONFIGURAÇÃO INICIAL':'BEM-VINDO DE VOLTA')+'</span><h2>'+(first?'Sua conta, sua senha.':bootstrap?'Seu primeiro administrador.':'Entre no seu inventário.')+'</h2><p>'+(first?'Defina sua senha pessoal para continuar.':bootstrap?'Use a senha administrativa atual para ativar o acesso individual.':'Use seu email e senha para acompanhar o ambiente Java.')+'</p>'+(first?'<div class="access-identity"><span class="access-avatar">'+esc(initials(session?.user?.name))+'</span><div><strong>'+esc(session?.user?.name)+'</strong><span>'+esc(session?.user?.email)+'</span></div></div><input type="email" name="email" value="'+esc(session?.user?.email)+'" autocomplete="username" class="sr-only" tabindex="-1" aria-label="Email da conta" readonly>':'')+(bootstrap?field('Senha administrativa atual','currentPassword','password','','required autocomplete="current-password"')+field('Seu nome','name','text','','required maxlength="120" autocomplete="name"'):'')+(!first?field('Email','email','email','','required maxlength="254" autocomplete="username" autocapitalize="none" spellcheck="false"'):'')+(first?field('Senha temporária','currentPassword','password','','required autocomplete="current-password"'):'')+field(first||bootstrap?'Nova senha':'Senha',first||bootstrap?'newPassword':'password','password','',(first||bootstrap?'required minlength="8" maxlength="128" autocomplete="new-password"':'required autocomplete="current-password" maxlength="256"'))+((first||bootstrap)?'<p class="access-hint" id="password-hint">Use de 8 a 128 caracteres. Uma frase longa funciona bem.</p>'+field('Confirmar nova senha','confirmPassword','password','','required minlength="8" maxlength="128" autocomplete="new-password"'):'')+'<p id="login-error" class="form-error" role="alert" hidden></p><button class="button primary" type="submit" id="login-submit">'+(first?'Salvar senha e entrar':bootstrap?'Ativar minha conta':'Entrar')+icon('back')+'</button>'+(first?'<button type="button" class="access-signout" data-access-logout>Voltar ao login</button>':'')+'<div class="login-note">'+(bootstrap?'Sua conta terá o grupo Administradores.':'Java Assessment · Acesso protegido')+'</div>';
    if(!first){$('access-email').id='login-email';$('login-form').querySelector('label[for="access-email"]').htmlFor='login-email';}if(!first&&!bootstrap){$('access-password').id='password';$('login-form').querySelector('label[for="access-password"]').htmlFor='password';$('login-form').querySelector('[data-show-password="access-password"]').dataset.showPassword='password';}
    requestAnimationFrame(()=>{if(!$('login-layer').hidden)$('login-form').querySelector('input:not([readonly])')?.focus();});
  }
  async function acceptSession(next){
    if(!next?.authenticated||!next.user?.id){showLogin();return;}
    epoch++;session=next;authBusy=false;users=[];events=[];adminLoaded=false;adminLoading=null;closeDialog();closeMenu();
    if(next.mustChangePassword||next.user.mustChangePassword){blockProduct(true);ctx.onSignedOut();authMode='password';renderAuth();return;}
    $('login-form').replaceChildren();$('login-layer').hidden=true;blockProduct(false);renderUser();applyPermissions();await ctx.onSession(next);
  }
  async function submitLogin(event){
    event.preventDefault();if(authBusy)return;authBusy=true;const revision=epoch,form=event.currentTarget,button=$('login-submit'),f=new FormData(form);button.disabled=true;$('login-error').hidden=true;
    try{
      const changing=authMode!=='login';if(changing&&f.get('newPassword')!==f.get('confirmPassword'))throw new Error('As senhas não são iguais. Confira a confirmação.');
      const path=authMode==='bootstrap'?'auth/bootstrap':authMode==='password'?'account/password':'login';
      const body=authMode==='bootstrap'?{currentPassword:f.get('currentPassword'),name:String(f.get('name')).trim(),email:String(f.get('email')).trim(),password:f.get('newPassword')}:authMode==='password'?{currentPassword:f.get('currentPassword'),newPassword:f.get('newPassword')}:{email:String(f.get('email')).trim(),password:f.get('password')};
      const next=await request(path,{method:'POST',body});if(revision!==epoch)return;form.reset();await acceptSession(next);
    }catch(error){if(revision===epoch){form.querySelectorAll('input[type="password"],input[data-password-visible]').forEach(input=>input.value='');errorAt('login-error',authMode==='bootstrap'&&error.status===401?'A senha administrativa atual está incorreta. Use a senha que você usava antes da atualização.':error.message);}}
    finally{if(revision===epoch){authBusy=false;if(button.isConnected)button.disabled=false;}}
  }
  async function start(){blockProduct(true);try{const next=await request('session');if(next.authenticated)await acceptSession(next);else showLogin();}catch(_){showLogin();}}
  async function signOut(){const active=!!session,token=session?.csrfToken;clearState();blockProduct(true);authMode='login';renderAuth();authBusy=true;$('login-submit').disabled=true;if(active){try{await fetch('/api/logout',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json','X-CSRF-Token':token||''},body:'{}'});}catch(_){errorAt('login-error','Não foi possível encerrar a sessão no servidor. Tente sair novamente após entrar.');}}authBusy=false;if($('login-submit'))$('login-submit').disabled=false;}
  function renderUser(){
    const user=session?.user;if(!user)return;$('user-account').hidden=false;$('user-initials').textContent=initials(user.name);$('user-name').textContent=user.name;$('user-role').textContent=roleName(user.role);$('user-toggle').setAttribute('aria-label','Conta de '+user.name+', '+roleName(user.role));$('profile-email').textContent=user.email;$('profile-name').textContent=user.name;
  }
  function closeMenu(){if(!$('user-menu'))return;$('user-menu').hidden=true;$('user-toggle').setAttribute('aria-expanded','false');}
  function applyPermissions(){
    const admin=isAdmin();document.body.dataset.accessRole=admin?'admin':'reader';
    document.querySelectorAll('[data-admin-only]').forEach(el=>el.hidden=!admin);
    if(!admin){document.querySelectorAll('#special-view '+mutationSelector.split(',').join(',#special-view ')+',#view-actions '+mutationSelector.split(',').join(',#view-actions ')).forEach(el=>el.remove());$('empty-assessment').hidden=true;}
  }
  function renderAdministration(){
    if(!isAdmin())return;
    if(adminTab==='update'&&$('dashboard-update-panel'))return;
    if(adminTab==='export'&&$('inventory-export-panel'))return;
    root.InventoryExport?.stop();
    root.DashboardUpdate?.stop();
    const active=users.filter(u=>u.status!=='DISABLED'),pending=users.filter(u=>u.status==='FIRST_ACCESS');
    $('admin-view').innerHTML='<div class="access-admin"><header class="access-admin-header"><div><span class="access-kicker">PESSOAS E PERMISSÕES</span><h1>Administração</h1><p>O acesso certo para cada pessoa.</p></div><button type="button" class="button primary" data-add-user>+ Adicionar usuário</button></header><div class="access-stats"><div><strong>'+users.length+'</strong><span>usuários cadastrados</span></div><div><strong>'+active.filter(u=>u.role==='ADMIN').length+'</strong><span>administradores habilitados</span></div><div><strong>'+pending.length+'</strong><span>aguardando primeiro acesso</span></div></div><div class="access-tabs" role="tablist" aria-label="Administração">'+[['users','Usuários'],['groups','Grupos'],['activity','Atividade'],['export','Exportar inventário'],['update','Atualização do dashboard']].map(([id,label])=>'<button type="button" role="tab" id="access-tab-'+id+'" aria-controls="access-admin-panel" aria-selected="'+(adminTab===id)+'" tabindex="'+(adminTab===id?'0':'-1')+'" data-admin-tab="'+id+'">'+label+'</button>').join('')+'</div><section id="access-admin-panel" role="tabpanel" aria-labelledby="access-tab-'+adminTab+'" tabindex="0">'+(!adminLoaded?'<div class="dash-empty" role="status">Carregando acesso…</div>':adminTab==='users'?userTable():adminTab==='groups'?groupCards():adminTab==='export'?'<div id="inventory-export-panel"></div>':adminTab==='update'?'<div id="dashboard-update-panel"></div>':activityTable())+'</section><p id="access-admin-error" class="form-error" role="alert" hidden></p></div>';    if(adminTab==='update')root.DashboardUpdate?.mount($('dashboard-update-panel'));
    if(adminTab==='export')root.InventoryExport?.mount($('inventory-export-panel'));
  }
  function userTable(){
    const q=adminQuery.trim().toLocaleLowerCase('pt-BR'),filtered=users.filter(u=>(!q||(u.name+' '+u.email).toLocaleLowerCase('pt-BR').includes(q))&&(!roleFilter||u.role===roleFilter)&&(!statusFilter||u.status===statusFilter));
    return '<div class="access-users-panel"><div class="access-filters"><label class="access-search">'+icon('search')+'<input type="search" id="access-user-search" placeholder="Buscar nome ou email" aria-label="Buscar usuários por nome ou email" value="'+esc(adminQuery)+'"></label><label><span class="sr-only">Filtrar grupo</span><select id="access-role-filter"><option value="">Todos os grupos</option><option value="ADMIN" '+(roleFilter==='ADMIN'?'selected':'')+'>Administradores</option><option value="READER" '+(roleFilter==='READER'?'selected':'')+'>Leitura</option></select></label><label><span class="sr-only">Filtrar status</span><select id="access-status-filter"><option value="">Todos os status</option>'+['ACTIVE','FIRST_ACCESS','DISABLED'].map(s=>'<option value="'+s+'" '+(statusFilter===s?'selected':'')+'>'+statusName(s)+'</option>').join('')+"</select></label></div><div class=\"table-wrap\"><table class=\"access-user-table\"><thead><tr><th>Pessoa</th><th>Grupo</th><th>Estado</th><th>Último acesso</th><th><span class=\"sr-only\">Ações</span></th></tr></thead><tbody>"+filtered.map(u=>'<tr><td><div class="access-person"><span class="access-avatar '+(u.role==='ADMIN'?'admin':'')+'">'+esc(initials(u.name))+'</span><div><strong>'+esc(u.name)+(u.id===session.user.id?'<small class="access-you">Você</small>':'')+'</strong><span>'+esc(u.email)+'</span></div></div></td><td><span class="access-role-badge '+(u.role==='ADMIN'?'admin':'')+'">'+(u.role==='ADMIN'?'Administradores':'Leitura')+'</span></td><td><span class="access-status '+esc(u.status.toLowerCase())+'"><i></i>'+statusName(u.status)+'</span></td><td><time>'+esc(stamp(u.lastLoginAt))+'</time></td><td><button type="button" class="text-button" data-edit-user="'+esc(u.id)+'" aria-label="Editar '+esc(u.name)+'">Gerenciar →</button></td></tr>').join('')+'</tbody></table></div>'+(!filtered.length?'<div class="dash-empty">Nenhum usuário encontrado. Ajuste a busca ou os filtros.</div>':'')+'<footer class="access-table-footer">'+filtered.length+' de '+users.length+' usuários<span>Contas individuais · dois grupos fixos</span></footer></div>';
  }
  function groupCards(){return '<div class="access-group-grid">'+[['ADMIN','Administradores','Controle do ambiente e dos acessos.',['Consultar e exportar inventário','Executar coletas e administrar agentes','Gerenciar ambientes, usuários e senhas']],['READER','Leitura','Visibilidade para acompanhar e analisar.',['Consultar máquinas e aplicações','Filtrar ambientes e exportar CSV','Gerenciar a própria senha']]].map(([role,title,description,rights])=>{const members=users.filter(u=>u.role===role&&u.status!=='DISABLED');return '<article class="access-group-card"><span class="access-group-symbol">'+icon(role==='ADMIN'?'shield':'eye')+'</span><span class="access-role-badge '+(role==='ADMIN'?'admin':'')+'">Grupo fixo</span><h2>'+title+'</h2><p>'+description+'</p><ul>'+rights.map(r=>'<li>'+icon('check')+esc(r)+'</li>').join('')+'</ul><footer><strong>'+members.length+' '+(members.length===1?'membro habilitado':'membros habilitados')+'</strong><button type="button" class="text-button" data-group-users="'+role+'">Ver usuários →</button></footer></article>';}).join('')+'</div>';}
  function actionName(action){return ({LOGIN:'Entrou no dashboard',MACHINE_DELETED:'Excluiu uma máquina',LOGOUT:'Saiu do dashboard',LOGIN_FAILED:'Tentativa de entrada não concluída',USER_CREATED:'Criou um usuário',USER_UPDATED:'Atualizou um usuário',USER_DISABLED:'Desativou um usuário',USER_ENABLED:'Reativou um usuário',PASSWORD_RESET:'Redefiniu o acesso',PASSWORD_CHANGED:'Alterou a própria senha',BOOTSTRAP:'Ativou o primeiro administrador',ROLE_CHANGED:'Alterou um grupo',GROUP_CHANGED:'Alterou um grupo',REAUTH_FAILED:'Não confirmou a identidade',PASSWORD_CHANGE_FAILED:'Tentativa de troca de senha não concluída',LOCAL_ADMIN_RECOVERY:'Recuperou o acesso localmente',INITIAL_ADMIN_PROVISIONED:'Provisionou o administrador'})[action]||String(action||'Evento').replaceAll('_',' ').toLowerCase();}
  function activityTable(){return '<section class="access-activity"><header><h2>Quem fez o quê.</h2><p>Os 200 eventos mais recentes de acesso e administração.</p></header><ol>'+events.map(e=>'<li><span class="access-event-icon">'+icon('clock')+'</span><div><strong>'+esc(e.actorName||'Acesso não autenticado')+'</strong><p>'+esc(actionName(e.action))+(e.targetName?' · '+esc(e.targetName):'')+'</p></div><time>'+esc(stamp(e.at))+'</time></li>').join('')+'</ol>'+(!events.length?'<div class="dash-empty">Os eventos de acesso aparecerão aqui.</div>':'')+'</section>';}
  async function refreshAdministration(quiet=false){
    if(!isAdmin())return false;
    if(adminLoading){const result=await adminLoading;if(result&&!quiet&&isAdmin())renderAdministration();return result;}
    const revision=epoch;
    adminLoading=(async()=>{
      try {
        const [u,a]=await Promise.all([request('admin/users',{headers:quiet?{'X-Assessment-Background':'1'}:{}}),request('admin/audit',{headers:quiet?{'X-Assessment-Background':'1'}:{}})]);
        if(revision!==epoch||!isAdmin())return false;
        users=u.users||[];events=a.events||[];adminLoaded=true;
        if(!quiet||!$('admin-view').contains(document.activeElement))renderAdministration();
        return true;
      } catch(error){
        if(revision===epoch&&isAdmin()){if(!$('access-admin-error'))renderAdministration();errorAt('access-admin-error',error.message);}
        return false;
      } finally {if(revision===epoch)adminLoading=null;}
    })();return adminLoading;
  }
  function lastAdministrator(user){return user.role==='ADMIN'&&user.status!=='DISABLED'&&users.filter(u=>u.role==='ADMIN'&&u.status!=='DISABLED').length<=1;}
  function reauthField(){return '<div class="access-reauth"><strong>Confirme sua identidade</strong><p>Informe sua senha para autorizar esta alteração.</p><input type="text" autocomplete="username" value="'+esc(session?.user?.email)+'" class="sr-only" readonly tabindex="-1" aria-label="Email do administrador">'+field('Sua senha','adminPassword','password','','required autocomplete="current-password"')+'</div>';}
  function openDialog(title,mode,content,submit='Salvar alterações',id=''){
    closeMenu();closeDialog();returnFocus=document.activeElement;dialogMode=mode;targetId=id;dialogBusy=false;dialogRevision++;$('access-dialog-title').textContent=title;$('access-dialog-body').innerHTML=content;$('access-dialog-error').hidden=true;$('access-dialog-submit').textContent=submit;$('access-dialog-submit').hidden=!submit;$('access-dialog-submit').disabled=false;dialog.querySelectorAll('[data-access-close]').forEach(b=>b.disabled=false);dialog.showModal();
  }
  function closeDialog(){dialogRevision++;dialogBusy=false;dialogMode='';targetId='';if(dialog?.open)dialog.close();if($('access-dialog-body'))$('access-dialog-body').replaceChildren();if($('access-dialog-error')){$('access-dialog-error').textContent='';$('access-dialog-error').hidden=true;}returnFocus=null;}
  function openCreate(){if(!isAdmin())return;openDialog('Adicionar usuário','create','<p>Uma senha temporária válida por 24 horas será exibida ao concluir. No primeiro acesso, a pessoa define sua própria senha.</p>'+field('Nome','name','text','','required maxlength="120" autocomplete="off"')+field('Email','email','email','','required maxlength="254" autocomplete="off" autocapitalize="none" spellcheck="false"')+'<label class="field" for="access-role">Grupo</label><select id="access-role" name="role"><option value="READER">Leitura</option><option value="ADMIN">Administradores</option></select><p class="access-hint">Leitura permite consultar e exportar o inventário.</p>'+reauthField(),'Criar usuário');}
  function openEdit(id){
    if(!isAdmin())return;const user=users.find(u=>u.id===id);if(!user)return;const last=lastAdministrator(user);
    openDialog('Gerenciar usuário','edit',field('Nome','name','text',user.name,'required maxlength="120" autocomplete="off"')+field('Email','email','email',user.email,'required maxlength="254" autocomplete="off" autocapitalize="none" spellcheck="false"')+'<label class="field" for="access-role">Grupo</label><select id="access-role" name="role" '+(last?'disabled':'')+'><option value="READER" '+(user.role==='READER'?'selected':'')+'>Leitura</option><option value="ADMIN" '+(user.role==='ADMIN'?'selected':'')+'>Administradores</option></select><label class="checkbox-field access-enabled"><input type="checkbox" name="enabled" '+(user.status!=='DISABLED'?'checked ':'')+(last?'disabled':'')+'><span><strong>Conta habilitada</strong><small>Desativar encerra as sessões e impede novos acessos.</small></span></label>'+(last?'<div class="access-callout">Esta é a última conta de administrador habilitada. Crie outro administrador antes de alterar o grupo ou desativá-la.</div>':'')+'<p class="access-hint">Alterar email, grupo ou habilitação encerra as sessões dessa pessoa.</p><button type="button" class="text-button access-reset" data-reset-user="'+esc(user.id)+'">Gerar nova senha temporária →</button>'+reauthField(),'Salvar alterações',user.id);
  }
  function openReset(id){if(!isAdmin())return;const user=users.find(u=>u.id===id);if(!user)return;openDialog('Redefinir acesso','reset','<div class="access-identity"><span class="access-avatar">'+esc(initials(user.name))+'</span><div><strong>'+esc(user.name)+'</strong><span>'+esc(user.email)+'</span></div></div><p>As sessões anteriores serão encerradas. A nova senha temporária vale por 24 horas e exige troca no próximo acesso.</p>'+(user.status==='DISABLED'?'<div class="access-callout">A conta está desativada. Habilite-a em Gerenciar usuário para permitir o acesso.</div>':'')+reauthField(),'Gerar senha temporária',id);}
  function showSecret(result){
    if(!result.temporaryPassword)throw new Error('O servidor não retornou a senha temporária. Redefina o acesso para gerar outra.');
    openDialog('Acesso pronto para compartilhar','secret','<div class="access-success">'+icon('check')+'<div><strong>'+esc(result.user?.name)+'</strong><span>'+esc(result.user?.email)+'</span></div></div><p>Copie agora e entregue à pessoa por um canal apropriado. A senha não ficará disponível nesta tela novamente.</p><label class="field" for="access-temporary">Senha temporária</label><div class="access-secret"><input id="access-temporary" type="text" readonly autocomplete="off" spellcheck="false" value="'+esc(result.temporaryPassword)+'"><button type="button" class="button" data-copy-temporary>Copiar</button></div><div class="access-callout">Válida até <strong>'+esc(stamp(result.expiresAt))+'</strong>. A troca será obrigatória no primeiro acesso.</div><p id="access-copy-status" class="access-hint" role="status"></p>','Concluir');
  }
  function openAccount(){const user=session?.user;if(!canInventory())return;openDialog('Minha conta','account','<div class="access-identity large"><span class="access-avatar">'+esc(initials(user.name))+'</span><div><strong>'+esc(user.name)+'</strong><span>'+esc(user.email)+'</span></div></div><dl class="details-list"><div><dt>Grupo</dt><dd>'+roleName(user.role)+'</dd></div><div><dt>Último acesso</dt><dd>'+esc(stamp(user.lastLoginAt))+'</dd></div></dl><p class="access-hint">Para alterar seu nome, email ou grupo, procure um administrador.</p><button type="button" class="button" data-account-password>Trocar minha senha</button>','Fechar');}
  function openPassword(){if(!canInventory())return;openDialog('Trocar minha senha','password','<p>Use uma senha pessoal de 8 a 128 caracteres. As outras sessões da sua conta serão encerradas.</p><input type="email" autocomplete="username" value="'+esc(session.user.email)+'" class="sr-only" readonly tabindex="-1" aria-label="Email da conta">'+field('Senha atual','currentPassword','password','','required autocomplete="current-password"')+field('Nova senha','newPassword','password','','required minlength="8" maxlength="128" autocomplete="new-password"')+field('Confirmar nova senha','confirmPassword','password','','required minlength="8" maxlength="128" autocomplete="new-password"'),'Salvar nova senha');}
  async function submitDialog(event){
    event.preventDefault();if(dialogBusy)return;if(['secret','account'].includes(dialogMode)){closeDialog();return;}
    const mode=dialogMode,id=targetId,revision=dialogRevision,generation=epoch,f=new FormData(dialogForm),user=users.find(u=>u.id===id);dialogBusy=true;$('access-dialog-submit').disabled=true;$('access-dialog-error').hidden=true;
    try{
      let result;
      if(mode==='password'){if(f.get('newPassword')!==f.get('confirmPassword'))throw new Error('As senhas não são iguais.');result=await request('account/password',{method:'POST',body:{currentPassword:f.get('currentPassword'),newPassword:f.get('newPassword')}});}
      else if(!isAdmin())throw new Error('Esta ação requer um administrador.');
      else if(mode==='create')result=await request('admin/users',{method:'POST',body:{name:String(f.get('name')).trim(),email:String(f.get('email')).trim(),role:f.get('role')||'READER',adminPassword:f.get('adminPassword')}});
      else if(mode==='reset')result=await request('admin/users/reset',{method:'POST',body:{id,adminPassword:f.get('adminPassword')}});
      else if(mode==='edit'){const last=lastAdministrator(user);result=await request('admin/users',{method:'PUT',body:{id,name:String(f.get('name')).trim(),email:String(f.get('email')).trim(),role:last?user.role:f.get('role'),enabled:last?true:f.has('enabled'),adminPassword:f.get('adminPassword')}});}
      if(revision!==dialogRevision||generation!==epoch)return;
      dialogForm.reset();closeDialog();
      if(mode==='password'){await acceptSession(result);ctx.notify('Senha alterada. As outras sessões foram encerradas.');return;}
      if((mode==='edit'&&id===session.user.id)||(mode==='reset'&&id===session.user.id)){
        if(mode==='reset'){showSecret(result);/* The secret is retained only in this open dialog until the user finishes. */session=null;blockProduct(true);ctx.onSignedOut();$('user-account').hidden=true;users=[];events=[];$('admin-view').replaceChildren();dialog.dataset.relogin='true';return;}
        await start();return;
      }
      if(mode==='create'||mode==='reset')showSecret(result);else ctx.notify('Usuário atualizado.');
      await refreshAdministration();
    }catch(error){if(revision===dialogRevision&&generation===epoch){dialogForm.querySelectorAll('input[type="password"],input[data-password-visible]').forEach(input=>input.value='');errorAt('access-dialog-error',error.message);}}
    finally{if(revision===dialogRevision){dialogBusy=false;$('access-dialog-submit').disabled=false;}}
  }
  function init(context){
    ctx=context;dialog=document.createElement('dialog');dialog.id='access-dialog';dialog.className='access-dialog';dialog.setAttribute('aria-labelledby','access-dialog-title');dialog.innerHTML='<form id="access-dialog-form"><header class="modal-header"><div><span class="eyebrow">ACESSO INDIVIDUAL</span><h2 id="access-dialog-title"></h2></div><button type="button" class="icon-button" data-access-close aria-label="Fechar diálogo">'+icon('close')+'</button></header><div class="modal-body" id="access-dialog-body"></div><p class="form-error" id="access-dialog-error" role="alert" hidden></p><footer class="modal-footer"><button type="button" class="button" data-access-close>Fechar</button><button type="submit" class="button primary" id="access-dialog-submit">Salvar</button></footer></form>';document.body.append(dialog);dialogForm=$('access-dialog-form');dialogForm.addEventListener('submit',submitDialog);dialog.addEventListener('close',()=>{if(dialog.open)return;const relogin=dialog.dataset.relogin==='true';delete dialog.dataset.relogin;closeDialog();if(relogin)showLogin('Use sua nova senha temporária para entrar.');});
    $('login-form').addEventListener('submit',submitLogin);
    $('user-toggle').onclick=()=>{const open=$('user-menu').hidden;$('user-menu').hidden=!open;$('user-toggle').setAttribute('aria-expanded',String(open));};
    document.addEventListener('click',async event=>{
      const target=event.target;
      if(!target.closest('#user-account'))closeMenu();
      const show=target.closest('[data-show-password]');if(show){const input=$(show.dataset.showPassword),visible=input.type==='password';input.type=visible?'text':'password';input.toggleAttribute('data-password-visible',visible);show.textContent=visible?'Ocultar':'Mostrar';show.setAttribute('aria-pressed',String(visible));show.setAttribute('aria-label',(visible?'Ocultar ':'Mostrar ')+input.name);return;}
      if(target.closest('[data-access-logout]')){await signOut();return;}
      if(target.closest('[data-access-close]')){closeDialog();return;}
      if(target.closest('[data-account]')){openAccount();return;}
      if(target.closest('[data-account-password]')){openPassword();return;}
      if(target.closest('[data-add-user]')){openCreate();return;}
      const edit=target.closest('[data-edit-user]');if(edit){openEdit(edit.dataset.editUser);return;}
      const reset=target.closest('[data-reset-user]');if(reset){openReset(reset.dataset.resetUser);return;}
      const tab=target.closest('[data-admin-tab]');if(tab&&isAdmin()){adminTab=tab.dataset.adminTab;renderAdministration();$('access-tab-'+adminTab).focus();return;}
      const group=target.closest('[data-group-users]');if(group){roleFilter=group.dataset.groupUsers;statusFilter='';adminQuery='';adminTab='users';renderAdministration();return;}
      if(target.closest('[data-copy-temporary]')){const revision=dialogRevision,input=$('access-temporary');if(!input)return;try{await navigator.clipboard.writeText(input.value);if(revision===dialogRevision)$('access-copy-status').textContent='Senha copiada. Compartilhe somente com essa pessoa.';}catch(_){if(revision===dialogRevision){input.select();$('access-copy-status').textContent='Selecione e copie a senha manualmente.';}}}
    });
    document.addEventListener('click',event=>{if(!isAdmin()&&event.target.closest(mutationSelector)){event.preventDefault();event.stopImmediatePropagation();}},true);
    document.addEventListener('input',event=>{if(event.target.id==='access-user-search'){const position=event.target.selectionStart;adminQuery=event.target.value;$('access-admin-panel').innerHTML=userTable();const input=$('access-user-search');input.focus();try{input.setSelectionRange(position,position);}catch(_){}}});
    document.addEventListener('change',event=>{if(event.target.id==='access-role-filter'){roleFilter=event.target.value;$('access-admin-panel').innerHTML=userTable();$('access-role-filter').focus();}if(event.target.id==='access-status-filter'){statusFilter=event.target.value;$('access-admin-panel').innerHTML=userTable();$('access-status-filter').focus();}});
    document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!$('user-menu').hidden){closeMenu();$('user-toggle').focus();}const tab=event.target.closest('[data-admin-tab]');if(tab&&['ArrowRight','ArrowLeft','Home','End'].includes(event.key)){event.preventDefault();const ids=['users','groups','activity','export','update'];adminTab=event.key==='Home'?'users':event.key==='End'?'update':ids[(ids.indexOf(adminTab)+(event.key==='ArrowRight'?1:ids.length-1))%ids.length];renderAdministration();$('access-tab-'+adminTab).focus();}});
  }
  root.AssessmentAccess={init,start,request,showLogin,isAdmin,canInventory,applyPermissions,renderAdministration,refreshAdministration,closeMenu,getUser:()=>session?.user||null,getRevision:()=>epoch};
})(window);
