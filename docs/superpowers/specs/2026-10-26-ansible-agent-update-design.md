# Atualização de agentes pelo Ansible

Fluxo autorizado na conversa: atualizar em lotes pequenos, preservar identidade, aguardar resultado e interromper quando o resultado for desconhecido. O usuário pediu preparar o playbook. Não requer nova versão do dashboard/agente nem acesso SSH ao cliente para desenvolvimento.

## Escolha

Usar a API administrativa existente, a partir do controlador Ansible. Copiar JARs não satisfaz as autorizações, assinaturas e transações do atualizador; reinstalar altera o cadastro. A API preserva esses controles e funciona com o dashboard 2.1.24 sem nova publicação.

O inventário e `--limit` selecionam os hosts. O controlador resolve hostname/FQDN exatos ou ID explícito por páginas de 100, rejeitando ambiguidades. Nunca usa a prévia global limitada a 1000 hosts. Exige HTTPS com CA, administrador no Vault e versão alvo explícita. Credenciais seguem por stdin, não pelos argumentos, e não são salvas no relatório.

Prévia é o padrão. Aplicação cria no máximo cinco pedidos por lote (o servidor executa um por vez dentro dele), com assessment posterior desativado. Verifica novamente versão/contato/elegibilidade antes de cada lote. Não cria novos lotes enquanto há operações externas ativas. Agentes sem contato recente são registrados para tentativa posterior, sem pedidos. Não inicia nem reinicia serviços remotamente.

Journal privado e chave idempotente são persistidos antes do POST. Retomada acompanha o mesmo lote, inclusive após resposta perdida. Só avança quando todos os pedidos são terminais; SUCCEEDED exige conferir versão no dashboard. EXPIRED/CANCELLED são registrados e permitem avançar. FAILED/ROLLED_BACK/UNCONFIRMED interrompem a campanha; pendentes do próprio lote são cancelados em ordem reversa. Timeout de acompanhamento interrompe e cancela somente WAITING do próprio lote; nunca força uma transação iniciada. Erros de rede/autenticação interrompem sem criar novo lote.

Há lock local e bloqueio por operações existentes no dashboard, mas não existe uma trava global atômica entre controladores: operar uma única campanha de cada vez. Cancelar campanhas antigas continua sendo ação separada. O relatório contém apenas identificação, versão, estados e contadores técnicos.

## Validação

Testes determinísticos do controlador com API simulada: paginação >1000, seleção ambígua, versão já atualizada, ausência de contato, lote bloqueado, autorização perdida, resposta de criação perdida/retomada, timeout, falhas e confirmação de versão. Testes HTTP reais locais com TLS e Ansible verificam cookies, CSRF, Vault/stdin e ausência de senha em saídas. Não executar no cliente durante desenvolvimento. O primeiro uso real deve ter `--limit` de uma máquina.
