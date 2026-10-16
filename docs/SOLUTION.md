# Funcionamento da solução

O Java Assessment reúne inventário de máquinas, Java, aplicações, servidores de aplicação, bibliotecas e datasources JDBC na infraestrutura do cliente. As evidências ajudam a identificar versões, localizar dependências e preparar planos de atualização e modernização.

## Arquitetura

O dashboard recebe informações e oferece consulta, organização por ambiente, solicitação de coletas e administração. Cada agente Linux consulta tarefas e envia resultados por HTTPS, usando sua identidade individual. SQLite armazena dados e histórico localmente, sem um serviço de banco separado.

O agente inicia a conexão. Cadastro, heartbeat e coleta são eventos distintos: uma máquina registrada e com contato recente pode ainda não ter concluído o primeiro assessment. Sem tarefa autorizada, o agente não inicia uma nova varredura.

## Instalação e coleta

1. Instale o dashboard no cliente e configure seu primeiro administrador.
2. Crie os ambientes e baixe seus pacotes de agente, identificados por ambiente e versão.
3. Extraia o ZIP na máquina Linux e execute o instalador com `--system-root`.
4. Confirme cadastro e contato; o destino operacional é `/opt/java-assessment-collector`.
5. Solicite ou agende uma coleta e acompanhe Work requests.
6. Confira o inventário, o horário e os avisos de cobertura.

O pacote inclui endereço do dashboard, CA pública e autorização temporária de cadastro. O nome do usuário Linux e a pasta de extração não determinam o destino operacional. Um JRE compatível basta para o agente; não é necessário javac.

## Interpretar o inventário

JDKs podem ser encontrados por arquivos; runtimes sem compilador entram quando há evidência de uso por aplicação. Várias JVMs no mesmo Java home não representam várias instalações. Processos próprios do coletor não são aplicações do cliente.

Arquivo encontrado, configuração declarada e processo observado são evidências diferentes. Um driver JDBC não comprova datasource; uma configuração de datasource não comprova conexão ativa ou saúde do pool.

A descoberta de JDBC cobre formatos específicos de Spring Boot, Tomcat, JBoss/WildFly standalone e WebLogic. Perfis, configurações externas, sobreposições ou recursos definidos somente em runtime podem ficar fora do escopo. A descoberta por configuração não conecta ao banco e não exporta usuário, senha ou URL JDBC completa.

## Pessoas e controle de acesso

Cada pessoa entra com email e senha. A primeira senha é temporária e precisa ser trocada. Administradores gerenciam usuários, ambientes, tarefas e agentes. Leitura permite consulta, acompanhamento e CSV, sem iniciar operações administrativas.

Ambos os perfis consultam todos os ambientes; filtros não são isolamento de autorização. Credenciais humanas e identidades dos agentes são separadas. Os agentes validam o servidor HTTPS e suas credenciais podem ser revogadas.

## Atualizações e Work requests

Na aba Máquinas do ambiente, use **Atualizar agentes do ambiente** para selecionar várias máquinas elegíveis. A ação de uma linha atua somente naquela máquina. O lote executa uma atualização por vez, validando assinatura, integridade, versão e arquivos de suporte.

Solicitações mostra a linha do tempo e o resultado por máquina. Fechar o popup não cancela a operação. Sucesso exige confirmação. Falta de confirmação mantém o lote em espera; falha de código pode restaurar a versão anterior e interromper as demais máquinas do lote.

Instalações V3 elegíveis recebem atualização completa de scripts, bibliotecas e executor pelo painel. Instalações sem rota compatível exigem o instalador completo configurado para o ambiente. Publicar uma versão no dashboard não atualiza agentes automaticamente.

Na 2.1.0, **Administração → Atualização do dashboard** permite enviar um ZIP assinado, preparar a atualização e confirmar o reinício com senha. O executor independente cria backup e verifica a versão por HTTPS; falhas provocam tentativa de retorno e a última versão pode ser restaurada quando compatível. A primeira adoção em dashboard antigo exige o script tradicional. Consulte [o guia operacional](DASHBOARD_SELF_UPDATE.md).

## Consulta e exportação

Use ambiente e máquina para navegar. “Concluído” com observações significa que a coleta terminou, mas há limites ou caminhos não inspecionados; abra **Observações** antes de interpretar uma ausência. Recarregar a interface mostra dados já recebidos, enquanto um novo assessment produz novas evidências.

Em **Ações → Exportar CSV**, escolha o escopo. O arquivo único inclui todos os registros desse escopo, não apenas a página visível. Filtros de busca da tabela não limitam a exportação. Credenciais e tokens não fazem parte do CSV.

## Implantação responsável

Valide uma amostra representativa de sistemas, JVMs e aplicações. Confira cobertura, tempo, memória e recursos conhecidos antes de ampliar a instalação. Inventário não substitui teste de compatibilidade ou monitoramento contínuo da saúde das aplicações. Consulte as [validações e limites da versão atual](validation/dashboard-self-update-2026-10-16.md) e o [guia de instalação](../dashboard-installer/GUIA_INSTALACAO.md).
