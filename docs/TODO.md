# Melhorias planejadas

## Entrega 2.1.24 — coletor: drivers JDBC identificados

- [x] Resolver WebLogic sem bloqueio por `vmArgs` truncado; configuração de domain home atribuída ao servidor do domínio (não ao processo auxiliar); `ojdbc14` reconhecido; motivos múltiplos no texto de INCOMPLETE. [Notas](RELEASE_2.1.24.md) e [validação](validation/collector-driver-resolution.md).
- [x] Testes novos (falham no código anterior), prova em Linux real e suíte completa de origem.
- [x] Build 2.1.23/2.1.24 e três ZIPs com SHA-256 preparados em `install/v2.1.24`, com READMEs e notas atualizados. Verificadores originais e atualização/restauração da console a partir de 2.1.20 e 2.1.22 aprovados; atualizadores originais dos agentes executados em fixtures com os bundles reais. [Evidências e limites](validation/console-release-2.1.24.md).
- [ ] Atualizar os agentes e coletar de novo para medir a cobertura de drivers. Estimativa antes da medição: perto de 90%, contra 27% na coleta de 26/10/2026.
- [ ] Datasources só de configuração, sem processo associado, continuam sem driver.
- Atualizar o dashboard não atualiza os agentes automaticamente.

## Entrega 2.1.22 — cadastro em escala

- [x] Consolidar a branch remover-limite-maquinas e corrigir máximos dos formulários, limpeza independente da capacidade e paginação administrativa por ambiente.
- [x] Regressão completa, limite exato, retry, retenção, isolamento e navegador desktop/mobile.
- [x] Build 2.1.21/2.1.22, três ZIPs e SHA-256, verificadores originais, precheck e atualização/restauração 2.1.20 → 2.1.22 em fixture. [Evidências](validation/console-release-2.1.22.md).
- [x] READMEs, contrato, guia de acesso de instalação e notas atualizados; pacotes antigos preservados.
- [ ] Operador validar upload/reinício pela console 2.1.20 antes de implantar no cliente. Sem deploy remoto ou merge no main nesta entrega para teste.
- [ ] Medir capacidade operacional com volume representativo antes de recomendar quantidade de agentes por servidor. Sem teto fixo de cadastro não significa recursos ilimitados.
- Agentes 2.1.20 continuam compatíveis. Atualizar o dashboard não atualiza os agentes automaticamente.

## Entrega 2.1.20 — Profile/contexto dos datasources

- [x] Coluna **Profile/contexto** e campo nos detalhes usando `configurationContext` já coletado. Datasources homônimos em perfis distintos permanecem separados; campo ausente aparece como “Não identificado”.
- [x] Sintaxe e 11 suítes JavaScript atuais passaram; teste legado beta-ui já falha na base publicada (registrado no plano).
- [x] Suíte completa, build final, pacotes assinados, navegador e atualização/restauração direta 2.1.16/2.1.18 → 2.1.20 em fixtures. [Evidências](validation/console-release-2.1.20.md).
- [x] READMEs, guia Tomcat/JBoss e instaladores com SHA-256 em install/v2.1.20 atualizados; versões anteriores preservadas.
- [ ] Operador testar upload/reinício pela console e conferir Profile/contexto no inventário real. Nenhum deploy remoto realizado.

## Entrega 2.1.18 — Excel e Mapa de bancos

- [x] Consolidar os dois commits de `mapa-bancos`, sem alterar implementação: Excel com dez abas, mapa CSV e driver provável restrito ao classpath.
- [x] Suíte de fontes, navegador e exportação sobre classes finais; planilha aberta com openpyxl.
- [x] Pacotes finais e verificadores originais; atualização/restauração direta 2.1.14 e ambas as compilações 2.1.16 → 2.1.18 em fixtures. [Evidências](validation/console-release-2.1.18.md).
- [x] Reservar ponte 2.1.17/final 2.1.18 e preparar três ZIPs com SHA-256 em install/v2.1.18, preservando versões anteriores. READMEs e guias afetados atualizados.
- [ ] Operador validar upload/reinício pela console e os quatro formatos de exportação com amostra real autorizada.
- Atualizar o dashboard não atualiza os agentes automaticamente. O agente não teve alteração funcional; os dados já coletados atendem às novas funcionalidades.

