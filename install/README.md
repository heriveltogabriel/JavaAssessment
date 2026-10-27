# Instalação do Java Assessment

Arquivos para instalar o dashboard e os agentes na infraestrutura do cliente. Os manuais ficam nesta pasta; cada versão tem sua própria pasta de instalador.

## Instalação e atualização de agentes com Ansible

- [Guia de instalação, recuperação e exemplos por ambiente](ansible/README.md)
- [Atualizar agentes existentes em lotes, preservando cadastro e sem SSH](ansible/UPDATE.md)
- [Baixar pacote Ansible](ansible/java-assessment-ansible.zip)
- [SHA-256 do pacote](ansible/java-assessment-ansible.zip.sha256)

O playbook usa wget, instala com sudo em lotes de duas máquinas preserva agentes íntegros e recupera instalações incompletas reconhecidas. Uma opção por host permite reinstalação limpa com backup e remoção oficial. Preencha o inventário e guarde as credenciais no Ansible Vault. Os exemplos publicados não contêm credenciais reais.

## Java para instalação sem download na VM

- [OpenJDK 17 para Linux x86_64](java/README.md): pacote Temurin em partes, checksums e instruções de reconstrução e extração.

## Documentação

- [Manual ilustrado da solução](Manual.pdf): arquitetura, inventário, atualização de agentes, relatórios e filtros JDBC.
- [Guia de instalação](Guia-instalacao.pdf): requisitos, comandos, configuração do dashboard e instalação dos agentes.

## Entrega atual para teste — 2.1.28

[Pacotes da 2.1.28](v2.1.28/README.md): menos contenção no SQLite, acompanhamento sem consultas sobrepostas, concorrência padrão 10/teto 25 e coleta agendada distribuída. Envie o [web-update](v2.1.28/assessment-dashboard-web-update-2.1.28.zip) na Administração. Atualização direta e rollback local verificados para as consoles **2.1.20/2.1.22/2.1.24/2.1.26**. [Notas](../docs/RELEASE_2.1.28.md) · [Provas e limites](../docs/validation/console-release-2.1.28.md).

**Atenção: atualize também os agentes. Atualizar o dashboard não atualiza os agentes automaticamente.** Agentes atuais **2.1.26** já aproveitam a melhora do servidor; sua atualização é **opcional** nesta entrega. Não instalar a ponte 2.1.27 manualmente para agentes V3 elegíveis. Os pacotes anteriores permanecem intactos.

## Entrega anterior preservada — 2.1.26

[Pacotes 2.1.26](v2.1.26/README.md): campanhas de atualização com grupos, concorrência global e cancelamento em lote; revisão e acompanhamento paginados. Envie o [web-update](v2.1.26/assessment-dashboard-web-update-2.1.26.zip) em **Administração → Atualização do dashboard**. [Notas](../docs/RELEASE_2.1.26.md) · [Evidências e rotas verificadas](../docs/validation/console-release-2.1.26.md).

**Atualizar o dashboard não atualiza os agentes automaticamente.** Atualize-os separadamente em uma campanha, começando por um piloto. As mudanças funcionais desta entrega estão no dashboard. Ponte interna 2.1.25, sem instalação manual nas origens V3 elegíveis. Os pacotes anteriores permanecem preservados.

## Entrega anterior preservada — 2.1.24

[Pacotes 2.1.24](v2.1.24/README.md): identificação de drivers JDBC, correções WebLogic e suporte a `ojdbc14`. Use o [web-update](v2.1.24/assessment-dashboard-web-update-2.1.24.zip) pela Administração. [Notas](../docs/RELEASE_2.1.24.md) · [Validação](../docs/validation/console-release-2.1.24.md).

**Atualize também os agentes e execute nova coleta.** Os dados anteriores não são recalculados. A ponte interna 2.1.23 não exige instalação manual. Consulte as origens verificadas e limites de validação no README dos pacotes.

## Entrega anterior preservada — 2.1.22

[Pacotes 2.1.22](v2.1.22/README.md): cadastro sem teto fixo de máquinas, formulários com 100.000 usos por código, limpeza e paginação. Envie o [web-update](v2.1.22/assessment-dashboard-web-update-2.1.22.zip) na Administração da sua console 2.1.20. [Notas](../docs/RELEASE_2.1.22.md) · [Validação](../docs/validation/console-release-2.1.22.md).

**Atualizar o dashboard não atualiza agentes automaticamente.** Agentes 2.1.20 podem permanecer instalados para esta melhoria. Não é necessário instalar a ponte 2.1.21 manualmente.

## Entrega anterior preservada — 2.1.20

