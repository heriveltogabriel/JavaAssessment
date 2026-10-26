# Ansible Agent Update Implementation Plan

> Execução nesta sessão, sem delegação. Desenho autorizado pelo pedido de preparar o playbook e pela proposta imediatamente anterior.

**Goal:** Orquestrar atualizações oficiais por lotes limitados, sem reinstalar ou trocar identidades.
**Architecture:** Playbook run_once delegado ao controlador, helper Python stdlib com sessão HTTPS, journal idempotente e relatório sanitizado. Usar API existente de state/agent-updates/work-requests.
**Tech Stack:** Ansible Core, Python 3.9+, urllib, JSON, fcntl.

- [x] Criar helper `install/ansible/files/update-agents.py`, validando seleção, TLS, versão e parâmetros; API paginada e falhas sem expor respostas privadas.
- [x] Implementar journal antes do POST (`requestKey` UUID), lock, retomada, polling limitado, cancelamento somente de WAITING e confirmação SUCCEEDED + collectorVersion.
- [x] Criar `update-agents.yml`, exemplos de inventário e vars/Vault. `run_once`, `delegate_to: localhost`, stdin/no_log, prévia padrão, relatório no controlador.
- [x] Criar testes em `tests/ansible-update-test.py`: simular servidor e tempo, verificar tamanho/ordem dos lotes, ausência de POST na prévia, idempotência após resposta perdida, interrupção em estado desconhecido, cancelamento reverso, seleção exata e relatório sem credenciais.
- [x] Executar `python3 tests/ansible-update-test.py`; verificar `ansible-playbook ... --syntax-check` e execução da prévia/aplicação em API TLS simulada. Falha de teste deve ser corrigida antes de empacotar; não publicar versão de produto.
- [x] Atualizar README principal/índice install/guia Ansible/TODO, incluir fontes na allowlist de `ops/build-ansible-package.py`; gerar ZIP/SHA e conferir conteúdo. Preservar mudanças locais não relacionadas.
