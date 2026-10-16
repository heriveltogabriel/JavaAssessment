# Cenário de homologação: atualização remota sem SSH

Objetivo: habilitar o mecanismo uma vez numa VM de teste e realizar a próxima atualização pelo dashboard, verificando versão, identidade e inventário.

## Preparação

Use uma VM Linux de homologação com saída HTTPS para o dashboard, serviço do agente instalado como root e comando `flock` disponível. Use um ambiente dedicado chamado **Teste de atualização**. Mantenha uma sessão no console da VM durante a primeira validação, para observar o serviço e os logs locais.

As versões de habilitação e destino, os comandos absolutos de publicação e os resultados automáticos serão registrados na entrega final em `output/agent-update/TESTE.md`. Os pacotes não são publicados nem instalados nas VMs pelos testes locais.

## Execução com a VM

1. Publique a versão habilitadora no dashboard pelo publicador do Mac.
2. No ambiente de teste, baixe o ZIP habilitador, transfira-o para a VM e extraia em uma pasta nova. Execute `sudo ./install.sh --system-root` uma vez. Se a identidade já estiver revogada, use uma nova instalação/identidade autorizada; atualizar não reativa credenciais revogadas.
3. Aguarde a máquina conectar. Anote seu ID, ambiente, versão do agente e quantidade de instalações Java. Confirme que a atualização remota é reconhecida como disponível para versões futuras.
4. Publique no dashboard a versão de destino preparada em seguida.
5. Na máquina de teste, escolha **⋮ → Atualizar agente**, confira as versões e confirme.
6. Acompanhe o pedido até concluir. Confira que o agente informa a versão nova, conserva o mesmo ID e ambiente e ainda tem o inventário anterior. Solicite uma coleta para confirmar operação normal.

## Resultados esperados

- Sucesso: mesmo registro, versão nova confirmada pelo agente e coleta posterior funcionando.
- Máquina offline: pedido aguardando; volta a conectar dentro da validade e processa o pedido. Expirado não deve executar.
- Perfil Leitura: não consegue iniciar/cancelar pela interface nem pela API.
- Agente antigo: exige instalação habilitadora, sem tentar executar um mecanismo inexistente.
- Pacote adulterado: recusado antes da troca do JAR.
- Código novo defeituoso: versão anterior restaurada, pedido marcado como restaurado e lote interrompido.

Os dois últimos casos usam apenas o laboratório automatizado com chaves temporárias e arquivos descartáveis. Não adulterar pacotes nem instalar uma versão defeituosa numa máquina de aplicação.

## Evidência

Os testes locais distinguem validação real de assinatura/HTTP/SQLite/JAR de simulação de `systemd`/SysV. O ensaio na VM completa a verificação do gerenciador de serviço real. Não considerar mera ausência de heartbeat como sucesso ou desinstalação.