[Pacotes 2.1.20](v2.1.20/README.md): coluna **Profile/contexto** nos datasources e nos detalhes. Use o [web-update](v2.1.20/assessment-dashboard-web-update-2.1.20.zip) na atualização administrativa. [Notas](../docs/RELEASE_2.1.20.md) · [Validação](../docs/validation/console-release-2.1.20.md).

Atualizar o dashboard não atualiza agentes automaticamente. Esta melhoria usa dados já coletados e não exige agente novo; revise a elegibilidade no painel para alinhar versões separadamente.

## Entrega anterior preservada — 2.1.18

[Pacotes da 2.1.18](v2.1.18/README.md): Excel (.xlsx), Mapa de bancos e driver provável somente com candidato no classpath. Use o [web-update](v2.1.18/assessment-dashboard-web-update-2.1.18.zip) a partir da 2.1.14 ou 2.1.16. [Notas](../docs/RELEASE_2.1.18.md) · [Validação](../docs/validation/console-release-2.1.18.md).

**Atualizar o dashboard não atualiza agentes automaticamente.** Não houve mudança funcional no agente; os novos formatos aproveitam o inventário existente. Atualize agentes separadamente pelo painel se desejar alinhar a versão unificada. O [guia de exportação](../docs/INVENTORY_EXPORT.md) detalha as novas opções e complementa o manual genérico.

## Entrega anterior preservada — 2.1.16

[Pacotes da 2.1.16](v2.1.16/README.md): exportação técnica em JSON ou CSV único dentro do ZIP pela Administração. Use o [web-update](v2.1.16/assessment-dashboard-web-update-2.1.16.zip) para atualizar pela console. [Notas](../docs/RELEASE_2.1.16.md) · [Validação](../docs/validation/console-release-2.1.16.md).

**Atualize também os agentes separadamente pelo painel.** A exportação já aproveita os dados existentes. Agentes 2.1.14 com atualizador V3 usam atualização direta; instalações sem capacidade/rota elegível precisam do instalador configurado para seu ambiente.

## Entrega anterior preservada — 2.1.14

[Pacotes da 2.1.14](v2.1.14/README.md): classpath longo e versão/JAR candidatos visíveis. Use o [web-update](v2.1.14/assessment-dashboard-web-update-2.1.14.zip) na console. [Notas](../docs/RELEASE_2.1.14.md) · [Validação da origem 2.1.12](../docs/validation/console-release-2.1.14.md).

**Atualize também os agentes e solicite nova coleta.** O dashboard pode apresentar candidatos já coletados; os novos limites dependem do agente novo.

## Entrega anterior preservada — 2.1.12

[Pacotes da 2.1.12](v2.1.12/README.md): correções da descoberta de aplicações e drivers, manifestos assinados, configurações externas e padrões de coleta. Envie [assessment-dashboard-web-update-2.1.12.zip](v2.1.12/assessment-dashboard-web-update-2.1.12.zip) em Administração → Atualização do dashboard.

[Notas](../docs/RELEASE_2.1.12.md) · [Validação 2.1.8 → 2.1.12](../docs/validation/console-release-2.1.12.md). **Atualize os agentes separadamente** e solicite nova coleta. `/opt` volta ao padrão de inclusão; listas salvas permanecem como estão.

## Entrega anterior preservada — 2.1.10

[Pacotes da 2.1.10](v2.1.10/README.md): configuração Spring e resolução JDBC no WebLogic, Tomcat e JBoss/WildFly. Envie [assessment-dashboard-web-update-2.1.10.zip](v2.1.10/assessment-dashboard-web-update-2.1.10.zip) em Administração → Atualização do dashboard.

[Notas](../docs/RELEASE_2.1.10.md) · [Validação](../docs/validation/console-release-2.1.10.md). **Atualize os agentes separadamente** e solicite nova coleta. Novos padrões excluem `/opt`; listas salvas são preservadas. O operador fará o teste pela console.

## Entrega anterior preservada — 2.1.8

[Pacotes da 2.1.8](v2.1.8/README.md): agente mais rápido e preciso (JVMs em execução, JARs internos, drivers e datasources) e dashboard com status de suporte, painel de riscos e visão por banco de dados. Para atualizar pela tela, envie [assessment-dashboard-web-update-2.1.8.zip](v2.1.8/assessment-dashboard-web-update-2.1.8.zip) em Administração → Atualização do dashboard.

[Notas](../docs/RELEASE_2.1.8.md). **Atualize os agentes separadamente** e solicite nova coleta. O deploy pela console será feito pelo operador.

## Entrega anterior preservada — 2.1.6

