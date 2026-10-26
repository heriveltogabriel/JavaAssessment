# Validação — atualização em lotes via Ansible

Data: 26/10/2026. Versão do produto preservada em 2.1.24.

## Executado

- `tests/ansible-update-test.py`: **31 testes aprovados**, execução final em 8,864 s, Python 3.12 do ambiente Ansible local.
- Integração executou o playbook real em prévia e aplicação contra servidor HTTPS simulado, com dois alvos, cookies Secure, CSRF e administrador. Somente um lote foi criado, confirmando `run_once`.
- Paginação de 1501 hosts; correspondência exata de hostname/FQDN/ID, recusa de ambiguidade, aliases duplicados e mistura de dashboards.
- Lotes 5+3; esperar conclusão e versão; assessment pós-atualização desativado. Não cria pedidos em prévia, para versões atuais/superiores, revogados ou sem contato.
- Rede: resposta de POST perdida, retomada com mesma chave idempotente, reautenticação após 401. Recusa redirecionamentos, hostname TLS incorreto e CA ausente. Erros HTTP não expõem corpo privado.
- Falha/rollback/UNCONFIRMED/timeout interrompem avanço; cancelamento apenas dos WAITING próprios, em ordem reversa. Um download iniciado permanece preservado.
- Journal com seleção/versão/dashboard; recusa reutilização com seleção diferente. Campanha concluída não cria novos pedidos; bloqueada não prossegue silenciosamente. Lock e arquivos privados conferidos. Credenciais ausentes nos relatórios e saída Ansible.
- `--syntax-check` aprovado; `--list-hosts --limit piloto` selecionou somente `app-prod-01`.
- ZIP público verificado pelo empacotador: allowlist, CRC, conteúdo idêntico às fontes e SHA-256. Exemplos locais/reais, Vaults e relatórios não entram no pacote.

A revisão encontrou uma conversão incorreta de lista Jinja após otimizar a seleção para inventários grandes. Foi corrigida com serialização JSON explícita e toda a suíte foi repetida com sucesso antes da entrega.

## Limites

Não foi executado nos servidores do cliente nem em 1500 agentes reais. A integração valida contrato HTTP/orquestração, não instalação de binários em máquinas reais. Usa o atualizador existente sem alterar scripts/Java do agente, servidor, versão ou instaladores do produto. Não foi necessário rodar a suíte Java para esta alteração isolada de automação.

Usar um host real como piloto após encerrar outros lotes. O dashboard continua executando um agente por vez por lote; não há limite global atômico entre operadores. A automação não recupera serviço parado nem resolve timeouts de conectividade. A presença e a versão são evidências fornecidas pelo dashboard.
