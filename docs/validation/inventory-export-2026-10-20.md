# Validação da exportação ZIP — 20/10/2026

Registro da primeira implementação, substituída pelo formato de arquivo único JSON/CSV. Veja a revisão em [validação da 2.1.16](console-release-2.1.16.md).

Alteração de dashboard em desenvolvimento sobre 2.1.14, sem incremento de versão, build de instalador ou publicação remota.

## Resultados

- `ops/test-source.sh`: aprovado; inclui os novos testes Java e JavaScript. Saída final: `Source regression checks passed; heavy stress and external hosts were not exercised.`
- `InventoryArchiveTest`: oito coleções e manifesto, inventário vazio, contagens, vínculos, JAR/versão e decisão por PID, informações de hardware/cobertura, exclusão de campos sensíveis e remoção de credenciais em textos permitidos, limites de bytes/registros e identificação.
- `InventoryArchiveHttpTest`: administrador recebe ZIP; anônimo e leitor são bloqueados; método, cache e nome do download; exportação global; limite de concorrência; remoção de temporários e liberação do limite após falha. Fixtures em loopback e banco descartável.
- `tests/inventory-export-ui.test.cjs`: clique duplo, geração em andamento, erro do servidor, resposta com tipo incorreto, mudança de sessão e cancelamento do componente.
- `tests/inventory-export-browser.cjs`: Chrome com fixture Java/SQLite local, aba de Administração, download real, teclado, telas de 1440×1100 e 390×844 sem transbordamento horizontal ou erros JavaScript. O teste bloqueia requisições externas.
- ZIP baixado: CRC de todas as entradas aprovado; JSON UTF-8 legível; contagens do manifesto coincidem com as listas; credenciais conhecidas da fixture ausentes.

Evidências locais: `tmp/inventory-export-source-tests.log` e `tmp/inventory-export-ui/` (capturas e ZIP de fixture). Não fazem parte dos instaladores publicados. Não houve validação com dados de cliente nem teste de carga em frota de um milhão de registros; os limites de recusa foram exercitados com parâmetros menores em teste isolado.

O exportador preserva os dados técnicos selecionados e não é um mecanismo de anonimização: nomes, endereços, caminhos e destinos de banco continuam identificáveis, conforme explicado na tela e no guia.
