# Recuperador e instalador — validação de 16/10/2026

Fontes validadas mantendo 2.0.55 durante desenvolvimento. O objetivo é um único salto da 2.0.53 para a próxima entrega final.

- Regressão de fontes Java/HTTP/SQLite/JavaScript passou.
- AgentBundleInstallerTest: 4362 verificações, incluindo dez fronteiras de interrupção, rollback, retry e preservação. AgentV3UninstallInventoryTest: 2897 verificações. Serviços nesses testes são adaptadores.
- Extensão systemd: teste de propriedade, conteúdo, idempotência, recusa de customizações e ausência de alteração em SysV. Desinstalador reconhece somente o SHA-256 da extensão própria; compatível com shim de rollback que usa âncora antiga.
- OL8/systemd real: sete cenários de marcador, permissões, links, PID ainda ativo e PID reutilizado; start, restart e stop/start em unidade isolada.
- OL8/âncora real: manager novo em probe isolado instalou a extensão; restart confirmou exatamente um loop. Hashes de conf, bootstrap e current.json permaneceram iguais. A extensão de teste foi retirada ao final; isso não publicou nem trocou a versão do agente.
- Produto compilado com ECJ para bytecode Java 6. Reboot completo da VM e corte de energia não executados neste ensaio.
- Python 3.9 local e 3.6.8 na OL8: OpenSSL com configuração ambiente inválida, certificado DNS/cadeia/SAN, HTTPS real com resposta dividida, recusa de resposta 200 inválida. Assinatura real de pacote público conferida em Python 3.6. Verificações de assinatura continuam obrigatórias.
- 64 testes do publicador passaram, mais dois testes do modo local (loopback com SNI e falha TLS sem fallback). Modo local usa loopback e SNI/CA configurados; modo de laboratório mantém host fixo.

A âncora permanece imutável. A extensão é instalada pelo manager carregado do slot novo antes da confirmação, sem restart durante a transação. O serviço antigo precisa estar ativo para receber a correção. Bancos JDBC locais continuam sem host remoto; essa apresentação não foi alterada nesta correção.

O instalador completo é para dashboard novo. Atualização de dashboard existente usa o pacote de atualização, preservando banco, configuração e confiança. Instalação completa num servidor vazio, reboot do host e ensaio final a partir da 2.0.53 devem ser distinguidos das verificações de assinatura/fixtures e dos testes de restart aqui registrados.

## Pacotes finais 2.0.57

- Verificação independente dos cinco artefatos públicos, assinatura, 24 origens legadas e preservação da âncora 2.0.9 passou. Recibo privado em `output/dashboard-releases/2.0.57/evidence/public-verification.json`. Isso valida os artefatos, não equivale a certificação de infraestrutura de produção.
- O binário original do manager 2.0.53 carregou e instalou o pacote completo final 2.0.57; o manager final confirmou o mesmo pedido. Identidade, configuração, CA e âncora permaneceram iguais. Download/API e ações de serviço foram adaptadores no teste; as assinaturas, binários, slots e journal foram reais. Sem ponte nem versão 2.0.55 intermediária. Recibo `evidence/direct-v3-2.0.53.log`.
- As duas classes AgentRecoveryService do pacote final são byte a byte iguais às executadas no ensaio nativo da OL8.
- ZIP final do instalador, checksum `df16cd29d51f453e9ece3eec9658d8b4734108134c9e4f80cd1bd7f184172380`, passou em `--check --no-package-install` no Python 3.6.8 da OL8: pacote, assinaturas, JDK, URL e identidade TLS. Não instalou dashboard nessa máquina. Oito testes do instalador final também passaram localmente.

- OL7: os mesmos sete cenários e start/restart/stop-start passaram no systemd nativo, sem alterar o agente instalado. O probe com as classes finais carregou no Java 6 configurado da máquina.