[Pacotes da 2.1.6](v2.1.6/README.md): JAR do datasource Spring e timeout de início por máquina no lote. Para atualizar pela tela, envie [assessment-dashboard-web-update-2.1.6.zip](v2.1.6/assessment-dashboard-web-update-2.1.6.zip) em Administração → Atualização do dashboard. O instalador de servidor novo não serve para upload.

[Notas](../docs/RELEASE_2.1.6.md) · [Validação](../docs/validation/console-release-2026-10-19.md). **Atualize os agentes separadamente** e solicite nova coleta para os campos Spring. O deploy pela console será feito pelo operador.

## Entrega anterior preservada — 2.1.4

[Pacotes da 2.1.4](v2.1.4/README.md): coluna Arquivo JAR separada da versão nas bibliotecas/datasources. [Notas](../docs/RELEASE_2.1.4.md). Agentes 2.1.2 podem permanecer instalados para esta melhoria visual; a publicação não os atualiza automaticamente.

## Entrega anterior preservada — 2.1.2

[Instaladores e comandos da 2.1.2](v2.1.2/README.md), com resolução de versões de drivers JDBC por datasource WebLogic. [Notas](../docs/RELEASE_2.1.2.md) e [evidências](../docs/validation/jdbc-driver-resolution-2026-10-19.md).

**Atualize os agentes separadamente pelo dashboard** e solicite nova coleta para receber os campos JDBC. Para agentes V3 elegíveis na 2.1.0, o destino é diretamente 2.1.2. A entrega anterior permanece preservada.

## Entrega anterior preservada — 2.1.0

[Pacotes e comandos da 2.1.0](v2.1.0/README.md):

- [Instalador para servidor novo](v2.1.0/assessment-dashboard-installer-2.1.0.zip).
- [Atualização por script para primeira adoção](v2.1.0/assessment-dashboard-update-2.1.0.zip).
- [Pacote assinado para atualização web](v2.1.0/assessment-dashboard-web-update-2.1.0.zip), com as restrições de origem descritas no guia da versão.
- [Notas da versão](../docs/RELEASE_2.1.0.md) e [guia da atualização administrativa](../docs/DASHBOARD_SELF_UPDATE.md).

Dashboard publicado e verificado no laboratório. O operador confirmou a instalação e atualização dos agentes. O pacote web da própria 2.1.0 não se reaplica à mesma versão e não substitui o script que instala o executor nas versões antigas. Os PDFs mantêm nomes genéricos; o novo fluxo está documentado no guia administrativo acima.

## Versão anterior preservada — 2.0.57

[Instalador, atualização e comandos da 2.0.57](v2.0.57/README.md). Os pacotes originais permanecem inalterados para o cliente que continuar nessa versão.

## Instalador anterior — 2.0.53

- [assessment-dashboard-installer-2.0.53.zip](v2.0.53/assessment-dashboard-installer-2.0.53.zip)
- [Arquivo SHA-256 da 2.0.53](v2.0.53/assessment-dashboard-installer-2.0.53.zip.sha256)
- [O que mudou na 2.0.53](../docs/RELEASE_2.0.53.md)

Inclui a interface em português, relatórios PDF/CSV com JDBC, instalação por ZIP ou curl/wget, descoberta Spring Boot, indicadores de comunicação e limpeza do inventário. Esta entrega inclui os ajustes finais publicados na interface: resumo “1 coleta concluída com observações.”, filtros de relatórios recolhidos ao entrar/trocar de modelo e correção do guia de instalação do agente.

Este ZIP instala um dashboard novo; não execute o instalador sobre um dashboard existente para atualizá-lo. Requer Enterprise Linux 8+ de 64 bits e JDK 17+. O suporte legado do agente não se aplica ao servidor do dashboard. Linux/Java de 32 bits ainda não foi validado.

```sh
# Linux
sha256sum -c assessment-dashboard-installer-2.0.53.zip.sha256
# macOS
shasum -a 256 -c assessment-dashboard-installer-2.0.53.zip.sha256
```

O resultado deve ser `OK`. Os PDFs mantêm nomes genéricos; consulte também o guia incluído no ZIP, as notas da versão, [a instalação via terminal](../docs/ENVIRONMENT_INSTALL_ACCESS.md) e [a limpeza do inventário](../docs/MACHINE_CLEANUP.md).

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza as máquinas automaticamente. Abra **Revisar agentes**: instalações V3 e perfis legados conhecidos a partir de 1.5.12 podem usar o painel quando elegíveis pelo catálogo assinado. Instalações não elegíveis precisam do pacote configurado do ambiente com `--system-root`. Acompanhe em **Solicitações**, confirme a versão final e solicite nova coleta para obter os novos dados. Comece por uma máquina.

## Instalador anterior — 2.0.17

