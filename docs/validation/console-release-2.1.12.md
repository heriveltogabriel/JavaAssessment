# Validação da entrega 2.1.12 — 20/10/2026

## Escopo e origem

Branch `correcoes-2.1.10`, recebida de `origin` no commit `6a553dd`, incluindo as correções de `dfd37b1`. O código das correções não foi alterado durante esta entrega. As únicas mudanças em código de produto são a versão gerada do agente e a versão/data geradas do dashboard.

Release preparada com `--lab-canary`, ponte interna 2.1.11 e destino 2.1.12. Nenhum deploy remoto ou atualização de agente remoto foi realizado. Os testes locais usam adaptadores de SO/processo e não substituem a confirmação no cliente.

## Fontes antes da reserva

`ops/test-source.sh` passou integralmente, com JDK 17 e Node: coletor, Spring/JNDI, configurações externas, relatórios/CSV/PDF, SQLite, autenticação, atualização e interface. Identificação do produto ainda em 2.1.10 durante essa execução.

```text
TomcatDiscoveryTest: 35 checks passed
JBOSS_DISCOVERY_TESTS=PASS checks=32
WEBLOGIC_PRECEDENCE=PASS checks=24
SERVER_DATASOURCE_TESTS=PASS checks=199
NATIVE_TOMCAT=PASS jsvc flags libjvm negative excluded-process privacy
APPLICATION_CONFIG_FILES=PASS unique ambiguous explicit named defaults privacy symlink
ARCHIVE_METADATA_TESTS=PASS
Source regression checks passed; heavy stress and external hosts were not exercised.
```

A suíte inclui os novos casos da branch para jsvc, manifestos assinados, configurações por nome, avisos da varredura e padrões de coleta. O aviso administrativo de nova versão também passou nos testes de visibilidade, permissões e revisão sem iniciar pedidos.

## Pacotes finais

- `ops/build-dashboard-release.py --lab-canary`: aprovado; ponte 2.1.11 e destino 2.1.12. Compilação ECJ source/target 1.6, com classes major 50 confirmadas no bundle final. Execução em macOS/JDK 17; não é evidência de execução nativa Java 6/Linux.
- Web-update assinado e instalador completo gerados sem falhas.
- `ORIGINAL_218_VERIFIERS=PASS`: verificadores extraídos do instalador preservado 2.1.8 aceitaram assinatura web, baseline, payload, 31 rotas do agente, chave pública, âncora e classes major 50; 253 arquivos web.
- `DIRECT_V3_UPGRADE=PASS 2.1.8 -> 2.1.12`: manager binário original da 2.1.8, bundle final assinado, mesma operação e identidade/CA/âncora preservadas; adaptadores locais de SO/rede.
- `FINAL_JAVA_PACKAGE=PASS 2.1.8 -> 2.1.12 -> 2.1.8`: atualização e restauração com classes Java reais, ZIP assinado, TLS/SNI, SQLite, identidade da instalação e preservação de dados criados após a atualização; adaptador local de processo, não systemd remoto.
- Instalador final extraído: `install.py --check` aprovado para pacote, JDK 17, URL e identidade TLS. Não foi feita instalação em servidor Linux vazio.
- Os três ZIPs passaram em CRC e SHA-256. Pacotes anteriores foram preservados.
- Conferência final: 170 arquivos de código/execução do pacote de revisão coincidem com a árvore de trabalho; 117 links locais da documentação resolvidos. Código das correções inalterado, exceto metadados de versão gerados.

A rota direta **2.1.8 → 2.1.12** foi validada para dashboard e agente nos limites acima, sem passar pela 2.1.10. A ponte interna 2.1.11 não exige instalação manual. Instalações modificadas ou sem rota elegível devem seguir a avaliação do painel e, quando indicado, usar o instalador completo configurado do ambiente.

Logs locais: `tmp/correcoes-2.1.12-source.log`, `tmp/build-2.1.12.log`, `tmp/direct-v3-218-to-2112.log`, `tmp/original-218-verifiers-2112.log`, `tmp/dashboard-final-218-to-2112.log`, `tmp/installer-precheck-2.1.12.log` e `output/dashboard-releases/2.1.12/evidence/`.

## Documentação

README principal, índice de instalação, README do agente, README dos pacotes e notas atualizados. Manuais genéricos regenerados: Manual com 16 páginas e Guia de instalação com 8 páginas; páginas alteradas renderizadas e conferidas visualmente.

## Arquivos entregues

- `assessment-dashboard-installer-2.1.12.zip`: 92187667 bytes; SHA-256 `ba003438fdf5dcf3732c6a27eb036fd0bab330118baac79457bbf4d2d892a8c2`.
- `assessment-dashboard-update-2.1.12.zip`: 92475683 bytes; SHA-256 `43a18ca916535d844eac49d71d1dc66b70e9778eb2efb10f83ee9ba7ae3f9d52`.
- `assessment-dashboard-web-update-2.1.12.zip`: 92427252 bytes; SHA-256 `2cf6b80f207e42cd3560f413e2df408afe7b2d4b184e691a6344eb070ae5a09a`.

## Validação operacional pelo operador

1. Enviar o web-update em **Administração → Atualização do dashboard**, conferir origem/destino, confirmar reinício e verificar o retorno em 2.1.12.
2. **Atualizar também os agentes separadamente**, começando por uma máquina elegível, e solicitar nova coleta. Conferir aplicação, datasource, JAR, versão e ressalvas da resolução.
3. Revisar exclusões salvas: `/opt` voltou ao padrão de inclusão, mas listas existentes não são migradas.
4. Confirmar Java 6/Linux real e as configurações do cliente. A coleta passiva não comprova a classe efetivamente carregada nem a versão de um banco remoto.
