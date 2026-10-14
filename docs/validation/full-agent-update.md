# Evidências da atualização completa

Data: 2026-10-14. Implementação consolidada na branch `codex/full-agent-update-plan`. As primeiras provas abaixo foram executadas com constantes do produto em 2.0.5. Posteriormente foi preparado o candidato exclusivo de laboratório 2.0.9, descrito ao final; nenhuma publicação ou migração real foi executada pelo agente nesta preparação.

## Validações locais

| Prova | Resultado e limite |
| --- | --- |
| Worker original 1.5.12 | 1.338 verificações: binário original carregado isoladamente, manifesto/assinatura, ciclo, ACK perdido, limpeza e recuperação. Serviços/probes/locks do SO são adapters |
| Verificador de bundle | 75 verificações, assinatura e inventário estritos; compilação major 50 |
| Assinatura Python/Java | 60 verificações com chave RSA exclusiva de teste, sem ler chave privada de produção |
| Lifecycle e limpeza | 4.362 verificações em heap de 96 MiB: instalação nova, T→U, dez fases duráveis, escrita/fsync/rename/cópia, espaço insuficiente, rede/ACK e limpeza com referências vivas |
| Desinstalação V3 | 2.896 verificações; snapshots, shim após rollback inicial, intent atômico, CA externa de 48 KiB, remoção parcial e retomada |
| Scripts de serviço | 30 verificações com caminhos e processos/serviços adapters; sem JVM ociosa |
| Escolha de perfil | 109 verificações, worker original e dois perfis assinados, HTTP/SQLite reais; só INCOMPATIBLE_ASSETS antes da troca avança |
| Coordenador SQLite | Reinício/replay, perfil esgotado, cancelamento/revogação/expiração e catálogo inválido sem fallback |
| Migração HTTP | 58 verificações: servidor, autenticação, catálogo assinado e SQLite reais; bridge não conclui pai; confirmação final exige bundle/manager/capacidade/heartbeat |
| Regressões de atualização e histórico | AgentUpdateHttpTest: 149 verificações; WorkRequestsHttpTest: 183; DashboardVersionHttpTest passou |
| Coleta posterior | PostUpdateAssessmentsTest: 65; PostUpdateAssessmentHttpTest: 67. Idempotência, opção, throttle, lease, reinício e retenção |
| Integração completa | 21 verificações: worker original → JAR bridge real → pacote completo → manager promovido → API real → um assessment. A execução de serviços/probes e a identidade do processo promovido são adapters explícitos |
| Navegador | Contrato, API real V1 e API real V3; seleção/refresh/ACK repetido, etapas, vínculos, opt-out, leitor, desktop/mobile |
| Publicação do dashboard | 63 testes locais, incluindo pais de atualização ocupados sem filho V1 e preservação do histórico |

Os testes de filesystem e evolução executam o código real com arquivos e assinaturas reais; serviços, processos do manager e probes do SO usam adapters declarados. O teste integrado não substitui a execução dos serviços Linux. A regressão geral de fontes e a suíte integrada consolidada passaram, incluindo atualização anterior, descoberta/JDBC, permissões, CSV e Work requests.

## Como reproduzir

Use um JDK 17 e o ECJ 3.33.0 para compilar o produto com target Java 6. Os ZIPs originais devem corresponder aos hashes de `agent-migration-baselines.json`. As fixtures nunca devem ser instaladas em uma máquina real.

```sh
export JAVA_HOME=/caminho/do/jdk17
export ASSESSMENT_ECJ_JAR=/caminho/do/ecj-3.33.0.jar
python3 -B tests/build-agent-migration-fixtures.py --output tmp/agent-migration/fixture
python3 -B tests/build-agent-full-update-fixtures.py --output tmp/full-update/http-fixture-final3
python3 -B tests/build-agent-profile-fixtures.py --fixture tmp/full-update/http-fixture-final3 --output tmp/full-update/profile-fixture
/bin/sh ops/test-agent-migration.sh --suite all --fixture tmp/full-update/http-fixture-final3 --profile-fixture tmp/full-update/profile-fixture
python3 -B tests/full-agent-release-test.py --fixture tmp/full-update/http-fixture-final3
python3 -B tests/full-update-installer-test.py --fixture tmp/full-update/http-fixture-final3
python3 -B tests/agent-bundle-service-test.py
```

Os destinos de fixture são imutáveis: se já existirem, use outra pasta e passe o mesmo caminho aos testes. O modo `all` usa separadamente a fixture original `tmp/agent-migration/fixture`. Evidências de execução ficam em `tmp/agent-migration/evidence/` e `tmp/full-update/`; screenshots/JSON de navegador em `output/agent-migration/`. Não são artefatos para distribuição.

## Gates ainda abertos para release

