# Java Assessment 2.0.49

## Inspeção por padrão

A inspeção de arquivos Java passa a vir ativada no padrão geral. Ambientes e máquinas herdam esse valor, preservando as exceções explícitas salvas em cada escopo. Na primeira abertura desta versão, o padrão geral antigo é ativado uma única vez; desativações gerais feitas depois continuam respeitadas nos reinícios. O armazenamento antigo não distingue o valor geral inicial de uma escolha global antiga, portanto esse valor geral também passa a ativado. Outros limites, agendas e inventários não são reescritos; nenhuma coleta é criada por essa migração.

O formulário mostra “Inspecionar arquivos Java” e explica o valor herdado. Personalização só é necessária para exceções.

## Diretórios internos

A varredura ignora os diretórios do coletor, atualizador, desinstalador e runtime privado antes de consumir os limites. O layout V3 em releases/<versão> resolve a raiz correta da instalação. O java-assessment-runtime não entra no inventário de instalações Java, inclusive quando usado por outro processo; a versão da JVM do agente continua reportada. Java compartilhado fora dessas raízes permanece elegível. Nomes parecidos não são excluídos por substring.

Mantidos os limites de 8 MiB de metadados, arquivos, tempo e heap. Aplicações em execução já são examinadas antes da varredura de arquivos.

## Cadastro de máquinas

“Remover do ambiente” passa a “Desvincular do ambiente (manter cadastro)”. Excluir máquina do dashboard permanece uma ação distinta com confirmação. Não deduplicamos nem excluímos automaticamente cadastros com o mesmo hostname/IP: podem representar instalações distintas. Na OL07, a leitura do banco confirmou cadastro antigo revogado e sem ambiente, sem registro de exclusão; o novo cadastro permanece ativo.

## Validação e entrega

Regressões de herança/migração única, exceções por ambiente/máquina, reinício, layouts antigo/V3, JVM privada, limites e aplicações próximas. Suíte HTTP local e navegador com fixtures; compatibilidade Java 6 verificada no build. Não equivale a validação universal de Linux, Java ou 32 bits.

**Atualize também os agentes.** Publicar o dashboard não atualiza as máquinas. Use o painel apenas para instalações elegíveis segundo o pacote assinado; as demais exigem instalador completo com --system-root, preservando cadastro e configuração. As exclusões internas exigem o novo coletor. Acompanhe em Solicitações e execute nova coleta; resultados antigos não são recalculados. Compatibilidade de origem deve ser confirmada pelos verificadores dos artefatos, sem presumir elegibilidade por número de versão.
