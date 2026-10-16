# Atualização de uma instalação existente 2.0.53

Use o ZIP **assessment-dashboard-update-VERSAO.zip** no servidor do cliente. O ZIP **installer** é para uma instalação nova e recusa um dashboard já instalado.

1. Confira o SHA-256, extraia o ZIP de atualização numa pasta nova e entre nela.
2. Execute `sudo /bin/sh atualizar-dashboard.sh`.
3. Confirme a versão no dashboard. Em **Revisar agentes**, selecione primeiro uma máquina e solicite a versão final. Depois de confirmar, atualize as demais. Execute uma coleta após a atualização.

O atualizador local usa a URL configurada para validar TLS e o SAN, conectando em loopback. Não depende de DNS externo, proxy ou acesso à Internet. Verifica os pacotes, a origem instalada e a chave pública dos agentes; faz backup privado do banco/configuração e troca o código com o serviço parado. Não reinstala, não recria usuários, não troca CA e não altera a URL. Recusa pedidos de atualização/desinstalação ainda ativos. O caminho do backup e o relatório de confirmação aparecem na saída; consulte o recibo antes de tentar novamente após erro.

**Agentes V3 2.0.53:** um pedido direto para a versão final, sem instalar 2.0.55 primeiro. O manager antigo recebe e verifica o pacote completo; o manager novo instala a correção de partida antes de confirmar. O número reservado à ponte de migração não é um passo manual; instalações que já usam V3 não usam a ponte.

A atualização do dashboard não atualiza agentes automaticamente. Elegibilidade depende dos arquivos assinados e do estado da instalação, não só do número. A extensão de partida suporta o serviço original; customizações desconhecidas são recusadas.

Antes do primeiro canário, se necessário, confira na máquina:

```sh
sudo systemctl is-active java-assessment-recovery.service
sudo systemctl show java-assessment-recovery.service -p MainPID -p ActiveState -p SubState
```

Em SysV, use `sudo service java-assessment-recovery status`. Não remova marcadores ou arquivos manualmente. Se o recuperador antigo já estiver parado, ele não consegue baixar a própria correção: confirme o diagnóstico e retome-o antes do pedido. Se está ativo, a correção é recebida pelo fluxo normal. Não é necessário reiniciar a máquina para atualizar.

Validações e limites em [recuperador/instalador](../validation/recovery-installer-2026-10-16.md). Os testes de serviço foram feitos no laboratório; não presumem as regras de rede, certificados ou personalizações do cliente.
