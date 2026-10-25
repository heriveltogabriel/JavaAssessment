# Cadastro em escala — desenho aprovado

Concluir a branch remover-limite-maquinas, mantendo seu protocolo de cadastro e sem mudar o coletor. O usuário aprovou corrigir os três problemas encontrados e publicar nova versão para testar a atualização pela console sobre 2.1.20.

- Formulários acompanham o limite do backend: 100.000 usos por código.
- Na emissão, limpar códigos expirados/revogados/de ambientes excluídos antes de contar os ativos. Preservar esgotados não expirados e identidades já registradas. Essa limpeza não depende de alcançar 10.000 códigos.
- GET administrativo de códigos, com páginas de 100 e escopo de ambiente antes de LIMIT/OFFSET. Interface anterior/próxima; consulta não devolve hashes nem tokens. Manter resumo legado limitado para compatibilidade. Respostas antigas não atravessam fechamento ou troca de ambiente.
- Testar limite exato, retry, limpeza abaixo do teto, paginação além de 500, isolamento, autorização, controles da interface e regressão completa.
- Após testes, reservar nova release, produzir três ZIPs assinados/checksums e validar atualização/restauração da 2.1.20 em fixture isolada. Não sobrescrever 2.1.20 nem instalar remotamente.

Agentes 2.1.20 permanecem compatíveis para estas mudanças. Limites de lote, HTTP e memória continuam; não prometer escalabilidade ilimitada.
