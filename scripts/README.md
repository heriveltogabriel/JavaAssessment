# Scripts de administração

Utilitários para execução manual. Leia a documentação de cada script antes de
executá-lo; não é necessário clonar todo o repositório para usar o arquivo `.sh`.

| Script | Finalidade | Documentação |
| --- | --- | --- |
| [remover-agente.sh](remover-agente.sh) | Remover o agente local usando o desinstalador oficial e preservar Java, aplicações e dashboard. | [Guia de remoção](../docs/REMOVER_AGENTE.md) |

## Remover o agente

Baixe `remover-agente.sh`, copie para a máquina Linux e execute fora dos diretórios
do agente:

```sh
sudo sh remover-agente.sh --check
sudo sh remover-agente.sh --execute
```

A primeira chamada faz uma prévia; a segunda remove os componentes e os dados
locais reconhecidos do agente. Operações pendentes, arquivos desconhecidos ou
helper ausente impedem a remoção. O cadastro e o histórico no dashboard permanecem.

Os scripts não são executados automaticamente nem alteram a versão do produto.
