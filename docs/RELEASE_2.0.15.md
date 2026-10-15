# Java Assessment 2.0.15 — Relatórios

Nova versão preparada localmente para teste. Dashboard e pacote do agente usam 2.0.15; 2.0.14 é o bridge interno. A versão publicada 2.0.13 permanece preservada. Esta entrega não publica o dashboard nem inicia atualizações nas máquinas.

## O que mudou

O menu lateral **Relatórios** abre cinco modelos: Máquinas, Instalações Java, Java Servers, Datasources e drivers JDBC e Resumo por ambiente. Cada modelo oferece colunas selecionáveis, filtros recolhíveis, indicadores de máquinas/ambientes/registros e paginação de 20, 50 ou 100 linhas.

Os filtros combinam ambiente, máquina ou IP, sistema operacional, arquitetura, Java, servidor e versão, datasource, driver e versão do driver. As buscas textuais aceitam trechos e acentos; as versões de servidor e driver são exatas. Java permite família (17) ou versão completa (17.0.12). Tipo e versão precisam pertencer ao mesmo servidor; datasource e driver precisam pertencer ao mesmo datasource.

O relatório JDBC inclui **datasource, driver, versão e classe do driver, servidor associado, máquina e ambiente**. Dados ausentes aparecem como “Não identificada”. A associação ao servidor só aparece quando foi registrada pelo coletor; não é inferida por dividir a mesma máquina. As listas de datasources e drivers disponíveis no relatório de servidores são explicitamente da máquina.

Não há filtros nem colunas de data da coleta ou cobertura. Os relatórios usam o último inventário armazenado. O PDF informa apenas a data de geração do documento. Um novo assessment só é necessário se os dados do inventário precisarem ser renovados.

## Exportação

- **CSV:** UTF-8, acentos, cabeçalho e colunas selecionadas; inclui todas as páginas do resultado filtrado. Campos que poderiam virar fórmulas em planilhas são neutralizados.
- **PDF:** download direto, formato A4 horizontal, resumo, filtros aplicados, cabeçalhos repetidos e paginação. Colunas largas são divididas em grupos com máquina/ambiente repetidos; conteúdo longo continua nas páginas seguintes.
- Tela e exportações usam a mesma consulta. Se o resultado mudar entre a consulta e o download, o sistema pede para atualizar o relatório antes de exportar.
- Administradores e usuários de leitura podem consultar e exportar. Senhas, tokens e URLs JDBC não fazem parte das colunas permitidas.

Cada relatório aceita até 10.000 registros, 8.192 caracteres por célula e cerca de 7 MiB de texto selecionado. Resultados acima dos limites pedem filtros mais específicos. Esses limites também se aplicam à prévia para evitar consultas excessivas. O PDF preserva português e caracteres WinAnsi; caracteres fora do conjunto da fonte aparecem como `[U+XXXX]`, com aviso. O CSV preserva o Unicode original.

## Como testar

1. Publicar o dashboard 2.0.15 usando o comando abaixo e entrar novamente.
2. Abrir **Relatórios → Máquinas**, escolher um ambiente e pesquisar `Linux`; conferir a quantidade de máquinas sem duplicação por servidor ou datasource.
3. Em **Java Servers**, filtrar `WebLogic` e uma versão presente no inventário, como `10.3.6.0`.
4. Em **JDBC**, filtrar um datasource ou driver conhecido. Conferir nome, classe, versão e servidor associado com a listagem de inventário.
5. Alterar as colunas e baixar **CSV** e **PDF**. Verificar que ambos seguem os filtros e incluem todas as páginas.
6. Abrir **Resumo por ambiente** e conferir as contagens. Ambientes vazios aparecem quando não há filtros de recursos.
7. Repetir consulta e exportação com um usuário de leitura. Usar o botão superior de recarregar para renovar o resultado.

```bash
python3 "/Users/heriveltosantos/Documents/Testes Pyton/JavaAssessment/output/dashboard-releases/2.0.15/deployment/publish-dashboard.py"
```

O ZIP `output/dashboard-releases/2.0.15/installer/assessment-dashboard-installer-2.0.15.zip` é destinado à instalação de um dashboard novo; para atualizar o dashboard existente, usar o publicador acima.

## Validação local

Consultas e HTTP com SQLite real: filtros combinados e correlacionados, contagem distinta de máquinas, busca por IP e acentos, campos estruturados inesperados, limites de célula e registros, CSV/PDF, autenticação, perfil de leitura e revisão do inventário. PDF: acentos, conteúdo longo, múltiplas páginas e 10.000 linhas, com inspeção visual. Navegador integrado: login, menu, filtros, colunas, downloads reais, recarregar, navegação, sessão, perfil leitor e layout móvel.

Resultados: 41 verificações de consultas/HTTP, 2.124 verificações do PDF, navegador integrado e regressão de Work requests aprovados; 24 verificações positivas/negativas do candidato e 8 testes do instalador aprovados. O builder concluiu as regressões de descoberta, JDBC, autenticação, atualização, instalação e publicação. O verificador conferiu checksums internos/externos, assinaturas, nove perfis legados e o bootstrap imutável. Os arquivos de relatório no runtime e no pacote de fontes são idênticos aos fontes testados.

As evidências locais ficam em `output/reports`, `output/pdf` e `output/dashboard-releases/2.0.15/evidence`. A validação local não substitui a conferência dos dados reais do ambiente amanhã.

## Atualizar os agentes

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza os agentes automaticamente. A tela de relatórios funciona com o inventário já recebido; não depende de uma nova capacidade de descoberta do agente.

O catálogo da versão inclui atualização completa V3 e migração dos perfis legados conhecidos a partir de 1.5.12. A elegibilidade exige os componentes e hashes esperados; o número da versão isolado não basta. Abra **Revisar agentes**: use o painel para as instalações elegíveis e o instalador configurado do ambiente com `--system-root` quando não forem elegíveis. Acompanhe o Work request e confirme a versão final. É possível manter a opção de assessment após a atualização marcada.

Linux/Java 32 bits e cenários de falha/reboot da migração continuam sem validação suficiente para uma declaração geral de compatibilidade em produção. Esta versão não altera essa condição.
