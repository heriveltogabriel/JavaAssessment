# Recuperação após reinício e instalador consolidado

Autorizado pelo usuário: corrigir a pendência, gerar instalador e permitir um único pedido de atualização a partir de 2.0.53. Não publicar versões intermediárias.

O bootstrap já assinado permanece imutável. O manager novo, carregado a partir do slot validado, instala um drop-in systemd próprio antes de confirmar a atualização. ExecStartPre valida diretórios e marcador privado, recusa PID ainda vivo e limpa somente recovery.stop antes de iniciar o loop original. O systemd serializa stop/start e encerra o cgroup anterior. Não limpar durante a instalação do drop-in e não reiniciar o recuperador enquanto ele executa a transação. SysV mantém seu start existente. Desinstalação remove somente o drop-in exato conhecido, depois de parar o serviço.

Alternativas rejeitadas: modificar a âncora instalada quebra sua imutabilidade; limpar o marcador pelo coletor periódico poderia desfazer uma parada intencional. O drop-in permanece compatível com a âncora antiga mesmo após rollback do código.

Instalador: APIs compatíveis com Python 3.6, OpenSSL com configuração explícita da CA e SAN, erros sanitizados e timeout, leitura completa do health com versão/storage verificados. Ajuste de entropia apenas na JVM do dashboard. Atualização preserva certificados, dados e identidade; instalação nova não substitui dashboard existente.

Validação: fixtures de marcador ausente/válido/inseguro/PID vivo, propriedade de drop-in e idempotência; Java 6; reinício nativo do recuperador; testes de instalador em Python 3.6 e 3.9 quando disponíveis; pacote final assinado com origem 2.0.53. Uma origem parada precisa de retomada antes do pedido. Não declarar salto direto final comprovado apenas com testes da 2.0.55.
