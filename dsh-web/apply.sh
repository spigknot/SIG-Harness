#!/data/data/com.termux/files/usr/bin/bash
# Reaplica as customizações do SIG Harness sobre a instalação do DeepSeek Harness.
#
#   bash dsh-web/apply.sh --dry-run     mostra o que faria
#   bash dsh-web/apply.sh               aplica os patches e instala o plugin Voz
#   bash dsh-web/apply.sh --profile     também sobrescreve ~/.dsh/profiles/web (com backup)
#   bash dsh-web/apply.sh --force       aplica mesmo com versão do DSH diferente
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DSH="${SIG_DSH_ROOT:-/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh}"
# DSH_PROFILE é exportado pelo harness com o *nome* do profile ("web"), não o caminho.
PROFILE="${SIG_PROFILE_PATH:-$HOME/.dsh/profiles/${DSH_PROFILE:-web}}"
# shellcheck disable=SC1091
. "$HERE/pin.env"

DRY=0; WITH_PROFILE=0; FORCE=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY=1 ;;
    --profile) WITH_PROFILE=1 ;;
    --force)   FORCE=1 ;;
    -h|--help) sed -n '3,8p' "$0"; exit 0 ;;
    *) echo "argumento desconhecido: $arg" >&2; exit 2 ;;
  esac
done

[ -d "$DSH/node_modules/@deepseek-ai" ] || { echo "erro: DSH não encontrado em $DSH" >&2; exit 1; }

INSTALLED="$(node -p "require('$DSH/package.json').version")"
if [ "$INSTALLED" != "$DSH_VERSION" ]; then
  echo "aviso: DSH instalado é $INSTALLED, mas os patches foram escritos para $DSH_VERSION" >&2
  if [ "$FORCE" = 1 ]; then
    echo "       seguindo por --force" >&2
  else
    echo "       rode capture.sh depois de atualizar, ou use --force" >&2
    exit 1
  fi
fi

status=0
for p in "$HERE"/patches/*.patch; do
  name="$(basename "$p")"
  if patch -p1 -R --dry-run -s -f -d "$DSH" < "$p" >/dev/null 2>&1; then
    echo "já aplicado   $name"
    continue
  fi
  if ! patch -p1 --dry-run -s -f -d "$DSH" < "$p" >/dev/null 2>&1; then
    echo "não aplica    $name (base mudou: rode capture.sh)" >&2
    status=1
    continue
  fi
  if [ "$DRY" = 1 ]; then echo "aplicaria     $name"; continue; fi
  patch -p1 -s -d "$DSH" < "$p"
  echo "aplicado      $name"
done

PLUGIN=dsh-web-sig-voice
if [ "$DRY" = 1 ]; then
  echo "copiaria      plugin $PLUGIN para $PROFILE/node_modules/"
else
  mkdir -p "$PROFILE/node_modules"
  rm -rf "${PROFILE:?}/node_modules/$PLUGIN"
  cp -R "$HERE/plugin/$PLUGIN" "$PROFILE/node_modules/$PLUGIN"
  echo "plugin ok     $PLUGIN"
fi

ARTIFACT_SRC="$HERE/artifacts/require_builtin-napi-v9-android-arm64.node"
ARTIFACT_DST="$DSH/node_modules/node-addon-require-builtin/build/napi/napi-v9-android-arm64/require_builtin.node"
if [ ! -f "$ARTIFACT_SRC" ]; then
  echo "aviso: artefato nativo ausente, $ARTIFACT_DST não será instalado" >&2
elif [ "$DRY" = 1 ]; then
  echo "copiaria      addon nativo -> node-addon-require-builtin/build/napi/napi-v9-android-arm64/"
elif [ -f "$ARTIFACT_DST" ] && cmp -s "$ARTIFACT_SRC" "$ARTIFACT_DST"; then
  echo "addon ok      require_builtin.node (já no lugar)"
else
  mkdir -p "$(dirname "$ARTIFACT_DST")"
  cp "$ARTIFACT_SRC" "$ARTIFACT_DST"
  chmod 755 "$ARTIFACT_DST"
  echo "addon ok      require_builtin.node instalado"
fi

if [ "$WITH_PROFILE" = 1 ]; then
  stamp="$(date +%Y%m%d-%H%M%S)"
  for f in cordis.patch.yml cordis.yml package.json pnpm-workspace.yaml; do
    if [ "$DRY" = 1 ]; then
      echo "copiaria      profile/$f"
      continue
    fi
    if [ -f "$PROFILE/$f" ] && ! cmp -s "$HERE/profile/$f" "$PROFILE/$f"; then
      cp "$PROFILE/$f" "$PROFILE/$f.bak-$stamp"
    fi
    cp "$HERE/profile/$f" "$PROFILE/$f"
    echo "profile ok    $f"
  done
fi

echo
echo "reinicie o host web para a interface recarregar:  pkill -f 'dsh web' ; dsh web"
exit "$status"
