#!/data/data/com.termux/files/usr/bin/bash
# Publica uma versão: executa as tarefas T1–T9 de UPDATE.md.
#
#   bash tools/release.sh             # publica de verdade
#   bash tools/release.sh --dry-run   # valida tudo, não commita nem publica
#
# Requer GH_TOKEN no ambiente (escopo repo).
set -euo pipefail

cd "$(dirname "$0")/.."
DRY=0
for arg in "$@"; do [ "$arg" = "--dry-run" ] && DRY=1; done
say() { printf '\n== %s\n' "$1"; }
# Token fora do .git/config: o helper expande GH_TOKEN só no momento do push.
GITC=(-c 'credential.helper=!f() { echo username=x-access-token; echo password="$GH_TOKEN"; }; f')
push_git() { git "${GITC[@]}" push "$@"; }
note() { printf '   %s\n' "$1"; }

say "T1 repositório limpo e sincronizado"
if [ -n "$(git status --porcelain)" ]; then
  echo "   ERRO: há alterações não commitadas. Commite antes." >&2; exit 1
fi
git fetch --quiet origin main
LOCAL=$(git rev-parse HEAD); REMOTE=$(git rev-parse origin/main)
[ "$LOCAL" = "$REMOTE" ] || { echo "   ERRO: main local diferente do remoto." >&2; exit 1; }
note "HEAD $LOCAL"

say "T2 camada dsh-web idempotente"
bash dsh-web/apply.sh --auto --dry-run >/dev/null
note "apply.sh --dry-run ok"

say "T3 patches reproduzem o instalado"
W="$HOME/.cache/sig-dsh-capture/pristine"
DSH="/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh"
if [ -d "$W" ]; then
  V=$(mktemp -d); mkdir -p "$V/node_modules/@deepseek-ai"
  for p in dsh-attachment-local dsh-client-ui-conversation dsh-host-directory-picker-browse \
           dsh-host-directory-picker-auto dsh-session-persistence-jsonl node-addon-system \
           dsh-client-ui-workspace dsh-llm-pi-ai; do
    cp -r "$W/$p/package" "$V/node_modules/@deepseek-ai/$p"
  done
  for f in dsh-web/patches/*.patch; do patch -p1 -s -d "$V" < "$f"; done
  bad=0
  for pair in dsh-attachment-local/lib/index.js dsh-client-ui-conversation/lib/client.js \
              dsh-host-directory-picker-browse/lib/index.js dsh-host-directory-picker-browse/README.md \
              dsh-host-directory-picker-auto/lib/index.js dsh-host-directory-picker-auto/README.md \
              dsh-session-persistence-jsonl/lib/index.js node-addon-system/lib/flock.js \
              dsh-client-ui-workspace/lib/client.js dsh-llm-pi-ai/lib/index.js; do
    pkg=${pair%%/*}; rel=${pair#*/}
    cmp -s "$V/node_modules/@deepseek-ai/$pkg/$rel" "$DSH/node_modules/@deepseek-ai/$pkg/$rel" || { echo "   DIFF $pair" >&2; bad=1; }
  done
  rm -rf "$V"
  [ "$bad" = 0 ] || { echo "   ERRO: patches divergem do instalado (rode capture.sh)." >&2; exit 1; }
  note "10 arquivos iguais"
else
  note "pristine ausente: etapa pulada (rode capture.sh para habilitar)"
fi

say "T4 versão"
if [ "$DRY" = 1 ]; then
  VER=$(node tools/release-api.mjs bump --dry-run)
  note "seria $VER"
else
  VER=$(node tools/release-api.mjs bump)
  note "$VER"
fi
VN=${VER% *}; VC=${VER#* }

say "T5 build e conferência"
if [ "$DRY" = 1 ]; then
  note "build pulado no dry-run"
else
  bash build.sh >/dev/null
  BADGING=$(aapt2 dump badging dist/SIG-Harness.apk 2>/dev/null | sed -n '1p')
  case "$BADGING" in *"versionCode='$VC'"*) : ;; *) echo "   ERRO: versionCode não confere ($BADGING)" >&2; exit 1 ;; esac
  apksigner verify dist/SIG-Harness.apk
  note "APK $VN (versionCode $VC) assinado"
fi

TAG="v$VN"
say "T6 commit e push"
if [ "$DRY" = 1 ]; then
  note "seria commit 'release: $VN (versionCode $VC)' + push"
else
  git add -A
  git commit -m "release: $VN (versionCode $VC)"
  push_git origin main
  note "main atualizado"
fi

say "T7 tag $TAG"
if [ "$DRY" = 1 ]; then
  note "seria git tag -a $TAG"
else
  git tag -a "$TAG" -m "SIG Harness $VN (versionCode $VC)"
  push_git origin "$TAG"
  note "tag publicada"
fi

say "T8 release com o APK"
if [ "$DRY" = 1 ]; then
  note "seria criar release $TAG e anexar dist/SIG-Harness.apk"
else
  node tools/release-api.mjs publish "$TAG"
fi

say "T9 conferência pública"
if [ "$DRY" = 1 ]; then
  note "seria conferir download público"
else
  node tools/release-api.mjs verify "$TAG" dist/SIG-Harness.apk
fi

say "concluído: $VN ($TAG)"
