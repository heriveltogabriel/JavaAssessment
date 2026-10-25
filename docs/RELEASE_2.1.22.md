# Java Assessment 2.1.22

## Cadastro de máquinas em escala

Remove o teto fixo de 1.000 máquinas cadastradas no dashboard. Códigos permitem até 100.000 usos, inclusive nos formulários e pacotes por ambiente. O limite de códigos ativos não esgotados é de 10.000 por categoria; registros esgotados ainda válidos continuam permitindo repetição do cadastro da mesma instalação.

Códigos expirados, revogados ou de ambientes removidos são limpos ao emitir novos códigos, sem remover identidades ou inventário das máquinas. A tela **Códigos de cadastro** consulta páginas de 100 registros com Anterior/Próxima, aplicando o ambiente antes da paginação. Assim, códigos antigos continuam acessíveis para consulta e revogação. A lista é exclusiva de administradores e não expõe tokens nem hashes.

O limite de tentativas de cadastro por IP passa de 30 para 600 por minuto. Configure `registrationPerMinute` em `conf/server.properties` (1 a 100.000) e reinicie o serviço para alterar. IP compartilhado por NAT/proxy divide esse limite. Memória, workers HTTP e lotes de até 1.000 máquinas por ação continuam limitados; a entrega não comprova capacidade operacional ilimitada.

## Atualização e compatibilidade

Use o [web-update](../install/v2.1.22/assessment-dashboard-web-update-2.1.22.zip) em **Administração → Atualização do dashboard**. A origem de teste é **2.1.20**, sem instalação manual da ponte 2.1.21. Consulte [as evidências](validation/console-release-2.1.22.md) para o resultado da atualização/restauração local e [os pacotes](../install/v2.1.22/README.md) para instruções. Não reinstale a mesma 2.1.20 e não use o instalador completo sobre o dashboard existente.

Não houve mudança funcional do agente. **Agentes 2.1.20 podem permanecer instalados para aproveitar esta alteração. Atualizar o dashboard não atualiza agentes automaticamente.** Para alinhar versões, abra Revisar agentes e atualize separadamente apenas quando elegível; instalações sem rota compatível usam o pacote configurado para seu ambiente.

Nenhum deploy remoto é realizado nesta entrega. O operador validará upload, reinício e inventário na sua console 2.1.20 antes de atualizar o cliente. Pacotes anteriores preservados.
