'use strict';
// Support lifecycle (EOL) display: badges, list filter, overview risk panel. Classification comes from the server.
(function(root){
  const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const STATUSES = ['fora','estendido','proximo','suportado','desconhecido'];
  const LABELS = {fora:'Fora de suporte',estendido:'Suporte estendido',proximo:'Fim próximo',suportado:'Suportado',desconhecido:'Desconhecido'};
  const ICONS = {fora:'✕',estendido:'$',proximo:'!',suportado:'✓',desconhecido:'?'};
  const HINTS = {fora:'Sem correções do fornecedor.',estendido:'Somente suporte pago/estendido do fornecedor.',proximo:'O suporte principal termina em até 12 meses.',suportado:'Dentro do suporte principal do fornecedor.',desconhecido:'Versão sem data confiável no catálogo.'};
  // Status palette (reserved colors), always paired with icon + text.
  const COLORS = {fora:'#d03b3b',estendido:'#ec835a',proximo:'#fab219',suportado:'#0ca30c',desconhecido:'#a8a59b'};
  const SUPPORT_VIEWS = ['runtimes','servers','applications','libraries'];
  const SUBSETS = {runtimes:{replaced:'Java substituído em disco'},hosts:{'coverage-partial':'Somente coletas parciais'},libraries:{'driver-unresolved':'Datasources com driver não identificado'}};

  const date = value => { const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value || '')); return m ? m[3]+'/'+m[2]+'/'+m[1] : ''; };
  const status = support => STATUSES.includes(support?.status) ? support.status : 'desconhecido';
  function title(support){
    const s = support || {};
    return [s.linha, s.fimPrincipal ? 'Fim do suporte principal: '+date(s.fimPrincipal) : '', s.fimEstendido ? 'Fim do suporte estendido: '+date(s.fimEstendido) : '', s.referencia || '', HINTS[status(s)]].filter(Boolean).join(' · ');
  }
  function badge(support, withLine=true){
    if(!support) return '<span class="unknown">—</span>';
    const s = status(support);
    return '<span class="support-badge support-'+s+'" title="'+esc(title(support))+'"><span class="support-icon" aria-hidden="true">'+ICONS[s]+'</span>'+LABELS[s]+'</span>'+(withLine && support.linha ? '<span class="cell-sub">'+esc(support.linha+(support.referencia&&support.fornecedor?' · ref. '+support.fornecedor:''))+'</span>' : '');
  }
  function detail(support){
    if(!support) return '';
    const s = support, rows = [['Status',badge(s,false)],['Linha',esc(s.linha || 'Não identificada no catálogo')],['Produto',esc([s.produto,s.fornecedor].filter(Boolean).join(' · ') || '—')],['Fim do suporte principal',esc(date(s.fimPrincipal) || '—')],['Fim do suporte estendido',esc(date(s.fimEstendido) || '—')]];
    if(s.observacao) rows.push(['Observação',esc(s.observacao)]);
    if(s.referencia) rows.push(['Referência',esc(s.referencia)]);
    if(/^https:\/\//.test(s.fonte || '')) rows.push(['Fonte oficial','<a href="'+esc(s.fonte)+'" target="_blank" rel="noopener noreferrer">'+esc(s.fonte.replace(/^https:\/\//,''))+'</a>']);
    rows.push(['Levantamento',esc(date(s.dataReferencia) || '—')]);
    return '<section class="detail-section support-detail"><h3>Ciclo de vida do suporte</h3><dl class="details-list">'+rows.map(([k,v])=>'<div><dt>'+esc(k)+'</dt><dd>'+v+'</dd></div>').join('')+'</dl></section>';
  }
  const supportParam = (view, value) => SUPPORT_VIEWS.includes(view) && STATUSES.includes(value) ? value : '';
  const subsetAllowed = (view, subset) => !!SUBSETS[view]?.[subset];
  const subsetLabel = subset => Object.values(SUBSETS).map(s => s[subset]).find(Boolean) || '';
  function filterOptions(view, value){
    if(!SUPPORT_VIEWS.includes(view)) return '';
    return '<option value="">Todos os status de suporte</option>'+STATUSES.map(s => '<option value="'+s+'"'+(s===value?' selected':'')+'>'+LABELS[s]+'</option>').join('');
  }
  function envParam(data){ const id = data?.scope?.environmentId || ''; return id === '' ? 'all' : id === '__unassigned__' ? 'unassigned' : id; }
  function href(data, view, params){ return '#environment/'+view+'?'+new URLSearchParams({env:envParam(data),...params}).toString(); }
  function riskPanel(data){
    const r = data?.risks;
    if(!r) return '';
    const items = [
      ['fora',r.javaHostsOut,'Máquinas com Java fora de suporte','runtimes',{support:'fora'}],
      ['fora',r.serversOut,'Servidores fora de suporte','servers',{support:'fora'}],
      ['proximo',r.serversEnding,'Servidores com fim de suporte próximo','servers',{support:'proximo'}],
      ['fora',r.springOut,'Aplicações Spring Boot fora de suporte','applications',{support:'fora'}],
      ['desconhecido',r.driversUnresolved,'Datasources com driver não identificado','libraries',{subset:'driver-unresolved'}],
      ['desconhecido',r.partialHosts,'Máquinas com coleta parcial','hosts',{subset:'coverage-partial'}],
      ['proximo',r.runtimesReplaced,'Java substituído em disco (reinício pendente)','runtimes',{subset:'replaced'}]
    ];
    const tiles = items.map(([tone,count,label,view,params]) => {
      const n = Number(count || 0), body = '<strong>'+n.toLocaleString('pt-BR')+'</strong><span>'+esc(label)+'</span>';
      return n ? '<a class="risk-tile risk-'+tone+'" href="'+esc(href(data,view,params))+'">'+body+'<span class="risk-go" aria-hidden="true">↗</span></a>' : '<div class="risk-tile risk-none">'+body+'</div>';
    }).join('');
    return '<section class="dash-panel risk-panel" aria-labelledby="risk-panel-title"><div class="panel-heading"><div><span class="dash-kicker">CICLO DE VIDA E COBERTURA</span><h3 id="risk-panel-title">Painel de riscos</h3></div></div><div class="risk-grid">'+tiles+'</div><div class="panel-note">Status de suporte pelo catálogo levantado em '+esc(date(r.dataReferencia) || '—')+'. Java: referência Oracle; outros fornecedores podem ter datas diferentes. Abra um registro para ver datas e fonte oficial.</div></section>';
  }
  // Overview "Versões Java" ring: one slice per support status so out-of-support versions never fold into "Outros".
  function javaGroups(data){
    return (data?.javaSupport || []).map(g => ({name:LABELS[status(g)],count:Number(g.count || 0),version:(g.versions || []).map(v => v.name+' ('+v.count+')').join(' · '),status:status(g),color:COLORS[status(g)],href:href(data,'runtimes',{support:status(g)}),icon:ICONS[status(g)]}));
  }
  const api = {STATUSES,LABELS,COLORS,badge,detail,supportParam,subsetAllowed,subsetLabel,filterOptions,riskPanel,javaGroups,href};
  if(typeof module !== 'undefined' && module.exports) module.exports = api; else root.AssessmentLifecycle = api;
})(typeof window !== 'undefined' ? window : globalThis);
