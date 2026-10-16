# Limpeza explícita do inventário

Proposta aprovada pelo usuário: inventário representa o presente; excluir máquinas antigas sem arquivar. Implementar depois dos ajustes visuais da 2.0.51, preservando-os.

## Experiência
Dentro de Java Assessment / Máquinas, atalho “Sem comunicação”. Usar a mesma tabela com máquina, ambiente, IP, agente, último contato e tempo sem comunicação. Períodos: 5 minutos, 24 horas, 7 dias e 30 dias. Incluir credenciais revogadas/desinstaladas com último contato antigo; datas ausentes/inválidas não satisfazem o filtro temporal. Os links dos cartões mantêm seu filtro exato e também oferecem limpeza.

Somente administradores selecionam máquinas explicitamente, no máximo 100 por operação. Selecionar página abrange somente as linhas visíveis. Troca de filtro, ambiente ou página limpa a seleção; atualização periódica remove seleções de máquinas que saíram do resultado. Leitores consultam a lista sem ações destrutivas.

Antes da confirmação, uma prévia do servidor mostra nomes, ambiente, IP, último contato e impedimentos. Confirmar digitando EXCLUIR. Nenhuma exclusão automática. O diálogo congela os alvos; falha de rede não dispara repetição automática.

## Segurança e dados
POST /api/hosts/cleanup/preview e POST /api/hosts/cleanup usam autenticação administrativa, CSRF e transação SQLite. Excluir revalida identidade do cadastro, nome, ambiente e último contato da prévia, limite temporal, credencial existente e ausência de operação pendente. Novo heartbeat, mudança de ambiente/nome, operação ativa ou alvo já ausente resultam em preservação e motivo por máquina. Validação estrutural precede qualquer escrita; erro de banco desfaz todo o lote.

A exclusão remove hosts, inventário, jobs, receipts, schedules, uninstall_requests, agent_update_requests/operations/steps/assessments e referências da seleção/skipped dos lotes; lotes vazios são removidos. Os registros das outras máquinas ficam preservados. A mesma remoção completa vale para a exclusão individual existente. Manter apenas a identidade mínima revogada (hash do token/installationId), e auditoria administrativa já limitada pelo produto, sem inventário arquivado. Não altera serviços nem arquivos do host remoto. Excluir é definitivo no banco atual; backups existentes não são reescritos.

Nunca unir máquinas por hostname ou IP. Instalação nova com ID diferente não deve ser excluída junto com a antiga. Nunca mostrar confirmação de sucesso sem resposta verificável.

## Validação e entrega
Testes HTTP/SQLite e navegador: períodos, escopos, paginação, seleção individual/lote, permissões/CSRF, novo contato antes da confirmação, operação pendente, exclusão completa, lote compartilhado, credencial bloqueada, rollback e persistência. Preparar nova release imutável incluindo os ajustes visuais da 2.0.51. Nenhuma máquina real será excluída durante os testes.
