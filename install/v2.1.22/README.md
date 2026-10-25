# Java Assessment 2.1.22

Cadastro em escala, limpeza de códigos inelegíveis e listagem paginada por ambiente. [Notas](../../docs/RELEASE_2.1.22.md) · [Validação](../../docs/validation/console-release-2.1.22.md).

## Atualizar a console 2.1.20

1. Faça download de [assessment-dashboard-web-update-2.1.22.zip](assessment-dashboard-web-update-2.1.22.zip).
2. Na versão 2.1.20, abra **Administração → Atualização do dashboard** e envie esse ZIP.
3. Aguarde a validação, confira 2.1.20 → 2.1.22 e confirme **Atualizar e reiniciar** com sua senha.
4. Após o retorno, confira a versão, máquinas, ambientes e **Códigos de cadastro**. Os formulários permitem até 100.000 usos; Anterior/Próxima percorrem páginas de 100 códigos.

A ponte interna 2.1.21 não exige instalação manual. Não use o instalador completo para atualizar uma console existente. Os limites de lote de coleta/atualização de agentes permanecem em 1.000 por ação.

**Atualizar o dashboard não atualiza agentes automaticamente.** Agentes 2.1.20 continuam compatíveis e não precisam ser atualizados para essa melhoria. Se desejar alinhar versões, confira Revisar agentes e faça a atualização separada pelo painel quando elegível; sem rota compatível, use o pacote configurado para o ambiente.

## Outros pacotes

- [Instalador completo](assessment-dashboard-installer-2.1.22.zip): servidor novo, Enterprise Linux 8+ e JDK 17+. Não enviar este arquivo à console.
- [Atualização por script](assessment-dashboard-update-2.1.22.zip): origens compatíveis sem executor independente. Na sua console 2.1.20, prefira web-update.

Cada ZIP possui .sha256. Pacotes antigos preservados; nenhum deploy remoto acompanha esta publicação. Consulte as evidências para os testes locais e limites de validação.
