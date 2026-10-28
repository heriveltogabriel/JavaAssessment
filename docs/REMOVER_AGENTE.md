# Remover e limpar o agente

Copie `scripts/remover-agente.sh` para a máquina Linux, fora de `/opt/java-assessment-*`.
O arquivo é independente do checkout e usa o desinstalador oficial instalado.

```sh
sudo sh remover-agente.sh --check
sudo sh remover-agente.sh --execute
```

A prévia verifica caminhos protegidos, definições dos serviços e operações pendentes.
Não garante que todo o inventário será aceito: a validação completa é feita pelo
executor oficial durante a remoção. Leia o resultado antes de executar.

A execução para os serviços do coletor, atualização/recuperação e desinstalação;
verifica se não restaram processos usando os diretórios; preserva travas órfãs
conhecidas em um diretório privado; chama `uninstall-local.sh remove` e `cleanup`.
Remove configuração, token/identidade local, coletas, logs e componentes que o
inventário oficial reconhece como pertencentes ao agente. Não remove Java do
sistema, aplicações nem dashboard. O cadastro e o histórico na console permanecem.

## Resultado e diagnóstico

Sucesso: `JA_REMOVAL=COMPLETE`. O caminho `JA_DIAGNOSTIC` contém o log e eventuais
travas preservadas; permissões privadas. Trate esse diagnóstico como informação
interna antes de compartilhá-lo. Os arquivos de coordenação
`/opt/java-assessment-ansible.lock` e `/opt/java-assessment-lifecycle.lock` permanecem
para não criar uma corrida com outros processos; não são serviços nem travas de polling.
O executor oficial também pode deixar `/var/log/java-assessment-uninstall-last.txt`.

O script recusa operações pendentes, dono de trava ainda vivo, instalações sem
helper confiável, caminhos montados, links, serviços personalizados ou arquivos
não reconhecidos. Não há opção de remoção forçada. Se a instalação ficou incompleta
antes de criar o helper, será necessário diagnosticar os resíduos antes de removê-los.
Versões antigas podem recusar arquivos operacionais que não conhecem.

Em falha, a saída informa `JA_ERROR` e o log. Serviços podem permanecer parados;
não há restauração automática nem reinício que possa disparar uma atualização.
Não repita às cegas se houve remoção parcial. Cancelar pedidos na console e aguardar
as operações em andamento antes da execução evita conflito com atualizações.

## Validação

- `sh -n scripts/remover-agente.sh`: sintaxe POSIX.
- `python3 tests/remover-agente-test.py`: oito testes isolados de travas, recusas,
  argumentos e falha de parada. Verificações GNU/Linux de propriedade e montagem
  são substituídas nas fixtures executadas no macOS.
- Não foi feita desinstalação real em hosts Linux nesta entrega. O suporte a
  systemd/SysV reutiliza as verificações do gerenciador Ansible e do helper oficial;
  a validação real em RHEL/OL ainda deve ser feita em máquina descartável.

Nenhuma versão do produto foi alterada por este utilitário.
