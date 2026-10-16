# Desinstalação de instalação nova na OL7

Continuação autorizada da limpeza e da validação da instalação do zero.

Diagnóstico nativo em 157.151.0.69 confirmou recusa dos arquivos conf/installation-id.lock e logs/permission-check.json, da fila prepared-0, do recibo uninstall-owner fora da fila inicial e do update-public.der ausente do registro code/ de origem local. Não são evidências de aplicação do cliente; os dois primeiros são produzidos por AgentTool e os demais pertencem ao bootstrap assinado.

Correção estreita: aceitar os dois nomes operacionais exatos, índices prepared de 0 a 8, recibo extra somente byte-a-byte igual ao inventário do helper já validado contra assinatura, chave pública faltante na origem somente para localInstall e coincidente com coletor e helper. Assinaturas, hashes de código, caminhos e objetos desconhecidos continuam protegidos.

Recuperação de 2.0.37: verificação somente de leitura com o código corrigido confirmou o inventário completo. O script operacional preservou em /var/tmp os arquivos operacionais e a fila exata fixada por checksum, parou o recuperador e chamou remove/cleanup do desinstalador original. Resultado nativo: UNINSTALL_LOCAL_VERIFIED, LIMPEZA_LOCAL_OK, zero unidades e três diretórios ausentes; OpenJDK 1.6.0_41 preservado.

Entrega: nova release imutável 2.0.47; candidata 2.0.45 não publicada. A reinstalação limpa e o assessment final requerem novo download depois da publicação. Não declarar teste de instalação concluído só por sucesso de build ou registro.
