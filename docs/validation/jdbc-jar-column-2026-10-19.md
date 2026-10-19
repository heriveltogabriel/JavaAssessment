# Validação da coluna Arquivo JAR — 19/10/2026

Verificação de sintaxe de app.js, testes de apresentação JDBC e regressões de dashboard passaram. Cobertos os nomes Oracle/Derby, JAR aninhado e ausência de identificação única sem escolher candidato. Caminhos são exibidos usando o escape de texto existente.

O pacote 2.1.4 é gerado pelo fluxo de entrega completo e mantém o ajuste visual separado da necessidade de atualização dos agentes. Os recibos de pacote e publicação ficam em output/dashboard-releases/2.1.4/evidence. Validação final e deploy são registrados após confirmação.

## Pacotes finais

Build completo passou; assinatura e integridade conferidas com 27 perfis conhecidos. Instalador, atualização por script e web-update tiveram ZIP e SHA-256 conferidos. O pacote web aceita a origem 2.1.2 e contém exatamente o app.js validado.

Atualização direta de agente 2.1.2 → 2.1.4 testada com atualizador original e bundle assinado, usando adaptadores de sistema/rede: cadastro, configuração, CA e âncora preservados. Nenhum agente real foi atualizado por este teste e agentes 2.1.2 já fornecem os dados exigidos pela nova coluna.

## Publicação confirmada

Em 19/10/2026 às 18:28:21 BRT, o executor independente concluiu 2.1.2 → 2.1.4 com SUCCEEDED, operação `28ed69711dff4afb8a68363281f7fa7f`. HTTPS local e externo confirmaram versão/status/SQLite; dashboard e executor ativos, configuração preservada e backup em `/opt/java-assessment-dashboard/.dashboard-updates/28ed69711dff4afb8a68363281f7fa7f`.

A preparação e confirmação foram enfileiradas por SSH administrativo. Nenhum agente foi atualizado. A aba real do navegador foi recarregada e exibiu login após o restart, portanto a conferência visual autenticada das colunas fica para o operador; testes de apresentação e conferência do app.js do pacote passaram.
