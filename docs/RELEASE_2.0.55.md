# Java Assessment 2.0.55

Entrega para validação no dashboard de laboratório. A 2.0.54 é reservada exclusivamente ao bridge interno; não exige uma atualização manual intermediária.

- WebLogic: domínio vinculado ao processo em execução, datasources referenciados e configurações adicionais identificadas, destino JDBC com host/IP, porta e banco/serviço. Domínios parados não são inventariados. Servidores com mesmo nome em domínios diferentes mantêm vínculos independentes.
- Spring Boot: descoberta por JAR executável e classpath explícito com diretórios/JARs, prioridade de configuração sobre dependências grandes e cache limitado. Casos ambíguos permanecem identificados como observações.
- Host/IP do banco em todos os relatórios, filtros de destino e exportações CSV/PDF; múltiplos endereços, inclusive RAC e IPv6, são preservados. URL completa e credenciais não são exportadas. Endereço configurado não confirma conexão ativa.
- Novos padrões: 50.000 arquivos, 180 segundos, 16 MiB de metadados e heap mantido em 96 MiB. Limites já configurados explicitamente não são alterados.

Fontes testados em Java 6 na OL7 e RHEL5; detalhes e limitações em [validação JDBC](validation/jdbc-discovery-2026-10-16.md). Esses ensaios isolados não substituem validação operacional dos pacotes finais no cliente. Os reparos do instalador e da inicialização do dashboard registrados em TODO continuam separados desta entrega; este publicador preserva os launchers e a configuração instalados.

**Atenção: atualize também os agentes.** Publicar o dashboard disponibiliza o pacote, mas não atualiza as máquinas automaticamente. O catálogo assinado inclui a origem 2.0.53, mantém a mesma chave de confiança e a âncora de recuperação existente. Use **Revisar agentes** e a atualização pelo painel quando a instalação for considerada elegível pelos hashes e pela geração do atualizador. Para instalações não elegíveis, o caminho é o instalador completo configurado para o ambiente; não force a troca isolada do JAR. A validação de compatibilidade desta preparação é de pacotes e testes locais, não uma promessa universal para todas as plataformas.

Após confirmar a nova versão do agente, solicite um assessment para preencher os novos campos. Inventários anteriores não são recalculados retroativamente. A versão real do banco depende da integração opcional; não é deduzida da versão do driver.
