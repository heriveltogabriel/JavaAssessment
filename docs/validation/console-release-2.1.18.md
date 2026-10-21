# Validação da release 2.1.18

Validação local em 21/10/2026, a partir de `mapa-bancos` no commit `528bd88` (inclui `479e36b`). Implementações recebidas preservadas; o build alterou somente identificadores de versão do agente/dashboard, VERSION e registro de release. Ponte interna **2.1.17**, final **2.1.18**.

## Resultados

- `ops/test-source.sh`: passou antes da reserva da versão. Inclui exportação, permissões HTTP, equivalência CSV/JSON, interface e driver provável.
- `ops/build-dashboard-release.py --lab-canary`: concluído; compilação do coletor com ECJ e bytecode Java 6. Nenhuma VM acessada.
- `ops/build-dashboard-web-update.py --release 2.1.18` e `dashboard-installer/build.py --with-source`: concluídos.
- Pacote final: `InventoryArchiveTest`, equivalência CSV/JSON e `InventoryArchiveHttpTest` passaram sobre classes extraídas do runtime final.
- Navegador: downloads reais dos quatro formatos, navegação por teclado, desktop/mobile, sem erro JavaScript nem transbordamento horizontal. Cada ZIP contém um único arquivo esperado.
- Excel aberto independentemente com openpyxl: dez abas, cabeçalho congelado, filtros e ausência de células de fórmula. JSON identifica 2.1.18; contagens do CSV conferidas com JSON. Mapa CSV possui as chaves de cruzamento e separador ponto e vírgula.
- Instalador final: `install.py --check --no-package-install` passou com JDK 17 e URL de fixture; pacote e identidade TLS conferidos.
- Verificadores originais da 2.1.14, 2.1.16 inicial e 2.1.16 revisada aceitaram assinatura, baselines e arquivos do web-update 2.1.18. As 32 rotas de migração existentes, chave de confiança e bootstrap imutável foram preservados.
- `tests/dashboard-update-final-package.py`: atualização e restauração **2.1.14 → 2.1.18 → 2.1.14** passaram com classes Java publicadas, TLS/SNI, SQLite e identidade da instalação.
- A mesma prova passou para **2.1.16 → 2.1.18 → 2.1.16**, tanto para a compilação inicial quanto para a revisão de arquivo único. Para a inicial, uma cópia temporária do teste apontou para os artefatos originais preservados; nenhum teste versionado foi alterado.
- ZIPs finais: CRC, hashes dos arquivos internos quando presentes e SHA-256 externo conferidos. Pacotes anteriores preservados.

## Escopo das provas

As atualizações foram exercitadas em ambiente isolado no macOS/JDK 17, usando adaptador de serviço por processo filho. Não equivalem a um canário Linux/systemd ou execução nativa Java 6. Não houve deploy remoto, atualização de agente real nem ensaio de carga pesada. O operador deve testar upload, confirmação, reinício e exportações com sua amostra real.

A rota direta pela console da **2.1.14 ou 2.1.16 para 2.1.18** foi validada nos pacotes finais; não é necessário instalar manualmente a ponte 2.1.17. O instalador completo é destinado a servidor novo.

**Atualizar o dashboard não atualiza os agentes automaticamente.** Não há mudança funcional no coletor nesta entrega. As funcionalidades usam o inventário existente; agentes 2.1.16 não precisam ser atualizados para Excel/Mapa. Para alinhar versões, revisar elegibilidade no painel e atualizar os agentes separadamente.

## Artefatos publicados em install/v2.1.18

| Arquivo | Bytes | SHA-256 |
| --- | ---: | --- |
| assessment-dashboard-installer-2.1.18.zip | 92946244 | `eefeda6daa7ba534666a9fb25d6c6b193381d8e0622214c8c88782f9b79b7860` |
| assessment-dashboard-update-2.1.18.zip | 93265517 | `66415a66f8758c9e4441e68848105bbd9ddc825809790d2a3942049befd08553` |
| assessment-dashboard-web-update-2.1.18.zip | 93220097 | `40080c83361cffdd172db4eff37dce38442204d9269a04d279d5b98eaaf044a5` |

## Evidências locais

Logs em `tmp/mapa-bancos-source-tests.log`, `tmp/build-2.1.18.log`, `tmp/web-update-2.1.18.log`, `tmp/installer-2.1.18.log`, `tmp/mapa-bancos-release-browser.log`, `tmp/export-final-2.1.18.log`, `tmp/installer-precheck-2.1.18.log`, `tmp/original-214-verifiers-2118.log`, `tmp/original-216-verifiers-2118.log`, `tmp/original-216-first-verifiers-2118.log`, `tmp/dashboard-final-2114-to-2118.log`, `tmp/dashboard-final-2116-to-2118.log` e `tmp/dashboard-final-2116-first-to-2118.log`. Downloads e capturas em `tmp/mapa-bancos-release-ui/`; evidências do build em `output/dashboard-releases/2.1.18/evidence/`. Esses arquivos temporários não integram os pacotes públicos.
