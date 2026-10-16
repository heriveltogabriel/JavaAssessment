# Java Assessment 2.0.31 — Download do agente em uma única tela

Preparada localmente para teste; publicação é uma etapa separada.

O botão **Download agent** abre duas abas: **Baixar ZIP**, com as instruções e opções de cadastro temporário existentes, e **Instalar via terminal**, com acesso reutilizável por ambiente. O botão separado de terminal foi removido. A criação de um ambiente abre a mesma tela na aba Terminal.

Na aba Terminal, escolha curl ou wget para copiar o comando ou baixar seu arquivo `.sh`. Transfira o script à máquina de destino e execute `sh nome-do-arquivo.sh`; ele baixa e extrai o pacote, verifica sua integridade e informa o comando de instalação com `--system-root`. A execução usa a versão disponível naquele momento. Proteja o script como uma senha, pois contém a credencial de instalação.

As opções do ZIP são preservadas ao alternar abas. A tela limpa os comandos ao sair da aba Terminal e ignora respostas atrasadas. Renovação/desativação continua exigindo confirmação, com consulta autenticada e proteção CSRF. Scripts não são executados automaticamente.

Inclui a correção da versão 2.0.29 para os dois downloads: o servidor aceita os três componentes exatos do atualizador completo, mantendo a verificação de integridade do pacote final.

## Publicação

```bash
python3 "/Users/heriveltosantos/Documents/Testes Pyton/JavaAssessment/output/dashboard-releases/2.0.31/deployment/publish-dashboard.py"
```

Após publicar, recarregue o navegador e abra **Environments → Download agent**. As mudanças desta tela não exigem reinstalar o agente.

**Atenção: atualize também os agentes** para manter as versões alinhadas. Publicar o dashboard não atualiza os agentes automaticamente. Abra **Revisar agentes**: use o painel nas instalações elegíveis, com perfis assinados conhecidos a partir de 1.5.12 e V3; nas demais, use o instalador completo do ambiente com `--system-root`, preservando cadastro, configuração e CA. Confirme o resultado em **Work requests**. A elegibilidade depende dos componentes verificados, não apenas do número de versão.

Esta entrega é validada localmente; não declara compatibilidade universal com Linux ou Java de 32 bits. Os testes no navegador usam credenciais fictícias e serviços locais descartáveis.
