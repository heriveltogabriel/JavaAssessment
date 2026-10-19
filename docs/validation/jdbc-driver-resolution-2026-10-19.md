# Validação JDBC — 2026-10-19

## Fonte

- 19 verificações específicas: Oracle ojdbc6/7/8/11, classe atual/legada/XA, nomes personalizados, classe exata, metadados Maven, manifesto assinado grande, candidatos concorrentes, metadados conflitantes, ciclos, exclusões, orçamento, cache e domínios distintos.
- Regressão completa de fonte em JDK17 passou: descoberta, HTTP, SQLite, relatórios, CSV, privacidade, autenticação, tarefas e JavaScript. Teste de relatórios com 75 verificações inclui colunas JDBC e resumos nos cinco modelos.
- Bytecode do probe compilado para Java 6 por ECJ e executado no Java 6u45 da RHEL5, sem substituir o agente ou reiniciar o WebLogic.

## Probe nativo

O processo WebLogic e seus arquivos reais foram lidos. Encontrados:

| Datasource | Classe | Versão do arquivo |
| --- | --- | --- |
| JmsLabDerby | org.apache.derby.jdbc.EmbeddedDriver | 10.12.1.1 |
| LAB_ORACLE_DRIVER_SIMULATION | oracle.jdbc.OracleDriver | 11.2.0.3.0 |

Oracle foi resolvido por referência Class-Path de manifesto até o ojdbc6.jar instalado no WebLogic. O descritor de simulação foi criado em config/jdbc, sem alterar config.xml: não está ativado e não conecta ao banco. Nenhuma credencial de WebLogic ou banco foi necessária.

Probe final: 245 arquivos JDBC examinados, 24.552.819 bytes de metadados, heap máximo configurado 96 MiB, nenhum aviso. Esses números são do probe de descoberta de processos, não de uma varredura completa nem de um teste de carga em frota. A resolução informa evidência de arquivo, não confirmação da classe carregada.

## Entrega

Os recibos finais de empacotamento/publicação ficam em output/dashboard-releases/2.1.2/evidence. A publicação e a coleta pelo agente instalado são verificações separadas do probe; não declarar a coleta nova recebida antes da atualização e confirmação do agente.

### Pacotes finais e publicação

- Pacote completo 2.1.2 e assinaturas verificados: 26 perfis legados conhecidos, bootstrap imutável e identidade dos artefatos. O pin 2.1.0 foi comparado ao bundle assinado do instalador publicado antes de ser incluído no verificador.
- Atualização direta V3 **2.1.0 → 2.1.2** passou com binário original e bundle final assinado, preservando identidade, configuração, CA e âncora; adaptadores de sistema/rede, sem substituir um agente real.
- Instalador, atualização por script e pacote web: ZIPs íntegros e SHA-256 conferidos. Pacote web aceita a origem 2.1.0.
- Publicação no dashboard de laboratório em **2026-10-19 20:53:56 UTC**, pelo executor independente, operação `33a0430f9a0a4780a703dbd1f342cfc1`, resultado **SUCCEEDED**. Preparação e confirmação foram enfileiradas pelo administrador via SSH; não foi um teste de upload pelo navegador.
- HTTPS local e externo confirmaram versão 2.1.2, status ok e SQLite. A CA pública instalada foi obtida por SSH autenticado para a conferência externa; a CA genérica do instalador não é a CA deste dashboard. Serviços dashboard/updater ativos, hashes da configuração preservados e backup da 2.1.0 em `/opt/java-assessment-dashboard/.dashboard-updates/33a0430f9a0a4780a703dbd1f342cfc1`.
- Nenhum pedido de atualização de agente foi criado por este deploy. Pendente a validação visual após atualizar o agente RHEL5 e executar nova coleta. A simulação foi mantida no domínio para esse teste.
