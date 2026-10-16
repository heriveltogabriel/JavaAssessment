# Java Assessment 2.0.51

## Comunicação dos agentes nos ambientes

Os cartões de ambiente preservam a última coleta e passam a apresentar a comunicação dos agentes. Contatos recentes aparecem em verde; ausência de contato por cinco minutos ou mais aparece em vermelho. Um clique abre Máquinas no ambiente e estado selecionados, com paginação e busca. O filtro permanece ao recarregar a página e pode ser removido em Limpar filtros.

O dashboard distingue contato recente (menos de 2min30s), atrasado (de 2min30s até antes de 5min) e sem comunicação (5min ou mais). A contagem usa todas as máquinas do ambiente, não apenas a página visível. Revogados, desinstalados, aguardando primeiro contato e horários inválidos são apresentados separadamente. Um horário mais de um minuto no futuro é considerado indisponível.

A atualização periódica existente, a cada 15 segundos com a página visível, reavalia o status. Um novo heartbeat pode devolver a máquina ao grupo conectado sem executar assessment. Ausência de comunicação não comprova que o sistema operacional esteja desligado. Detalhes da cobertura continuam disponíveis nas telas de inventário e máquina.

## Validação

Testes locais de SQLite com mais de 100 máquinas: limites de tempo, escopos, paginação, busca, estados administrativos e retorno por heartbeat. Teste real HTTP/SQLite no navegador: cartões, filtros, recarga, atualização, navegação e layout móvel. Suíte de regressão do projeto e verificação dos pacotes assinados.

## Atualização

**Atualize também os agentes.** Publicar o dashboard não atualiza os agentes automaticamente. Esta melhoria de comunicação usa o heartbeat já existente e fica disponível após atualizar o dashboard, sem depender de uma nova coleta ou atualização do agente. Use **Revisar agentes** para conferir versão e elegibilidade; atualize pelo painel apenas as instalações aceitas pelos perfis assinados. Para as demais, use o instalador completo configurado para o ambiente com `--system-root`, preservando cadastro e configuração. Acompanhe em **Solicitações**.

O pacote inclui os perfis de origem revisados, incluindo o pacote 2.0.49; elegibilidade depende também dos hashes dos componentes de suporte. Verificação local de artefatos não equivale a validação universal de Linux/Java ou 32 bits. Nenhuma máquina remota foi alterada na preparação desta versão.
