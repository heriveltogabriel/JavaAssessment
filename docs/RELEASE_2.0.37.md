# Java Assessment 2.0.37 — Interface em português do Brasil

Menus, telas de inventário, filtros, configurações, ações, mensagens de erro, estados, instruções de instalação, relatórios e cabeçalhos CSV em português. Datas e números apresentados no padrão brasileiro. Solicitações substitui Work requests; coleta substitui assessment nos textos da interface.

Nomes cadastrados, marcas, versões, comandos, caminhos, identificadores e dados técnicos permanecem como informados. Mensagens técnicas desconhecidas são preservadas para diagnóstico. O nome do produto continua Java Assessment. A tradução é de apresentação, sem migração do banco ou alteração do protocolo dos agentes.

O download do agente continua em uma única tela com as abas **Baixar ZIP** e **Instalar via terminal**, incluindo cópia e download de scripts curl/wget.

## Publicação

```bash
python3 "/Users/heriveltosantos/Documents/Testes Pyton/JavaAssessment/output/dashboard-releases/2.0.37/deployment/publish-dashboard.py"
```

Preparada localmente; não publicada automaticamente. Após publicar, recarregue a página. As traduções do painel não exigem reinstalação dos agentes.

**Atenção: atualize também os agentes** para alinhar as versões. Publicar o dashboard não atualiza os agentes automaticamente. Abra **Revisar agentes**: as instalações com perfis assinados conhecidos a partir de 1.5.12 e V3 podem usar o painel quando elegíveis. Nas demais, use o instalador completo do ambiente com `--system-root`, preservando cadastro, configuração e CA. Confirme a versão final em **Solicitações**. A elegibilidade depende dos componentes verificados, não apenas do número da versão.

Validações locais não equivalem a homologação de todas as combinações de Java/Linux, especialmente arquiteturas de 32 bits.
