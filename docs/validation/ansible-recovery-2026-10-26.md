# Ansible: recuperação de instalações — 26/10/2026

Escopo: pacote autônomo `install/ansible`; dashboard e agente permanecem na versão 2.1.24. Nenhum instalador versionado, código Java, protocolo de atualização ou credencial do cliente foi alterado.

## Fluxos

- `auto` ignora instalações localmente íntegras; retoma instalações clássicas reconhecidas com identidade; reativa serviços V3 verificáveis, sem substituir slots/recibos.
- Resíduos anteriores ao cadastro só saem do caminho ativo depois da verificação de pacote/Java, ausência de serviços/processos/agendamentos e backup protegido.
- `reinstall`, selecionado por host, usa o desinstalador existente. Uma recusa nunca é substituída por remoção recursiva forçada. Novo cadastro exige revisar o registro anterior no dashboard.
- Locks/operações pendentes, V3 sem ponteiro, código V3 indisponível, serviço alterado, links, montagens, identidade incompleta e arquivos desconhecidos interrompem o fluxo.
- A cópia do pacote executada como root é verificada novamente e fica protegida. O instalador lê essa cópia com a identidade root; não tenta ler a área privada como o usuário original do sudo.
- Logs e backups ficam em `/opt/java-assessment-ansible.XXXXXX`, acesso exclusivo de root. Podem conter segredos; não são artefatos de suporte públicos.

## Verificações reproduzíveis

Ambiente de controle: Python 3.12 e Ansible Core 2.19.14. Executar com um ambiente Python que contenha Ansible/PyYAML:

```sh
python tests/ansible-recovery-test.py
python tests/ansible-install-test.py
ansible-playbook -i install/ansible/inventory.example.yml install/ansible/install-agents.yml --syntax-check
ansible-playbook -i install/ansible/inventory.example.yml install/ansible/install-agents.yml --list-hosts --limit producao
/bin/sh -n install/ansible/files/manage-agent.sh
python ops/build-ansible-package.py
```

**Resultado:** 27 testes do helper e 14 testes do playbook passaram (41 cenários). A suíte do helper terminou em 409,9 s e a integração em 237,7 s, com as simulações locais descritas abaixo.

Os testes executam o shell e as condições do playbook, com fronteiras de SO simuladas: systemd/SysV, proprietário, Java, wget e instalador/desinstalador. Exercitam cópias privadas, hashes, preservação de identidade e permissões do backup, limpeza temporária e ausência de tokens na saída. Os testes SysV cobrem preservação, reativação e reinstalação.

A sintaxe e a seleção de dois hosts do grupo produção foram verificadas. O construtor usa uma lista explícita de sete arquivos públicos, confere CRC, conteúdo e SHA-256 do ZIP; inventários reais, Vault, chaves e `private/` não entram no pacote.

## Limites

Não houve SSH/deploy ou reinstalação em VM real nesta tarefa. Os simuladores não validam a implementação de systemd, Java ou o desinstalador de cada versão instalada. Validar primeiro um host com `--limit` por distribuição, revisar o log privado e confirmar contato/coleta no dashboard. A recuperação de uma instalação com metadados danificados continua exigindo diagnóstico; não se promete reparo automático de qualquer estado.
