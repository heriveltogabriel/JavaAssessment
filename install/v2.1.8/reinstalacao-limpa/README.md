# Reinstalação limpa do dashboard

Use quando a atualização por script (`assessment-dashboard-update-2.1.8.zip`) não funcionar num dashboard existente, por exemplo um cliente na 2.0.53. O script [`reinstalar-dashboard.sh`](reinstalar-dashboard.sh) remove a instalação atual **com backup completo**, permite instalar a 2.1.8 do zero e, se quiser, traz de volta os dados e o certificado antigos — ou desfaz tudo.

Não altera os agentes nas máquinas. Ensaie uma vez no laboratório antes de usar no cliente.

## Antes de começar

- Acesso root (sudo) ao servidor do dashboard.
- O instalador `assessment-dashboard-installer-2.1.8.zip` copiado e extraído no servidor.
- Espaço livre em `/root` para o backup (o modo `inspecionar` mostra).
- Uma janela de manutenção: os agentes ficam sem comunicação enquanto o dashboard estiver parado.

Copie o script para o servidor e entre nele, por exemplo:

```sh
scp reinstalar-dashboard.sh usuario@servidor:/tmp/
ssh usuario@servidor
```

## 1. Inspecionar (só leitura)

```sh
sudo bash /tmp/reinstalar-dashboard.sh inspecionar
```

Mostra o que está instalado (`/opt/java-assessment-dashboard`, serviços systemd, atualizador, arquivos em `/root`, conta `java-assessment-dashboard`), versão, URL, porta, certificados, arquivos do banco, estado dos serviços, `/health` e espaço livre. Nada é alterado. Guarde a saída: a URL e a porta serão usadas na reinstalação.

## 2. Remover (com backup)

```sh
sudo bash /tmp/reinstalar-dashboard.sh remover
```

Pede que você digite `REMOVER`. Em seguida:

1. Para o dashboard e o atualizador.
2. Cria `/root/java-assessment-backup-<data>/` (acesso só do root) com:
   - `instalacao.tar.gz` — instalação completa, serviços e arquivos de `/root`, com `instalacao.tar.gz.sha256`;
   - `tls/` — `fullchain.pem`, `privkey.pem`, `agent-ca.pem` e `server.properties` avulsos;
   - `conta-uid-gid`, `VERSION` e `conteudo.txt`.
3. Confere que o backup abre e só então remove a instalação, os serviços, o atualizador, os arquivos em `/root` e a conta do serviço — exatamente o que faria o instalador recusar o servidor.

**Guarde o backup**: ele contém a chave privada do certificado.

## 3. Instalar a 2.1.8

No diretório extraído do instalador, escolha uma opção. O comando com os caminhos do backup aparece no fim da remoção.

**a) Mesmo endereço e mesmo certificado (recomendado)** — os agentes continuam confiando no servidor:

```sh
sudo ./install.sh --public-url <mesma URL> \
  --tls-cert <backup>/tls/fullchain.pem \
  --tls-key <backup>/tls/privkey.pem \
  --agent-ca <backup>/tls/agent-ca.pem
```

Depois siga para o passo 4 para manter usuários, ambientes, inventário e o cadastro dos agentes.

**b) Totalmente nova** — nova CA e banco vazio:

```sh
sudo ./install.sh
```

Os agentes perdem o vínculo com o servidor: reinstale-os com o pacote do ambiente baixado no novo dashboard (o playbook Ansible do projeto ajuda nas instalações em lote).

## 4. Restaurar dados e configuração (opcional, após a opção a)

```sh
sudo bash /tmp/reinstalar-dashboard.sh restaurar-dados /root/java-assessment-backup-<data>
```

Para o dashboard novo, guarda os dados da instalação limpa em `/root/java-assessment-instalacao-limpa-<data>/`, coloca de volta `data/` (banco) e `conf/` (configuração, certificado e CA) da instalação anterior, ajusta permissões, inicia o serviço e mostra o `/health`. A 2.1.8 atualiza a estrutura do banco ao iniciar.

Confira login, máquinas e a versão 2.1.8. Os agentes voltam a se comunicar sem reinstalação. Depois, atualize-os pelo painel e solicite nova coleta.

Use a mesma porta da instalação anterior no passo 3: a porta vem do `server.properties` restaurado.

## 5. Voltar à instalação original (desfazer tudo)

```sh
sudo bash /tmp/reinstalar-dashboard.sh voltar-original /root/java-assessment-backup-<data>
```

Se houver uma instalação atual, ela é removida primeiro, com backup próprio (pede `REMOVER`). Depois recria a conta com o mesmo UID/GID, restaura arquivos, permissões e serviços exatamente como estavam e inicia o dashboard anterior.

## Resumo

| Situação | Caminho |
|---|---|
| Atualização por script funcionou | Nada a fazer; reinstalação não é necessária |
| Atualização falhou e quer manter tudo | `inspecionar` → `remover` → instalar (a) → `restaurar-dados` |
| Quer recomeçar do zero | `inspecionar` → `remover` → instalar (b) → reinstalar agentes |
| Algo deu errado na reinstalação | `voltar-original <backup>` |

Os testes do script foram feitos numa cópia isolada da estrutura de diretórios (remover, reinstalar, restaurar dados e voltar ao original com arquivos idênticos). O comportamento com systemd e contas reais deve ser confirmado no ensaio de laboratório.
