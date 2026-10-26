# Validação da release 2.1.24

Data: 26/10/2026. Base `main` em `068c980`, correções `9ade067` e `f0dde88`. Ponte interna 2.1.23 e final 2.1.24. Código funcional das correções preservado; alterações da entrega em versão, empacotamento e documentação.

## Provas executadas nesta entrega

- `ops/test-source.sh`: suíte completa aprovada antes da reserva. Compilação, coletores, SQLite/HTTP, relatórios, exportações e JavaScript; inclui aviso administrativo de agentes antigos, permissões e ausência de pedidos automáticos. Log `tmp/source-2.1.24.log`.
- `ops/build-dashboard-release.py --lab-canary`: aprovado na primeira reserva, compilação do coletor para bytecode Java 6, testes do coletor com heap de 96 MiB, testes de atualização, assinaturas e pacotes completos finais. Log `tmp/build-2.1.24.log`; detalhes em `output/dashboard-releases/2.1.24/evidence/`.
- Web-update e instalador construídos pelos scripts oficiais. Três ZIPs verificados por CRC, nomes sem duplicidade/traversal, SHA-256 externo e inventário interno. Manifesto web inclui 2.1.20 e 2.1.22. Log `tmp/packages-2.1.24.json`.
- Verificadores extraídos dos instaladores publicados 2.1.20 e 2.1.22 aceitaram o novo web-update e catálogo do agente: assinatura, baseline, pin, bundle, bootstrap imutável, bytecode major 50 e preservação das 32 rotas legadas. Logs `tmp/original-220-verifiers-224.log` e `tmp/original-222-verifiers-224.log`.
- **Console 2.1.20 → 2.1.24 → 2.1.20 e 2.1.22 → 2.1.24 → 2.1.22**: classes publicadas reais em processos Java, TLS/SNI, SQLite íntegro, identidade preservada e restauração conservando dados gravados após atualizar. Logs `tmp/dashboard-final-220-to-224.log` e `tmp/dashboard-final-222-to-224.log`.
- **Agentes 2.1.20 → 2.1.24 e 2.1.22 → 2.1.24**: atualizador carregado do JAR original publicado, instalação V3 inicializada com o bundle assinado original, novo bundle assinado aplicado, código/manager promovidos, transição para confirmação, bootstrap inalterado e geração anterior preservada. Identidade, configuração, CA, Java privado, spool e logs conservados. Logs `tmp/agent-original-220-to-224.log` e `tmp/agent-original-222-to-224.log`.
- Instalador final extraído: `install.py --check --no-package-install`, JDK 17, URL e identidade TLS sintéticas aprovados, sem instalação de serviços. Log `tmp/installer-precheck-2.1.24.log`.

A primeira invocação da suíte não encontrou o antigo JDK temporário; a suíte foi executada integralmente com o JDK já disponível em `tools/jdk17`. Nenhum teste de produto foi alterado para obter aprovação.

## Limites e operação no cliente

As provas desta entrega foram locais, em macOS/JDK 17. A console usa processos filhos como adaptador de serviço. No teste do agente, serviços, transporte e confirmação pelo novo manager são adaptadores explícitos; não é uma prova de systemd, SysV, reboot ou Java 6 nativo. O teste Linux/JVM 8 registrado na [validação das correções](collector-driver-resolution.md) foi realizado anteriormente e não foi repetido aqui (Docker indisponível).

Não houve deploy remoto nem atualização de máquina do cliente. O build conserva a marca `candidateOnly` do fluxo de laboratório e não deve ser apresentado como certificação em produção ou de todas as origens >=1.5.12.

O web-update aceita as origens 2.1.20/2.1.22 verificadas. Agentes dessas versões com V3 íntegro podem usar a atualização separada pelo painel, sem instalar manualmente a ponte 2.1.23. A elegibilidade deve ser confirmada na instalação real; sem capacidade/rota compatível, use o pacote de agente configurado para o ambiente com `--system-root`. O instalador completo do dashboard é apenas para servidor novo.

**Atualizar o dashboard não atualiza os agentes automaticamente.** Comece por uma máquina do cliente, confirme versão/contato e execute nova coleta antes de ampliar o lote. A estimativa de 90% de drivers identificados não foi medida; os inventários antigos não são recalculados.

## Pacotes

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| assessment-dashboard-installer-2.1.24.zip | 92991829 | `48b8f59871be712ea8ca5e98f3942b7adf05e0ef7e0cd14904b161518ee0a344` |
| assessment-dashboard-update-2.1.24.zip | 93312921 | `7b4835ec677912c66ca13a8d313783d841c0ef775d49f0b21ad07282a6c048e8` |
| assessment-dashboard-web-update-2.1.24.zip | 93266917 | `c637cd98236c06c3c7fb39f40ee96467a0599c3e1c0f3211cc7d27560efa1166` |
