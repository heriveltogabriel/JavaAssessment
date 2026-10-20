/* Datasources JDBC tab, database-destination groups and the readable JDBC report cell. */
(function (root) {
  'use strict';
  const esc = v => String(v == null ? '' : v).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const n = v => Number(v || 0).toLocaleString('pt-BR');
  const plural = (count, one, many) => n(count) + ' ' + (Number(count) === 1 ? one : many);
  const UNIDENTIFIED = '__unidentified__';
  const UNRESOLVED_DRIVER = ['Driver não localizado', 'Múltiplos candidatos', 'Inspeção incompleta'];
  const text = value => typeof value === 'string' || typeof value === 'number' ? String(value) : '';
  const unique = values => values.filter((value, index) => value && values.indexOf(value) === index);

  function stateLabel(state) {
    const value = text(state).toUpperCase();
    if (value === 'CONFIGURED') return 'Configurado';
    if (value === 'DISABLED') return 'Desativado';
    if (value === 'APPLICATION_REPORTED') return 'Informado pela aplicação';
    return text(state);
  }

  // Servers and applications named directly, by associated processes or by linked application contexts.
  function relations(row) {
    const associations = Array.isArray(row.associations) ? row.associations.filter(a => a && typeof a === 'object') : [];
    const contexts = Array.isArray(row.linkedContexts) ? row.linkedContexts.filter(c => c && typeof c === 'object') : [];
    return {
      servers: unique([text(row.serverName)].concat(associations.map(a => text(a.serverName)))),
      applications: unique([text(row.applicationName)].concat(associations.map(a => text(a.applicationName)), contexts.map(c => text(c.context))))
    };
  }

  function destination(row) {
    const dashboard = root.AssessmentDashboard;
    return dashboard ? dashboard.databaseDestination(row) : '';
  }

  /** Table columns of the "Datasources JDBC" tab; helpers come from app.js so links and cells match the other tabs. */
  function columns(h) {
    const column = (key, label, render) => ({key, label, render});
    return [
      column('name', 'Datasource / JNDI', r => {
        const jndi = Array.isArray(r.jndiNames) ? r.jndiNames.map(text).filter(name => name && name !== r.name) : [];
        return h.link('datasources', r.id, r.name || r.driverName, jndi.join(', '), r.hostId);
      }),
      column('host', 'Máquina', r => h.hostLink(r.hostId)),
      column('relations', 'Servidor / aplicação', r => {
        const found = relations(r);
        const lines = found.servers.map(name => '<span>' + esc(name) + '</span>').concat(found.applications.map(name => '<span class="cell-sub">' + esc(name) + '</span>'));
        return lines.length ? '<div class="ds-relations">' + lines.join('') + '</div>' : h.value(null);
      }),
      column('databaseType', 'Banco', r => h.value(r.databaseType)),
      column('destination', 'Destino (host:porta / serviço)', r => {
        const resolved = root.AssessmentDashboard && root.AssessmentDashboard.databaseEndpoints(r).length > 0;
        const value = destination(r);
        if (resolved) return '<span class="ds-destination">' + esc(value) + '</span>';
        return '<span class="unknown">Não identificado</span>' + (value ? '<span class="cell-sub" title="Host não resolvido na configuração">' + esc(value) + '</span>' : '');
      }),
      column('driver', 'Driver JDBC', r => {
        const driver = text(r.driverName) || text(r.driverClass);
        return driver ? esc(driver) : h.value(null);
      }),
      column('driverArtifact', 'Arquivo JAR', r => h.artifact?h.artifact(r):h.value(r.driverArtifact)),
      column('driverVersion', 'Versão do driver', r => h.version?h.version(r):h.value(r.driverVersion)),
      column('support', 'Suporte', r => h.support?h.support(r):h.value(null)),
      column('driverResolution', 'Identificação do driver', r => {
        const label = h.resolutionLabel(r.driverResolution);
        if (!label) return h.value(null);
        return '<span class="badge ' + (text(r.driverResolution) === 'IDENTIFIED' ? '' : 'warning') + '">' + esc(label) + '</span>';
      }),
      column('state', 'Estado', r => h.value(stateLabel(r.state)))
    ];
  }

  /** Filter chip text for a "db:<key>" subset (drill-down from the database view). */
  function focusLabel(subset) {
    const key = text(subset).slice(3);
    return key === UNIDENTIFIED ? 'Destino não identificado' : 'Destino: ' + key;
  }

  function names(values, total, render) {
    if (!values.length) return '<span class="unknown">Não informado</span>';
    const shown = values.map(render).join('');
    const more = Number(total || 0) - values.length;
    return shown + (more > 0 ? '<span class="db-more">+' + n(more) + '</span>' : '');
  }

  function group(g, scope) {
    const identified = g.identified !== false && g.key !== UNIDENTIFIED;
    const types = (g.databaseTypes || []).map(type => '<span class="badge neutral">' + esc(type) + '</span>').join('');
    const machines = names(g.machines || [], g.machineCount, m => '<button type="button" class="link" data-machine="' + esc(m.id) + '">' + esc(m.name) + '</button>');
    const servers = names(g.servers || [], g.serverCount, name => '<span>' + esc(name) + '</span>');
    const applications = names(g.applications || [], g.applicationCount, name => '<span>' + esc(name) + '</span>');
    return '<article class="db-group' + (identified ? '' : ' unidentified') + '">' +
      '<header><div><span class="dash-kicker">' + (identified ? 'DESTINO CONFIGURADO' : 'SEM DESTINO RESOLVIDO') + '</span>' +
      '<h3>' + esc(identified ? (g.label || g.key) : 'Destino não identificado') + '</h3>' +
      (identified ? '' : '<p>Placeholder, alias ou URL sem host resolvido na configuração encontrada.</p>') + '</div>' +
      (types ? '<div class="db-types">' + types + '</div>' : '') + '</header>' +
      '<div class="db-metrics"><span><strong>' + n(g.datasources) + '</strong> ' + (Number(g.datasources) === 1 ? 'datasource' : 'datasources') + '</span><span><strong>' + n(g.machineCount) + '</strong> ' + (Number(g.machineCount) === 1 ? 'máquina' : 'máquinas') + '</span><span><strong>' + n(g.serverCount) + '</strong> ' + (Number(g.serverCount) === 1 ? 'servidor' : 'servidores') + '</span><span><strong>' + n(g.applicationCount) + '</strong> ' + (Number(g.applicationCount) === 1 ? 'aplicação' : 'aplicações') + '</span></div>' +
      '<dl class="db-lists"><div><dt>Máquinas</dt><dd>' + machines + '</dd></div><div><dt>Servidores</dt><dd>' + servers + '</dd></div><div><dt>Aplicações</dt><dd>' + applications + '</dd></div></dl>' +
      '<footer><button type="button" class="button" data-inventory="datasources" data-subset="' + esc('db:' + g.key) + '" data-scope="' + esc(scope) + '">Ver ' + plural(g.datasources, 'datasource', 'datasources') + ' →</button></footer></article>';
  }

  /** "Bancos de dados" view: groups computed by the server (view=databases), paginated like the other views. */
  function databases(data, scope) {
    const groups = Array.isArray(data.databaseGroups) ? data.databaseGroups : [];
    const total = Number(data.pagination?.total || 0);
    const intro = '<p class="db-intro">' + plural(total, 'destino', 'destinos') + ' neste escopo. Destinos são configurações encontradas (host, porta e serviço sem diferenciar maiúsculas); não confirmam conexão ativa.</p>';
    if (!groups.length) return intro + '<div class="dash-empty" role="status">Nenhum datasource JDBC encontrado neste escopo.</div>';
    const pagination = root.AssessmentDashboard ? root.AssessmentDashboard.pagination(data) : '';
    return intro + '<div class="db-groups">' + groups.map(g => group(g, scope)).join('') + '</div>' + pagination;
  }

  // Report cells hold one datasource per line, as built by Reports.tuple: "name — driver · version · ... · Destino configurado: X · Driver: Y".
  function parseLine(line) {
    const split = line.indexOf(' — ');
    const name = split < 0 ? line : line.slice(0, split);
    const parts = split < 0 ? [] : line.slice(split + 3).split(' · ').filter(Boolean);
    const destinationPart = parts.find(part => part.startsWith('Destino configurado: '));
    const driverPart = parts.find(part => part.startsWith('Driver: '));
    const dest = destinationPart ? destinationPart.slice('Destino configurado: '.length) : '';
    return {
      name,
      parts,
      destination: /^não identificad[ao]/i.test(dest) ? '' : dest,
      unresolvedDriver: parts[0] === 'Não identificada' || (!!driverPart && UNRESOLVED_DRIVER.includes(driverPart.slice('Driver: '.length)))
    };
  }

  function summary(items) {
    const databases = unique(items.map(item => item.destination.toLowerCase()));
    const unresolved = items.filter(item => item.unresolvedDriver).length;
    return [plural(items.length, 'datasource', 'datasources'), plural(databases.length, 'banco', 'bancos')]
      .concat(unresolved ? [plural(unresolved, 'driver não identificado', 'drivers não identificados')] : []).join(' · ');
  }

  /** Screen rendering of a multi-datasource JDBC cell: short summary, expandable to one datasource per line. */
  function reportCell(raw) {
    const value = text(raw);
    const lines = value.split('\n').map(line => line.trim()).filter(Boolean);
    if (!lines.length || /^não identificad[ao]$/i.test(value)) return esc(value || 'Não identificado');
    const items = lines.map(parseLine);
    return '<details class="report-jdbc"><summary>' + esc(summary(items)) + '</summary><ul>' +
      items.map(item => '<li><strong>' + esc(item.name) + '</strong>' + item.parts.map(part => '<span>' + esc(part) + '</span>').join('') + '</li>').join('') +
      '</ul></details>';
  }

  const api = {columns, databases, focusLabel, reportCell, summary, parseLine, relations, stateLabel};
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.AssessmentDatasources = api;
})(typeof window !== 'undefined' ? window : globalThis);
