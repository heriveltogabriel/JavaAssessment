# Java Assessment

Descoberta e inventário do ambiente Java na infraestrutura do cliente. A versão **2.0.53** reúne dashboard, agente Linux e banco SQLite local, com organização por ambiente e máquina.

![Overview do Java Assessment com indicadores de agentes, instalações Java, servidores e datasources JDBC](imagens/overview.png)

## Recursos

- Visão geral com quatro distribuições compactas: Java, sistemas operacionais, servidores por produto/versão e datasources por nome/versão do driver; máquinas em lista com busca e dez itens por página.
- Instalações Java, incluindo runtimes sem compilador quando há evidência de uso por aplicação.
- Aplicações, processos, servidores Java, bibliotecas e datasources JDBC por configuração suportada.
- Contas individuais com email e senha, perfis Administrador e Leitura e troca de senha no primeiro acesso.
- Relatórios de máquinas, Java, servidores, JDBC e ambientes, com filtros combinados, seleção de colunas e exportação PDF/CSV.
- JDBC em todos os relatórios, com datasource, driver e versão apresentados juntos.
- Exportação de um CSV único do inventário por ambiente, máquina ou todos os ambientes.
- Ações de máquina, revogação e desinstalação controlada do agente.
- Atualização de agentes compatíveis por HTTPS, individualmente ou por ambiente, com assinatura e verificação.
- Histórico de coletas e atualizações em **Solicitações**, incluindo progresso, observações e assessment após atualizar.
- Interface em português e indicadores de agentes conectados/desconectados por ambiente.
- Instalação pelo ZIP ou por comandos e scripts curl/wget gerados no dashboard, com acesso de instalação administrado por ambiente.
- Descoberta passiva de Spring Boot, configurações de datasource e drivers; integração opcional para receber a versão real do banco informada pela aplicação.
- Filtro **Sem comunicação** e exclusão individual ou em lote do inventário, com confirmação e revalidação do último contato.

O agente inicia a comunicação HTTPS com o dashboard. Contato e consulta de tarefas são separados da varredura: sem tarefa, não há novo assessment. Os dados permanecem na instalação do cliente.

## Relatórios

A área **Relatórios** transforma o inventário recebido em consultas para análise e compartilhamento. Escolha o modelo, aplique filtros e selecione as colunas antes de exportar.

| Modelo | Principais informações |
| --- | --- |
| Máquinas | Ambiente, IP, sistema operacional, versões Java, servidores e JDBC da máquina |
| Java | Máquina, ambiente, IP, versão, Java home, fornecedor e JDBC da máquina |
| Servidores Java | Máquina, ambiente, IP, produto, nome e versão do servidor, Java associado, sistema operacional e JDBC do servidor |
| JDBC | Datasource/nome JNDI, banco, driver, versão, classe, máquina, IP, ambiente, servidores e aplicações associados |
| Resumo por ambiente | Quantidades de máquinas, instalações Java, servidores e datasources, com resumo JDBC |

Combine os filtros de máquinas e servidores com os de JDBC: presença de datasources, nome/JNDI, banco, driver e versão, classe, referência do driver, servidor, aplicação, tipo de recurso e estado. Os campos oferecem sugestões do inventário e aceitam digitação. Por exemplo, consulte máquinas com determinada versão do WebLogic ou datasources de uma aplicação com um driver específico.

O JDBC de um servidor usa as associações registradas no inventário. Para consultar também os recursos encontrados na mesma máquina, adicione a coluna **JDBC da máquina**. A versão Java associada ao servidor é obtida pela correspondência do Java home registrado. Campos sem evidência permanecem sem informação.

PDF e CSV respeitam os mesmos filtros e colunas, incluindo todas as páginas do resultado. As exportações têm limite de 10.000 registros e 8.192 caracteres por célula; refine os filtros para consultas maiores. Os relatórios não incluem URLs JDBC, senhas ou tokens. O estado de um datasource descreve a evidência coletada e não confirma conectividade com o banco.

## Operação do inventário

Em **Java Assessment → Máquinas → Sem comunicação**, filtre por ambiente e tempo sem contato: 5 minutos, 24 horas, 7 dias ou 30 dias. Administradores podem selecionar máquinas e usar **Excluir do inventário**. A confirmação verifica novamente o contato e preserva máquinas que voltaram a comunicar ou tiveram alterações.

