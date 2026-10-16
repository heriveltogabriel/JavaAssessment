# Versão completa do dashboard

Pedido autorizado: exibir a versão completa e incrementar em cada nova publicação.

O valor atual `1.0 Beta` é uma constante de interface e `/health` também usa `1.0-beta`. O histórico de pacotes tem sufixos diferentes sem alterar esses valores.

Usar `1.0.1-beta` como primeira versão completa e incrementar o patch (`1.0.2-beta`, `1.0.3-beta`). Incrementar ao preparar uma nova entrega, antes da compilação; repetir a transferência do mesmo pacote conserva sua versão. Reiniciar o serviço conserva a versão. Um número reservado por uma preparação que falhou não é reutilizado.

Alternativas consideradas: alterar constantes manualmente é sujeito a esquecimento; usar só a data não atende à sequência de versões; gerar uma versão sequencial no empacotamento atende ao pedido e permite rastrear cada pacote. A última opção será implementada.

`release/dashboard.json` registra a última reserva. `ops/build-dashboard-release.py` reserva o próximo número sob trava, gera `server/DashboardVersion.java` e sincroniza `dashboard-installer/VERSION`. O servidor compilado expõe a versão em `/health`, na exportação JSON e no log inicial. A interface lê `/health` com cache desativado e exibe o mesmo valor no cabeçalho, menu lateral e Sobre. Falha de leitura mostra versão indisponível, nunca um valor antigo inventado.

Pacotes ficam em pastas imutáveis por versão, com versão nos nomes. O publicador manual verifica checksums e a versão exata após a atualização. O atualizador aceita apenas bases conhecidas pelos hashes: CSV confirmado, runtime 1.5.1 preparado anteriormente e entregas sequenciais deste fluxo. Não pressupõe que a tentativa anterior foi concluída. A versão instalada também participa do backup e rollback. O agente disponível continua 1.5.1.

Validação: incremento sucessivo e trava em diretório temporário; HTTP real para versão/health/exportação; navegador real para cabeçalho/menu/Sobre e largura móvel; testes isolados do atualizador para bases conhecidas e versão incorreta; checksums, compilação independente e arquivos de entrega. Preparação local apenas; publicação pelo usuário em seu Terminal.