- [assessment-dashboard-installer-2.0.17.zip](v2.0.17/assessment-dashboard-installer-2.0.17.zip)
- [Arquivo SHA-256 da 2.0.17](v2.0.17/assessment-dashboard-installer-2.0.17.zip.sha256)
- [O que mudou na 2.0.17](../docs/RELEASE_2.0.17.md)

## Instalador anterior — 2.0.13

- [assessment-dashboard-installer-2.0.13.zip](v2.0.13/assessment-dashboard-installer-2.0.13.zip)
- [Arquivo SHA-256 da 2.0.13](v2.0.13/assessment-dashboard-installer-2.0.13.zip.sha256)
- [O que mudou na 2.0.13](../docs/RELEASE_2.0.13.md)

Inclui a atualização completa dos agentes elegíveis, assessment após atualização, abertura direta do Work request, menu Java Assessment e listagem compacta de IPs. Este ZIP instala um dashboard novo; não execute o instalador sobre um dashboard existente para atualizá-lo.

Confira com `sha256sum -c assessment-dashboard-installer-2.0.13.zip.sha256` no Linux ou `shasum -a 256 -c assessment-dashboard-installer-2.0.13.zip.sha256` no macOS. O resultado deve ser `OK`.

Para esta entrega histórica, consulte o guia incluído no ZIP e as notas da 2.0.13; os PDFs no início desta página acompanham a operação atual. O dashboard requer Enterprise Linux 8+ de 64 bits e JDK 17+; o suporte legado do agente não se aplica ao servidor do dashboard. Linux/Java de 32 bits ainda não foi validado.

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza as máquinas automaticamente. Use **Revisar agentes**: instalações V3 elegíveis recebem o pacote completo; perfis legados conhecidos a partir de 1.5.12 podem usar a migração pelo painel. A versão sozinha não comprova compatibilidade. Instalações sem suporte elegível precisam do pacote configurado do ambiente com `--system-root`. Acompanhe o Work request e o assessment após a atualização. Teste primeiro uma máquina.

## Instalador anterior — 2.0.5

- [assessment-dashboard-installer-2.0.5.zip](v2.0.5/assessment-dashboard-installer-2.0.5.zip)
- [Arquivo SHA-256 da 2.0.5](v2.0.5/assessment-dashboard-installer-2.0.5.zip.sha256)
- [O que mudou na 2.0.5](../docs/RELEASE_2.0.5.md)

A 2.0.5 traz os quatro painéis compactos do overview, a lista paginada de máquinas e o aviso para revisar agentes desatualizados. Para os procedimentos e limites desta entrega histórica, consulte o guia incluído no respectivo ZIP e suas notas de versão.

Confira a integridade com `sha256sum -c assessment-dashboard-installer-2.0.5.zip.sha256` no Linux ou `shasum -a 256 -c assessment-dashboard-installer-2.0.5.zip.sha256` no macOS.

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza as máquinas automaticamente. Instalações 2.0.2 e 2.0.3 com atualização remota habilitada e suporte original podem receber 2.0.5 pelo painel. Para agentes anteriores, baixe o pacote do ambiente no dashboard e utilize o instalador completo com `--system-root`. Acompanhe em **Work requests**.

## Instalador anterior — 2.0.3

- [assessment-dashboard-installer-2.0.3.zip](v2.0.3/assessment-dashboard-installer-2.0.3.zip)
- [Arquivo SHA-256 da 2.0.3](v2.0.3/assessment-dashboard-installer-2.0.3.zip.sha256)
- [Mudanças da 2.0.3](../docs/RELEASE_2.0.3.md)

## Instalador anterior — 2.0.2

- [Baixar assessment-dashboard-installer-2.0.2.zip](https://github.com/heriveltogabriel/JavaAssessment/raw/refs/heads/main/install/v2.0.2/assessment-dashboard-installer-2.0.2.zip)
- [Baixar arquivo SHA-256](https://github.com/heriveltogabriel/JavaAssessment/raw/refs/heads/main/install/v2.0.2/assessment-dashboard-installer-2.0.2.zip.sha256)

Baixe os dois arquivos na mesma pasta. Confira a integridade antes de instalar:

**Linux**

```sh
sha256sum -c assessment-dashboard-installer-2.0.2.zip.sha256
```

**macOS**

```sh
shasum -a 256 -c assessment-dashboard-installer-2.0.2.zip.sha256
```

O resultado deve indicar `OK`. Siga então o guia de instalação. Após configurar o dashboard do cliente, crie os ambientes e baixe os agentes por ele.

O ZIP publicado nesta pasta é o pacote de entrega original da versão 2.0.2. Seu SHA-256 é:

```text
a246f70c29e3c3c6139d8dfc70a9c96ee1d6c19d5555be4982d71005e5e640ff
```