A exclusão remove o cadastro e os dados dos totais, relatórios, exportações e listas de atualização, sem mover a máquina para “Sem ambiente”. Mantém apenas o bloqueio técnico da credencial antiga e a auditoria administrativa limitada. Não desinstala serviços na máquina e não ocorre automaticamente por falta de comunicação. Veja [a documentação da limpeza](docs/MACHINE_CLEANUP.md).

A inspeção de arquivos Java vem habilitada por padrão, respeitando exceções configuradas, limites de tempo e leitura. Diretórios internos do agente, incluindo `java-assessment-runtime`, ficam fora do inventário. A versão da JVM do próprio agente continua visível. Para Spring Boot, consulte [a cobertura passiva e seus limites](docs/SPRING_DISCOVERY.md) e [a integração opcional](docs/SPRING_INTEGRATION.md); a versão do driver não é usada para inferir a versão do banco.

## Componentes e requisitos

| Componente | Função | Requisitos |
| --- | --- | --- |
| Dashboard | Inventário, ambientes, coletas, usuários e administração | Enterprise Linux 8+ compatível, 64 bits, systemd, JDK 17+ |
| Agente | Descoberta com escopo, exclusões e limites configuráveis | Linux e JVM existente compatível; serviço instalado com sudo/root |
| SQLite | Inventário, configurações e histórico | Embarcado; sem serviço de banco separado |

Uma instalação nova do dashboard configura `Xms=Xmx=1536m` e limite do serviço de 2 GiB. O coletor usa `Xms16m` e `Xmx96m`; memória nativa e subprocessos são adicionais. Dimensionamento e cobertura dependem do ambiente real.

## Gerar o instalador a partir do Git

Na raiz deste repositório, com Python 3 e OpenSSL disponíveis:

```sh
# Recupera somente os artefatos públicos assinados do instalador publicado.
unzip -o install/v2.0.53/assessment-dashboard-installer-2.0.53.zip 'dist/*' -d .
python3 -B ops/prepare-beta-dependencies.py --check
python3 -B dashboard-installer/build.py --with-source
```

Os resultados ficam em `dist/assessment-dashboard-installer-2.0.53.zip` e `dist/assessment-dashboard-source-2.0.53.zip`, com arquivos SHA-256. O build usa as bibliotecas fixadas, o agente genérico e o catálogo público assinado incluídos no repositório. Não exige arquivos da máquina de desenvolvimento nem a chave privada de assinatura.

O pacote do agente versionado em `dist/assessment-collector.zip` é genérico e não contém identidade cadastrada. O catálogo extraído em `dist/` inclui os pacotes completos e as rotas de migração. O build verifica assinaturas, chave pública e integridade antes de empacotar. Alterações nos arquivos executáveis exigem uma nova entrega assinada; o build não assina nem substitui a confiança instalada.

## Instalar no cliente

O [diretório install](install/README.md) reúne o [manual ilustrado](install/Manual.pdf), o [guia de instalação](install/Guia-instalacao.pdf) e o instalador pronto da versão 2.0.53, com seu SHA-256. Os PDFs usam nomes estáveis e comandos com `X.Y.Z` para a versão escolhida. Para as funcionalidades mais recentes, consulte também as notas da versão e os guias específicos abaixo.

**Para uma instalação nova.** O ZIP não deve ser executado sobre um dashboard existente para atualizá-lo. Na VM destinada ao dashboard, após transferir o instalador e seu SHA-256:

```sh
sha256sum -c assessment-dashboard-installer-2.0.53.zip.sha256
unzip assessment-dashboard-installer-2.0.53.zip -d dashboard-installer-2.0.53
cd dashboard-installer-2.0.53
./install.sh \
  --public-url https://dashboard.exemplo.com \
  --admin-name "Administrador" \
  --admin-email admin@exemplo.com
```

Substitua o endereço e a identidade pelos valores do cliente. O instalador fornece uma senha temporária e exige sua troca no primeiro acesso. A equipe deve configurar a confiança no certificado HTTPS e permitir acesso à porta 443. O endereço pode ser privado.

