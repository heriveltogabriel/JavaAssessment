# Proposta: usuários, primeiro acesso e dois perfis

Status: proposta aprovada pelo usuário em 13/10/2026. Implementação local autorizada com workflow paralelo; publicação remota será feita pelo fluxo manual existente. A prévia usa somente dados fictícios.

## Recomendação

Contas individuais locais, guardadas no SQLite do dashboard, com nome, email único, senha e um grupo por usuário. Dois grupos fixos: Administradores e Leitura. Os filtros de ambiente existentes continuam disponíveis; nesta etapa, ambos os perfis têm visibilidade do inventário de todos os ambientes. Não criar grupos customizáveis ou permissões por ambiente sem pedido específico.

Alternativas: grupos customizáveis acrescentam uma matriz de permissões sem necessidade atual; autenticação por provedor corporativo exigiria integração externa. O modelo local com dois grupos resolve o pedido com o menor esforço operacional. Exportação CSV é uma permissão de leitura proposta.

## Primeiro administrador

Na atualização deste dashboard, a credencial administrativa atual autoriza uma única configuração inicial. O responsável informa nome, email e uma nova senha; a conta recebe Administradores. A partir dessa ativação, a senha compartilhada anterior deixa de autenticar. A configuração não fica aberta ao primeiro visitante da URL.

Em instalações novas, nome/email do primeiro administrador e uma credencial temporária protegida são provisionados no instalador. No primeiro login, a troca de senha é obrigatória antes de abrir o inventário. O estado de configuração é persistido atomicamente: reiniciar ou atualizar não cria outro administrador, e duas abas concorrentes não podem assumir a conta inicial.

## Contas seguintes e recuperação

O administrador cria usuário com nome, email e grupo (Leitura selecionada inicialmente). Uma senha temporária aleatória, válida por24horas, é mostrada uma única vez para entrega ao usuário por um canal apropriado. O dashboard armazena somente o hash dessa credencial. Não enviar emails ou integrar SMTP nesta etapa.

O usuário entra com email e senha temporária, define a senha pessoal e só então ganha acesso ao dashboard. Uma sessão de primeiro acesso permite apenas troca da própria senha, consulta mínima da própria sessão e logout. Se a credencial temporária expirar, o administrador gera outra. Redefinir senha encerra as sessões anteriores e exige nova troca no próximo acesso. Admin não vê nem recupera senha pessoal.

Sem MFA nesta etapa: propor senha de15–128caracteres, permitir frases, espaços, colagem e gerenciadores, com confirmação e mostrar/ocultar. Mensagem de erro uniforme para email/senha inválidos. Limitar tentativas por conta e origem, com espera progressiva, sem bloqueio permanente acionável por atacantes.

## Permissões

Administradores: consultar/exportar inventário, executar/coordenar coletas, alterar ambientes/agendamentos/configurações, gerenciar pacotes/códigos de instalação e desinstalação, criar/editar usuários, redefinir senha, alterar grupo e ativar/desativar contas, consultar auditoria de acesso.

Leitura: consultar inventário, abrir detalhes, filtrar/paginar e exportarCSV. Pode trocar a própria senha e encerrar a própria sessão. Não pode executar coletas, modificar inventário/configuração, criar pacotes/códigos, instalar/desinstalar agentes, gerenciar usuários ou acessar dados administrativos sensíveis. Ocultar controles de escrita e também negar chamadas diretas à API. Endpoints GET que entregam autorização/pacotes administrativos também exigem Admin.

O servidor é a autoridade sobre usuário/grupo/status. Credenciais de agentes continuam separadas das contas humanas. DTOs e exportações de leitura não expõem hashes, tokens, códigos de instalação ou dados administrativos de outros usuários.

## Interface

Login: email, senha, mostrar/ocultar, entrar e indicação clara de acesso individual. Primeiro acesso: email identificado e nova senha/confirmar; não permite adiar a troca. Barra superior: iniciais, nome e perfil; menu Minha conta, Trocar senha, Sair. A área Administração aparece para Admin, com abas Usuários, Grupos e Atividade.

Usuários: busca por nome/email e filtros por grupo/status. Colunas nome/email, grupo, status e último acesso; ações editar, redefinir acesso temporário e desativar/reativar. Estados: Primeiro acesso, Ativo e Desativado. O último administrador ativo não pode ser removido, desativado ou movido para Leitura; validação também protege operações concorrentes.

Grupos: dois cartões explicando os direitos e indicando os membros; os grupos não podem ser renomeados/removidos. Atividade: quem fez, o que fez e quando. Registrar criação/alteração/desativação, troca/redefinição de senha, mudanças de grupo, login/logout e falhas relevantes, sem registrar senhas, hashes ou credenciais de sessão.

## Segurança e validação

Substituir o SHA256 simples atual por Argon2id com salt individual e parâmetros versionados, avaliados contra os recursos do servidor. HTTPS, cookies HttpOnly/Secure/SameSite, CSRF e rotação de sessão no login/troca de senha. Propor30min de inatividade e8h de duração máxima. Desativação, redefinição de senha e mudança de grupo invalidam sessões imediatamente; autorização é conferida em cada chamada.

Reautenticar o Admin para concessão de privilégios, redefinição de acesso e mudanças críticas da própria conta. Emails não mudam sem uma operação administrativa autenticada; a troca invalida sessões. Recuperação do último administrador exige um procedimento protegido na máquina do dashboard, sem criar uma porta pública de recuperação.

Testar: primeiro administrador/corridas/reinício; migração do login atual; primeiro acesso e expiração; login genérico contra enumeração; bloqueio real de cada endpoint administrativo para Leitura; troca de senha própria; invalidar sessões; último Admin; recuperação; segredo ausente em resposta/export/log; rollback de autenticação antes de publicar. O rollback não pode reativar inadvertidamente a senha compartilhada antiga.

Na implementação aprovada, o próximo pacote usará a versão comum seguinte do dashboard/agente, conforme o fluxo existente. Esta proposta não incrementa a versão nem altera os pacotes1.5.3 preparados.

Fontes: OWASP Authentication, Authorization, Password Storage e Session Management Cheat Sheets, consultadas em13/10/2026.
