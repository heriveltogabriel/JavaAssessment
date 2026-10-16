# Java Assessment 2.0.57

Entrega consolidada após a 2.0.55 de laboratório. A ponte 2.0.56 é interna; agentes V3 podem solicitar diretamente a versão final.

- Corrige a partida do recuperador systemd após stop/restart, mantendo a âncora assinada e o cadastro. A extensão é instalada pelo manager novo antes da confirmação da atualização; SysV mantém o fluxo existente.
- Instalador compatível com Python 3.6, CA/certificados com configuração explícita e SAN, timeout do OpenSSL e confirmação completa do HTTPS `/health`.
- Dashboard configura a fonte de entropia antes de inicializar TLS. Novas instalações incluem o parâmetro no launcher; atualizações preservam o launcher instalado e aplicam o padrão no processo do dashboard quando não existe opção explícita.
- Pacote separado de atualização local para instalações existentes; ZIP installer continua destinado a dashboard novo. Certificados do cliente, URL, usuários, banco e cadastros são preservados.
- Inclui as melhorias WebLogic/Spring/JDBC, relatórios de destino do banco e limites de coleta da 2.0.55.

**Atenção: atualize também os agentes.** Atualizar o dashboard não atualiza automaticamente as máquinas. Em instalações V3 elegíveis da 2.0.53, o caminho previsto é um único pedido para 2.0.57; não instalar 2.0.55 primeiro. Um recuperador antigo já parado precisa ser retomado antes de receber a correção. Customizações desconhecidas dos serviços não são sobrescritas. Comece por uma máquina e confirme a versão e a coleta antes de ampliar o lote.

Consulte [os comandos de atualização](runbooks/update-from-2.0.53.md) e [as evidências e limites dos ensaios](validation/recovery-installer-2026-10-16.md). Nenhum ensaio de laboratório garante as condições de infraestrutura do cliente.
