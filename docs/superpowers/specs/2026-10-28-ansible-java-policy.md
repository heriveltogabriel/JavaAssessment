# Seleção de Java pelo Ansible

## Escopo

Atender ao pedido de seleção dinâmica de Java 8 ou superior de fornecedor diferente de IBM, por grupo, sem depender de um caminho ou update do Java e sem redistribuir agentes saudáveis. A política é opcional: defaults Java 6+ e fornecedor livre preservam o fluxo legado.

## Decisões

- `assessment_java_min_major` define a versão principal mínima, padrão 6.
- `assessment_java_exclude_ibm` filtra `java.vendor` e `java.vm.vendor`, padrão false.
- Um caminho explícito é opcional e estrito; falha não autoriza fallback.
- Ler metadados da JVM após root-trust. Propriedades desconhecidas, execução incompatível ou timeout rejeitam o candidato. Não deduzir fornecedor pelo diretório.
- Descoberta limitada: caminhos comuns, executáveis de processos, profundidade 8, 10000 entradas, até 24 candidatos e timeout por processo.
- Serviços/cadastro só podem ser alterados depois da seleção; agentes saudáveis são preservados antes da busca.
- Recuperação V3 somente reativa serviços; deve validar a JVM persistida. Se não atender, para para revisão.
- Registro TLS interrompido e preservação do UUID são uma questão independente. Manter o bloqueio de resíduos ambíguos.

## Verificação

Testar seleção IBM7/Oracle8, IBM8 excluído, Java17 em local não padrão, vendor desconhecido, candidato sem confiança, caminho explícito sem fallback, identidade incompleta intacta e agente saudável preservado. Exercitar também o playbook real em fixtures locais; não declarar execução no cliente ou correção TLS sem evidência remota.
