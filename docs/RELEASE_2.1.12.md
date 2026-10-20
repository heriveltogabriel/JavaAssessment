# Java Assessment 2.1.12

Release da branch `correcoes-2.1.10`, incorporando as correções dos commits `dfd37b1` e `6a553dd` sem alterações no seu código. [Detalhes das correções](CORRECTIONS_2.1.10_PENDING.md).

## Correções

- `/opt` volta às inclusões padrão e sai das exclusões. Listas já salvas não são migradas: revise ambientes que salvaram `/opt` como exclusão na 2.1.10.
- Tomcat/jsvc reconhece `-java-home`, `-cwd`, `-jvm` e processos renomeados por `-procname`.
- JVM em caminho excluído não bloqueia a decisão JDBC por boot/ext; a limitação permanece como ressalva.
- Configurações externas em `/etc/config` são associadas por nomes normalizados e tokens, evitando associações genéricas indevidas.
- Manifestos assinados usam somente a seção principal, preservando a identificação da versão sem consumir o orçamento com digests por classe.
- Avisos de links/profundidade são agregados; cópias do pacote do agente não entram no inventário.
- `catalina.properties` segue a origem configurada, o base e o padrão embutido. A leitura de cmdline fica restrita aos processos candidatos.

## Atualização

Pacote da console: `assessment-dashboard-web-update-2.1.12.zip`, em **Administração → Atualização do dashboard**. O instalador completo é para servidor novo; o pacote por script atende origens compatíveis sem executor web.

A rota requerida é **2.1.8 → 2.1.12**, sem instalar 2.1.10. A validação dos pacotes finais está nas [evidências](validation/console-release-2.1.12.md). A 2.1.11 é a ponte interna da entrega, não uma instalação manual intermediária.

**Atualize também os agentes separadamente pelo painel**, começando por uma máquina elegível, e solicite nova coleta. Instalações sem rota elegível precisam do instalador completo configurado para o ambiente. Atualizar o dashboard não recalcula inventários anteriores.

Coleta passiva não confirma a classe efetivamente carregada ou a versão de um banco remoto. Os testes locais não substituem validação operacional no cliente. Não foi feito deploy remoto nesta entrega.
