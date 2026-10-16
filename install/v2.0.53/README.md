# Instalador 2.0.53

Pacote para instalação nova do dashboard, incluindo os ajustes finais da interface publicados nesta versão. Consulte [as notas](../../docs/RELEASE_2.0.53.md) e [o guia geral](../README.md).

Verifique antes de extrair:

```sh
sha256sum -c assessment-dashboard-installer-2.0.53.zip.sha256
```

O checksum identifica esta revisão do instalador. Os quatro arquivos web foram atualizados em relação ao pacote de preparação inicial: `app.js`, `environments.js`, `reports.js` e `work-requests.js`. Fontes do servidor, conteúdo do agente e catálogo assinado são os mesmos da preparação validada. Os checksums internos foram regenerados.

Validações para publicação: integridade de todas as entradas listadas em SHA256SUMS, assinaturas do catálogo pelo construtor, correspondência da interface com os hotfixes publicados e oito testes do instalador. O ZIP é genérico e não contém identidade de agente cadastrada.

**Atualize também os agentes.** O dashboard não atualiza as máquinas automaticamente. Use **Revisar agentes** para conferir a elegibilidade pelo painel; nas demais instalações, use o pacote completo do ambiente com `--system-root`.
