# Recuperação do instalador Ansible — Implementation Plan

> **For agentic workers:** executar este plano por etapas com executing-plans; escopo aprovado pelo usuário em 26/10/2026.

**Goal:** repetir o playbook após falhas sem apagar agentes íntegros nem perder identidades por padrão.

**Architecture:** helper POSIX distribuído junto ao playbook classifica estado local e coordena os instaladores existentes. `auto` preserva agentes saudáveis, retoma instalação clássica com identidade e reativa serviços V3 verificados; `reinstall` usa remoção oficial após backup. Resíduos sem identidade só são retirados quando não há serviços, processos ou helpers associados. Estados ambíguos/operações pendentes bloqueiam antes da remoção. Não alterar código do agente nem versão 2.1.24.

**Tech Stack:** Ansible raw/script, shell POSIX, ferramentas Linux já exigidas pelo agente, testes Python/Ansible locais.

- [x] Criar `install/ansible/files/manage-agent.sh`: probe/apply, validação de proprietário/links, locks, Java e pacote antes de alterações, backups privados, retomada e reinstalação explícita.
- [x] Integrar `install/ansible/install-agents.yml`: modos auto/reinstall, resultado legível por host, download privado antes de mudanças; manter no_log e limpeza temporária.
- [x] Ampliar `tests/ansible-install-test.py` e criar testes do helper com limites de SO simulados: saudável, parcial, download/Java inválidos, identidade, V3, serviços, locks, links, remoção recusada, recuperação e reinstalação.
- [x] Executar testes, syntax-check, shell syntax e diff-check. Corrigir somente defeitos desta mudança.
- [x] Atualizar exemplos, README principal, índice install e documentação do pacote. Explicar escopo de recuperação e backup contendo credenciais; nenhum restore automático nem remoção do cadastro no dashboard.
- [x] Gerar ZIP Ansible determinístico e SHA-256 com lista explícita de arquivos públicos; verificar conteúdo/CRC/hash.
- [x] Commit apenas dos arquivos desta tarefa e push main; confirmar SHA remoto. Preservar alterações locais anteriores.
