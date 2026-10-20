# Tomcat, JBoss e entrega consolidada

Execução com as skills brainstorming, writing-plans e executing-plans. Especificação fornecida e implementação/publicação autorizadas pelo usuário em 20/10/2026. A autorização explícita para subir código e instaladores prevalece sobre a restrição genérica de push do documento.

**Objetivo:** integrar a descoberta passiva Tomcat/JBoss ao motor JDBC e publicar uma única versão após validação, incluindo Spring e WebLogic.
**Arquitetura:** motor compartilhado mantém precedência, versão, candidatos e resultado por PID. TomcatDataSources e JbossDataSources fornecem origens e configuração específicas. Preservar listas salvas, limites, privacidade e Java 6.
**Tecnologias:** Java 6/ECJ no agente, Java 17/SQLite, JavaScript, builds Python assinados existentes.

- [x] A: atualizar defaults em AssessmentScan/AssessmentServer e formulários; validar caminhos e observação de exclusão por processo. Fixtures: novo/salvo, duplicados/interseção e processo excluído.
- [x] B1/B2: Tomcat common.loader ordenado, wildcard sem ordem, propriedades seguras com -D prioritário e ambiente opt-in. Fixtures: base/home, externo, provável/ambíguo, senha nunca resolvida.
- [x] C1-C4: módulo multi-JAR, camadas/overlays e deployments no motor compartilhado. Fixtures: classe exata, overlay/raiz, repositório content, deployment desativado, domain por PID.
- [x] B3-B6: jsvc, docBase/xmlBase externo, symlinks ligados ao processo, ServerInfo customizado; fixtures por caso, sem executar apps.
- [x] C5-C7: cached domain e aviso de controlador, descritores de aplicação e vínculo JNDI; fixture sem credenciais.
- [x] D: exibir proveniência, observações, VARIES por instância/servidor e preservar inventário antigo.
- [x] Regressão: JAVA_HOME=JDK17 NODE=runtime /bin/sh ops/test-source.sh; ECJ source/target1.6 e major50. Regressão WebLogic intacta. Corrigir todas as falhas antes da versão.
- [x] Revisão final e README/TODO/notas/guias. Só então incrementar uma vez e executar builds de release, web-update e instalador; comparar compatibilidade com pacote 2.1.8 e verificar artefatos finais.
- [x] Publicar código/documentação/instaladores em Git sem sobrescrever releases anteriores. Confirmar remoto. Enviar WhatsApp para Herivelto Gabriel com arquivo web-update e orientação de atualizar agentes/nova coleta.

Não fazer deploy remoto: o usuário vai validar a atualização pela console. Validações locais e compatibilidade de pacotes serão diferenciadas de ensaio em servidor real. Manter /home/opc/oracle somente no perfil legado conforme fallback autorizado na especificação.

Entrega publicada em `main` e `tomcat-jboss-agente`: commit `eb40c8c`. Aviso de conclusão enviado ao usuário pelo WhatsApp. Console real permanece para validação do operador.
