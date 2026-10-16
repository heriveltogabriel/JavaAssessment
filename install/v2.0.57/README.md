# Java Assessment 2.0.57

**Cliente já instalado na 2.0.53: use o pacote `update`, não o `installer`.**

- [Baixar atualizador](assessment-dashboard-update-2.0.57.zip) · [SHA-256](assessment-dashboard-update-2.0.57.zip.sha256)
- [Baixar instalador](assessment-dashboard-installer-2.0.57.zip) · [SHA-256](assessment-dashboard-installer-2.0.57.zip.sha256)

## Atualizar dashboard existente

Copie `assessment-dashboard-update-2.0.57.zip` e seu `.sha256` para o servidor do dashboard. Execute na pasta onde os arquivos foram copiados:

```sh
sha256sum -c assessment-dashboard-update-2.0.57.zip.sha256
unzip assessment-dashboard-update-2.0.57.zip -d dashboard-update-2.0.57
cd dashboard-update-2.0.57
sudo /bin/sh atualizar-dashboard.sh
```

Pare se o checksum não mostrar `OK`. Use uma pasta nova. O atualizador verifica a origem, cria backup privado, preserva banco/usuários/configuração/certificados e confirma a versão por HTTPS. Não precisa baixar Java nem pacotes pela Internet. Não execute `publish-dashboard.py` no cliente: esse wrapper é específico do laboratório; `atualizar-dashboard.sh` usa a instalação local.

## Instalar dashboard novo

```sh
sha256sum -c assessment-dashboard-installer-2.0.57.zip.sha256
unzip assessment-dashboard-installer-2.0.57.zip -d dashboard-installer-2.0.57
cd dashboard-installer-2.0.57
sudo /bin/bash install.sh
```

Requer EL8+ e JDK17+. Para usar Java já instalado e evitar repositórios: `sudo /bin/bash install.sh --java-home /caminho/do/jdk17 --no-package-install`.

## Agentes

**Atualize também os agentes:** atualizar o dashboard só disponibiliza o pacote. Em **Revisar agentes**, solicite diretamente **2.0.53 → 2.0.57**; não instalar 2.0.55 antes. Primeiro uma máquina, confirme a versão e a coleta, depois amplie o lote. A versão 2.0.56 é somente ponte interna para instalações legadas; V3 não passa por ela.

A correção de restart chega no mesmo pacote e preserva a âncora assinada. O recuperador antigo deve estar ativo para recebê-la. Customizações desconhecidas de serviços ou arquivos incompatíveis exigem diagnóstico; não force troca de JAR ou remoção de marcadores.

[Notas da versão](../../docs/RELEASE_2.0.57.md) · [Comandos e detalhes](../../docs/runbooks/update-from-2.0.53.md) · [Validações e limites](../../docs/validation/recovery-installer-2026-10-16.md)

Testes incluem atualização direta com o binário original 2.0.53 e pacote final assinado (serviços simulados), restart nativo do recuperador na OL8 e precheck do instalador no Python3.6 da OL8. Não houve instalação do pacote no cliente nem reboot completo de VM nesse ensaio.
