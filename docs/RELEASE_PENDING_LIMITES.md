# Limites de cadastro — escopo consolidado

## O que muda

- **Sem teto fixo de máquinas cadastradas.** O cadastro não recusa mais a partir de 1.000 máquinas.
- **Códigos esgotados não ocupam vaga.** Um código com todos os usos consumidos sai da contagem de códigos ativos. Ele é preservado enquanto não expira, permitindo repetir o cadastro. Códigos expirados, revogados ou de ambientes excluídos são limpos na emissão seguinte.
- **Teto de códigos ativos: 500 → 10.000.**
- **Usos por código: 1.000 → 100.000.** Vale para o código de cadastro manual e para o pacote de instalação por ambiente.
- **Limite de cadastros por minuto por IP** passa a ser configurável em `conf/server.properties` com a chave `registrationPerMinute` (1 a 100.000, padrão 600). Antes era fixo em 30. Conta tentativas, inclusive recusadas; mudar a configuração requer reiniciar o dashboard.

- Formulários aceitam até 100.000 usos; a lista administrativa consulta páginas de 100 códigos, com escopo de ambiente aplicado antes da paginação e sem expor credenciais.

## Compatibilidade

- Sem mudança de esquema. Inventários, máquinas e identidades existentes são preservados. Apenas códigos inelegíveis são removidos durante a emissão de novos códigos.
- Agentes 2.1.20 continuam funcionando: a API de cadastro não mudou. Apenas recusas que antes aconteciam por limite deixam de ocorrer.
- Não é preciso atualizar agentes para receber esta mudança.

## Limites mantidos

- Corpo de requisição, memória do serviço, workers e fila HTTP.
- Limite de 4.096 IPs na tabela de tentativas de cadastro.
- Lotes de coleta e de atualização de agentes (até 1.000 máquinas por ação).

Consolidado na [release 2.1.22](RELEASE_2.1.22.md), com validação local de atualização direta da 2.1.20.
