(function () {
  'use strict';
  window.AssessmentRelease = null;
  fetch('/health', {cache: 'no-store', credentials: 'same-origin'})
    .then(function (response) {
      if (!response.ok) throw new Error('Version unavailable');
      return response.json();
    })
    .then(function (release) {
      if (release.status !== 'ok' || typeof release.version !== 'string' || release.version.length > 64 ||
          release.version !== release.version.trim() || !/^\d+\.\d+\.\d+(?:-beta)?$/.test(release.version) ||
          typeof release.builtAt !== 'string' || !Number.isFinite(Date.parse(release.builtAt))) {
        throw new Error('Invalid release metadata');
      }
      window.AssessmentRelease = {version: release.version, builtAt: release.builtAt};
      ['interface-version', 'dashboard-version'].forEach(function (id) {
        var label = document.getElementById(id);
        if (label) label.textContent = 'Versão ' + release.version;
      });
    })
    .catch(function () {
      ['interface-version', 'dashboard-version'].forEach(function (id) {
        var label = document.getElementById(id);
        if (label) label.textContent = 'Versão indisponível';
      });
    });
})();
