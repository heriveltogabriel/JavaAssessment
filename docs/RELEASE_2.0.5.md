# Java Assessment 2.0.5

## Overview compacto

O overview reúne quatro painéis: versões Java, sistemas operacionais, Java Servers por produto e versão e JDBC por nome do datasource e versão do driver. Cada painel mostra até três grupos e reúne o restante em “Outros”, mantendo o total do ambiente. Versões ausentes aparecem como “Não informada”. O painel JDBC usa os datasources encontrados; bibliotecas JDBC avulsas não entram nessa contagem.

A seção de máquinas passa a ser uma tabela com busca e dez itens por página. A busca consulta todo o inventário do ambiente, incluindo máquinas fora da página atual. Os gráficos continuam mostrando os totais do ambiente. As ações individuais e os links para detalhes permanecem disponíveis. Em telas pequenas, somente a tabela tem rolagem horizontal.

## Aviso de atualização

Administradores recebem um aviso quando há agentes ativos com versão conhecida anterior à versão do dashboard. O aviso considera todos os ambientes e abre a revisão de elegibilidade; a abertura não agenda atualizações. O acesso de leitura não mostra esses controles. O aviso é recalculado após o próximo contato confirmado do agente e atualização dos dados da tela.

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza os agentes das máquinas. Abra **Revisar agentes** para conferir a versão e a elegibilidade. Instalações 2.0.2 e 2.0.3 com atualização remota habilitada e arquivos de suporte originais podem receber 2.0.5 pelo painel. Instalações anteriores precisam do instalador completo com os componentes de suporte atuais. Baixe o pacote configurado para o ambiente no dashboard e execute `sudo /bin/sh ./install.sh --system-root`, preservando o cadastro e a configuração existentes. Acompanhe o resultado em **Work requests** e confirme a versão informada pela máquina.

A entrega mantém os limites de memória e a descoberta do agente; a mudança de versão mantém dashboard e agente identificados como 2.0.5. Não é necessário executar um novo assessment apenas para reorganizar os dados existentes no overview. Se a máquina ainda não possui as correções de descoberta anteriores, atualize o agente e execute uma nova coleta.

## Validação

- Build integrado do dashboard e agente, incluindo versão unificada, assinaturas, autenticação, inventário JDBC, descoberta, Work requests e fixtures de publicação/recuperação.
- Classes do agente compiladas para Java 6 e conferidas por compilação independente; heap operacional permanece em 96 MiB.
- Overview HTTP/SQLite e Chrome com 100 máquinas fictícias, paginação, busca, isolamento por ambiente e layout desktop/mobile.
- Regressões de renderização, refresh e aviso administrativo; a revisão de agentes não agenda pedidos automaticamente.
- Os 23 arquivos de suporte assinados são idênticos aos de 2.0.2 e 2.0.3; o requisito de atualizador continua em geração 2.

Esses testes são locais. Não comprovam instalação em todas as distribuições nem execução em Linux/Java de 32 bits. A publicação no servidor depende da execução e confirmação do script de entrega.

 A versão 2.0.4 foi reservada, mas não publicada: uma restrição de execução local interrompeu seu teste HTTP. A numeração não foi reutilizada.

Os PDFs em `install/` continuam na edição 2.0.2. Os procedimentos de instalação permanecem aplicáveis; utilize os nomes dos pacotes da versão atual.
