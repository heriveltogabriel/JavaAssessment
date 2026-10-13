# Java Assessment 1.0 Beta — validação

Data: 13 de outubro de 2026. Implementação e pacote preparados localmente. **Nenhuma VM foi atualizada.**

## O que mudou

- Inventário, ambientes, máquinas, tarefas e credenciais persistidos em SQLite local, com índices e transações.
- Consultas, filtros e paginação executados no servidor. O navegador recebe apenas a página e os resumos necessários.
- Coletas atualizam uma máquina por transação. Heartbeat apenas atualiza o contato. Reenvios são reconhecidos sem duplicar registros.
- Exportação produz um arquivo temporário protegido, em lotes, sem carregar todo o inventário na heap.
- Migração dos dois arquivos JSON legados antes da primeira inicialização, com backup e validação. Instalador inclui ferramenta de backup consistente de SQLite.
- Dashboard em Java 17 para Linux 8+. O coletor mantém o protocolo e a compatibilidade com Java 6.

## Teste de carga com 1.000 máquinas

O teste utilizou HTTP em loopback, SQLite real e Temurin 17.0.20.1 em macOS aarch64. A JVM recebeu `-Xmx256m`. O gerador de carga e o servidor participaram do mesmo processo; os números de memória incluem a estrutura do teste.

| Medida | Resultado |
| --- | ---: |
| Máquinas / ambientes | 1.000 / 10 |
| Registros de inventário | 34.000 |
| Duração da carga concorrente | 60,049 segundos |
| Heartbeats aceitos | 1.021, aproximadamente 17/s |
| Coletas completas e seus reenvios | 12 |
| Consultas do dashboard | 292 |
| Latência de consulta p50 / p95 | 65,51 / 88,24 ms |
| Maior latência de consulta | 168,50 ms |
| Pior p95 entre os tipos de consulta | 133,83 ms, filtro Java 8 |
| Detalhe de máquina p95 | 5,08 ms |
| Heartbeat p95 | 2,19 ms |
| Tamanho das respostas | 16.671 a 117.939 bytes |
| Heap retida antes / depois | 7.185.744 / 7.933.760 bytes |
| Pico de heap amostrado | 27.011.184 bytes, aproximadamente 25,8 MiB |
| RSS antes / depois | 150.240 / 178.608 KiB |
| Persistência após reabrir o banco | Aprovada |

O teste cobre resumos, páginas de máquinas, ambiente, versão Java, aplicações, bibliotecas, detalhe de máquina e bibliotecas por ambiente. O limite de aprovação foi p95 inferior a 500 ms para cada caso. Evidência completa: [beta-1000-load.json](validation/beta-1000-load.json).

Essas medidas não são uma garantia de capacidade para qualquer VM. Um minuto sem crescimento significativo de heap não prova ausência de vazamentos em longa duração. O tempo acima é da API; não representa tempo de renderização do navegador nem latência da rede do cliente.

## Testes funcionais

| Área | Resultado |
| --- | --- |
| SQLite | Persistência, índices, rollback, savepoints, leitores WAL, snapshots, migração, limites, backups e permissões aprovados |
| API HTTP real | Login/CSRF, registro/reenvio, isolamento por máquina, herança de configurações, filas, reinício e resultado tardio aprovados |
| Aceitação de coleta | Erro de escrita simulado gera rollback e nenhuma confirmação; reenvio após corrigir a falha é aceito |
| Consultas | Escopo de ambiente/máquina, paginação de bibliotecas+datasources, pesquisa por hostname, exportação e exclusão de JRE aprovados |
| Administração | Exclusão com nome exato, bloqueio de ambiente ocupado, mudança de ambiente e revogação persistente aprovados |
| Interface | Testes unitários, cancelamento de respostas antigas e navegação com dados paginados aprovados |
| Navegador com Java/SQLite real | 1.000 máquinas, 600 versões exatas distintas, versão desconhecida, filtros, detalhes relacionados, exportação, fila para 600 máquinas e tela móvel aprovados; sem erros JavaScript |
| Instalador | 10 testes aprovados, incluindo HTTPS real, SQLite, biblioteca nativa, migração antes do boot e backup/restauração consistente |

Os testes de instalador executaram Java/HTTPS/SQLite reais no macOS. As operações de systemd, conta e ownership do Linux foram simuladas. **Ainda é necessária a instalação limpa em uma VM Linux 8+**, após autorização, para validar os pacotes da distribuição, serviço, permissões, firewall e comunicação dos coletores no destino.

## Reproduzir

Requisitos locais: JDK 17+, Node.js, Playwright com Chrome disponível e as dependências fixadas em `lib/`. Os testes criam fixtures temporárias e acessam apenas loopback. Não utilizam credenciais ou endereços de um dashboard instalado.

```sh
export JAVA_HOME=/caminho/do/jdk17
/bin/sh ops/test-beta.sh
```

Se Node/Playwright não estiverem no caminho padrão, informe `NODE`, `NODE_PATH` ou `PLAYWRIGHT_MODULE`. `BETA_LOAD=0` pula apenas o teste de carga. O relatório de uma nova execução fica em `tmp/beta-reports/beta-1000-load.json`.

Para executar a suíte específica do instalador, consulte os parâmetros no início de `tests/beta-installer-test.py`. Para instalação e migração, veja `dashboard-installer/GUIA_INSTALACAO.md` e `dashboard-installer/BETA_MIGRATION.md`.

## Entrega e atualização

O pacote versionado é `dist/assessment-dashboard-installer-1.0-beta.zip`, acompanhado de SHA-256. O pacote de fontes é `dist/assessment-dashboard-source-1.0-beta.zip`.

A Beta deve ser instalada em Linux 8+; a VM RHEL 5 do piloto permanece apta ao coletor, mas não é destino do novo dashboard. A atualização ou migração aguarda aprovação explícita do usuário. Migrar o banco não altera automaticamente a URL e a CA já configuradas nos agentes.
