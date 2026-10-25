# Instalação direta pelo terminal

O botão **Baixar agente** abre uma única tela com duas abas: **Baixar ZIP**, com o fluxo de cadastro temporário, e **Instalar via terminal**, com acesso permanente por ambiente para baixar diretamente da máquina Linux. Cada aba tem suas próprias instruções de instalação. Não requer SSH no dashboard nem Ansible.

## Como usar

1. Em **Ambientes**, crie um ambiente. O acesso de instalação é gerado e a tela do comando abre automaticamente. Para ambientes anteriores, clique em **Baixar agente → Instalar via terminal → Ativar acesso de instalação**.
2. Escolha **curl** ou **wget** e copie o bloco inteiro, ou use **Baixar curl (.sh)** / **Baixar wget (.sh)**. Transfira o arquivo à máquina de destino, restrinja sua leitura aos administradores e execute `sh nome-do-arquivo.sh` como usuário normal. O arquivo contém o mesmo comando exibido, com uma credencial de instalação: não publique em repositórios e descarte com segurança quando não precisar mais dele.
3. O comando cria uma pasta privada, baixa a versão atual, verifica o ZIP e seus checksums e imprime o comando exato de instalação. Execute esse comando com sudo para instalar com `--system-root`.

Requisitos: acesso HTTPS ao dashboard, `curl` ou `wget` com suporte TLS compatível, `unzip`, `sha256sum` e uma JVM suportada para o coletor. O comando inclui a CA pública do pacote, valida o certificado e não segue redirecionamentos. Não use `-k` ou `--no-check-certificate`. Em Linux legado, a ferramenta do sistema pode não suportar o HTTPS do dashboard mesmo quando o agente Java consegue conectar; nesse caso mantenha o download ZIP e a transferência manual.

O download não executa sudo nem instala Java. O diretório de extração é temporário e exclusivo; o destino do serviço continua `/opt/java-assessment-collector`. Reinstalar usa o comportamento existente: preserva identidade e ambiente atual. Não migra automaticamente uma máquina cadastrada em outro dashboard.

## Validade e revogação

O **comando não expira**. Ele permanece válido até renovação/desativação do acesso, exclusão do ambiente ou alteração do endereço/certificado do dashboard. Baixa a versão disponível no momento da execução; para um lote que precisa de versão idêntica, não publique uma nova versão durante sua instalação.

Cada ZIP obtido recebe uma autorização própria de cadastro de 24 horas e até 100.000 cadastros, sem limite fixo de máquinas do produto. Expirar o ZIP não expira o comando: baixe novamente. O sistema limita dois streams simultâneos e 1.200 emissões por hora por ambiente; os comandos repetem downloads com espera crescente até seis tentativas. Grandes lotes devem escalonar os downloads. O endpoint de cadastro conserva seus limites preexistentes.

- **Renovar acesso de instalação:** gera nova credencial e invalida o comando antigo e novos cadastros pelos ZIPs baixados com ele.
- **Desativar novos cadastros:** desativa esse acesso e os ZIPs associados, sem remover o ambiente.
- **Gerar novo acesso:** reativa com outra credencial.
- Agentes já registrados continuam usando suas credenciais individuais.
- O ZIP baixado pela opção tradicional é independente; seus códigos continuam seguindo a validade/revogação tradicionais.

## Proteção e operação

Somente administradores podem consultar/copiar e administrar o acesso. O comando contém uma credencial: trate-o como senha, inclusive no histórico do terminal e na área de transferência. A credencial não vai na URL nem nos argumentos do processo curl/wget; o bloco grava uma configuração temporária privada, removida ao terminar. O pacote extraído também é privado; remova a pasta de staging depois de concluir a instalação.

A credencial permanente é derivada de um segredo local em `data/.environment-install.key`, com permissão 600 e dono do serviço. O banco guarda apenas o estado e a geração por ambiente. **Preserve banco e chave juntos no backup privado do dashboard**; a chave não deve ser copiada aos agentes nem incluída em instaladores públicos. Se a chave sumir ou ficar insegura, o acesso falha fechado: restaure o arquivo original protegido. O fluxo de ZIP tradicional continua disponível. O publicador desta versão inclui a chave existente no backup privado junto ao banco e preserva o diretório de dados durante a atualização. Backups externos também precisam incluir ambos. A rotação/revogação fica na auditoria administrativa, sem tokens.

A renovação não altera o certificado HTTPS. Se mudar endereço/CA, copie um comando novo pela interface, mesmo que mantenha a credencial. A tela não grava comandos em localStorage e limpa o conteúdo ao mudar para a aba ZIP, fechar ou sair da sessão. As respostas usam `Cache-Control: no-store`.

## Validação desta entrega

Testes locais verificam autenticação/roles/CSRF, download em lote, sobrevivência a reinício, revogação de ZIPs anteriores, manutenção das credenciais operacionais, proteção da chave, limites e concorrência. Os comandos são exercitados com downloaders simulados, incluindo falha, privacidade e checksum; a API é testada em HTTP local descartável. Uma prova com curl real e HTTPS local confirma CA válida, recusa de CA incorreta antes do envio da credencial e ausência de redirecionamento. A interface foi verificada em desktop/mobile, incluindo o download ZIP anterior. Esses testes não substituem a prova com curl/wget e TLS da distribuição do cliente.

## Cadastro em escala

O dashboard não impõe um teto fixo de máquinas cadastradas. Cada código pode permitir até 100.000 usos; os formulários de cadastro e de pacote por ambiente respeitam esse limite. Códigos ativos não esgotados têm limite de 10.000 por categoria (manual/pacote e acesso de instalação). Os códigos esgotados ainda válidos são preservados para repetição do cadastro; expirados, revogados ou de ambientes removidos são limpos ao emitir o próximo código. Credenciais dos agentes já registrados não são removidas por essa limpeza.

**Códigos de cadastro** usa páginas de 100 registros, com Anterior/Próxima e filtro pelo ambiente atual. Somente administradores podem consultar ou revogar códigos; nenhum segredo é devolvido na listagem.

`registrationPerMinute=600`, em `conf/server.properties`, controla as tentativas de cadastro por IP por minuto (1 a 100.000; reiniciar após alterar). IPs compartilhados por NAT/proxy dividem o limite; respostas 429 indicam que o instalador deve aguardar antes de repetir. Os limites de 1.000 máquinas por ação de coleta/atualização permanecem. Remover o teto de cadastro não comprova capacidade operacional ilimitada.