Crie os ambientes e use **Baixar agente** no dashboard do cliente. A aba **Instalar via terminal** gera comandos e scripts curl/wget: execute o download como usuário normal e depois o comando com sudo que ele apresentar. O acesso permanece válido até renovação/desativação; trate o script como credencial. A opção **Baixar ZIP** continua disponível. Veja [o guia do acesso de instalação](docs/ENVIRONMENT_INSTALL_ACCESS.md).

Para o ZIP, na máquina monitorada, extraia o ZIP em uma pasta nova e execute:

```sh
sudo /bin/sh ./install.sh --system-root
```

O destino operacional é `/opt/java-assessment-collector`, independentemente da pasta de extração ou do nome do usuário. **Atenção: publicar o dashboard não atualiza os agentes automaticamente.** Use **Revisar agentes** para consultar elegibilidade: instalações V3 recebem atualização completa e perfis legados conhecidos a partir de 1.5.12 podem usar migração pelo painel. A versão sozinha não comprova compatibilidade. Instalações sem suporte elegível precisam do instalador completo configurado para o ambiente, preservando cadastro, configuração e CA. Comece por uma máquina e mantenha o assessment após atualização habilitado. Acompanhe em **Solicitações** e confirme a versão da máquina.

## Desenvolvimento e validação local

Com JDK 17+ e Node.js no ambiente:

```sh
export JAVA_HOME=/caminho/do/jdk17
/bin/sh ops/test-source.sh
python3 -B tests/agent-update-installer-test.py
python3 -B tests/dashboard_installer_test.py
BETA_INSTALLER_RUNTIME_TEST=1 python3 -B tests/beta-installer-test.py
```

As verificações HTTP/HTTPS usam loopback e diretórios temporários. Não atualizam VMs. `ops/test-source.sh` não executa os testes pesados de carga ou de diretórios.

Para compilar o agente com alvo Java 6, configure `ASSESSMENT_ECJ_JAR` com um Eclipse Compiler compatível, como ECJ 3.33.0. Os testes executados em JDK 17 não comprovam execução em Java 6 ou Linux de 32 bits.

Os testes de navegador usam Playwright e Chrome. Configure `NODE`, `NODE_PATH`, `PLAYWRIGHT_MODULE` ou `BROWSER_CHANNEL` conforme o ambiente. `BETA_LOAD=0 /bin/sh ops/test-beta.sh` executa a suíte histórica de interface sem o teste de carga.

## Documentação

- [Processo de release e aviso obrigatório de atualização dos agentes](docs/RELEASE_PROCESS.md)
- [Melhorias planejadas](docs/TODO.md)
- [Plano do atualizador completo a partir da 1.5.12 e assessment após atualizar](docs/superpowers/plans/2026-10-14-full-agent-update.md) — disponível para instalações elegíveis; consulte os resultados e limites de validação nas notas da versão.
- [Visão funcional, operação e limites](docs/SOLUTION.md)
- [Mudanças e validação da 2.0.53](docs/RELEASE_2.0.53.md)
- [Instalação e configuração](dashboard-installer/GUIA_INSTALACAO.md)
- [Opções do instalador](dashboard-installer/README.md)
- [Instalação do coletor](collector/INSTALL.md)
- [Instalação em lote com Ansible e wget, por ambiente](install/ansible/README.md)
- [Arquitetura e contrato HTTP](CONTRACT.md)
- [Reconstrução do pacote de fontes](dashboard-installer/README-SOURCE.md)
- [Atualização completa V3 e assessment posterior](docs/AGENT_UPDATE_V3.md)
- [Validações e gates antes de publicar](docs/validation/full-agent-update.md)
- [Migração e backup](dashboard-installer/BETA_MIGRATION.md)

Os nomes de alguns scripts e documentos mantêm `beta` por compatibilidade com a estrutura anterior. A versão atual do produto é 2.0.53. Relatórios históricos descrevem somente o cenário e a versão em que foram produzidos.

Configurações privadas, chaves, inventário, logs, pacotes de ambientes e ferramentas específicas do laboratório ficam fora do Git. Publicar código neste repositório não instala nem atualiza servidores ou agentes.

## Licença

[MIT](LICENSE). As dependências mantêm suas próprias licenças em [lib/licenses](lib/licenses), com versões e checksums em [dependencies-lock.json](lib/dependencies-lock.json).