## Entrega 2.1.16 — exportação técnica em ZIP

- [x] Aba administrativa revisada: escolha JSON/CSV e ZIP com um único arquivo; CSV com uma linha por recurso e detalhes múltiplos nas células. Campos permitidos e exclusão de credenciais preservados.
- [x] Regressão em fontes, navegador desktop/mobile e testes da exportação nas classes finais do runtime.
- [x] Pacotes, assinaturas, verificadores originais, atualização/restauração do dashboard e atualização direta do agente 2.1.14 → 2.1.16 em fixtures. [Evidências](validation/console-release-2.1.16.md).
- [x] Catálogo de migração com 32 perfis preservados: origens V3 2.1.14+ assinadas seguem diretamente, mantendo baselines dos helpers.
- [x] README, documentação, manual genérico e instaladores atualizados.
- [ ] Operador validar upload pela console e exportação com uma amostra real autorizada.

## Entrega 2.1.14 — classpath longo e candidato JDBC

- [x] Ampliar classpath para 1.024 entradas, argumentos/ambiente para 256 KiB e resolução JDBC para 4.096 caminhos.
- [x] Distinguir truncamento real por fonte e preservar classpath completo compatível.
- [x] Mostrar JAR/versão provável quando único e concordante entre PIDs, preservando motivo e suporte.
- [x] Regressão, Java 6 bytecode e pacotes finais; atualização/rollback 2.1.12 → 2.1.14 em fixtures. [Evidências](validation/console-release-2.1.14.md).
- [ ] Operador atualizar dashboard/agentes e confirmar nova coleta no cliente.
- [x] Limite de 32 perfis revisado na 2.1.16 com política explícita e preservação de todas as rotas antigas.

## Entrega consolidada 2.1.10 — teste pela console

- [x] Tomcat/JBoss no motor compartilhado, novos padrões, fixtures, regressão completa e alvo Java 6.
- [x] Pacotes assinados 2.1.10, verificador original 2.1.8, atualização de agente e upgrade/rollback do dashboard em ambiente isolado. [Evidências](validation/console-release-2.1.10.md).

- [x] Implementar parsing `-jar`, `appConfigPaths`, prefixos/imports/dialetos Spring, vínculo JNDI e leitores JPA/Hibernate/XML/JDBC genérico com testes locais.
- [x] Implementar resolução WebLogic por PID: precedência, fd/maps, proveniência, módulos de aplicação, candidatos e relatórios/exportações. Ver [guia JDBC](JDBC_DRIVER_RESOLUTION.md).
- [ ] Validar drivers reais e preferências de classloader em RHEL 5/OL7, incluindo JAR substituído em disco e múltiplos processos.
- [ ] Obter amostra de inventário de patches JDBC antes de implementar fallback por registry.xml; testar aplicações WAR dentro de EAR.
- [x] Consolidar Spring, WebLogic, Tomcat/JBoss e padrões de coleta na mesma entrega.
- [ ] Validar em Linux/Java 6 real e nos ambientes de laboratório; o teste ECJ não substitui execução no Java 6.
- [x] Documentar a entrega em [notas 2.1.10](RELEASE_2.1.10.md), guias e READMEs; preservar instaladores anteriores.
- [ ] Localizar os scripts pessoais `gerar-2.1.8.sh`/`.command`, ausentes neste checkout, para generalizar a versão. Os scripts oficiais de release são usados na entrega.

- [ ] Operador validar upload/reinício pela console, atualizar uma máquina de cada servidor e solicitar nova coleta; registrar resultados em [validação](validation/console-release-2.1.10.md).
- [ ] TomEE opcional e patches `.installation` permanecem fora desta entrega.

## Entrega 2.1.8 — agente e dashboard

