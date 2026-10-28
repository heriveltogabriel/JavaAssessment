# Remover agente local

Escopo aprovado: utilitario shell independente, sem nova release nem execucao remota.

Usar desinstalador oficial e verificacoes de propriedade dos servicos; preservar Java, aplicacoes e dashboard. Previa por padrao, remocao explicita. Parar diante de operacoes pendentes, arquivos desconhecidos ou helper ausente. Preservar travas orfas fora da instalacao apos parar servicos e verificar processos.

Implementacao e validacao: reaproveitar verificacoes do gerenciador Ansible; criar wrapper; testar locks vazios, PID vivo, conteudo desconhecido e propagacao de falha; validar sintaxe POSIX. Sem declarar teste real em Linux quando apenas fixtures foram executadas.
