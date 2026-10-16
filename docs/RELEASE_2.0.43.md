# Java Assessment 2.0.43 — instalação OL7

## Correções

- Após stop bem-sucedido do atualizador, aceita serviço loaded em inactive ou failed apenas quando MainPID=0, ControlPID=0 e não há Job. Corrige a parada failed/143 observada na OL7 sem limpar ou esconder seu diagnóstico. Estados desconhecidos, processos ativos, comandos de parada com falha e operações pendentes continuam bloqueados.
- Download por curl/wget executado com sudo de usuário comum é recusado antes de criar staging. Execute o download sem sudo; root direto continua suportado.
- Passo 2 explica que --system-root cria e ativa os serviços automaticamente.
- Script operacional específico para limpar a instalação de teste ol07 usa o desinstalador com inventário verificado; preserva JVM e aplicações. O registro remoto deve ser removido pela interface.

## Validação

Teste reproduziu SERVICE_FAILED antes da correção e passou depois (282 verificações de handoff). Comandos curl/wget: 59 verificações incluindo execução normal, root direto e sudo recusado. Interface testada em navegador; limpeza testada com seis cenários isolados. Gates de release validam JVM, assinatura, integração HTTP, instalação e remoção com fixtures. A reinstalação real do zero na OL7 ainda precisa ser executada pelo operador após publicar.

## Publicação e agentes

Atualizar o dashboard não atualiza os agentes automaticamente. Publique 2.0.43, baixe um NOVO script ou ZIP pelo dashboard e instale com --system-root. Para agentes existentes, use atualização no dashboard somente se elegíveis pelo pacote assinado; nos demais casos use o instalador completo. Não reutilize downloads anteriores neste teste de instalação. Acompanhe o resultado em Solicitações e confira a versão reportada. Não afirmar compatibilidade universal de Linux/Java ou x86 de 32 bits.