- [x] Agente: pausa adaptativa, find único no Java 6, poda de homes de produto, orçamentos separados, hsperfdata, Java substituído em disco, JARs internos sem descompactar, catálogo de drivers/pools, placeholders, JBoss domain/XA/módulos/legado, Tomcat pools/ResourceLink, TNS, módulos JDBC de aplicação no WebLogic. Revisão independente e correções.
- [x] Dashboard: status de suporte (catálogo com fontes), painel de riscos, abas Datasources JDBC e Bancos de dados, coluna JDBC resumida nos relatórios, textos.
- [ ] Gerar pacotes assinados no Mac e testar upload pela console, atualização de agentes e nova coleta.
- [ ] Conferir no catálogo as datas marcadas como fonte secundária (JBoss EAP 7.4/8, WebLogic 12.2.1.4/14.1.2, Oracle JDBC 11–18).
- [ ] Validar o modo find do Java 6 e o hsperfdata em RHEL 5 real; patches do registry.xml com Oracle Home real.
- [ ] Próximos: cache incremental entre coletas; servidores parados com datasources; comparação entre coletas.

## Entrega 2.1.6 — teste pela console

- [x] Datasources Spring: associar driverArtifact à biblioteca única da aplicação; preservar ambiguidades e separar versão do driver/banco. Fixtures de JAR aninhado, associação de processo, integração opcional e relatórios/exportações validadas.
- [x] Timeout do lote: cinco minutos para iniciar quando chegar a vez; persistência, manutenção sem heartbeat, bloqueio de claim tardio V1/V3 e avanço da próxima máquina. Atualizações iniciadas preservam recuperação.
- [x] Motivo visível em Solicitações e atualização de agentes; novo pedido permitido quando a máquina voltar.
- [x] Documentação, README e PDFs com nomes genéricos revisados.
- [ ] Operador enviar o ZIP web-update 2.1.6 na console, confirmar e verificar reconexão/versão final. Não houve deploy desta entrega por SSH.
- [ ] Após atualizar OL07, solicitar coleta e confirmar JAR nas linhas dos três datasources reais; a coleta anterior confirmou versão H2 2.3.232, mas não o campo driverArtifact.
- [ ] Ensaio operacional do lote com uma máquina desligada; os cenários de timeout, reinício e retorno tardio já passaram em testes isolados.

## Versão de drivers JDBC — entrega de laboratório 2.1.2

Descoberta Oracle JDBC e evidências nos relatórios implementadas; probe Java 6/RHEL5 identificou Oracle 11.2.0.3.0 e Derby 10.12.1.1. Pacote final e publicação registrados nas [evidências](validation/jdbc-driver-resolution-2026-10-19.md). A simulação Oracle não ativa datasource nem conecta a banco.

## Atualização administrativa — 2.1.0 publicada no laboratório

Implementação concluída, pacotes validados em ambiente isolado e primeiro deploy no dashboard de laboratório confirmado em 2026-10-16. HTTPS externo 2.1.0, executor ativo/habilitado e permissões da fila conferidos; banco, configuração e CA preservados. Nenhum agente atualizado pelo deploy: [desenho](superpowers/specs/2026-10-16-dashboard-self-update-design.md), [operação](DASHBOARD_SELF_UPDATE.md), [evidências](validation/dashboard-self-update-2026-10-16.md). A 2.0.57 permanece preservada para o cliente. Não publicar no cliente sem validação da entrega final.


## Descoberta WebLogic/Spring e destinos JDBC — fonte validada

**Status em 2026-10-16:** implementação e regressões locais concluídas, com probe isolado executado em Java 6 na OL7 e RHEL5. Após autorização de publicação, dashboard de laboratório atualizado para 2.0.55; pacote assinado disponibilizado, agentes instalados preservados. HTTPS, arquivos da interface, banco, configuração e CA conferidos. Detalhes em [evidências](validation/jdbc-discovery-2026-10-16.md) e [comandos de laboratório](runbooks/jdbc-discovery-lab.md).

