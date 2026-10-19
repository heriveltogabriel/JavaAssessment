# Aplicações e datasources do laboratório OL07

Preparados em 19/10/2026 na VM ol07 (157.151.0.69), em `/home/opc/java-assessment-lab/datasources-20261019`. Os JARs anteriores permanecem intactos. A aplicação existente foi adaptada para que o endpoint de saúde informe o nome configurado, consulte a tabela de laboratório e leia versões via DatabaseMetaData.

| Aplicação | Datasource | Banco H2 | Endpoint local |
| --- | --- | --- | --- |
| lab-spring-pedidos | PedidosLab | Memória | http://127.0.0.1:18080/lab/health |
| lab-spring-faturamento | FaturamentoLab | Arquivo persistente em data/faturamento | http://127.0.0.1:18081/lab/health |
| lab-spring-integracao | IntegracaoLab | TCP 127.0.0.1:19092, banco integracao | http://127.0.0.1:18082/lab/health |

Spring Boot 3.5.16, Tomcat embutido 10.1.55, HikariCP 6.3.3 e H2/driver 2.3.232. Cada aplicação verificou conexão real e uma linha na tabela `lab_validation`. Os bancos contêm apenas dados artificiais de laboratório. H2 usa usuário local sa com senha vazia e aceita somente loopback; não expor as portas nem reutilizar essa configuração em produção.

## Serviços

- java-lab-spring-pedidos.service
- java-lab-spring-faturamento.service
- java-lab-spring-integracao.service
- java-lab-h2.service
- java-lab-pedidos.service
- java-lab-faturamento.service
- java-lab-integracao.service

Todos executam como opc e estão habilitados no systemd. Os três últimos são os processos Java 6 simples já existentes. Os Spring Boot usam o JDK17 privado do laboratório, com heap máximo 96 MiB cada; H2 usa 64 MiB, e os processos simples 32 MiB. Integração depende do serviço H2. As portas 18080/18081/18082/19092 foram verificadas escutando somente em 127.0.0.1. Não foi realizado reboot para este teste.

```sh
sudo systemctl status 'java-lab-*' --no-pager
sudo systemctl restart java-lab-spring-pedidos.service java-lab-spring-faturamento.service java-lab-spring-integracao.service
curl --noproxy '*' http://127.0.0.1:18080/lab/health
curl --noproxy '*' http://127.0.0.1:18081/lab/health
curl --noproxy '*' http://127.0.0.1:18082/lab/health
```

Use os serviços acima para iniciar/parar; os antigos start.sh/spring-start.sh não gerenciam estes novos serviços e não devem ser executados junto deles.

Uma coleta da OL07 foi solicitada pelo dashboard: `449919ee-09da-47a0-9b92-cb2f68859fcf`. O sucesso dos endpoints é separado da confirmação de inventário recebido. Versão real do banco foi verificada nos endpoints de laboratório; a coleta passiva não consulta esses endpoints automaticamente.

## Resultado recebido

Coleta concluída em 19/10/2026 às 18:53:11 BRT, com observações de links simbólicos ignorados/limite de profundidade em outros caminhos. O dashboard confirmou 2 instalações Java, 7 processos/aplicações e os 3 datasources com versão 2.3.232. IntegracaoLab trouxe destino 127.0.0.1:19092. As bibliotecas H2 e Tomcat embutido foram encontradas nos pacotes; Tomcat permanece como biblioteca embutida, sem registro de servidor independente.

Limite observado: driverArtifact não está preenchido na linha dos datasources Spring Boot; o JAR está visível na linha H2 associada à mesma aplicação. Melhoria registrada em docs/TODO.md.
