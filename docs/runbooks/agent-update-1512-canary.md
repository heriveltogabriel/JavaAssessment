# Canários de atualização a partir da 1.5.12

Objetivo: validar duas instalações originais, uma por vez, antes de entregar o mecanismo ao cliente. A release final T e o bridge B só serão reservados após os testes locais e de serviços Linux. Os números usados nas fixtures não são versões publicáveis.

## Preparação

1. Identificar no dashboard as duas máquinas que ainda informam 1.5.12. Conferir hostname, ambiente e ID; não deduzir isso de uma lista antiga de IPs.
2. Fazer snapshot recuperável da VM. Confirmar espaço para pacote, extração, geração ativa/anterior e recuperação. Não apagar backups para ganhar espaço durante a prova.
3. Confirmar ausência de coleta RUNNING, atualização ou desinstalação pendente. Registrar um inventário antes do teste.
4. Guardar checksums da configuração, identidade, CA efetivamente indicada e `java.path` em pasta privada de evidência. Não colar a configuração ou token em logs/chats.

Para coletar essa pré-verificação pelo Terminal do Mac, na raiz do projeto:

```sh
python3 ops/validar-canarios.py
```

Informar os IPs de até duas máquinas. O script usa por padrão `~/OneDrive/id_rsa`; `--identity` e `--user` permitem outra chave e usuário. Para uma máquina RHEL 5 que exija algoritmos SSH antigos, executar separadamente com `--legacy`. A coleta usa `sudo -n`, exige a chave de host conhecida ou confirmada no Terminal e salva relatórios privados em `output/agent-canaries/`. Ela não instala, reinicia ou executa o coletor. É necessário conferir o resultado e as operações atuais no dashboard antes do teste de migração.

Na máquina, comandos de leitura:

```sh
sudo unzip -p /opt/java-assessment-collector/collector.jar META-INF/MANIFEST.MF
sudo unzip -p /opt/java-assessment-update/helper.jar META-INF/MANIFEST.MF
sudo cat /opt/java-assessment-collector/conf/update-capability
sudo cat /opt/java-assessment-update/scheduler
df -Pk /opt
sudo sha256sum /opt/java-assessment-collector/conf/collector.properties \
  /opt/java-assessment-collector/conf/java.path
```

Conferir o serviço conforme o scheduler:

```sh
# systemd
sudo systemctl status java-assessment-collector.timer java-assessment-update.service --no-pager

# SysV, inclusive quando "service" não está no PATH
sudo /bin/sh /opt/java-assessment-collector/root-service.sh status
sudo /bin/sh /opt/java-assessment-update/update-service.sh status
```

Registrar os processos das aplicações antes/depois, incluindo PID e horário de início. Não registrar argumentos completos: podem conter segredos da aplicação.

## Publicar e executar o canário A

1. Preparar a release T com seu B interno e origens fixadas por checksum. Validar o instalador e o catálogo público do pacote. Publicar somente o dashboard e conferir saúde/versão.
2. Abrir **Revisar agentes**, selecionar somente A e confirmar **migração com verificação local pendente**. Manter **Executar assessment após atualizar** marcado.
3. Acompanhar o mesmo Work request: transição, pacote completo, instalação, verificação, confirmação e coleta. O bridge B não deve aparecer como sucesso final.
4. Confirmar versão T e manager pelo diagnóstico novo:

```sh
sudo /bin/sh /opt/java-assessment-collector/agent-tool.sh version
```

5. Repetir os checksums anteriores. Identidade, ambiente, configuração, CA, Java selecionado e spool pendente devem permanecer corretos.
6. Confirmar **um** assessment vinculado, com o escopo esperado. Conferir dois contatos de polling posteriores e a continuidade do agendamento normal.
7. Conferir recuperação ativa e o coletor:

```sh
# systemd
sudo systemctl status java-assessment-recovery.service java-assessment-collector.timer --no-pager

# SysV
sudo /bin/sh /opt/java-assessment-update/bootstrap/update-bootstrap.sh status
sudo /bin/sh /opt/java-assessment-collector/root-service.sh status
```

## Canário B e evolução seguinte

Só iniciar B após A estável. Deixar previamente uma coleta QUEUED, sem lease, e confirmar que a atualização a aproveita após T, sem criar outra.

Preparar uma segunda release U que altere manager e pelo menos um script/biblioteca. Atualizar A pelo painel e depois B; em uma das operações desmarcar o assessment. Confirmar que nenhuma coleta extra é criada nesse caso. Conferir código ativo/anterior e retenção/limpeza com inventários; arquivos desconhecidos devem permanecer intactos.

Executar também reboot e interrupções controladas em VMs descartáveis systemd/SysV. Essa prova precede o uso em produção; uma rodada de testes em objetos Java não a substitui.

## Se não concluir

- Abrir os eventos da máquina no Work request e o diagnóstico local em `/opt/java-assessment-update/state/`.
- Não apagar journal, ponteiros, bootstrap, configuração ou runtime Java para forçar outra tentativa.
- Se a rede falhar após a verificação local, restaurar conectividade e deixar a confirmação continuar. Não declarar rollback apenas pela ausência de resposta.
- Se houver falha local, conferir a restauração e o diagnóstico antes de criar novo pedido.
- Se o perfil de suporte for desconhecido, usar o instalador completo configurado para o ambiente, preservando a identidade, após resolver a operação existente.

**Atenção: atualize também os agentes.** Publicar T não atualiza as máquinas automaticamente. A elegibilidade depende dos perfis assinados e da instalação protegida, além da versão declarada.
