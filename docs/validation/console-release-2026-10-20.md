# Validação da entrega 2.1.8 — 20/10/2026

Candidato de laboratório. Nenhum deploy nem atualização de agente foi feito nesta entrega; o envio pela console e a atualização dos agentes são do operador.

## Fontes

- `ops/test-source.sh` aprovado em Linux (nuvem) e no macOS do build, com o lint Java 6 do coletor sem problemas.
- Revisão independente do agente: sem vazamento de segredo nem travamento reproduzido; 10 achados corrigidos, cada um com teste.
- Máquina simulada (14 apps Spring Boot/WAR, Oracle Home WebLogic 12.2.1.4, 60 mil arquivos): coleta de 17,1 s parcial com 18 avisos para 1,1 s completa; aplicações 10/14 para 14/14; WebLogic identificado (antes não encontrado).
- Dashboard conferido no navegador com dados de teste: painel de riscos, selos de suporte, abas Datasources JDBC e Bancos de dados, relatório.

## Pacotes

Build `ops/build-dashboard-release.py --lab-canary` (2.1.7 ponte interna, 2.1.8 final), web-update e instalador. Limite de helpers conhecidos elevado de 32 para 64 (a 2.1.6 era a 33ª versão de origem).

- `assessment-dashboard-installer-2.1.8.zip`: 88186221 bytes; SHA-256 `cd9badc86aac556089f41ac50dc6e8e5f186a05e4ad3fe582715b51239f71e42`.
- `assessment-dashboard-update-2.1.8.zip`: 88471432 bytes; SHA-256 `7cca54fca1e3efac28cbe5475c6327b9527356b74b1cd1e62054e8a631824460`.
- `assessment-dashboard-web-update-2.1.8.zip`: 88423174 bytes; SHA-256 `5b80a8757dab147c12e8a94fd3bfef18041adde4b4e5145f85a4dfd6cb699fe4`.

## Validação operacional pendente

1. Upload do web-update na Administração, confirmação, retorno da sessão e versão final.
2. Atualizar um agente pelo painel e solicitar nova coleta; conferir versões reais de JVM, servidores e datasources.
3. Caminho do cliente 2.0.53 → 2.1.8 pelo pacote de atualização por script, ensaiado em laboratório antes de levar ao cliente.
