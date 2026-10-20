# Validação da entrega 2.1.10 — 20/10/2026

## Escopo

Entrega consolidada de Spring, WebLogic e Tomcat/JBoss, preparada para o operador testar pela console. Nenhum deploy remoto nem atualização de agente remoto foi executado nesta entrega. Validações locais não substituem a confirmação na instalação real.

## Fontes antes do fechamento

Com dashboard, agente e instalador ainda identificados como 2.1.8:

```text
JDBC_DRIVER_RESOLVER_TESTS=PASS checks=21
WEBLOGIC_PRECEDENCE=PASS checks=24
TomcatDiscoveryTest: 29 checks passed
JBOSS_DISCOVERY_TESTS=PASS checks=32
NATIVE_TOMCAT=PASS jsvc flags libjvm negative excluded-process privacy
SERVER_DATASOURCE_TESTS=PASS checks=199
SETTINGS_PATHS_HTTP=PASS fresh legacy-read no-migration duplicate conflict host-effective
Source regression checks passed; heavy stress and external hosts were not exercised.
ECJ_JAVA6=PASS source=1.6 target=1.6 class-major=50 classes=113
```

`ops/test-source.sh` passou integralmente: coletor, Spring/JNDI, configuração, relatórios/CSV/PDF, SQLite, autenticação, atualização e interface. O teste JNDI preserva candidatos e decisões por PID também na linha associada da aplicação. Compilação ECJ com source/target 1.6 e verificação de major 50; execução dos testes no macOS/JDK 17, sem alegar execução nativa Java 6.

Os padrões novos e as listas antigas foram testados separadamente, inclusive leitura de configuração antiga conflitante sem migração e rejeição ao salvar listas inválidas. Processo em caminho excluído continua visível, sem leitura de seus datasources.

Manuais genéricos regenerados: Manual com 16 páginas e Guia de instalação com 8 páginas. Páginas alteradas renderizadas e conferidas visualmente. Fontes e nomes estáveis preservados.

## Pacotes finais

- Build final 2.1.10 aprovado, com ponte interna 2.1.9, testes do coletor/distribuição/instalação/recuperação e assinaturas.
- `ORIGINAL_218_VERIFIERS=PASS`: os verificadores extraídos do instalador preservado 2.1.8 aceitaram assinatura web, baseline, payload, 30 rotas do agente, chave pública, âncora e classes major 50.
- `DIRECT_V3_UPGRADE=PASS 2.1.8 -> 2.1.10`: manager original 2.1.8, bundle final assinado, mesma operação, identidade, CA e âncora preservados. Adaptadores locais de SO/rede.
- `FINAL_JAVA_PACKAGE=PASS 2.1.8 -> 2.1.10 -> 2.1.8`: atualização e restauração com classes Java reais, TLS/SNI, SQLite, identidade e preservação dos dados criados depois da atualização. Adaptador local de processo; não systemd remoto.
- Instalador final extraído: `install.py --check` aprovado para pacote, JDK 17, URL e identidade TLS. Não foi feita instalação em servidor Linux vazio.
- Os três ZIPs passaram em CRC/descompressão e SHA-256. Pacotes anteriores, inclusive `install/v2.1.8`, permaneceram inalterados.

A origem 2.1.8 foi validada para atualização pelo painel nos limites acima. Instalações alteradas ou sem rota elegível precisam de avaliação pelo painel e, quando indicado, do instalador completo configurado do ambiente. Não é necessária uma instalação manual da ponte 2.1.9.

Logs finais locais: `tmp/direct-v3-218-to-2110.log`, `tmp/original-218-verifiers-2110.log`, `tmp/dashboard-final-218-to-2110.log`, `tmp/installer-precheck-2.1.10.log`.

## Arquivos entregues

- `assessment-dashboard-installer-2.1.10.zip`: 91405877 bytes; SHA-256 `6a455e804ece189d73f3b0267830b7853dffbedbf702a38b0016184c69ae61bb`.
- `assessment-dashboard-update-2.1.10.zip`: 91693435 bytes; SHA-256 `0f0d4531eb1d7f1a98b7432561f403c05566774232390670e3ce673076153182`.
- `assessment-dashboard-web-update-2.1.10.zip`: 91644886 bytes; SHA-256 `5a5bc315337bea25cb339edb044458032c76095ef12ab1fc9cd6df1db5e9941f`.

## Validação operacional pelo operador

1. Enviar `assessment-dashboard-web-update-2.1.10.zip` em Administração → Atualização do dashboard; conferir origem/destino, confirmar reinício e verificar o retorno em 2.1.10.
2. Atualizar separadamente uma máquina de cada tipo de servidor e solicitar nova coleta. Conferir JAR, versão, suporte, candidatos e associação por aplicação.
3. Para produtos em `/opt`, revisar a exclusão antes da coleta. Listas já salvas não foram migradas.
4. Validar Java 6/Linux real e as configurações do cliente. TomEE, `.installation`, bootable JAR em `/tmp`, segredos, classloaders personalizados e servidores parados permanecem fora do escopo desta ampliação.

Logs locais: `tmp/tomcat-jboss-source.log`, `tmp/tomcat-jboss-ecj.log`, `output/dashboard-releases/2.1.10/evidence/` e `tmp/pdfs/documentation/review/`. Esses resultados são evidência de testes, não confirmação de implantação.

## Publicação

Código, documentação e três pacotes publicados em `main` e `tomcat-jboss-agente`, commit `eb40c8c`, com referências remotas conferidas. Os 149 arquivos executáveis/fontes selecionados do pacote de revisão coincidem com a árvore de trabalho. Aviso de conclusão enviado ao usuário; nenhum deploy remoto foi realizado.
