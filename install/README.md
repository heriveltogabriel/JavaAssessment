# Instalação do Java Assessment

Arquivos para instalar o dashboard e os agentes na infraestrutura do cliente. Os manuais ficam nesta pasta; cada versão tem sua própria pasta de instalador.

## Documentação — 2.0.2

- [Manual ilustrado da solução](Manual-2.0.2.pdf): arquitetura, inventário, operação e recursos.
- [Guia de instalação](Guia-instalacao-2.0.2.pdf): requisitos, comandos, configuração do dashboard e instalação dos agentes.

## Instalador atual — 2.0.3

- [Baixar assessment-dashboard-installer-2.0.3.zip](https://github.com/heriveltogabriel/JavaAssessment/raw/refs/heads/main/install/v2.0.3/assessment-dashboard-installer-2.0.3.zip)
- [Baixar arquivo SHA-256 da 2.0.3](https://github.com/heriveltogabriel/JavaAssessment/raw/refs/heads/main/install/v2.0.3/assessment-dashboard-installer-2.0.3.zip.sha256)
- [O que mudou na 2.0.3](../docs/RELEASE_2.0.3.md)

A 2.0.3 corrige a descoberta da versão do WebLogic. Os PDFs acima foram produzidos para a 2.0.2; os procedimentos continuam aplicáveis, substituindo o número da versão nos nomes dos pacotes.

Na pasta que contém o ZIP e o SHA-256 da 2.0.3, confira a integridade com `sha256sum -c assessment-dashboard-installer-2.0.3.zip.sha256` no Linux ou `shasum -a 256 -c assessment-dashboard-installer-2.0.3.zip.sha256` no macOS.

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