- Executar a migração em Linux com serviços reais systemd e SysV, incluindo saída do coletor, lock global e nova execução do manager.
- Executar com Java 6 real e Java moderno; bytecode major 50 não comprova execução em uma JVM real.
- Reiniciar/interromper uma VM Linux entre as fronteiras duráveis e validar recuperação e nenhuma sinalização de processos de aplicação.
- Medir RSS, espaço de pico e tempo de pausa em Linux. O limite configurado de heap é 96 MiB; ele não é uma medida de RSS total.
- Validar os dois canários originais 1.5.12, um por vez, e uma segunda atualização que altere suporte/manager.

Linux/Java de 32 bits permanecem sem teste real, conforme limitação já informada pelo usuário. Não declarar suporte de produção validado para essa arquitetura.

Acesso remoto por shell não foi usado nesta execução. O [runbook de canários](../runbooks/agent-update-1512-canary.md) descreve a execução necessária no laboratório. Não substituir essas provas por adapters locais nem contornar restrições de acesso remoto.

Detalhes adicionais: [desinstalação V3](agent-v3-uninstall.md) e [escolha sequencial de perfis](agent-profile-fallback.md). A montagem offline passou 16 verificações e o instalador completo passou 13 testes com a fixture final. O builder operacional validou seis origens de migração e dez baselines de helper, sem assinatura de produção.

## Pré-verificação dos dois canários

Em 2026-10-14 o operador executou `ops/validar-canarios.py` nas duas máquinas Linux, em sequência. Ambas informaram o coletor original 1.5.12, instalação `system-root`, capability `update-v1`, timer ativo e helper em execução. Uma usa RHEL 8/Java 17 com helper 1.5.12; a outra RHEL 7/Java 11 com helper 1.5.9. O serviço de coleta inativo entre disparos do timer não indica falha por si só.

Os hashes recebidos foram comparados aos ZIPs originais fixados em `agent-migration-baselines.json`: coletor, helper real, chave pública e os 23 arquivos de suporte conferiram em ambas. As sete classes `AgentUpdateWorker*` dos helpers 1.5.9 e 1.5.12 têm bytes idênticos; isso não substitui a execução da migração com as dependências e os serviços reais. Nenhum arquivo de operação pendente foi detectado e havia mais de 22 GiB disponíveis em cada máquina. Configuração, identidade, CA e seleção do Java tiveram seus hashes registrados privadamente para comparação posterior.

Os endereços, hashes de identidades/configurações e relatórios completos ficam fora do Git, em `output/agent-canaries/`. Essa etapa confirma a origem instalada para preparar o teste; não fecha os gates de migração, recuperação, reboot, confirmação final ou assessment posterior. O dashboard publicado continua em 2.0.5 com o mecanismo anterior.

## Candidato de laboratório 2.0.9

Com a autorização do usuário para testar a primeira VM sem snapshot, foram reservados B=2.0.8 e T=2.0.9. A reserva anterior 2.0.6/2.0.7 foi consumida por uma falha de sandbox ao abrir o servidor HTTP local do teste e não foi reutilizada. O pacote final está marcado `candidateOnly: true`, preserva a chave instalada e permanece sem publicação nesta preparação. O builder operacional usa `--lab-canary`; o marcador persiste no ledger, recibos e documentação embutida. O candidato não equivale à liberação para o cliente.

- As regressões executadas pelo builder passaram, incluindo descoberta, JDBC, autenticação, Work requests, instaladores e publicação.
- `ops/verify-full-agent-candidate.py` conferiu os cinco artefatos finais, assinaturas/pin, bundle, bootstrap, bytecode major 50, seis rotas e helpers conhecidos 1.5.12/1.5.9, sem ler chave privada. O teste negativo adicional passou dez verificações.
- A suíte completa de migração foi repetida com catálogo de teste separado B=2.0.8/T=2.0.9 e evolução U=2.0.10. Passaram origem original, contratos, lifecycle, desinstalação, perfis, servidor, assessment e integração. As ações do SO continuam sendo adapters explícitos.
- Chrome com API/SQLite reais locais confirmou o fluxo do painel 2.0.9, etapas internas, confirmação final, um assessment vinculado, opção desmarcada e layout desktop/mobile; nenhuma resposta de API foi simulada.
- A automação opcional de laboratório passou 16 testes offline: um único host, segredo somente em memória, assinatura/hash final, idempotência, retomada e reinício sem duplicação. A solicitação do primeiro teste real será feita pela interface, conforme preferência do operador.

Pacotes e evidências estão em `output/dashboard-releases/2.0.9/`; fixtures e logs complementares em `tmp/full-update/canary-209-*`. Os gates de Linux real, Java 6/SysV, reinício/interrupção, segundo canário e evolução real continuam abertos.
