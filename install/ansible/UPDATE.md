# Atualizar agentes existentes com Ansible

Este playbook controla a API do dashboard 2.1.24 a partir do controlador Ansible. Seleciona máquinas pelo inventário/`--limit`, cria lotes de até **5 pedidos**, espera o resultado e confere a versão antes de avançar. **Dentro de cada lote o dashboard atualiza uma máquina por vez**; não são cinco downloads simultâneos. Cadastro, configuração, certificados e verificação dos pacotes continuam sob responsabilidade do atualizador oficial.

Não exige SSH nem Python nos agentes. Eles precisam estar comunicando com o dashboard e possuir uma rota de atualização elegível. Não reinicia serviços parados, não corrige conectividade e não substitui agentes por uma instalação limpa. Instalação/recuperação continuam em `install-agents.yml`.

## Preparar

No controlador Linux/macOS, use Ansible Core com Python 3.9+ (validado em Core 2.19/Python 3.12). Extraia **todos os arquivos** do [pacote Ansible](java-assessment-ansible.zip). Acesso HTTPS direto ao dashboard é necessário; o helper não usa proxies do ambiente e não aceita redirecionamentos ou TLS sem validação.

Antes de aplicar, finalize o cancelamento dos lotes antigos e confira operações em execução/sem confirmação. O playbook bloqueia novas campanhas se encontrar outros lotes ativos. **Opere uma campanha por vez**, inclusive entre operadores/controladores; a API atual não oferece exclusão global atômica.

```sh
umask 077
mkdir -p private
chmod 700 private
cp update-vars.example.yml private/update-vars.yml
cp update-vault.example.yml private/update-vault.yml
cp update-inventory.example.yml update-inventory.yml
```

Preencha `private/update-vars.yml` com URL HTTPS, versão alvo **igual à versão publicada do dashboard**, arquivo PEM da CA e diretório do relatório no controlador. A CA é a mesma usada na instalação. A atualização não usa o token de instalação nem o token individual do agente.

Preencha `private/update-vault.yml` com o **e-mail e senha de um administrador ativo do dashboard**, que já tenha trocado a senha temporária. Criptografe:

```sh
ansible-vault encrypt private/update-vault.yml
# Para alterar depois:
ansible-vault edit private/update-vault.yml
```

Não passe senha por argumento `-e` nem coloque arquivos reais no Git. O playbook envia a configuração ao helper via stdin em tarefa `no_log`. Cookies e CSRF ficam somente na memória do processo; relatórios não incluem credenciais ou respostas brutas da API.

## Selecionar máquinas

Pode reutilizar o inventário da instalação ou usar `update-inventory.yml`. O grupo raiz é `assessment_agents`. O hostname do inventário deve corresponder exatamente ao hostname ou FQDN registrado no dashboard (ignora maiúsculas e ponto final). Se o inventário usa apelido/IP, configure:

```yaml
all:
  children:
    assessment_agents:
      children:
        piloto:
          hosts:
            meu-apelido:
              assessment_update_hostname: host.exemplo.com
              # Se houver nomes repetidos, informe o ID do cadastro desejado:
              # assessment_update_host_id: ID-EXATO-DO-CADASTRO
```

Não escolhe arbitrariamente entre cadastros com o mesmo nome. Não usa correspondência parcial nem identifica um host pelo IP de conexão SSH. ID explícito é autoritativo. Dois aliases para o mesmo cadastro são recusados antes de criar pedidos. `--limit` restringe a campanha, e **nenhuma conexão SSH será aberta**.

## Prévia, piloto e aplicação

A prévia é o padrão e não cria/cancela pedidos de atualização. Chamadas de leitura podem processar expirações normais da fila, como acontece na interface. Comece com um host:

```sh
ansible-playbook -i update-inventory.yml update-agents.yml --syntax-check
ansible-playbook -i update-inventory.yml update-agents.yml --list-hosts --limit piloto

ansible-playbook -i update-inventory.yml update-agents.yml \
  -e @private/update-vars.yml -e @private/update-vault.yml \
  --ask-vault-pass --limit piloto
```

Confira `preview.json` no diretório configurado. Para criar os pedidos:

```sh
ansible-playbook -i update-inventory.yml update-agents.yml \
  -e @private/update-vars.yml -e @private/update-vault.yml \
  -e '{"assessment_update_apply":true}' \
  --ask-vault-pass --limit piloto
```

O comando fica aguardando até a conclusão ou uma condição de parada. Em outra sessão do controlador:

