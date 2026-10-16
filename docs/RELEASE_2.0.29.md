# Java Assessment 2.0.29 — Correção dos downloads de agentes

Preparada localmente para teste. Corrige o download ZIP tradicional e a geração do comando em **Instalar via terminal**. Não publica nem instala automaticamente.

## Causa e correção

O pacote final inclui `full-update/bundle.zip`, `full-update/manifest.json` e `full-update/manifest.sig`, mas a lista de arquivos aceitos pelo servidor ainda rejeitava esses caminhos. O teste anterior validava o pacote intermediário, antes da inclusão do bundle assinado.

O servidor agora aceita somente os três nomes exatos, exige o conjunto completo e mantém os limites de tamanho, quantidade e caminhos. ZIPs antigos sem esse conjunto continuam aceitos. Nenhum arquivo de identidade ou configuração existente é alterado.

O gate de release testa o pacote **depois** da incorporação do bundle, tanto pela validação do instalador quanto pelos endpoints reais de download ZIP e acesso permanente em HTTP local. Os bytes dos componentes assinados são preservados no download configurado.

## Publicação e verificação

```bash
python3 "/Users/heriveltosantos/Documents/Testes Pyton/JavaAssessment/output/dashboard-releases/2.0.29/deployment/publish-dashboard.py"
```

Depois, recarregue a página e teste **Download agent** e **Instalar via terminal → Ativar acesso de instalação**. A correção está no dashboard: reinstalar o agente não resolve nem é necessário para corrigir os botões.

**Atenção: atualize também os agentes** para manter as versões alinhadas. Publicar o dashboard não atualiza os agentes automaticamente. Use **Revisar agentes**: atualização pelo painel para instalações elegíveis (perfis conhecidos a partir de 1.5.12 e V3); instalador completo do ambiente com `--system-root` para as demais, preservando cadastro, configuração e CA. Acompanhe em **Work requests**. A elegibilidade depende dos componentes assinados, não somente da versão.

A validação desta entrega é local; não declara compatibilidade universal com distribuições Linux ou Java de 32 bits. O fluxo remoto deve ser confirmado após publicar, sem remover a opção de ZIP tradicional.

## Empacotamento

A tentativa local 2.0.27 foi interrompida, não publicada, ao atingir o limite antigo de 16 hashes conhecidos do helper. As ferramentas de compilação e assinatura agora compartilham um limite de 32 hashes explícitos, sem curingas, duplicatas ou remoção dos hashes anteriores. A validação independente continua comparando a lista exata contra os pacotes previamente autenticados. O protocolo e os serviços das máquinas não foram alterados por esse ajuste.
