# Java Assessment 2.1.2 — laboratório

Identifica versões dos drivers dos datasources WebLogic, com foco em Oracle JDBC, e inclui caminho, evidências e candidatos nos detalhes, relatórios e exportações. Quando há mais de um driver possível, mostra a ambiguidade.

## Dashboard existente com executor independente (2.1.0)

Em **Administração → Atualização do dashboard**, envie [assessment-dashboard-web-update-2.1.2.zip](assessment-dashboard-web-update-2.1.2.zip), aguarde a validação e confirme a atualização e reinicialização. O executor cria backup, verifica HTTPS e versão e restaura a versão anterior se a aplicação falhar.

## Servidor novo

Use [assessment-dashboard-installer-2.1.2.zip](assessment-dashboard-installer-2.1.2.zip). Requer Enterprise Linux 8+, JDK 17+ e Python compatível com o instalador. Não execute sobre um dashboard existente.

## Primeira adoção do executor

O [pacote de atualização por script](assessment-dashboard-update-2.1.2.zip) atende origens compatíveis sem executor independente. Consulte o README incluído; ele recusa execução quando o executor já está instalado. Preserve os backups e a configuração existente.

Os arquivos `.sha256` ao lado de cada ZIP permitem conferir a transferência. Os pacotes anteriores permanecem intactos.

**Atualize também os agentes.** Publicar o dashboard não atualiza os agentes automaticamente. A atualização direta V3 **2.1.0 → 2.1.2** foi testada com o atualizador original e o pacote final assinado, usando adaptadores de sistema/rede. Solicite a atualização pelo painel e depois uma coleta. A 2.1.1 é ponte interna para migração legada, não um salto manual exigido para V3. Instalações sem rota elegível exigem o instalador completo configurado do ambiente.

A simulação Oracle no RHEL5 valida a leitura dos arquivos e a identificação do driver; não conecta ao banco e não confirma a classe carregada na JVM. Esta entrega é destinada ao laboratório.

[Notas](../../docs/RELEASE_2.1.2.md) · [Validação](../../docs/validation/jdbc-driver-resolution-2026-10-19.md) · [Identificação JDBC](../../docs/JDBC_DRIVER_RESOLUTION.md)
