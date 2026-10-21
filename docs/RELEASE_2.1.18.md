# Java Assessment 2.1.18

Entrega da branch `mapa-bancos`, baseada na 2.1.16. Versão final 2.1.18, com ponte interna 2.1.17. Esta entrega preserva os instaladores anteriores e não reutiliza a numeração 2.1.16.

## Excel e Mapa de bancos

Em **Administração → Exportar inventário**, escolha o formato e baixe um ZIP com um único arquivo:

- **Excel (.xlsx):** `inventario.xlsx`, com abas Resumo, Ambientes, Máquinas, Java, Processos, Servidores, Bibliotecas, Aplicações, Datasources e Mapa de bancos. Cada aba mostra as colunas de seu tipo, com cabeçalho fixo e filtro. Listas e evidências são resumidas em texto; células longas recebem aviso de corte. Para preservar todos os detalhes estruturados, use JSON.
- **Mapa de bancos:** `mapa-bancos.csv`, separado por ponto e vírgula, com uma linha por datasource × destino configurado × processo/aplicação. As chaves `chaveServico`, `chaveHostPorta` e `hostCurto` ajudam no cruzamento com outro inventário. Destinos configurados não comprovam conexão ativa; a versão do driver não é a versão do banco.
- **JSON e CSV completo:** continuam disponíveis. A exportação mantém as exclusões de usuários, senhas, tokens e chaves; nomes, IPs e caminhos técnicos continuam identificáveis.

A planilha avisa quando uma célula excederia o limite de texto e tem limite de linhas por aba. O CSV completo não ganhou esses resumos. Consulte [o formato e os limites](INVENTORY_EXPORT.md).

## Driver provável

A tela só mostra JAR/versão **provável** de inspeção incompleta quando o único candidato estava no classpath. Um JAR encontrado somente no disco, ou em `WEB-INF/lib` da aplicação sem evidência de classpath, não é promovido a provável. A identificação continua distinguindo evidência incompleta de driver confirmado.

## Atualização e agentes

Use [assessment-dashboard-web-update-2.1.18.zip](../install/v2.1.18/assessment-dashboard-web-update-2.1.18.zip) em **Administração → Atualização do dashboard**. A entrega é destinada à atualização direta da 2.1.14 ou 2.1.16 para 2.1.18, sem instalar manualmente a ponte 2.1.17. Consulte [os pacotes](../install/v2.1.18/README.md) e [a validação final](validation/console-release-2.1.18.md). O instalador completo é para servidor novo.

**Atualizar o dashboard não atualiza os agentes automaticamente.** Não houve mudança funcional no agente: Excel, mapa e ajuste de exibição usam os dados já coletados, e agentes 2.1.16 não precisam atualização para essas funcionalidades. A identificação do agente acompanha a versão unificada gerada pelo build; para alinhar versões, revise a elegibilidade e atualize os agentes separadamente pelo painel. Instalações sem capacidade/rota elegível precisam do instalador configurado para seu ambiente.

As implementações recebidas nos commits `479e36b` e `528bd88` foram mantidas. A entrega acrescenta somente identificação de versão gerada, documentação e pacotes. Nenhum deploy remoto foi executado.