- [x] Domínios WebLogic vinculados a processos em execução; domínio, datasource, host/IP, porta e banco/serviço. Servidores homônimos mantêm associações independentes.
- [x] Descoberta Spring Boot em JAR executável e classpath explícito; prioridade e cache limitado de configurações; novos casos de teste.
- [x] Destinos JDBC em bibliotecas, todos os modelos de relatório, filtros e CSV/PDF, sem URL completa nem credenciais.
- [x] Padrões de 50.000 arquivos, 180 segundos e 16 MiB de metadados; heap mantido em 96 MiB e limites explicitamente configurados preservados.
- [x] Regressão local e probe nativo nas duas VMs, sem modificar agentes. Bancos reais do laboratório são locais; endereços remotos foram testados com fixtures.
- [x] Publicar a entrega de laboratório 2.0.55 autorizada, com pacotes assinados e origem 2.0.53 verificada; scripts, bibliotecas e chave pública iguais aos da 2.0.53.
- [x] As cinco máquinas concluíram 2.0.55 após retomada operacional. Correção durável e instalador consolidados na preparação 2.0.57; publicação e canário nativo com pacote final ainda separados da geração dos artefatos.

## Recuperador V3 após reinício — correção preparada na 2.0.57

Em 2026-10-16, o pedido `e9a58be8-deb0-40fc-8f5b-d692eada4876` (2.0.53 → 2.0.55) ficou WAITING apesar de heartbeats recentes. O serviço java-assessment-recovery estava habilitado, mas inactive/dead, Result=success. O marcador privado vazio state/recovery.stop era anterior ao boot. ExecStart executa diretamente `update-bootstrap.sh loop`; esse caminho não remove o marcador de parada do desligamento anterior.

Retomada pontual: confirmados hostname, serviço sem PID/cgroup/job, ausência de transação/PID de recovery, hash do bootstrap conhecido, marcador root/600/regular/vazio/um link/anterior ao boot. Removido apenas o marcador e iniciado o recuperador pelo systemd. Hashes da configuração permaneceram iguais e o mesmo pedido avançou para DOWNLOADING, sem novo pedido nem alteração de artefatos assinados.

**Outras máquinas:** confirmado o mesmo marcador anterior ao boot e serviço inativo na instance-ol8, instance-web e ol07. Aplicada a mesma retomada pontual com configurações preservadas. RHEL5/SysV estava com recuperador ativo, sem marcador; o lote sequencial aguardava as máquinas anteriores, conforme a regra de ordinal do coordenador. Não foi necessário alterar a RHEL5.

**Resultado:** o mesmo pedido confirmou SUCCEEDED/OK em 2026-10-16 às 15:19:40 BRT, com heartbeat na versão 2.0.55. Isso valida a atualização observada após a retomada; a falha de boot permanece aberta.

**Lote concluído:** em 2026-10-16 às 15:27:56 BRT, as cinco máquinas confirmaram 2.0.55/SUCCEEDED: instance-09, instance-ol8, instance-rh5, instance-web e ol07. Na última consulta, três coletas já haviam concluído com observações, a RHEL5 concluído sem observações e a ol07 estava pronta para coletar. Recibo privado em `output/dashboard-releases/2.0.55/evidence/lab-batch-after-recovery.json`.

- [x] Extensão systemd própria instalada pelo manager novo antes da confirmação, sem alterar a âncora. Testes de propriedade, links, permissões, PID ativo/reutilizado, start/restart e restart da âncora real na OL8 passaram. Desinstalação reconhece somente a extensão exata. Pacote preparado 2.0.57; implantação nas máquinas ainda pendente.
- [x] Restart nativo na OL8 preservou identidade/configuração/bootstrap/ponteiro e confirmou uma instância. Testes locais cobriram rollback e interrupções do journal.
- [ ] Reboot completo/corte de energia com transação em andamento e canário nativo do pacote final; não confundir com os testes de restart e fixtures.

## Próxima versão — correções do instalador e inicialização do dashboard

**Status:** correções implementadas e artefatos 2.0.57 preparados em 2026-10-16. Python 3.6 real na OL8, Python 3.9 local, assinatura pública e HTTPS passaram; ZIP final passou em --check. Instalação completa em servidor vazio e atualização nativa de dashboard 2.0.53 continuam ensaios distintos, ainda não executados. Ver [evidências](validation/recovery-installer-2026-10-16.md).

**Regra de entrega:** manter a versão atual durante desenvolvimento e validação; somente depois de validar as correções incrementar para uma entrega consolidada. Evitar versões intermediárias e atualizações sucessivas dos agentes do cliente, conforme o [processo de release](RELEASE_PROCESS.md).

