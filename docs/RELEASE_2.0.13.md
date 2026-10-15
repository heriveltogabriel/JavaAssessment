# Java Assessment 2.0.13

Preparada localmente em 2026-10-15 para continuidade da validação no laboratório. Dashboard e agente usam 2.0.13; o bridge interno usa 2.0.12. Publicar o dashboard continua sendo uma operação explícita do operador.

O operador confirmou a publicação e o funcionamento do dashboard. O recibo local confirmou versão 2.0.13 e HTTPS externo, com configuração e banco preservados; nenhum agente foi alterado pela publicação. O instalador para um dashboard novo está em `install/v2.0.13`, acompanhado do SHA-256.

## Mudanças

- Confirmação de atualização abre automaticamente o Work request retornado pelo servidor, em todos os ambientes, mostrando o lote inteiro. Erros, resposta perdida e respostas sem identificador mantêm o tratamento anterior; reenvio reutiliza a chave idempotente.
- Menu lateral **Java Assessment** abre **Machines / All environments**, limpando máquina, busca, datas e página anteriores. Leitores também acessam a listagem; ações administrativas continuam restritas.
- O bridge systemd para e confirma a inatividade do helper legado antes de iniciar o bootstrap independente. Preserva o estado original e só restaura o helper quando a falha anterior ao handoff permite fazê-lo com segurança. Inclui a leitura corrigida de propriedades vazias do systemd.

- Machines mostra o primeiro IP de rede, priorizando IPv4, e um botão “+N” para abrir os demais nos detalhes. Loopback e IPv6 link-local ficam fora da tabela; a lista completa permanece nos detalhes da máquina. IPv6 de rede continua visível quando não há IPv4. A alteração foi autorizada antes da publicação, mantendo a versão 2.0.13; somente arquivos web e pacotes do dashboard foram recompostos, com novos hashes. Agente, bridges e assinaturas permanecem idênticos.

## Correção do empacotamento antes da publicação

A primeira tentativa após o ajuste de IPs foi recusada por `Internal checksum mismatch: web/app.js`, antes da parada do serviço e da substituição de arquivos. Os manifestos internos dos ZIPs foram recompostos, junto com os hashes externos e as referências do publicador. O verificador local agora rejeita manifestos internos desatualizados, mesmo quando o hash externo está correto. O agente e todos os artefatos assinados permanecem idênticos. A versão continua 2.0.13 porque esta tentativa não instalou a versão no servidor.

## Validação

Testes de navegação desktop/mobile, perfis administrador/leitor, ACK perdido, erro de confirmação, API real V1/V3 e SQLite locais passaram. O fluxo confirma o pedido correto e mantém o assessment vinculado. A regressão antiga do modal teve um timeout no login após expirar a sessão; a repetição integral sem alterações passou, registrada como instabilidade do ensaio, não como correção de autenticação.

Os contratos passaram 75 verificações de manifesto, 14 de rotas e 256 de handoff. O builder executou suas regressões de descoberta, JDBC, autenticação, atualização, instaladores e publicação. O verificador público conferiu os cinco artefatos, oito perfis legados, assinaturas, bootstrap herdado e bytecode compatível com Java 6; vinte checks negativos passaram. Fonte empacotada e oito bridges conferidos com a correção de parada coordenada.

O operador confirmou anteriormente os fluxos normais em RHEL 5/Java 6, RHEL 7/Java 11 e RHEL 9/Java 11. RHEL 8/Java 17 concluiu após reparo assistido. O candidato 2.0.13 ainda precisa de prova nativa da concorrência corrigida; todos os canários anteriores já foram migrados. Falhas/reboot, recuperação na fronteira PREPARED do anchor imutável 2.0.9 e Linux/Java 32 bits permanecem sem validação suficiente para declarar liberação geral ao cliente.

## Atualizar os agentes

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza as máquinas automaticamente. Abra **Revisar agentes** e use o painel nas instalações elegíveis: agentes V3 usam pacote completo; perfis legados conhecidos a partir de 1.5.12 usam migração. O número da versão sozinho não comprova elegibilidade. Instalações desconhecidas ou sem atualizador root funcional exigem o instalador configurado do ambiente com `--system-root`.

Mantenha **Executar novo assessment após a atualização** marcado e acompanhe o pedido aberto automaticamente. Confirme a versão final e o recebimento da coleta. Os pacotes anteriores permanecem imutáveis.
