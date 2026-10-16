# Validação do atualizador administrativo — 2.1.0

A 2.1.0 está preparada localmente. O dashboard de laboratório continua ativo na 2.0.57; nenhum agente real foi atualizado durante estes testes. O cliente permanece com sua versão atual.

## Preservação

- Commit estável `78fd604dcac3c08690c643280d6047006d41246d`, tag local `backup/v2.0.57-before-dashboard-updater`, instalador e atualizador originais, hashes comparados e Git bundle verificado.
- Backup privado do laboratório: instalação, certificados, configuração, unidade, SQLite por API de backup online com `integrity_check=ok` e chave local de acesso de instalação. Serviço não interrompido. Não é um backup do ambiente do cliente.

## Provas executadas

- 25 testes Python: assinatura RSA/SHA256, adulteração, duplicatas, links, travessia, origens, versões, espaço, operação pendente, cancelamento, revalidação, bibliotecas e classes internas Java, preservação de dados, falha de início e recuperação em todas as 16 fronteiras de troca de componentes.
- HTTP Java real: ADMIN/Leitura, CSRF, origem, streaming limitado, senha de confirmação, fila e manutenção bloqueando tarefas dos agentes sem bloquear health/status.
- Chrome: upload, confirmação, reconexão, sucesso, restauração, cancelamento, escape de conteúdo e integração da aba na Administração. Regressão real de usuários/primeiro acesso/troca de senha/leitura/CSV/logout passou.
- Regressões Java/HTTP/SQLite/JavaScript e verificações da geração da release passaram. 64 testes do atualizador tradicional e oito do instalador passaram.
- OL8, Python 3.6 e systemd: falha de saúde provocada em servidor HTTPS fictício confirma tentativa de retorno automático. Unidade temporária removida ao fim.
- Pacote assinado e classes reais: **2.0.57 → 2.1.0 → 2.0.57**, com SQLite real e HTTPS/CA/SNI verificados, em processo local e na OL8. A prova Linux usa usuário `opc`, unidade systemd temporária, `ProtectSystem=strict`, pasta de dados gravável e `PrivateTmp=true`. O banco e a chave local foram preservados, incluindo uma gravação posterior à atualização. Conferidas as permissões dos arquivos auxiliares SQLite.
- Seis testes adicionais de provisionamento conferem permissões, idempotência, recusa de links e preservação de configurações personalizadas em caminhos temporários, com systemctl simulado.
- A exceção gerada pelo bootstrap para a fila foi exercitada em unidade systemd temporária: o usuário consegue gravar na fila e ler o estado, mas não alterar o estado.
- Agentes: binários originais **2.0.53 e 2.0.57 → pacote final 2.1.0**, mesma operação, cadastro/configuração/CA/âncora preservados. Sistema operacional e rede são adaptadores neste teste; não representa atualização de agente real.
- Verificação pública independente: assinaturas, hashes, cinco artefatos principais, perfis legados e âncora imutável. O instalador foi conferido também pelo `SHA256SUMS` interno e pela correspondência das fontes do executor.

## Evidências e limites

Recibos e logs em `output/dashboard-releases/2.1.0/evidence`, incluindo `public-verification.json`, `final-java-local.log`, `final-java-native-ol8.log` e provas diretas dos agentes. Os builds não publicados rejeitados durante validação ficaram arquivados separadamente; nenhum pacote publicado foi substituído.

A prova isolada inclui uma pasta de manutenção vazia na origem 2.0.57 para modelar o pré-requisito do executor. Não significa que uma 2.0.57 original aceite atualização pelo navegador. A primeira adoção da 2.1.0 usa o script tradicional, e a instalação completa do executor no dashboard real será conferida no primeiro deploy de laboratório.

Interrupções usam injeção de falhas, não corte elétrico. Não houve teste no cliente, migração de dados reais nem atualização automática de agentes. O pacote preserva o banco atual no retorno; não promete recuperação sem acesso ao servidor quando o próprio dashboard não consegue iniciar.