- [x] Incorporar à JVM do dashboard o ajuste `-Djava.security.egd=file:/dev/urandom`, incluindo instalação nova e atualização. O diagnóstico mostrou a thread principal bloqueada em `NativePRNG.implGenerateSeed` / `SecureRandom.generateSeed` durante `AssessmentServer.tls`. O operador confirmou a recuperação após aplicar o ajuste ao launcher. Restringir a configuração ao dashboard, sem modificar o Java global ou os agentes.
- [x] Corrigir o uso de `subprocess.run(capture_output=True)` incompatível com Python 3.6 no instalador, preservando captura de saída, timeout e verificação de assinatura. Revisar a seleção do interpretador e validar o caminho completo em Python 3.6 e 3.9; o teste local de contrato de API não substitui execução no interpretador real.
- [x] Tornar explícita a configuração de geração da CA e do certificado do servidor, incluindo extensões, SAN DNS/IP e validação da cadeia. A geração com configuração explícita resolveu o erro observado de `unable to get local issuer certificate`; a causa exata da falha na geração original ainda precisa ser reproduzida. Preservar certificados fornecidos pelo cliente e não desabilitar a validação TLS.
- [x] Melhorar o diagnóstico do instalador: apresentar saída útil e sanitizada do OpenSSL, sem expor senhas, tokens ou chaves privadas; distinguir processo ativo de aplicação pronta. Revisar a verificação HTTPS de `/health`, com timeout e erro claro quando a inicialização não termina, antes de anunciar sucesso. O instalador já possui uma verificação de saúde; fortalecer sua cobertura, sem tratar o travamento posterior como prova de que ela não existia.
- [ ] Validar instalação nova, reinicialização do serviço e atualização da 2.0.53, preservando banco, usuários, configurações, certificados e cadastros dos agentes. Conferir `/health`, login e persistência do ajuste após atualização; incluir regressões de falhas TLS e inicialização bloqueada. Documentar o procedimento e os limites dos testes na entrega.

**Evidência operacional:** correção pontual aplicada pelo operador ao launcher do dashboard, com backup, seguida de reinício e confirmação de funcionamento. Incorporada à preparação 2.0.57; artefatos anteriores permanecem intactos. A geração do instalador foi autorizada; publicação desta preparação ainda não executada.

## Atualização remota completa do agente

**Status:** implementação e regressões locais concluídas em 2026-10-14, com origem mínima 1.5.12. Bridge com binário original, pacote completo, operação lógica, recuperação, limpeza, desinstalação, empacotamento e assessment posterior integrados. Houve migração automática 1.5.12 para 2.0.11 em RHEL 7/Java 11, recuperação assistida em RHEL 8/Java 17 e evolução 2.0.9 para 2.0.11 em RHEL 9/Java 11, com assessment recebido nos dois últimos ensaios enviados pelo operador. A correção da concorrência conhecida ainda não foi publicada; O operador também confirmou migração e assessment em RHEL 5/Java 6. Interrupções/reboot, fronteiras de recuperação e prova do candidato corrigido continuam gates de release. O mecanismo em produção continua sendo o atual.

**Candidato de laboratório:** 2.0.9, com bridge interno 2.0.8, publicado pelo operador. A primeira tentativa pelo dashboard ocorreu em uma terceira máquina de laboratório, RHEL 9/Java 11, e ficou em `PREPARED`: o coletor antigo, protegido por `ProtectSystem=full`, não podia criar o serviço de recuperação em `/etc`. Após a retomada pontual pelo operador, o mesmo Work request confirmou 2.0.9 em 2026-10-14 às 22:45 GMT-3. A máquina voltou a reportar e um assessment vinculado foi recebido. Naquele momento, os dois canários originais 1.5.12 ainda não tinham concluído a migração.

**Correção em validação:** a próxima montagem deve iniciar o bootstrap systemd em um serviço independente criado pelo PID 1, preservando a proteção do coletor. Fonte corrigida e regressões locais passaram; os artefatos publicados 2.0.9 permanecem imutáveis. `ops/retomar-migracao.py` destravou exclusivamente o pedido identificado, conferindo assinatura, origem e hashes preservados de configuração, identidade, Java, CA e chave pública. O sucesso posterior foi conferido na interface real. O próximo candidato deve comprovar o fluxo automático em um canário original 1.5.12, sem essa intervenção. Isso não encerra os gates de Linux/Java 6, reinício/recuperação, segundo canário e evolução posterior.

