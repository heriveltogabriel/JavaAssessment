# Validação da entrega 2.1.6 — 19/10/2026

## Resultado e limites

Código e pacotes finais preparados para o operador testar pela console. Nenhum deploy 2.1.6 nem atualização de agente remoto foi executado nesta entrega. Os testes abaixo são isolados; não substituem a confirmação do upload e reinício na instalação real.

## Fontes

- `ops/test-source.sh`: regressão completa aprovada; inclui coleta limitada, JDBC, Spring, inventário, autenticação, instalação, atualização e relatórios.
- `AgentUpdateStartTimeoutTest`: 27 verificações com SQLite persistente e relógio controlado. Cinco minutos por vez; próxima máquina; manutenção sem heartbeat; reinício; claim tardio legado/V3; bridge cancelado; atualização iniciada preservada.
- `AgentUpdateHttpTest`: 158 verificações, incluindo entrega à próxima máquina e novo pedido ao retorno do agente.
- `JdbcEndpointReportsTest`: 79 verificações; caminho de JAR Spring aninhado em relatório, PDF, CSV e CSV de inventário.
- SpringDataSource/SpringIntegration: candidato único, ambiguidade, versão do banco separada e remoção de JAR incompatível quando relato opcional informa outra versão.
- Navegador local com fixtures: atualização de agentes aprovada, incluindo mensagem AGENT_START_TIMEOUT. Um seletor antigo do teste foi corrigido de Machines para Máquinas.
- Atualizador do dashboard: 25 testes Python aprovados. Navegador com respostas simuladas aprovou upload, senha, reconexão, cancelamento, restauração e escape de conteúdo.

## Pacotes finais

- Build 2.1.6 com bridge interno 2.1.5 aprovado, incluindo regressões de empacotamento e código do coletor compatível com Java 6.
- Verificador independente: assinaturas, hashes, 28 perfis legados e proveniência dos binários originais 1.5.12 e helpers 1.5.12/1.5.9 aprovados. Isso não é novo ensaio nativo dessas migrações.
- Baseline 2.1.4 revisado contra manifesto original e bytes executáveis/de suporte do instalador preservado. Configuração de exemplo e SHA256SUMS diferem na embalagem do instalador por desenho.
- `AgentDirectV3UpgradeTest`: 2.0.53, 2.1.0 e 2.1.4 → 2.1.6 aprovados com manager original, bundle final assinado e preservação de identidade, CA e âncora; adaptadores locais de SO/rede.
- `dashboard-update-final-package.py --from-version 2.1.4 --to-version 2.1.6`: atualização e restauração para 2.1.4 aprovadas com classes Java reais, TLS/SNI, SQLite, identidade e dados criados após atualização preservados. Adaptador de processo local; sem systemd remoto.
- Instalador extraído: `install.py --check` aprovado para pacote, JDK, URL e identidade TLS. Instalação nova em servidor vazio não executada nesta entrega.
- Os três ZIPs passaram no teste de descompressão/CRC e SHA-256. Fontes da interface e do registro de atualização no ZIP web correspondem à árvore de trabalho; origem 2.1.4 incluída no manifesto.
- Manual (16 páginas) e Guia (8 páginas) regenerados e revisados visualmente. Nomes genéricos preservados.

## Pacotes entregues

- `assessment-dashboard-installer-2.1.6.zip`: 86288984 bytes; SHA-256 `52ec8b9e72459e24e31ff4ade779e527e84157bcdc36f3f7f0bc58ad445b9b20`.
- `assessment-dashboard-update-2.1.6.zip`: 86561802 bytes; SHA-256 `622a1fde4712ac8425b985c72dc0c027761de7aa7ac2f60c141649a60675f280`.
- `assessment-dashboard-web-update-2.1.6.zip`: 86512996 bytes; SHA-256 `ce2679570b581a1455edc58c7e1a9391ce9832cc38d05ee396ad59c8d2222cb4`.

## Validação operacional pendente

1. Upload do pacote web na Administração, preparação, confirmação, retorno da sessão e versão final.
2. Após atualizar um agente OL07, nova coleta dos três datasources para confirmar o campo JAR na interface.
3. Ensaio real de lote com a primeira máquina desligada. Testes determinísticos já cobrem a lógica, mas esse cenário ainda não foi executado nas VMs nesta entrega.

As evidências completas da execução ficam localmente em `tmp/console-*.log` e `output/dashboard-releases/2.1.6/evidence/`; não contêm uma declaração de deploy. Atualize agentes separadamente pelo painel quando elegíveis; instalador completo apenas quando não houver rota compatível.
