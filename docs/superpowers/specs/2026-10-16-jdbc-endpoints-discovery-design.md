# Descoberta de aplicações e destinos JDBC

## Escopo aprovado

Implementar em fontes, mantendo a versão publicada: WebLogic somente em domínios ligados a processos ativos; Spring Boot em JAR e layouts descompactados; destinos JDBC em inventário, relatórios e exportações. O usuário autorizou implementação e testes nas VMs de laboratório em 2026-10-16, com alternativa de comandos prontos caso SSH não esteja disponível. Não publicar nem atualizar agentes instalados com binários diferentes sob a mesma versão.

## Contrato

Extrair de URLs reconhecidas somente `databaseHost`, `databasePort`, `databaseName` e uma lista estruturada `databaseEndpoints` para múltiplos destinos. Não armazenar URL bruta, usuário, senha ou parâmetros de autenticação. Endereços são configuração encontrada, nunca prova de conexão. Protocolos, aliases e expressões não resolvidos ficam desconhecidos, sem inferência. Nome do banco permite serviço Oracle/SID, sem chamar versão de driver de versão do banco. A integração opcional continua responsável pela versão real informada pela aplicação.

WebLogic: resolver o domínio ativo pelo launcher e diretório de trabalho; consultar referências de config/config.xml e descritores em config/jdbc dentro desse domínio. Não varrer domínios vizinhos parados. Manter associação entre domínio, servidores ativos e datasource, deduplicando a mesma configuração entre PIDs. Arquivo sem referência será identificado separadamente como configuração sem vínculo de implantação confirmado.

Spring: melhorar reconhecimento por evidência de Spring Boot no classpath/layout/metadados e separar a configuração do custo de bibliotecas aninhadas. Preservar o processo mesmo quando não houver evidência suficiente para classificá-lo como Spring Boot. Configurações externas, profiles e placeholders permitidos seguem resolução passiva; não executar aplicação, abrir conexão JDBC nem acessar Config Server/Vault. Registrar limites e ausência de evidência, sem classificar aplicação Java genérica como Spring apenas por conter um datasource.

Interface: destinos visíveis na listagem/detalhe de datasources, filtros JDBC e relatórios de máquinas, Java, servidores, ambientes e JDBC, incluindo CSV e PDF. Dados históricos sem campos novos continuam válidos. Agregações preservam a relação entre datasource e destino.

## Recursos e validação

Perfil padrão novo: 50.000 arquivos, 180 segundos, 16 MiB de leitura de metadados, heap de 96 MiB. Respeitar configurações explícitas anteriores, com opção de selecionar limites ampliados no laboratório. Não ampliar limites implicitamente em coletas já em andamento.

Testes: URLs Oracle simples/SID/RAC/IPv6 e outros drivers, remoção de credenciais, XML seguro, múltiplos domínios ativos/parados, duplicação por PID, Spring Boot JAR/classpath/configuração externa, driver e versão, filtros e exportações. Rodar testes Java com heap de 96 MiB e compilar coletor para Java 6. Nas VMs, usar um probe isolado que não registra agente nem envia dados, não substitui serviço e grava somente resultado sanitizado em diretório privado. RHEL 5: 157.151.15.169; OL7: 157.151.0.69. Preservar aplicativos existentes. Relatar limites e evidências reais, sem afirmar compatibilidade não testada.