**Teste do candidato 2.0.11:** publicado pelo operador. O canário original RHEL 8/Java 17 chegou ao bridge 2.0.10 e ao pacote `PREPARED`, mas não concluiu. O serviço temporário foi criado fora da proteção do coletor (`ProtectSystem=no`), confirmando essa parte da correção. Ele falhou ao disputar o flock com o atualizador original 1.5.12 (`LIFECYCLE_LOCK_BUSY`); a unidade falhada com nome fixo impediu nova execução automática. Configuração, identidade, Java e confiança continuam com hashes iguais aos anteriores. Nenhum bootstrap, origem de recuperação ou ponteiro ativo foi criado nessa tentativa. Os artefatos publicados permanecem imutáveis.

**Correção na fonte, ainda não publicada:** parar apenas o helper legado, sem segurar o flock, antes de enfileirar o bootstrap; persistir seu estado original, confirmar inatividade e contadores zerados e restaurá-lo condicionalmente se a fila falhar antes do handoff. A restauração precisa limpar somente o marcador operacional `stop` vazio, regular, privado e pertencente a root, porque o `ExecStart=loop` original não faz essa limpeza; start sem confirmação posterior não é sucesso. O pedido RHEL 8 foi retomado com seus arquivos assinados existentes, conforme registro abaixo. O sucesso posterior em RHEL 7 não elimina a corrida reproduzida em RHEL 8; não liberar para cliente antes dos gates restantes.

**Retomada pontual concluída:** o primeiro reparo 2.0.11 parou antes do bootstrap com `ORIGINAL_HELPER_DID_NOT_STOP`; os seis hashes de preservação continuaram iguais. O diagnóstico posterior confirmou o helper `inactive/dead`, MainPID e ControlPID zerados, `Job=` vazio, ControlGroup vazio e cgroups ausentes. O marcador de parada era regular, root, modo 600 e vazio. A leitura corrigida solicita explicitamente todas as propriedades, distingue campos ausentes de `Job=` vazio e aceita apenas a inatividade comprovada; quando o helper já está parado, não repete a parada. O operador retomou o mesmo pedido, e o dashboard confirmou 2.0.11 em 2026-10-14 às 23:35 GMT-3, 1/1 atualizada e nenhuma pendência; a máquina voltou a reportar. Assessment vinculado liberado e aguardando coleta na primeira conferência. Houve um falso negativo posterior no resumo do script ao exigir propriedades de serviço para o timer; a leitura foi corrigida sem nova execução na máquina. O sucesso com reparo manual não valida a próxima migração automática.

**Regressões da correção:** 256 checks do handoff, 323 checks da retomada 2.0.11, 54 checks da retomada anterior, contratos de manifesto/rotas e 30 checks dos wrappers passaram localmente. O timer usa apenas propriedades comuns de unidade; falha nessa exibição final registra `UNKNOWN` sem invalidar handoff já comprovado. Os guardas dos serviços permanecem estritos. ECJ compilou 61 classes, todas com bytecode Java 6. Os serviços continuam simulados nesses testes: nenhuma dessas contagens substitui a prova nativa.

**Evolução após a migração confirmada:** o operador atualizou a máquina RHEL 9/Java 11 de 2.0.9 para 2.0.11 pelo painel. A captura enviada mostrou atualização completa confirmada, verificação e conclusão em 2026-10-14 às 23:42:35 GMT-3. O assessment automático posterior foi recebido, com 4 instalações Java e 0 servidores; não houve retomada manual relatada nesse teste. Isso comprova o salto observado para uma instalação já migrada, sem encerrar os gates das demais rotas e plataformas. Os pacotes publicados 2.0.11 ainda não incluem a correção da corrida com o helper legado.

