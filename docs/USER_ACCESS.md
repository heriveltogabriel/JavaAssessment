# Contas individuais e administração

O dashboard usa nome, email único e senha individual. Há dois grupos fixos: **Administradores** e **Leitura**. Ambos consultam o inventário de todos os ambientes; os filtros de ambiente continuam disponíveis. Leitura pode abrir detalhes e exportar CSV. Administradores também gerenciam coleta, ambientes, agentes, códigos, usuários e acessos.

## Primeiro acesso depois da atualização

1. Abra o dashboard. A configuração inicial pede a senha administrativa que você já usava.
2. Informe seu nome, email e uma nova senha pessoal de 8 a 128 caracteres. A nova senha deve ser diferente da anterior.
3. Sua conta será criada no grupo Administradores. Os próximos acessos usam email e senha.

Essa ativação ocorre uma única vez e preserva o inventário. Reiniciar o serviço não recria o administrador. A senha compartilhada deixa de autenticar assim que a conta individual é ativada. A configuração inicial não pode ser assumida por um visitante sem a credencial administrativa atual.

Em instalações novas, o instalador pede nome/email do primeiro administrador e gera uma senha temporária protegida. A conta já existe antes de o servidor começar a atender. Use o email e a senha temporária fornecidos pelo instalador, e defina sua senha pessoal antes de acessar o inventário.

## Administrar usuários

Abra **Administração → Usuários → Adicionar usuário**. Informe nome, email e grupo. Leitura vem selecionado inicialmente. Confirme sua própria senha para autorizar a criação.

O dashboard mostra uma senha temporária aleatória uma única vez, válida por 24 horas. Copie-a e entregue-a à pessoa por um canal apropriado. Não há envio automático de email. A pessoa deve trocá-la no primeiro acesso, antes de consultar qualquer dado.

Em **Gerenciar**, você pode editar nome/email/grupo, desativar ou reativar a conta, ou gerar uma nova senha temporária. Email, grupo, habilitação e redefinição de senha encerram as sessões anteriores. O último administrador habilitado não pode ser desativado ou movido para Leitura. As contas são desativadas, sem exclusão do histórico.

**Grupos** explica as permissões dos dois grupos fixos. **Atividade** mostra os 200 eventos mais recentes, com a pessoa responsável e data/hora. O banco mantém até 10.000 eventos de acesso/administração; não registra senhas, hashes ou cookies. Administradores não conseguem visualizar a senha pessoal de outro usuário.

## Minha conta e sessões

A barra superior mostra a conta conectada. Abra o menu para consultar Minha conta, trocar sua senha ou sair. A troca de senha encerra outras sessões da mesma conta e mantém seu grupo.

As senhas aceitam frases, espaços e gerenciadores, sem regras de composição ou troca periódica. São armazenadas com Argon2id, salt individual e parâmetros versionados (19MiB, duas iterações, paralelismo1). O cálculo tem concorrência limitada a duas operações. O custo foi medido localmente; confirme o tempo de resposta após publicar no servidor real.

As sessões expiram após 30 minutos sem atividade ou 8 horas de duração máxima. A atualização automática do inventário usa `X-Assessment-Background: 1` e não prolonga a inatividade. O servidor confere conta, grupo, status e revisão de autorização a cada requisição. Há limitação progressiva de tentativas por email e origem, cookies HttpOnly/Secure/SameSite, CSRF e rotação de sessão. As credenciais dos agentes são independentes das contas humanas.

## Atualizar e recuperar pelo Terminal do Mac

Na pasta `deployment` da entrega 1.5.6, execute `python3 atualizar-e-recuperar-acesso.py`. O comando publica a versão com mínimo de 8 caracteres, confirma a versão e abre a recuperação interativa na máquina do dashboard. Usa a mesma chave SSH do publicador. Se a publicação já foi confirmada, pode repetir apenas a etapa de recuperação.

Quando a configuração inicial não foi concluída, informe seu nome e email para criar o primeiro administrador. Se já existem contas, informe o email de um administrador listado. A ferramenta cria backup privado do banco e da configuração, pausa o serviço, gera uma senha temporária e reinicia o serviço. O inventário e os agentes são preservados. A senha temporária aparece no seu Terminal e em um registro privado de root na VM; não é gravada em relatórios no Mac. Entre com ela em até 24h e escolha uma senha pessoal de 8 a 128 caracteres.

## Correção da recuperação na versão1.5.6

Se a recuperação parou com `Arquivo ou pasta precisa ser privado: /root`, use o comando `python3 recuperar-acesso.py` da pasta `output/access-recovery/1.5.6-r2`. A publicação1.5.6 já foi concluída; essa entrada executa apenas a recuperação. Ela mantém as permissões de `/root` e protege a nova pasta de backup com0700 e a credencial com0600. O estado systemd `failed` após a parada só é aceito com todos os processos encerrados, conforme confirmado pelo PID principal, PID de controle e cgroup vazio.

## Recuperar o último administrador na máquina do dashboard

Esta recuperação é operacional e exige acesso ao sistema da máquina. Não existe endpoint público para contorná-la. Use a ferramenta incluída no servidor, como a conta do serviço, para evitar criar arquivos SQLite pertencentes a root:

1. Pare `java-assessment-dashboard.service` e confirme que está parado. A ferramenta também obtém o bloqueio exclusivo do servidor e recusa execução concorrente.
2. Crie um arquivo temporário com a senha temporária escolhida (8–128 caracteres), proprietário `java-assessment-dashboard`, modo `0600` ou `0400`. Use uma pasta privada pertencente à mesma conta; não inclua a senha na linha de comando ou no histórico.
3. Execute com o mesmo Java17+ usado por `run-server.sh` (substitua `JAVA_DO_SERVICO` e `ARQUIVO_PRIVADO` pelos caminhos absolutos verificados):

```sh
sudo -u java-assessment-dashboard JAVA_DO_SERVICO -Xmx128m \
  -Dorg.sqlite.tmpdir=/opt/java-assessment-dashboard/data/native \
  -cp '/opt/java-assessment-dashboard/classes:/opt/java-assessment-dashboard/lib/*' \
  assessment.UserAdminTool \
  --root /opt/java-assessment-dashboard \
  --mode recover \
  --email EMAIL_DO_ADMINISTRADOR_EXISTENTE \
  --password-file ARQUIVO_PRIVADO
```

4. A saída confirma a conta e a validade da senha temporária, sem imprimir a senha. Remova somente o arquivo temporário criado para esta operação.
5. Inicie o serviço novamente. Entre com o email e a senha temporária e faça a troca obrigatória.

A ferramenta recupera um administrador já existente; não eleva uma conta de Leitura. O modo `provision` é usado pelo instalador apenas antes de criar o primeiro administrador. Diretórios/arquivos vinculados por symlink, credenciais com modos inseguros ou proprietário inesperado são recusados.

## Atualização e recuperação de versão

Use o publicador da entrega atual. O banco, as identidades dos agentes e os dados de inventário são preservados. Após ativar contas individuais, o marcador persistente `humanAuthActivated` impede que o atualizador restaure uma versão que aceite a antiga senha compartilhada. Se uma verificação falhar nessa situação, ele mantém o código com suporte às contas e o banco, registra o motivo e tenta restabelecer esse serviço. Não restaure manualmente um código anterior ao suporte de contas sobre um banco já migrado.

O fluxo local prepara os pacotes e as evidências; publicar na VM é uma etapa manual. A versão da entrega é comum ao dashboard e ao pacote do agente. Agentes já instalados continuam reportando sua versão real até serem atualizados.
