# Versão do WebLogic por evidência local

O usuário confirmou um WebLogic 10.3.6.0 em RHEL 5. Seu processo informa `-Dwls.home` e `-Dweblogic.home`, ambos apontando para o diretório `server`. O manifesto de `server/lib/weblogic.jar` contém `Implementation-Version: 10.3.6.0`, mas o JAR tem 23.553 entradas. O coletor ignora essas propriedades e recusa arquivos comuns acima de 5.000 entradas.

## Decisão

Reconhecer somente essas propriedades permitidas de processos cujo launcher é `weblogic.Server`. Consultar `lib/weblogic.jar` no diretório informado, mantendo o domínio separado da instalação. Continuar aceitando classpath explícito. Não inferir versão pelo nome da pasta, versão de domínio ou bibliotecas JDBC.

Para `weblogic.jar`, ler apenas o manifesto com acesso aleatório ao ZIP, validando o diretório central e a entrada local. Limites: arquivo de 128 MiB, 32.768 entradas, diretório central de 4 MiB, manifesto de 64 KiB, dados compactados de até 128 KiB. Continuam valendo 40 arquivos compactados, 8 MiB de leitura compartilhada e prazo da coleta. O conteúdo de classes não é lido, extraído ou executado. Os limites gerais de arquivos de aplicação permanecem iguais.

A alternativa de aumentar globalmente o limite de arquivos ampliaria o trabalho de coleta sem necessidade. Executar `weblogic.version` ou consultar JMX exigiria carregar código da instalação ou acesso administrativo; a leitura do manifesto já responde à necessidade.

## Saída e falhas

Preencher o campo existente `servers.version` e a versão de framework da aplicação associada. A tabela de servidores, os detalhes da máquina e o CSV já consomem esses campos. Evidência preserva o caminho do JAR. Ausência, exclusão, configuração de inspeção desativada, CRC inválido, duplicidade ou limite excedido deixam a versão desconhecida, com diagnóstico de cobertura quando apropriado.

## Validação

Reproduzir localmente o JAR com 23.553 entradas e manifesto 10.3.6.0, propriedades de inicialização sem `-cp`, dois processos com versões distintas e ausência de metadados. Testar ZIP inválido, manifesto duplicado, tamanho inflado, exclusões, orçamento compartilhado e contagem de arquivos. Compilar para Java 6 e executar os testes com heap de 96 MiB. A VM real permanece sem alteração; o SSH foi bloqueado pela revisão automática e os metadados reais foram fornecidos pelo usuário.
