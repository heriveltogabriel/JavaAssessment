# Fontes dos PDFs de instalação e operação

Os documentos publicados usam nomes estáveis: `install/Manual.pdf` e `install/Guia-instalacao.pdf`. Não vinculam a capa a uma release. Os exemplos de instalação usam `VERSAO="X.Y.Z"`, preenchida com a versão do pacote baixado; os ZIPs e seus checksums continuam versionados.

## Gerar

Use Python 3 com `reportlab`, `pypdf` e `Pillow`. Defina `ASSESSMENT_PDF_FONTS` para uma pasta contendo DejaVu Sans (regular, bold e mono), Noto Sans (regular e bold) e Rubik (regular e bold). Sem essa variável, os scripts procuram as fontes no runtime local do Codex.

Na raiz do repositório:

```sh
python3 docs/pdf/render-manual.py
python3 docs/pdf/render-guide.py
```

Os scripts produzem os PDFs em `output/pdf/` e copiam os resultados para `install/`. Evidências de texto, comandos e layout ficam em `tmp/pdfs/documentation/`. As quatro ilustrações originais mantidas no manual estão em `assets/`; o fluxo de atualização é desenhado diretamente no PDF com texto selecionável.

Antes de publicar, renderize os dois PDFs com Poppler e confira todas as páginas, comandos, links do sumário e paginação. Os controles de tamanho de texto não substituem a revisão visual. Ao mudar o número de páginas, ajuste o roteiro e os rodapés.

Esta geração altera somente a documentação. Não modifica instaladores assinados, agentes ou dashboards.
