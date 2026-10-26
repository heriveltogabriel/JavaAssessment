# Java Assessment 2.1.26 — teste de campanhas

Campanhas persistentes de atualização com revisão paginada, grupos de 10, concorrência global, pausa/retomada e cancelamento em lote. [Notas](../../docs/RELEASE_2.1.26.md) · [Guia](../../docs/AGENT_UPDATE_CAMPAIGNS.md) · [Validação e limites](../../docs/validation/console-release-2.1.26.md).

## Atualizar sua console

1. Baixe [assessment-dashboard-web-update-2.1.26.zip](assessment-dashboard-web-update-2.1.26.zip) e seu [SHA-256](assessment-dashboard-web-update-2.1.26.zip.sha256).
2. Abra **Administração → Atualização do dashboard**, envie o ZIP web-update e aguarde a validação da origem e assinatura.
3. Confira o destino **2.1.26** e confirme **Atualizar e reiniciar** com sua senha.
4. Após o retorno, confira versão, ambientes, inventário e contato dos agentes.

As origens 2.1.20, 2.1.22 e 2.1.24 são o escopo de validação local desta entrega; consulte as evidências antes de usar outra origem. A ponte 2.1.25 é interna, sem instalação manual para agentes V3 elegíveis.

## Testar as campanhas

**Atualizar o dashboard não atualiza os agentes automaticamente. Atualize também os agentes separadamente.** Não houve mudança funcional no coletor nesta entrega; o build alinha sua versão a 2.1.26.

1. Confira e encerre os pedidos pendentes da fila antiga. Instalações já iniciadas não são interrompidas pelo cancelamento.
2. Em **Revisar agentes**, filtre e selecione explicitamente poucas máquinas elegíveis para o primeiro teste. Nenhuma máquina é selecionada automaticamente.
3. Mantenha inicialmente grupos de **10** e concorrência global **2**. A coleta após atualizar é opcional e começa desmarcada.
4. Acompanhe **Solicitações**, confirme a versão final e o contato das máquinas. Teste pausa, retomada e cancelamento da fila.
5. Depois do piloto, selecione as candidatas de todo o filtro para a campanha maior. A fila continua no servidor mesmo com o navegador fechado.

Testes com 5.000 registros sintéticos não garantem o tempo de conclusão no cliente. Ajuste a concorrência conforme carga, rede e resultado do piloto. Resultado desconhecido exige conferência antes de retomar; não inicie campanhas duplicadas.

Agentes 2.1.20/2.1.22/2.1.24 com V3 íntegro entram pela atualização separada do painel, sujeita à elegibilidade real. Instalações sem capacidade/rota compatível precisam do pacote de agente configurado para o ambiente e do instalador com `--system-root`, preservando cadastro, configuração e CA. O instalador do dashboard não serve para instalar agentes.

## Outros pacotes

- [Instalador completo do dashboard](assessment-dashboard-installer-2.1.26.zip) · [SHA-256](assessment-dashboard-installer-2.1.26.zip.sha256): servidor novo, Enterprise Linux 8+ e JDK 17+. Não enviar este ZIP à console existente.
- [Atualização por script](assessment-dashboard-update-2.1.26.zip) · [SHA-256](assessment-dashboard-update-2.1.26.zip.sha256): alternativa por terminal; consulte o README incluído. Para seu teste pela tela, use **web-update**.

Pacotes gerados com `--lab-canary` para teste do operador. Não houve deploy remoto, reboot de host ou validação de carga real durante a preparação. Os pacotes anteriores foram preservados.
