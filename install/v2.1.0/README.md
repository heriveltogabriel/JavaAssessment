# Java Assessment 2.1.0

A **2.0.57 permanece preservada para o cliente**. Esta entrega adiciona Administração → Atualização do dashboard. Foi publicada e verificada no dashboard de laboratório; o operador confirmou a instalação e atualização dos agentes. Essa confirmação não representa implantação no cliente.

## Primeira atualização para 2.1.0

Use [o pacote de atualização](assessment-dashboard-update-2.1.0.zip) em uma instalação compatível. Ele instala o serviço independente que permite usar o painel nas próximas versões.

```sh
unzip assessment-dashboard-update-2.1.0.zip -d dashboard-update-2.1.0
cd dashboard-update-2.1.0
sudo /bin/sh atualizar-dashboard.sh
```

Use uma pasta nova. O script verifica os arquivos, cria backup privado, preserva banco e configuração e confirma a versão via HTTPS. A operação não atualiza os agentes. O wrapper `publish-dashboard.py` é específico do laboratório; use `atualizar-dashboard.sh` no servidor do cliente.

## Instalação nova

[Instalador completo](assessment-dashboard-installer-2.1.0.zip), destinado a um servidor novo com EL8+ e JDK17+:

```sh
unzip assessment-dashboard-installer-2.1.0.zip -d dashboard-installer-2.1.0
cd dashboard-installer-2.1.0
sudo /bin/bash install.sh --java-home /caminho/do/jdk17 --no-package-install
```

## Próximas atualizações pelo painel

Abra **Administração → Atualização do dashboard**, envie o pacote assinado `assessment-dashboard-web-update-X.Y.Z.zip`, confira a preparação e confirme **Atualizar e reiniciar** com sua senha. O serviço independente mantém o progresso mesmo se o navegador fechar. A tela permite cancelar a preparação e restaurar a última versão quando compatível. Falha de saúde provoca tentativa de retorno automático.

O ZIP `web-update` da própria 2.1.0 está incluído para validação/referência. Ele não instala o executor em uma 2.0.57 e não pode ser reaplicado numa 2.1.0: o painel aceita somente versões posteriores compatíveis.

**Atenção: atualize também os agentes.** O dashboard apenas disponibiliza os pacotes. Há prova local da atualização direta **2.0.53 e 2.0.57 → 2.1.0**, com binário original e pacote final assinado, usando adaptadores de sistema e rede. Antes de ampliar o lote, valide uma máquina real. Instalações sem rota elegível exigem o pacote configurado do ambiente e instalador completo.

[Notas da versão](../../docs/RELEASE_2.1.0.md) · [Guia operacional](../../docs/DASHBOARD_SELF_UPDATE.md) · [Validações e limites](../../docs/validation/dashboard-self-update-2026-10-16.md)
