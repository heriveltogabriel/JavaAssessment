# Atualização do agente pelo dashboard

A atualização usa HTTPS iniciado pelo agente, com sua identidade já registrada. Não exige chave SSH, porta de entrada na VM ou IP público na máquina do agente.

## Primeira instalação

Instale uma vez a versão habilitadora com `sudo ./install.sh --system-root`, a partir do ZIP do ambiente extraído em uma pasta nova. O instalador preserva a identidade existente e instala o executor protegido de atualização. Agentes antigos, instalações manuais e cron aparecem como incompatíveis até essa etapa.

## Passagem para 2.0.2

A 2.0.2 corrige o executor, seus scripts e a confirmação de heartbeat. Execute uma vez o instalador completo com `--system-root` em cada máquina, preservando a identidade existente. O dashboard lê a geração efetivamente instalada: `update-v1` não atende ao mínimo assinado `minUpdater=2` desta entrega e recebe a orientação para usar o instalador. `update-v2` aceita manifestos de gerações 1 e 2, mas continuam obrigatórias versão superior e igualdade dos arquivos de suporte. O protocolo de solicitação permanece `UPDATE_AGENT_V1`.

Falta de rede ou timeout do heartbeat deixam o pedido sem confirmação, com journal e backup preservados para nova tentativa. Falha do probe de código continua provocando rollback. Não cancele/apague arquivos de uma atualização que já começou para forçar outra instalação.

## Uso

Como Administrador, abra o ambiente e a aba **Machines**. Clique em **Atualizar agentes do ambiente**, ao lado de **Enrollment codes**, para ver todas as máquinas desse ambiente. A busca e a paginação da tabela não reduzem essa prévia. Confira o nome do ambiente, a versão instalada e a versão disponível; marque máquinas específicas ou **Selecionar elegíveis** e confirme. Em **All environments**, o botão **Atualizar agentes** permite selecionar entre todos os ambientes. Máquinas sem ambiente têm sua própria seleção.

O menu **⋮ → Atualizar agente** de uma linha atua somente naquela máquina. A entrada global **Actions → Atualizar agentes** continua disponível e segue o contexto atual. O dashboard explica por que uma máquina não pode ser atualizada. Acompanhe os estados após confirmar e use **Ver em Work requests** para abrir o histórico do lote e os detalhes de cada máquina. O resumo na tela do ambiente permite retornar às atualizações em andamento mesmo após fechar a janela. O perfil Leitura pode acompanhar pedidos, mas não pode iniciar nem cancelar atualizações. Consulte [Acompanhamento em Work requests](WORK_REQUESTS.md).

O agente recebe a solicitação na próxima conexão; a máquina offline aguarda até expirar o pedido. Em um lote, somente uma máquina é atualizada por vez. Uma falha ou restauração cancela as solicitações que ainda aguardavam naquele lote. Um resultado sem confirmação interrompe o avanço até haver uma confirmação válida.

## Proteções e limites

O formato inicial atualiza `collector.jar`, que contém o código Java do agente. Scripts de instalação, bibliotecas TLS, chave pública, configuração, identidade, ambiente e inventário são preservados. O agente exige que todos os arquivos estáveis instalados coincidam com os hashes do manifesto assinado. Uma versão que dependa de mudanças nesses arquivos exige o instalador completo e não é aplicada pelo atualizador remoto.

O pacote precisa ter assinatura RSA3072/SHA256 válida, versão superior à instalada, tamanho e hash corretos e compatibilidade com o executor instalado. O dashboard não pode enviar scripts livres, uma URL arbitrária ou um caminho para executar como root. O executor troca o JAR usando arquivo temporário e rename no mesmo sistema de arquivos, mantém backup privado e verifica o novo código. Falha local restaura a versão anterior. Falta de rede não é interpretada como sucesso.

Enquanto há atualização ativa, coleta nova, mudança de ambiente, exclusão e desinstalação são bloqueadas para aquela máquina. O publicador também bloqueia a atualização do próprio dashboard enquanto houver pedidos de atualização de agente pendentes ou sem confirmação. Se um pedido começar após o dashboard voltar ao ar e a verificação da publicação falhar, o publicador preserva a versão que autorizou esse pedido, em vez de remover seu catálogo num rollback.

## Chave de assinatura

A chave privada fica somente no Mac em `output/agent-update-signing/private-key.pem`, em diretório com permissão `0700` e arquivo `0600`. Ela não é enviada ao dashboard nem incluída nos ZIPs. Preserve um backup protegido dessa chave para assinar versões futuras. O instalador fixa a chave pública inicial; este protocolo não troca a chave silenciosamente. Perder a chave privada exigirá um procedimento explícito de migração de confiança e reinstalação.

O gerador `python3 ops/build-dashboard-release.py` recompila dashboard e agente com a mesma versão, cria o pacote e seu manifesto assinado e mantém as entregas anteriores imutáveis. Publicar o dashboard disponibiliza uma versão nova; atualizar agentes continua exigindo a ação explícita do administrador.
