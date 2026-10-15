# Instalação do Java Assessment

Arquivos para instalar o dashboard e os agentes na infraestrutura do cliente. Os manuais ficam nesta pasta; cada versão tem sua própria pasta de instalador.

## Documentação — 2.0.2

- [Manual ilustrado da solução](Manual-2.0.2.pdf): arquitetura, inventário, operação e recursos.
- [Guia de instalação](Guia-instalacao-2.0.2.pdf): requisitos, comandos, configuração do dashboard e instalação dos agentes.

## Instalador atual — 2.0.17

- [assessment-dashboard-installer-2.0.17.zip](v2.0.17/assessment-dashboard-installer-2.0.17.zip)
- [Arquivo SHA-256 da 2.0.17](v2.0.17/assessment-dashboard-installer-2.0.17.zip.sha256)
- [O que mudou na 2.0.17](../docs/RELEASE_2.0.17.md)

Inclui relatórios de máquinas, Java, servidores, JDBC e ambientes com filtros, seleção de colunas e exportação PDF/CSV. Todos os modelos apresentam contexto JDBC; o relatório de servidores também apresenta IP e versão Java associada. Os filtros JDBC incluem datasource, banco, driver, versão, classe, referência, servidor, aplicação, tipo de recurso, estado e presença no inventário.

Este ZIP instala um dashboard novo; não execute o instalador sobre um dashboard existente para atualizá-lo. Os relatórios usam o inventário já recebido, respeitando as associações registradas entre servidores e datasources.

Confira com `sha256sum -c assessment-dashboard-installer-2.0.17.zip.sha256` no Linux ou `shasum -a 256 -c assessment-dashboard-installer-2.0.17.zip.sha256` no macOS. O resultado deve ser `OK`.

Os PDFs acima permanecem na versão 2.0.2. Consulte também o guia incluído no ZIP e as notas da 2.0.17. O dashboard requer Enterprise Linux 8+ de 64 bits e JDK 17+; o suporte legado do agente não se aplica ao servidor do dashboard. Linux/Java de 32 bits ainda não foi validado.

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza as máquinas automaticamente. Abra **Revisar agentes**: instalações V3 e perfis legados conhecidos a partir de 1.5.12 podem usar o painel quando forem elegíveis; a versão numérica sozinha não comprova compatibilidade. Instalações desconhecidas ou sem atualizador funcional precisam do pacote configurado do ambiente com `--system-root`. Acompanhe o Work request e confirme a versão final. Teste primeiro uma máquina.

## Instalador anterior — 2.0.13

- [assessment-dashboard-installer-2.0.13.zip](v2.0.13/assessment-dashboard-installer-2.0.13.zip)
- [Arquivo SHA-256 da 2.0.13](v2.0.13/assessment-dashboard-installer-2.0.13.zip.sha256)
- [O que mudou na 2.0.13](../docs/RELEASE_2.0.13.md)

Inclui a atualização completa dos agentes elegíveis, assessment após atualização, abertura direta do Work request, menu Java Assessment e listagem compacta de IPs. Este ZIP instala um dashboard novo; não execute o instalador sobre um dashboard existente para atualizá-lo.

Confira com `sha256sum -c assessment-dashboard-installer-2.0.13.zip.sha256` no Linux ou `shasum -a 256 -c assessment-dashboard-installer-2.0.13.zip.sha256` no macOS. O resultado deve ser `OK`.

Os PDFs acima permanecem na versão 2.0.2. Consulte também o guia incluído no ZIP e as notas da 2.0.13. O dashboard requer Enterprise Linux 8+ de 64 bits e JDK 17+; o suporte legado do agente não se aplica ao servidor do dashboard. Linux/Java de 32 bits ainda não foi validado.

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza as máquinas automaticamente. Use **Revisar agentes**: instalações V3 elegíveis recebem o pacote completo; perfis legados conhecidos a partir de 1.5.12 podem usar a migração pelo painel. A versão sozinha não comprova compatibilidade. Instalações sem suporte elegível precisam do pacote configurado do ambiente com `--system-root`. Acompanhe o Work request e o assessment após a atualização. Teste primeiro uma máquina.

## Instalador anterior — 2.0.5

- [assessment-dashboard-installer-2.0.5.zip](v2.0.5/assessment-dashboard-installer-2.0.5.zip)
- [Arquivo SHA-256 da 2.0.5](v2.0.5/assessment-dashboard-installer-2.0.5.zip.sha256)
- [O que mudou na 2.0.5](../docs/RELEASE_2.0.5.md)

A 2.0.5 traz os quatro painéis compactos do overview, a lista paginada de máquinas e o aviso para revisar agentes desatualizados. Os PDFs acima foram produzidos para a 2.0.2; os procedimentos continuam aplicáveis, substituindo o número da versão nos nomes dos pacotes.

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
