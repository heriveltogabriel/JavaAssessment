# Versão do dashboard

A versão é comum ao dashboard e ao agente e não usa sufixo beta. A entrega 2.0.0 reúne os retoques de interface e a revisão do reload. Cabeçalho, menu lateral, Sobre, `/health`, log inicial, exportação CSV, binário do agente, manifesto, metadata e nome do ZIP usam o mesmo número. A interface apresenta a versão completa como `Versão 2.0.0`.

## Preparar uma nova entrega

No Mac, na pasta do projeto:

```sh
python3 ops/build-dashboard-release.py
```

O comando incrementa automaticamente o patch, gera as versões do dashboard e do agente, recompila os dois, executa testes HTTP com o JAR real do agente e gera os pacotes em `output/dashboard-releases/<versão>/`. Ele imprime o caminho absoluto do publicador. Cada entrega tem sua própria pasta e seus próprios checksums; entregas anteriores não são sobrescritas. O agente continua compatível com Java6 (bytecode50).

Uma preparação que falhou reserva seu número; corrigir o problema e preparar outra entrega gera o próximo. Reenviar o mesmo pacote ou reiniciar o serviço não gera uma versão nova. Use este gerador para as próximas publicações; os geradores antigos representam entregas históricas.

Para uma mudança explícita de versão principal, use `python3 ops/build-dashboard-release.py --version 2.0.0`. O número solicitado deve ser uma versão estável maior que todas as versões já reservadas e que a versão atual do agente. Depois da entrega 2.0.0, o comando sem argumentos continuará com 2.0.1. Não repita a reserva de uma versão já preparada.

## Publicar

Execute no seu Terminal o comando `python3` com o caminho de `deployment/publish-dashboard.py` impresso pelo gerador. O publicador faz SCP, usa a chave `~/OneDrive/id_rsa` e publica somente em `167.126.4.37`. Ele preserva banco, configuração, CA e identidades; cria backup privado; inclui `VERSION` no rollback e exige que `/health` responda com a versão completa esperada. Não atualiza agentes nas VMs.

O pacote inclui o runtime Java sem compilador e o agente com a mesma versão da entrega. Aceita somente os hashes de bases conhecidas: entrega CSV confirmada, entrega de runtimes1.5.1 preparada e entregas anteriores do fluxo sequencial. A ausência de um recibo da última tentativa não é tratada como publicação bem-sucedida. Bases divergentes ou versão incorreta do pacote do agente são recusadas antes de parar o serviço.

Histórico de desinstalação com estados `SUCCEEDED`, `FAILED`, `CANCELLED` ou `EXPIRED` permite a atualização e é preservado. Pedidos `WAITING`, `UNINSTALLING` ou `UNCONFIRMED` bloqueiam a atualização; o diagnóstico apresenta apenas estados e quantidades. Estados desconhecidos também bloqueiam. A checagem usa uma transação de leitura e é repetida após a parada do serviço e após a publicação. O publicador não cancela pedidos nem apaga registros.

Após publicar, recarregue a página. A versão aparecerá no cabeçalho, no menu e em Sobre. Baixe novamente o ZIP do ambiente para obter o agente da mesma versão e atualize cada VM: extraia em uma pasta nova e execute `sudo ./install.sh --system-root`. A configuração e a identidade existentes são preservadas. As VMs continuam reportando a versão real antiga até receberem essa atualização; publicar o dashboard não atualiza o agente instalado.

Se o servidor não responder com metadados válidos, a interface mostra a versão como indisponível.

Tudo é preparado e validado localmente pelo Codex. A publicação remota é executada pelo usuário no Terminal.

A partir de 1.5.4, o login usa contas individuais (Administradores/Leitura). Veja `docs/USER_ACCESS.md` para primeiro acesso, permissões e recuperação protegida. Após ativar as contas, o rollback para autenticação por senha compartilhada é bloqueado.

A partir de 1.5.7, o menu ⋮ das máquinas permite mover, remover do ambiente e excluir o cadastro com proteção. Veja `docs/MACHINE_ACTIONS.md`. A preservação das contagens é verificada com o serviço parado; contagens após reiniciar são informativas porque administradores podem excluir registros durante a verificação.
