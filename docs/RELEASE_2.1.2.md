# Java Assessment 2.1.2 — teste de laboratório

Identificação da versão de drivers JDBC vinculados a datasources WebLogic, com foco Oracle JDBC. Cruza classe exata, classpath do processo, lib do domínio e referências de manifestos; reconhece JARs renomeados e informa ambiguidades/limites. Não confunde versão de driver com versão do banco, nem arquivo encontrado com classe carregada.

Caminho, origem, estado da identificação e candidatos aparecem nos detalhes e relatórios JDBC. Resumos dos demais relatórios e exportações PDF/CSV levam as evidências. Leitura de manifestos/índices foi otimizada; metadados globais até 32 MiB, mantendo heap 96 MiB, 50.000 arquivos e 180 segundos como padrões.

Probe passivo no RHEL5 com Java 6 e WebLogic identificou Oracle JDBC 11.2.0.3.0 e Derby 10.12.1.1. O datasource Oracle é uma configuração de simulação não ativada, sem conexão com banco. Consulte [funcionamento e limites](JDBC_DRIVER_RESOLUTION.md) e [evidências](validation/jdbc-driver-resolution-2026-10-19.md).

Dashboard do laboratório publicado e verificado em 19/10/2026, com backup da 2.1.0 e configuração preservados.

Dashboard 2.1.0 com executor instalado usa o pacote web-update 2.1.2 em Administração → Atualização do dashboard. O instalador completo é para servidores novos. A 2.1.0 e seus pacotes permanecem intactos.

**Atenção: atualize também os agentes.** Para agentes V3 elegíveis, solicite diretamente a versão 2.1.2 pelo painel e depois uma coleta. A 2.1.1 é somente a ponte interna de migração legada; não requer uma atualização manual intermediária. Instalações não elegíveis usam o pacote configurado do ambiente e o instalador completo. Esta é uma entrega de laboratório, sem publicação no cliente.
