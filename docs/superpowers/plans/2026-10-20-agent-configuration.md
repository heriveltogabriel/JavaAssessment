# Descoberta de configurações de aplicações — plano de implementação

> Execução sequencial nesta sessão, com executing-plans. Especificação e autorização: documento fornecido pelo usuário em 20/10/2026. Branch codex/melhorias-agente-2; sem push, deploy, assinatura ou incremento de versão.

**Objetivo:** identificar corretamente os launchers, configurações externas, Spring/JPA/Hibernate/XML e vínculos JNDI, preservando Java 6, privacidade e limites de leitura.

**Arquitetura:** ProcessLaunch descreve o launcher; SpringConfiguration resolve candidatos Spring por processo; um leitor ApplicationJdbc limita a busca aos artefatos e pastas de aplicações observadas. A ligação JNDI acontece após a descoberta de todos os processos, para não depender da ordem. Destinos são evidências de configuração, sem conexão ao banco. Ambiguidades ficam explícitas.

**Tecnologias:** Java 6 no coletor, Java 17/SQLite no servidor, JavaScript no dashboard, ECJ e testes locais.

## 1. Launcher
- [x] Estender ProcessLaunch para opções entre -jar e arquivo, confirmação por perfdata e aviso sem valores sobre opções posteriores.
- [x] Cobrir caso real, jar simples/ausente/relativo, classpath, argfile e privacidade em ProcessLaunchTest; rodar ops/test-source.sh.

## 2. Configuração e pastas
- [x] Acrescentar appConfigPaths em defaults, validação geral/ambiente/máquina e formulários; fallback /etc/config; listas vazias desativam descoberta adicional.
- [x] Remover /opt só dos padrões novos; verificar preservação de configurações salvas.
- [x] Descobrir arquivos limitados, sem links, vinculando nomes/locais explícitos; genéricos de máquina sem associação quando ambíguos.
- [x] Testar isolamento, defaults, herança, limites e vínculos; rodar suíte.

## 3. Spring
- [x] Detectar prefixos com binding relaxado, dialetos como pista (URL prevalece), imports locais limitados e perfis.
- [x] Limites de importação: profundidade 3, 20 arquivos, 1 MiB; ciclos e fontes remotas viram observações sanitizadas.
- [x] Testar precedência, importação relativa, opcional, ciclos, perfis, múltiplos prefixos e segredos; rodar suíte.

## 4. JNDI
- [x] Normalizar prefixos conhecidos e correlacionar exclusivamente datasources de servidores da mesma máquina.
- [x] Copiar campos permitidos apenas com candidato único; marcar ausente/ambíguo sem fabricar destino.
- [x] Testar ambas as ordens de processo, JBoss/Tomcat/WebLogic e ambiguidade; rodar suíte.

## 5. Outros JDBC e entrega
- [x] Ler persistence.xml, hibernate.cfg.xml/properties e beans Spring XML com parser sem entidades externas.
- [x] Busca genérica somente em pastas/arquivos de aplicações observadas, texto até 512 KiB, deduplicada contra leitores específicos.
- [x] Expor origem/evidências no detalhe, registrar testes nas suítes e validar ausência de segredos.
- [ ] Generalizar os scripts pessoais de build: não encontrados neste checkout.
- [x] Documentar mudanças em AGENT_CONFIGURATION_PENDING.md, sem reservar uma versão. O pedido posterior do usuário adia o fechamento até consolidar outras implementações.
- [x] Executar suíte completa e ECJ source/target 1.6, conferir diff e entregar arquivos/testes/limites.

## Limites e decisões
- Não expandir segredos ou nomes de usuário em placeholders; não serializar URL, ambiente ou argv bruto.
- Configuração genérica com várias aplicações permanece na máquina; não entra como override de todas as aplicações.
- JPA/dialeto não comprova versão do banco ou uso do datasource.
- Java 6 real/Linux e cenário do cliente continuam distintos de compilação ECJ e fixtures no macOS.
- Limite de helpers já foi elevado para 64 na 2.1.8; não refazer mudança de 32 indicada pelo documento antigo.

## Validação desta etapa

A regressão de fonte foi executada após cada item. O caso real é reproduzido por AgentConfigAcceptanceTest. ECJ com source/target 1.6 gerou 91 classes major 50. Execução em Linux e JVM Java 6 real permanece pendente. A branch contém somente código e documentação; os artefatos publicados e a versão 2.1.8 permanecem intactos.
