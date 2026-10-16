# Instalação OL7 após parada do atualizador

Escopo aprovado pelo usuário: corrigir o instalador, esclarecer download/instalação, preparar limpeza local e release para repetir a instalação do zero.

O systemd da OL7 confirmou MainPID=0, ControlPID=0 e Job vazio após stop, mas manteve failed/143. O bootstrap exige inactive além do predicado stopped existente. Remover essa condição redundante somente após stop retornar sucesso; continuar exigindo loaded, estado inactive ou failed, zero processos e zero job. Não tratar failed como serviço saudável nem relaxar assinatura, identidade, flock ou validação de arquivos. Timeouts, estado incompleto e processos ativos continuam bloqueadores.

Alternativas rejeitadas: reset-failed automático mascara diagnóstico; SuccessExitStatus=143 depende de alterar a unidade antiga e não resolve origens já instaladas. A correção usa o estado efetivo de parada já validado pelo protocolo.

Download feito por sudo a partir de usuário comum será recusado antes de criar arquivos. Root direto continua permitido. O passo 2 explica que --system-root instala e configura serviços automaticamente. Limpeza local usa o executor de desinstalação existente e recusa operação pendente; exclui apenas downloads reconhecidos da instalação de teste. Não remove JVM do sistema nem registros do dashboard.

Validar estados systemd, scripts curl/wget executados com fixtures, interface, pacote assinado e instalador. A reinstalação real na OL7 será validada pelo usuário após publicação; não prometer teste remoto ainda não realizado.
