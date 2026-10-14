# Overview compacto

Solicitação de 2026-10-14: acrescentar Java Servers e JDBC aos painéis de distribuição e substituir os cartões de máquinas por uma listagem que continue utilizável com 100 máquinas. O usuário escolheu JDBC por **nome do datasource e versão do driver**.

## Layout e comportamento

Manter os indicadores superiores. Usar quatro painéis em grade 2 × 2 (uma coluna no celular), com gráficos menores e até três grupos nomeados mais “Outros”: versões Java, sistemas operacionais, servidores Java por produto/versão e datasources por nome/versão do driver. Totais e distribuições abrangem todo o ambiente selecionado; busca e paginação de máquinas não alteram os gráficos. Versões ausentes são explicitamente não informadas. Cada painel tem acesso ao inventário correspondente, sem simular um filtro por versão que a tela ainda não suporta.

O JDBC conta datasources, não arquivos de drivers. Não atribuir versão a partir do nome do servidor nem tratar configuração como conexão ativa. Java Servers mantém o mesmo conjunto com evidência de execução usado pelo indicador existente.

Substituir “Explore your machines” por tabela de dez máquinas por página com busca por nome, sistema ou IP. Mostrar nome/ambiente, sistema operacional, contato do agente, versões Java, contagem de servidores e datasources, além do menu de ações existente. No celular, manter tabela com rolagem horizontal contida. Manter acesso ao inventário completo e aos detalhes da máquina.

## Implementação

Agregar servidores/datasources em SQLite e retornar apenas os três maiores grupos mais o restante agregado, sem carregar todos os registros no navegador. Usar a paginação SQL existente para os dez hosts do overview. A atividade recente mantém sua consulta independente, e os gráficos usam apenas o escopo de ambiente/máquina. A interface reaproveita os handlers de detalhes, inventário e paginação, acrescentando a busca da tabela.

## Verificação

Testar grupos conhecidos e versões ausentes; escaping de nomes; agregados completos com mais grupos que o limite; busca/paginação com 100 hosts; isolamento por ambiente; ações e navegação; desktop e celular. Preservar o aviso de atualização dos agentes preparado anteriormente. Preparar fontes para a próxima entrega, sem alterar agentes ou publicar uma versão existente novamente.
