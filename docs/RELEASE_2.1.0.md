# Java Assessment 2.1.0

Nova área **Administração → Atualização do dashboard**: upload de ZIP assinado, preparação, confirmação de reinício com senha administrativa, acompanhamento das etapas, download do log, histórico e restauração compatível da última atualização.

O executor systemd é independente do painel. A assinatura e os arquivos são validados antes da aplicação; o serviço cria backup privado, preserva banco/configuração/certificados e verifica HTTPS e versão final. Falha de inicialização provoca tentativa automática de retornar à versão anterior. Cancelamento só está disponível antes da aplicação. Restauração não descarta o banco atual e é recusada diante de operações de agentes novas/pendentes ou arquivos alterados.

**Primeira adoção:** a passagem da 2.0.57 para a 2.1.0 usa o script tradicional, que instala o executor. Depois, novas versões compatíveis usam `assessment-dashboard-web-update-X.Y.Z.zip` pelo painel. O instalador completo continua destinado a servidores novos. Pacotes antigos e instaladores completos não são aceitos como upload.

A 2.0.57 e seus instaladores estão preservados para o cliente. A 2.1.0 foi publicada no dashboard de laboratório em 2026-10-16, com HTTPS e executor independente verificados. Nenhuma publicação foi feita no cliente. Os testes nativos usam o dashboard Java real em instalação isolada, com banco e certificados de teste, usuário sem privilégios e systemd; consulte [validações e limites](validation/dashboard-self-update-2026-10-16.md) e [o guia operacional](DASHBOARD_SELF_UPDATE.md).

**Atenção: atualize também os agentes.** Atualizar o dashboard não atualiza as máquinas. Use Revisar agentes para instalações V3 elegíveis; instalações sem rota compatível exigem o pacote configurado do ambiente e instalador completo. Comece por uma máquina e confirme versão e nova coleta. As melhorias JDBC e a correção do recuperador da 2.0.57 estão incluídas.
