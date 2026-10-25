# Limites de cadastro (pendente de release)

## O que muda

- **Sem teto fixo de máquinas cadastradas.** O cadastro não recusa mais a partir de 1.000 máquinas.
- **Códigos esgotados não ocupam vaga.** Um código com todos os usos consumidos sai da contagem de códigos ativos. Ele continua existindo até expirar, para que repetições de cadastro funcionem como antes.
- **Teto de códigos ativos: 500 → 10.000.**
- **Usos por código: 1.000 → 100.000.** Vale para o código de cadastro manual e para o pacote de instalação por ambiente.
- **Limite de cadastros por minuto por IP** passa a ser configurável pelo `conf` com a chave `registrationPerMinute` (1 a 100.000, padrão 600). Antes era fixo em 30.

## Compatibilidade

- Sem mudança de esquema nem de dados. Inventários, máquinas, cadastros e códigos existentes não são alterados.
- Agentes 2.1.20 continuam funcionando: a API de cadastro não mudou. Apenas recusas que antes aconteciam por limite deixam de ocorrer.
- Não é preciso atualizar agentes para receber esta mudança.

## Limites mantidos

- Corpo de requisição, memória do serviço, workers e fila HTTP.
- Limite de 4.096 IPs na tabela de tentativas de cadastro.
- Lotes de coleta e de atualização de agentes (até 1.000 máquinas por ação).
