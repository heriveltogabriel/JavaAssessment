# Desinstalação com atualização completa V3

O executor continua anunciando `uninstall-v1`. O protocolo com o dashboard é preservado; a remoção local reconhece os diretórios versionados, o gerenciador independente e a âncora de recuperação.

## Propriedade e retomada

Cada slot é validado contra seu manifesto e sua assinatura RSA-3072. O bootstrap inicial tem recibo próprio e bibliotecas próprias. O executor guarda seus manifestos, assinaturas e pin públicos para continuar verificável depois que os diretórios do agente forem removidos.

A remoção é recusada quando houver atualização ativa, identidade divergente, código alterado ou arquivo/diretório desconhecido. Transações concluídas com limpeza pendente só são removidas quando os arquivos restantes correspondem ao inventário e aos hashes da fila de limpeza. O plano local durável permite retomar após interrupção, mesmo se o pin ou o JAR original já tiver sido apagado.

A troca do próprio executor usa intent durável por arquivo e renomeação atômica. Um `.copy` interrompido só é tratado quando o intent identifica exatamente o destino e o hash de uma fonte validada no snapshot, slot assinado ou bootstrap. Sem esse comprovante, o arquivo é preservado e a recuperação é recusada.

A CA configurada é copiada para o executor independente com limite de 512 KiB. A cópia privada permite confirmar a desinstalação após remover o coletor; o arquivo original externo, a JVM e as aplicações permanecem intactos. JSON e configuração mantêm os limites anteriores de 32 KiB.

## Exceção explícita no primeiro rollback

Se a primeira migração voltar ao coletor legado, o coletor, a configuração e a identidade anteriores são restaurados. O snapshot do executor de desinstalação também permanece intacto para diagnóstico.

O binário do executor de desinstalação **permanece V3**, usando a âncora inicial assinada e suas bibliotecas. Seus scripts são obtidos de um slot verificado, com cópias dos recibos assinados dentro do executor. A unidade e a capacidade anteriores são restauradas. Esse shim mantém `uninstall-v1` e permite remover com segurança a estrutura de recuperação que ainda existe após o rollback. Não se promete restaurar o hash original do helper nessa situação.

Os recibos próprios tornam o shim independente dos slots de tentativa: a limpeza desses slots após a retenção de diagnóstico não quebra a desinstalação. Em rollback entre versões já V3, o snapshot do helper anterior é restaurado integralmente.

## Evidência local e limites

`AgentV3UninstallInventoryTest` usa assinaturas reais e arquivos temporários para validar remoção, snapshots, rollback, retenção, arquivos desconhecidos, retomada e preservação de arquivos externos. As chamadas de serviço usam adapters declarados. Os testes dos scripts usam serviços simulados. `UninstallWorkerTest` usa HTTP de loopback e um executável com os caminhos transplantados para pastas temporárias.

Esses testes não substituem a validação de instalação, serviços, reinício e recuperação em Linux real. A publicação permanece condicionada a esse gate.
