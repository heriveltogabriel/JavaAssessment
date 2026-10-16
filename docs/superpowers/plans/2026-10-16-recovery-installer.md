# Recuperador e instalador — plano de implementação

**Goal:** uma entrega corrigida, com atualização direta da 2.0.53.
**Architecture:** extensão systemd de propriedade verificada, instalada pelo manager novo; âncora preservada. Instalador independente do runtime antigo.
**Tech Stack:** Java 6, shell POSIX, systemd, Python 3.6+, OpenSSL.

- [x] Criar AgentRecoveryService e teste: drop-in constante, caminhos fixos, verificação de propriedade/conteúdo, escrita durável e daemon-reload; recusar customizações. Integrar em Production.serviceEvidence antes da confirmação; remover pelo helper de uninstall depois da parada.
- [x] Executar teste de script de partida em Linux: marcador inexistente/válido, links, modo inseguro, PID vivo; manter marcador quando recusar. Executar restart nativo e verificar identidade/âncora/único processo.
- [x] Substituir capture_output no instalador por stdout/stderr PIPE. Usar configuração explícita CA/server e validar SAN/cadeia com testes reais OpenSSL. Aplicar ajuste de entropia ao launcher e verificar health completo e limitado.
- [x] Validar regressão de migração e instalador; verificar compatibilidade e preparar roteiro para recuperador antigo parado. Incrementar somente após validação. Montar pacote final e repetir assinatura/integridade/testes de instalação e salto direto.
- [x] Entregar ZIP/checksum/notas e comandos separados para dashboard existente e novas instalações. Informar limites comprovados e agentes atualizados separadamente. Não publicar no cliente nem enviar ao Git sem pedido.

Limites registrados: reboot completo/corte de energia, instalação integral em servidor vazio e atualização nativa do dashboard do cliente não executados; canário final antes de ampliar o lote. Publicação/Git não realizados nesta geração.
