/* Analysis transfer: a ZIP download, independent of report filters and agent credentials. */
(function(root){
  'use strict';
  let panel=null,controller=null,generation=0;
  function stop(){generation++;controller?.abort();controller=null;panel=null;}
  function mount(target){
    if(!target)return;
    stop();panel=target;
    target.innerHTML='<section class="inventory-export"><span class="access-kicker">DADOS PARA ANÁLISE</span><h2>Exportar inventário</h2><p>Baixe um pacote ZIP com as informações técnicas da coleta para realizar levantamentos e análises em outra aplicação.</p><div class="inventory-export-details"><h3>O que vai no pacote</h3><p>Ambientes, máquinas, sistemas operacionais, recursos de hardware, instalações e versões Java, processos, aplicações, servidores, bibliotecas e datasources JDBC. Inclui nomes e caminhos dos arquivos JAR, versões dos drivers, destinos de banco configurados, associações, evidências e observações da coleta.</p><h3>Proteção dos dados de acesso</h3><p>O pacote não inclui cadastros de usuários, senhas, tokens, chaves privadas ou certificados. Comandos completos de processos e configurações operacionais também ficam de fora.</p><p>Nomes de máquinas, endereços IP, caminhos de arquivos e destinos de banco podem estar presentes para identificar os recursos. Compartilhe o pacote apenas com quem estiver autorizado a analisar essa infraestrutura.</p></div><form id="inventory-export-form"><label class="field" for="inventory-export-label">Identificação do levantamento ou cliente <span>(opcional)</span></label><input id="inventory-export-label" type="text" maxlength="120" placeholder="Ex.: Cliente — Produção" autocomplete="off"><p class="access-hint">Exporta todo o inventário atual, independentemente dos filtros das outras telas. Não é um backup do dashboard.</p><button id="inventory-export-submit" type="submit" class="button primary">Exportar inventário em ZIP</button><p id="inventory-export-status" role="status" aria-live="polite"></p><p id="inventory-export-error" class="form-error" role="alert" hidden></p></form></section>';
    target.querySelector('form').addEventListener('submit',download);
  }
  async function download(event){
    event.preventDefault();if(controller||!panel||!root.AssessmentAccess.isAdmin())return;
    const owner=panel,revision=root.AssessmentAccess.getRevision(),requestGeneration=generation;
    const button=owner.querySelector('#inventory-export-submit'),input=owner.querySelector('#inventory-export-label'),status=owner.querySelector('#inventory-export-status'),error=owner.querySelector('#inventory-export-error');
    const active=()=>generation===requestGeneration&&panel===owner&&root.AssessmentAccess.getRevision()===revision&&root.AssessmentAccess.isAdmin();
    const abort=new AbortController();controller=abort;button.disabled=true;input.disabled=true;error.hidden=true;status.textContent='Preparando o pacote ZIP. Aguarde a conclusão…';
    try{
      const response=await fetch('/api/admin/inventory-export?'+new URLSearchParams({label:input.value.trim()}),{credentials:'same-origin',cache:'no-store',headers:{Accept:'application/zip'},signal:abort.signal});
      if(!active())return;
      if(!response.ok){let message='Não foi possível exportar o inventário.';try{message=(await response.json()).error||message;}catch(_){}if(response.status===401){root.AssessmentAccess.showLogin('Sua sessão terminou. Entre novamente.');return;}throw new Error(message);}
      if(!response.headers.get('Content-Type')?.startsWith('application/zip'))throw new Error('O servidor não retornou um pacote ZIP válido.');
      const blob=await response.blob();if(!active())return;
      const supplied=response.headers.get('Content-Disposition')?.match(/filename="(java-assessment-inventario-[0-9-]+\.zip)"/),filename=supplied?supplied[1]:'java-assessment-inventario.zip';
      const url=URL.createObjectURL(blob),anchor=document.createElement('a');
      try{anchor.href=url;anchor.download=filename;document.body.appendChild(anchor);anchor.click();}finally{anchor.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);}
      status.textContent='Pacote gerado. O download foi iniciado; confira a pasta de downloads do navegador.';
    }catch(failure){if(active()&&failure.name!=='AbortError'){status.textContent='';error.textContent=failure.message;error.hidden=false;}}
    finally{if(controller===abort)controller=null;if(active()){button.disabled=false;input.disabled=false;}}
  }
  root.InventoryExport={mount,stop};
})(window);