```sh
cat private/update-piloto-2.1.24/report.json
cat private/update-piloto-2.1.24/outcome.json
```

`report.json` é atualizado durante o acompanhamento; `outcome.json` registra o resultado da última execução. Não interprete um outcome antigo enquanto a execução atual ainda está ativa. A prévia escreve `preview.json`; a aplicação escreve `journal.json` e `report.json`. Arquivos 0600, diretório 0700. O relatório pode conter nomes de máquinas e IDs; trate-o como inventário interno.

Após validar o piloto, selecione o próximo grupo e **outro diretório**:

```sh
ansible-playbook -i update-inventory.yml update-agents.yml \
  -e @private/update-vars.yml -e @private/update-vault.yml \
  -e '{"assessment_update_apply":true,"assessment_update_report_dir":"/CAMINHO/PRIVADO/update-producao-2.1.24"}' \
  --ask-vault-pass --limit producao
```

Substitua `/CAMINHO/PRIVADO` pelo caminho real no controlador. Execute antes sem `assessment_update_apply:true` para conferir a nova seleção. **Não use `-f` ou `serial` para tentar aumentar o paralelismo:** o helper controla o envio e o acompanhamento, não o número de workers Ansible.

## Comportamento em falhas

| Situação | Resultado |
| --- | --- |
| Versão igual ou superior ao alvo | Ignora, sem downgrade |
| Revogado/desinstalado, versão desconhecida, sem contato recente | Registra e não cria pedido |
| Cadastro ausente, ambíguo ou duplicado no inventário | Interrompe antes de enviar |
| Lote externo ativo ou operação concorrente no host | Interrompe; não cancela pedidos de outro operador |
| SUCCEEDED | Confere também `collectorVersion` no dashboard |
| EXPIRED/CANCELLED | Registra e permite avançar ao próximo lote; não declara sucesso |
| FAILED/ROLLED_BACK/UNCONFIRMED | Bloqueia campanha e cancela somente WAITING do próprio lote, do fim para o começo |
| Prazo local de acompanhamento excedido | Mesmo bloqueio; não mata processos e não cancela transações iniciadas |
| Rede/TLS/autenticação/erro HTTP | Interrompe, preserva journal para retomada; não repete POST com outra chave |

O estado `finished:true` significa acompanhamento concluído, não sucesso de todas as máquinas. Confira `counts` e os resultados individuais. **O assessment pós-atualização está desativado** para reduzir carga; solicite a coleta depois.

Parâmetros:

| Variável | Padrão | Limites |
| --- | --- | --- |
| `assessment_update_batch_size` | 5 | 1–5 |
| `assessment_update_contact_seconds` | 300 | 60–900; idade máxima do último contato |
| `assessment_update_poll_seconds` | 15 | 5–120 |
| `assessment_update_timeout_seconds` | 1800 | 300–7200, por lote, inclui tempo parado durante retomada |
| `assessment_update_pause_seconds` | 15 | 0–300, entre lotes |

A autorização para iniciar pedidos continua com validade de 60 minutos e o limite de início de 5 minutos por máquina definido pelo dashboard. O timeout do playbook é independente e não amplia esses prazos.

## Retomar e repetir falhas

Use um diretório persistente no controlador; em AWX/Automation Controller, ele deve sobreviver entre execuções do ambiente de execução.

Se o Ansible/controlador parar ou a resposta de criação se perder, execute **o mesmo comando, com a mesma seleção, dashboard, versão e diretório**. A chave idempotente é persistida antes do envio: a retomada acompanha o pedido existente, sem duplicá-lo. Pedidos já concluídos não são recriados. Não altere nem remova `journal.json` para destravar a fila.

Campanhas bloqueadas por falha/resultado desconhecido exigem conferir Solicitações e resolver a condição antes de iniciar uma nova. Para tentar novamente hosts anteriormente sem contato/expirados/cancelados, selecione esses hosts e use um novo diretório, depois de encerrar a campanha anterior. Um lock impede duas execuções no mesmo diretório; para controladores/diretórios diferentes também existe conferência de lotes ativos, mas ela não substitui a regra de um único operador por campanha.

## Validação e limites

Testes automatizados cobrem paginação acima de 1000 hosts, seleção, idempotência, perda de resposta, falha, timeout, cancelamento restrito, confirmação de versão e ausência de credenciais nos relatórios. Integração executa Ansible com API HTTPS simulada e valida cookies/CSRF/CA. **Não houve execução desta automação no cliente nem validação em seus 1500 agentes.** Comece com um host real. Não altera código, versão, unidades ou instaladores do dashboard/agente.
