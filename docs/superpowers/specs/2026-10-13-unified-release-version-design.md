# Uma versão para dashboard e agente

Pedido explícito: dashboard e agente precisam usar a mesma versão. A separação anterior dos contadores é substituída por uma versão comum de entrega.

A entrega comum validada será1.5.3, avançando a partir da maior versão existente (agente1.5.1; dashboard1.0.2-beta);1.5.2 foi reservada durante a validação local e não será publicada. As próximas serão1.5.4,1.5.5. Todas sem sufixo beta. Manter dois contadores sincronizados manualmente seria sujeito a erro; usar somente um rótulo visual não versionaria os binários. Será usado o contador único existente, que gera e valida as constantes de ambos os produtos.

O gerador reserva o próximo patch considerando a maior versão existente; gera DashboardVersion.java e AgentTransport.VERSION, sincroniza VERSION do instalador, recompila servidorJava17 e agenteJava6, e coloca o mesmo valor nos manifestos, DEPENDENCIES.json e nomes de ZIP. Novas entregas sempre geram os dois binários, mesmo quando a alteração funcional ocorreu em apenas um componente. Reenvio de uma entrega mantém sua versão. Não reescrever releases anteriores.

A publicação verifica também a versão do pacote do agente, usando informação autenticada no servidor; exige igualdade com /health e a versão da entrega. O contador/código/timestamp/manifestos/nomes são conferidos antes de empacotar. Bibliotecas TLS, scripts de instalação e CA continuam vindo da base pública verificada; a instalação nas VMs continua manual, com identidade preservada.

Agentes já instalados reportam a sua versão real até serem atualizados. Não alterar os dados históricos das máquinas para simular atualização. Instruções de entrega explicam publicação do dashboard, download do ZIP do ambiente e instalação root na VM.

Validação: migração1.0.2+1.5.1→1.5.2 e incremento comum seguinte; rejeitar geração com versões divergentes; ler a versão por reflexão do AgentTransport no JAR real, conferir manifesto/metadata/nomes e comparar com health/info/download autenticados; testar regressões de agente/coleta e de publicador; navegador local em desktop/celular. Preparar todos os pacotes antes de entregar o comando manual.
