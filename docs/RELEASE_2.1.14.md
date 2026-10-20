# Java Assessment 2.1.14

Correção para classpaths longos e apresentação da evidência JDBC encontrada. Ponte interna 2.1.13; destino final 2.1.14.

- Classpath: até 1.024 entradas únicas (antes 64), marcando incompletude apenas quando há descarte ou caminho não resolvido.
- Argumentos e ambiente do processo: até 256 KiB (antes 64 KiB). Resolução JDBC: até 4.096 caminhos (antes 512), mantendo os limites globais de leitura e tempo.
- Classpath completo dos argumentos é preservado quando compatível com o prefixo dos metadados hsperfdata cortados. Fontes divergentes e argumentos adicionais da JVM truncados mantêm motivo específico.
- Bibliotecas/datasources mostram JAR e versão prováveis quando o único candidato conhecido é igual em todos os processos associados. Resultados conflitantes ficam visíveis como alternativas. Status técnico e suporte continuam preservados; arquivo em disco não comprova classe carregada.

## Atualizar a instalação do cliente

Use `assessment-dashboard-web-update-2.1.14.zip` em **Administração → Atualização do dashboard**. O instalador completo serve para servidor novo; não use esse instalador no upload da console. A origem do cliente para validação é **2.1.12**, sem reinstalação e sem atualização manual intermediária da ponte. Consulte as [evidências dos pacotes finais](validation/console-release-2.1.14.md).

**Atualize também os agentes separadamente pelo painel e solicite nova coleta.** A melhoria de apresentação já aproveita candidatos existentes; os novos limites e a correção de leitura exigem agente novo. Instalações sem rota elegível devem usar o instalador completo configurado do ambiente. Nenhum reinício do WebLogic é requerido pela alteração.

Os testes usam fixtures locais; não confirmam execução no cliente. Não foi feito deploy remoto. [Limites e interpretação JDBC](JDBC_DRIVER_RESOLUTION.md).
