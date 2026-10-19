# Java Assessment 2.1.4 — laboratório

Bibliotecas Java passa a mostrar **Arquivo JAR** e **Versão** em colunas separadas, por exemplo `ojdbc6.jar` e `11.2.0.3.0`. Os detalhes mantêm o caminho completo; candidatos ambíguos não são escolhidos automaticamente.

## Atualizar o dashboard

Para o laboratório na 2.1.2 com executor independente, use Administração → Atualização do dashboard e envie [assessment-dashboard-web-update-2.1.4.zip](assessment-dashboard-web-update-2.1.4.zip). Após a validação, confirme a atualização e reinicialização. O executor cria backup e verifica a nova versão por HTTPS.

## Outros pacotes

- [Instalador para servidor novo](assessment-dashboard-installer-2.1.4.zip). Enterprise Linux 8+, JDK 17+ e Python compatível; não executar sobre um dashboard existente.
- [Atualização por script](assessment-dashboard-update-2.1.4.zip), para origens compatíveis sem executor independente; siga o README incluído. Com executor instalado, use o pacote web.

Cada ZIP tem um arquivo `.sha256` ao lado. Versões anteriores são preservadas.

**Agentes:** publicar o dashboard não atualiza as máquinas automaticamente. Para esta alteração visual, agentes 2.1.2 podem permanecer nessa versão, sem nova coleta. Para receber as melhorias JDBC em versões anteriores, confira a elegibilidade pelo painel e atualize agentes compatíveis; quando não houver rota, use o instalador completo configurado do ambiente. A 2.1.3 é ponte interna de migração, não um salto manual exigido para V3.

[Notas](../../docs/RELEASE_2.1.4.md) · [Validação](../../docs/validation/jdbc-jar-column-2026-10-19.md)
