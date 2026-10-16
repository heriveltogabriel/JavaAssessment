/* Inventory reports: a read-only, revision-bound view of the latest inventory. */
(function (global) {
  'use strict';
  const generalFields = [
    ['machine', 'Máquina', 'Nome ou parte do nome'], ['os', 'Sistema operacional', 'Ex.: Red Hat'],
    ['architecture', 'Arquitetura', 'Ex.: x86_64'], ['javaVersion', 'Versão Java', 'Ex.: 17 ou 17.0.12'],
    ['serverType', 'Servidor Java', 'Ex.: WebLogic'], ['serverVersion', 'Versão do servidor', 'Versão exata']
  ];
  const jdbcFields = [
    ['datasource', 'Datasource / nome JNDI', 'Ex.: jdbc/Pedidos'], ['databaseHost', 'Host/IP do banco (configurado)', 'DNS, IPv4 ou IPv6'], ['databasePort', 'Porta do banco (configurada)', 'Ex.: 1521'], ['databaseName', 'Banco / serviço / SID (configurado)', 'Ex.: pedidos'], ['databaseType', 'Banco de dados', 'Ex.: PostgreSQL'],
    ['driver', 'Driver JDBC', 'Nome ou classe do driver'], ['driverVersion', 'Versão do driver', 'Versão exata'], ['databaseVersion', 'Versão do banco', 'Versão exata informada'],
    ['driverClass', 'Classe do driver', 'Ex.: org.postgresql.Driver'], ['driverReference', 'Referência do driver', 'Nome ou referência registrada'],
    ['serverName', 'Servidor associado', 'Ex.: AdminServer'], ['applicationName', 'Aplicação associada', 'Nome da aplicação'],
    ['resourceType', 'Tipo de recurso', 'Ex.: javax.sql.DataSource'], ['datasourceState', 'Estado observado', 'Estado informado pelo inventário']
  ];
  const fields = generalFields.concat(jdbcFields);
  const descriptions = {
    machines: 'Uma linha por máquina', java: 'Uma linha por instalação Java',
    servers: 'Uma linha por servidor', jdbc: 'Uma linha por datasource',
    environments: 'Totais por ambiente'
  };
  const modelLabels = {machines:'Máquinas',java:'Java',servers:"Servidores de aplicação Java",jdbc:'JDBC',environments:'Resumo por ambiente'};
  const symbols = {machines: '▤', java: '◉', servers: '▥', jdbc: '≋', environments: '▦'};
  const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const text = value => value == null || value === '' ? 'Não identificado' : Array.isArray(value) ? value.join(' · ') : String(value);
  const initial = () => ({model: 'machines', environmentId: '', columns: [], page: 1, pageSize: 20});
  let deps, options, draft = initial(), applied, result, loading = false, exporting = false;
  let sequence = 0, epoch = 0, error = '', exportError = '', collapsed = true, columnOpen = false, mounted;
  const mount = () => document.getElementById('reports-view');
  const visible = () => { const el = mount(); return el && !el.hidden && !el.closest('[hidden]'); };
  const model = () => options && options.models.find(item => item.key === draft.model);
  const equalQuery = () => applied && JSON.stringify({...draft, page: 1}) === JSON.stringify({...applied, page: 1});
  function parameters(query) {
    const values = new URLSearchParams();
    Object.keys(query).forEach(key => { const value = Array.isArray(query[key]) ? query[key].join(',') : query[key]; if (value !== '' && value != null) values.set(key, value); });
    return values;
  }
  function init(dependencies) {
    deps = dependencies;
    const el = mount();
    if (el && mounted !== el) {
      el.addEventListener('click', click);
      el.addEventListener('input', input);
      el.addEventListener('change', change);
      el.addEventListener('submit', submit);
      mounted = el;
    }
  }
  function clear() {
    epoch++; sequence++; options = undefined; draft = initial(); applied = result = undefined;
    loading = exporting = false; error = exportError = ''; collapsed = true; columnOpen = false;
    if (mount()) mount().replaceChildren();
  }
  function enter() {collapsed = true; columnOpen = false;}
  function render() {
    if (deps && visible()) paint();
  }
  async function refresh(quiet) {
    if (!deps || !visible()) return false;
    const token = ++sequence, session = epoch;
    loading = true; error = exportError = ''; paint();
    try {
      if (!options || !quiet) {
        const firstOptions = !options;
        const received = await deps.api('reports/options');
        if (session !== epoch || token !== sequence) return false;
        options = received;
        if (!options.models.some(item => item.key === draft.model)) draft.model = options.models[0].key;
        if (firstOptions && !draft.columns.length) draft.columns = model().defaults.slice();
      }
      if (session !== epoch || token !== sequence) return false;
      // A refresh repeats the displayed query; unsubmitted filter edits remain a draft.
      const request = applied ? {...applied, columns: applied.columns.slice()} : {...draft, columns: draft.columns.slice()};
      const received = await deps.api('reports?' + parameters(request));
      if (session !== epoch || token !== sequence) return false;
      applied = request; result = received;
      return true;
    } catch (failure) {
      if (session !== epoch || token !== sequence) return false;
      error = failure.message || 'Não foi possível carregar os relatórios.';
      result = undefined;
      return false;
    } finally {
      if (session === epoch && token === sequence) { loading = false; if (visible()) paint(); }
    }
  }
  async function query(page) {
    if (!visible() || !options) return;
    const token = ++sequence, session = epoch, draftAtStart = JSON.stringify(draft);
    const request = page ? {...applied, page, columns: applied.columns.slice()} : {...draft, page: 1, columns: draft.columns.slice()};
    if (!request.columns.length) { error = 'Selecione pelo menos uma coluna.'; paint(); return; }
    loading = true; error = exportError = ''; paint();
    try {
      const received = await deps.api('reports?' + parameters(request));
      if (session !== epoch || token !== sequence) return;
      applied = request; result = received;
      if (!page && JSON.stringify(draft) === draftAtStart) draft = {...request, columns: request.columns.slice()};
      else draft.page = page;
    } catch (failure) {
      if (session !== epoch || token !== sequence) return;
      error = failure.message || 'Não foi possível gerar o relatório. Tente novamente.';
      result = undefined;
    } finally {
      if (session === epoch && token === sequence) { loading = false; if (visible()) paint(); }
    }
  }
  function paint() {
    const el = mount(); if (!el || !visible()) return;
    el.classList.add('reports-workspace');
    const selected = model(), changed = result && !equalQuery();
    el.innerHTML = '<header class="reports-heading"><div><span class="reports-kicker">INVENTÁRIO · RELATÓRIOS</span><h2>Seu ambiente, em perspectiva.</h2><p>Escolha um modelo, refine os filtros e leve o resumo com você.</p></div><div class="reports-export"><button type="button" class="button" data-report-export="csv" '+(!result || loading || exporting || changed ? 'disabled' : '')+'>Exportar CSV</button><button type="button" class="button primary" data-report-export="pdf" '+(!result || loading || exporting || changed ? 'disabled' : '')+'>Exportar PDF</button></div></header>' +
      (options ? '<div class="reports-models" role="group" aria-label="Modelo do relatório">'+ options.models.map(item => '<button type="button" data-report-model="'+esc(item.key)+'" aria-pressed="'+(item.key === draft.model)+'" '+(exporting ? 'disabled' : '')+'><span class="reports-model-symbol" aria-hidden="true">'+esc(symbols[item.key] || '▤')+'</span><strong>'+esc(modelLabels[item.key] || item.label)+'</strong><small>'+esc(descriptions[item.key] || '')+'</small></button>').join('')+'</div>'+ filters(selected) : '') +
      '<div class="reports-feedback" aria-live="polite">'+(exporting ? '<p class="reports-message">Preparando o arquivo com todos os resultados filtrados…</p>' : '')+(error ? '<div class="reports-error" role="alert">'+esc(error)+' <button type="button" class="text-button" data-report-retry>Tentar novamente</button></div>' : '')+(exportError ? '<div class="reports-error" role="alert">'+esc(exportError)+' <button type="button" class="text-button" data-report-refresh>Atualizar resultados</button></div>' : '')+'</div>'+ 
      '<div id="reports-results" aria-busy="'+loading+'">'+(loading ? '<div class="reports-loading" role="status"><span class="reports-pulse"></span>Preparando seu relatório…</div>' : result ? results() : '')+'</div>';
  }
  function filterInput([key, label, placeholder]) {
    const suggestions = (Array.isArray(options.suggestions?.[key]) ? options.suggestions[key] : []).filter(value => typeof value === 'string').slice(0, 100);
    return '<label class="field">'+esc(label)+'<input name="'+esc(key)+'" value="'+esc(draft[key] || '')+'" placeholder="'+esc(placeholder)+'" maxlength="200" autocomplete="off" list="reports-suggest-'+esc(key)+'"></label><datalist id="reports-suggest-'+esc(key)+'">'+suggestions.map(value => '<option value="'+esc(value)+'"></option>').join('')+'</datalist>';
  }
  function filters(selected) {
    return '<form id="reports-form" class="reports-filters"><div class="reports-filter-heading"><button type="button" class="text-button" data-report-toggle aria-expanded="'+!collapsed+'" aria-controls="reports-filter-fields"><span aria-hidden="true">'+(collapsed?'▸':'▾')+'</span> Filtros do relatório</button><span>Textos por trecho · Java por versão ou família</span></div><div id="reports-filter-fields" '+(collapsed?'hidden':'')+'><fieldset class="reports-filter-group"><legend>Máquinas e servidores</legend><div class="reports-filter-grid"><label class="field">Ambiente<select name="environmentId"><option value="">Todos os ambientes</option>'+options.environments.map(env=>'<option value="'+esc(env.id)+'" '+(draft.environmentId===env.id?'selected':'')+'>'+esc(env.name)+'</option>').join('')+'</select></label>'+generalFields.map(filterInput).join('')+'</div></fieldset><fieldset class="reports-filter-group reports-jdbc-filters"><legend>JDBC e datasources</legend><p>Combine os critérios do mesmo datasource. Host/IP e porta devem pertencer ao mesmo destino. Endereço configurado não confirma conexão ativa.</p><div class="reports-filter-grid"><label class="field">Presença de JDBC<select name="jdbcPresence"><option value="">Com ou sem JDBC</option><option value="present" '+(draft.jdbcPresence==='present'?'selected':'')+'>Com JDBC identificado</option><option value="missing" '+(draft.jdbcPresence==='missing'?'selected':'')+'>Sem JDBC identificado</option></select></label>'+jdbcFields.map(filterInput).join('')+'</div></fieldset></div><div class="reports-filter-actions"><button type="submit" class="button primary" '+(loading || !draft.columns.length?'disabled':'')+'>Aplicar filtros</button><button type="button" class="text-button" data-report-reset>Limpar filtros</button><div class="reports-columns"><button type="button" class="button" data-report-columns aria-expanded="'+columnOpen+'" aria-controls="reports-column-options">Colunas <span class="reports-column-count">'+draft.columns.length+'</span></button><div id="reports-column-options" class="reports-column-options" '+(!columnOpen?'hidden':'')+'><strong>Colunas do relatório</strong><p>Escolha os dados e aplique os filtros.</p>'+selected.columns.map(col=>'<label><input type="checkbox" data-report-column="'+esc(col.key)+'" '+(draft.columns.includes(col.key)?'checked':'')+'> '+esc(col.label)+'</label>').join('')+'<button type="submit" class="button primary" '+(!draft.columns.length?'disabled':'')+'>Aplicar seleção</button></div></div><span class="reports-draft" '+(result&&!equalQuery()?'':'hidden')+'>Há alterações para aplicar.</span></div></form>';
  }
  function results() {
    const rows = result.rows || [], paging = result.pagination || {page:1,pages:1,total:rows.length,pageSize:20};
    return '<div class="reports-applied" aria-label="Filtros aplicados"><strong>'+esc(result.title)+'</strong>'+(result.filters && result.filters.length ? result.filters.map(f=>'<span>'+esc(f.label)+': <b>'+esc(f.value)+'</b></span>').join('') : '<span>Todos os ambientes · sem filtros adicionais</span>')+'</div><div class="reports-summary">'+[['Máquinas',result.summary.machines],['Ambientes',result.summary.environments],['Registros',result.summary.records]].map(([label,count])=>'<div><span>'+label+'</span><strong>'+Number(count||0).toLocaleString('pt-BR')+'</strong></div>').join('')+'</div><section class="reports-table-panel" aria-label="Resultados do relatório"><div class="reports-table-heading"><div><h3>'+esc(result.title)+'</h3><p>'+esc(descriptions[applied.model] || '')+' · PDF e CSV incluem todos os resultados filtrados.</p>'+(result.context?'<p class="reports-association-context">'+esc(result.context)+'</p>':'')+'</div><label>Linhas por página <select data-report-page-size>'+[20,50,100].map(size=>'<option '+(Number(draft.pageSize)===size?'selected':'')+'>'+size+'</option>').join('')+'</select></label></div>'+(rows.length?'<div class="reports-table-scroll" tabindex="0" role="region" aria-label="Tabela de resultados; role horizontalmente para ver mais colunas"><table><thead><tr>'+result.columns.map(col=>'<th scope="col">'+esc(col.label)+'</th>').join('')+'</tr></thead><tbody>'+rows.map(row=>'<tr>'+result.columns.map(col=>'<td'+(['jdbc','hostJdbc','databaseHost','databaseDestination'].includes(col.key)?' class="reports-jdbc-cell"':'')+'>'+esc(text(row[col.key]))+'</td>').join('')+'</tr>').join('')+'</tbody></table></div>':'<div class="reports-empty"><span aria-hidden="true">⌕</span><h3>Nenhum resultado encontrado</h3><p>Revise os filtros ou escolha outro modelo para explorar o inventário.</p></div>')+'<footer class="reports-pagination"><span>'+Number(paging.total).toLocaleString('pt-BR')+' registro(s)</span><div><button type="button" class="button" data-report-page="'+(paging.page-1)+'" '+(paging.page<=1?'disabled':'')+'>Anterior</button><span>Página '+paging.page+' de '+Math.max(1,paging.pages)+'</span><button type="button" class="button" data-report-page="'+(paging.page+1)+'" '+(paging.page>=paging.pages?'disabled':'')+'>Próxima</button></div></footer></section>';
  }
  function input(event) {
    if (event.target.name && (fields.some(item=>item[0]===event.target.name) || ['environmentId','jdbcPresence'].includes(event.target.name))) {
      draft[event.target.name]=event.target.value;
      syncDirty();
    }
  }
  function syncDirty() {
    const el=mount(), changed=!!(result&&!equalQuery());
    el.querySelectorAll('[data-report-export]').forEach(button=>button.disabled=!result||loading||exporting||changed);
    const note=el.querySelector('.reports-draft');if(note)note.hidden=!changed;
  }
  function change(event) {
    input(event);
    if(event.target.hasAttribute('data-report-column')) {
      const key=event.target.dataset.reportColumn;
      if(event.target.checked)draft.columns.push(key);else draft.columns=draft.columns.filter(item=>item!==key);
      // Retain the model's column order, regardless of the order checkboxes were selected.
      draft.columns=model().columns.map(col=>col.key).filter(key=>draft.columns.includes(key));
      mount().querySelector('.reports-column-count').textContent=draft.columns.length;
      mount().querySelectorAll('[type="submit"]').forEach(button=>button.disabled=!draft.columns.length||loading);
      syncDirty();
    }
    if(event.target.hasAttribute('data-report-page-size')) {draft.pageSize=Number(event.target.value);query();}
  }
  function submit(event) {if(event.target.id==='reports-form'){event.preventDefault();columnOpen=false;query();}}
  function click(event) {
    const button=event.target.closest('button');if(!button||button.disabled)return;
    if(button.hasAttribute('data-report-model')) {
      draft.model=button.dataset.reportModel;draft.columns=model().defaults.slice();draft.page=1;columnOpen=false;collapsed=true;query();
    }else if(button.hasAttribute('data-report-toggle')) {collapsed=!collapsed;paint();}
    else if(button.hasAttribute('data-report-columns')) {columnOpen=!columnOpen;paint();}
    else if(button.hasAttribute('data-report-reset')) {const current=draft.model;draft=initial();draft.model=current;draft.columns=model().defaults.slice();query();}
    else if(button.hasAttribute('data-report-page')) query(Number(button.dataset.reportPage));
    else if(button.hasAttribute('data-report-export')) exportFile(button.dataset.reportExport);
    else if(button.hasAttribute('data-report-retry')) options?query():refresh();
    else if(button.hasAttribute('data-report-refresh')) refresh();
  }
  async function exportFile(format) {
    if (!result || !applied || loading || exporting || !equalQuery()) return;
    const session=epoch, queryParams=parameters(applied);queryParams.set('revision',result.revision);
    exporting=true;exportError='';paint();
    try {
      const file=await deps.download('reports/export.'+format+'?'+queryParams,{},format==='csv'?'text/csv':'application/pdf');
      if(session!==epoch)return;
      const url=URL.createObjectURL(file.blob),anchor=document.createElement('a');anchor.href=url;anchor.download=file.filename||'java-assessment.'+format;document.body.appendChild(anchor);anchor.click();anchor.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
    } catch(failure) {
      if(session!==epoch)return;
      exportError=failure.status===409?'O inventário mudou desde a consulta. Atualize os resultados e exporte novamente.':failure.message||'Não foi possível exportar. Atualize os resultados e tente novamente.';
    } finally {if(session===epoch){exporting=false;if(visible())paint();}}
  }
  global.AssessmentReports={init,render,refresh,clear,enter};
})(window);
