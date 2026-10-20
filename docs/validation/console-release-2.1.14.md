# Validação da entrega 2.1.14 — 20/10/2026

## Alteração e validação antes da reserva

Implementada na branch `correcoes-2.1.10`, sobre a 2.1.12. Nenhuma VM foi alterada. A origem do cliente confirmada pelo operador é 2.1.12.

`ops/test-source.sh` passou integralmente ainda com versão 2.1.12: coletor, relatórios, SQLite, autenticação, instalação, atualização e JavaScript. Novos casos cobrem 1.024 entradas exatas, 1.025 com descarte, duplicação no limite, argumentos maiores que 64 KiB, driver depois da antiga entrada 64, recuperação de classpath completo compatível com perfdata parcial, divergência de fontes e vmArgs truncados. O caso de quatro PIDs cobre candidato único, conflito, evidência ausente, arquivo substituído, escape HTML e imutabilidade do resultado original.

```text
WEBLOGIC_PRECEDENCE=PASS checks=25
JDBC_PROBABLE_DISPLAY=PASS four-PID agreement conflicts missing evidence replaced files escaping immutable
Source regression checks passed; heavy stress and external hosts were not exercised.
```

## Pacotes finais

- Build `--lab-canary` aprovado: ponte interna 2.1.13 e destino 2.1.14, compilação ECJ Java 6 e bytecode major 50. Os testes rodam em macOS/JDK 17, não comprovam Java 6/Linux nativo.
- `DIRECT_V3_UPGRADE=PASS 2.1.12 -> 2.1.14`: manager binário original, bundle final assinado, mesma operação, identidade, CA e âncora preservadas. Adaptadores locais de SO/rede.
- `ORIGINAL_212_VERIFIERS=PASS`: verificadores extraídos do instalador publicado 2.1.12 aceitaram assinatura web, baseline, payload, 32 rotas, chave pública, âncora e classes major 50; 256 arquivos web.
- `FINAL_JAVA_PACKAGE=PASS 2.1.12 -> 2.1.14 -> 2.1.12`: classes Java reais, TLS/SNI, SQLite, identidade e preservação de dados após atualizar/restaurar. Adaptador de processo local, não systemd remoto.
- Instalador extraído: `PRECHECK OK: package, JDK, dashboard URL and TLS identity`.
- Os três ZIPs passaram em CRC e SHA-256; artefatos anteriores preservados.

Logs locais: `tmp/jdbc-long-classpath-source.log`, `tmp/build-2.1.14.log`, `tmp/direct-v3-2112-to-2114.log`, `tmp/original-212-verifiers-2114.log`, `tmp/dashboard-final-2112-to-2114.log`, `tmp/installer-precheck-2.1.14.log` e `output/dashboard-releases/2.1.14/evidence/`.

## Entrega

- `assessment-dashboard-installer-2.1.14.zip`: 92927767 bytes; SHA-256 `13d9f3dbb18228d296a75d448f753c65b3a4d0d8f03d6590c3eeb152630dfcca`.
- `assessment-dashboard-update-2.1.14.zip`: 93215376 bytes; SHA-256 `e9fd120eada34559ed8d3268a6d44330ce160d84efa16d02f76e7ccf136218ac`.
- `assessment-dashboard-web-update-2.1.14.zip`: 93167808 bytes; SHA-256 `3b41aa24a64cca1ab534cfd8975c6d78fa5add7c39f4c39669201e22983bf480`.

README principal, índice de instalação, README dos pacotes/agente, notas e guia JDBC atualizados. Manuais genéricos revisados quanto ao escopo: o procedimento de instalação permanece igual; nenhum PDF foi alterado nesta entrega.

## Operação

Upload do web-update em Administração → Atualização do dashboard, origem 2.1.12 e destino 2.1.14, confirmação e reinício do dashboard. **Atualizar também os agentes separadamente e solicitar nova coleta.** Não é necessário instalar a ponte manualmente nem reiniciar WebLogic. A tabela aproveita candidatos anteriores; limites novos exigem novo agente. O resultado persistido, o suporte e relatórios/exportações não promovem candidato a versão confirmada.

Amostras do cliente continuam necessárias para validar o resultado operacional. Nenhum deploy remoto foi executado.
