# Plano: inspeção Java padrão

1. Adicionar regressões HTTP para padrão novo, migração única, herança, exceções e reinício em tests/ArchiveDefaultsHttpTest.java.
2. Mudar AssessmentServer.java: padrão verdadeiro e migração transacional, sem reescrever configurações de hosts/ambientes nem agendas.
3. Cobrir layouts antigo/V3, helpers, runtime privado, limites e nomes semelhantes em CollectorSweepTest/JdkInventoryTest; ajustar AssessmentScan.java.
4. Simplificar rótulo de personalização e explicar valor herdado em web/environments.js e web/app.js. Verificar pelo navegador com fixture local.
5. Executar regressões pertinentes e compatibilidade de bytecode Java 6. Preparar release imutável com notas, checksums e aviso para atualizar agentes; informar claramente se ainda não publicada.

Concluído: implementação, testes e preparação local de 2.0.49. Artefatos e 837 checksums verificados. Publicação não realizada. Testes nativos da nova release ainda pendentes.
