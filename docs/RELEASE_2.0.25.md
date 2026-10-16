# Java Assessment 2.0.25 — Instalação por ambiente via terminal

**Candidato para validação em laboratório.** Dashboard e agente: 2.0.25; bridge interno: 2.0.24. Esta preparação é local e não publica nem atualiza máquinas.

## Novo acesso de instalação

A opção **Download agent** continua disponível. Ao lado dela, **Instalar via terminal** permite copiar um comando curl ou wget e baixar o pacote diretamente na máquina Linux. Ambientes novos recebem esse acesso na criação; ambientes existentes exigem ativação explícita pelo administrador.

O comando não expira e serve para várias máquinas do mesmo ambiente. Cada execução baixa o pacote atual e recebe um código de cadastro de 24 horas. Renovar ou desativar o acesso invalida o comando anterior e novos cadastros pelos ZIPs associados, preservando agentes já registrados. O ZIP tradicional é independente.

O comando valida HTTPS com a CA do dashboard, verifica os checksums e apresenta o comando de instalação com sudo. A instalação é uma etapa separada. Credenciais não são enviadas em URL nem nos argumentos do downloader. Não foi criado playbook Ansible.

Documentação: [Instalação via terminal](ENVIRONMENT_INSTALL_ACCESS.md). Preserve a chave privada de instalação e o banco nos backups. O publicador inclui a chave existente no backup privado sem mudar os dados do serviço.

## Validação

Regressão local do servidor/coletor, lifecycle de credenciais, permissões, CSRF, isolamento por ambiente, revogação, cadastro em lote e preservação de agentes. Interface desktop/mobile e fluxo ZIP anterior verificados. Prova com curl real em HTTPS local: download, checksum, recusa de CA incorreta e bloqueio de redirecionamento. Wget exercitado com downloader simulado; precisa de teste real na distribuição de destino, especialmente Linux legado.

Esta funcionalidade é do dashboard; o código de descoberta do coletor não foi alterado por ela. A versão dos pacotes permanece unificada. Compatibilidade universal com Linux/JVM 32 bits e versões legadas não é declarada.

## Publicar para teste

```bash
python3 "/Users/heriveltosantos/Documents/Testes Pyton/JavaAssessment/output/dashboard-releases/2.0.25/deployment/publish-dashboard.py"
```

Após publicar, abra **Environments → Instalar via terminal**. Em uma VM de teste, execute o comando copiado, instale usando o comando impresso e confirme o cadastro no ambiente. Renove o acesso, confira que o comando antigo é recusado e que o agente cadastrado continua comunicando.

## Agentes

**Atenção: atualize também os agentes.** Publicar o dashboard não atualiza os agentes das máquinas. Abra **Revisar agentes** para conferir versão e elegibilidade. O catálogo preserva os perfis conhecidos de migração a partir de 1.5.12 e o caminho V3; não presume compatibilidade só pela versão numérica. Use o painel para instalações elegíveis. Instalações desconhecidas ou sem suporte funcional precisam do pacote completo do ambiente e `--system-root`, preservando identidade, configuração e CA. Acompanhe em **Work requests**.