**Migração automática 1.5.12 confirmada no segundo canário:** a captura posterior do operador mostrou RHEL 7/Java 11 migrando de 1.5.12 para 2.0.11, com atualização completa confirmada em 2026-10-14 às 23:48:58 GMT-3. O assessment posterior foi recebido, com 1 instalação Java e 2 servidores. Não houve retomada manual relatada nesse pedido. Registrar como sucesso da rota observada; manter a falha de concorrência RHEL 8 aberta até publicar e validar a correção. Como esse canário agora também está migrado, uma nova prova com origem 1.5.12 exigirá outra instalação original ou restauração controlada de laboratório, não downgrade improvisado.

**Gate adicional de recuperação:** conferir falhas fatais em `PREPARED` depois da instalação do bootstrap, antes de `QUIESCING`. O anchor imutável 2.0.9 pode encerrar o pedido sem restaurar helper/origem nessa fronteira. Uma alteração apenas na fonte nova não corrige retroativamente o anchor instalado; esse caso requer desenho e prova antes da entrega ao cliente.

- [Desenho: migração, preservação, recuperação, limpeza e assessment](superpowers/specs/2026-10-14-full-agent-update-design.md)
- [Plano com oito workflows e gates de validação](superpowers/plans/2026-10-14-full-agent-update.md)

O primeiro gate usa o atualizador e os scripts originais 1.5.12, incluindo ACK, limpeza, locks e passagem para recuperação independente. Depois dos testes isolados, duas máquinas ainda em 1.5.12 serão canários em sequência. Uma segunda release que altere suporte/manager deverá comprovar a evolução posterior pelo painel.

No mecanismo anterior ao candidato 2.0.9, a atualização remota substitui somente `collector.jar`. Os scripts, bibliotecas e o atualizador instalado precisam corresponder aos arquivos de suporte do manifesto assinado. Pular versões é permitido quando esses requisitos continuam compatíveis.

Nos pacotes publicados, 1.5.10 a 2.0.0 usam `update-v1`; a 2.0.1 passou a `update-v2` e alterou o atualizador; a 2.0.2 alterou o launcher para 96 MiB. Os arquivos de suporte da 2.0.2 e 2.0.3 são iguais. A compatibilidade de entregas futuras precisa ser verificada, não inferida apenas pelo número da versão.

### Resultado desejado

- Permitir atualizar também scripts, bibliotecas e componentes do atualizador pelo dashboard, sem SSH, com pacote e manifesto assinados e verificação de integridade.
- Definir uma migração explícita para atualizadores antigos. Eles não passam a entender um novo formato só porque o dashboard mudou.
- Preservar identidade, ambiente, configuração, confiança TLS/CA e inventário existente.
- Validar arquivos e permissões antes da troca; manter backup e recuperação caso haja falha, interrupção de energia ou falha na verificação de funcionamento.
- Mostrar a compatibilidade real antes da confirmação e acompanhar todas as etapas em Work requests.
- Validar saltos entre releases, Java 6 e posteriores e os serviços SysV/systemd. Não declarar suporte testado em 32 bits enquanto não houver validação real.

Não remover verificações de assinatura, hashes ou geração do atualizador para liberar artificialmente agentes antigos.

Toda entrega continua lembrando a atualização dos agentes no dashboard e nas instruções, conforme [o processo de release](RELEASE_PROCESS.md). O código novo inclui a opção de executar um assessment após a confirmação final. Consulte o [contrato V3](AGENT_UPDATE_V3.md), as [evidências e limites](validation/full-agent-update.md) e o [runbook de canários](runbooks/agent-update-1512-canary.md).

**RHEL 5/Java 6 confirmado pelo operador em 2026-10-15:** a atualização de 2.0.5 para 2.0.11 finalizou com sucesso e o assessment automático foi recebido. O pedido havia sido observado na interface em migração automática. A confirmação não fornece medições de memória/tempo nem prova recuperação após interrupção; esses gates continuam abertos.

**Candidato 2.0.13 preparado em 2026-10-15, ainda não publicado:** inclui a correção da parada coordenada do helper legado no bridge 2.0.12, navegação automática para o Work request confirmado e menu Java Assessment para Machines / All environments. Fontes e oito bridges empacotados foram conferidos; assinaturas e checks negativos passaram. A validação nativa do candidato corrigido e os gates de recuperação continuam pendentes. Ver [notas 2.0.13](RELEASE_2.0.13.md).
