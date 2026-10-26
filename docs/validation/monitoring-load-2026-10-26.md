# Correção da carga de acompanhamento — 26/10/2026

Base publicada: 2.1.26 / `4cbefc3`. Correção de fontes sem incremento e sem alteração dos instaladores publicados. Nenhum host do cliente acessado.

## Diagnóstico confirmado no código

- Poll de acompanhamento a cada 5 s (lista e detalhe) mais state a cada 15 s.
- Cada GET de work-requests rodava manutenção de atualizações/expiração e adquiria a transação de escrita global.
- Teste HTTP com transação concorrente aberta reproduziu timeout antes da correção: `/private/tmp/ja-monitoring-before.log`. A preparação inicial do teste tinha uma coluna incorreta na fixture de metadata; foi corrigida para usar a API existente antes de reproduzir o defeito.

## Correção

GETs usam readSnapshot e não fazem manutenção. O mesmo método de manutenção é chamado pelo agendador independente de 5 s. Poll da tela espera a resposta antes de contar 30 s; erros elevam o intervalo a 60/120 s e sucesso o restaura. Aba oculta, troca de escopo e logout impedem novas chamadas. Em Solicitações, state automático foi suprimido; ações manuais continuam disponíveis.

Com detalhe aberto e sem interações, o fluxo anterior previa cerca de 28 consultas/minuto (24 de acompanhamento + 4 de state), contra no máximo cerca de 4/minuto após a correção, fora a carga inicial. É uma estimativa dos intervalos; não uma medição do servidor do cliente, e não inclui chamadas dos agentes ou outras abas.

## Validação

- `WorkRequestsHttpTest`: PASS, **186 verificações**. Lista, resumo ativo e detalhe respondem enquanto o escritor mantém uma transação aberta. GET não expira pedidos; o método de manutenção independente os expira. Mantidos escopo, permissões, histórico, cancelamento, integridade e ausência de credenciais. Log `/private/tmp/ja-monitoring-after.log`.
- `tests/interface-refresh.test.cjs`: PASS, **29 verificações**, incluindo relógio controlado: 30 s, espera pela resposta mais lenta, 60/120 s em falhas, recuperação, aba oculta e limpeza com resposta pendente; nenhum state automático em jobs e atualização explícita preservada.
- `ops/test-source.sh`: PASS, suíte completa. Log `/private/tmp/ja-monitoring-source.log`.
- `tests/work-requests-browser.cjs`: PASS, navegação desktop/mobile, retenção de foco/rascunhos, respostas lentas, erro, leitor, escopo e logout. Log `/private/tmp/ja-monitoring-browser.log`.
- `tests/agent-campaigns-browser.cjs`: PASS com HTTP/SQLite reais e **5.000 hosts sintéticos**, paginação e cancelamento depois de fechar a página. A fixture usa o mesmo método de manutenção da produção com relógio acelerado de 250 ms para o teste; produção permanece em 5 s. GETs não fazem o cancelamento avançar. Log `/private/tmp/ja-monitoring-campaign-browser.log`.
- `git diff --check`: PASS.

Estes testes não simulam milhares de transferências simultâneas ou o contato real de todos os agentes. Não foi medida a redução de CPU no cliente. A correção precisa entrar em uma nova release e ser validada com sua origem antes de instalar.
