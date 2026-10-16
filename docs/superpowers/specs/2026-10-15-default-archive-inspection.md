# Inspeção Java por padrão

Desenho aprovado pelo usuário: inspeção ativa no padrão geral, herdada por ambientes e máquinas, com personalizações explícitas preservadas. A interface deve separar o valor efetivo da opção de personalizar.

O padrão geral persistido será ativado uma única vez na atualização. O armazenamento antigo não distingue o `false` inicial de um `false` global salvo; esse valor geral é tratado como o padrão a mudar, conforme o pedido. As exceções salvas nos ambientes/máquinas nunca são reescritas. Uma desativação geral feita depois da migração permanece desativada nos reinícios. Não alterar agendas, criar coletas ou modificar inventários antigos.

O coletor reconhece a raiz tanto no layout antigo quanto em releases V3. A varredura ignora a instalação, o atualizador e o desinstalador, antes dos limites. O runtime privado, incluindo java-assessment-runtime, não é inspecionado nem incluído como instalação Java. Java compartilhado fora dessas raízes permanece elegível; a versão da JVM do agente continua reportada. Exclusões respeitam limites de diretório, sem filtrar caminhos apenas por substring. Evidência explícita de aplicações reais permanece disponível.

Manter os limites atuais de tempo, arquivos, metadados e memória e a prioridade já existente dos processos antes da varredura. Não aumentar orçamento nem executar código descoberto. A mudança de coletor exige atualizar os agentes e realizar nova coleta.
