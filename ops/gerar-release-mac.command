#!/bin/bash
# Duplo clique no Mac: gera a próxima release assinada do Java Assessment (dashboard + agente)
# a partir da branch publicada no GitHub, numa pasta de trabalho fora da Cloud.
# A chave de assinatura nunca sai do Mac. Não publica nada na console.
#   Variáveis opcionais: BRANCH (padrão correcoes-2.1.10), SRC (repositório com output/ e tools/).
set -euo pipefail
BRANCH="${BRANCH:-correcoes-2.1.10}"
REPO_URL="https://github.com/heriveltogabriel/JavaAssessment"
W="$HOME/JavaAssessment-build-release"
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="$HERE/gerar-release.log"
exec > >(tee "$LOG") 2>&1
fail() { echo; echo "ERRO: $*"; echo "Log completo: $LOG"; read -r -p "Enter para fechar" _ || true; exit 1; }

echo "== 1/7 Localizando o repositório com output/ (chave de assinatura e releases anteriores)"
if [ -z "${SRC:-}" ]; then
  for candidate in "$HOME"/Documents/Cloud/JavaAssessment* "$HOME"/Documents/Cloud/*/JavaAssessment* "$HOME"/JavaAssessment* "$HOME"/Documents/JavaAssessment*; do
    [ -f "$candidate/output/agent-update-signing/private-key.pem" ] && [ -d "$candidate/output/dashboard-releases/2.1.10" ] && { SRC="$candidate"; break; }
  done
fi
[ -n "${SRC:-}" ] || fail "não encontrei a pasta com output/dashboard-releases/2.1.10 e a chave; rode com SRC=/caminho/do/repositorio"
SRC="$(cd "$SRC" && pwd)"; echo "  $SRC"
[ -s "$SRC/tools/ecj-3.33.0.jar" ] || fail "falta $SRC/tools/ecj-3.33.0.jar"

echo "== 2/7 Cópia de trabalho em $W (branch $BRANCH)"
if [ ! -d "$W/.git" ]; then
  git clone -q --filter=blob:none --no-checkout "$REPO_URL" "$W"
  cd "$W" && git sparse-checkout set --no-cone '/*' '!/install/'
else
  cd "$W"
fi
git fetch -q origin "$BRANCH" || fail "não consegui baixar a branch $BRANCH do GitHub"
git checkout -q -B "$BRANCH" "origin/$BRANCH"
[ -z "$(git status --porcelain --untracked-files=no)" ] || fail "a cópia de trabalho tem alterações; apague $W e rode de novo"
git log --oneline -1
[ -e output ] || ln -s "$SRC/output" output
[ -e tools ] || ln -s "$SRC/tools" tools

echo "== 3/7 JDK 17 e pré-requisitos"
JDK="${ASSESSMENT_JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
[ -x "$JDK/bin/javac" ] || fail "JDK 17 não encontrado (instale ou defina ASSESSMENT_JAVA_HOME)"
"$JDK/bin/javac" -version 2>&1 | grep -q ' 17\.' || fail "o build espera javac 17 em $JDK"
command -v openssl >/dev/null || fail "openssl não encontrado"
export ASSESSMENT_JAVA_HOME="$JDK" ASSESSMENT_ECJ_JAR="$PWD/tools/ecj-3.33.0.jar" JAVA_HOME="$JDK"
chmod 700 output/agent-update-signing; chmod 600 output/agent-update-signing/private-key.pem
python3 - <<'PY' || fail "pré-requisitos não conferem (veja acima)"
import json, subprocess, sys
from pathlib import Path
problems = []
state = json.loads(Path('release/dashboard.json').read_text())
print('  versão atual no repositório:', state['version'])
for r in state['releases']:
    if r['status'] != 'prepared':
        continue
    base = Path('output/dashboard-releases', r['version'])
    for f in (base / 'packages/package-manifest.json', base / 'deployment/dashboard-update-sources.zip'):
        if not f.is_file():
            problems.append('falta ' + str(f))
derived = subprocess.run(['openssl', 'pkey', '-in', 'output/agent-update-signing/private-key.pem', '-pubout', '-outform', 'DER'], capture_output=True).stdout
if derived != Path('collector/public/agent-update-public.der').read_bytes():
    problems.append('a chave privada não corresponde à chave pública dos agentes')
if problems:
    print('\n'.join('  - ' + p for p in problems[:20])); sys.exit(1)
print('  ok: chave e releases anteriores conferidas')
PY

mkdir -p tmp/release-logs
echo "== 4/7 Suíte de regressão (alguns minutos)"
command -v node >/dev/null || { echo "  aviso: Node.js ausente; testes de interface pulados"; export NODE=true; }
sh ops/test-source.sh > tmp/release-logs/test-source.log 2>&1 || { tail -30 tmp/release-logs/test-source.log; fail "suíte falhou"; }
tail -1 tmp/release-logs/test-source.log

echo "== 5/7 Build da release (reserva ponte interna e versão final)"
python3 ops/build-dashboard-release.py --lab-canary 2>&1 | tee tmp/release-logs/build-release.log || fail "build da release falhou"
VERSION="$(python3 -c "import json;print(json.load(open('release/dashboard.json'))['version'])")"
OUT="output/dashboard-releases/$VERSION"
[ -f "$OUT/packages/assessment-dashboard-runtime-$VERSION.zip" ] || fail "o build não gerou a versão $VERSION"
echo "  versão gerada: $VERSION"

echo "== 6/7 Pacote web-update e instalador"
python3 ops/build-dashboard-web-update.py --release "$VERSION" 2>&1 | tee tmp/release-logs/build-web-update.log || fail "web-update falhou"
python3 dashboard-installer/build.py 2>&1 | tee tmp/release-logs/build-installer.log || fail "instalador falhou"
DEST="install/v$VERSION"; mkdir -p "$DEST"
for f in "$OUT/packages/assessment-dashboard-web-update-$VERSION.zip" "$OUT/packages/assessment-dashboard-update-$VERSION.zip" "dist/assessment-dashboard-installer-$VERSION.zip"; do
  [ -f "$f" ] || fail "não encontrei $f"
  cp "$f" "$DEST/"; (cd "$DEST" && shasum -a 256 "$(basename "$f")" > "$(basename "$f").sha256")
done
(cd "$DEST" && shasum -a 256 -c ./*.sha256)

echo "== 7/7 Commit da release e envio ao GitHub (branch $BRANCH)"
git add --sparse -A release server/DashboardVersion.java dashboard-installer/VERSION collector/AgentTransport.java "$DEST"
git -c user.name="Herivelto Santos" -c user.email="heriveltogabriel@gmail.com" commit -q -m "Release $VERSION: correções da 2.1.10 (pacotes assinados)" || true
if git push -q origin "HEAD:$BRANCH"; then
  echo "  enviado: branch $BRANCH com install/v$VERSION"
else
  echo "  aviso: o envio ao GitHub falhou (credenciais). Os pacotes estão em $W/$DEST"
fi
mkdir -p "$SRC/install/v$VERSION"; cp "$DEST"/* "$SRC/install/v$VERSION/"
open "$W/$DEST" 2>/dev/null || true
cat <<EOF

Pronto: versão $VERSION.
  Console:  $W/$DEST/assessment-dashboard-web-update-$VERSION.zip
  Cópia em: $SRC/install/v$VERSION
Envie o web-update em Administração → Atualização do dashboard, depois atualize os agentes e solicite nova coleta.
EOF
read -r -p "Enter para fechar" _ || true
